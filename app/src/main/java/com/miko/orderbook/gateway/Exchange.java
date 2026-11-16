package com.miko.orderbook.gateway;

import com.miko.orderbook.MatchingEngine;
import com.miko.orderbook.Order;
import com.miko.orderbook.OrderBook;
import com.miko.orderbook.OrderType;
import com.miko.orderbook.Side;
import com.miko.orderbook.Trade;
import com.miko.orderbook.wire.Message;
import com.miko.orderbook.wire.Message.*;
import com.miko.orderbook.wire.RejectReason;

import java.util.ArrayList;
import java.util.List;

/** Exchange logic: one message in, the reply messages out. No networking here. */
public class Exchange {

    private final OrderBook book = new OrderBook();
    private final MatchingEngine engine = new MatchingEngine(book);
    private long lastOrderId = 0;          // order IDs must be strictly increasing

    public List<Message> handle(Message in) {
        if (in instanceof EnterOrder o) return enter(o);
        if (in instanceof CancelOrder c) return cancel(c);
        // a client sent a message only the exchange is supposed to send
        return List.of(new Rejected(0, RejectReason.MALFORMED));
    }

    private List<Message> enter(EnterOrder o) {
        // Rule D: an ID that isn't higher than the last one was already used (or is a resend)
        if (o.orderId() <= lastOrderId) {
            return List.of(new Rejected(o.orderId(), RejectReason.DUPLICATE_ORDER_ID));
        }
        lastOrderId = o.orderId();

        Order order = new Order(o.orderId(), o.side(), OrderType.LIMIT,
                                o.price(), o.quantity(), o.orderId());
        List<Trade> trades = engine.submit(order);

        List<Message> replies = new ArrayList<>();
        replies.add(new Accepted(o.orderId()));

        long incomingLeaves = o.quantity();
        for (Trade t : trades) {
            // the incoming order's side of the trade
            incomingLeaves -= t.quantity();
            replies.add(new Executed(o.orderId(), t.quantity(), t.price(), incomingLeaves, t.sequence()));

            // the resting order's side of the same trade (same matchId)
            long restingId = (o.side() == Side.BUY) ? t.sellOrderId() : t.buyOrderId();
            Order resting = book.getOrder(restingId);                 // null once fully filled
            long restingLeaves = (resting == null) ? 0 : resting.getRemainingQty();
            replies.add(new Executed(restingId, t.quantity(), t.price(), restingLeaves, t.sequence()));
        }
        return replies;
    }

    private List<Message> cancel(CancelOrder c) {
        Order resting = book.getOrder(c.orderId());
        if (resting == null) {                                        // never existed, or already done
            return List.of(new Rejected(c.orderId(), RejectReason.UNKNOWN_ORDER));
        }
        long canceledQty = resting.getRemainingQty();                 // read BEFORE it's removed
        engine.cancel(c.orderId());
        return List.of(new Canceled(c.orderId(), canceledQty));
    }
}