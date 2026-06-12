package app.zcat.infochat.collector.eval.stage1;

import app.zcat.infochat.collector.outbox.PostPersister;
import app.zcat.infochat.collector.outbox.TestEvalQueueConsumer;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U-18 companion: the scheduled stale-RAW re-emitter re-enqueues posts
 * stuck in {@code status='RAW'} past the profile-driven age, reusing
 * the OutboxRehydrator query shape. A post still draining (fresh
 * {@code status_changed_at}) is NOT re-emitted; a non-RAW post is
 * never a candidate.
 *
 * <p>Asserts against {@link TestEvalQueueConsumer}'s drained emissions
 * filtered to this test's seeded ids (other ITs in the same Quarkus
 * container also write RAW posts), the same isolation pattern
 * {@code OutboxRehydratorIT} uses.
 */
@QuarkusTest
class Stage1WorkerStaleRawReEmitterIT {

    @Inject
    Stage1Worker worker;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    TestEvalQueueConsumer consumer;

    @BeforeEach
    void drainConsumerBuffer() {
        consumer.drain();
    }

    @Test
    void reEmitsOnlyStaleRawPosts() throws Exception {
        UUID sourceUuid = seedRssSource();
        // status_changed_at one day old → comfortably past the base
        // stale-raw age (30m under %test); the fresh post is at now().
        UUID staleRaw = insertPost(sourceUuid, "stale", "RAW",
            Instant.parse("2026-06-01T08:00:00Z"),
            Instant.now().minusSeconds(86_400));
        UUID freshRaw = insertPost(sourceUuid, "fresh", "RAW",
            Instant.parse("2026-06-01T08:01:00Z"),
            Instant.now());
        UUID readyOld = insertPost(sourceUuid, "ready", "READY",
            Instant.parse("2026-06-01T08:02:00Z"),
            Instant.now().minusSeconds(86_400));

        worker.reEmitStaleRaw();
        awaitConsumerStable();

        Set<UUID> emitted = consumer.drain().stream()
            .map(PostPersister.PersistedPostKey::id)
            .collect(Collectors.toSet());

        assertTrue(emitted.contains(staleRaw),
            "a post stuck RAW past the age threshold must be re-enqueued");
        assertFalse(emitted.contains(freshRaw),
            "a RAW post still within the age threshold must NOT be re-enqueued");
        assertFalse(emitted.contains(readyOld),
            "a non-RAW post must never be a stale-RAW candidate");
    }

    private UUID insertPost(UUID sourceUuid, String slug, String status,
                            Instant fetchedAt, Instant statusChangedAt) throws Exception {
        final String sql =
            "INSERT INTO post ("
                + "  id, uid, source_id, upstream_identifier, url, title, body, "
                + "  author, published_at, fetched_at, status, status_changed_at, "
                + "  stage1_done, stage2_done, tagger_done, embedding_done, "
                + "  stage1_flagged, stage2_failed, tagger_fallback, tags"
                + ") VALUES ("
                + "  gen_random_uuid(), ?, ?, ?, NULL, ?, ?, NULL, NULL, ?, ?, ?, "
                + "  FALSE, FALSE, FALSE, FALSE, FALSE, FALSE, FALSE, '{}'"
                + ") RETURNING id";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "m1-295-stale-" + slug + "-uid");
            ps.setObject(2, sourceUuid);
            ps.setString(3, "m1-295-stale-" + slug + "-upstream");
            ps.setString(4, "Stale-RAW IT post " + slug);
            ps.setString(5, "An ordinary benign headline.");
            ps.setTimestamp(6, Timestamp.from(fetchedAt));
            ps.setString(7, status);
            ps.setTimestamp(8, Timestamp.from(statusChangedAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID seedRssSource() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('rss', ?, ?, 'news', '{}') "
                     + "RETURNING id")) {
            ps.setString(1, "https://m1-295-stale-it.example.test/feed.xml");
            ps.setString(2, "Stale-RAW IT source");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    /**
     * Wait for the consumer's buffer to stop changing for one polling
     * window — the heuristic that SmallRye has delivered whatever
     * {@code reEmitStaleRaw()} emitted. Bounded at 5 seconds. Mirrors
     * {@code OutboxRehydratorIT#awaitConsumerStable}.
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
