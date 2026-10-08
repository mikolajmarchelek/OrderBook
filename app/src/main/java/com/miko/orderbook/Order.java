package com.miko.orderbook;

public class Order {
    private final long id;
    private final Side side;
    private final OrderType type;
    private final long price;
    private final long originalQty;
    private long remainingQty;
    private final long sequence;
    private final int ownerId;
    public static final int NO_OWNER = 0;

    public Order(long id, int ownerId, Side side, OrderType type, long price, long qty, long sequence) {
        if (qty <= 0) {
            throw new IllegalArgumentException("qty must be positive");
        }
        if(type == OrderType.LIMIT && price <= 0) {
            throw new IllegalArgumentException("Limit Price must be positive");
        }
        this.id = id;
        this.ownerId = ownerId;
        this.side = side;
        this.type = type;
        this.price = price;
        this.originalQty = qty;
        this.remainingQty = qty;
        this.sequence = sequence;
    }

    public Order(long id, Side side, OrderType type, long price, long qty, long sequence) {
        this(id, NO_OWNER, side, type, price, qty, sequence);
    }
    
    
    public void fill(long qty) {
        if (qty <= 0 || qty > remainingQty) {
            throw new IllegalAccessError("Invalid fill qty:" + qty);
        }
        remainingQty -= qty;
    }

    public boolean isFilled() {
        return remainingQty == 0;
    }


    //Setters and getters
    public long getId() {
        return id;
    }

    


    public int getOwnerId() {
        return ownerId;
    }

    public Side getSide() {
        return side;
    }

    public OrderType getType() {
        return type;
    }

    public long getPrice() {
        return price;
    }

    public long getOriginalQty() {
        return originalQty;
    }

    public long getRemainingQty() {
        return remainingQty;
    }

    public long getSequence() {
        return sequence;
    }

    @Override
public String toString() {
    return "Order{id=" + id
            + ", side=" + side
            + ", type=" + type
            + ", price=" + price
            + ", qty=" + remainingQty + "/" + originalQty
            + ", seq=" + sequence 
            + ", owner=" + ownerId + "}";
    }
}
