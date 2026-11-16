package com.miko.orderbook;

public enum Side {
    BUY('B'),
    SELL('S');

    private final char code;            

    Side(char code) {
        this.code = code;              
    }

    public char code() {
        return code;                    
    }

    public static Side fromCode(char c) {
        return switch (c) {
            case 'B' -> BUY;
            case 'S' -> SELL;
            default -> throw new IllegalArgumentException("invalid side: " + c);
        };
    }
}