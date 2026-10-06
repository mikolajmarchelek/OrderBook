package com.miko.orderbook;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BookPrinterTest {

    @Test
    void rendersEmptyBookWithoutCrashing() {
        String out = assertDoesNotThrow(() -> BookPrinter.render(new OrderBook(), 5));
        assertTrue(out.contains("one side empty"));
    }

    @Test
    void rendersOneSidedBookWithoutCrashing() {
        OrderBook book = new OrderBook();
        book.addRestingOrder(new Order(1, Side.BUY, OrderType.LIMIT, 10000, 10, 1));

        String out = assertDoesNotThrow(() -> BookPrinter.render(book, 5));
        assertTrue(out.contains("one side empty"));
        assertTrue(out.contains("100.00"));
    }

    @Test
    void formatsTicksAsPrices() {
        assertEquals("100.02", BookPrinter.formatPrice(10002));
        assertEquals("99.99", BookPrinter.formatPrice(9999));
    }
}