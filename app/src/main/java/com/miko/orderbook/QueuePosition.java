package com.miko.orderbook;

public record QueuePosition(long price, Side side, long remainingQty, int position, long qtyAhead) {
}