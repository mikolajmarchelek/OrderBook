import pytest

from mmrl.agent import AGENT_OWNER, MarketMakerAccount
from mmrl.market import Market, MarketParams
from mmrl.types import NO_OWNER, Order, OrderType, Side, Trade


@pytest.fixture
def market():
    return Market(MarketParams(seed=1))      # empty book: we place every order by hand


def other(market, side, order_type, price, qty):
    """Submit an order from someone else (anonymous background flow)."""
    oid = market.next_id()
    return market.engine.submit(Order(oid, NO_OWNER, side, order_type, price, qty, oid))


# ---------- quoting ----------

def test_quote_places_both_sides(market):
    acct = MarketMakerAccount(market)
    acct.quote(9_999, 10_001, 5)
    assert market.engine.book.best_bid() == 9_999
    assert market.engine.book.best_ask() == 10_001
    assert market.engine.book.get_order(acct.bid_id).owner == AGENT_OWNER


def test_requote_keeps_ids_and_moves_prices(market):
    acct = MarketMakerAccount(market)
    acct.quote(9_999, 10_001, 5)
    bid_id, ask_id = acct.bid_id, acct.ask_id
    acct.quote(9_998, 10_002, 5)
    assert (acct.bid_id, acct.ask_id) == (bid_id, ask_id)
    assert (market.engine.book.best_bid(), market.engine.book.best_ask()) == (9_998, 10_002)


def test_none_pulls_a_side(market):
    acct = MarketMakerAccount(market)
    acct.quote(9_999, 10_001, 5)
    acct.quote(None, 10_001, 5)
    assert market.engine.book.best_bid() is None
    assert acct.bid_id is None


def test_filled_quote_is_replaced_by_a_new_order(market):
    acct = MarketMakerAccount(market)
    acct.quote(9_999, 10_001, 5)
    first_bid = acct.bid_id
    other(market, Side.SELL, OrderType.MARKET, 0, 5)      # fills our whole bid
    acct.quote(9_999, 10_001, 5)
    assert acct.bid_id != first_bid                       # the old one is gone, so a fresh order
    assert market.engine.book.best_bid() == 9_999


# ---------- bookkeeping ----------

def test_bid_fill_makes_us_long_and_costs_cash(market):
    acct = MarketMakerAccount(market)
    acct.quote(9_999, 10_001, 5)
    other(market, Side.SELL, OrderType.MARKET, 0, 3)      # someone sells 3 into our bid
    fills = acct.sync()
    assert len(fills) == 1
    assert acct.inventory == 3
    assert acct.cash == -3 * 9_999


def test_round_trip_earns_the_spread(market):
    acct = MarketMakerAccount(market)
    acct.quote(9_999, 10_001, 5)
    other(market, Side.SELL, OrderType.MARKET, 0, 2)      # we buy 2 at 9,999
    other(market, Side.BUY, OrderType.MARKET, 0, 2)       # we sell 2 at 10,001
    acct.sync()
    assert acct.inventory == 0
    assert acct.cash == 2 * (10_001 - 9_999)              # 2 units x 2-tick spread = 4


def test_value_marks_inventory_to_market(market):
    acct = MarketMakerAccount(market)
    acct.quote(9_999, 10_001, 5)
    other(market, Side.SELL, OrderType.MARKET, 0, 3)
    acct.sync()
    # bought 3 at 9,999; worth 10,000 each -> +3 ticks of profit on paper
    assert acct.value(10_000) == pytest.approx(3.0)
    # if the price falls to 9,990, we're down 27
    assert acct.value(9_990) == pytest.approx(-27.0)


def test_sync_ignores_other_peoples_trades_and_never_double_counts(market):
    acct = MarketMakerAccount(market)
    other(market, Side.BUY, OrderType.LIMIT, 9_000, 4)
    other(market, Side.SELL, OrderType.MARKET, 0, 4)      # a trade between two others
    assert acct.sync() == []
    acct.quote(9_999, 10_001, 5)
    other(market, Side.SELL, OrderType.MARKET, 0, 1)
    assert len(acct.sync()) == 1
    assert acct.sync() == []                              # already counted
    assert acct.inventory == 1


def test_modify_that_crosses_is_counted(market):
    acct = MarketMakerAccount(market)
    other(market, Side.SELL, OrderType.LIMIT, 10_002, 4)
    acct.quote(9_999, 10_010, 5)
    acct.quote(10_002, 10_010, 5)                         # we lift the 10,002 ask ourselves
    assert acct.inventory == 4
    assert acct.cash == -4 * 10_002


def test_agent_in_a_live_market():
    m = Market(MarketParams(seed=2))
    m.run(300)                                            # warm up the book
    acct = MarketMakerAccount(m)
    for _ in range(2_000):
        mid = m.engine.book.mid()
        if mid is not None:
            acct.quote(int(mid) - 2, int(mid) + 2, 2)
        m.step()
        acct.sync()
    assert acct.inventory != 0 or acct.cash != 0          # it actually traded
    # every unit we hold came from a recorded fill
    ours = [t for t in m.engine.trade_log if AGENT_OWNER in (t.buy_owner, t.sell_owner)]
    assert acct.inventory == sum(t.signed_qty_for(AGENT_OWNER) for t in ours)