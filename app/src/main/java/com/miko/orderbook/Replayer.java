package com.miko.orderbook;

import java.util.List;

public final class Replayer {
    private Replayer() {}   // utility class: only static methods, never instantiated

    // Feed events into a fresh engine, in order. Returns the engine so callers
    // can compare its trade log and book with the original run.
    public static MatchingEngine replay(List<OrderEvent> events) {
        MatchingEngine engine = new MatchingEngine(new OrderBook());

        for (OrderEvent e : events) {
            switch (e.type()) {
                case SUBMIT -> engine.submit(new Order(
                        e.orderId(), e.ownerId(), e.side(), e.orderType(),
                        e.price(), e.qty(), e.orderId()));
                case CANCEL -> engine.cancel(e.orderId());
                case MODIFY -> engine.modify(e.orderId(), e.price(), e.qty());
            }
        }
        return engine;
    }
}