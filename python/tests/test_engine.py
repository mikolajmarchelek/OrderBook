"""Ports of the Java MatchingEngineTest cases, plus logging and replay."""

import pytest

from mmrl.book import QueuePosition
from mmrl.engine import MatchingEngine, replay
from mmrl.types import NO_OWNER, EventType, Order, OrderType, Side, Trade

AGENT = 1
OTHER = 2


def limit(order_id, side, price, qty, owner=NO_OWNER):
    return Order(order_id, owner, side, OrderType.LIMIT, price, qty, order_id)


def market(order_id, side, qty, owner=NO_OWNER):
    return Order(order_id, owner, side, OrderType.MARKET, 0, qty, order_id)


def trade(buy_id, sell_id, aggressor, price, qty, seq):
    """Expected trade between two anonymous orders."""
    return Trade(buy_id, sell_id, NO_OWNER, NO_OWNER, aggressor, price, qty, seq)


@pytest.fixture
def engine():
    return MatchingEngine()


# ---------- no match ----------

def test_limit_order_rests_on_empty_book(engine):
    assert engine.submit(limit(1, Side.BUY, 10000, 10)) == []
    assert engine.book.best_bid() == 10000


def test_non_crossing_orders_rest(engine):
    engine.submit(limit(1, Side.SELL, 10002, 10))
    assert engine.submit(limit(2, Side.BUY, 10000, 10)) == []
    assert engine.book.spread() == 2


# ---------- basic matching ----------

def test_equal_prices_trade(engine):
    engine.submit(limit(1, Side.SELL, 10002, 10))
    trades = engine.submit(limit(2, Side.BUY, 10002, 10))
    assert trades == [trade(2, 1, Side.BUY, 10002, 10, 1)]
    assert engine.book.best_bid() is None and engine.book.best_ask() is None


def test_trade_happens_at_resting_price(engine):
    engine.submit(limit(1, Side.SELL, 10002, 10))
    assert engine.submit(limit(2, Side.BUY, 10005, 10))[0].price == 10002


def test_incoming_sell_hits_bids_best_first(engine):
    engine.submit(limit(1, Side.BUY, 10000, 10))
    engine.submit(limit(2, Side.BUY, 9999, 10))
    trades = engine.submit(limit(3, Side.SELL, 9999, 15))
    assert trades == [trade(1, 3, Side.SELL, 10000, 10, 1), trade(2, 3, Side.SELL, 9999, 5, 2)]
    assert engine.book.best_level(Side.BUY).total_qty == 5
    assert engine.book.best_ask() is None


# ---------- price-time priority ----------

def test_sweeps_multiple_levels_in_price_order(engine):
    engine.submit(limit(2, Side.SELL, 10002, 50))
    engine.submit(limit(7, Side.SELL, 10002, 10))
    engine.submit(limit(4, Side.SELL, 10003, 12))
    engine.submit(limit(3, Side.BUY, 10000, 20))

    trades = engine.submit(limit(9, Side.BUY, 10003, 65))

    assert trades == [
        trade(9, 2, Side.BUY, 10002, 50, 1),
        trade(9, 7, Side.BUY, 10002, 10, 2),
        trade(9, 4, Side.BUY, 10003, 5, 3),
    ]
    assert engine.book.best_ask() == 10003
    assert engine.book.best_level(Side.SELL).total_qty == 7
    assert not engine.book.contains(9)
    assert engine.book.best_bid() == 10000


def test_oldest_order_at_level_fills_first(engine):
    engine.submit(limit(1, Side.SELL, 10002, 10))
    engine.submit(limit(2, Side.SELL, 10002, 10))
    assert engine.submit(limit(3, Side.BUY, 10002, 10))[0].sell_order_id == 1
    assert engine.book.contains(2) and not engine.book.contains(1)


def test_partially_filled_resting_order_keeps_its_place(engine):
    first = limit(1, Side.SELL, 10002, 20)
    engine.submit(first)
    engine.submit(limit(2, Side.SELL, 10002, 10))
    engine.submit(limit(3, Side.BUY, 10002, 5))
    level = engine.book.best_level(Side.SELL)
    assert level.peek_first() is first
    assert first.remaining_qty == 15
    assert level.total_qty == 25


# ---------- leftovers ----------

def test_limit_leftover_rests_with_remaining_qty(engine):
    engine.submit(limit(1, Side.SELL, 10002, 10))
    engine.submit(limit(2, Side.BUY, 10003, 25))
    assert engine.book.best_bid() == 10003
    assert engine.book.best_level(Side.BUY).total_qty == 15


def test_market_order_leftover_is_discarded(engine):
    engine.submit(limit(1, Side.SELL, 10002, 10))
    engine.submit(limit(2, Side.SELL, 10005, 10))
    trades = engine.submit(market(3, Side.BUY, 30))
    assert trades == [trade(3, 1, Side.BUY, 10002, 10, 1), trade(3, 2, Side.BUY, 10005, 10, 2)]
    assert engine.book.best_bid() is None and not engine.book.contains(3)


def test_market_order_on_empty_book_does_nothing(engine):
    assert engine.submit(market(1, Side.SELL, 10)) == []


# ---------- validation & bookkeeping ----------

def test_duplicate_id_rejected_before_any_trade(engine):
    resting = limit(1, Side.SELL, 10002, 10)
    engine.submit(resting)
    with pytest.raises(ValueError):
        engine.submit(limit(1, Side.BUY, 10002, 10))
    assert resting.remaining_qty == 10
    assert len(engine.event_log) == 1           # the rejected order was never logged


def test_trade_sequence_keeps_increasing(engine):
    engine.submit(limit(1, Side.SELL, 10002, 10))
    engine.submit(limit(2, Side.SELL, 10002, 10))
    t1 = engine.submit(limit(3, Side.BUY, 10002, 10))[0]
    t2 = engine.submit(limit(4, Side.BUY, 10002, 10))[0]
    assert (t1.sequence, t2.sequence) == (1, 2)


# ---------- owners and aggressor ----------

def test_trade_records_owners_and_aggressor(engine):
    engine.submit(limit(1, Side.BUY, 10000, 10, owner=AGENT))
    t = engine.submit(limit(2, Side.SELL, 10000, 4, owner=OTHER))[0]
    assert (t.buy_owner, t.sell_owner, t.aggressor) == (AGENT, OTHER, Side.SELL)
    assert t.signed_qty_for(AGENT) == 4


# ---------- self-trade prevention ----------

def test_self_trade_cancels_resting_order(engine):
    engine.submit(limit(1, Side.BUY, 10000, 5, owner=AGENT))
    trades = engine.submit(limit(2, Side.SELL, 10000, 5, owner=AGENT))
    assert trades == []
    assert not engine.book.contains(1)
    assert engine.book.best_ask() == 10000 and engine.book.best_bid() is None


def test_self_trade_prevention_skips_own_order_but_trades_with_others(engine):
    engine.submit(limit(1, Side.SELL, 10001, 3, owner=OTHER))
    engine.submit(limit(2, Side.SELL, 10002, 5, owner=AGENT))
    engine.submit(limit(3, Side.SELL, 10003, 4, owner=OTHER))

    trades = engine.submit(limit(4, Side.BUY, 10003, 6, owner=AGENT))

    assert [t.price for t in trades] == [10001, 10003]
    assert sum(t.signed_qty_for(AGENT) for t in trades) == 6
    assert not engine.book.contains(2)
    assert engine.book.best_level(Side.SELL).total_qty == 1


def test_anonymous_orders_are_never_self_trades(engine):
    engine.submit(limit(1, Side.BUY, 10000, 5))
    assert len(engine.submit(limit(2, Side.SELL, 10000, 5))) == 1


# ---------- modify ----------

def test_reducing_qty_keeps_queue_position(engine):
    engine.submit(limit(1, Side.BUY, 10000, 40, owner=AGENT))
    engine.submit(limit(2, Side.BUY, 10000, 80))
    assert engine.modify(1, 10000, 25) == []
    assert engine.book.queue_position(1) == QueuePosition(10000, Side.BUY, 25, 1, 0)
    assert engine.book.best_level(Side.BUY).total_qty == 105


def test_increasing_qty_loses_queue_position(engine):
    engine.submit(limit(1, Side.BUY, 10000, 10, owner=AGENT))
    engine.submit(limit(2, Side.BUY, 10000, 30))
    engine.modify(1, 10000, 15)
    assert engine.book.queue_position(1) == QueuePosition(10000, Side.BUY, 15, 2, 30)


def test_changing_price_moves_order(engine):
    engine.submit(limit(1, Side.BUY, 10000, 10, owner=AGENT))
    engine.submit(limit(2, Side.BUY, 10000, 30))
    engine.modify(1, 10001, 10)
    assert engine.book.best_bid() == 10001
    assert engine.book.snapshot(2).bid_qtys == (10, 30)


def test_modify_that_crosses_trades_as_aggressor(engine):
    engine.submit(limit(1, Side.SELL, 10002, 5, owner=OTHER))
    engine.submit(limit(2, Side.BUY, 10000, 10, owner=AGENT))
    trades = engine.modify(2, 10002, 10)
    assert len(trades) == 1
    assert trades[0].aggressor is Side.BUY
    assert trades[0].signed_qty_for(AGENT) == 5          # owner survived the modify
    assert engine.book.queue_position(2) == QueuePosition(10002, Side.BUY, 5, 1, 0)


def test_same_qty_and_price_is_a_no_op(engine):
    engine.submit(limit(1, Side.BUY, 10000, 10))
    engine.submit(limit(2, Side.BUY, 10000, 10, owner=AGENT))
    engine.submit(limit(3, Side.BUY, 10000, 10))
    engine.modify(2, 10000, 10)
    assert engine.book.queue_position(2).position == 2


def test_modifying_missing_order_does_nothing(engine):
    engine.submit(limit(1, Side.BUY, 10000, 10))
    assert engine.modify(999, 10001, 5) == []
    assert engine.book.best_bid() == 10000


def test_modify_rejects_non_positive_qty(engine):
    engine.submit(limit(1, Side.BUY, 10000, 10))
    with pytest.raises(ValueError):
        engine.modify(1, 10000, 0)


# ---------- event log and replay ----------

def test_inputs_are_logged_but_self_trade_cancels_are_not(engine):
    engine.submit(limit(1, Side.SELL, 10002, 5, owner=OTHER))
    engine.submit(limit(2, Side.BUY, 10000, 10, owner=AGENT))
    engine.modify(2, 10002, 10)
    engine.cancel(2)
    assert [e.type for e in engine.event_log] == [
        EventType.SUBMIT, EventType.SUBMIT, EventType.MODIFY, EventType.CANCEL]

    stp = MatchingEngine()
    stp.submit(limit(1, Side.BUY, 10000, 5, owner=AGENT))
    stp.submit(limit(2, Side.SELL, 10000, 5, owner=AGENT))   # STP cancels #1 internally
    assert len(stp.event_log) == 2


def test_replay_reproduces_trades_and_book(engine):
    engine.submit(limit(1, Side.SELL, 10002, 5, owner=OTHER))
    engine.submit(limit(2, Side.BUY, 10000, 10, owner=AGENT))
    engine.modify(2, 10002, 10)
    engine.submit(market(3, Side.SELL, 2))
    engine.cancel(2)

    replayed = replay(engine.event_log)

    assert replayed.trade_log == engine.trade_log
    assert replayed.book.snapshot(5) == engine.book.snapshot(5)
    assert replayed.event_log == engine.event_log