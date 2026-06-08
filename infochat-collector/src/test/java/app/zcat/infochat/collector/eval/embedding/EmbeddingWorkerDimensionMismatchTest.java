package app.zcat.infochat.collector.eval.embedding;

import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.notifier.AdminNotificationRecord;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.llm.EmbeddingProvider;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the runtime per-vector dimensionality-mismatch behavior of
 * {@link EmbeddingWorker}: a mismatch must NOT throw out of the
 * scheduled tick forever, but instead fire exactly one coalesced
 * operator alert via {@link ThrottledAdminNotifier#notifyOnce} and skip
 * the batch (M1-233). Complements {@link EmbeddingWorkerIT} test 4,
 * which pins the same contract for the single-batch entry point.
 *
 * <h2>Stub provider</h2>
 *
 * <p>Reuses {@link EmbeddingWorkerIT.StubEmbeddingProvider} — the
 * module's single {@code @Alternative @Priority(Integer.MAX_VALUE)}
 * {@link EmbeddingProvider} that ArC selects over the production
 * provider for every {@code @QuarkusTest} in this module. Injecting
 * {@link EmbeddingProvider} here yields that same stub singleton.
 *
 * <h2>Why {@code processBatch} for the coalescing assertion</h2>
 *
 * <p>{@code onTick} enumerates pending posts from the whole {@code post}
 * table, so its batch is not deterministic under a shared test DB.
 * {@code processBatch} is the exact per-tick work unit {@code onTick}
 * invokes once per tick; calling it repeatedly with the same batch
 * models repeated ticks deterministically, which is what the
 * "coalesced to one alert across repeated ticks" assertion needs. The
 * separate {@code onTick} no-throw test exercises the real scheduled
 * entry point.
 */
@QuarkusTest
class EmbeddingWorkerDimensionMismatchTest {

    /** The active embedding dimension under the test profile (V11 seed). */
    private static final int EXPECTED_DIMENSION = 768;

    /** A deliberately wrong dimension to trigger the mismatch path. */
    private static final int WRONG_DIMENSION = 384;

    /** fetched_at inside V11's bootstrap partition (May 2026). */
    private static final Instant FETCHED_AT = Instant.parse("2026-05-16T10:00:00Z");

    private static final String UID_PREFIX = "embed-dim-mismatch/";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    EmbeddingWorker embeddingWorker;

    @Inject
    EmbeddingProvider embeddingProvider;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    private EmbeddingWorkerIT.StubEmbeddingProvider stub() {
        return (EmbeddingWorkerIT.StubEmbeddingProvider) embeddingProvider;
    }

    @BeforeEach
    void reset() throws Exception {
        stub().reset();
        clearPosts();
        // Reset the coalescing counters so the exact notification_count
        // / suppressed_count assertions reflect only this test's ticks.
        clearNotification(EmbeddingWorker.ERROR_CLASS_EMBEDDING_DIMENSION_MISMATCH);
    }

    @Test
    void repeatedMismatchFiresExactlyOneCoalescedAlertAndInsertsNoEmbedding() throws Exception {
        // A batch whose embed result has the correct COUNT but the
        // WRONG per-vector dimension. The row is constructed directly
        // (not seeded): the mismatch path returns before any post /
        // post_embedding access, so no seeded row is needed.
        UUID postId = UUID.randomUUID();
        EmbeddingWorker.PostRow row = new EmbeddingWorker.PostRow(
            postId, FETCHED_AT, "mismatch-probe title", "mismatch-probe body", null);

        int ticks = 3;
        for (int i = 0; i < ticks; i++) {
            // Each tick re-picks the same wedged batch; the provider keeps
            // returning the same wrong-dimension vector.
            stub().queueSuccess(List.of(new float[WRONG_DIMENSION]));
            assertDoesNotThrow(
                () -> embeddingWorker.processBatch(List.of(row)),
                "the per-tick work unit must skip the mismatch, not throw");
        }

        assertEquals(ticks, stub().callCount(),
            "each tick issues exactly one embed call (the mismatch path does not retry)");

        Optional<AdminNotificationRecord> state = throttledAdminNotifier.getState(
            EmbeddingWorker.ERROR_CLASS_EMBEDDING_DIMENSION_MISMATCH);
        assertTrue(state.isPresent(),
            "a coalesced operator alert must fire on the dimension mismatch");
        assertEquals(1, state.get().notificationCount(),
            "the alert must coalesce to exactly one emission across repeated ticks");
        assertEquals(ticks - 1, state.get().suppressedCount(),
            "every in-window tick after the first must be suppressed, not re-emitted");

        assertEquals(0, postEmbeddingCount(postId),
            "no post_embedding row may be inserted on the mismatch skip path");
    }

    @Test
    void repeatedMismatchDoesNotPropagateExceptionOutOfOnTick() throws Exception {
        // Seed a pickup-ready post so onTick has work, then drive the
        // real scheduled entry point repeatedly. The tick must complete
        // every time — no stack-trace-per-poll loop.
        seedPickupReadyPost("ontick");
        int ticks = 3;
        for (int i = 0; i < ticks; i++) {
            stub().queueSuccess(List.of(new float[WRONG_DIMENSION]));
        }

        assertDoesNotThrow(() -> {
            for (int i = 0; i < ticks; i++) {
                embeddingWorker.onTick();
            }
        }, "a persistent dimension mismatch must not propagate out of onTick");
    }

    // ---------- helpers ----------

    private void seedPickupReadyPost(String slug) throws Exception {
        UUID sourceId = seedRssSource(slug);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body, "
                     + "  fetched_at, status, "
                     + "  stage1_done, stage2_done, tagger_done, embedding_done, "
                     + "  stage1_flagged, stage2_failed, tagger_fallback, tags"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, ?, ?, ?, 'RAW', "
                     + "  TRUE, FALSE, TRUE, FALSE, FALSE, FALSE, FALSE, '{}'"
                     + ")")) {
            ps.setString(1, UID_PREFIX + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "embed-dim-upstream-" + slug);
            ps.setString(4, "Embed dim title " + slug);
            ps.setString(5, "Embed dim body for slug " + slug);
            ps.setTimestamp(6, Timestamp.from(FETCHED_AT));
            ps.executeUpdate();
        }
    }

    private UUID seedRssSource(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('rss', ?, ?, 'news', '{ai}') "
                     + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            ps.setString(1, "https://embed-dim.example.test/" + slug + "/feed.xml");
            ps.setString(2, "Embed dim source " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void clearPosts() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM post_embedding WHERE post_id IN "
                    + "(SELECT id FROM post WHERE uid LIKE ?)")) {
                ps.setString(1, UID_PREFIX + "%");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM post WHERE uid LIKE ?")) {
                ps.setString(1, UID_PREFIX + "%");
                ps.executeUpdate();
            }
        }
    }

    private void clearNotification(String notificationKey) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM admin_notification_state WHERE notification_key = ?")) {
            ps.setString(1, notificationKey);
            ps.executeUpdate();
        }
    }

    private int postEmbeddingCount(UUID postId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(*) FROM post_embedding WHERE post_id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }
}
