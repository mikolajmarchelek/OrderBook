package com.miko.orderbook.wire;

import com.miko.orderbook.Side;

/** Every message in PROTOCOL.md v0.1. */
public sealed interface Message {

    // ---- client -> exchange ----
    record EnterOrder(long orderId, Side side, long quantity, long price) implements Message {}
    record CancelOrder(long orderId) implements Message {}

    // ---- exchange -> client ----
    record Accepted(long orderId) implements Message {}
    record Executed(long orderId, long quantity, long price, long leavesQty, long matchId) implements Message {}
    record Canceled(long orderId, long canceledQty) implements Message {}
    record Rejected(long orderId, RejectReason reason) implements Message {}
}