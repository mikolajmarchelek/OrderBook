package com.miko.orderbook;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// Runs the simulator with a fixed seed and writes the files the Python twin is checked against:
//   events.csv     every input the engine received, in order
//   trades.csv     every trade the engine produced
//   snapshots.csv  the top N levels of the book after every K-th event
//
// Usage: ParityExport <seed> <steps> <outDir> <snapshotEvery> <levels>
public class ParityExport {

    private static final int AGENT = 1;

    public static void main(String[] args) throws IOException {
        long seed = Long.parseLong(args[0]);
        int steps = Integer.parseInt(args[1]);
        Path outDir = Path.of(args[2]);
        int snapshotEvery = Integer.parseInt(args[3]);
        int levels = Integer.parseInt(args[4]);

        Random random = new Random(seed);
        MatchingEngine engine = new MatchingEngine(new OrderBook());
        FairPrice fair = new FairPrice(10000, 0.5, random);
        IdGenerator ids = new IdGenerator();
        OrderFlowGenerator flow = new OrderFlowGenerator(engine, fair, random, 0.2, ids);
        ScriptedQuoter agent = new ScriptedQuoter(engine, fair, random, ids);

        // (eventCount, snapshot) pairs; eventCount = how many events had been applied
        List<Long> snapshotAt = new ArrayList<>();
        List<BookSnapshot> snapshots = new ArrayList<>();
        long nextSnapshot = snapshotEvery;

        for (int i = 0; i < steps; i++) {
            flow.step();
            if (i % 10 == 0) {
                agent.requote();          // exercises owners, modify and self-trade prevention
            }
            long eventCount = engine.getEventLog().size();
            if (eventCount >= nextSnapshot) {
                snapshotAt.add(eventCount);
                snapshots.add(engine.getBook().snapshot(levels));
                nextSnapshot = eventCount + snapshotEvery;
            }
        }

        Files.createDirectories(outDir);
        writeEvents(outDir.resolve("events.csv"), engine.getEventLog());
        writeTrades(outDir.resolve("trades.csv"), engine.getTradeLog());
        writeSnapshots(outDir.resolve("snapshots.csv"), snapshotAt, snapshots);

        System.out.printf("seed=%d steps=%d -> %d events, %d trades, %d snapshots in %s%n",
                seed, steps, engine.getEventLog().size(), engine.getTradeLog().size(),
                snapshots.size(), outDir.toAbsolutePath());
    }

    private static void writeEvents(Path file, List<OrderEvent> events) throws IOException {
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(file))) {
            out.println("type,orderId,ownerId,side,orderType,price,qty");
            for (OrderEvent e : events) {
                out.println(e.toCsv());
            }
        }
    }

    private static void writeTrades(Path file, List<Trade> trades) throws IOException {
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(file))) {
            out.println("buyOrderId,sellOrderId,buyOwner,sellOwner,aggressor,price,quantity,sequence");
            for (Trade t : trades) {
                out.println(t.toCsv());
            }
        }
    }

    // Long format: one row per (snapshot, side, level). Padded levels appear as price 0, qty 0.
    private static void writeSnapshots(Path file, List<Long> at, List<BookSnapshot> snaps) throws IOException {
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(file))) {
            out.println("afterEvent,side,level,price,qty");
            for (int s = 0; s < snaps.size(); s++) {
                BookSnapshot snap = snaps.get(s);
                for (int lvl = 0; lvl < snap.levels(); lvl++) {
                    out.println(at.get(s) + ",BUY," + lvl + "," + snap.bidPrices()[lvl] + "," + snap.bidQtys()[lvl]);
                }
                for (int lvl = 0; lvl < snap.levels(); lvl++) {
                    out.println(at.get(s) + ",SELL," + lvl + "," + snap.askPrices()[lvl] + "," + snap.askQtys()[lvl]);
                }
            }
        }
    }

    // A dumb market maker with a real owner id: keeps one bid and one ask near fair value,
    // moving them with modify. Not a strategy, just a way to put agent-style events in the log.
    private static final class ScriptedQuoter {
        private final MatchingEngine engine;
        private final FairPrice fair;
        private final Random random;
        private final IdGenerator ids;
        private long bidId = -1;
        private long askId = -1;

        ScriptedQuoter(MatchingEngine engine, FairPrice fair, Random random, IdGenerator ids) {
            this.engine = engine;
            this.fair = fair;
            this.random = random;
            this.ids = ids;
        }

        void requote() {
            long center = fair.roundedTicks();
            long qty = 1 + random.nextInt(20);
            bidId = quote(bidId, Side.BUY, Math.max(1, center - 1 - random.nextInt(3)), qty);
            askId = quote(askId, Side.SELL, center + 1 + random.nextInt(3), qty);
        }

        // Modify the order if it's still resting, otherwise submit a fresh one. Returns its id.
        private long quote(long id, Side side, long price, long qty) {
            if (id != -1 && engine.getBook().contains(id)) {
                engine.modify(id, price, qty);
                return id;
            }
            long newId = ids.next();
            engine.submit(new Order(newId, AGENT, side, OrderType.LIMIT, price, qty, newId));
            return newId;
        }
    }
}