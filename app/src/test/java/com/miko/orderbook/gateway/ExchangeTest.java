package com.miko.orderbook.gateway;

import com.miko.orderbook.Side;
import com.miko.orderbook.wire.Message.*;
import com.miko.orderbook.wire.RejectReason;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ExchangeTest {

    @Test
    void protocolExampleScenario() {
        Exchange ex = new Exchange();
        assertEquals(List.of(new Accepted(1)),
                ex.handle(new EnterOrder(1, Side.BUY, 100, 5025)));
        assertEquals(List.of(new Accepted(2),
                             new Executed(2, 60, 5025, 0, 1),
                             new Executed(1, 60, 5025, 40, 1)),
                ex.handle(new EnterOrder(2, Side.SELL, 60, 5020)));
        assertEquals(List.of(new Canceled(1, 40)), ex.handle(new CancelOrder(1)));
        assertEquals(List.of(new Rejected(1, RejectReason.UNKNOWN_ORDER)), ex.handle(new CancelOrder(1)));
    }

    @Test
    void rejectsReusedOrLowerOrderId() {
        Exchange ex = new Exchange();
        ex.handle(new EnterOrder(5, Side.BUY, 10, 100));
        assertEquals(List.of(new Rejected(5, RejectReason.DUPLICATE_ORDER_ID)),
                ex.handle(new EnterOrder(5, Side.BUY, 10, 100)));
        assertEquals(List.of(new Rejected(3, RejectReason.DUPLICATE_ORDER_ID)),
                ex.handle(new EnterOrder(3, Side.BUY, 10, 100)));
    }
}