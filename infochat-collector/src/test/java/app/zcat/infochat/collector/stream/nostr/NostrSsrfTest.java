package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.ssrf.IpBlocklist;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient.SsrfPolicyException;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 tests for the {@link NostrRelayConnection} SSRF gate
 * (M1-101). Each case drives a single {@link
 * NostrRelayConnection#connectAndSubscribe()} attempt against an
 * {@link SsrfGuardedHttpClient} whose {@code resolverSeam} returns
 * deterministic addresses, so the gate decision is fully controlled
 * by the test (no real DNS, no network round-trip).
 *
 * <p>The acceptance items these tests cover (from the ticket
 * frontmatter):
 * <ul>
 *   <li>{@code blockedIpRefused} — "NostrRelayConnection runs DNS
 *       resolution through the infochat-ssrf IpBlocklist before
 *       opening a wss:// WebSocket connection — a relay whose
 *       hostname resolves to a blocked IP range ... is refused".
 *   <li>{@code reconnectReResolvesAndBlocks} — "DNS is re-resolved
 *       on every reconnect — a relay that initially resolved to a
 *       public IP but later resolves to a private IP is refused on
 *       reconnect".
 * </ul>
 *
 * <p>Mid-session peer-IP-change detection (the watcher path) is
 * covered by {@code NostrSsrfIT} against a live {@code FakeNostrRelay}
 * because it requires an established WebSocket connection.
 */
class NostrSsrfTest {

    // Arbitrary high port chosen so the WebSocket dial after a passing
    // SSRF check fails fast on connection-refused (the only failure
    // path under test is the SSRF arm; this avoids accidentally waiting
    // on a hung handshake). The pin redirects DNS to the seam-supplied
    // IP, so the actual hostname is irrelevant.
    private static final URI RELAY_URI = URI.create("wss://nostr-ssrf-fixture.invalid:39753/relay");

    private static final String FILTER = "{\"kinds\":[1]}";

    private static final Duration FAST_BASE = Duration.ofMillis(50);

    private static final Duration FAST_MAX = Duration.ofMillis(200);

    @Test
    void blockedIpRefused() {
        // Seam returns 127.0.0.1; the default-strict IpBlocklist refuses
        // 127.0.0.0/8 (loopback). The gate must throw BEFORE the
        // WebSocket handshake — no socket is ever opened.
        Function<String, List<InetAddress>> seam = host -> List.of(loopback());
        SsrfGuardedHttpClient ssrfClient = newSsrfClient(seam, new IpBlocklist());
        NostrRelayConnection connection = newConnection(ssrfClient);

        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
                connection::connectAndSubscribe);
        assertTrue(ex.getMessage().contains("blocked IP"),
                "default-strict IpBlocklist must refuse 127.0.0.1 with the literal "
                        + "\"blocked IP\" prefix before the WebSocket handshake; got: "
                        + ex.getMessage());
    }

    @Test
    void reconnectReResolvesAndBlocks() {
        // Two connect attempts simulate the production reconnect path
        // (runLoop calls connectAndSubscribe once per iteration). The
        // seam returns a loopback address on call 1 (passes the
        // loopback-permitting blocklist) and an RFC-1918 private
        // address on call 2 (refused by the strict-on-private super
        // implementation). The gate must let call 1 through to the
        // dial (which then fails fast on ConnectException because the
        // pinned IP/port is not listening) and refuse call 2 before
        // the handshake.
        AtomicInteger seamCalls = new AtomicInteger();
        Function<String, List<InetAddress>> seam = host -> {
            int n = seamCalls.incrementAndGet();
            return List.of(n == 1 ? loopback() : privateAddress());
        };
        SsrfGuardedHttpClient ssrfClient = newSsrfClient(seam, new LoopbackPermittingBlocklist());
        NostrRelayConnection connection = newConnection(ssrfClient);

        // First connect: SSRF check passes (loopback permitted), then
        // the WebSocket dial throws because the pinned IP/port is not
        // listening. The exception must NOT be SsrfPolicyException —
        // that would indicate the gate refused, defeating the test's
        // premise that the seam was consulted twice.
        Exception firstFailure = assertThrows(Exception.class, connection::connectAndSubscribe);
        assertFalse(firstFailure instanceof SsrfPolicyException,
                "first connect's SSRF check must pass; the failure must come from "
                        + "the WebSocket dial (no listener on the pinned IP/port). "
                        + "Got SsrfPolicyException, which means the gate refused "
                        + "loopback under a LoopbackPermittingBlocklist — wiring bug.");

        // Second connect (simulated reconnect): seam now returns
        // 192.168.99.99, which the LoopbackPermittingBlocklist still
        // refuses via super.isBlocked. The gate must raise before any
        // WebSocket handshake.
        SsrfPolicyException ex = assertThrows(SsrfPolicyException.class,
                connection::connectAndSubscribe);
        assertTrue(ex.getMessage().contains("blocked IP"),
                "reconnect must re-resolve and refuse the now-blocked address with "
                        + "the literal \"blocked IP\" prefix; got: " + ex.getMessage());
        assertEquals(2, seamCalls.get(),
                "DNS must be re-resolved on every (re)connect — the resolver seam "
                        + "must be invoked exactly once per connect attempt");
    }

    private static SsrfGuardedHttpClient newSsrfClient(Function<String, List<InetAddress>> seam,
                                                       IpBlocklist blocklist) {
        // The package-private resolver-seam constructor is the same
        // surface the SsrfGuardedHttpClient unit tests use to install
        // deterministic DNS without registering a JVM-wide resolver
        // provider. The HTTP knobs (timeouts, body cap, redirect cap)
        // are irrelevant to the WebSocket path but must be valid.
        return new SsrfGuardedHttpClient(
                blocklist,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofMinutes(2),
                10L * 1024 * 1024,
                3,
                seam);
    }

    private static NostrRelayConnection newConnection(SsrfGuardedHttpClient ssrfClient) {
        return new NostrRelayConnection(
                RELAY_URI, FILTER,
                OptionalLong::empty, event -> { },
                FAST_BASE, FAST_MAX,
                HttpClient.newHttpClient(), ssrfClient,
                NostrRelayConnection.DEFAULT_PEER_IP_CHECK_INTERVAL,
                noOpTracker());
    }

    /** See {@code NostrStreamSourceTest.noOpTracker} — same intent: satisfy the constructor only. */
    private static RelayHealthTracker noOpTracker() {
        return new RelayHealthTracker(List.of(RELAY_URI), Integer.MAX_VALUE,
                Duration.ofHours(1), Integer.MAX_VALUE, Clock.systemUTC(), t -> { });
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static InetAddress privateAddress() {
        try {
            // 192.168.0.0/16 — RFC 1918 private; strictly inside the
            // IpBlocklist refusal set, distinct from 127.0.0.0/8 so
            // the seam's two responses produce visibly different
            // results in the test.
            return InetAddress.getByName("192.168.99.99");
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Test-only {@link IpBlocklist} subclass that permits loopback so
     * the first connect attempt's SSRF check passes (mirroring the
     * pattern used by Fetcher tests against the in-process
     * {@code HttpServer} fixture). Every other blocklist range is
     * still refused via the strict super-implementation.
     */
    private static final class LoopbackPermittingBlocklist extends IpBlocklist {

        @Override
        public boolean isBlocked(@NonNull InetAddress addr) {
            if (addr.isLoopbackAddress()) {
                return false;
            }
            return super.isBlocked(addr);
        }
    }
}
