package com.miko.orderbook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MatchingEngineTest {

    private OrderBook book;
    private MatchingEngine engine;

    // runs before EVERY test → each test starts with a fresh, empty book
    @BeforeEach
    void setUp() {
        book = new OrderBook();
        engine = new MatchingEngine(book);
    }

    private Order limit(long id, Side side, long price, long qty) {
        return new Order(id, side, OrderType.LIMIT, price, qty, id);
    }

    private Order market(long id, Side side, long qty) {
        return new Order(id, side, OrderType.MARKET, 0, qty, id);
    }

    // ---------- no match ----------

    @Test
    void limitOrderRestsOnEmptyBook() {
        List<Trade> trades = engine.submit(limit(1, Side.BUY, 10000, 10));

        assertTrue(trades.isEmpty());
        assertEquals(10000L, book.bestBid());
        assertTrue(book.contains(1));
    }

    @Test
    void nonCrossingBuyRests() {
        engine.submit(limit(1, Side.SELL, 10002, 10));
        List<Trade> trades = engine.submit(limit(2, Side.BUY, 10000, 10));

        assertTrue(trades.isEmpty());
        assertEquals(10000L, book.bestBid());
        assertEquals(10002L, book.bestAsk());
    }

    @Test
    void nonCrossingSellRests() {
        engine.submit(limit(1, Side.BUY, 9999, 10));
        List<Trade> trades = engine.submit(limit(2, Side.SELL, 10000, 10));

        assertTrue(trades.isEmpty());
        assertEquals(1L, book.spread());
    }

    // ---------- basic matching ----------

    @Test
    void equalPricesTrade() {
        engine.submit(limit(1, Side.SELL, 10002, 10));
        List<Trade> trades = engine.submit(limit(2, Side.BUY, 10002, 10));

        assertEquals(List.of(new Trade(2, 1, 10002, 10, 1)), trades);
        // both fully filled → book empty
        assertNull(book.bestBid());
        assertNull(book.bestAsk());
        assertFalse(book.contains(1));
        assertFalse(book.contains(2));
    }

    @Test
    void tradeHappensAtRestingPrice() {
        engine.submit(limit(1, Side.SELL, 10002, 10));
        List<Trade> trades = engine.submit(limit(2, Side.BUY, 10005, 10));

        // buyer was willing to pay 10005, gets price improvement to 10002
        assertEquals(10002, trades.get(0).price());
    }

    @Test
    void sellerIsIncomingSideMirror() {
        engine.submit(limit(1, Side.BUY, 10000, 10));
        engine.submit(limit(2, Side.BUY, 9999, 10));
        List<Trade> trades = engine.submit(limit(3, Side.SELL, 9999, 15));

        assertEquals(List.of(
                new Trade(1, 3, 10000, 10, 1),   // resting bid is the buyer
                new Trade(2, 3, 9999, 5, 2)
        ), trades);
        assertEquals(9999L, book.bestBid());
        assertEquals(5, book.bestLevel(Side.BUY).getTotalQty());
        assertNull(book.bestAsk());   // incoming sell fully filled, didn't rest
    }

    // ---------- price-time priority ----------

    @Test
    void sweepsMultipleLevelsInPriceOrder() {
        // the worked example from the lesson
        engine.submit(limit(2, Side.SELL, 10002, 50));
        engine.submit(limit(7, Side.SELL, 10002, 10));
        engine.submit(limit(4, Side.SELL, 10003, 12));
        engine.submit(limit(3, Side.BUY, 10000, 20));

        List<Trade> trades = engine.submit(limit(9, Side.BUY, 10003, 65));

        assertEquals(List.of(
                new Trade(9, 2, 10002, 50, 1),
                new Trade(9, 7, 10002, 10, 2),
                new Trade(9, 4, 10003, 5, 3)
        ), trades);
        // level 10002 fully consumed and deleted → best ask moves up
        assertEquals(10003L, book.bestAsk());
        assertEquals(7, book.bestLevel(Side.SELL).getTotalQty());
        assertFalse(book.contains(2));
        assertFalse(book.contains(7));
        assertTrue(book.contains(4));
        // incoming fully filled → did not rest; bids unchanged
        assertFalse(book.contains(9));
        assertEquals(10000L, book.bestBid());
    }

    @Test
    void oldestOrderAtLevelFillsFirst() {
        engine.submit(limit(1, Side.SELL, 10002, 10));
        engine.submit(limit(2, Side.SELL, 10002, 10));
        List<Trade> trades = engine.submit(limit(3, Side.BUY, 10002, 10));

        assertEquals(1, trades.get(0).sellOrderId());   // #1 arrived first
        assertTrue(book.contains(2));
        assertFalse(book.contains(1));
    }

    @Test
    void partiallyFilledRestingOrderKeepsItsPlace() {
        Order first = limit(1, Side.SELL, 10002, 20);
        engine.submit(first);
        engine.submit(limit(2, Side.SELL, 10002, 10));

        engine.submit(limit(3, Side.BUY, 10002, 5));

        PriceLevel level = book.bestLevel(Side.SELL);
        assertSame(first, level.peekFirst());   // still at the front
        assertEquals(15, first.getRemainingQty());
        assertEquals(25, level.getTotalQty());
    }

    // ---------- leftovers ----------

    @Test
    void limitLeftoverRestsWithRemainingQty() {
        engine.submit(limit(1, Side.SELL, 10002, 10));
        List<Trade> trades = engine.submit(limit(2, Side.BUY, 10003, 25));

        assertEquals(1, trades.size());
        assertEquals(10003L, book.bestBid());
        assertEquals(15, book.bestLevel(Side.BUY).getTotalQty());   // not 25!
        assertNull(book.bestAsk());
    }

    @Test
    void marketOrderSweepsAndLeftoverIsDiscarded() {
        engine.submit(limit(1, Side.SELL, 10002, 10));
        engine.submit(limit(2, Side.SELL, 10005, 10));

        List<Trade> trades = engine.submit(market(3, Side.BUY, 30));

        assertEquals(List.of(
                new Trade(3, 1, 10002, 10, 1),
                new Trade(3, 2, 10005, 10, 2)
        ), trades);
        assertNull(book.bestAsk());
        assertNull(book.bestBid());     // leftover 10 did NOT rest
        assertFalse(book.contains(3));
    }

    @Test
    void marketOrderOnEmptyBookDoesNothing() {
        List<Trade> trades = engine.submit(market(1, Side.SELL, 10));

        assertTrue(trades.isEmpty());
        assertNull(book.bestBid());
        assertNull(book.bestAsk());
    }

    // ---------- validation & bookkeeping ----------

    @Test
    void duplicateIdRejectedBeforeAnyTrade() {
        Order resting = limit(1, Side.SELL, 10002, 10);
        engine.submit(resting);

        assertThrows(IllegalArgumentException.class,
                () -> engine.submit(limit(1, Side.BUY, 10002, 10)));
        // nothing traded: resting order untouched
        assertEquals(10, resting.getRemainingQty());
        assertEquals(10, book.bestLevel(Side.SELL).getTotalQty());
    }

    @Test
    void tradeSequenceKeepsIncreasingAcrossSubmits() {
        engine.submit(limit(1, Side.SELL, 10002, 10));
        engine.submit(limit(2, Side.SELL, 10002, 10));

        Trade t1 = engine.submit(limit(3, Side.BUY, 10002, 10)).get(0);
        Trade t2 = engine.submit(limit(4, Side.BUY, 10002, 10)).get(0);

        assertEquals(1, t1.sequence());
        assertEquals(2, t2.sequence());
    }

        // ---------- queue position ----------

    @Test
    void queuePositionCountsOrdersAndQtyAhead() {
        engine.submit(limit(1, Side.BUY, 10000, 40));
        engine.submit(limit(2, Side.BUY, 10000, 80));
        engine.submit(limit(3, Side.BUY, 10000, 10));   // "mine"

        QueuePosition qp = book.queuePosition(3);

        assertEquals(new QueuePosition(10000, Side.BUY, 10, 3, 120), qp);
    }

    @Test
    void frontOfQueueHasNothingAhead() {
        engine.submit(limit(1, Side.SELL, 10002, 25));

        QueuePosition qp = book.queuePosition(1);

        assertEquals(1, qp.position());
        assertEquals(0, qp.qtyAhead());
    }

    @Test
    void queuePositionImprovesWhenOrdersAheadFillOrCancel() {
        engine.submit(limit(1, Side.BUY, 10000, 40));
        engine.submit(limit(2, Side.BUY, 10000, 80));
        engine.submit(limit(3, Side.BUY, 10000, 10));   // mine: 3rd, 120 ahead

        engine.submit(market(4, Side.SELL, 30));        // partially fills #1 → 10 left
        assertEquals(new QueuePosition(10000, Side.BUY, 10, 3, 90), book.queuePosition(3));

        book.cancel(2);                                 // order ahead of me cancels
        assertEquals(new QueuePosition(10000, Side.BUY, 10, 2, 10), book.queuePosition(3));
    }

    @Test
    void queuePositionNullWhenNotResting() {
        engine.submit(limit(1, Side.SELL, 10002, 10));
        engine.submit(limit(2, Side.BUY, 10002, 10));   // fully fills #1

        assertNull(book.queuePosition(1));    // filled → gone
        assertNull(book.queuePosition(2));    // fully filled, never rested
        assertNull(book.queuePosition(999));  // never existed
    }
}