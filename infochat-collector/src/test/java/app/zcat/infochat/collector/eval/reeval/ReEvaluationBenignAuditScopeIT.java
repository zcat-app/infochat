package app.zcat.infochat.collector.eval.reeval;

import app.zcat.infochat.collector.eval.testing.StubLlmProvider;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.llm.LlmProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The RE_EVAL_RELEASED audit + admin page are scoped to a genuine
 * hidden→visible release: a BENIGN re-eval of a post that was HELD
 * (status='QUARANTINED') at re-eval time (docs/spec/security.md
 * §Re-evaluation job). M1-482 re-scoped this from the post class to the
 * pre-transition visibility, so the audit follows the actual exposure:
 *
 * <ul>
 *   <li>An already-visible (READY) infra-failure post → NO audit, NO page
 *       (clearing the Stage-2-failure flag is not a release).</li>
 *   <li>A QUARANTINED infra-failure post (release-on-stage2-failure=false,
 *       or re-hidden by a prior non-BENIGN roll) → audit AND page — a real
 *       hidden→visible release the class-based scope would have dropped.</li>
 *   <li>The audit row's {@code target_id} carries the stable post UID
 *       ({@code post.uid}), never the internal {@code post.id} surrogate.</li>
 * </ul>
 *
 * <p>The re-eval scheduler is disabled in tests
 * ({@code infochat.reeval.poll-interval=off}), so {@code processOne} is the
 * sole writer of the {@code re-eval-released} notifier key during a method —
 * the page-absence assertion is deterministic. Each post's audit rows are
 * isolated by its unique UID.</p>
 */
@QuarkusTest
class ReEvaluationBenignAuditScopeIT {

    private static final Instant FETCHED_AT = Instant.parse("2026-06-08T10:00:00Z");
    private static final String UID_PREFIX = "reeval-audit-scope-it/";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    ReEvaluationJob reEvaluationJob;

    @Inject
    LlmProvider llmProvider;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    private StubLlmProvider stub() {
        return (StubLlmProvider) llmProvider;
    }

    @BeforeEach
    void setup() throws Exception {
        stub().reset();
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM quarantine WHERE post_uid LIKE ?", UID_PREFIX + "%");
            exec(conn, "DELETE FROM post WHERE uid LIKE ?", UID_PREFIX + "%");
            // Reset the coalesced release-page counter so a page fired by an
            // earlier method (or test class) cannot make this method's
            // absence assertion read a stale row.
            exec(conn, "DELETE FROM admin_notification_state WHERE notification_key = ?",
                ReEvaluationJob.ERROR_CLASS_REEVAL_RELEASED);
        }
    }

    @Test
    void readyInfraFailureBenignReEval_writesNoReleaseAudit_andNoPage() throws Exception {
        // status=READY: the post is already user-visible with its Stage 1
        // redactions. A BENIGN re-eval clears stage2_failed but exposes
        // nothing new — not a release, so no audit and no admin page.
        stub().setNextResponse("BENIGN");
        SeededPost post = seedInfraFailurePost("ready-no-audit", "READY", /* readyAt */ true);
        seedPendingQuarantineRow(post, "ph-ready-no-audit");

        reEvaluationJob.processOne(candidateFor(post, /* stage2Failed */ true, /* verdict */ null));

        assertPostField(post.id, "stage2_failed", false);
        assertReleaseAuditCountForUid(post.uid, 0);
        assertNoReleasePage();
    }

    @Test
    void quarantinedInfraFailureBenignReEval_writesReleaseAudit_andPages() throws Exception {
        // status=QUARANTINED: the post was held (release-on-stage2-failure=
        // false, or re-hidden by a prior non-BENIGN roll). A BENIGN re-eval
        // requeues it RAW→…→READY — a genuine hidden→visible release, so the
        // audit row is written and the admin is paged.
        stub().setNextResponse("BENIGN");
        SeededPost post = seedInfraFailurePost("quarantined-audit", "QUARANTINED", /* readyAt */ false);
        seedPendingQuarantineRow(post, "ph-quarantined-audit");

        reEvaluationJob.processOne(candidateFor(post, /* stage2Failed */ true, /* verdict */ null));

        assertPostStatus(post.id, "RAW");
        assertPostField(post.id, "stage2_failed", false);
        assertReleaseAuditCountForUid(post.uid, 1);
        assertTrue(throttledAdminNotifier.getState(ReEvaluationJob.ERROR_CLASS_REEVAL_RELEASED).isPresent(),
            "a QUARANTINED→released infra-failure BENIGN re-eval must page the admin");
    }

    @Test
    void unknownBenignReEval_auditTargetIdEqualsPostUid() throws Exception {
        // The UNKNOWN→BENIGN release audit's target_id is the stable post UID,
        // never the internal post.id surrogate (acceptance item 3b).
        stub().setNextResponse("BENIGN");
        SeededPost post = seedUnknownQuarantinedPost("unknown-uid");
        seedPendingQuarantineRow(post, "ph-unknown-uid");

        reEvaluationJob.processOne(candidateFor(post, /* stage2Failed */ false, /* verdict */ "UNKNOWN"));

        assertReleaseAuditCountForUid(post.uid, 1);
        assertNoReleaseAuditForTarget(post.id.toString());
    }

    // ---------- helpers ----------

    private ReEvaluationJob.ReEvalCandidate candidateFor(SeededPost post, boolean stage2Failed,
                                                         @Nullable String verdict) throws Exception {
        return new ReEvaluationJob.ReEvalCandidate(post.id, post.uid, post.fetchedAt, stage2Failed, 0,
            verdict, currentBody(post));
    }

    private @Nullable String currentBody(SeededPost post) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT body FROM post WHERE id = ? AND fetched_at = ?")) {
            ps.setObject(1, post.id);
            ps.setTimestamp(2, Timestamp.from(post.fetchedAt));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private SeededPost seedInfraFailurePost(String slug, String status, boolean readyAt) throws Exception {
        return seedPost(slug, status, /* stage2Failed */ true, readyAt, /* stage2Verdict */ null);
    }

    private SeededPost seedUnknownQuarantinedPost(String slug) throws Exception {
        return seedPost(slug, "QUARANTINED", /* stage2Failed */ false, /* readyAt */ false,
            /* stage2Verdict */ "UNKNOWN");
    }

    private SeededPost seedPost(String slug, String status, boolean stage2Failed, boolean readyAt,
                                @Nullable String stage2Verdict) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            UUID sourceId = seedRssSource(conn, slug);
            String uid = UID_PREFIX + slug;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO post ("
                        + "  uid, source_id, upstream_identifier, title, body,"
                        + "  fetched_at, status, status_changed_at, ready_at,"
                        + "  stage1_done, stage1_flagged, stage2_done, stage2_failed,"
                        + "  tagger_done, tagger_fallback, entity_done, embedding_done,"
                        + "  tags, re_eval_attempts, stage2_verdict"
                        + ") VALUES ("
                        + "  ?, ?, ?, ?, ?,"
                        + "  ?, ?, now(), " + (readyAt ? "now()" : "NULL") + ","
                        + "  TRUE, TRUE, TRUE, ?,"
                        + "  TRUE, FALSE, TRUE, TRUE,"
                        + "  '{ai}'::text[], 0, ?"
                        + ") RETURNING id, fetched_at")) {
                ps.setString(1, uid);
                ps.setObject(2, sourceId);
                ps.setString(3, "reeval-audit-scope-upstream-" + slug);
                ps.setString(4, "ReEval audit scope IT " + slug);
                ps.setString(5, "Body with [REDACTED:ph-" + slug + "] here");
                ps.setTimestamp(6, Timestamp.from(FETCHED_AT));
                ps.setString(7, status);
                ps.setBoolean(8, stage2Failed);
                ps.setString(9, stage2Verdict);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    return new SeededPost((UUID) rs.getObject(1), uid, rs.getTimestamp(2).toInstant());
                }
            }
        }
    }

    private void seedPendingQuarantineRow(SeededPost post, String placeholderId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO quarantine ("
                     + "  id, post_id, post_uid, post_fetched_at, flagged_at, flagged_by,"
                     + "  rule_id, placeholder_id, original_html, status"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, now(), 'stage1',"
                     + "  'regex-test', ?, '<b>span</b>', 'PENDING'"
                     + ")")) {
            ps.setObject(1, post.id);
            ps.setString(2, post.uid);
            ps.setTimestamp(3, Timestamp.from(post.fetchedAt));
            ps.setString(4, placeholderId);
            ps.executeUpdate();
        }
    }

    private UUID seedRssSource(Connection conn, String slug) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                    + "VALUES ('rss', ?, ?, 'news', '{ai}') "
                    + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                    + "RETURNING id")) {
            ps.setString(1, "https://reeval-audit-scope-it.example.test/" + slug + "/feed.xml");
            ps.setString(2, "ReEval audit scope IT source " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void assertReleaseAuditCountForUid(String postUid, int expected) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(*) FROM audit_log "
                     + "WHERE target_id = ? AND action = 'RE_EVAL_RELEASED'")) {
            ps.setString(1, postUid);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expected, rs.getInt(1),
                    "RE_EVAL_RELEASED rows for post_uid " + postUid);
            }
        }
    }

    private void assertNoReleaseAuditForTarget(String targetId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT 1 FROM audit_log WHERE target_id = ? AND action = 'RE_EVAL_RELEASED'")) {
            ps.setString(1, targetId);
            try (ResultSet rs = ps.executeQuery()) {
                assertFalse(rs.next(),
                    "no RE_EVAL_RELEASED row may carry the internal post.id as target_id: " + targetId);
            }
        }
    }

    private void assertNoReleasePage() {
        assertTrue(throttledAdminNotifier.getState(ReEvaluationJob.ERROR_CLASS_REEVAL_RELEASED).isEmpty(),
            "a non-held (already-visible) infra-failure BENIGN re-eval must not page the admin");
    }

    private void assertPostStatus(UUID postId, String expected) throws Exception {
        assertPostField(postId, "status", expected);
    }

    private void assertPostField(UUID postId, String field, Object expected) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT " + field + " FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expected, rs.getObject(1), "post." + field);
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

    private record SeededPost(UUID id, String uid, Instant fetchedAt) {
    }
}
