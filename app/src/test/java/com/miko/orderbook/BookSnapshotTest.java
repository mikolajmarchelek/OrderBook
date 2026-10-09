package com.miko.orderbook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BookSnapshotTest {

    private OrderBook book;
    private MatchingEngine engine;

    @BeforeEach
    void setUp() {
        book = new OrderBook();
        engine = new MatchingEngine(book);
    }

    private void limit(long id, Side side, long price, long qty) {
        engine.submit(new Order(id, side, OrderType.LIMIT, price, qty, id));
    }

    @Test
    void snapshotHasFixedShapeAndPadsMissingLevelsWithZeros() {
        limit(1, Side.BUY, 10000, 5);
        limit(2, Side.BUY, 9999, 7);
        limit(3, Side.SELL, 10002, 4);

        BookSnapshot s = book.snapshot(3);

        assertEquals(3, s.levels());
        assertArrayEquals(new long[]{10000, 9999, 0}, s.bidPrices());   // best first, padded
        assertArrayEquals(new long[]{5, 7, 0}, s.bidQtys());
        assertArrayEquals(new long[]{10002, 0, 0}, s.askPrices());
        assertArrayEquals(new long[]{4, 0, 0}, s.askQtys());
    }

    @Test
    void levelsAggregateAllOrdersAtThatPrice() {
        limit(1, Side.SELL, 10002, 4);
        limit(2, Side.SELL, 10002, 6);

        assertEquals(10, book.snapshot(1).askQtys()[0]);
    }

    @Test
    void snapshotsWithSameContentAreEqual() {
        limit(1, Side.BUY, 10000, 5);
        limit(2, Side.SELL, 10002, 4);

        // two separate snapshot objects, separate arrays, same numbers
        assertEquals(book.snapshot(5), book.snapshot(5));
        assertEquals(book.snapshot(5).hashCode(), book.snapshot(5).hashCode());
    }

    @Test
    void snapshotDoesNotChangeWhenBookChangesLater() {
        limit(1, Side.BUY, 10000, 5);
        BookSnapshot before = book.snapshot(2);

        limit(2, Side.BUY, 10001, 3);   // new best bid after the snapshot

        assertEquals(10000, before.bidPrices()[0]);
    }

    @Test
    void imbalanceIsPositiveWhenMoreBidsAndBounded() {
        limit(1, Side.BUY, 10000, 30);
        limit(2, Side.SELL, 10002, 10);

        assertEquals(0.5, book.snapshot(5).imbalance(1), 1e-12);   // (30 - 10) / 40
    }

    @Test
    void imbalanceOnlyCountsTopKLevels() {
        limit(1, Side.BUY, 10000, 10);
        limit(2, Side.BUY, 9990, 1000);   // deep, far from the touch
        limit(3, Side.SELL, 10002, 10);

        BookSnapshot s = book.snapshot(5);
        assertEquals(0.0, s.imbalance(1), 1e-12);                  // top level is balanced
        assertTrue(s.imbalance(2) > 0.9);                          // deeper view sees the big bid
    }

    @Test
    void imbalanceOfEmptyBookIsZero() {
        assertEquals(0.0, book.snapshot(5).imbalance(5), 1e-12);
    }

    @Test
    void imbalanceRejectsBadK() {
        BookSnapshot s = book.snapshot(3);
        assertThrows(IllegalArgumentException.class, () -> s.imbalance(0));
        assertThrows(IllegalArgumentException.class, () -> s.imbalance(4));
    }
}