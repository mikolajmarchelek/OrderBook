package com.miko.orderbook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MatchingEngine {

    private final OrderBook book;
    private long nextTradeSeq = 1;
    private final List<Trade> tradeLog = new ArrayList<>();

    public MatchingEngine(OrderBook book) {
        this.book = book;
    }

    public OrderBook getBook() {return book;}

     // Match an incoming order against the book. Returns the trades it produced.
    public List<Trade> submit(Order incoming) {
        // 0. reject an id that's already resting in the book before any trades happen
        if (book.contains(incoming.getId())) {
            throw new IllegalArgumentException("the book already contains that order");
        }

        List<Trade> trades = new ArrayList<>();
        Side opposite = incoming.getSide() == Side.BUY ? Side.SELL : Side.BUY;

        while (incoming.getRemainingQty() > 0) {
            PriceLevel best = book.bestLevel(opposite);
            if (best == null) {
                break;
            }
            if (!crosses(incoming, best.getPrice())) {
                break;
            }
            Order resting = best.peekFirst();
            long qty = Math.min(incoming.getRemainingQty(), resting.getRemainingQty());

            incoming.fill(qty);
            resting.fill(qty);
            best.reduceQty(qty);

            long buyId; 
            long sellId;
            if (incoming.getSide() == Side.BUY) {
                buyId = incoming.getId();
                sellId = resting.getId();
            } else {
                buyId = resting.getId();
                sellId = incoming.getId();
            }
            trades.add(new Trade(buyId, sellId, best.getPrice(), qty, nextTradeSeq++));

            if (resting.isFilled()) {
                book.cancel(resting.getId());
            }
        }
        if (incoming.getRemainingQty() != 0 && incoming.getType() == OrderType.LIMIT) {
            book.addRestingOrder(incoming);
        }
        tradeLog.addAll(trades);
        return trades;
    }

    // Does the incoming order accept this resting price?
    private boolean crosses(Order incoming, long restingPrice) {
        if (incoming.getType() == OrderType.MARKET) {
            return true;
        }
        if (incoming.getSide() == Side.BUY) {
            return incoming.getPrice() >= restingPrice;

        }
        if (incoming.getSide() == Side.SELL) {
            return incoming.getPrice() <= restingPrice;
        }
        throw new IllegalStateException("unknown side: " + incoming.getSide());
    }

    public List<Trade> getTradeLog() {
       return Collections.unmodifiableList(tradeLog);
   }

   // Like submit, but also measures execution quality.
    public ExecutionReport execute(Order incoming) {
        Double midAtArrival = book.mid();       
        List<Trade> fills = submit(incoming);
        return new ExecutionReport(incoming.getId(), incoming.getSide(), midAtArrival, fills);
}
}
