package com.miko.orderbook;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class OrderFlowGenerator {
    private final MatchingEngine engine;
    private final FairPrice fairPrice;
    private final Random random;

    private double aggressiveness;           // 0..1: chance a new order takes liquidity
    private final double cancelProb = 0.3;   // chance an event is a cancel
    private final int maxOffsetTicks = 5;    // passive orders: 1..5 ticks from fair
    private final int maxQty = 50;           // order sizes: 1..50

    private final IdGenerator ids;
    private final List<Long> restingIds = new ArrayList<>();   // candidates for cancels

    public OrderFlowGenerator(MatchingEngine engine, FairPrice fairPrice,
                    Random random, double aggressiveness, IdGenerator ids) {
       this.engine = engine;
       this.fairPrice = fairPrice;
       this.random = random;
       this.ids = ids;
       setAggressiveness(aggressiveness);
   }

    // One event: move the fair price, then cancel OR submit a new order.
    public List<Trade> step() {
        fairPrice.step();

        if (!restingIds.isEmpty() && random.nextDouble() < cancelProb) {
            cancelRandom();
            return List.of();
        }

        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
        Order order = random.nextDouble() < aggressiveness
                ? aggressiveOrder(side)
                : passiveOrder(side);

        List<Trade> trades = engine.submit(order);

        // remember it only if it's actually resting now
        if (engine.getBook().contains(order.getId())) {
            restingIds.add(order.getId());
        }
        return trades;
    }

    // LIMIT at fair - offset (buy) or fair + offset (sell)
    private Order passiveOrder(Side side) {
        long offset = 1 + random.nextInt(maxOffsetTicks);
        long fair = fairPrice.roundedTicks();
        long price;
        if (side == Side.BUY) {
            price = fair - offset;
        } else {
            price = fair + offset;
        }
        price = Math.max(1, price);
        long id = newId();
        return new Order(id, side, OrderType.LIMIT, price, randomQty(), id);
    }

    // MARKET order, takes whatever is there
    private Order aggressiveOrder(Side side) {
        long id = newId();
        return new Order(id, side ,OrderType.MARKET, 0, randomQty(), id);
    }

    // pick a random resting id, cancel it, remove it from the list
    private void cancelRandom() { 
        int randIdx = random.nextInt(restingIds.size());
        long removed = restingIds.get(randIdx);
        engine.cancel(removed);
        restingIds.remove(randIdx);
    }

    private long randomQty() {
        return 1 + random.nextInt(maxQty);   // 1..maxQty
    }

    private long newId() {
       return ids.next();
   }

    public void setAggressiveness(double aggressiveness) {
        if (aggressiveness < 0 || aggressiveness > 1) {
            throw new IllegalArgumentException("aggressiveness must be in [0, 1]: " + aggressiveness);
        }
        this.aggressiveness = aggressiveness;
    }

    public double getAggressiveness() { return aggressiveness; }
}