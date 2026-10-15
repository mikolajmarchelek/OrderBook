"""Background traders: the market the agent will trade against.

Every trader has the same shape: act(engine, fair_value) is called once per simulation
step and sends orders/cancels through the engine (so everything is logged and replayable).
"""

from __future__ import annotations

import itertools
import math
import random
from dataclasses import dataclass

from mmrl.engine import MatchingEngine
from mmrl.types import NO_OWNER, Order, OrderType, Side

INFORMED_OWNER = 2      # owner id 1 is reserved for the agent


def poisson(rng: random.Random, lam: float) -> int:
    """Number of events in one step when events arrive at an average rate lam (Knuth's method)."""
    limit = math.exp(-lam)
    k, p = 0, 1.0
    while True:
        p *= rng.random()
        if p <= limit:
            return k
        k += 1


def reference_price(engine: MatchingEngine, fallback: float) -> int:
    """The book's mid in whole ticks, or the fallback if a side is empty."""
    mid = engine.book.mid()
    return round(mid if mid is not None else fallback)


# ---------- noise traders ----------

@dataclass(frozen=True)
class NoiseParams:
    limit_rate: float = 2.0      # average new limit orders per step
    market_rate: float = 0.5     # average new market orders per step
    cancel_prob: float = 0.05    # chance per step that EACH resting order is cancelled
    max_offset: int = 5          # limit orders land 1..max_offset ticks from mid
    max_qty: int = 10            # order sizes 1..max_qty


class NoiseTraders:
    """Many uninformed traders, modelled as one source of random orders around the book's mid.

    They never look at the fair value (it's only a fallback when the book is empty). Their
    limit orders give the book depth; their market orders are the 'good' flow a market
    maker earns the spread from.

    Cancels are per resting order: each one has the same small chance of being cancelled
    every step. That keeps the book from growing forever: on average it settles near
    limit_rate / cancel_prob resting orders (2.0 / 0.05 = 40 by default).
    """

    def __init__(self, params: NoiseParams, rng: random.Random, ids: itertools.count) -> None:
        self.params = params
        self.rng = rng
        self.ids = ids
        self.resting: list[int] = []      # our order ids that may still rest (candidates for cancels)

    def act(self, engine: MatchingEngine, fair_value: float) -> None:
        p = self.params
        self.resting = [i for i in self.resting if engine.book.contains(i)]   # drop filled ones
        for _ in range(min(poisson(self.rng, p.cancel_prob * len(self.resting)), len(self.resting))):
            self._cancel_random(engine)
        for _ in range(poisson(self.rng, p.limit_rate)):
            self._new_limit(engine, reference_price(engine, fair_value))
        for _ in range(poisson(self.rng, p.market_rate)):
            self._new_market(engine)

    def _random_side_and_qty(self) -> tuple[Side, int]:
        side = Side.BUY if self.rng.random() < 0.5 else Side.SELL
        return side, self.rng.randint(1, self.params.max_qty)

    def _new_limit(self, engine: MatchingEngine, mid: int) -> None:
        side, qty = self._random_side_and_qty()
        offset = self.rng.randint(1, self.params.max_offset)
        price = mid - offset if side is Side.BUY else mid + offset
        order_id = next(self.ids)
        engine.submit(Order(order_id, NO_OWNER, side, OrderType.LIMIT, max(price, 1), qty, order_id))
        if engine.book.contains(order_id):
            self.resting.append(order_id)

    def _new_market(self, engine: MatchingEngine) -> None:
        side, qty = self._random_side_and_qty()
        order_id = next(self.ids)
        engine.submit(Order(order_id, NO_OWNER, side, OrderType.MARKET, 0, qty, order_id))

    def _cancel_random(self, engine: MatchingEngine) -> None:
        i = self.rng.randrange(len(self.resting))
        order_id = self.resting[i]
        self.resting[i] = self.resting[-1]      # O(1) removal: swap with last, then pop
        self.resting.pop()
        engine.cancel(order_id)


# ---------- informed traders ----------

@dataclass(frozen=True)
class InformedParams:
    act_prob: float = 0.2        # chance per step that an informed trader looks at the market
    threshold: float = 2.0       # only trade if the book is wrong by more than this many ticks
    qty: int = 5                 # size of each market order


class InformedTraders:
    """Traders who know the fair value and pick off quotes that are on the wrong side of it."""

    def __init__(self, params: InformedParams, rng: random.Random, ids: itertools.count) -> None:
        self.params = params
        self.rng = rng
        self.ids = ids

    def decide(self, best_bid: int | None, best_ask: int | None, fair_value: float) -> Side | None:
        """Which side to trade, or None. Buy if the ask is cheap vs fair value, sell if the bid is rich."""
        t = self.params.threshold
        if best_ask is not None and fair_value - best_ask > t:
            return Side.BUY             # asset offered below its true value -> buy it
        if best_bid is not None and best_bid - fair_value > t:
            return Side.SELL            # someone bids above the true value -> sell to them
        return None      
    
    def act(self, engine: MatchingEngine, fair_value: float) -> None:
        if self.rng.random() >= self.params.act_prob:
            return
        side = self.decide(engine.book.best_bid(), engine.book.best_ask(), fair_value)
        if side is None:
            return
        order_id = next(self.ids)
        engine.submit(Order(order_id, INFORMED_OWNER, side, OrderType.MARKET, 0,
                            self.params.qty, order_id))