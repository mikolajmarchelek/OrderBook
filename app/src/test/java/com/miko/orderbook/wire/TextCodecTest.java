package com.miko.orderbook.wire;

import com.miko.orderbook.Side;
import com.miko.orderbook.wire.Message.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TextCodecTest {

    // ---------- decode: valid ----------
    @Test
    void decodesEnterOrder() throws Exception {
        assertEquals(new EnterOrder(1, Side.BUY, 100, 5025), TextCodec.decode("O,1,B,100,5025"));
    }

    @Test
    void decodesCancel() throws Exception {
        assertEquals(new CancelOrder(1), TextCodec.decode("X,1"));
    }

    // ---------- encode: the PROTOCOL.md examples ----------
    @Test
    void encodesReplies() {
        assertEquals("A,1",              TextCodec.encode(new Accepted(1)));
        assertEquals("E,2,60,5025,0,1",  TextCodec.encode(new Executed(2, 60, 5025, 0, 1)));
        assertEquals("C,1,40",           TextCodec.encode(new Canceled(1, 40)));
        assertEquals("J,3,Q",            TextCodec.encode(new Rejected(3, RejectReason.INVALID_QUANTITY)));
    }

    @Test
    void roundTrip() throws Exception {
        Message original = new EnterOrder(7, Side.SELL, 60, 5020);
        assertEquals(original, TextCodec.decode(TextCodec.encode(original)));
    }

    // ---------- decode: rejects ----------
    private void assertRejected(String text, long orderId, RejectReason reason) {
        ProtocolException e = assertThrows(ProtocolException.class, () -> TextCodec.decode(text));
        assertEquals(orderId, e.orderId());
        assertEquals(reason, e.reason());
    }

    @Test void zeroQuantity()    { assertRejected("O,3,B,0,5025",   3, RejectReason.INVALID_QUANTITY); }
    @Test void negativePrice()   { assertRejected("O,4,B,100,-1",   4, RejectReason.INVALID_PRICE); }
    @Test void badSide()         { assertRejected("O,5,X,100,5025", 5, RejectReason.INVALID_SIDE); }
    @Test void notANumber()      { assertRejected("O,6,B,abc,5025", 6, RejectReason.MALFORMED); }
    @Test void tooFewFields()    { assertRejected("O,7,B,100",      7, RejectReason.MALFORMED); }
    @Test void unknownType()     { assertRejected("Z,8",            8, RejectReason.MALFORMED); }
    @Test void emptyMessage()    { assertRejected("",               0, RejectReason.MALFORMED); }
}