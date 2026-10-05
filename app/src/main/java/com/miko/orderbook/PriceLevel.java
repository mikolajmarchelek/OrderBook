package com.miko.orderbook;

import java.util.ArrayDeque;

public class PriceLevel {
    private final long price;
    private final ArrayDeque<Order> orders = new ArrayDeque<>();
    private long totalQty;

    public PriceLevel(long price) {
        this.price = price;
    }

    
    // Add order to the BACK of the queue (it arrived last → fills last).
    // Throw if order.getPrice() != this.price. Update totalQty.
    public void add(Order order) {
        if (order.getPrice() != this.price) {
            throw new IllegalArgumentException("order price " + order.getPrice() + " != level price " + price);
        }
        orders.addLast(order);
        totalQty += order.getRemainingQty();
    }

    // Return the oldest order WITHOUT removing it (null if empty).
    public Order peekFirst() {
        return orders.peek();
    }

    // Remove and return the oldest order. Update totalQty.
    public Order pollFirst() {
        Order ord = orders.poll();
        if (ord != null && ord.getRemainingQty() > 0) {
            reduceQty(ord.getRemainingQty());
        }
        return ord;

    }

    // Remove a specific order (for cancels). Update totalQty.
    public boolean remove(Order order) {
        boolean removed = orders.remove(order);
        if (removed && order.getRemainingQty() > 0) {
            reduceQty(order.getRemainingQty());
        }
        return removed;
    }

    // Called after a resting order is partially filled: totalQty -= qty.
    public void reduceQty(long qty) {
        if (qty <= 0 || qty > totalQty){
            throw new IllegalArgumentException("the quantity is outside the range");
        }
        totalQty -= qty;
    }

    public boolean isEmpty() {
        return orders.size() == 0;
    }
    public long getPrice() {return price;}
    public long getTotalQty() {return totalQty;}
    public int getOrderCount() {return orders.size();}

}
