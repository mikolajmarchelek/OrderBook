package com.miko.orderbook.wire;

/** Thrown by the decoder when a message breaks the protocol rules. */
public class ProtocolException extends Exception {
    private final long orderId;
    private final RejectReason reason;

    public ProtocolException(long orderId, RejectReason reason) {
        super(reason + " (order " + orderId + ")");
        this.orderId = orderId;
        this.reason = reason;
    }

    public long orderId() { return orderId; }
    public RejectReason reason() { return reason; }

    /** The J message to send back for this error. */
    public Message.Rejected toRejected() {
        return new Message.Rejected(orderId, reason);
    }
}