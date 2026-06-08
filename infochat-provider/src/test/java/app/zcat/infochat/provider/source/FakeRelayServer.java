package app.zcat.infochat.provider.source;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A minimal in-process WebSocket endpoint for relay-probe tests. A
 * plain blocking {@link ServerSocket} on virtual threads that answers
 * the RFC 6455 opening handshake and nothing more — the probe under
 * test aborts the socket right after the handshake completes, so no
 * frame support is needed.
 *
 * <p>Deliberately framework-free: a standalone {@code Vertx.vertx()}
 * HTTP server created in a JVM where a Quarkus application (which
 * ships its own Vert.x, including {@code vertx-http}) has already
 * booted misroutes WebSocket upgrades to its null request handler and
 * the handshake never answers. Plain JDK sockets keep this fixture
 * independent of any framework state in the test JVM, so it behaves
 * identically before and after a {@code @QuarkusTest} boot.</p>
 *
 * <p>Listens on an ephemeral loopback port; {@link #uri()} is the
 * {@code ws://} address to dial. {@link AutoCloseable} so a
 * try-with-resources or an {@code @AfterAll} releases the port.
 * Public (not package-private) because the {@code provider.command}
 * integration tests dial it too.</p>
 */
public final class FakeRelayServer implements AutoCloseable {

    /** RFC 6455 §1.3 magic GUID appended to the client key before hashing. */
    private static final String WEBSOCKET_ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket server;
    private final List<Socket> connections = new CopyOnWriteArrayList<>();
    private final int port;

    public FakeRelayServer() {
        try {
            this.server = new ServerSocket();
            this.server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        } catch (IOException e) {
            throw new IllegalStateException("FakeRelayServer failed to start", e);
        }
        this.port = server.getLocalPort();
        Thread.ofVirtual().name("fake-relay-accept").start(this::acceptLoop);
    }

    public int port() {
        return port;
    }

    /** The {@code ws://127.0.0.1:<port>} address tests dial. */
    public URI uri() {
        return URI.create("ws://127.0.0.1:" + port);
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException e) {
            throw new IllegalStateException("FakeRelayServer failed to close", e);
        }
        // Unblock any connection thread still parked in its read loop.
        for (Socket connection : connections) {
            try {
                connection.close();
            } catch (IOException e) {
                // close-after-close during teardown — ignore
            }
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
            connections.add(socket);
            Thread.ofVirtual().name("fake-relay-conn").start(() -> handshake(socket));
        }
    }

    /**
     * Accept every handshake on every path: read the upgrade request
     * head, answer {@code 101 Switching Protocols} with the computed
     * {@code Sec-WebSocket-Accept}, then hold the connection open until
     * the peer closes (the probe aborts right after the 101).
     */
    private void handshake(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String clientKey = null;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && "sec-websocket-key".equals(
                        line.substring(0, colon).trim().toLowerCase(Locale.ROOT))) {
                    clientKey = line.substring(colon + 1).trim();
                }
            }
            if (clientKey == null) {
                // Not a WebSocket upgrade (or peer closed mid-head) —
                // nothing to accept.
                socket.close();
                return;
            }
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
            writer.write("HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + acceptValue(clientKey) + "\r\n"
                    + "\r\n");
            writer.flush();
            // Discard anything the peer sends; no protocol behavior needed.
            while (socket.getInputStream().read() != -1) {
                // ignore
            }
        } catch (IOException e) {
            // Peer aborted (the probe's expected post-handshake behavior)
            // or teardown closed the socket under us.
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                // close-after-close — ignore
            }
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
}
