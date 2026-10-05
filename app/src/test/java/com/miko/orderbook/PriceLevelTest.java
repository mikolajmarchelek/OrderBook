package com.miko.orderbook;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PriceLevelTest {

    private static final long PRICE = 10001;

    // helper: limit buy at this level's price; id doubles as sequence
    private Order order(long id, long qty) {
        return new Order(id, Side.BUY, OrderType.LIMIT, PRICE, qty, id);
    }

    // ---------- basics ----------

    @Test
    void newLevelIsEmpty() {
        PriceLevel level = new PriceLevel(PRICE);

        assertTrue(level.isEmpty());
        assertEquals(0, level.getTotalQty());
        assertEquals(0, level.getOrderCount());
        assertNull(level.peekFirst());
    }

    @Test
    void addIncreasesTotalQty() {
        PriceLevel level = new PriceLevel(PRICE);

        level.add(order(1, 10));
        level.add(order(2, 5));

        assertEquals(15, level.getTotalQty());
        assertEquals(2, level.getOrderCount());
        assertFalse(level.isEmpty());
    }

    @Test
    void addWithWrongPriceThrows() {
        PriceLevel level = new PriceLevel(PRICE);
        Order wrong = new Order(1, Side.BUY, OrderType.LIMIT, PRICE + 1, 10, 1);

        assertThrows(IllegalArgumentException.class, () -> level.add(wrong));
        assertTrue(level.isEmpty());   // failed add must not change state
    }

    // ---------- time priority (FIFO) ----------

    @Test
    void ordersComeOutInArrivalOrder() {
        PriceLevel level = new PriceLevel(PRICE);
        Order first = order(1, 10);
        Order second = order(2, 5);
        Order third = order(3, 7);
        level.add(first);
        level.add(second);
        level.add(third);

        assertSame(first, level.pollFirst());
        assertSame(second, level.pollFirst());
        assertSame(third, level.pollFirst());
        assertTrue(level.isEmpty());
        assertEquals(0, level.getTotalQty());
    }

    @Test
    void peekDoesNotRemove() {
        PriceLevel level = new PriceLevel(PRICE);
        Order a = order(1, 10);
        level.add(a);

        assertSame(a, level.peekFirst());
        assertSame(a, level.peekFirst());
        assertEquals(1, level.getOrderCount());
        assertEquals(10, level.getTotalQty());
    }

    @Test
    void pollFirstOnEmptyReturnsNull() {
        PriceLevel level = new PriceLevel(PRICE);

        assertNull(level.pollFirst());
        assertEquals(0, level.getTotalQty());
    }

    // ---------- cancels ----------

    @Test
    void removeExistingOrderFromMiddle() {
        PriceLevel level = new PriceLevel(PRICE);
        Order a = order(1, 10);
        Order b = order(2, 5);
        Order c = order(3, 7);
        level.add(a);
        level.add(b);
        level.add(c);

        assertTrue(level.remove(b));
        assertEquals(17, level.getTotalQty());
        assertEquals(2, level.getOrderCount());
        // remaining orders keep their time priority
        assertSame(a, level.pollFirst());
        assertSame(c, level.pollFirst());
    }

    @Test
    void removeMissingOrderReturnsFalse() {
        PriceLevel level = new PriceLevel(PRICE);
        level.add(order(1, 10));
        Order neverAdded = order(2, 5);

        assertFalse(level.remove(neverAdded));
        assertEquals(10, level.getTotalQty());
        assertEquals(1, level.getOrderCount());
    }

    @Test
    void removeIsByIdentityNotByFields() {
        PriceLevel level = new PriceLevel(PRICE);
        Order original = order(1, 10);
        Order lookalike = order(1, 10);   // same fields, different object
        level.add(original);

        assertFalse(level.remove(lookalike));
        assertEquals(1, level.getOrderCount());
    }

    // ---------- fills ----------

    @Test
    void partialFillReducesDepth() {
        PriceLevel level = new PriceLevel(PRICE);
        Order a = order(1, 10);
        level.add(a);

        a.fill(4);
        level.reduceQty(4);

        assertEquals(6, level.getTotalQty());
        assertSame(a, level.peekFirst());   // still at the front
        assertEquals(1, level.getOrderCount());
    }

    // regression test: polling a fully filled order used to call reduceQty(0) and throw
    @Test
    void fullyFilledOrderCanBePolled() {
        PriceLevel level = new PriceLevel(PRICE);
        Order a = order(1, 10);
        level.add(a);

        a.fill(10);
        level.reduceQty(10);
        Order polled = assertDoesNotThrow(() -> level.pollFirst());

        assertSame(a, polled);
        assertTrue(level.isEmpty());
        assertEquals(0, level.getTotalQty());
    }

    // regression test: remove() used to return false for a removed order with 0 remaining
    @Test
    void fullyFilledOrderCanBeRemoved() {
        PriceLevel level = new PriceLevel(PRICE);
        Order a = order(1, 10);
        level.add(a);

        a.fill(10);
        level.reduceQty(10);

        assertTrue(level.remove(a));
        assertTrue(level.isEmpty());
        assertEquals(0, level.getTotalQty());
    }

    @Test
    void reduceQtyRejectsInvalidAmounts() {
        PriceLevel level = new PriceLevel(PRICE);
        level.add(order(1, 10));

        assertThrows(IllegalArgumentException.class, () -> level.reduceQty(0));
        assertThrows(IllegalArgumentException.class, () -> level.reduceQty(-1));
        assertThrows(IllegalArgumentException.class, () -> level.reduceQty(11));
        assertEquals(10, level.getTotalQty());   // rejected calls changed nothing
    }
}