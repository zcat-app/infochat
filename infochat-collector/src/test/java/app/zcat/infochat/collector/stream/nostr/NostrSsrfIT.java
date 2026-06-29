package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.ssrf.LoopbackPermittingBlocklist;
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

    /**
     * Blocklist arm: the watcher re-resolves to 169.254.169.254 (cloud-
     * metadata; refused by the strict super-implementation of IpBlocklist),
     * so {@code resolveForWebSocket} raises SsrfPolicyException, which
     * {@code peerIpDiverged()} treats as a peer-IP-change signal → hard close.
     */
    @Test
    void blockedReResolveTriggersHardClose() {
        assertWatcherHardClosesOnDivergence(blockedMetadata());
    }

    /**
     * Connection-migration arm (M1-498): the watcher re-resolves to a DIFFERENT
     * but still-allowed loopback alias (127.0.0.2). The blocklist does NOT
     * refuse it, so {@code resolveForWebSocket} returns normally; divergence is
     * detected purely by {@code peerIpDiverged()}'s set-intersection check — the
     * pinned set {127.0.0.1} is disjoint from the re-resolved {127.0.0.2} — and
     * the connection is still hard-closed. This is the spec's "allowed-but-
     * changed IP" trigger, distinct from the blocklist arm above; a regression
     * that only fired on the blocklist arm would ship green without it.
     */
    @Test
    void allowedButChangedPeerIpTriggersHardClose() {
        assertWatcherHardClosesOnDivergence(loopbackAlias());
    }

    /**
     * Drive the live watcher with a two-phase resolver seam — call 1 (the
     * connect-time SSRF check) returns 127.0.0.1 so the WebSocket handshake
     * reaches the local FakeNostrRelay; call 2+ (the periodic watcher
     * re-resolve) returns {@code secondResolve}. Asserts the handshake
     * completes, the watcher actually re-resolves, then the connection is
     * hard-closed because the re-resolved peer address diverges from the
     * pinned set.
     */
    private void assertWatcherHardClosesOnDivergence(InetAddress secondResolve) {
        AtomicInteger seamCalls = new AtomicInteger();
        Function<String, List<InetAddress>> seam = host -> {
            int n = seamCalls.incrementAndGet();
            return List.of(n == 1 ? loopback() : secondResolve);
        };

        SsrfGuardedHttpClient ssrfClient = new SsrfGuardedHttpClient(
                LoopbackPermittingBlocklist.create(),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofMinutes(2),
                10L * 1024 * 1024,
                3,
                seam);

        connection = new NostrRelayConnection(
                relay.uri(), FILTER,
                OptionalLong::empty, event -> true,
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

        // Phase 3: peerIpDiverged returned true, the runLoop called
        // webSocket.abort(), and the fake relay's closeHandler decrements
        // liveConnectionCount to 0.
        assertTrue(relay.awaitConnectionCount(0, AWAIT),
                "watcher must hard-close the connection after the re-resolved peer "
                        + "address diverges from the pinned set (mid-session DNS rebind)");
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

    private static InetAddress loopbackAlias() {
        try {
            // 127.0.0.2 — a DIFFERENT address in the loopback range
            // (127.0.0.0/8). LoopbackPermittingBlocklist permits the whole
            // range, so the re-resolve passes the SSRF gate; it is still
            // disjoint from the pinned 127.0.0.1, exercising the set-
            // intersection divergence arm rather than the blocklist arm.
            return InetAddress.getByName("127.0.0.2");
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
            // refused via super.isBlockedAgainst.
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
}
