package app.zcat.infochat.messaging.impl.simplex;

import org.jspecify.annotations.NonNull;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * Test double for {@link WebSocket} whose {@link #sendText} throws the
 * {@link IllegalStateException} the JDK raises when a send is attempted on a
 * concurrently-aborted socket. Used by {@code SimpleXWebSocketClientTest} to
 * exercise the {@code sendCommand} race-with-{@code close()} path
 * deterministically (the real race window is too narrow to hit reliably).
 *
 * <p>Top-level (not an inner class) per the project's avoid-inner-class-fakes
 * rule. Only {@link #sendText} and {@link #abort} are reachable from the code
 * under test; the remaining methods throw to flag unexpected use.</p>
 */
final class ThrowingWebSocket implements WebSocket {

    @Override
    public CompletableFuture<WebSocket> sendText(@NonNull CharSequence data, boolean last) {
        throw new IllegalStateException("simulated send on a concurrently-aborted WebSocket");
    }

    @Override
    public CompletableFuture<WebSocket> sendBinary(@NonNull ByteBuffer data, boolean last) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<WebSocket> sendPing(@NonNull ByteBuffer message) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<WebSocket> sendPong(@NonNull ByteBuffer message) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<WebSocket> sendClose(int statusCode, @NonNull String reason) {
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
