package app.zcat.infochat.collector.eval.embedding;

import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.notifier.AdminNotificationRecord;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.llm.EmbeddingProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
 * Pins the non-finite vector component behavior of
 * {@link EmbeddingWorker} (M1-327, deep-review v5.5 collector F1): a
 * returned vector whose length matches the configured dimension but
 * which carries a {@code Float.NaN} or {@code ±Infinity} component must
 * NOT reach {@link EmbeddingWorker#formatVector} and throw
 * {@code SQLException} out of the {@code ?::vector} cast forever. Instead
 * the worker fires exactly one coalesced operator alert via
 * {@link ThrottledAdminNotifier#notifyOnce} and skips the batch BEFORE
 * any INSERT/UPDATE, leaving the post {@code embedding_done=FALSE} so a
 * later finite-vector tick auto-recovers it.
 *
 * <p>Mirrors {@link EmbeddingWorkerDimensionMismatchTest} — same stub
 * provider, same {@code processBatch} entry point for determinism (see
 * that class's "Why processBatch" note), same seed/clear helpers — for
 * the parallel non-finite guard rather than the dimension-mismatch guard.
 */
@QuarkusTest
class EmbeddingWorkerNonFiniteTest {

    /** The active embedding dimension under the test profile (V11 seed). */
    private static final int EXPECTED_DIMENSION = 768;

    /** fetched_at inside V11's bootstrap partition (May 2026). */
    private static final Instant FETCHED_AT = Instant.parse("2026-05-16T10:00:00Z");

    private static final String UID_PREFIX = "embed-nonfinite/";

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
        // Reset the coalescing counter so the notification_count assertion
        // reflects only this test's ticks.
        clearNotification(EmbeddingWorker.ERROR_CLASS_EMBEDDING_NONFINITE);
    }

    /**
     * Each of the three non-finite IEEE-754 values pgvector rejects must
     * be caught by the guard: the batch is skipped via one coalesced
     * alert, no embed retry fires, no row is written, and
     * {@code embedding_done} stays FALSE. The row is hand-built (not
     * seeded): the guard returns before any post / post_embedding access.
     */
    @ParameterizedTest
    @ValueSource(floats = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
    void nonFiniteComponentAlertsOperatorAndSkipsWithoutThrowing(float nonFinite) throws Exception {
        UUID postId = UUID.randomUUID();
        EmbeddingWorker.PostRow row = new EmbeddingWorker.PostRow(
            postId, FETCHED_AT, "nonfinite-probe title", "nonfinite-probe body", null);

        stub().queueSuccess(List.of(vectorWithComponent(EXPECTED_DIMENSION, 0, nonFinite)));

        assertDoesNotThrow(
            () -> embeddingWorker.processBatch(List.of(row)),
            "a non-finite component must be skipped via a coalesced alert, not throw");

        assertEquals(1, stub().callCount(),
            "the non-finite skip path must NOT retry (it is not a batch-failure case)");

        Optional<AdminNotificationRecord> state = throttledAdminNotifier.getState(
            EmbeddingWorker.ERROR_CLASS_EMBEDDING_NONFINITE);
        assertTrue(state.isPresent(),
            "a coalesced operator alert must fire on the non-finite component");
        assertEquals(1, state.get().notificationCount(),
            "exactly one notifyOnce emission for a single non-finite tick");

        assertEquals(0, postEmbeddingCount(postId),
            "no post_embedding row may be inserted on the non-finite skip path");
    }

    /**
     * The wedge fix end-to-end: a NaN-bearing vector skips with one
     * alert and no write, then a subsequent tick with an all-finite
     * vector completes the embedding (the idempotent pickup auto-recovers
     * once the provider output normalizes).
     */
    @Test
    void nanComponentSkipsThenFiniteVectorAutoRecovers() throws Exception {
        SeededPost post = seedPickupReadyPost("recover");
        EmbeddingWorker.PostRow row = rowFor(post);

        // Tick 1: provider returns a right-length vector with one NaN
        // component. The worker skips before any write.
        stub().queueSuccess(List.of(vectorWithComponent(EXPECTED_DIMENSION, 0, Float.NaN)));
        embeddingWorker.processBatch(List.of(row));

        Optional<AdminNotificationRecord> state = throttledAdminNotifier.getState(
            EmbeddingWorker.ERROR_CLASS_EMBEDDING_NONFINITE);
        assertTrue(state.isPresent() && state.get().notificationCount() == 1,
            "the NaN tick must fire exactly one coalesced operator alert");
        assertEmbeddingDone(post.id, false);
        assertEquals(0, postEmbeddingCount(post.id),
            "the NaN tick must write no post_embedding row");

        // Tick 2: provider output has normalized to an all-finite vector.
        // The same batch is re-processed and the embedding completes.
        stub().queueSuccess(List.of(finiteVector(EXPECTED_DIMENSION)));
        embeddingWorker.processBatch(List.of(row));

        assertEmbeddingDone(post.id, true);
        assertEquals(1, postEmbeddingCount(post.id),
            "the finite recovery tick must insert exactly one post_embedding row");
    }

    /**
     * Companion happy path: an all-finite vector is unaffected by the new
     * guard — the row is inserted and {@code embedding_done} advances.
     */
    @Test
    void allFiniteHappyPathIsUnchanged() throws Exception {
        SeededPost post = seedPickupReadyPost("happy");

        stub().queueSuccess(List.of(finiteVector(EXPECTED_DIMENSION)));
        embeddingWorker.processBatch(List.of(rowFor(post)));

        assertEmbeddingDone(post.id, true);
        assertEquals(1, postEmbeddingCount(post.id),
            "an all-finite vector must insert exactly one post_embedding row");
        Optional<AdminNotificationRecord> state = throttledAdminNotifier.getState(
            EmbeddingWorker.ERROR_CLASS_EMBEDDING_NONFINITE);
        assertTrue(state.isEmpty(),
            "no non-finite alert may fire on the all-finite happy path");
    }

    // ---------- helpers ----------

    /** A finite vector with distinct non-zero components (1, 1/2, 1/3, ...). */
    private static float[] finiteVector(int dimension) {
        float[] v = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            v[i] = 1.0f / (i + 1);
        }
        return v;
    }

    /** An otherwise-finite vector with {@code value} planted at {@code index}. */
    private static float[] vectorWithComponent(int dimension, int index, float value) {
        float[] v = finiteVector(dimension);
        v[index] = value;
        return v;
    }

    private EmbeddingWorker.PostRow rowFor(SeededPost post) {
        return new EmbeddingWorker.PostRow(
            post.id, post.fetchedAt, "nonfinite title " + post.uid, "nonfinite body " + post.uid, null);
    }

    private SeededPost seedPickupReadyPost(String slug) throws Exception {
        UUID sourceId = seedRssSource(slug);
        String uid = UID_PREFIX + slug;
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
                     + ") RETURNING id, fetched_at")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, "embed-nonfinite-upstream-" + slug);
            ps.setString(4, "Embed nonfinite title " + slug);
            ps.setString(5, "Embed nonfinite body for slug " + slug);
            ps.setTimestamp(6, Timestamp.from(FETCHED_AT));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "INSERT INTO post must yield an id");
                UUID id = (UUID) rs.getObject(1);
                Instant fetchedAt = rs.getTimestamp(2).toInstant();
                return new SeededPost(id, uid, fetchedAt);
            }
        }
    }

    private UUID seedRssSource(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('rss', ?, ?, 'news', '{ai}') "
                     + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            ps.setString(1, "https://embed-nonfinite.example.test/" + slug + "/feed.xml");
            ps.setString(2, "Embed nonfinite source " + slug);
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

    private void assertEmbeddingDone(UUID postId, boolean expected) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT embedding_done FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "post row must exist");
                assertEquals(expected, rs.getBoolean("embedding_done"), "embedding_done");
            }
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

    private record SeededPost(UUID id, String uid, Instant fetchedAt) {
    }
}
