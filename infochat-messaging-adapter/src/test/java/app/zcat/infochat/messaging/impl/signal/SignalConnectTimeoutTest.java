package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.MessagingException;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;

/**
 * Pins {@link SignalJsonRpcClient#connect()}'s bounded TCP connect via a
 * deterministic socket seam — no real network, no timing race. The connect
 * window (a SYN that never draws a SYN-ACK after the endpoint probe passed)
 * is one no test can produce deterministically through {@code start()}, so
 * the bound is pinned against the package-private {@code newSocket()} factory
 * the client connects through, routed via {@code SignalAdapter.connectClient}
 * exactly as {@link SignalAdapterStartFailureTest} does.
 *
 * <p>A {@link RecordingSocket} injected through {@code newSocket()} records
 * the timeout argument and throws {@link SocketTimeoutException}; the test
 * asserts the recorded value equals {@link SignalJsonRpcClient#CONNECT_TIMEOUT_MS}
 * — proving a bound is applied, not the OS default (timeout 0) — and that the
 * timeout surfaces as {@link FailureCategory#TRANSIENT} with the subprocess
 * torn down. A revert to {@code new Socket(addr, port)} drops the seam
 * (compile failure on the {@code @Override}); a revert to {@code connect(endpoint, 0)}
 * records 0 != the constant (assertion failure).</p>
 */
class SignalConnectTimeoutTest {

    private static final String ACCOUNT = "+15550000000";

    @Test
    void connectAppliesBoundedTimeoutAndSurfacesTransient() throws IOException {
        InetSocketAddress endpoint = unusedLoopbackEndpoint();
        RecordingSocket recordingSocket = new RecordingSocket();
        SignalAdapter adapter = new SignalAdapter("/bin/true", "/tmp", ACCOUNT, endpoint);
        SignalJsonRpcClient client = new SignalJsonRpcClient(
                endpoint, ACCOUNT, new SignalMessageCodec(), Duration.ofSeconds(1)) {
            @Override
            Socket newSocket() {
                return recordingSocket;
            }
        };
        SignalSubprocess subprocess = new SignalSubprocess(
                new ProcessBuilder("/bin/true"),
                endpoint,
                SignalSubprocess.BackoffPolicy.laptopDefault(),
                1);

        MessagingException ex = assertThrows(MessagingException.class,
                () -> adapter.connectClient(client, subprocess));

        assertEquals(SignalJsonRpcClient.CONNECT_TIMEOUT_MS, recordingSocket.recordedTimeoutMs,
                "connect() must apply the bounded CONNECT_TIMEOUT_MS, not the OS default (timeout 0)");
        assertEquals(FailureCategory.TRANSIENT, ex.category(),
                "a connect timeout is a recoverable outage — connectClient classifies it TRANSIENT");
        assertEquals(SignalSubprocess.State.STOPPED, subprocess.state(),
                "a timed-out connect must tear the just-started subprocess down");
    }

    /**
     * A loopback endpoint that refuses connections: bind an ephemeral port,
     * close it, and hand the (now-unbound) address out. Nothing re-binds it,
     * and the injected {@link RecordingSocket} never dials it anyway — the
     * address only supplies a well-formed {@link InetSocketAddress}.
     */
    private static InetSocketAddress unusedLoopbackEndpoint() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return new InetSocketAddress("127.0.0.1", probe.getLocalPort());
        }
    }
}

/**
 * Stand-in socket that records the connect timeout argument and throws
 * {@link SocketTimeoutException}, reproducing the kernel connect-timeout path
 * without a real connection. Top-level package-private per the module
 * convention against inner-class test doubles. {@code newSocket()} never
 * calls {@code connect} on the real impl, so no OS socket is ever opened.
 */
class RecordingSocket extends Socket {

    int recordedTimeoutMs = -1;

    @Override
    public void connect(SocketAddress endpoint, int timeout) throws IOException {
        this.recordedTimeoutMs = timeout;
        throw new SocketTimeoutException("connect timed out (test seam)");
    }
}
