package com.miko.orderbook;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderBookTest {

    // helpers: id doubles as sequence
    private Order buy(long id, long price, long qty) {
        return new Order(id, Side.BUY, OrderType.LIMIT, price, qty, id);
    }

    private Order sell(long id, long price, long qty) {
        return new Order(id, Side.SELL, OrderType.LIMIT, price, qty, id);
    }

    // ---------- empty book ----------

    @Test
    void emptyBookHasNoPrices() {
        OrderBook book = new OrderBook();

        assertNull(book.bestBid());
        assertNull(book.bestAsk());
        assertNull(book.spread());
        assertNull(book.mid());
        assertNull(book.bestLevel(Side.BUY));
        assertNull(book.bestLevel(Side.SELL));
    }

    // ---------- price priority ----------

    @Test
    void bestBidIsHighestPrice() {
        OrderBook book = new OrderBook();
        book.addRestingOrder(buy(1, 9998, 10));
        book.addRestingOrder(buy(2, 10000, 10));
        book.addRestingOrder(buy(3, 9999, 10));

        assertEquals(10000L, book.bestBid());
    }

    @Test
    void bestAskIsLowestPrice() {
        OrderBook book = new OrderBook();
        book.addRestingOrder(sell(1, 10004, 10));
        book.addRestingOrder(sell(2, 10002, 10));
        book.addRestingOrder(sell(3, 10003, 10));

        assertEquals(10002L, book.bestAsk());
    }

    @Test
    void bestLevelMatchesBestPrice() {
        OrderBook book = new OrderBook();
        book.addRestingOrder(buy(1, 9999, 10));
        book.addRestingOrder(buy(2, 10000, 10));
        book.addRestingOrder(sell(3, 10003, 10));
        book.addRestingOrder(sell(4, 10002, 10));

        assertEquals(10000L, book.bestLevel(Side.BUY).getPrice());
        assertEquals(10002L, book.bestLevel(Side.SELL).getPrice());
    }

    // ---------- spread and mid ----------

    @Test
    void spreadAndMid() {
        OrderBook book = new OrderBook();
        book.addRestingOrder(buy(1, 10000, 10));
        book.addRestingOrder(sell(2, 10003, 10));

        assertEquals(3L, book.spread());
        double mid = book.mid();
        assertEquals(10001.5, mid, 1e-9);
    }

    @Test
    void spreadAndMidNullWhenOneSideEmpty() {
        OrderBook book = new OrderBook();
        book.addRestingOrder(buy(1, 10000, 10));

        assertNull(book.spread());
        assertNull(book.mid());
    }

    // ---------- time priority within a level ----------

    @Test
    void sameePriceOrdersShareLevelInArrivalOrder() {
        OrderBook book = new OrderBook();
        Order first = buy(1, 10000, 10);
        Order second = buy(2, 10000, 5);
        book.addRestingOrder(first);
        book.addRestingOrder(second);

        PriceLevel level = book.bestLevel(Side.BUY);
        assertEquals(2, level.getOrderCount());
        assertEquals(15, level.getTotalQty());
        assertSame(first, level.peekFirst());
    }

    // ---------- validation ----------

    @Test
    void marketOrderCannotRest() {
        OrderBook book = new OrderBook();
        Order market = new Order(1, Side.BUY, OrderType.MARKET, 0, 10, 1);

        assertThrows(IllegalArgumentException.class, () -> book.addRestingOrder(market));
        assertFalse(book.contains(1));
        assertNull(book.bestBid());
    }

    @Test
    void duplicateIdRejectedWithoutSideEffects() {
        OrderBook book = new OrderBook();
        book.addRestingOrder(buy(1, 10000, 10));
        Order duplicate = sell(1, 10005, 10);

        assertThrows(IllegalArgumentException.class, () -> book.addRestingOrder(duplicate));
        // the rejected order must not leave a ghost level behind
        assertNull(book.bestAsk());
        assertEquals(10000L, book.bestBid());
    }

    // ---------- cancels ----------

    @Test
    void cancelUnknownIdReturnsFalse() {
        OrderBook book = new OrderBook();
        book.addRestingOrder(buy(1, 10000, 10));

        assertFalse(book.cancel(99));
        assertEquals(10000L, book.bestBid());
    }

    @Test
    void cancelRemovesOrderButKeepsNonEmptyLevel() {
        OrderBook book = new OrderBook();
        book.addRestingOrder(buy(1, 10000, 10));
        book.addRestingOrder(buy(2, 10000, 5));

        assertTrue(book.cancel(1));

        assertFalse(book.contains(1));
        assertTrue(book.contains(2));
        assertEquals(10000L, book.bestBid());
        PriceLevel level = book.bestLevel(Side.BUY);
        assertEquals(1, level.getOrderCount());
        assertEquals(5, level.getTotalQty());
    }

    @Test
    void cancellingLastOrderRemovesLevel() {
        OrderBook book = new OrderBook();
        book.addRestingOrder(sell(1, 10002, 10));
        book.addRestingOrder(sell(2, 10003, 10));

        assertTrue(book.cancel(1));

        // no phantom level: best ask moves to the next price
        assertEquals(10003L, book.bestAsk());
    }

    @Test
    void cancellingOnlyOrderEmptiesSide() {
        OrderBook book = new OrderBook();
        book.addRestingOrder(buy(1, 10000, 10));

        assertTrue(book.cancel(1));

        assertNull(book.bestBid());
        assertNull(book.bestLevel(Side.BUY));
    }

    @Test
    void cancelTwiceReturnsFalseSecondTime() {
        OrderBook book = new OrderBook();
        book.addRestingOrder(buy(1, 10000, 10));

        assertTrue(book.cancel(1));
        assertFalse(book.cancel(1));
    }
}