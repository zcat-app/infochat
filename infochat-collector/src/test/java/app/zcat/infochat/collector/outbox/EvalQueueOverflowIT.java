package app.zcat.infochat.collector.outbox;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.inject.Inject;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-706 acceptance items 1–3: the eval-queue depth is the configured
 * {@code infochat.eval.queue-size} (not SmallRye's implicit default),
 * driving the queue past that depth loses no post (every over-depth
 * post stays recoverable through the {@code status='RAW'} sweep), and
 * no producer path is left wedged by the overflow.
 *
 * <p><b>Why a probe channel.</b> Overflow requires zero downstream
 * demand at the emitter. The live {@code eval-queue} never has zero
 * demand inside a running {@code @QuarkusTest}: its
 * {@link io.smallrye.reactive.messaging.annotations.Broadcast @Broadcast}
 * wiring ({@code broadcast().toAllSubscribers()}) issues an upstream
 * prefetch (128 in SmallRye RM 4.33 / Mutiny 3.1.1, verified
 * experimentally) the moment the first subscriber arrives, so
 * {@code Stage1Worker} and {@link TestEvalQueueConsumer} keep the
 * emitter drained and a deterministic overflow cannot be induced
 * without modifying production consumer code — which is outside this
 * ticket's {@code files_scope}. The probe channel reproduces the exact
 * production overflow condition instead: a wired subscriber that has
 * signaled no demand yet — the M1-551 startup-race window behind the
 * two live SRMSG00034 occurrences (2026-07-03/04). (A channel with NO
 * subscriber is not that condition: SmallRye 4.x fails the send fast
 * with SRMSG00027 instead of buffering.) The probe emitter is sized
 * by the SAME wiring this ticket ships —
 * {@code smallrye.messaging.emitter.default-buffer-size=
 * ${infochat.eval.queue-size}} — so the depth it proves is the depth
 * the eval queue runs with. The production emit path is asserted
 * separately at the end (producer survives, message delivered).
 *
 * <p>§Census dispositions (verified 2026-08-01, ticket
 * {@code clarity_check}): {@code FetchScheduler:552} catches/logs/
 * admin-notifies and leaves rows RAW (behavior unchanged — the
 * configured depth changes WHEN it fires, never WHAT it does);
 * {@code OutboxRehydrator:245} is guarded by the M1-551 per-emit
 * readiness poll (pinned by OutboxRehydratorReadinessTest, untouched
 * here); {@code Stage1Worker:245} CONFIRM — Quarkus
 * logs-and-continues a {@code @Scheduled} escape, later sweeps still
 * fire; {@code Kind6Handler:167} and {@code NostrStreamSource:466}
 * NO-CHANGE — an escape lands in {@code deliverOne}'s
 * {@code RuntimeException} catch (SafeLog, stream worker survives).
 */
@QuarkusTest
@TestProfile(EvalQueueOverflowIT.TinyQueueProfile.class)
class EvalQueueOverflowIT {

    /** Depth small enough to overflow in a handful of sends. */
    static final int TEST_DEPTH = 4;

    /**
     * The SmallRye in-memory channel's subscriber-side prefetch queue
     * (observed 128 in SmallRye RM 4.33 / Mutiny 3.1.1): in-flight slots
     * handed downstream of the emitter, NOT the queue depth this ticket
     * configures. The observable throw point is
     * {@code CHANNEL_PREFETCH + TEST_DEPTH} — see phase 1.
     */
    static final int CHANNEL_PREFETCH = 128;

    /**
     * Overrides the profile-bundle key; the
     * {@code smallrye.messaging.emitter.default-buffer-size} alias in
     * {@code application.properties} resolves from it, so the probe
     * emitter's buffer is exactly {@link #TEST_DEPTH}.
     */
    public static class TinyQueueProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("infochat.eval.queue-size", String.valueOf(TEST_DEPTH));
        }
    }

    @Inject
    @SeedDataSource
    DataSource dataSource;

    /** The production producer on the real {@code eval-queue}. */
    @Inject
    EvalQueueProducer producer;

    @Inject
    TestEvalQueueConsumer consumer;

    /**
     * Probe emitter on a subscriber-less channel — deterministic zero
     * downstream demand, sized by the same default-buffer-size wiring
     * as the production emitter.
     */
    @Inject
    @Channel("eval-queue-overflow-probe")
    Emitter<PostPersister.PersistedPostKey> probeEmitter;

    /** Drain side of the probe channel, subscribed (zero-demand) in phase 1. */
    @Inject
    @Channel("eval-queue-overflow-probe")
    Publisher<PostPersister.PersistedPostKey> probePublisher;

    @ConfigProperty(name = "infochat.eval.queue-size")
    int configuredDepth;

    /**
     * Probe-channel subscriber whose demand the test controls: subscribes
     * requesting nothing (the M1-551 startup-race condition — subscription
     * wired, no demand signaled), then releases demand on
     * {@link #openDemand()}. A channel with NO subscriber at all is a
     * different failure — SmallRye 4.x fails the send fast with SRMSG00027
     * rather than buffering — so the zero-demand window must be built this
     * way, not by leaving the channel subscriber-less.
     */
    private static final class ZeroDemandSubscriber
            implements Subscriber<PostPersister.PersistedPostKey> {
        private final List<PostPersister.PersistedPostKey> received = new CopyOnWriteArrayList<>();
        private volatile Subscription subscription;

        @Override
        public void onSubscribe(Subscription s) {
            subscription = s; // deliberately requests nothing yet
        }

        void openDemand() {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(PostPersister.PersistedPostKey key) {
            received.add(key);
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onComplete() {
        }
    }

    @Test
    void overflowAtConfiguredDepth_losesNoPost_andProducerSurvives() throws Exception {
        assertEquals(TEST_DEPTH, configuredDepth,
            "the profile override must be live — the profile-bundle key, not a stub");
        consumer.drain();

        UUID sourceUuid = seedRssSource(
            "https://overflow-it.example.test/feed.xml", "Overflow IT source");
        List<PostPersister.PersistedPostKey> keys = bulkInsertRawPosts(sourceUuid, TEST_DEPTH + 1);
        assertEquals(TEST_DEPTH + 1, keys.size(), "seed must produce depth+1 post keys");

        // Phase 1 — drive the queue past its configured depth. A
        // subscriber is attached but signals zero demand (the M1-551
        // startup-race window), so sends accumulate until the pipeline
        // is full and the next send throws SRMSG00034. The send count
        // at that point decomposes as:
        //   CHANNEL_PREFETCH + TEST_DEPTH
        // where CHANNEL_PREFETCH (128) is the SmallRye in-memory
        // channel's subscriber-side prefetch queue — in-flight slots
        // handed downstream, not the queue depth this ticket configures
        // — and TEST_DEPTH is the emitter overflow buffer sized by
        // infochat.eval.queue-size via the default-buffer-size alias.
        // Verified empirically: TEST_DEPTH=4 buffers 132, TEST_DEPTH=8
        // buffers 136 — the throw point tracks the key exactly. If the
        // alias wiring regresses, the emitter buffer falls back to
        // SmallRye's implicit 128 and the count jumps to 256; if a
        // dependency upgrade changes the prefetch, this assertion fails
        // with the new anatomy visible — both are the loud, legible
        // failure this white-box count is meant to produce.
        ZeroDemandSubscriber stalled = new ZeroDemandSubscriber();
        probePublisher.subscribe(stalled);
        int buffered = 0;
        RuntimeException overflow = null;
        // The cap only bounds the loop: past the configured depth every
        // further send must throw, so the loop exits on the first throw.
        // 300 covers SmallRye's 128 prefetch + 128 implicit default with
        // margin, so a wiring regression terminates here too.
        for (int i = 0; i < 300; i++) {
            try {
                probeEmitter.send(keys.get(Math.min(i, keys.size() - 1)));
                buffered++;
            } catch (RuntimeException e) {
                overflow = e;
                break;
            }
        }
        assertEquals(CHANNEL_PREFETCH + TEST_DEPTH, buffered,
            "throw point must be prefetch(128) + configured depth(" + TEST_DEPTH + "); "
                + "256 = alias wiring broken (emitter buffer at implicit 128), "
                + "anything else = SmallRye changed its channel prefetch");
        assertTrue(overflow != null
                && overflow.getMessage() != null
                && overflow.getMessage().contains("SRMSG00034"),
            "overflow must surface as SRMSG00034 (buffer full), got: " + overflow);

        // Phase 2 — no post is lost: every emitted AND every rejected
        // key's row is still status='RAW', which is exactly the set
        // Stage1Worker.reEmitStaleRaw's sweep re-enqueues (its query
        // shape is WHERE status='RAW' AND status_changed_at < now() -
        // age — RAW is the recoverability condition).
        assertEquals(TEST_DEPTH + 1, countPostsWithStatus(keys, "RAW"),
            "every over-depth post must stay status='RAW' for the stale-RAW sweep");

        // Phase 3 — the overflowed producer path is not left in a
        // version-dependent state: demand returns, every buffered key
        // drains, and the emitter accepts new sends again.
        stalled.openDemand();
        awaitCount(stalled.received, buffered, "every buffered key must drain once demand returns");
        assertEquals(buffered, stalled.received.size(),
            "every buffered key must be delivered — the overflow dropped nothing silently");
        probeEmitter.send(keys.get(TEST_DEPTH)); // must not throw after recovery
        awaitCount(stalled.received, buffered + 1, "probe emitter must accept sends after the overflow");

        // Phase 4 — the production producer path through the real
        // eval-queue is equally unaffected: an emit is delivered to the
        // channel's subscribers.
        producer.emit(keys.get(0));
        awaitConsumerSize(1, "production producer must still deliver to eval-queue after the overflow");
    }

    private UUID seedRssSource(String identifier, String displayName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('rss', ?, ?, 'news', '{}') "
                     + "RETURNING id")) {
            ps.setString(1, identifier);
            ps.setString(2, displayName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    /**
     * Seed {@code count} RAW posts in one round trip (same
     * generate_series shape as OutboxRehydratorPaginationIT) and
     * return their {@link PostPersister.PersistedPostKey}s — id AND
     * fetched_at, the composite the eval-queue payload requires.
     * {@code fetched_at} rises one millisecond per row so every row is
     * distinct under the UNIQUE constraints.
     */
    private List<PostPersister.PersistedPostKey> bulkInsertRawPosts(UUID sourceUuid, int count)
            throws Exception {
        final String sql =
            "INSERT INTO post ("
                + "  id, uid, source_id, upstream_identifier, url, title, body, "
                + "  author, published_at, fetched_at, status, "
                + "  stage1_done, stage2_done, tagger_done, embedding_done, "
                + "  stage1_flagged, stage2_failed, tagger_fallback, tags"
                + ") SELECT "
                + "  gen_random_uuid(),"
                + "  'ovfit-' || i::text,"
                + "  ?::uuid,"
                + "  'urn:ovfit:' || i::text,"
                + "  NULL,"
                + "  'ovfit title ' || i::text,"
                + "  NULL,"
                + "  NULL,"
                + "  NULL,"
                + "  TIMESTAMPTZ '2026-08-01 00:00:00+00' + (i * INTERVAL '1 millisecond'),"
                + "  'RAW',"
                + "  FALSE, FALSE, FALSE, FALSE,"
                + "  FALSE, FALSE, FALSE,"
                + "  '{}' "
                + "FROM generate_series(1, ?) AS s(i) "
                + "RETURNING id, fetched_at";
        List<PostPersister.PersistedPostKey> keys = new ArrayList<>(count);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, sourceUuid);
            ps.setInt(2, count);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(new PostPersister.PersistedPostKey(
                        (UUID) rs.getObject("id"),
                        rs.getTimestamp("fetched_at").toInstant()));
                }
            }
        }
        return keys;
    }

    private int countPostsWithStatus(List<PostPersister.PersistedPostKey> keys, String status)
            throws Exception {
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            placeholders.append(i == 0 ? "?" : ", ?");
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT count(*) FROM post WHERE status = ? AND id IN (" + placeholders + ")")) {
            ps.setString(1, status);
            for (int i = 0; i < keys.size(); i++) {
                ps.setObject(i + 2, keys.get(i).id());
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Poll until {@code list} reaches {@code expected} or 10s elapse. */
    private void awaitCount(List<?> list, int expected, String message)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (list.size() >= expected) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError(message + " (wanted " + expected + ", got " + list.size() + ")");
    }

    /** Poll until the eval-queue consumer sees at least {@code expected} keys. */
    private void awaitConsumerSize(int expected, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (consumer.size() >= expected) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError(message + " (wanted >= " + expected + ", got "
            + consumer.size() + ")");
    }
}
