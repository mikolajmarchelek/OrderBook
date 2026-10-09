import pytest

from mmrl.book import BookSnapshot, OrderBook, PriceLevel, QueuePosition
from mmrl.types import NO_OWNER, Order, OrderType, Side


def limit(order_id, side, price, qty, owner=NO_OWNER):
    return Order(order_id, owner, side, OrderType.LIMIT, price, qty, order_id)


@pytest.fixture
def book():
    return OrderBook()    # a fresh, empty book for every test that asks for "book"


# ---------- PriceLevel ----------

def test_level_keeps_arrival_order_and_total():
    level = PriceLevel(10000)
    a, b = limit(1, Side.BUY, 10000, 5), limit(2, Side.BUY, 10000, 7)
    level.add(a)
    level.add(b)
    assert level.peek_first() is a
    assert level.total_qty == 12
    assert level.order_count == 2


def test_level_rejects_wrong_price():
    with pytest.raises(ValueError):
        PriceLevel(10000).add(limit(1, Side.BUY, 9999, 5))


def test_level_remove_is_by_identity():
    level = PriceLevel(10000)
    a = limit(1, Side.BUY, 10000, 5)
    level.add(a)
    look_alike = limit(1, Side.BUY, 10000, 5)   # equal fields, different object
    assert not level.remove(look_alike)
    assert level.remove(a)
    assert level.is_empty and level.total_qty == 0


def test_level_reduce_qty_validates():
    level = PriceLevel(10000)
    level.add(limit(1, Side.BUY, 10000, 5))
    with pytest.raises(ValueError):
        level.reduce_qty(0)
    with pytest.raises(ValueError):
        level.reduce_qty(6)


# ---------- best prices ----------

def test_empty_book_has_no_prices(book):
    assert book.best_bid() is None
    assert book.best_ask() is None
    assert book.spread() is None
    assert book.mid() is None
    assert book.best_level(Side.BUY) is None


def test_best_bid_is_highest_and_best_ask_is_lowest(book):
    for o in (limit(1, Side.BUY, 9998, 5), limit(2, Side.BUY, 10000, 5), limit(3, Side.BUY, 9999, 5),
              limit(4, Side.SELL, 10003, 5), limit(5, Side.SELL, 10002, 5), limit(6, Side.SELL, 10004, 5)):
        book.add_resting(o)
    assert book.best_bid() == 10000
    assert book.best_ask() == 10002
    assert book.best_level(Side.BUY).price == 10000
    assert book.best_level(Side.SELL).price == 10002


def test_spread_and_mid(book):
    book.add_resting(limit(1, Side.BUY, 10000, 5))
    book.add_resting(limit(2, Side.SELL, 10003, 5))
    assert book.spread() == 3
    assert book.mid() == pytest.approx(10001.5)


def test_market_order_cannot_rest(book):
    with pytest.raises(ValueError):
        book.add_resting(Order(1, NO_OWNER, Side.BUY, OrderType.MARKET, 0, 5, 1))
    assert not book.contains(1)


def test_duplicate_id_rejected_without_side_effects(book):
    book.add_resting(limit(1, Side.BUY, 10000, 5))
    with pytest.raises(ValueError):
        book.add_resting(limit(1, Side.SELL, 10002, 5))
    assert book.best_ask() is None
    assert book.best_bid() == 10000


# ---------- cancel ----------

def test_cancel_unknown_id_returns_false(book):
    book.add_resting(limit(1, Side.BUY, 10000, 5))
    assert book.cancel(99) is False
    assert book.best_bid() == 10000


def test_cancel_keeps_non_empty_level(book):
    book.add_resting(limit(1, Side.BUY, 10000, 10))
    book.add_resting(limit(2, Side.BUY, 10000, 5))
    assert book.cancel(1) is True
    assert not book.contains(1) and book.contains(2)
    level = book.best_level(Side.BUY)
    assert level.order_count == 1 and level.total_qty == 5


def test_cancelling_last_order_removes_level(book):
    book.add_resting(limit(1, Side.SELL, 10002, 5))
    book.add_resting(limit(2, Side.SELL, 10003, 5))
    assert book.cancel(1)
    assert book.best_ask() == 10003


def test_cancel_twice_returns_false_second_time(book):
    book.add_resting(limit(1, Side.BUY, 10000, 5))
    assert book.cancel(1) is True
    assert book.cancel(1) is False
    assert book.best_bid() is None


# ---------- reduce ----------

def test_reduce_keeps_queue_position_and_updates_level(book):
    book.add_resting(limit(1, Side.BUY, 10000, 40))
    book.add_resting(limit(2, Side.BUY, 10000, 80))
    assert book.reduce(1, 25) is True
    assert book.queue_position(1) == QueuePosition(10000, Side.BUY, 25, 1, 0)
    assert book.best_level(Side.BUY).total_qty == 105


def test_reduce_unknown_order_returns_false(book):
    assert book.reduce(99, 5) is False


# ---------- queue position ----------

def test_queue_position_counts_orders_and_qty_ahead(book):
    book.add_resting(limit(1, Side.BUY, 10000, 40))
    book.add_resting(limit(2, Side.BUY, 10000, 80))
    book.add_resting(limit(3, Side.BUY, 10000, 10))
    assert book.queue_position(3) == QueuePosition(10000, Side.BUY, 10, 3, 120)
    assert book.queue_position(1).position == 1
    assert book.queue_position(999) is None


# ---------- snapshot ----------

def test_snapshot_fixed_shape_best_first_padded(book):
    book.add_resting(limit(1, Side.BUY, 10000, 5))
    book.add_resting(limit(2, Side.BUY, 9999, 7))
    book.add_resting(limit(3, Side.SELL, 10002, 4))

    s = book.snapshot(3)

    assert s.bid_prices == (10000, 9999, 0)
    assert s.bid_qtys == (5, 7, 0)
    assert s.ask_prices == (10002, 0, 0)
    assert s.ask_qtys == (4, 0, 0)


def test_snapshot_only_takes_top_levels(book):
    for i, price in enumerate((10002, 10003, 10004, 10005), start=1):
        book.add_resting(limit(i, Side.SELL, price, i))
    assert book.snapshot(2).ask_prices == (10002, 10003)


def test_snapshots_with_same_content_are_equal(book):
    book.add_resting(limit(1, Side.BUY, 10000, 5))
    assert book.snapshot(5) == book.snapshot(5)     # tuples compare by value: no equals override needed


def test_imbalance(book):
    book.add_resting(limit(1, Side.BUY, 10000, 30))
    book.add_resting(limit(2, Side.SELL, 10002, 10))
    s = book.snapshot(5)
    assert s.imbalance(1) == pytest.approx(0.5)
    assert OrderBook().snapshot(3).imbalance(3) == 0.0
    with pytest.raises(ValueError):
        s.imbalance(6)