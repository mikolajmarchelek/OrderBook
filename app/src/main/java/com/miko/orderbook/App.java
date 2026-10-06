package com.miko.orderbook;

public class App {
    public String getGreeting() {
        return "Hello World!";
    }

    public static void main(String[] args) {
        OrderBook book = new OrderBook();
        MatchingEngine engine = new MatchingEngine(book);

        // build a book (none of these cross, so they all rest)
        engine.submit(new Order(1, Side.SELL, OrderType.LIMIT, 10004, 30, 1));
        engine.submit(new Order(2, Side.SELL, OrderType.LIMIT, 10002, 50, 2));
        engine.submit(new Order(3, Side.SELL, OrderType.LIMIT, 10003, 12, 3));
        engine.submit(new Order(4, Side.SELL, OrderType.LIMIT, 10002, 10, 4));
        engine.submit(new Order(5, Side.BUY,  OrderType.LIMIT, 10000, 20, 5));
        engine.submit(new Order(6, Side.BUY,  OrderType.LIMIT,  9999, 45, 6));

        System.out.println("=== BEFORE ===");
        System.out.println(BookPrinter.render(book, 5));

        // aggressive buy: sweeps 10002 and part of 10003
        System.out.println(">>> BUY LIMIT 65 @ 100.03");
        ExecutionReport report = engine.execute(new Order(7, Side.BUY, OrderType.LIMIT, 10003, 65, 7));

        for (Trade t : report.fills()) {
            System.out.println("TRADE " + t.quantity() + " @ " + BookPrinter.formatPrice(t.price())
                    + "  (buy #" + t.buyOrderId() + " / sell #" + t.sellOrderId() + ")");
        }

        System.out.printf("arrival mid %.3f | filled %d | avg %.4f | slippage %.3f ticks%n",
                report.arrivalMid() / 100.0,
                report.filledQty(),
                report.avgPrice() / 100.0,
                report.slippageTicks());

        System.out.println();
        System.out.println("=== AFTER ===");
        System.out.println(BookPrinter.render(book, 5));
    }
}