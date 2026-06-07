package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.ssrf.IpBlocklist;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the M1-101 mid-session peer-IP-change watcher
 * in {@link NostrRelayConnection}. A live {@link FakeNostrRelay}
 * provides a real {@code ws://} endpoint; the
 * {@link SsrfGuardedHttpClient} returns one IP at connect time (so
 * the handshake succeeds against the relay) and a different IP on
 * every subsequent re-resolve (so the watcher's
 * {@code peerIpDiverged} check fires and the connection is hard-
 * closed). The test exercises the spec's "any peer-IP change
 * observed at the socket layer is a hard close" promise (see
 * {@code docs/spec/security.md} §SSRF).
 *
 * <p>Plain JUnit, no Quarkus container — the watcher path does not
 * touch the DB or the supervisor. Failsafe picks the file up via the
 * {@code *IT.java} naming convention.
 */
class NostrSsrfIT {

    private static final Duration AWAIT = Duration.ofSeconds(5);

    // Watcher interval kept short so the first tick fires well within
    // the FakeNostrRelay's life. Production default is 60s; this test
    // exercises the same code path on a compressed schedule.
    private static final Duration PEER_IP_CHECK_INTERVAL = Duration.ofMillis(50);

    private static final String FILTER = "{\"kinds\":[1]}";

    private FakeNostrRelay relay;

    private NostrRelayConnection connection;

    @BeforeEach
    void setup() {
        relay = new FakeNostrRelay();
    }

    @AfterEach
    void teardown() {
        if (connection != null) {
            connection.stop();
        }
        if (relay != null) {
            relay.close();
        }
    }

    @Test
    void peerIpChangeTriggersHardClose() {
        // Two-phase resolver seam:
        //   Call 1 (connect-time SSRF check) — returns 127.0.0.1 so the
        //     WebSocket handshake reaches the local FakeNostrRelay
        //     (which binds to 127.0.0.1).
        //   Call 2+ (periodic watcher re-resolve) — returns
        //     169.254.169.254 (cloud-metadata; refused by the strict
        //     super-implementation of IpBlocklist). The watcher's
        //     resolveForWebSocket raises SsrfPolicyException, which
        //     peerIpDiverged() treats as a peer-IP-change signal →
        //     hard close.
        // The "blocked re-resolve" arm is deterministic; the "different
        // but still allowed" arm is equally covered by the watcher's
        // intersection check, but reaching it would require routing a
        // non-pinned loopback alias to the FakeNostrRelay, which is
        // platform-dependent. The spec's "peer-IP change" wording
        // covers either trigger.
        AtomicInteger seamCalls = new AtomicInteger();
        Function<String, List<InetAddress>> seam = host -> {
            int n = seamCalls.incrementAndGet();
            return List.of(n == 1 ? loopback() : blockedMetadata());
        };

        SsrfGuardedHttpClient ssrfClient = new SsrfGuardedHttpClient(
                new LoopbackPermittingBlocklist(),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofMinutes(2),
                10L * 1024 * 1024,
                3,
                seam);

        connection = new NostrRelayConnection(
                relay.uri(), FILTER,
                OptionalLong::empty, event -> { },
                Duration.ofMillis(50), Duration.ofMillis(200),
                HttpClient.newHttpClient(), ssrfClient,
                PEER_IP_CHECK_INTERVAL,
                noOpTracker(List.of(relay.uri())));

        connection.start();

        // Phase 1: the handshake completes against the fake relay and
        // the REQ frame reaches it. Without these waits the test could
        // race the watcher: if the watcher fired before the WebSocket
        // listener finished sending the REQ, awaitConnectionCount(0)
        // would succeed for the wrong reason (connect never completed
        // rather than connect succeeded then watcher aborted).
        assertTrue(relay.awaitConnectionCount(1, AWAIT),
                "WebSocket handshake must complete against the fake relay");
        assertTrue(relay.awaitFrameCount(1, AWAIT),
                "REQ frame must reach the relay before the watcher fires");

        // Phase 2: the watcher's first tick re-resolves through the
        // SSRF gate (seamCalls reaches 2). Asserting on the seam
        // counter BEFORE the connection-drop assertion isolates the
        // failure mode: if seamCalls stays at 1, the watcher never
        // ran (and any natural close would mask the bug). If the
        // counter advances, the watcher is on the right code path.
        awaitCondition(() -> seamCalls.get() >= 2, AWAIT);
        assertTrue(seamCalls.get() >= 2,
                "the resolver seam must have been invoked at least once after "
                        + "connect (the periodic peer-IP watcher); seamCalls="
                        + seamCalls.get());

        // Phase 3: peerIpDiverged returned true (blocked re-resolve),
        // the runLoop called webSocket.abort(), and the fake relay's
        // closeHandler decrements liveConnectionCount to 0.
        assertTrue(relay.awaitConnectionCount(0, AWAIT),
                "watcher must hard-close the connection after the re-resolved peer "
                        + "address is refused by the SSRF gate (mid-session DNS rebind)");
    }

    private static void awaitCondition(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static InetAddress blockedMetadata() {
        try {
            // 169.254.169.254 — cloud-instance metadata, refused by
            // the link-local arm of the default IpBlocklist
            // (169.254.0.0/16). LoopbackPermittingBlocklist only
            // carves out loopback; the metadata range is still
            // refused via super.isBlocked.
            return InetAddress.getByName("169.254.169.254");
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    /** See {@code NostrStreamSourceTest.noOpTracker} — same intent: satisfy the constructor only. */
    private static RelayHealthTracker noOpTracker(List<java.net.URI> relayUris) {
        return new RelayHealthTracker(relayUris, Integer.MAX_VALUE,
                Duration.ofHours(1), Integer.MAX_VALUE, Clock.systemUTC(), t -> { });
    }

    /**
     * Loopback-permitting {@link IpBlocklist} so the connect-time
     * SSRF check passes against the FakeNostrRelay's 127.0.0.1
     * binding. Every other range (including the link-local /
     * cloud-metadata 169.254.0.0/16 used in the watcher arm) is
     * still refused via the strict super-implementation — exactly
     * the carve-out shape the Fetcher tests use against their own
     * in-process HTTP fixture.
     */
    private static final class LoopbackPermittingBlocklist extends IpBlocklist {

        @Override
        public boolean isBlocked(InetAddress addr) {
            if (addr.isLoopbackAddress()) {
                return false;
            }
            return super.isBlocked(addr);
        }
    }
}
