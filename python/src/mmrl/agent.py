"""The market maker's account: its two quotes, its inventory, its cash, its profit.
"""

from __future__ import annotations

from mmrl.market import Market
from mmrl.types import Order, OrderType, Side, Trade

AGENT_OWNER = 1


class MarketMakerAccount:
    def __init__(self, market: Market, owner: int = AGENT_OWNER) -> None:
        self.market = market
        self.owner = owner
        self.bid_id: int | None = None      # our resting bid's order id, if any
        self.ask_id: int | None = None
        self.inventory = 0                  # q: units held (positive = long, negative = short)
        self.cash = 0                       # in ticks x units: we start with nothing
        self._seen = len(market.engine.trade_log)   # trades before this index are already counted

    # ---------- quoting ----------

    def quote(self, bid_price: int | None, ask_price: int | None, qty: int) -> None:
        """Place/move our bid and ask. None on a side means: don't quote that side."""
        self.bid_id = self._place(self.bid_id, Side.BUY, bid_price, qty)
        self.ask_id = self._place(self.ask_id, Side.SELL, ask_price, qty)
        self.sync()                         # a moved quote can trade immediately

    def _place(self, order_id: int | None, side: Side, price: int | None, qty: int) -> int | None:
        engine = self.market.engine
        resting = order_id is not None and engine.book.contains(order_id)
        if price is None:                   # pull this side
            if resting:
                engine.cancel(order_id)
            return None
        if resting:                         # keep the same id; queue rules decided by the engine
            engine.modify(order_id, price, qty)
            return order_id
        new_id = self.market.next_id()
        engine.submit(Order(new_id, self.owner, side, OrderType.LIMIT, price, qty, new_id))
        return new_id

    def cancel_all(self) -> None:
        self.quote(None, None, 1)

    # ---------- bookkeeping ----------

    def sync(self) -> list[Trade]:
        """Apply every trade since the last sync that involved us. Returns our new fills."""
        log = self.market.engine.trade_log
        fills = []
        for trade in log[self._seen:]:
            if self.owner in (trade.buy_owner, trade.sell_owner):
                self._apply_fill(trade)
                fills.append(trade)
        self._seen = len(log)
        return fills

    def _apply_fill(self, trade: Trade) -> None:
        """Update inventory and cash for one of our fills."""
        signed = trade.signed_qty_for(self.owner)     # +qty if we bought, -qty if we sold
        self.inventory += signed
        self.cash -= signed * trade.price             # buying costs cash, selling brings cash in

    def value(self, mark_price: float) -> float:
        """Mark-to-market wealth: cash plus inventory valued at mark_price."""
        return self.cash + self.inventory * mark_price