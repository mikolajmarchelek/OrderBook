"""The matching engine, mirroring the Java MatchingEngine rule for rule."""

from __future__ import annotations

from mmrl.book import OrderBook
from mmrl.types import NO_OWNER, EventType, Order, OrderEvent, OrderType, Side, Trade


class MatchingEngine:
    def __init__(self, book: OrderBook | None = None) -> None:
        self.book = book if book is not None else OrderBook()
        self.trade_log: list[Trade] = []
        self.event_log: list[OrderEvent] = []
        self._next_trade_seq = 1

    # ---------- the three inputs (each one is logged) ----------

    def submit(self, incoming: Order) -> list[Trade]:
        """Match an incoming order against the book. Returns the trades it produced."""
        if self.book.contains(incoming.id):
            raise ValueError(f"order {incoming.id} is already resting in the book")
        self.event_log.append(OrderEvent(
            EventType.SUBMIT, incoming.id, incoming.owner, incoming.side,
            incoming.type, incoming.price, incoming.remaining_qty))
        return self._match(incoming)

    def cancel(self, order_id: int) -> bool:
        self.event_log.append(OrderEvent(EventType.CANCEL, order_id, NO_OWNER, None, None, 0, 0))
        return self.book.cancel(order_id)

    def modify(self, order_id: int, new_price: int, new_qty: int) -> list[Trade]:
        """Amend a resting order, keeping its id. Returns trades if the new price crosses."""
        if new_qty <= 0:
            raise ValueError(f"new quantity must be positive: {new_qty}")
        self.event_log.append(OrderEvent(EventType.MODIFY, order_id, NO_OWNER, None, None, new_price, new_qty))

        old = self.book.get_order(order_id)
        if old is None:
            return []                                   # already filled or cancelled
        if new_price == old.price and new_qty == old.remaining_qty:
            return []                                   # nothing changed
        if new_price == old.price and new_qty < old.remaining_qty:
            self.book.reduce(order_id, new_qty)         # shrink in place, keep queue spot
            return []
        self.book.cancel(order_id)                      # new price or bigger size -> back of queue
        replacement = Order(old.id, old.owner, old.side, OrderType.LIMIT,
                            new_price, new_qty, old.sequence)
        return self._match(replacement)                 # NOT submit: that would log a second event

    # ---------- matching ----------

    def _match(self, incoming: Order) -> list[Trade]:
        trades: list[Trade] = []
        opposite = incoming.side.opposite

        while incoming.remaining_qty > 0:
            best = self.book.best_level(opposite)
            if best is None or not self._crosses(incoming, best.price):
                break
            resting = best.peek_first()

            if self._is_self_trade(incoming, resting):
                self.book.cancel(resting.id)            # engine's own reaction: not logged
                continue

            qty = min(incoming.remaining_qty, resting.remaining_qty)
            incoming.fill(qty)
            resting.fill(qty)
            best.reduce_qty(qty)

            if incoming.side is Side.BUY:
                buyer, seller = incoming, resting
            else:
                buyer, seller = resting, incoming
            trades.append(Trade(buyer.id, seller.id, buyer.owner, seller.owner,
                                incoming.side, best.price, qty, self._next_trade_seq))
            self._next_trade_seq += 1

            if resting.is_filled:
                self.book.cancel(resting.id)

        if incoming.remaining_qty > 0 and incoming.type is OrderType.LIMIT:
            self.book.add_resting(incoming)
        self.trade_log.extend(trades)
        return trades

    @staticmethod
    def _crosses(incoming: Order, resting_price: int) -> bool:
        """Does the incoming order accept this resting price?"""
        if incoming.type is OrderType.MARKET:
            return True
        if incoming.side is Side.BUY:
            return incoming.price >= resting_price
        return incoming.price <= resting_price

    @staticmethod
    def _is_self_trade(incoming: Order, resting: Order) -> bool:
        """Same owner on both sides, and not anonymous background flow."""
        return incoming.owner != NO_OWNER and incoming.owner == resting.owner


def apply_event(engine: MatchingEngine, e: OrderEvent) -> list[Trade]:
    """Feed one logged input into the engine. Returns the trades it produced."""
    if e.type is EventType.SUBMIT:
        return engine.submit(Order(e.order_id, e.owner, e.side, e.order_type, e.price, e.qty, e.order_id))
    if e.type is EventType.CANCEL:
        engine.cancel(e.order_id)
        return []
    return engine.modify(e.order_id, e.price, e.qty)


def replay(events: list[OrderEvent]) -> MatchingEngine:
    """Feed events into a fresh engine, in order (the Java Replayer)."""
    engine = MatchingEngine()
    for e in events:
        apply_event(engine, e)
    return engine