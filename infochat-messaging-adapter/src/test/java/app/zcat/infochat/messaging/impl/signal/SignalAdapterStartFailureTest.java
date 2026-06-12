package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.MessagingException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Pins {@link SignalAdapter#start()}'s SPI exception contract: transport
 * startup failures surface as the SPI's checked
 * {@link MessagingException} ("@throws MessagingException on transport
 * startup failure"), while config-shaped failures — the
 * capability-only-constructor misuse guard and the bot-ACI derivation
 * from the signal-cli account store — legitimately remain
 * {@link IllegalStateException}. The derivation-failure pins use a
 * nonexistent binary so a wrong ordering (derivation after the spawn)
 * would surface as the spawn's MessagingException instead of the
 * asserted IllegalStateException: derivation MUST precede the spawn.
 */
class SignalAdapterStartFailureTest {

    private static final String ACCOUNT = "+15550000000";

    @TempDir
    Path dataDir;

    @Test
    void startThrowsMessagingExceptionWhenSubprocessSpawnFails() throws IOException {
        // Valid account store: derivation now precedes the spawn, so the
        // spawn-failure pin needs the derivation to pass first.
        SignalAccountStoreFixture.writeStore(
                dataDir, ACCOUNT, "00000000-0000-0000-0000-000000000204");
        SignalAdapter adapter = new SignalAdapter(
                "/nonexistent/signal-cli-binary",
                dataDir.toString(),
                ACCOUNT,
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
                ACCOUNT,
                deadEndpoint);
        SignalJsonRpcClient client = new SignalJsonRpcClient(
                deadEndpoint, ACCOUNT, new SignalMessageCodec(),
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
    void startFailsWhenAccountStoreMissing() throws IOException {
        // No accounts.json under the (existing) data dir.
        SignalAdapter adapter = new SignalAdapter(
                "/nonexistent/signal-cli-binary",
                dataDir.toString(),
                ACCOUNT,
                unusedLoopbackEndpoint());

        IllegalStateException ex = assertThrows(IllegalStateException.class, adapter::start);

        assertTrue(ex.getMessage().contains("accounts.json"),
                "the failure must name the store path the operator has to inspect");
        assertTrue(ex.getMessage().contains("infochat.adapters.signal.data-dir"),
                "the failure must name the property the operator has to fix");
    }

    @Test
    void startFailsWhenAccountStoreMalformed() throws IOException {
        SignalAccountStoreFixture.writeMalformedStore(dataDir);
        SignalAdapter adapter = new SignalAdapter(
                "/nonexistent/signal-cli-binary",
                dataDir.toString(),
                ACCOUNT,
                unusedLoopbackEndpoint());

        IllegalStateException ex = assertThrows(IllegalStateException.class, adapter::start);

        assertTrue(ex.getMessage().contains("accounts.json"),
                "the failure must name the store path the operator has to inspect");
    }

    @Test
    void startFailsWhenStoreAciMalformed() throws IOException {
        SignalAccountStoreFixture.writeStore(dataDir, ACCOUNT, "Not-A-UUID-At-All");
        SignalAdapter adapter = new SignalAdapter(
                "/nonexistent/signal-cli-binary",
                dataDir.toString(),
                ACCOUNT,
                unusedLoopbackEndpoint());

        IllegalStateException ex = assertThrows(IllegalStateException.class, adapter::start);

        assertTrue(ex.getMessage().contains("infochat.adapters.signal.data-dir"),
                "the failure must name the property the operator has to fix");
        assertFalse(ex.getMessage().toLowerCase().contains("not-a-uuid-at-all"),
                "the failure must not echo the raw store value (D37 log hygiene)");
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
