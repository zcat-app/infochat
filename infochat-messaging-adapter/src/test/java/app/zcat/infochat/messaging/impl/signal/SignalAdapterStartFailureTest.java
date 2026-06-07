package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.MessagingException;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;

/**
 * Pins {@link SignalAdapter#start()}'s SPI exception contract: transport
 * startup failures surface as the SPI's checked
 * {@link MessagingException} ("@throws MessagingException on transport
 * startup failure"), while the capability-only-constructor misuse guard
 * legitimately remains {@link IllegalStateException} — a programming
 * error, not a transport failure.
 */
class SignalAdapterStartFailureTest {

    @Test
    void startThrowsMessagingExceptionWhenSubprocessSpawnFails() throws IOException {
        SignalAdapter adapter = new SignalAdapter(
                "/nonexistent/signal-cli-binary",
                "/tmp",
                "+15550000000",
                "00000000-0000-0000-0000-000000000204",
                unusedLoopbackEndpoint());

        MessagingException ex = assertThrows(MessagingException.class, adapter::start);

        assertEquals(FailureCategory.PERMANENT, ex.category(),
                "a spawn failure (missing/unexecutable binary) is not a recoverable outage");
    }

    @Test
    void connectFailureThrowsMessagingExceptionAndStopsSubprocess() throws IOException {
        // The connect-failure window (daemon answers the endpoint probe,
        // then vanishes before the JSON-RPC connect) cannot be produced
        // deterministically through start() — the probe and the connect
        // dial the same endpoint, so any listener arrangement that fails
        // the connect also fails the probe, and a listener torn down
        // in between is a race. The contract is pinned against the
        // package-private connectClient seam start() routes through.
        InetSocketAddress deadEndpoint = unusedLoopbackEndpoint();
        SignalAdapter adapter = new SignalAdapter(
                "/bin/true",
                "/tmp",
                "+15550000000",
                "00000000-0000-0000-0000-000000000204",
                deadEndpoint);
        SignalJsonRpcClient client = new SignalJsonRpcClient(
                deadEndpoint, "+15550000000", new SignalMessageCodec(),
                Duration.ofSeconds(1));
        SignalSubprocess subprocess = new SignalSubprocess(
                new ProcessBuilder("/bin/true"),
                deadEndpoint,
                SignalSubprocess.BackoffPolicy.laptopDefault(),
                1);

        MessagingException ex = assertThrows(MessagingException.class,
                () -> adapter.connectClient(client, subprocess));

        assertEquals(FailureCategory.TRANSIENT, ex.category(),
                "a refused connect right after a successful endpoint probe is a recoverable outage");
        assertEquals(SignalSubprocess.State.STOPPED, subprocess.state(),
                "a failed connect must tear the just-started subprocess down");
    }

    @Test
    void startStillThrowsIllegalStateExceptionForCapabilityOnlyConstructor() {
        SignalAdapter adapter = new SignalAdapter();

        assertThrows(IllegalStateException.class, adapter::start,
                "constructor misuse is a programming error, not a transport failure");
    }

    /**
     * A loopback endpoint that refuses connections: bind an ephemeral
     * port, close it, and hand the (now-unbound) address out. Nothing
     * re-binds it within the test's lifetime.
     */
    private static InetSocketAddress unusedLoopbackEndpoint() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return new InetSocketAddress("127.0.0.1", probe.getLocalPort());
        }
    }
}
