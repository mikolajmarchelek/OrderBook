"""Core data types, mirroring the Java engine's Side, OrderType, Order, Trade and OrderEvent."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

NO_OWNER = 0  # anonymous background flow (Java: Order.NO_OWNER)


class Side(Enum):
    BUY = "BUY"
    SELL = "SELL"

    @property
    def opposite(self) -> Side:
        return Side.SELL if self is Side.BUY else Side.BUY


class OrderType(Enum):
    LIMIT = "LIMIT"
    MARKET = "MARKET"


class EventType(Enum):
    SUBMIT = "SUBMIT"
    CANCEL = "CANCEL"
    MODIFY = "MODIFY"


@dataclass(slots=True)
class Order:
    """A live order. Mutable: remaining_qty shrinks as it fills (like the Java Order)."""

    id: int
    owner: int
    side: Side
    type: OrderType
    price: int          # ticks; ignored for MARKET orders
    remaining_qty: int
    sequence: int

    def __post_init__(self) -> None:
        if self.remaining_qty <= 0:
            raise ValueError(f"qty must be positive: {self.remaining_qty}")
        if self.type is OrderType.LIMIT and self.price <= 0:
            raise ValueError(f"limit price must be positive: {self.price}")

    def fill(self, qty: int) -> None:
        if qty <= 0 or qty > self.remaining_qty:
            raise ValueError(f"invalid fill qty: {qty}")
        self.remaining_qty -= qty

    def reduce_to(self, new_qty: int) -> None:
        if new_qty <= 0 or new_qty >= self.remaining_qty:
            raise ValueError(f"invalid new qty: {new_qty}")
        self.remaining_qty = new_qty

    @property
    def is_filled(self) -> bool:
        return self.remaining_qty == 0


@dataclass(frozen=True, slots=True)
class Trade:
    """An executed trade. Immutable, like the Java record. Field order matches trades.csv."""

    buy_order_id: int
    sell_order_id: int
    buy_owner: int
    sell_owner: int
    aggressor: Side
    price: int
    quantity: int
    sequence: int

    def signed_qty_for(self, owner: int) -> int:
        if owner == self.buy_owner and owner == self.sell_owner:
            return 0
        if owner == self.buy_owner:
            return self.quantity
        if owner == self.sell_owner:
            return -self.quantity
        return 0

    @classmethod
    def from_csv(cls, row: dict[str, str]) -> Trade:
        return cls(
            buy_order_id=int(row["buyOrderId"]),
            sell_order_id=int(row["sellOrderId"]),
            buy_owner=int(row["buyOwner"]),
            sell_owner=int(row["sellOwner"]),
            aggressor=Side(row["aggressor"]),
            price=int(row["price"]),
            quantity=int(row["quantity"]),
            sequence=int(row["sequence"]),
        )


@dataclass(frozen=True, slots=True)
class OrderEvent:
    """One input to the engine. side/order_type are None for CANCEL and MODIFY."""

    type: EventType
    order_id: int
    owner: int
    side: Side | None
    order_type: OrderType | None
    price: int
    qty: int

    @classmethod
    def from_csv(cls, row: dict[str, str]) -> OrderEvent:
        return cls(
            type=EventType(row["type"]),
            order_id=int(row["orderId"]),
            owner=int(row["ownerId"]),
            side=Side(row["side"]) if row["side"] else None,
            order_type=OrderType(row["orderType"]) if row["orderType"] else None,
            price=int(row["price"]),
            qty=int(row["qty"]),
        )