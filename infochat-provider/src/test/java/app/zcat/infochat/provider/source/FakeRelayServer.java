package app.zcat.infochat.provider.source;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A minimal in-process WebSocket endpoint for relay-probe tests.
 * Backed by a Vert.x WebSocket server (the JDK ships only a WebSocket
 * <em>client</em>), it accepts the opening handshake and nothing more —
 * the probe under test aborts the socket right after the handshake
 * completes, so no protocol behavior is needed. Mirrors the collector's
 * {@code FakeNostrRelay} fixture shape, minus the NIP-01 frame helpers.
 *
 * <p>Listens on an ephemeral loopback port; {@link #uri()} is the
 * {@code ws://} address to dial. {@link AutoCloseable} so a
 * try-with-resources or an {@code @AfterAll} releases the port and the
 * Vert.x event loop. Public (not package-private) because the
 * {@code provider.command} integration tests dial it too.</p>
 */
public final class FakeRelayServer implements AutoCloseable {

    private final Vertx vertx;
    private final HttpServer server;
    private final int port;

    public FakeRelayServer() {
        this.vertx = Vertx.vertx();
        CompletableFuture<HttpServer> started = new CompletableFuture<>();
        vertx.createHttpServer()
                // Accept every handshake on every path; the probe never
                // sends a frame.
                .webSocketHandler(socket -> { })
                .listen(0)
                .onSuccess(started::complete)
                .onFailure(started::completeExceptionally);
        try {
            this.server = started.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            vertx.close();
            throw new IllegalStateException("FakeRelayServer failed to start", e);
        }
        this.port = server.actualPort();
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
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("FakeRelayServer failed to close", e);
        }
    }
}
