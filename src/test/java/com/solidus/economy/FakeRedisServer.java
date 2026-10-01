package com.solidus.economy;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal in-JVM Redis server for tests (W-2 round, 2.3.1).
 *
 * <p>Speaks enough RESP2 for everything {@link RedisLayer} does —
 * {@code PING, AUTH, SELECT, GET, SET, SETEX, DEL, PUBLISH, SUBSCRIBE,
 * UNSUBSCRIBE, QUIT} plus tolerant {@code HELLO/CLIENT} replies — over a
 * REAL TCP socket on loopback, so the full Jedis client path (connect
 * handshake, command encoding, pub/sub push frames, null bulk decoding) is
 * exercised without any infrastructure. Real-Redis fidelity stays covered in
 * CI by {@code RedisLayerTest} (live {@code redis:7} container) and
 * {@code RedisPackagingSmokeTest} (the built jar).</p>
 *
 * <p>Deliberately forgiving: unknown commands get a Redis-style error reply
 * (like a real server), never a dropped connection, and disconnects just
 * unregister subscriptions.</p>
 */
final class FakeRedisServer implements AutoCloseable {

    private final ServerSocket server;
    private final Thread acceptLoop;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "FakeRedis-Client");
        t.setDaemon(true);
        return t;
    });

    /** key -> (value, expireAtMillis; 0 = no expiry). */
    private final Map<String, Expiring> store = new ConcurrentHashMap<>();
    /** channel -> subscribed client sockets (push frames are cross-thread). */
    private final Map<String, Set<Socket>> subscriptions = new ConcurrentHashMap<>();

    private volatile boolean closed;

    private record Expiring(byte[] data, long expireAt) {
        boolean expired() {
            return expireAt > 0 && System.currentTimeMillis() >= expireAt;
        }
    }

    private FakeRedisServer(int port) throws IOException {
        server = new ServerSocket(port, 64, InetAddress.getLoopbackAddress());
        acceptLoop = new Thread(this::acceptLoop, "FakeRedis-Accept");
        acceptLoop.setDaemon(true);
        acceptLoop.start();
    }

    static FakeRedisServer start() throws IOException {
        return new FakeRedisServer(0); // ephemeral port
    }

    int port() {
        return server.getLocalPort();
    }

    /** A ready-to-use {@code redis://} URI for this instance. */
    String uri() {
        return "redis://127.0.0.1:" + port() + "/0";
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = server.accept();
                pool.execute(() -> handle(socket));
            } catch (IOException e) {
                if (!closed) return;
            }
        }
    }

    // -- one connection, one thread ----------------------------------------

    private void handle(Socket socket) {
        try (socket) {
            socket.setTcpNoDelay(true);
            var in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            while (!closed) {
                List<byte[]> cmd = readCommand(in);
                if (cmd == null || cmd.isEmpty()) return;
                dispatch(socket, out, cmd);
            }
        } catch (IOException ignored) {
            // client went away
        } finally {
            for (Set<Socket> subs : subscriptions.values()) {
                subs.remove(socket);
            }
        }
    }

    private void dispatch(Socket socket, OutputStream out, List<byte[]> cmd) throws IOException {
        String name = str(cmd.get(0)).toUpperCase(Locale.ROOT);
        switch (name) {
            case "PING" -> {
                if (cmd.size() > 1) {
                    writeBulk(out, cmd.get(1));
                } else {
                    writeSimple(out, "PONG");
                }
            }
            // Redis replies to HELLO with a flat array of key/value pairs; an
            // array reply is valid in both RESP2 and RESP3.
            case "HELLO" -> writeArray(out,
                "server".getBytes(StandardCharsets.UTF_8), "fake-redis".getBytes(StandardCharsets.UTF_8),
                "version".getBytes(StandardCharsets.UTF_8), "1.0.0".getBytes(StandardCharsets.UTF_8),
                "proto".getBytes(StandardCharsets.UTF_8), "2".getBytes(StandardCharsets.UTF_8));
            case "CLIENT", "COMMAND", "CONFIG", "AUTH", "SELECT" -> writeSimple(out, "OK");
            case "GET" -> {
                String key = str(cmd.get(1));
                Expiring value = store.get(key);
                if (value == null) {
                    writeNullBulk(out);
                } else if (value.expired()) {
                    store.remove(key, value);
                    writeNullBulk(out);
                } else {
                    writeBulk(out, value.data());
                }
            }
            case "SET" -> {
                store.put(str(cmd.get(1)), new Expiring(cmd.get(2), 0L));
                writeSimple(out, "OK");
            }
            case "SETEX" -> {
                long ttlSeconds = Long.parseLong(str(cmd.get(2)));
                store.put(str(cmd.get(1)),
                    new Expiring(cmd.get(3), System.currentTimeMillis() + ttlSeconds * 1000L));
                writeSimple(out, "OK");
            }
            case "DEL" -> {
                int removed = 0;
                for (int i = 1; i < cmd.size(); i++) {
                    if (store.remove(str(cmd.get(i))) != null) removed++;
                }
                writeInt(out, removed);
            }
            case "PUBLISH" -> publish(out, str(cmd.get(1)), cmd.get(2));
            case "SUBSCRIBE" -> {
                for (int i = 1; i < cmd.size(); i++) {
                    String channel = str(cmd.get(i));
                    subscriptions.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet()).add(socket);
                    writePush(out, "subscribe", cmd.get(i), subscribedCount(socket));
                }
            }
            case "UNSUBSCRIBE" -> {
                for (Set<Socket> subs : subscriptions.values()) {
                    subs.remove(socket);
                }
                // Jedis sends bare UNSUBSCRIBE: the channel element is nil and
                // the count must reach 0 so its process() loop exits.
                writePush(out, "unsubscribe", null, 0);
            }
            case "QUIT" -> {
                writeSimple(out, "OK");
                throw new IOException("quit"); // ends the handler loop cleanly
            }
            default -> writeError(out, "unknown command '" + name + "'");
        }
        out.flush();
    }

    private void publish(OutputStream out, String channel, byte[] message) throws IOException {
        int count = 0;
        Set<Socket> subs = subscriptions.get(channel);
        if (subs != null && !subs.isEmpty()) {
            for (Socket sub : List.copyOf(subs)) {
                try {
                    synchronized (sub) {
                        OutputStream subOut = sub.getOutputStream();
                        writeArray(subOut,
                            "message".getBytes(StandardCharsets.UTF_8),
                            channel.getBytes(StandardCharsets.UTF_8),
                            message);
                        subOut.flush();
                    }
                    count++;
                } catch (IOException deadSubscriber) {
                    subs.remove(sub);
                }
            }
        }
        writeInt(out, count);
    }

    // -- RESP2 wire format ---------------------------------------------------

    /**
     * Push-style 3-element reply: {@code [kind, channel, count]} where kind
     * and channel are bulk strings (channel may be nil) and count is a RESP2
     * INTEGER — exactly the shape real Redis sends for subscribe/unsubscribe
     * confirmations. Jedis hard-casts element 3 to {@link Long}
     * ({@code checkcast} in {@code JedisPubSubBase.process}), so a bulk
     * string there would throw {@code ClassCastException} in its subscriber
     * thread and the subscription would never confirm.
     */
    private static void writePush(OutputStream out, String kind, byte[] channel, long count)
            throws IOException {
        out.write(("*3\r\n").getBytes(StandardCharsets.UTF_8));
        writeBulk(out, kind.getBytes(StandardCharsets.UTF_8));
        if (channel == null) {
            writeNullBulk(out);
        } else {
            writeBulk(out, channel);
        }
        writeInt(out, count);
    }

    /** How many channels this connection is currently subscribed to — real
     * Redis counts cumulatively across SUBSCRIBE commands on one connection. */
    private int subscribedCount(Socket socket) {
        int n = 0;
        for (Set<Socket> subs : subscriptions.values()) {
            if (subs.contains(socket)) n++;
        }
        return n;
    }

    /** Reads one command (array of bulk strings); null on clean EOF. */
    private static List<byte[]> readCommand(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null || line.isEmpty()) return null;
        if (line.startsWith("*")) {
            int count = Integer.parseInt(line.substring(1).trim());
            List<byte[]> parts = new ArrayList<>(Math.max(0, count));
            for (int i = 0; i < count; i++) {
                String header = readLine(in);
                if (header == null || !header.startsWith("$")) {
                    throw new IOException("bad bulk header: " + header);
                }
                int len = Integer.parseInt(header.substring(1).trim());
                if (len < 0) {
                    parts.add(null);
                    continue;
                }
                byte[] data = in.readNBytes(len);
                if (data.length != len) throw new IOException("truncated bulk body");
                readLine(in); // trailing \r\n
                parts.add(data);
            }
            return parts;
        }
        // Inline commands (unusual, tolerated)
        List<byte[]> parts = new ArrayList<>();
        for (String token : line.trim().split("\\s+")) {
            parts.add(token.getBytes(StandardCharsets.UTF_8));
        }
        return parts;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b = in.read();
        if (b == -1) return null;
        while (b != -1 && b != '\r') {
            buf.write(b);
            b = in.read();
        }
        if (b == '\r' && in.read() != '\n') {
            throw new IOException("malformed line terminator");
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static void writeSimple(OutputStream out, String s) throws IOException {
        out.write(('+' + s + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void writeError(OutputStream out, String s) throws IOException {
        out.write(("-" + s + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void writeInt(OutputStream out, long n) throws IOException {
        out.write((":" + n + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBulk(OutputStream out, byte[] data) throws IOException {
        out.write(("$" + data.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeNullBulk(OutputStream out) throws IOException {
        out.write("$-1\r\n".getBytes(StandardCharsets.UTF_8));
    }

    /** Array of bulk strings; a null element is encoded as a nil bulk. */
    private static void writeArray(OutputStream out, byte[]... items) throws IOException {
        out.write(("*" + items.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        for (byte[] item : items) {
            if (item == null) {
                writeNullBulk(out);
            } else {
                writeBulk(out, item);
            }
        }
    }

    private static String str(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }
}
