package com.miko.orderbook;

public record Trade(long buyOrderId, long sellOrderId, int buyOwner, int sellOwner, Side aggressor, long price, long quantity, long sequence) {

    public Trade {
        if (price <= 0) throw new IllegalArgumentException("price can't be negative");
        if (quantity <= 0) throw new IllegalArgumentException("Quantity can't be negative");
        if (aggressor == null) throw new IllegalArgumentException("Aggressor can't be null");
    }
    
    public long signedQtyFor(int owner) {
        if (owner == buyOwner && owner == sellOwner) {
            return 0;              
        }
        if (owner == buyOwner) {
            return quantity;
        }
        if (owner == sellOwner) {
            return -quantity;
        }
        return 0;
    }
    public String toCsv() {
        return buyOrderId + "," + sellOrderId + "," + buyOwner + "," + sellOwner + ","
                + aggressor + "," + price + "," + quantity + "," + sequence;
    }

}
