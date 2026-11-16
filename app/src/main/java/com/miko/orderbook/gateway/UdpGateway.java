package com.miko.orderbook.gateway;

import com.miko.orderbook.wire.Message;
import com.miko.orderbook.wire.ProtocolException;
import com.miko.orderbook.wire.TextCodec;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** UDP front door of the exchange: receive text, decode, run the exchange, send replies. */
public class UdpGateway {

    public static void main(String[] args) throws Exception {
        int port = 9000;
        boolean verbose = args.length > 0 && args[0].equals("-v");

        Exchange exchange = new Exchange();
        DatagramSocket socket = new DatagramSocket(port);
        System.out.println("Exchange gateway listening on UDP port " + port);

        byte[] buf = new byte[256];
        DatagramPacket in = new DatagramPacket(buf, buf.length);

        while (true) {
            in.setLength(buf.length);
            socket.receive(in);
            String text = new String(buf, 0, in.getLength(), StandardCharsets.US_ASCII);
            if (verbose) System.out.println("<- " + text);

            List<Message> replies;
            try {
                replies = exchange.handle(TextCodec.decode(text));
            } catch (ProtocolException e) {
                replies = List.of(e.toRejected());                    // bad message -> J, never reaches the engine
            }

            for (Message reply : replies) {                           // one reply per datagram
                String outText = TextCodec.encode(reply);
                byte[] out = outText.getBytes(StandardCharsets.US_ASCII);
                socket.send(new DatagramPacket(out, out.length, in.getSocketAddress()));
                if (verbose) System.out.println("-> " + outText);
            }
        }
    }
}