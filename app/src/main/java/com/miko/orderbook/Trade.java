package com.miko.orderbook;

public record Trade(long buyOrderId, long sellOrderId, long price, long quantity, long sequence) {

    public Trade {
        if (price <= 0) throw new IllegalArgumentException("price can't be negative");
        if (quantity <= 0) throw new IllegalArgumentException("Quantity can't be negative");
    }

}
