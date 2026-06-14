package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.ssrf.IpBlocklist;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 tests for the post-verify productivity gate in
 * {@link NostrRelayConnection#handleFrame} (M1-326). The deep-review finding
 * was that {@code markProductive()} fired on the {@code EVENT} arm <em>before</em>
 * the event crossed the signature trust boundary, so a relay streaming
 * well-framed but signature-invalid EVENTs scored healthy — backoff reset every
 * connect, no cooldown ever applied, the all-relays-bad / terminal escalation
 * unreachable for that relay.
 *
 * <p>Each case drives {@code handleFrame} directly with raw NIP-01 frames and a
 * controllable {@link Predicate} event sink standing in for
 * {@code NostrStreamSource::enqueueInbound} (which runs {@code verifier.verify()}
 * and returns {@code false} on a forged signature). No socket, no container —
 * the gate decision is the only thing under test.
 */
class NostrProductivityAfterVerifyTest {

    private static final URI RELAY_URI = URI.create("wss://nostr-productivity-fixture.invalid:39753/relay");

    private static final String FILTER = "{\"kinds\":[1]}";

    @Test
    void signatureInvalidEventFloodDoesNotCreditProductivityAndKeepsCooldownReachable() {
        AtomicInteger verifyCalls = new AtomicInteger();
        // Stands in for verifier.verify() rejecting every event (forged sig).
        Predicate<NostrEvent> rejectAll = event -> {
            verifyCalls.incrementAndGet();
            return false;
        };

        // failureThreshold=1: one prior unproductive close parks the relay in a
        // long cooldown. The flood below must neither credit productivity nor
        // clear that cooldown — i.e. the per-relay escalation stays reachable.
        RelayHealthTracker tracker = new RelayHealthTracker(
                List.of(RELAY_URI), 1, Duration.ofHours(1), Integer.MAX_VALUE,
                Clock.systemUTC(), t -> { });
        tracker.recordFailure(RELAY_URI);
        Instant wellBeyondNow = Instant.now().plus(Duration.ofMinutes(30));
        assertTrue(tracker.nextAttemptTime(RELAY_URI).isAfter(wellBeyondNow),
                "precondition: one failure at threshold 1 must park the relay in a ~1h cooldown");

        NostrRelayConnection connection = newConnection(rejectAll, tracker);

        for (int i = 0; i < 5; i++) {
            connection.handleFrame(eventFrame("evt-" + i));
        }

        assertEquals(5, verifyCalls.get(),
                "every well-framed EVENT must reach the signature trust boundary (the sink)");
        assertFalse(connection.productiveSinceConnect(),
                "a flood of signature-invalid EVENTs must NOT credit productivity");
        // markProductive() is the sole caller of healthTracker.recordSuccess and
        // flips productiveSinceConnect in the same edge, so the still-false flag
        // above proves recordSuccess never fired: the primed cooldown survives
        // the flood, leaving the unproductive-close recordFailure escalation
        // (cooldown → all-relays-bad → terminal) reachable for this relay.
        assertTrue(tracker.nextAttemptTime(RELAY_URI).isAfter(wellBeyondNow),
                "the invalid-event flood must not clear the relay's cooldown");
    }

    @Test
    void singleSignatureValidEventCreditsProductivity() {
        Predicate<NostrEvent> acceptAll = event -> true;

        // Prime the same ~1h cooldown so the credit is observable two ways: the
        // productiveSinceConnect flag flips, AND recordSuccess clears the cooldown.
        RelayHealthTracker tracker = new RelayHealthTracker(
                List.of(RELAY_URI), 1, Duration.ofHours(1), Integer.MAX_VALUE,
                Clock.systemUTC(), t -> { });
        tracker.recordFailure(RELAY_URI);

        NostrRelayConnection connection = newConnection(acceptAll, tracker);

        connection.handleFrame(eventFrame("evt-ok"));

        assertTrue(connection.productiveSinceConnect(),
                "a verify-passing EVENT must credit productivity (no regression)");
        assertFalse(tracker.nextAttemptTime(RELAY_URI).isAfter(Instant.now().plus(Duration.ofMinutes(30))),
                "the verify-passing event's recordSuccess must clear the primed cooldown");
    }

    @Test
    void eoseCreditsProductivityWithoutConsultingTheSink() {
        AtomicInteger verifyCalls = new AtomicInteger();
        Predicate<NostrEvent> rejectAll = event -> {
            verifyCalls.incrementAndGet();
            return false;
        };
        NostrRelayConnection connection = newConnection(rejectAll, noOpTracker());

        connection.handleFrame("[\"EOSE\",\"sub1\"]");

        assertTrue(connection.productiveSinceConnect(),
                "EOSE is relay liveness independent of event validity and must credit productivity");
        assertEquals(0, verifyCalls.get(),
                "the EOSE arm must not route through the event sink / signature gate");
    }

    private static NostrRelayConnection newConnection(Predicate<NostrEvent> eventSink,
                                                      RelayHealthTracker tracker) {
        return new NostrRelayConnection(
                RELAY_URI, FILTER,
                OptionalLong::empty, eventSink,
                Duration.ofMillis(50), Duration.ofMillis(200),
                HttpClient.newHttpClient(), unusedSsrfClient(),
                NostrRelayConnection.DEFAULT_PEER_IP_CHECK_INTERVAL,
                tracker);
    }

    /** See {@code NostrSsrfTest.noOpTracker} — same intent: satisfy the constructor only. */
    private static RelayHealthTracker noOpTracker() {
        return new RelayHealthTracker(List.of(RELAY_URI), Integer.MAX_VALUE,
                Duration.ofHours(1), Integer.MAX_VALUE, Clock.systemUTC(), t -> { });
    }

    // handleFrame never touches the SSRF client; this satisfies the non-null
    // constructor contract only. The resolver seam is never invoked.
    private static SsrfGuardedHttpClient unusedSsrfClient() {
        return new SsrfGuardedHttpClient(
                new IpBlocklist(),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofMinutes(2),
                10L * 1024 * 1024,
                3,
                (String host) -> List.<InetAddress>of());
    }

    private static String eventFrame(String id) {
        return "[\"EVENT\",\"sub1\",{"
                + "\"id\":\"" + id + "\",\"pubkey\":\"pk\",\"created_at\":1700000000,"
                + "\"kind\":1,\"tags\":[[\"e\",\"x\"]],\"content\":\"hi\",\"sig\":\"deadbeef\"}]";
    }
}
