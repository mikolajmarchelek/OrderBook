package com.miko.orderbook;

public class App {
    public String getGreeting() {
        return "Hello World!";
    }
    public static void main(String[] args) throws InterruptedException {
    java.util.Random random = new java.util.Random(42);   // one seed for everything

    OrderBook book = new OrderBook();
    MatchingEngine engine = new MatchingEngine(book);
    FairPrice fairPrice = new FairPrice(10000, 0.5, random);
    OrderFlowGenerator flow = new OrderFlowGenerator(engine, fairPrice, random, 0.5);
    Simulator sim = new Simulator(engine, fairPrice, flow, 50);

    sim.run(30);
}
}