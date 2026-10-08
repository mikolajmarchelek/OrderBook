package com.miko.orderbook;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.List;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)       // report average time per operation
@OutputTimeUnit(TimeUnit.NANOSECONDS)  // ... in nanoseconds
public class OrderBookBenchmark {

    // JMH runs every benchmark once per value: queues of 10, 100 and 1000 orders
    @Param({"10", "100", "1000"})
    public int queueDepth;

    private static final long DEEP_LEVEL = 9_980;   

    private OrderBook book;
    private MatchingEngine engine;
    private long nextId;
    private long oldestId;   // id of the order at the front of the deep level

    // runs before each iteration, OUTSIDE the measured time
    @Setup(Level.Iteration)
    public void setUp() {
        book = new OrderBook();
        engine = new MatchingEngine(book);
        nextId = 1;

        // background book: 10 bid and 10 ask levels around 10000
        for (int i = 1; i <= 10; i++) {
            engine.submit(order(Side.BUY, 10_000 - i, 50));
            engine.submit(order(Side.SELL, 10_000 + i, 50));
        }

        // one deep level with queueDepth orders
        oldestId = nextId;
        for (int i = 0; i < queueDepth; i++) {
            engine.submit(order(Side.BUY, DEEP_LEVEL, 10));
        }
    }

    private Order order(Side side, long price, long qty) {
        long id = nextId++;
        return new Order(id, side, OrderType.LIMIT, price, qty, id);
    }

    // Add to the BACK of the deep queue, then cancel it.
    // Cancel must scan past all queueDepth orders, expect O(n).
    @Benchmark
    public boolean addThenCancelBack() {
        Order o = order(Side.BUY, DEEP_LEVEL, 10);
        engine.submit(o);
        return book.cancel(o.getId());
    }

    // Add to the back, cancel the FRONT (oldest) order.
    // The queue keeps its size, and cancel finds its target immediately, expect O(1).
    @Benchmark
    public boolean addThenCancelFront() {
        engine.submit(order(Side.BUY, DEEP_LEVEL, 10));
        return book.cancel(oldestId++);
    }

    // A sell rests inside the spread (new level), then a buy matches it fully (level deleted).
    // Measures resting + matching + creating a trade + level create/delete.
    @Benchmark
    public List<Trade> restThenMatch() {
        engine.submit(order(Side.SELL, 10_000, 10));
        return engine.submit(order(Side.BUY, 10_000, 10));
    }
}