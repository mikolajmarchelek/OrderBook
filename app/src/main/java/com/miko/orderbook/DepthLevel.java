package com.miko.orderbook;

public record DepthLevel(long price, long totalQty, int orderCount) {

    public DepthLevel {
        if (price <= 0) throw new IllegalArgumentException("price can't be negative");
        if (totalQty <= 0) throw new IllegalArgumentException("Quantity can't be negative");
    }
}
