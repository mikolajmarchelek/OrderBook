package com.miko.orderbook;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SideTest {

    @Test
    void codesRoundTrip() {
        assertEquals('B', Side.BUY.code());
        assertEquals('S', Side.SELL.code());
        assertEquals(Side.BUY, Side.fromCode('B'));
        assertEquals(Side.SELL, Side.fromCode('S'));
    }

    @Test
    void invalidCodeThrows() {
        assertThrows(IllegalArgumentException.class, () -> Side.fromCode('X'));
    }
}