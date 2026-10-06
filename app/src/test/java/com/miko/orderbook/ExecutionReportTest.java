package com.miko.orderbook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionReportTest {

    private OrderBook book;
    private MatchingEngine engine;

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

    @Test
    void buySweepMatchesDemo() {
        engine.submit(limit(1, Side.SELL, 10004, 30));
        engine.submit(limit(2, Side.SELL, 10002, 50));
        engine.submit(limit(3, Side.SELL, 10003, 12));
        engine.submit(limit(4, Side.SELL, 10002, 10));
        engine.submit(limit(5, Side.BUY, 10000, 20));

        ExecutionReport r = engine.execute(limit(7, Side.BUY, 10003, 65));

        assertEquals(10001.0, r.arrivalMid(), 1e-9);
        assertEquals(65, r.filledQty());
        // (60 * 10002 + 5 * 10003) / 65
        assertEquals(650135.0 / 65, r.avgPrice(), 1e-9);
        assertEquals(650135.0 / 65 - 10001.0, r.slippageTicks(), 1e-9);
    }

    @Test
    void sellSlippageIsPositiveWhenBelowMid() {
        engine.submit(limit(1, Side.BUY, 10000, 10));
        engine.submit(limit(2, Side.SELL, 10002, 10));   // mid = 10001

        ExecutionReport r = engine.execute(market(3, Side.SELL, 10));

        assertEquals(10000.0, r.avgPrice(), 1e-9);
        assertEquals(1.0, r.slippageTicks(), 1e-9);      // sold 1 tick below mid → cost
    }

    @Test
    void noFillGivesNullAvgAndSlippage() {
        engine.submit(limit(1, Side.SELL, 10002, 10));

        ExecutionReport r = engine.execute(limit(2, Side.BUY, 10000, 10));   // doesn't cross

        assertEquals(0, r.filledQty());
        assertNull(r.avgPrice());
        assertNull(r.slippageTicks());
    }

    @Test
    void emptySideAtArrivalGivesNullSlippageButRealAvg() {
        engine.submit(limit(1, Side.SELL, 10002, 10));   // no bids → no mid

        ExecutionReport r = engine.execute(market(2, Side.BUY, 5));

        assertNull(r.arrivalMid());
        assertEquals(10002.0, r.avgPrice(), 1e-9);       // it DID fill
        assertNull(r.slippageTicks());                   // but no benchmark to compare to
    }

    @Test
    void midIsCapturedBeforeMatching() {
        engine.submit(limit(1, Side.BUY, 10000, 10));
        engine.submit(limit(2, Side.SELL, 10002, 10));

        ExecutionReport r = engine.execute(market(3, Side.BUY, 10));

        // the ask side is now empty, so book.mid() is null AFTER matching
        assertNull(book.mid());
        // but the report kept the mid from BEFORE
        assertEquals(10001.0, r.arrivalMid(), 1e-9);
    }

    // ---------- trade log ----------

    @Test
    void tradeLogAccumulatesAcrossOrders() {
        engine.submit(limit(1, Side.SELL, 10002, 10));
        engine.submit(limit(2, Side.SELL, 10003, 10));
        engine.submit(limit(3, Side.BUY, 10002, 5));
        engine.execute(market(4, Side.BUY, 10));

        assertEquals(3, engine.getTradeLog().size());
        assertEquals(1, engine.getTradeLog().get(0).sequence());
        assertEquals(3, engine.getTradeLog().get(2).sequence());
    }

    @Test
    void tradeLogIsReadOnly() {
        engine.submit(limit(1, Side.SELL, 10002, 10));
        engine.submit(limit(2, Side.BUY, 10002, 10));

        assertThrows(UnsupportedOperationException.class, () -> engine.getTradeLog().clear());
        assertEquals(1, engine.getTradeLog().size());
    }
}