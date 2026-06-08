package app.zcat.infochat.collector.stream.nostr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A minimal in-process NIP-01 relay for tests. A plain blocking
 * {@link ServerSocket} on virtual threads that speaks just enough of
 * RFC 6455 itself (the JDK ships only a WebSocket <em>client</em>): it
 * answers the opening handshake, reads and unmasks inbound client text
 * frames (the REQ the subscriber sends), and writes unmasked server text
 * frames to push EVENT/EOSE messages or drop the connection on demand.
 * Shared by every Nostr ticket (M1-096..M1-101); keep it protocol-
 * generic, not test-specific.
 *
 * <p>Deliberately framework-free: a standalone {@code Vertx.vertx()}
 * HTTP server created in a JVM where a Quarkus application (which ships
 * its own Vert.x, including {@code vertx-http}) has already booted
 * misroutes WebSocket upgrades to its null request handler and the
 * handshake never answers. Plain JDK sockets keep this fixture
 * independent of any framework state in the test JVM, so it behaves
 * identically before and after a {@code @QuarkusTest} boot — mirroring
 * the provider-side {@code FakeRelayServer} fix. Unlike that fixture
 * (whose probe aborts right after the 101), this relay is bidirectional,
 * so it carries a minimal frame codec on top of the handshake.</p>
 *
 * <p>RFC 6455 scope is deliberately minimal: single-frame (FIN=1) text
 * messages, the 7-bit / 16-bit / 64-bit payload-length forms, client→
 * server frames unmasked on read (§5.3), server→client frames written
 * unmasked. No continuation/fragmentation, compression, or ping/pong —
 * the production JDK client never needs them here, and it is the
 * conformance oracle: wrong framing means its frames won't parse and the
 * consumer ITs fail.</p>
 *
 * <p>Listens on an ephemeral port; {@link #uri()} is the {@code ws://}
 * address to dial. {@link AutoCloseable} so a try-with-resources or an
 * {@code @AfterEach} releases the port.</p>
 */
final class FakeNostrRelay implements AutoCloseable {

    /** RFC 6455 §1.3 magic GUID appended to the client key before hashing. */
    private static final String WEBSOCKET_ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket server;
    private final int port;
    private final List<String> inboundFrames = new CopyOnWriteArrayList<>();
    private final List<Connection> liveConnections = new CopyOnWriteArrayList<>();

    FakeNostrRelay() {
        try {
            // Wildcard bind (all interfaces) on an ephemeral port, matching the
            // interface coverage of the Vert.x listen(0) this replaces; uri()
            // dials the "localhost" alias the SSRF gate resolves.
            this.server = new ServerSocket(0);
        } catch (IOException e) {
            throw new IllegalStateException("FakeNostrRelay failed to start", e);
        }
        this.port = server.getLocalPort();
        Thread.ofVirtual().name("fake-nostr-accept").start(this::acceptLoop);
    }

    /** The {@code ws://} address a client dials to reach this relay. */
    URI uri() {
        return URI.create("ws://localhost:" + port);
    }

    /** Every inbound text frame received so far (the REQ frames the subscriber sent). */
    List<String> receivedFrames() {
        return List.copyOf(inboundFrames);
    }

    /** Number of client connections currently open to this relay. */
    int liveConnectionCount() {
        return liveConnections.size();
    }

    /**
     * Push one EVENT frame ({@code ["EVENT", subId, {event}]}) to every
     * connected client. The subscriber accepts events on its single
     * subscription regardless of {@code subId}, so a fixed id suffices.
     */
    void sendEvent(NostrEvent event) {
        ArrayNode frame = NostrMessage.MAPPER.createArrayNode();
        frame.add("EVENT");
        frame.add("sub");
        frame.add(NostrMessage.MAPPER.valueToTree(event));
        String text;
        try {
            text = NostrMessage.MAPPER.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize fake EVENT frame", e);
        }
        for (Connection connection : liveConnections) {
            connection.writeText(text);
        }
    }

    /** Push an {@code ["EOSE", subId]} frame to every connected client. */
    void sendEose() {
        for (Connection connection : liveConnections) {
            connection.writeText("[\"EOSE\",\"sub\"]");
        }
    }

    /** Drop every current client connection so the subscriber must reconnect. */
    void disconnectClients() {
        for (Connection connection : List.copyOf(liveConnections)) {
            connection.close();
        }
    }

    /**
     * Block until at least {@code count} inbound frames have been received or
     * {@code timeout} elapses.
     *
     * @return true if the count was reached within the timeout.
     */
    boolean awaitFrameCount(int count, Duration timeout) {
        return awaitCondition(() -> inboundFrames.size() >= count, timeout);
    }

    /**
     * Block until at least {@code count} client connections are live or
     * {@code timeout} elapses.
     *
     * @return true if the count was reached within the timeout.
     */
    boolean awaitConnectionCount(int count, Duration timeout) {
        return awaitCondition(() -> liveConnections.size() >= count, timeout);
    }

    private boolean awaitCondition(java.util.function.BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException e) {
            throw new IllegalStateException("FakeNostrRelay failed to close", e);
        }
        // Unblock any connection thread still parked in its read loop.
        for (Connection connection : List.copyOf(liveConnections)) {
            connection.close();
        }
    }

    private void acceptLoop() {
        while (true) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                // Server closed; expected during teardown.
                return;
            }
            Thread.ofVirtual().name("fake-nostr-conn").start(() -> serve(socket));
        }
    }

    /**
     * Per-connection handler: answer the opening handshake, register the
     * connection as live, then read inbound frames until the peer closes.
     * Registration happens only after a successful 101 so
     * {@link #liveConnectionCount()} reflects upgraded WebSockets, and the
     * {@code finally} de-registers on every exit (peer close, abort, or
     * teardown) so the count drops back to zero.
     */
    private void serve(Socket socket) {
        Connection connection = null;
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            String clientKey = readHandshakeKey(in);
            if (clientKey == null) {
                // Not a WebSocket upgrade (or peer closed mid-head).
                return;
            }
            out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + acceptValue(clientKey) + "\r\n"
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            connection = new Connection(socket, out);
            liveConnections.add(connection);
            readFrames(in);
        } catch (IOException e) {
            // Peer aborted (the production client's abort()) or teardown
            // closed the socket under us.
        } finally {
            if (connection != null) {
                liveConnections.remove(connection);
            }
            try {
                socket.close();
            } catch (IOException e) {
                // close-after-close — ignore
            }
        }
    }

    /**
     * Read the HTTP upgrade-request head one byte at a time directly off the
     * raw stream — stopping exactly at the terminating CRLF CRLF — and return
     * the {@code Sec-WebSocket-Key}, or {@code null} if absent. Reading byte
     * by byte (rather than via a buffering reader) is required: a
     * {@code BufferedReader} would read past the blank line into the first
     * masked frame the client sends immediately after the handshake and
     * corrupt the frame stream.
     */
    private static String readHandshakeKey(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            head.write(b);
            int size = head.size();
            if (size >= 4) {
                byte[] bytes = head.toByteArray();
                if (bytes[size - 4] == '\r' && bytes[size - 3] == '\n'
                        && bytes[size - 2] == '\r' && bytes[size - 1] == '\n') {
                    break;
                }
            }
        }
        String request = head.toString(StandardCharsets.US_ASCII);
        for (String line : request.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && "sec-websocket-key".equals(
                    line.substring(0, colon).trim().toLowerCase(Locale.ROOT))) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    /**
     * Read masked client frames until a close frame or EOF. Text-frame
     * payloads are recorded as inbound REQ frames; other opcodes
     * (ping/pong/binary/continuation) are read off the wire and ignored —
     * the production client never sends them in these tests.
     */
    private void readFrames(InputStream in) throws IOException {
        while (true) {
            int firstByte = in.read();
            if (firstByte == -1) {
                return;
            }
            int opcode = firstByte & 0x0F;
            int secondByte = readByte(in);
            boolean masked = (secondByte & 0x80) != 0;
            long length = secondByte & 0x7F;
            if (length == 126) {
                length = (readByte(in) << 8) | readByte(in);
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    length = (length << 8) | readByte(in);
                }
            }
            byte[] maskKey = new byte[4];
            if (masked) {
                readFully(in, maskKey);
            }
            byte[] payload = new byte[(int) length];
            readFully(in, payload);
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= maskKey[i & 3];
                }
            }
            if (opcode == 0x8) {
                // Close frame — the peer is done.
                return;
            }
            if (opcode == 0x1) {
                inboundFrames.add(new String(payload, StandardCharsets.UTF_8));
            }
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b == -1) {
            throw new EOFException("peer closed mid-frame");
        }
        return b;
    }

    private static void readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read == -1) {
                throw new EOFException("peer closed mid-frame");
            }
            offset += read;
        }
    }

    private static String acceptValue(String clientKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(
                    (clientKey + WEBSOCKET_ACCEPT_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 ships with every JDK; checked-exception plumbing only.
            throw new IllegalStateException(e);
        }
    }

    /**
     * One upgraded client connection. Holds the socket's output stream and
     * serializes writes (an EVENT push and a teardown close can race across
     * threads) behind a per-connection lock.
     */
    private static final class Connection {

        private final Socket socket;
        private final OutputStream out;
        private final Object writeLock = new Object();

        Connection(Socket socket, OutputStream out) {
            this.socket = socket;
            this.out = out;
        }

        /** Write a single unmasked text frame (FIN=1) to the client. */
        void writeText(String text) {
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x81); // FIN + text opcode
            int length = payload.length;
            if (length <= 125) {
                frame.write(length);
            } else if (length <= 0xFFFF) {
                frame.write(126);
                frame.write((length >> 8) & 0xFF);
                frame.write(length & 0xFF);
            } else {
                frame.write(127);
                for (int shift = 56; shift >= 0; shift -= 8) {
                    frame.write((int) (((long) length >> shift) & 0xFF));
                }
            }
            frame.write(payload, 0, payload.length);
            synchronized (writeLock) {
                try {
                    out.write(frame.toByteArray());
                    out.flush();
                } catch (IOException e) {
                    // Peer gone; the read loop will de-register this connection.
                }
            }
        }

        /** Close the socket; the connection's read loop then de-registers it. */
        void close() {
            try {
                socket.close();
            } catch (IOException e) {
                // close-after-close during teardown — ignore
            }
        }
    }
}
