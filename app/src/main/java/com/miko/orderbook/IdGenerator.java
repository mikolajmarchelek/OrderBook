package com.miko.orderbook;

public class IdGenerator {
    private long next = 1;

    public long next() {
        return next++;
    }
}