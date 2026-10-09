package com.miko.orderbook;

import java.util.Arrays;

// Fixed-shape view of the top N levels on each side, best price first.
// Missing levels are padded with price 0 and qty 0.
public record BookSnapshot(long[] bidPrices, long[] bidQtys, long[] askPrices, long[] askQtys) {

    public int levels() { return bidPrices.length; }

    // (bid qty - ask qty) / (bid qty + ask qty) over the top k levels. In [-1, 1]; 0 if both empty.
    public double imbalance(int k) {
        if (k <1 || k > levels()) {
            throw new IllegalArgumentException("k must be in [1, " + levels() + "]: " + k);
        }
        long bid = 0;
        long ask = 0;
        for (int i = 0; i < k; i++) {
            bid += bidQtys[i];
            ask += askQtys[i];
        }
        long total = bid + ask;
        if (total == 0) {
            return 0.0;
        }
        return (double) (bid-ask) / total;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BookSnapshot s)) return false;
        return Arrays.equals(bidPrices, s.bidPrices) && Arrays.equals(bidQtys, s.bidQtys)
                && Arrays.equals(askPrices, s.askPrices) && Arrays.equals(askQtys, s.askQtys);
    }

    @Override
    public int hashCode() {
        int h = Arrays.hashCode(bidPrices);
        h = 31 * h + Arrays.hashCode(bidQtys);
        h = 31 * h + Arrays.hashCode(askPrices);
        h = 31 * h + Arrays.hashCode(askQtys);
        return h;
    }

    @Override
    public String toString() {
        return "BookSnapshot{bids=" + Arrays.toString(bidPrices) + "x" + Arrays.toString(bidQtys)
                + ", asks=" + Arrays.toString(askPrices) + "x" + Arrays.toString(askQtys) + "}";
    }
}