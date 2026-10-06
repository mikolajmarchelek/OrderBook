package com.miko.orderbook;

import java.util.List;

public class Simulator {
    private final MatchingEngine engine;
    private final FairPrice fairPrice;
    private final OrderFlowGenerator flow;
    private int eventsPerSecond;

    public Simulator(MatchingEngine engine, FairPrice fairPrice,
                     OrderFlowGenerator flow, int eventsPerSecond) {
        this.engine = engine;
        this.fairPrice = fairPrice;
        this.flow = flow;
        this.eventsPerSecond = eventsPerSecond;
    }

    public void run(int seconds) throws InterruptedException {
        for (int s = 1; s <= seconds; s++) {
            int tradesThisSecond = 0;
            long volumeThisSecond = 0;

            for (int e = 0; e < eventsPerSecond; e++) {
                List<Trade> trades = flow.step();
                tradesThisSecond += trades.size();
                for (Trade trade : trades) {
                    volumeThisSecond += trade.quantity();
                }
            }

            System.out.print("\033[H\033[2J");   // clear the terminal (ANSI codes)
            System.out.printf("t=%ds | fair %.2f | trades %d | volume %d | aggressiveness %.2f%n%n",
                    s, fairPrice.getValue() / 100.0,
                    tradesThisSecond, volumeThisSecond, flow.getAggressiveness());
            System.out.println(BookPrinter.render(engine.getBook(), 8));

            Thread.sleep(1000);
        }
    }
}