"""The Gymnasium environment: the agent is a market maker in the simulated market.

Each step (= 1 simulated second):
  1. the agent picks how far from the market's mid to put its bid and its ask
  2. the account moves its two quotes there
  3. the market steps: fair value moves, noise and informed traders act
  4. the agent is rewarded: change in its mark-to-market value, minus an inventory penalty
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

import gymnasium as gym
import numpy as np

from mmrl.agent import AGENT_OWNER, MarketMakerAccount
from mmrl.market import Market, MarketParams
from mmrl.types import Side


@dataclass(frozen=True)
class EnvParams:
    market: MarketParams = field(default_factory=MarketParams)
    episode_steps: int = 1_000       # steps per episode (1 step = 1 simulated second)
    warmup_steps: int = 300          # market runs alone first, so the book is full when we join
    order_qty: int = 2               # size of each of our quotes
    max_offset: int = 5              # quotes go 1..max_offset ticks from the mid
    max_inventory: int = 20          # at +/- this, stop quoting the side that would grow it
    inventory_penalty: float = 0.01  # eta in: reward = change in value - eta * inventory^2


N_FEATURES = 9
OBS_LIMIT = 10.0                     # features are scaled to roughly [-1, 1]; clip outliers here


class MarketMakingEnv(gym.Env):
    metadata = {"render_modes": []}

    def __init__(self, params: EnvParams | None = None) -> None:
        super().__init__()
        self.p = params or EnvParams()
        k = self.p.max_offset
        # action a -> (bid offset, ask offset), each in 1..k ticks: k*k combinations
        self.action_space = gym.spaces.Discrete(k * k)
        self.observation_space = gym.spaces.Box(-OBS_LIMIT, OBS_LIMIT, shape=(N_FEATURES,), dtype=np.float32)
        self.market: Market | None = None
        self.account: MarketMakerAccount | None = None

    # ---------- action decoding ----------

    def decode_action(self, action: int) -> tuple[int, int]:
        """Action number -> (bid offset, ask offset) in ticks. 0 -> (1,1), 1 -> (1,2), ... k*k-1 -> (k,k)."""
        k = self.p.max_offset
        return 1 + action // k, 1 + action % k

    # ---------- the market as the agent sees it ----------

    def reference_mid(self) -> float:
        """Mid of the book WITHOUT our own quotes (we shouldn't measure the market by ourselves)."""
        bid, ask = self._others_best(Side.BUY), self._others_best(Side.SELL)
        if bid is not None and ask is not None:
            self._last_mid = (bid + ask) / 2
        return self._last_mid

    def _others_best(self, side: Side) -> int | None:
        book = self.market.engine.book
        for level in book._levels_best_first(side):
            ours = sum(o.remaining_qty for o in level.orders if o.owner == AGENT_OWNER)
            if level.total_qty - ours > 0:
                return level.price
        return None

    def _observation(self) -> np.ndarray:
        p, book = self.p, self.market.engine.book
        mid = self.reference_mid()
        snap = book.snapshot(5)
        bid, ask = self._others_best(Side.BUY), self._others_best(Side.SELL)
        spread = (ask - bid) if bid is not None and ask is not None else 0
        mids = self._mids
        ret = lambda lag: (mids[-1] - mids[-1 - lag]) if len(mids) > lag else 0.0
        obs = np.array([
            self.account.inventory / p.max_inventory,         # how loaded we are, -1..1
            1.0 - self._t / p.episode_steps,                  # time left in the episode, 1..0
            spread / 10.0,                                    # market spread (others only)
            snap.imbalance(1),                                # order book imbalance, top level
            snap.imbalance(5),                                # ... and top 5 levels
            ret(1) / 5.0,                                     # recent mid moves
            ret(5) / 5.0,
            ret(30) / 10.0,
            (self._last_fills_signed / p.order_qty),          # what just traded with us (+bought / -sold)
        ], dtype=np.float32)
        return np.clip(obs, -OBS_LIMIT, OBS_LIMIT)

    # ---------- gym API ----------

    def reset(self, *, seed: int | None = None, options: dict | None = None):
        super().reset(seed=seed)
        market_seed = int(self.np_random.integers(0, 2**31 - 1))
        self.market = Market(MarketParams(
            seed=market_seed,
            fair_value=self.p.market.fair_value,
            noise=self.p.market.noise,
            informed=self.p.market.informed,
        ))
        self.market.run(self.p.warmup_steps)
        self.account = MarketMakerAccount(self.market)
        self._t = 0
        self._last_mid = self.market.history[-1].fair_value
        self._mids = [self.reference_mid()]
        self._last_fills_signed = 0
        self._value = self.account.value(self._mids[-1])
        return self._observation(), self._info()

    def step(self, action):
        p = self.p
        bid_off, ask_off = self.decode_action(int(action))
        mid = self.reference_mid()

        # symmetric rounding: never lean to one side by half a tick
        bid = math.ceil(mid - bid_off)
        ask = math.floor(mid + ask_off)
        if ask <= bid:
            ask = bid + 1
        q = self.account.inventory
        bid_price = None if q >= p.max_inventory else bid       # too long: stop buying
        ask_price = None if q <= -p.max_inventory else ask      # too short: stop selling

        self.account.quote(bid_price, ask_price, p.order_qty)
        fills_before = self.account.inventory
        self.market.step()
        self.account.sync()
        self._t += 1
        self._last_fills_signed = self.account.inventory - fills_before

        new_mid = self.reference_mid()
        self._mids.append(new_mid)
        new_value = self.account.value(new_mid)
        pnl = new_value - self._value
        self._value = new_value

        q = self.account.inventory
        reward = pnl - p.inventory_penalty * q**2

        truncated = self._t >= p.episode_steps
        if truncated:
            # close the position at the end: pay half the spread on every unit still held
            bid_o, ask_o = self._others_best(Side.BUY), self._others_best(Side.SELL)
            half_spread = (ask_o - bid_o) / 2 if bid_o is not None and ask_o is not None else 1.0
            reward -= abs(q) * half_spread
            self.account.cancel_all()

        return self._observation(), float(reward), False, truncated, self._info(pnl=pnl)

    def _info(self, pnl: float = 0.0) -> dict:
        return {
            "inventory": self.account.inventory,
            "cash": self.account.cash,
            "value": self._value,
            "pnl": pnl,
            "fair_value": self.market.history[-1].fair_value,
            "t": self._t,
        }