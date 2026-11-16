package com.miko.orderbook.wire;

import com.miko.orderbook.Side;
import com.miko.orderbook.wire.Message.*;

/** Converts between PROTOCOL.md v0.1 text and Message objects. Stateless. */
public final class TextCodec {

    private TextCodec() {}            // only static methods, so no instances needed

    // ===================== ENCODE =====================

    public static String encode(Message m) {
        if (m instanceof EnterOrder o)
            return "O," + o.orderId() + "," + o.side().code() + "," + o.quantity() + "," + o.price();
        if (m instanceof CancelOrder x)
            return "X," + x.orderId();
        if (m instanceof Accepted a)
            return "A," + a.orderId();
        if (m instanceof Executed e)
            return "E," + e.orderId() + "," + e.quantity() + "," + e.price()
                    + "," + e.leavesQty() + "," + e.matchId();
        if (m instanceof Canceled c)
            return "C," + c.orderId() + "," + c.canceledQty();
        if (m instanceof Rejected j)
            return "J," + j.orderId() + "," + j.reason().code();
        throw new IllegalArgumentException("unknown message: " + m);
    }

    // ===================== DECODE (client -> exchange) =====================

    public static Message decode(String text) throws ProtocolException {
        String[] f = text.strip().split(",", -1);   // -1 keeps empty fields, e.g. "O,1,,100,5"

        if (f[0].length() != 1) {
            throw new ProtocolException(orderIdOrZero(f), RejectReason.MALFORMED);
        }
        return switch (f[0].charAt(0)) {
            case 'O' -> decodeEnterOrder(f);
            case 'X' -> decodeCancel(f);
            default  -> throw new ProtocolException(orderIdOrZero(f), RejectReason.MALFORMED);
        };
    }

    private static EnterOrder decodeEnterOrder(String[] f) throws ProtocolException {
        // Rule 1 (F): right number of fields, and the numbers parse
        if (f.length != 5) throw new ProtocolException(orderIdOrZero(f), RejectReason.MALFORMED);
        long orderId  = parseLong(f[1], 0);
        long quantity = parseLong(f[3], orderId);
        long price    = parseLong(f[4], orderId);

        // Rule 3 (S): side is exactly one valid character
        Side side;
        try {
            if (f[2].length() != 1) throw new IllegalArgumentException();
            side = Side.fromCode(f[2].charAt(0));
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(orderId, RejectReason.INVALID_SIDE);
        }

        // Rules 4-5 (Q, P): positive quantity and price
        if (quantity <= 0) throw new ProtocolException(orderId, RejectReason.INVALID_QUANTITY);
        if (price <= 0)    throw new ProtocolException(orderId, RejectReason.INVALID_PRICE);

        return new EnterOrder(orderId, side, quantity, price);
    }

    private static CancelOrder decodeCancel(String[] f) throws ProtocolException {
        if (f.length != 2) throw new ProtocolException(orderIdOrZero(f), RejectReason.MALFORMED);
        return new CancelOrder(parseLong(f[1], 0));
    }

    // ===================== helpers =====================

    /** Parses a number, or rejects the message as malformed (F). */
    private static long parseLong(String s, long orderIdForReject) throws ProtocolException {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new ProtocolException(orderIdForReject, RejectReason.MALFORMED);
        }
    }

    /** Best-effort order ID for a reject when the message is broken; 0 if unknown. */
    private static long orderIdOrZero(String[] f) {
        if (f.length < 2) return 0;
        try {
            return Long.parseLong(f[1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}