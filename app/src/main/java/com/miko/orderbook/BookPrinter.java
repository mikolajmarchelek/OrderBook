package com.miko.orderbook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BookPrinter {
    private static final double TICK_SIZE = 0.01;

    private BookPrinter() {}   // utility class: never instantiated

    // ticks → human price, for DISPLAY only: 10002 → "100.02"
    public static String formatPrice(long ticks) {
        return String.format("%.2f", ticks * TICK_SIZE);
    }

    public static String render(OrderBook book, int levels) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-4s %10s %8s %7s%n", "", "PRICE", "QTY", "ORDERS"));

        // asks: reverse so the best ask is at the BOTTOM, next to the spread
        List<DepthLevel> asks = new ArrayList<>(book.depth(Side.SELL, levels));
        Collections.reverse(asks);

        for (DepthLevel level : asks) {
            sb.append(row("ASK", level));
        }

        Long spread = book.spread();
        Double mid = book.mid();

        if (spread == null) {
            sb.append(String.format("------ one side empty ------%n"));
        }else {
            sb.append(String.format("------ spread %s | mid %.3f ------%n",
            formatPrice(spread), mid * TICK_SIZE));
        }

        List<DepthLevel> bids = new ArrayList<>(book.depth(Side.BUY, levels));
        for (DepthLevel level: bids) {
            sb.append(row("BID", level));
        }

        return sb.toString();
    }

    private static String row(String label, DepthLevel level) {
        return String.format("%-4s %10s %8d %7d%n",
                label, formatPrice(level.price()), level.totalQty(), level.orderCount());
    }
}