package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the dedup-record-after-offer fix (M1-287): {@link
 * NostrStreamSource} records an event id in the {@link NostrDedupFilter} only
 * after the inbound queue accepts it, so a queue-full drop does not poison the
 * dedup set against the relay's reconnect replay. This restores the
 * at-least-once outbox invariant (architecture.md: "an event is written to the
 * outbox before the implementation considers it processed").
 *
 * <p>Each test drives {@link NostrStreamSource#enqueueInbound} directly against
 * a 1-slot inbound queue so {@code offer} returns false deterministically after
 * a single filler event, without a live relay flooding INBOUND_CAPACITY
 * events.</p>
 */
class NostrDedupRecordAfterOfferTest {

    // Two distinct validly-signed kind-1 fixtures: the filler occupies the
    // single queue slot; the target is the event the full queue drops and the
    // reconnect later replays.
    private static final NostrEvent FILLER = NostrSignedEventFixtures.VALID_KIND_1_DRAIN_A_EVENT;
    private static final NostrEvent TARGET = NostrSignedEventFixtures.VALID_KIND_1_EVENT;
    private static final String TARGET_ID = NostrSignedEventFixtures.KIND_1_ID;

    /** Acceptance item 2: the id is committed only after a successful offer. */
    @Test
    void droppedEventIdNotRecorded() {
        NostrDedupFilter dedupFilter = new NostrDedupFilter();
        NostrStreamSource worker = singleSlotWorker(dedupFilter);

        assertTrue(worker.enqueueInbound(FILLER), "filler occupies the only inbound slot");
        assertFalse(worker.enqueueInbound(TARGET), "target dropped: inbound queue full");

        assertFalse(dedupFilter.seen(TARGET_ID),
                "a dropped event's id is NOT recorded — record happens only after a successful offer");
    }

    /** Acceptance item 1: the dropped event is accepted on replay once room exists. */
    @Test
    void droppedEventReplaysAfterQueueDrains() {
        NostrDedupFilter dedupFilter = new NostrDedupFilter();
        NostrStreamSource worker = singleSlotWorker(dedupFilter);

        assertTrue(worker.enqueueInbound(FILLER), "filler occupies the only inbound slot");
        assertFalse(worker.enqueueInbound(TARGET), "target dropped: inbound queue full");

        // The delivery loop catches up (modelled by draining the queue) and the
        // relay reconnect replays the dropped event. With the dedup set
        // un-poisoned, the replay is accepted rather than silently rejected.
        worker.drainInbound();
        assertTrue(worker.enqueueInbound(TARGET),
                "replayed target accepted after the queue drained — the drop did not poison the dedup set");
    }

    private static NostrStreamSource singleSlotWorker(NostrDedupFilter dedupFilter) {
        // start() is never called, so the relay/http/ssrf collaborators are
        // inert; they are passed only to satisfy the constructor's non-null
        // fields. The verifier is real because enqueueInbound verifies every
        // event's BIP-340 signature before the dedup/offer logic runs.
        List<URI> noRelays = List.of();
        return new NostrStreamSource(noRelays, OptionalLong::empty,
                Duration.ofMillis(20), Duration.ofMillis(100),
                HttpClient.newHttpClient(), new SsrfGuardedHttpClient(),
                new NostrEventVerifier(), noOpTracker(noRelays), dedupFilter,
                /* inboundCapacity */ 1);
    }

    /** See NostrStreamSourceTest.noOpTracker — wired only to satisfy the constructor. */
    private static RelayHealthTracker noOpTracker(List<URI> relayUris) {
        return new RelayHealthTracker(relayUris, Integer.MAX_VALUE,
                Duration.ofHours(1), Integer.MAX_VALUE, Clock.systemUTC(), t -> { });
    }
}
