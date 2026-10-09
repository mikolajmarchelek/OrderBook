package com.miko.orderbook;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class DeterminismTest {

    // Run the background-flow simulator for `steps` events with one seed.
    private MatchingEngine runSim(long seed, int steps) {
        Random random = new Random(seed);
        MatchingEngine engine = new MatchingEngine(new OrderBook());
        FairPrice fair = new FairPrice(10000, 0.5, random);
        OrderFlowGenerator flow = new OrderFlowGenerator(engine, fair, random, 0.2, new IdGenerator());
        for (int i = 0; i < steps; i++) {
            flow.step();
        }
        return engine;
    }

    @Test
    void sameSeedGivesIdenticalTrades() {
        MatchingEngine a = runSim(42, 5_000);
        MatchingEngine b = runSim(42, 5_000);

        assertFalse(a.getTradeLog().isEmpty());          // sanity: something actually traded
        assertEquals(a.getTradeLog(), b.getTradeLog());
        assertEquals(a.getBook().depth(Side.BUY, 10), b.getBook().depth(Side.BUY, 10));
        assertEquals(a.getBook().depth(Side.SELL, 10), b.getBook().depth(Side.SELL, 10));
    }

    @Test
    void differentSeedGivesDifferentTrades() {
        assertNotEquals(runSim(42, 5_000).getTradeLog(), runSim(7, 5_000).getTradeLog());
    }

    @Test
    void replayingTheEventLogReproducesTheRun() {
        MatchingEngine original = runSim(42, 5_000);

        MatchingEngine replayed = Replayer.replay(original.getEventLog());

        assertEquals(original.getTradeLog(), replayed.getTradeLog());
        assertEquals(original.getBook().depth(Side.BUY, 10), replayed.getBook().depth(Side.BUY, 10));
        assertEquals(original.getBook().depth(Side.SELL, 10), replayed.getBook().depth(Side.SELL, 10));
    }

    @Test
    void agentSubmitModifyCancelAreLoggedAndReplayed() {
        MatchingEngine engine = new MatchingEngine(new OrderBook());
        engine.submit(new Order(1, 2, Side.SELL, OrderType.LIMIT, 10002, 5, 1));
        engine.submit(new Order(2, 1, Side.BUY, OrderType.LIMIT, 10000, 10, 2));
        engine.modify(2, 10002, 10);    // crosses → trades 5
        engine.cancel(2);               // pull the leftover

        assertEquals(List.of(EventType.SUBMIT, EventType.SUBMIT, EventType.MODIFY, EventType.CANCEL),
                engine.getEventLog().stream().map(OrderEvent::type).toList());

        MatchingEngine replayed = Replayer.replay(engine.getEventLog());
        assertEquals(engine.getTradeLog(), replayed.getTradeLog());
        assertFalse(replayed.getBook().contains(2));
    }

    @Test
    void selfTradeCancelsAreNotLoggedAsInputs() {
        MatchingEngine engine = new MatchingEngine(new OrderBook());
        engine.submit(new Order(1, 1, Side.BUY, OrderType.LIMIT, 10000, 5, 1));
        engine.submit(new Order(2, 1, Side.SELL, OrderType.LIMIT, 10000, 5, 2));   // STP cancels #1

        // only the two submits: the cancel was a consequence, and replay recreates it
        assertEquals(2, engine.getEventLog().size());
        assertFalse(Replayer.replay(engine.getEventLog()).getBook().contains(1));
    }

    @Test
    void csvRoundTripPreservesEveryEvent() {
        List<OrderEvent> events = runSim(42, 2_000).getEventLog();

        List<OrderEvent> parsed = new ArrayList<>();
        for (OrderEvent e : events) {
            parsed.add(OrderEvent.fromCsv(e.toCsv()));
        }

        assertEquals(events, parsed);
    }
}