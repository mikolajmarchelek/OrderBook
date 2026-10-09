package com.miko.orderbook;

import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.HashMap;
import java.util.TreeMap;

public class OrderBook {
    // price → level. Bids: highest first. Asks: lowest first.
    private final TreeMap<Long, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, PriceLevel> asks = new TreeMap<>();
    // order id → order, for O(1) cancel lookup
    private final HashMap<Long, Order> orderIndex = new HashMap<>();

    // Picks the right tree for a side, so the rest of the code doesn't need if/else everywhere.
    private TreeMap<Long, PriceLevel> levelsFor(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    // Put a LIMIT order into the book (no matching here; that's Step 3).
    public void addRestingOrder(Order order) { 
        if (order.getType() == OrderType.MARKET || orderIndex.containsKey(order.getId())) {
            throw new IllegalArgumentException ("Adding a resting order must have a limit. Also the order id can't be a duplicate");
        }
        PriceLevel level = levelsFor(order.getSide()).computeIfAbsent(order.getPrice(), p -> new PriceLevel(p));
        level.add(order);
        orderIndex.put(order.getId(), order);

    }

    // Cancel by id. Returns false if the id isn't in the book.
    public boolean cancel(long orderId) { 
        Order order = orderIndex.get(orderId);
        if (order == null) {
            return false;
        }
        TreeMap<Long, PriceLevel> levels = levelsFor(order.getSide());
        PriceLevel level = levels.get(order.getPrice());
        
        if (level == null) {
            throw new IllegalStateException("order " + orderId + " is indexed but no level exists at price " + order.getPrice());
        }

        level.remove(order);
        if (level.isEmpty()) {
            levels.remove(order.getPrice());
        }
        orderIndex.remove(orderId);
        return true;
    }

    // Best prices. null if that side is empty.
    public Long bestBid() {
        if (bids.isEmpty()) {
            return null;
        } else {
            return bids.firstKey();
        }
    }
    public Long bestAsk() {
        if (asks.isEmpty()) {
            return null;
        } else {
            return asks.firstKey();
        }
    }

    // Spread in ticks (bestAsk - bestBid). null if either side is empty.
    public Long spread() { 
        if(bestBid() == null || bestAsk() == null) {
            return null;
        } else {
            return bestAsk()-bestBid();
        }
    }

    // Mid in ticks, as double: (10001 + 10002) / 2 = 10001.5
    public Double mid() { 
        if (bestBid() == null || bestAsk() == null) {
            return null;
        } else {
        return (bestAsk() + bestBid()) / 2.0; 
        }
    }

    // Best level on a side, for the matching engine. null if empty.
    public PriceLevel bestLevel(Side side) {
        var entry = levelsFor(side).firstEntry();
        return entry == null ? null : entry.getValue();
    }

    // The live resting order, or null if it isn't in the book.
    public Order getOrder(long orderId) {
        return orderIndex.get(orderId);
    }

    // Reduce a resting order's qty in place, keeping its queue position.
    // Returns false if the order isn't in the book.
    public boolean reduce(long orderId, long newQty) {
        if(getOrder(orderId) == null) {
            return false;
        }
        Order order = orderIndex.get(orderId);
        TreeMap<Long, PriceLevel> levels = levelsFor(order.getSide());
        PriceLevel level = levels.get(order.getPrice());

        level.reduceQty(order.getRemainingQty() - newQty);
        order.reduceQtyTo(newQty);
        return true;
    }

    public boolean contains(long orderId) { return orderIndex.containsKey(orderId); }

    //Top N levels on one side, best price first. It's an immutable snapshot.
    public List<DepthLevel> depth(Side side, int levels) {
        ArrayList<DepthLevel> result = new ArrayList<>();

        for (PriceLevel level : levelsFor(side).values()) {
            if (result.size() == levels) {
                break;
            }
            result.add(new DepthLevel(level.getPrice(), level.getTotalQty(), level.getOrderCount()));
        }
        return result;
    }
    //Snapshot of a resting order's place in its queue. null if not in the book.
    public QueuePosition queuePosition(long orderId) {
        Order order = orderIndex.get(orderId);
        if (order == null) {
            return null;
        }
        PriceLevel level = levelsFor(order.getSide()).get(order.getPrice());
        if (level == null) {
            throw new IllegalStateException("order " + orderId + " is indexed but no level exists at " + order.getPrice());       
        }
        return level.positionOf(order);
    }
}
