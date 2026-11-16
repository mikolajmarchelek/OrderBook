package com.miko.orderbook.wire;

public enum RejectReason {
    INVALID_QUANTITY('Q'),
    INVALID_PRICE('P'),
    INVALID_SIDE('S'),
    DUPLICATE_ORDER_ID('D'),
    UNKNOWN_ORDER('U'),
    MALFORMED('F');

    private final char code;

    RejectReason(char code) {
        this.code = code;
    }

    public char code() {
        return code;
    }

    public static RejectReason fromCode(char c) {
        for (RejectReason r : values()) {      // values() = all enum constants
            if (r.code == c) return r;
        }
        throw new IllegalArgumentException("invalid reject reason: " + c);
    }
}