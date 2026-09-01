package app.zcat.infochat.collector.eval.reeval;

import app.zcat.infochat.collector.eval.stage2.Stage2VerdictHandler;
import app.zcat.infochat.collector.eval.testing.StubLlmProvider;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.llm.LlmProvider;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-739 acceptance: the UNKNOWN-first-pass interplay. A first-pass
 * UNKNOWN verdict on a row-less post inserts the
 * {@code flagged_by='stage2'} PENDING row (covered row-shape-side by
 * {@code Stage2FirstPassQuarantineRowIT}); a later re-eval that rolls
 * BENIGN must close that row via M1-738's widened
 * {@code ReEvaluationJob.closeQuarantineRows}
 * ({@code flagged_by IN ('stage1','stage2')}) — otherwise the admin
 * queue keeps asserting "awaiting review" about a post the release
 * just made visible. This test drives the REAL handler insert and the
 * REAL job close end to end; the job's re-hide path itself stays
 * untouched (M1-738 owns it).
 */
@QuarkusTest
class FirstPassStage2RowBenignCloseIT {

    private static final Instant FETCHED_AT = Instant.parse("2026-06-01T11:00:00Z");
    private static final String UID = "firstpass-stage2-close-it/unknown-arc";
    private static final String BODY = "first-pass UNKNOWN arc body";
    private static final String JUDGED_BODY = "the exact body the first-pass judge saw";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    Stage2VerdictHandler stage2VerdictHandler;

    @Inject
    ReEvaluationJob reEvaluationJob;

    @Inject
    LlmProvider llmProvider;

    @BeforeEach
    void setup() throws Exception {
        ((StubLlmProvider) llmProvider).reset();
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM post WHERE uid = ?", UID);
            // No FK from quarantine to post (denormalized by design).
            exec(conn, "DELETE FROM quarantine WHERE post_uid = ?", UID);
        }
    }

    @Test
    void firstPassUnknownRowClosesOnLaterBenignReEval() throws Exception {
        SeededPost post = seedRawPost();

        // First pass: UNKNOWN on a row-less post — the M1-739 insert fires.
        stage2VerdictHandler.apply(post.id(), post.fetchedAt(),
            Stage2VerdictHandler.Verdict.UNKNOWN, JUDGED_BODY);
        assertEquals("QUARANTINED", readPostField(post.id(), "status"));
        assertEquals("UNKNOWN", readPostField(post.id(), "stage2_verdict"));
        assertEquals("PENDING", readStage2RowStatus(post.id()),
            "the first-pass insert landed the stage2 review row");

        // Later re-eval rolls BENIGN: the widened close must take the
        // stage2 row PENDING→BENIGN_CLOSED, exactly as it does for the
        // re-hide path's own rows (M1-738).
        ((StubLlmProvider) llmProvider).setNextResponse("BENIGN");
        reEvaluationJob.processOne(new ReEvaluationJob.ReEvalCandidate(
            post.id(), UID, post.fetchedAt(), /* stage2Failed */ false,
            /* reEvalAttempts */ 0, /* stage2Verdict */ "UNKNOWN", BODY));

        assertEquals("BENIGN_CLOSED", readStage2RowStatus(post.id()),
            "M1-738's widened closeQuarantineRows must close the first-pass stage2 row");
        assertEquals("RAW", readPostField(post.id(), "status"),
            "BENIGN re-eval requeues the QUARANTINED post to the release path");
    }

    // ---------- helpers ----------

    private String readPostField(UUID postId, String column) throws Exception {
        // Test-only dynamic column read; both call sites pass literals.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT " + column + " FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "seeded post present");
                return rs.getString(1);
            }
        }
    }

    private String readStage2RowStatus(UUID postId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status FROM quarantine WHERE post_id = ? AND flagged_by = 'stage2'")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the stage2 row exists");
                return rs.getString(1);
            }
        }
    }

    private SeededPost seedRawPost() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID sourceId = seedRssSource(conn);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO post (uid, source_id, upstream_identifier, title, body, "
                        + "fetched_at, status, stage1_done, stage1_flagged, tags) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'RAW', TRUE, TRUE, '{}') "
                        + "RETURNING id, fetched_at")) {
                ps.setString(1, UID);
                ps.setObject(2, sourceId);
                ps.setString(3, "firstpass-close-upstream");
                ps.setString(4, "First-pass close IT");
                ps.setString(5, BODY);
                ps.setTimestamp(6, Timestamp.from(FETCHED_AT));
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    return new SeededPost((UUID) rs.getObject(1), rs.getTimestamp(2).toInstant());
                }
            }
        }
    }

    private UUID seedRssSource(Connection conn) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                    + "VALUES ('rss', ?, ?, 'news', '{ai}') "
                    + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                    + "RETURNING id")) {
            ps.setString(1, "https://firstpass-close-it.example.test/feed.xml");
            ps.setString(2, "First-pass close IT source");
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
}
