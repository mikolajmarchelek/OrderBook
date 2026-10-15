"""The market simulator: one clock that moves the fair value and lets every trader act.

This is the world the RL environment wraps. One seed controls everything.
"""

from __future__ import annotations

import itertools
import random
from dataclasses import dataclass, field

from mmrl.engine import MatchingEngine
from mmrl.fair_value import FairValue, FairValueParams
from mmrl.traders import InformedParams, InformedTraders, NoiseParams, NoiseTraders


@dataclass(frozen=True)
class MarketParams:
    seed: int = 0
    fair_value: FairValueParams = field(default_factory=FairValueParams)
    noise: NoiseParams = field(default_factory=NoiseParams)
    informed: InformedParams = field(default_factory=InformedParams)


@dataclass(frozen=True, slots=True)
class StepStats:
    """What the market looked like at the end of one step."""

    t: int
    fair_value: float
    best_bid: int | None
    best_ask: int | None
    trades: int                 # trades that happened during this step


class Market:
    def __init__(self, params: MarketParams) -> None:
        self.params = params
        self.engine = MatchingEngine()
        self.t = 0
        self.history: list[StepStats] = []

        # One master seed -> independent random streams for each part of the market.
        # The fair value gets its own stream, so adding or changing traders never changes V's path.
        master = random.Random(params.seed)
        self.fair_value = FairValue(params.fair_value, random.Random(master.getrandbits(64)))
        self.rng = random.Random(master.getrandbits(64))         # trader order shuffling
        trader_rng = random.Random(master.getrandbits(64))

        self._ids = itertools.count(1)                          # shared: order ids never collide
        self.traders = [
            NoiseTraders(params.noise, trader_rng, self._ids),
            InformedTraders(params.informed, trader_rng, self._ids),
        ]

    def next_id(self) -> int:
        """A fresh order id, for anyone else trading in this market (the agent, later)."""
        return next(self._ids)

    def step(self) -> StepStats:
        """Advance the market by one step and return a summary of it."""
        trades_before = len(self.engine.trade_log)        # 1. count trades so far
        v = self.fair_value.step()                        # 2. the true price moves

        order = list(self.traders)                        # 3. every trader acts,
        self.rng.shuffle(order)                           #    in a random order each step
        for trader in order:
            trader.act(self.engine, v)

        self.t += 1                                       # 4. the clock advances

        stats = StepStats(                                # 5. record what the market looks like now
            self.t,
            v,
            self.engine.book.best_bid(),
            self.engine.book.best_ask(),
            len(self.engine.trade_log) - trades_before,   #    trades that happened during this step
        )
        self.history.append(stats)
        return stats

    def run(self, steps: int) -> None:
        for _ in range(steps):
            self.step()