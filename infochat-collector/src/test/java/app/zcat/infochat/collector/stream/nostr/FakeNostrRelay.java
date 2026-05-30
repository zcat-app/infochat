package app.zcat.infochat.collector.stream.nostr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A minimal in-process NIP-01 relay for tests. Backed by a Vert.x WebSocket
 * server (the JDK ships only a WebSocket <em>client</em>), it accepts a
 * client connection, records every inbound text frame (the REQ the
 * subscriber sends), and can push EVENT frames or drop the connection on
 * demand. Shared by every Nostr ticket (M1-096..M1-101); keep it protocol-
 * generic, not test-specific.
 *
 * <p>Listens on an ephemeral port; {@link #uri()} is the {@code ws://}
 * address to dial. {@link AutoCloseable} so a try-with-resources or an
 * {@code @AfterEach} releases the port and the Vert.x event loop.</p>
 */
final class FakeNostrRelay implements AutoCloseable {

    private final Vertx vertx;
    private final HttpServer server;
    private final int port;
    private final List<String> inboundFrames = new CopyOnWriteArrayList<>();
    private final List<ServerWebSocket> liveSockets = new CopyOnWriteArrayList<>();

    FakeNostrRelay() {
        this.vertx = Vertx.vertx();
        CompletableFuture<HttpServer> started = new CompletableFuture<>();
        vertx.createHttpServer()
                .webSocketHandler(socket -> {
                    liveSockets.add(socket);
                    socket.textMessageHandler(inboundFrames::add);
                    socket.closeHandler(closed -> liveSockets.remove(socket));
                })
                .listen(0)
                .onSuccess(started::complete)
                .onFailure(started::completeExceptionally);
        try {
            this.server = started.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            vertx.close();
            throw new IllegalStateException("FakeNostrRelay failed to start", e);
        }
        this.port = server.actualPort();
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
        return liveSockets.size();
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
        for (ServerWebSocket socket : liveSockets) {
            socket.writeTextMessage(text);
        }
    }

    /** Push an {@code ["EOSE", subId]} frame to every connected client. */
    void sendEose() {
        for (ServerWebSocket socket : liveSockets) {
            socket.writeTextMessage("[\"EOSE\",\"sub\"]");
        }
    }

    /** Drop every current client connection so the subscriber must reconnect. */
    void disconnectClients() {
        for (ServerWebSocket socket : List.copyOf(liveSockets)) {
            socket.close();
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
        return awaitCondition(() -> liveSockets.size() >= count, timeout);
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
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("FakeNostrRelay failed to close", e);
        }
    }
}
