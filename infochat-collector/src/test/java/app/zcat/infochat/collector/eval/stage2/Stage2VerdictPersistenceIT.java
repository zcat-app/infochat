package app.zcat.infochat.collector.eval.stage2;

import app.zcat.infochat.collector.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T25: the Stage 2 verdict write is folded into the same UPDATE that sets
 * {@code post.stage2_done}/{@code status} on the {@code (id, fetched_at)} row,
 * rather than issued as a second UPDATE on that same row. These tests pin that
 * the folded statement persists the verdict together with the done/status
 * transition for both folded paths — the BENIGN RAW-retained release and the
 * QUARANTINED verdict path. The fold is only safe because both writes target
 * the same row in the same transaction; that the verdict lands alongside the
 * status/flags is the observable proof.
 *
 * <p>Also pins the Stage-2 fail-open release path (INFRA_FAILURE under
 * release-on-stage2-failure=true, the test profile's inherited base value):
 * the post is released RAW with {@code stage2_failed=true}, and the
 * operator-visible {@code releasedStage2FailedCount()} counter increments
 * (the observability slice of M-K8 — the boot audit row says the posture is
 * armed, this counter says how often it fired).</p>
 */
@QuarkusTest
class Stage2VerdictPersistenceIT {

    private static final Instant FETCHED_AT = Instant.parse("2026-06-09T09:00:00Z");
    private static final String UID_PREFIX = "verdict-fold-it/";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    Stage2VerdictHandler stage2VerdictHandler;

    @BeforeEach
    void setup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM post WHERE uid LIKE ?", UID_PREFIX + "%");
            // No FK from quarantine to post (denormalized by design) — the
            // INJECTION test below now inserts a flagged_by='stage2' row
            // (M1-739), which must not leak into other tests' row counts.
            exec(conn, "DELETE FROM quarantine WHERE post_uid LIKE ?", UID_PREFIX + "%");
        }
    }

    @Test
    void benignVerdictFoldsVerdictWithRawReleaseUpdate() throws Exception {
        SeededPost post = seedRawPost("benign");

        stage2VerdictHandler.apply(post.id(), post.fetchedAt(),
            Stage2VerdictHandler.Verdict.BENIGN, /* judgedBody */ null);

        PostRow row = readPost(post);
        assertEquals("RAW", row.status(), "BENIGN keeps status RAW (Invariant 5)");
        assertTrue(row.stage2Done(), "stage2_done set");
        assertFalse(row.stage2Failed(), "stage2_failed stays false on a verdict");
        assertEquals("BENIGN", row.stage2Verdict(),
            "the folded UPDATE persisted the verdict alongside the done/status write");
    }

    @Test
    void injectionVerdictFoldsVerdictWithQuarantineUpdate() throws Exception {
        SeededPost post = seedRawPost("injection");

        stage2VerdictHandler.apply(post.id(), post.fetchedAt(),
            Stage2VerdictHandler.Verdict.INJECTION, "judged body for " + post.id());

        PostRow row = readPost(post);
        assertEquals("QUARANTINED", row.status(), "INJECTION quarantines the post");
        assertTrue(row.stage2Done(), "stage2_done set");
        assertFalse(row.stage2Failed(), "stage2_failed stays false on a verdict");
        assertEquals("INJECTION", row.stage2Verdict(),
            "the folded UPDATE persisted the verdict alongside the status transition");
        assertNotNull(row.statusChangedAt(), "status_changed_at advanced by the quarantine UPDATE");
    }

    @Test
    void infraFailureReleaseIncrementsReleasedCounter() throws Exception {
        SeededPost post = seedRawPost("infra-failure");

        // Delta, not absolute: the counter is a process-wide singleton other
        // tests in the same container may have bumped.
        long before = stage2VerdictHandler.releasedStage2FailedCount();
        stage2VerdictHandler.apply(post.id(), post.fetchedAt(),
            Stage2VerdictHandler.Verdict.INFRA_FAILURE, /* judgedBody */ null);

        PostRow row = readPost(post);
        // RAW + stage2_failed proves the release branch ran — i.e. the test
        // profile resolves release-on-stage2-failure=true (inherited base).
        assertEquals("RAW", row.status(),
            "fail-open release keeps status RAW (Tagger/Embedding still run)");
        assertTrue(row.stage2Failed(), "stage2_failed marks the degraded-mode release");
        assertEquals(before + 1, stage2VerdictHandler.releasedStage2FailedCount(),
            "the fail-open release path must increment the released-post counter exactly once");
    }

    // ---------- helpers ----------

    private PostRow readPost(SeededPost post) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status, stage2_done, stage2_failed, stage2_verdict, status_changed_at "
                     + "FROM post WHERE id = ? AND fetched_at = ?")) {
            ps.setObject(1, post.id());
            ps.setTimestamp(2, Timestamp.from(post.fetchedAt()));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "seeded post present");
                return new PostRow(
                    rs.getString("status"),
                    rs.getBoolean("stage2_done"),
                    rs.getBoolean("stage2_failed"),
                    rs.getString("stage2_verdict"),
                    rs.getTimestamp("status_changed_at"));
            }
        }
    }

    private SeededPost seedRawPost(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID sourceId = seedRssSource(conn, slug);
            String uid = UID_PREFIX + slug;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO post (uid, source_id, upstream_identifier, title, body, "
                        + "fetched_at, status, stage1_done, stage1_flagged, tags) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'RAW', TRUE, FALSE, '{}') "
                        + "RETURNING id, fetched_at")) {
                ps.setString(1, uid);
                ps.setObject(2, sourceId);
                ps.setString(3, "verdict-fold-upstream-" + slug);
                ps.setString(4, "Verdict fold IT " + slug);
                ps.setString(5, "Verdict fold IT body " + slug);
                ps.setTimestamp(6, Timestamp.from(FETCHED_AT));
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    return new SeededPost((UUID) rs.getObject(1), rs.getTimestamp(2).toInstant());
                }
            }
        }
    }

    private UUID seedRssSource(Connection conn, String slug) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                    + "VALUES ('rss', ?, ?, 'news', '{ai}') "
                    + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                    + "RETURNING id")) {
            ps.setString(1, "https://verdict-fold-it.example.test/" + slug + "/feed.xml");
            ps.setString(2, "Verdict fold IT source " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return (UUID) rs.getObject(1);
            }
        }
    }

    private static void exec(Connection conn, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private record SeededPost(UUID id, Instant fetchedAt) {
    }

    private record PostRow(String status, boolean stage2Done, boolean stage2Failed,
                           @Nullable String stage2Verdict, @Nullable Timestamp statusChangedAt) {
    }
}
