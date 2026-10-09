import dataclasses
from collections import Counter
from pathlib import Path

import pytest

from mmrl.io import read_events, read_trades
from mmrl.types import NO_OWNER, EventType, Order, OrderEvent, OrderType, Side, Trade

PARITY_DIR = Path(__file__).resolve().parents[2] / "parity"


# ---------- enums ----------

def test_side_opposite():
    assert Side.BUY.opposite is Side.SELL
    assert Side.SELL.opposite is Side.BUY


def test_enums_parse_from_java_strings():
    assert Side("BUY") is Side.BUY
    assert OrderType("MARKET") is OrderType.MARKET
    assert EventType("MODIFY") is EventType.MODIFY


# ---------- Order ----------

def limit(order_id, side, price, qty, owner=NO_OWNER):
    return Order(order_id, owner, side, OrderType.LIMIT, price, qty, order_id)


def test_order_rejects_non_positive_qty():
    with pytest.raises(ValueError):
        limit(1, Side.BUY, 10000, 0)


def test_limit_order_rejects_non_positive_price():
    with pytest.raises(ValueError):
        limit(1, Side.BUY, 0, 5)


def test_market_order_may_have_zero_price():
    Order(1, NO_OWNER, Side.BUY, OrderType.MARKET, 0, 5, 1)   # no exception


def test_fill_and_is_filled():
    o = limit(1, Side.BUY, 10000, 10)
    o.fill(4)
    assert o.remaining_qty == 6 and not o.is_filled
    o.fill(6)
    assert o.is_filled


def test_fill_rejects_overfill():
    o = limit(1, Side.BUY, 10000, 10)
    with pytest.raises(ValueError):
        o.fill(11)


def test_reduce_to_only_shrinks():
    o = limit(1, Side.BUY, 10000, 10)
    o.reduce_to(3)
    assert o.remaining_qty == 3
    for bad in (0, 3, 5):
        with pytest.raises(ValueError):
            o.reduce_to(bad)


# ---------- Trade ----------

def test_signed_qty_for():
    t = Trade(1, 2, 7, 9, Side.SELL, 10000, 5, 1)
    assert t.signed_qty_for(7) == 5      # buyer
    assert t.signed_qty_for(9) == -5     # seller
    assert t.signed_qty_for(3) == 0      # not involved


def test_self_trade_signed_qty_is_zero():
    assert Trade(1, 2, 7, 7, Side.BUY, 10000, 5, 1).signed_qty_for(7) == 0


def test_trade_is_immutable():
    t = Trade(1, 2, 0, 0, Side.BUY, 10000, 5, 1)
    with pytest.raises(dataclasses.FrozenInstanceError):
        t.price = 1


def test_trades_with_same_fields_are_equal():
    assert Trade(1, 2, 0, 0, Side.BUY, 10000, 5, 1) == Trade(1, 2, 0, 0, Side.BUY, 10000, 5, 1)


# ---------- reading the Java fixture ----------

def test_read_events_from_fixture():
    events = read_events(PARITY_DIR / "events.csv")

    assert len(events) == 12_000
    assert events[0] == OrderEvent(EventType.SUBMIT, 1, 0, Side.SELL, OrderType.LIMIT, 10005, 20)
    assert events[1] == OrderEvent(EventType.SUBMIT, 2, 1, Side.BUY, OrderType.LIMIT, 9998, 14)

    counts = Counter(e.type for e in events)
    assert set(counts) == {EventType.SUBMIT, EventType.CANCEL, EventType.MODIFY}


def test_cancel_and_modify_have_no_side():
    events = read_events(PARITY_DIR / "events.csv")
    for e in events:
        if e.type is not EventType.SUBMIT:
            assert e.side is None and e.order_type is None


def test_read_trades_from_fixture():
    trades = read_trades(PARITY_DIR / "trades.csv")

    assert len(trades) == 5_773
    assert trades[0] == Trade(2, 6, 1, 0, Side.SELL, 9998, 14, 1)
    assert [t.sequence for t in trades] == list(range(1, len(trades) + 1))   # 1, 2, 3, ... no gaps