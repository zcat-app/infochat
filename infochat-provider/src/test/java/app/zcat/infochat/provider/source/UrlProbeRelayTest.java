package app.zcat.infochat.provider.source;

import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.source.UrlProbe.ProbeResult;
import app.zcat.infochat.ssrf.IpBlocklist;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 unit tests for {@link UrlProbe#probeRelay(URI)} —
 * the StreamSource-shaped probe leg per {@code docs/spec/commands.md}
 * §Source management ("a single connection attempt against the first
 * relay"). Mirrors the {@link UrlProbeTest} fixture pattern with a
 * {@link FakeRelayServer} WebSocket endpoint in place of the
 * {@code com.sun.net.httpserver.HttpServer} fixture.
 *
 * <p>Acceptance row map:
 * <ul>
 *   <li>(a) reachable, policy-allowed relay → SUCCESS (handshake
 *       completes, socket aborted).</li>
 *   <li>(b) relay resolving to a blocked address range →
 *       {@link BundleKeys#ERROR_ADD_SOURCE_URL_BLOCKED_SSRF}, never
 *       dialed.</li>
 *   <li>(c) unreachable relay (connection refused) →
 *       {@link BundleKeys#ERROR_ADD_SOURCE_URL_UNREACHABLE} — distinct
 *       from the SSRF-rejection key, pinning the genuine-SSRF vs
 *       ordinary-unreachability reply split.</li>
 * </ul>
 */
class UrlProbeRelayTest {

    // (a) reachable, policy-allowed relay → SUCCESS
    @Test
    void relayProbeReportsSuccessForReachablePolicyAllowedRelay() throws Exception {
        try (FakeRelayServer relay = new FakeRelayServer()) {
            UrlProbe probe = new UrlProbe(loopbackPermittingClient());

            ProbeResult result = probe.probeRelay(relay.uri());

            assertTrue(result.ok(),
                    "a completed WebSocket handshake against an allowed relay must report SUCCESS");
            assertEquals(101, result.httpStatus(),
                    "relay-probe success carries the Switching Protocols status");
        }
    }

    // (b) blocked address range → url_blocked_ssrf, never dialed
    @Test
    void relayProbeMapsBlockedAddressRangeToBlockedSsrfBundleKey() {
        // Strict blocklist (no-arg constructor) refuses loopback; the
        // checkAndPinForWebSocket gate raises SsrfPolicyException(BLOCKED_IP)
        // before any socket is opened — port 9 (discard) is never dialed.
        // wss:// exercises the TLS scheme through the {ws, wss} allowlist.
        UrlProbe probe = new UrlProbe(new SsrfGuardedHttpClient());

        ProbeResult result = probe.probeRelay(URI.create("wss://127.0.0.1:9/never-dialed"));

        assertFalse(result.ok(), "blocked-range relay must FAIL");
        assertEquals(BundleKeys.ERROR_ADD_SOURCE_URL_BLOCKED_SSRF, result.failureBundleKey());
    }

    // (c) connection refused → url_unreachable (NOT the SSRF key)
    @Test
    void relayProbeMapsConnectionRefusedToUrlUnreachable() throws Exception {
        // Bind-then-close yields a loopback port with no listener; the
        // policy-allowed dial is refused at TCP connect.
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        UrlProbe probe = new UrlProbe(loopbackPermittingClient());

        ProbeResult result = probe.probeRelay(
                URI.create("ws://127.0.0.1:" + closedPort + "/unreachable"));

        assertFalse(result.ok(), "refused connection must FAIL");
        assertEquals(BundleKeys.ERROR_ADD_SOURCE_URL_UNREACHABLE, result.failureBundleKey(),
                "ordinary unreachability must map to the unreachable key, not the SSRF key");
    }

    private SsrfGuardedHttpClient loopbackPermittingClient() {
        return new SsrfGuardedHttpClient(
                new LoopbackPermitting(),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofMinutes(2),
                10L * 1024,
                3);
    }

    /**
     * Test-only blocklist subclass that permits loopback addresses so
     * the in-process {@link FakeRelayServer} fixture can be dialed;
     * every other range still routes through the strict
     * {@link IpBlocklist}. Mirrors the same-named inner class in
     * {@code UrlProbeTest}.
     */
    private static final class LoopbackPermitting extends IpBlocklist {

        @Override
        public boolean isBlocked(InetAddress addr) {
            if (addr.isLoopbackAddress()) {
                return false;
            }
            return super.isBlocked(addr);
        }
    }
}
