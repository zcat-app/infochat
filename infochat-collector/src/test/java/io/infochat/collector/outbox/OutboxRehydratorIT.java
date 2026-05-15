package io.infochat.collector.outbox;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for {@link OutboxRehydrator} against the Quarkus
 * DevServices Postgres. Seeds posts directly via JDBC with a mix of
 * {@code status='RAW'} and {@code 'READY'} rows, runs the rehydrator,
 * and asserts:
 * <ul>
 *   <li>{@link TestEvalQueueConsumer} receives the test's seeded
 *       {@code 'RAW'} keys (and NOT the seeded {@code 'READY'} ones),
 *       in {@code (fetched_at, id)} relative order;</li>
 *   <li>A re-run after no state change re-emits the SAME RAW set
 *       (rehydrator does not mark posts as "re-enqueued"; idempotency
 *       lives at the eval-worker boundary per
 *       {@code docs/spec/schema.md} §Invariants Invariant 5);</li>
 *   <li>Marking some {@code 'RAW'} posts to {@code 'READY'} and
 *       re-running shrinks the re-enqueue set to the remaining
 *       seeded {@code 'RAW'} posts.</li>
 * </ul>
 *
 * <p>The test does NOT assert absolute consumer counts because other
 * ITs in the same Quarkus container also write {@code 'RAW'} posts
 * (PostPersisterIT, FetchSchedulerIT). Assertions filter the
 * consumer's drained emissions to the test's own seeded post ids and
 * check set / relative-order properties against those.
 *
 * <p>Method order is fixed because the cases share DB state and
 * each builds on the previous.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutboxRehydratorIT {

    @Inject
    DataSource dataSource;

    @Inject
    OutboxRehydrator rehydrator;

    @Inject
    TestEvalQueueConsumer consumer;

    private static UUID seededSourceUuid;

    // Three RAW posts (fetched_at strictly increasing) plus two READY
    // posts seeded directly via JDBC. The rehydrator must pick up the
    // three RAW; the READY rows must NOT be re-enqueued.
    private static final Instant T0 = Instant.parse("2026-05-15T08:00:00Z");
    private static final Instant T1 = Instant.parse("2026-05-15T08:01:00Z");
    private static final Instant T2 = Instant.parse("2026-05-15T08:02:00Z");
    private static final Instant T3 = Instant.parse("2026-05-15T08:03:00Z");
    private static final Instant T4 = Instant.parse("2026-05-15T08:04:00Z");

    private static UUID rawId0;
    private static UUID rawId1;
    private static UUID rawId2;
    private static UUID readyId0;
    private static UUID readyId1;

    @BeforeEach
    void drainConsumerBuffer() {
        // The OutboxRehydrator @PostConstruct fired at Quarkus
        // startup, and prior ITs may have left emissions in the
        // consumer's buffer. Drain at the start of each test so the
        // post-rehydrate drain captures only this test's events.
        consumer.drain();
    }

    @Test
    @Order(1)
    void rehydratesOnlyRawPostsInFetchedAtOrder() throws Exception {
        seededSourceUuid = seedRssSource(
            "https://rehydrator-it.example.test/feed.xml",
            "Rehydrator IT source");

        rawId0 = insertPost(seededSourceUuid, "urn:reh:raw:0", T0, "RAW");
        rawId1 = insertPost(seededSourceUuid, "urn:reh:raw:1", T1, "RAW");
        rawId2 = insertPost(seededSourceUuid, "urn:reh:raw:2", T2, "RAW");
        readyId0 = insertPost(seededSourceUuid, "urn:reh:ready:0", T3, "READY");
        readyId1 = insertPost(seededSourceUuid, "urn:reh:ready:1", T4, "READY");

        Set<UUID> mySeededRawIds = new LinkedHashSet<>();
        mySeededRawIds.add(rawId0);
        mySeededRawIds.add(rawId1);
        mySeededRawIds.add(rawId2);

        rehydrator.rehydrate();
        // The async dispatch needs a moment to deliver to the consumer.
        awaitConsumerStable();

        List<PostPersister.PersistedPostKey> drained = consumer.drain();
        List<UUID> drainedIds = drained.stream()
            .map(PostPersister.PersistedPostKey::id)
            .collect(Collectors.toList());
        Set<UUID> drainedIdSet = new LinkedHashSet<>(drainedIds);

        // (a) all three seeded RAW posts appear in the emission.
        assertTrue(drainedIdSet.contains(rawId0), "rawId0 must be re-enqueued");
        assertTrue(drainedIdSet.contains(rawId1), "rawId1 must be re-enqueued");
        assertTrue(drainedIdSet.contains(rawId2), "rawId2 must be re-enqueued");

        // (b) neither READY post id appears.
        assertFalse(drainedIdSet.contains(readyId0),
            "READY post must NOT be re-enqueued");
        assertFalse(drainedIdSet.contains(readyId1),
            "READY post must NOT be re-enqueued");

        // (c) relative order: in the drained list filtered to my
        // seeded RAW ids, the order is (T0 -> T1 -> T2). The
        // rehydrator's ORDER BY (fetched_at, id) guarantees this.
        List<UUID> myOrderedIds = drainedIds.stream()
            .filter(mySeededRawIds::contains)
            .collect(Collectors.toList());
        assertEquals(List.of(rawId0, rawId1, rawId2), myOrderedIds,
            "rehydrator must emit my seeded RAW posts in (fetched_at, id) order");
    }

    @Test
    @Order(2)
    void rerunReemitsSameSetWhenNoStateChanged() throws Exception {
        rehydrator.rehydrate();
        awaitConsumerStable();

        List<PostPersister.PersistedPostKey> drained = consumer.drain();
        Set<UUID> drainedIdSet = drained.stream()
            .map(PostPersister.PersistedPostKey::id)
            .collect(Collectors.toSet());

        assertTrue(drainedIdSet.contains(rawId0), "re-run must re-emit rawId0");
        assertTrue(drainedIdSet.contains(rawId1), "re-run must re-emit rawId1");
        assertTrue(drainedIdSet.contains(rawId2), "re-run must re-emit rawId2");
        assertFalse(drainedIdSet.contains(readyId0),
            "re-run must NOT emit READY posts");
        assertFalse(drainedIdSet.contains(readyId1),
            "re-run must NOT emit READY posts");
    }

    @Test
    @Order(3)
    void shrinksReemitSetWhenSomeRawTransitionsToReady() throws Exception {
        // Promote rawId0 to READY — simulates T1-D's eval pipeline
        // marking the post as done. The rehydrator's next call must
        // emit rawId1 and rawId2 but not rawId0.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE post SET status = 'READY', ready_at = now() WHERE id = ?")) {
            ps.setObject(1, rawId0);
            ps.executeUpdate();
        }

        rehydrator.rehydrate();
        awaitConsumerStable();

        List<PostPersister.PersistedPostKey> drained = consumer.drain();
        Set<UUID> drainedIdSet = drained.stream()
            .map(PostPersister.PersistedPostKey::id)
            .collect(Collectors.toSet());

        assertFalse(drainedIdSet.contains(rawId0),
            "rawId0 (promoted to READY) must NOT be re-enqueued");
        assertTrue(drainedIdSet.contains(rawId1), "rawId1 still RAW; must be re-enqueued");
        assertTrue(drainedIdSet.contains(rawId2), "rawId2 still RAW; must be re-enqueued");
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

    private UUID insertPost(UUID sourceUuid, String upstreamId, Instant fetchedAt, String status)
            throws Exception {
        final String sql =
            "INSERT INTO post ("
                + "  id, uid, source_id, upstream_identifier, url, title, body, "
                + "  author, published_at, fetched_at, status, "
                + "  stage1_done, stage2_done, tagger_done, embedding_done, "
                + "  stage1_flagged, stage2_failed, tagger_fallback, tags"
                + ") VALUES ("
                + "  gen_random_uuid(), ?, ?, ?, NULL, ?, NULL, NULL, NULL, ?, ?, "
                + "  FALSE, FALSE, FALSE, FALSE, FALSE, FALSE, FALSE, '{}'"
                + ") RETURNING id";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            // uid: a hex-shaped placeholder distinct per upstreamId
            // so the UNIQUE (uid, fetched_at) constraint doesn't fire.
            ps.setString(1, "deadbeef" + Integer.toHexString(upstreamId.hashCode()));
            ps.setObject(2, sourceUuid);
            ps.setString(3, upstreamId);
            ps.setString(4, "Title for " + upstreamId);
            ps.setTimestamp(5, Timestamp.from(fetchedAt));
            ps.setString(6, status);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    /**
     * Wait for the consumer's buffer size to stop changing for one
     * polling window — the heuristic that SmallRye has finished
     * delivering whatever {@code rehydrator.rehydrate()} emitted.
     * The wait is bounded by 5 seconds.
     */
    private void awaitConsumerStable() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        int lastSeen = consumer.size();
        int stableTicks = 0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
            int now = consumer.size();
            if (now == lastSeen) {
                stableTicks++;
                if (stableTicks >= 3) {
                    return;
                }
            } else {
                lastSeen = now;
                stableTicks = 0;
            }
        }
    }
}
