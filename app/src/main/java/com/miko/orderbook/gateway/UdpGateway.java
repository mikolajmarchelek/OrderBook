package com.miko.orderbook.gateway;

import com.miko.orderbook.wire.Message;
import com.miko.orderbook.wire.ProtocolException;
import com.miko.orderbook.wire.TextCodec;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** UDP front door of the exchange, with per-stage timing. */
public class UdpGateway {

    private static final int RECORD = 15000;      // messages to time: 5000 warm-up + 10000 measured

    public static void main(String[] args) throws Exception {
        int port = 9000;
        boolean verbose = args.length > 0 && args[0].equals("-v");

        Exchange exchange = new Exchange();
        DatagramSocket socket = new DatagramSocket(port);
        System.out.println("Exchange gateway listening on UDP port " + port);

        byte[] buf = new byte[256];
        DatagramPacket in = new DatagramPacket(buf, buf.length);

        // timing storage: allocated ONCE, before the loop
        long[] parseNs = new long[RECORD];
        long[] matchNs = new long[RECORD];
        long[] sendNs  = new long[RECORD];
        int count = 0;

        while (true) {
            in.setLength(buf.length);
            socket.receive(in);
            long t1 = System.nanoTime();                              // T1: message received

            String text = new String(buf, 0, in.getLength(), StandardCharsets.US_ASCII);
            if (verbose) System.out.println("<- " + text);

            List<Message> replies;
            long t2;
            try {
                Message msg = TextCodec.decode(text);
                t2 = System.nanoTime();                               // T2: decoded
                replies = exchange.handle(msg);
            } catch (ProtocolException e) {
                t2 = System.nanoTime();
                replies = List.of(e.toRejected());
            }
            long t3 = System.nanoTime();                              // T3: matched

            for (Message reply : replies) {
                String outText = TextCodec.encode(reply);
                byte[] out = outText.getBytes(StandardCharsets.US_ASCII);
                socket.send(new DatagramPacket(out, out.length, in.getSocketAddress()));
                if (verbose) System.out.println("-> " + outText);
            }
            long t4 = System.nanoTime();                              // T4: all replies sent

            if (count < RECORD) {
                parseNs[count] = t2 - t1;
                matchNs[count] = t3 - t2;
                sendNs[count]  = t4 - t3;
                count++;
                if (count == RECORD) {
                    writeCsv(parseNs, matchNs, sendNs);               // once, after the run
                }
            }
        }
    }

    private static void writeCsv(long[] parse, long[] match, long[] send) throws IOException {
        try (PrintWriter w = new PrintWriter("stages.csv")) {
            w.println("parse_ns,match_ns,send_ns");
            for (int i = 0; i < parse.length; i++) {
                w.println(parse[i] + "," + match[i] + "," + send[i]);
            }
        }
        System.out.println("Wrote stages.csv (" + parse.length + " messages)");
    }
}