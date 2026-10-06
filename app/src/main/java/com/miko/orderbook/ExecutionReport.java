package com.miko.orderbook;

import java.util.List;

public record ExecutionReport(long orderId, Side side, Double arrivalMid, List<Trade> fills) {

    // total quantity filled across all trades
    public long filledQty() {
        long total = 0;
        for (Trade trades : fills){
            total += trades.quantity();
        }
        return total;
    }

    // volume-weighted average fill price, in ticks. null if nothing filled
    public Double avgPrice() {
        if (filledQty() == 0) {
            return null;
        }
        long qty = 0;
        long notional = 0;
        for (Trade trades : fills) {
            qty += trades.quantity();
            notional += trades.price() * trades.quantity();
        }
        if (qty == 0) {
            return null;
        }
        return (double) notional/qty;
    }

    // cost vs. arrival mid, in ticks. positive = worse than mid.
    // null if nothing filled OR arrivalMid is null (empty side at arrival)
    public Double slippageTicks() { 
        Double avg = avgPrice();
        if (avg == null || arrivalMid == null) {
            return null;
        }
        if (side == Side.BUY) {
            return avg-arrivalMid;
        }
        return arrivalMid-avg;

    }
}