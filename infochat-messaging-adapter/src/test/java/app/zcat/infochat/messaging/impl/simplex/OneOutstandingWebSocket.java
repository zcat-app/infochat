package app.zcat.infochat.messaging.impl.simplex;

import org.jspecify.annotations.Nullable;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test double for {@link WebSocket} that enforces the JDK's
 * one-outstanding-text-send rule deterministically: a {@code sendText}
 * that overlaps an incomplete prior send is rejected — asynchronously
 * (failed future, the JDK's documented shape) or synchronously (thrown
 * {@link IllegalStateException}, the concurrently-aborted shape) — and
 * its frame is dropped, exactly like the real socket. Accepted frames
 * complete their futures after a configurable delay on a background
 * thread so concurrent callers genuinely overlap.
 *
 * <p>Top-level (not an inner class) per the project's
 * avoid-inner-class-fakes rule. Only {@link #sendText} and
 * {@link #abort} are reachable from the code under test; the remaining
 * methods throw to flag unexpected use (mirrors
 * {@link ThrowingWebSocket}).</p>
 */
final class OneOutstandingWebSocket implements WebSocket {

    enum CollisionMode { ASYNC_FAILED_FUTURE, SYNC_THROW }

    private final BlockingQueue<String> transmitted = new LinkedBlockingQueue<>();
    private final AtomicInteger acceptedCount = new AtomicInteger();
    private final AtomicInteger collisionCount = new AtomicInteger();
    private final long completionDelayMillis;
    private final CollisionMode collisionMode;

    // Guarded by this (sendText is synchronized); completed from the
    // background delay thread.
    private @Nullable CompletableFuture<WebSocket> outstanding;

    OneOutstandingWebSocket(long completionDelayMillis, CollisionMode collisionMode) {
        this.completionDelayMillis = completionDelayMillis;
        this.collisionMode = collisionMode;
    }

    @Override
    public synchronized CompletableFuture<WebSocket> sendText(CharSequence data,
                                                              boolean last) {
        CompletableFuture<WebSocket> prior = outstanding;
        if (prior != null && !prior.isDone()) {
            collisionCount.incrementAndGet();
            if (collisionMode == CollisionMode.SYNC_THROW) {
                throw new IllegalStateException("Send pending");
            }
            return CompletableFuture.failedFuture(new IllegalStateException("Send pending"));
        }
        CompletableFuture<WebSocket> sendFuture = new CompletableFuture<>();
        outstanding = sendFuture;
        acceptedCount.incrementAndGet();
        transmitted.add(data.toString());
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(completionDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sendFuture.complete(this);
        });
        return sendFuture;
    }

    /** Block up to {@code timeout} for the next accepted (transmitted) frame. */
    String awaitTransmitted(Duration timeout) throws InterruptedException {
        String frame = transmitted.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (frame == null) {
            throw new AssertionError(
                    "no frame transmitted within " + timeout
                            + " (collisions so far: " + collisionCount.get() + ")");
        }
        return frame;
    }

    int acceptedCount() {
        return acceptedCount.get();
    }

    int collisionCount() {
        return collisionCount.get();
    }

    @Override
    public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void request(long n) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getSubprotocol() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isOutputClosed() {
        return false;
    }

    @Override
    public boolean isInputClosed() {
        return false;
    }

    @Override
    public void abort() {
        // No-op: close() on the client under test calls abort() during teardown.
    }
}
