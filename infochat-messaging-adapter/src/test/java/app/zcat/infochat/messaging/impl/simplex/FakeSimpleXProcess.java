package app.zcat.infochat.messaging.impl.simplex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Test double that stands in for a running simplex-chat process for the
 * sake of {@link SimpleXWebSocketClientTest}. Listens on a loopback random
 * port, accepts a single WebSocket upgrade per {@link #start()}, and
 * exposes {@link #sendFrame(String)} / {@link #awaitFrame(Duration)}
 * primitives so a test can inject server→client frames and assert what
 * the client sent.
 *
 * <p>Kept here, top-level and package-private (not nested), per the
 * "avoid private inner classes in test files" working rule.</p>
 *
 * <p>The implementation is a minimal RFC-6455 server: HTTP upgrade
 * handshake then text-frame parsing with mask-decoding on the inbound
 * side and length-encoding (single-byte, 16-bit, and 64-bit forms) on
 * the outbound side. No TLS, no extensions, no continuation frames — the
 * SimpleX adapter only sends/receives single-fragment text frames.</p>
 */
public final class FakeSimpleXProcess implements AutoCloseable {

    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket serverSocket;
    private final int port;
    private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
    // Counts every completed WS handshake. Tests that choreograph a
    // kill→reconnect sequence snapshot the value before the kill and await
    // a higher one, so they never race the re-accept; awaitClient(timeout)
    // (the pre-existing single-connection call pattern) awaits ≥1.
    private final AtomicInteger handshakeGeneration = new AtomicInteger();

    private volatile Socket clientSocket;
    private volatile Thread acceptThread;
    private volatile Thread readerThread;
    private volatile boolean closed = false;
    // Identity of a socket deliberately severed by killClientConnection():
    // its reader's IOException is choreography, not a fake-side failure,
    // and must not surface a __READER_ERROR__ marker into the frame queue.
    private volatile Socket killedSocket;

    public FakeSimpleXProcess() throws IOException {
        this.serverSocket = new ServerSocket();
        this.serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
        this.port = serverSocket.getLocalPort();
    }

    /** Begin accepting one connection. Non-blocking. */
    public void start() {
        acceptThread = Thread.ofVirtual().name("fake-simplex-accept").start(this::runAccept);
    }

    /** WebSocket URI a {@link SimpleXWebSocketClient} can connect to. */
    URI wsUri() {
        return URI.create("ws://127.0.0.1:" + port);
    }

    public int port() {
        return port;
    }

    /** Wait until the client has finished the WS handshake. */
    public void awaitClient(Duration timeout) throws InterruptedException {
        awaitClientGeneration(1, timeout);
    }

    /** Count of completed WS handshakes so far. */
    int clientGeneration() {
        return handshakeGeneration.get();
    }

    /**
     * Block until at least {@code atLeast} WS handshakes have completed —
     * the reconnect tests' way of observing that the adapter's rebuilt
     * client actually arrived after a kill.
     */
    void awaitClientGeneration(int atLeast, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (handshakeGeneration.get() < atLeast && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(5);
        }
        if (handshakeGeneration.get() < atLeast) {
            throw new IllegalStateException(
                    "client did not connect within " + timeout);
        }
    }

    /**
     * Sever the live client connection WITHOUT closing the server socket
     * — simulates simplex-chat dying while the WS port stays available
     * (the supervisor respawns a process serving the same port). The
     * accept loop keeps running, so a rebuilt client re-wires
     * {@code clientSocket} on its next handshake.
     */
    void killClientConnection() throws IOException {
        Socket s = clientSocket;
        if (s != null) {
            killedSocket = s;
            s.close();
        }
    }

    /** Send a text frame from server to client. */
    void sendFrame(String text) throws IOException {
        Socket socket = requireConnectedClient();
        OutputStream out = socket.getOutputStream();
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        synchronized (out) {
            out.write(0x81); // FIN + text opcode
            int len = payload.length;
            if (len < 126) {
                out.write(len);
            } else if (len < 65_536) {
                out.write(126);
                out.write((len >> 8) & 0xFF);
                out.write(len & 0xFF);
            } else {
                out.write(127);
                for (int shift = 56; shift >= 0; shift -= 8) {
                    out.write((int) ((long) len >> shift) & 0xFF);
                }
            }
            out.write(payload);
            out.flush();
        }
    }

    /** Pop the next text frame the client sent us, or fail the test on timeout. */
    public String awaitFrame(Duration timeout) throws InterruptedException {
        String frame = received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (frame == null) {
            throw new IllegalStateException(
                    "no client→server frame received within " + timeout);
        }
        return frame;
    }

    @Override
    public void close() {
        closed = true;
        Socket socket = clientSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Test teardown — socket may already be closed.
            }
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // Test teardown — server may already be closed.
        }
        joinQuietly(readerThread);
        joinQuietly(acceptThread);
    }

    // -- internals ---------------------------------------------------------

    private void runAccept() {
        // Loop rather than accept-once: SimpleXAdapter.start() does a TCP
        // probe to the port (waitForWebSocketReady) BEFORE the WebSocket
        // handshake, and the probe socket is closed immediately without
        // sending any HTTP bytes. With single-shot accept, that probe
        // would consume the slot and the subsequent real handshake would
        // hang. Looping — with per-iteration handshake failures absorbed
        // — lets the probe land harmlessly and the real client connect
        // afterward. Existing unit tests still see one successful
        // handshake (they make exactly one connection); the loop then
        // blocks in accept() until close() unblocks it via serverSocket
        // close.
        while (!closed) {
            try {
                Socket s = serverSocket.accept();
                try {
                    handleHandshake(s);
                } catch (IOException handshakeFailure) {
                    // Handshake failure (e.g. WaitForWebSocketReady's
                    // zero-byte probe) — drop this socket and wait for
                    // the next connection. Real WebSocket clients send
                    // the GET upgrade line synchronously after connect,
                    // so a real client's handshake will be readable on
                    // the next iteration.
                    try { s.close(); } catch (IOException ignored) { /* dropped */ }
                    continue;
                }
                this.clientSocket = s;
                handshakeGeneration.incrementAndGet();
                this.readerThread = Thread.ofVirtual()
                        .name("fake-simplex-reader")
                        .start(() -> runReader(s));
                // Keep accepting after a successful handshake: a client
                // rebuilt after a supervised subprocess restart connects on
                // a later iteration and overwrites clientSocket — one LIVE
                // client at a time is still the semantics (the pre-existing
                // single-connection suites make exactly one connection and
                // see no behavioral difference; the loop just blocks in
                // accept() until close()).
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                throw new IllegalStateException("accept failed", e);
            }
        }
    }

    private void handleHandshake(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
        String key = null;
        int b;
        int consecutiveCrlf = 0;
        while ((b = in.read()) != -1) {
            lineBuffer.write(b);
            if (b == '\n') {
                String line = lineBuffer.toString(StandardCharsets.US_ASCII).trim();
                lineBuffer.reset();
                if (line.isEmpty()) {
                    consecutiveCrlf++;
                    if (consecutiveCrlf >= 1) {
                        break;
                    }
                } else {
                    consecutiveCrlf = 0;
                    if (line.regionMatches(true, 0, "Sec-WebSocket-Key:", 0,
                            "Sec-WebSocket-Key:".length())) {
                        key = line.substring("Sec-WebSocket-Key:".length()).trim();
                    }
                }
            }
        }
        if (key == null) {
            throw new IOException("WebSocket handshake missing Sec-WebSocket-Key");
        }
        String accept = base64Sha1(key + WS_MAGIC);
        OutputStream out = s.getOutputStream();
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private void runReader(Socket s) {
        try (InputStream in = s.getInputStream()) {
            while (!closed) {
                int b1 = in.read();
                if (b1 == -1) {
                    return;
                }
                int b2 = in.read();
                if (b2 == -1) {
                    return;
                }
                int opcode = b1 & 0x0F;
                boolean masked = (b2 & 0x80) != 0;
                long length = b2 & 0x7F;
                if (length == 126) {
                    length = ((long) readByte(in) << 8) | readByte(in);
                } else if (length == 127) {
                    length = 0;
                    for (int i = 0; i < 8; i++) {
                        length = (length << 8) | readByte(in);
                    }
                }
                byte[] mask = new byte[4];
                if (masked) {
                    readFully(in, mask, 4);
                }
                byte[] payload = new byte[(int) length];
                readFully(in, payload, (int) length);
                if (masked) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i & 3];
                    }
                }
                if (opcode == 0x8) {
                    return; // close frame from client
                }
                if (opcode == 0x9 || opcode == 0xA) {
                    continue; // ping/pong, ignore
                }
                if (opcode == 0x1) {
                    received.add(new String(payload, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            if (!closed && s != killedSocket) {
                // Reader thread died on a non-shutdown IOException — surface
                // via the queue so awaitFrame can observe. A deliberately
                // killed socket's reader is excluded: its death is the
                // test's own choreography.
                received.add("__READER_ERROR__:" + e.getClass().getSimpleName());
            }
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b == -1) {
            throw new IOException("unexpected EOF");
        }
        return b & 0xFF;
    }

    private static void readFully(InputStream in, byte[] dest, int length) throws IOException {
        int off = 0;
        while (off < length) {
            int n = in.read(dest, off, length - off);
            if (n == -1) {
                throw new IOException("unexpected EOF after " + off + " of " + length);
            }
            off += n;
        }
    }

    private static String base64Sha1(String input) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return Base64.getEncoder().encodeToString(
                    sha1.digest(input.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    private Socket requireConnectedClient() {
        Socket socket = clientSocket;
        if (socket == null) {
            throw new IllegalStateException("client has not connected yet");
        }
        return socket;
    }

    private static void joinQuietly(Thread t) {
        if (t == null) {
            return;
        }
        try {
            t.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
