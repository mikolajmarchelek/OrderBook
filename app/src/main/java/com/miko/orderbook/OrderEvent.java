package com.miko.orderbook;

public record OrderEvent(EventType type, long orderId, int ownerId,
                         Side side, OrderType orderType, long price, long qty) {

    public static OrderEvent submit(Order o) {
        return new OrderEvent(EventType.SUBMIT, o.getId(), o.getOwnerId(),
                o.getSide(), o.getType(), o.getPrice(), o.getRemainingQty());
    }

    public static OrderEvent cancel(long orderId) {
        return new OrderEvent(EventType.CANCEL, orderId, Order.NO_OWNER, null, null, 0, 0);
    }

    public static OrderEvent modify(long orderId, long newPrice, long newQty) {
        return new OrderEvent(EventType.MODIFY, orderId, Order.NO_OWNER, null, null, newPrice, newQty);
    }

    public String toCsv() {
        return type + "," + orderId + "," + ownerId + ","
                + (side == null ? "" : side) + ","
                + (orderType == null ? "" : orderType) + ","
                + price + "," + qty;
    }

    public static OrderEvent fromCsv(String line) {
        String[] f = line.split(",", -1);   
        return new OrderEvent(
                EventType.valueOf(f[0]),
                Long.parseLong(f[1]),
                Integer.parseInt(f[2]),
                f[3].isEmpty() ? null : Side.valueOf(f[3]),
                f[4].isEmpty() ? null : OrderType.valueOf(f[4]),
                Long.parseLong(f[5]),
                Long.parseLong(f[6]));
    }
}