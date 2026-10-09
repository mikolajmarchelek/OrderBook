"""The limit order book: price levels with FIFO queues, mirroring the Java OrderBook."""

from __future__ import annotations

from bisect import insort
from collections import deque
from dataclasses import dataclass

from mmrl.types import Order, OrderType, Side


class PriceLevel:
    """All resting orders at one price, oldest first (price-time priority)."""

    __slots__ = ("price", "orders", "total_qty")

    def __init__(self, price: int) -> None:
        self.price = price
        self.orders: deque[Order] = deque()
        self.total_qty = 0

    def add(self, order: Order) -> None:
        if order.price != self.price:
            raise ValueError(f"order price {order.price} != level price {self.price}")
        self.orders.append(order)              # back of the queue: arrived last, fills last
        self.total_qty += order.remaining_qty

    def peek_first(self) -> Order | None:
        return self.orders[0] if self.orders else None

    def remove(self, order: Order) -> bool:
        """Remove this exact order object (by identity, like the Java version)."""
        for i, o in enumerate(self.orders):
            if o is order:
                del self.orders[i]
                self.total_qty -= order.remaining_qty
                return True
        return False

    def reduce_qty(self, qty: int) -> None:
        if qty <= 0 or qty > self.total_qty:
            raise ValueError(f"qty outside (0, total]: {qty}")
        self.total_qty -= qty

    @property
    def is_empty(self) -> bool:
        return not self.orders

    @property
    def order_count(self) -> int:
        return len(self.orders)


@dataclass(frozen=True, slots=True)
class QueuePosition:
    price: int
    side: Side
    remaining_qty: int
    position: int       # 1 = front of the queue
    qty_ahead: int


@dataclass(frozen=True, slots=True)
class BookSnapshot:
    """Top N levels per side, best first, padded with zeros. Tuples, so == compares contents."""

    bid_prices: tuple[int, ...]
    bid_qtys: tuple[int, ...]
    ask_prices: tuple[int, ...]
    ask_qtys: tuple[int, ...]

    @property
    def levels(self) -> int:
        return len(self.bid_prices)

    def imbalance(self, k: int) -> float:
        """(bid qty - ask qty) / (bid qty + ask qty) over the top k levels; 0.0 if both empty."""
        if k < 1 or k > self.levels:
            raise ValueError(f"k must be in [1, {self.levels}]: {k}")
        bid = sum(self.bid_qtys[:k])
        ask = sum(self.ask_qtys[:k])
        total = bid + ask
        return 0.0 if total == 0 else (bid - ask) / total


class OrderBook:
    """Both sides of the book.

    Each side is a dict price -> PriceLevel for O(1) lookup, plus a sorted list of its
    prices (ascending) for "best price" and ordered walks. Best bid = last bid price,
    best ask = first ask price.
    """

    def __init__(self) -> None:
        self._levels: dict[Side, dict[int, PriceLevel]] = {Side.BUY: {}, Side.SELL: {}}
        self._prices: dict[Side, list[int]] = {Side.BUY: [], Side.SELL: []}
        self._index: dict[int, Order] = {}     # order id -> order, for O(1) cancel

    # ---------- adding and removing ----------

    def add_resting(self, order: Order) -> None:
        if order.type is OrderType.MARKET:
            raise ValueError("market orders cannot rest")
        if order.id in self._index:
            raise ValueError(f"duplicate order id {order.id}")
        levels = self._levels[order.side]
        level = levels.get(order.price)
        if level is None:
            level = PriceLevel(order.price)
            levels[order.price] = level
            insort(self._prices[order.side], order.price)   # keep the price list sorted
        level.add(order)
        self._index[order.id] = order

    def cancel(self, order_id: int) -> bool:
        order = self._index.pop(order_id, None)
        if order is None:
            return False
        level = self._levels[order.side][order.price]
        level.remove(order)
        if level.is_empty:
            self._remove_level(order.side, order.price)
        return True

    def reduce(self, order_id: int, new_qty: int) -> bool:
        """Shrink a resting order in place, keeping its queue position."""
        order = self._index.get(order_id)
        if order is None:
            return False
        level = self._levels[order.side][order.price]
        level.reduce_qty(order.remaining_qty - new_qty)    # level first, using the OLD qty
        order.reduce_to(new_qty)
        return True

    def _remove_level(self, side: Side, price: int) -> None:
        del self._levels[side][price]
        self._prices[side].remove(price)

    # ---------- lookups ----------

    def contains(self, order_id: int) -> bool:
        return order_id in self._index

    def get_order(self, order_id: int) -> Order | None:
        return self._index.get(order_id)

    def best_bid(self) -> int | None:
        prices = self._prices[Side.BUY]
        return prices[-1] if prices else None

    def best_ask(self) -> int | None:
        prices = self._prices[Side.SELL]
        return prices[0] if prices else None

    def best_level(self, side: Side) -> PriceLevel | None:
        price = self.best_bid() if side is Side.BUY else self.best_ask()
        return None if price is None else self._levels[side][price]

    def spread(self) -> int | None:
        bid, ask = self.best_bid(), self.best_ask()
        return None if bid is None or ask is None else ask - bid

    def mid(self) -> float | None:
        bid, ask = self.best_bid(), self.best_ask()
        return None if bid is None or ask is None else (bid + ask) / 2

    def queue_position(self, order_id: int) -> QueuePosition | None:
        order = self._index.get(order_id)
        if order is None:
            return None
        level = self._levels[order.side][order.price]
        qty_ahead = 0
        for position, o in enumerate(level.orders, start=1):
            if o is order:
                return QueuePosition(order.price, order.side, order.remaining_qty, position, qty_ahead)
            qty_ahead += o.remaining_qty
        raise RuntimeError(f"order {order_id} is indexed but not in its level")

    def _levels_best_first(self, side: Side):
        """Yield a side's price levels from best to worst price."""
        prices = self._prices[side]
        ordered = reversed(prices) if side is Side.BUY else prices
        for price in ordered:
            yield self._levels[side][price]

    def snapshot(self, levels: int) -> BookSnapshot:
        if levels <= 0:
            raise ValueError(f"levels must be positive: {levels}")
        sides = {}
        for side in (Side.BUY, Side.SELL):
            prices = [0] * levels
            qtys = [0] * levels
            for i, level in enumerate(self._levels_best_first(side)):
                if i == levels:
                    break
                prices[i] = level.price
                qtys[i] = level.total_qty
            sides[side] = (tuple(prices), tuple(qtys))
        return BookSnapshot(*sides[Side.BUY], *sides[Side.SELL])