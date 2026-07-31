package app.zcat.infochat.collector.eval.reeval;

import app.zcat.infochat.collector.eval.testing.StubLlmProvider;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.llm.LlmProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ReEvaluationJobTest {

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    ReEvaluationJob reEvaluationJob;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @Inject
    LlmProvider llmProvider;

    @ConfigProperty(name = "infochat.partitions.retention-days.post")
    int postRetentionDays;

    private StubLlmProvider stub() {
        return (StubLlmProvider) llmProvider;
    }

    @BeforeEach
    void reset() {
        stub().reset();
    }

    @Test
    void infraFailureBenign_clearsFlag_retainsRedactions_closesQuarantine() throws Exception {
        // stage2_failed=true post released with redactions, re-eval says BENIGN.
        stub().setNextResponse("BENIGN");
        SeededPost post = seedInfraFailurePost("infra-benign");
        seedQuarantineRow(post, "placeholder-1", "original content");

        reEvaluationJob.processOne(candidateFor(post, true, 0));

        assertPostField(post.id, "stage2_failed", false);
        assertPostBodyContains(post.id, "[REDACTED:placeholder-1]");
        assertQuarantineStatus(post.id, "BENIGN_CLOSED");
        // A RAW (in-pipeline, not yet user-visible) infra-failure post was never
        // withheld pending review, so its BENIGN re-eval is not a held→visible
        // release: no RE_EVAL_RELEASED audit (M1-482 visibility-based scoping).
        // The QUARANTINED→released infra-failure case still audits — pinned by
        // quarantinedInfraFailureBenign_requeuesToRaw_singleReleaseAuditRow.
        assertReEvalReleasedCount(post.id, 0);
    }

    @Test
    void benignReEval_closesStage1AndReEvalStage2Rows() throws Exception {
        // U-25, retargeted by M1-738 (test-modification authorized in the
        // ticket body §Redteam refine): the "future non-stage1 writer" the
        // stage1-only predicate guarded against now exists and IS the
        // re-evaluation job (QuarantineDao.insertReEvalRow). A BENIGN
        // re-eval reverses the judgments both machine writers recorded,
        // so it must close the post's stage1 AND stage2 PENDING rows —
        // a stage2 row left PENDING would keep the admin queue asserting
        // "awaiting review" about the post this release just made visible.
        stub().setNextResponse("BENIGN");
        SeededPost post = seedInfraFailurePost("u25-predicate");
        seedQuarantineRow(post, "placeholder-1", "original content"); // flagged_by='stage1'
        seedQuarantineRowFlaggedBy(post, "ph-other", "other original", "stage2");

        reEvaluationJob.processOne(candidateFor(post, true, 0));

        assertEquals("BENIGN_CLOSED", readQuarantineStatusByFlaggedBy(post.id, "stage1"),
            "the Stage 1 quarantine row must close on a BENIGN re-eval");
        assertEquals("BENIGN_CLOSED", readQuarantineStatusByFlaggedBy(post.id, "stage2"),
            "the re-eval job's own stage2 row must close on the job's BENIGN release (M1-738)");
    }

    @Test
    void reHiddenPostBenignRelease_closesReEvalStage2Row() throws Exception {
        // M1-738 end-to-end lifecycle: a row-less READY post is re-hidden
        // (stage2 row inserted), then a later re-eval rolls BENIGN — the
        // post auto-releases (QUARANTINED→RAW, RE_EVAL_RELEASED audited)
        // AND the inserted row transitions PENDING→BENIGN_CLOSED with its
        // NOTIFY, so it drops out of the PENDING admin queue.
        SeededPost post = seedInfraFailurePost("rehide-then-benign");
        setPostBody(post, "released during the outage, re-judged twice");
        setPostStatus(post, "READY");

        try (Connection listenConn = dataSource.getConnection()) {
            PGConnection pg = listenTo(listenConn, "quarantine_review");

            stub().setNextResponse("INJECTION");
            reEvaluationJob.processOne(candidateFor(post, true, 0));
            assertPostStatus(post.id, "QUARANTINED");
            UUID quarantineId = readSoleQuarantineId(post.id);
            assertQuarantineReviewNotify(pg, "quarantine", quarantineId, "PENDING");

            // Roll 2 mirrors what enumerateCandidates now reads: verdict
            // recorded, counter incremented, stage2_failed preserved.
            stub().setNextResponse("BENIGN");
            reEvaluationJob.processOne(candidateFor(post, true, 1, "INJECTION"));

            assertPostStatus(post.id, "RAW");
            assertEquals("BENIGN_CLOSED", readQuarantineStatusByFlaggedBy(post.id, "stage2"),
                "the inserted stage2 row must close when the re-hidden post releases");
            assertReEvalReleasedCount(post.id, 1);
            assertQuarantineReviewNotify(pg, "quarantine", quarantineId, "BENIGN_CLOSED");
        }
    }

    @Test
    void infraFailureCapExhaustion_transitionsToNeedsReview() throws Exception {
        // Infra-failure post at cap → NEEDS_REVIEW.
        SeededPost post = seedInfraFailurePost("infra-cap");
        setReEvalAttempts(post.id, post.fetchedAt, 3);
        ReEvaluationJob.ReEvalCandidate candidate = candidateFor(post, true, 3);

        try (Connection listenConn = dataSource.getConnection()) {
            PGConnection pg = listenTo(listenConn, "quarantine_review");

            reEvaluationJob.processOne(candidate);

            assertPostStatus(post.id, "NEEDS_REVIEW");
            assertQuarantineReviewNotify(pg, "post", post.id, "NEEDS_REVIEW");
        }
    }

    @Test
    void unknownBenign_requeuesToRawForPipeline_closesQuarantine() throws Exception {
        // UNKNOWN-verdict QUARANTINED post, re-eval says BENIGN → requeued
        // RAW so the tagger/entity/embedding workers and ReadyPromoter
        // finish the release (a direct READY flip would orphan tags and
        // the new_post NOTIFY). The full READY-with-tags consequence is
        // pinned in ReEvalVerdictNotifyIT.
        stub().setNextResponse("BENIGN");
        SeededPost post = seedUnknownQuarantinedPost("unknown-benign");
        seedQuarantineRow(post, "placeholder-2", "original span");

        reEvaluationJob.processOne(candidateFor(post, false, 0));

        assertPostStatus(post.id, "RAW");
        assertPostBodyContains(post.id, "[REDACTED:placeholder-2]");
        assertQuarantineStatus(post.id, "BENIGN_CLOSED");
    }

    @Test
    void unknownCapExhaustion_transitionsToNeedsReview() throws Exception {
        // UNKNOWN cap (2) is lower than infra cap (3).
        SeededPost post = seedUnknownQuarantinedPost("unknown-cap");
        setReEvalAttempts(post.id, post.fetchedAt, 2);

        reEvaluationJob.processOne(candidateFor(post, false, 2));

        assertPostStatus(post.id, "NEEDS_REVIEW");
    }

    @Test
    void reEvalNonBenign_staysQuarantined_incrementsCounter() throws Exception {
        stub().setNextResponse("INJECTION");
        SeededPost post = seedUnknownQuarantinedPost("non-benign");
        seedQuarantineRow(post, "placeholder-3", "bad content");

        reEvaluationJob.processOne(candidateFor(post, false, 0));

        assertPostStatus(post.id, "QUARANTINED");
        assertReEvalAttempts(post.id, 1);
        // "alongside the new verdict" — the re-eval verdict is recorded,
        // not just counted (docs/spec/security.md §Re-evaluation job).
        assertPostField(post.id, "stage2_verdict", "INJECTION");
    }

    @Test
    void reHideWithNoQuarantineRow_insertsPendingStage2RowVisibleInReviewView_andNotifies()
            throws Exception {
        // M1-738: a post released READY during a Stage 2 outage carries no
        // quarantine row (Stage 1 found nothing), so a re-hide to
        // QUARANTINED must INSERT one whole-body flagged_by='stage2'
        // PENDING row — quarantine_review_view projects quarantine rows
        // only, and without the row the hidden post never enters the
        // /quarantine list admin queue. The insert emits the same
        // quarantine_review PENDING NOTIFY the Stage 1 insert emits.
        stub().setNextResponse("INJECTION");
        SeededPost post = seedInfraFailurePost("rehide-no-row");
        String body = "fully visible body the judge re-judged";
        setPostBody(post, body);
        setPostStatus(post, "READY");

        try (Connection listenConn = dataSource.getConnection()) {
            PGConnection pg = listenTo(listenConn, "quarantine_review");

            reEvaluationJob.processOne(candidateFor(post, true, 0));

            assertPostStatus(post.id, "QUARANTINED");
            assertEquals(1, countQuarantineRows(post.id),
                "the re-hide must insert exactly one quarantine row");
            // Read through the redacted Provider view — the surface
            // /quarantine list actually queries — so the assertion pins
            // queue membership, not just the base-table write.
            UUID quarantineId = assertReviewViewRow(post.id, body.length());
            assertOriginalHtmlAndPlaceholder(quarantineId, body);
            assertQuarantineReviewNotify(pg, "quarantine", quarantineId, "PENDING");
        }
    }

    @Test
    void reHideWithPendingRow_bumpsAndReannounces_insertsNoDuplicate() throws Exception {
        // M1-738: the open-row path is unchanged — a re-hide with an
        // existing PENDING quarantine row keeps the updated_at bump +
        // PENDING re-announce and inserts NO duplicate row.
        stub().setNextResponse("INJECTION");
        SeededPost post = seedInfraFailurePost("rehide-existing-row");
        seedQuarantineRow(post, "placeholder-1", "original content");
        setPostStatus(post, "READY");
        UUID existingId = readSoleQuarantineId(post.id);
        ageQuarantineRows(post.id);

        try (Connection listenConn = dataSource.getConnection()) {
            PGConnection pg = listenTo(listenConn, "quarantine_review");

            reEvaluationJob.processOne(candidateFor(post, true, 0));

            assertPostStatus(post.id, "QUARANTINED");
            assertEquals(1, countQuarantineRows(post.id),
                "the re-hide must NOT insert a duplicate row when a PENDING row exists");
            assertEquals(existingId, readSoleQuarantineId(post.id));
            assertEquals("PENDING", readQuarantineStatusByFlaggedBy(post.id, "stage1"),
                "the existing Stage 1 row stays PENDING (bumped, not replaced)");
            assertQuarantineUpdatedAtAdvanced(post.id);
            assertQuarantineReviewNotify(pg, "quarantine", existingId, "PENDING");
        }
    }

    @Test
    void infraFailureOnReEval_doesNotConsumeAttempt() throws Exception {
        // INFRA_FAILURE is transient — must NOT increment the counter.
        stub().failAll();
        SeededPost post = seedInfraFailurePost("infra-no-consume");
        seedQuarantineRow(post, "placeholder-infra", "original");

        reEvaluationJob.processOne(candidateFor(post, true, 0));

        assertReEvalAttempts(post.id, 0);
    }

    @Test
    void unknownEntryReleaseAfterInterimInjectionRoll_recordsPriorVerdictInjection() throws Exception {
        // UNKNOWN-entry post; an interim roll records INJECTION, the
        // next roll releases BENIGN. RE_EVAL_RELEASED must carry the
        // recorded stage2_verdict ('INJECTION' — the hostile-flip
        // signal), not the post's entry class
        // (docs/spec/security.md §Re-evaluation job).
        SeededPost post = seedUnknownQuarantinedPost("prior-verdict");
        seedQuarantineRow(post, "placeholder-prior", "original span");

        stub().setNextResponse("INJECTION");
        reEvaluationJob.processOne(candidateFor(post, false, 0, "UNKNOWN"));
        assertPostField(post.id, "stage2_verdict", "INJECTION");

        stub().setNextResponse("BENIGN");
        // The second roll's candidate mirrors what enumerateCandidates
        // now reads: the overwritten verdict and the incremented counter.
        reEvaluationJob.processOne(candidateFor(post, false, 1, "INJECTION"));

        assertPostStatus(post.id, "RAW");
        assertReEvalReleasedPriorVerdict(post.id, "INJECTION");
    }

    @Test
    void quarantinedInfraFailureBenign_requeuesToRaw_singleReleaseAuditRow() throws Exception {
        // A QUARANTINED infra-failure post (release-on-stage2-failure=
        // false shape, or re-hidden by a prior non-BENIGN roll)
        // re-evaluated BENIGN requeues through the normal pipeline —
        // RAW, flag cleared, quarantine closed — with exactly ONE
        // RE_EVAL_RELEASED row for the whole flow. The old
        // clear-flag-only behavior left the post QUARANTINED while the
        // audit row reported a release that never happened.
        stub().setNextResponse("BENIGN");
        SeededPost post = seedInfraFailurePost("infra-requeue");
        setPostStatus(post, "QUARANTINED");
        seedQuarantineRow(post, "placeholder-requeue", "original");

        reEvaluationJob.processOne(candidateFor(post, true, 0));

        assertPostStatus(post.id, "RAW");
        assertPostField(post.id, "stage2_failed", false);
        assertQuarantineStatus(post.id, "BENIGN_CLOSED");
        assertReEvalReleasedCount(post.id, 1);
    }

    @Test
    void needsReviewDepthAlert_firesWhenQueueExceedsThreshold() throws Exception {
        // Seed posts exceeding threshold (5). fetched_at = now(): the depth
        // count is bounded by the retention+slack scan window, so the rows
        // must sit inside it (a fixed past date would rot out of the window
        // as the wall clock advances).
        for (int i = 0; i < 6; i++) {
            seedNeedsReviewPost("depth-" + i, Instant.now());
        }

        reEvaluationJob.checkNeedsReviewDepth();

        assertAdminNotification(ReEvaluationJob.ERROR_CLASS_NEEDS_REVIEW_DEPTH);
    }

    @Test
    void needsReviewDepthCount_excludesRowsOlderThanScanWindow() throws Exception {
        // The depth COUNT carries the same fetched_at floor as the candidate
        // scan so partition pruning applies; rows below the floor stop
        // counting toward the alert. Oldest bootstrap partition (May 2026)
        // is always below the ~32-day floor, same fixture convention as
        // ReEvaluationJobWindowTest.
        Instant belowFloor = Instant.parse("2026-05-01T00:00:00Z");
        assertTrue(belowFloor.isBefore(Instant.now().minusSeconds((postRetentionDays + 2L) * 86400)),
            "test fixture invalid: belowFloor is inside the depth scan window");
        clearNeedsReviewPosts();
        seedNeedsReviewPost("count-in-window-a", Instant.now());
        seedNeedsReviewPost("count-in-window-b", Instant.now());
        seedNeedsReviewPost("count-below-floor", belowFloor);

        assertEquals(2, reEvaluationJob.countNeedsReviewWithinScanWindow(),
            "rows older than the retention+slack floor must be excluded; in-window rows counted");
    }

    @Test
    void reconstructOriginalBody_splicesCollidingPlaceholderLiteralByteExact() throws Exception {
        // A quarantined span whose CONTENT contains a placeholder-shaped
        // literal naming the post's other placeholder. The old global
        // String.replace loop would, when processing collide-a first,
        // substitute collide-b's original into the literal inside
        // collide-a's just-spliced content; the position-anchored splice
        // only ever substitutes at token positions of the stored body.
        SeededPost post = seedInfraFailurePost("splice-collide");
        setPostBody(post, "A [REDACTED:collide-a] B [REDACTED:collide-b] C");
        seedQuarantineRow(post, "collide-a", "x [REDACTED:collide-b] y");
        seedQuarantineRow(post, "collide-b", "z");

        String reconstructed = reEvaluationJob.reconstructOriginalBody(candidateFor(post, true, 0));

        assertEquals("A x [REDACTED:collide-b] y B z C", reconstructed,
            "spliced content must be byte-exact: a placeholder-shaped literal inside "
                + "quarantined content is user content, never a substitution site");
    }

    @Test
    void reconstructOriginalBody_usesCandidateCarriedBody_notASecondPostRead() throws Exception {
        // The DB body and the candidate-carried body diverge on purpose: if
        // reconstructOriginalBody still issued a second SELECT body FROM post
        // it would splice into the STALE DB body and yield
        // "STALE <original> STALE". Reading the candidate-carried body yields
        // "live <original> body" — proving the second post read is gone.
        SeededPost post = seedInfraFailurePost("candidate-carried-body");
        setPostBody(post, "STALE [REDACTED:m] STALE");
        seedQuarantineRow(post, "m", "<original>");

        ReEvaluationJob.ReEvalCandidate candidate = new ReEvaluationJob.ReEvalCandidate(
            post.id, readUid(post.id), post.fetchedAt, true, 0, null, "live [REDACTED:m] body");

        assertEquals("live <original> body", reEvaluationJob.reconstructOriginalBody(candidate),
            "reconstruct must splice into the candidate-carried body, not a second post read");
    }

    @Test
    void quarantineReviewNotify_emittedOnNeedsReviewTransition() throws Exception {
        SeededPost post = seedInfraFailurePost("notify-needs-review");
        setReEvalAttempts(post.id, post.fetchedAt, 3);
        ReEvaluationJob.ReEvalCandidate candidate = candidateFor(post, true, 3);

        try (Connection listenConn = dataSource.getConnection()) {
            PGConnection pg = listenTo(listenConn, "quarantine_review");

            reEvaluationJob.processOne(candidate);

            assertPostStatus(post.id, "NEEDS_REVIEW");
            assertQuarantineReviewNotify(pg, "post", post.id, "NEEDS_REVIEW");
        }
    }

    // ---------- helpers ----------

    private ReEvaluationJob.ReEvalCandidate candidateFor(SeededPost post, boolean stage2Failed,
                                                         int attempts) throws Exception {
        // Mirrors the seeded state: infra-failure posts carry no recorded
        // verdict (the judge never ran), UNKNOWN-class posts carry 'UNKNOWN'.
        return candidateFor(post, stage2Failed, attempts, stage2Failed ? null : "UNKNOWN");
    }

    private ReEvaluationJob.ReEvalCandidate candidateFor(SeededPost post, boolean stage2Failed,
                                                         int attempts,
                                                         @Nullable String stage2Verdict)
            throws Exception {
        // Carry the live post.body, mirroring how enumerateCandidates folds
        // body into the candidate scan — reconstructOriginalBody now reads
        // the body from the candidate, not from a second post read.
        return new ReEvaluationJob.ReEvalCandidate(post.id, readUid(post.id), post.fetchedAt, stage2Failed,
            attempts, stage2Verdict, currentBody(post));
    }

    private String readUid(UUID postId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT uid FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected seeded post " + postId);
                return rs.getString(1);
            }
        }
    }

    private @Nullable String currentBody(SeededPost post) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT body FROM post WHERE id = ? AND fetched_at = ?")) {
            ps.setObject(1, post.id);
            ps.setTimestamp(2, Timestamp.from(post.fetchedAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private SeededPost seedInfraFailurePost(String slug) throws Exception {
        UUID sourceId = seedSource(slug);
        Instant fetchedAt = Instant.parse("2026-05-20T10:00:00Z");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body,"
                     + "  fetched_at, status,"
                     + "  stage1_done, stage1_flagged, stage2_done, stage2_failed,"
                     + "  tagger_done, tagger_fallback, embedding_done, tags, re_eval_attempts"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, ?, ?,"
                     + "  ?, 'RAW',"
                     + "  TRUE, TRUE, TRUE, TRUE,"
                     + "  FALSE, FALSE, FALSE, '{}', 0"
                     + ") RETURNING id, fetched_at")) {
            ps.setString(1, "reeval-" + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "upstream-" + slug);
            ps.setString(4, "Post " + slug);
            ps.setString(5, "Body with [REDACTED:placeholder-1] here");
            ps.setTimestamp(6, Timestamp.from(fetchedAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new SeededPost((UUID) rs.getObject(1), rs.getTimestamp(2).toInstant());
            }
        }
    }

    private SeededPost seedUnknownQuarantinedPost(String slug) throws Exception {
        UUID sourceId = seedSource(slug);
        Instant fetchedAt = Instant.parse("2026-05-20T11:00:00Z");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body,"
                     + "  fetched_at, status, status_changed_at,"
                     + "  stage1_done, stage1_flagged, stage2_done, stage2_failed,"
                     + "  tagger_done, tagger_fallback, embedding_done, tags, re_eval_attempts,"
                     + "  stage2_verdict"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, ?, ?,"
                     + "  ?, 'QUARANTINED', now(),"
                     + "  TRUE, TRUE, TRUE, FALSE,"
                     + "  FALSE, FALSE, FALSE, '{}', 0,"
                     + "  'UNKNOWN'"
                     + ") RETURNING id, fetched_at")) {
            ps.setString(1, "reeval-" + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "upstream-" + slug);
            ps.setString(4, "Post " + slug);
            ps.setString(5, "Body with [REDACTED:placeholder-2] here");
            ps.setTimestamp(6, Timestamp.from(fetchedAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new SeededPost((UUID) rs.getObject(1), rs.getTimestamp(2).toInstant());
            }
        }
    }

    private void seedNeedsReviewPost(String slug, Instant fetchedAt) throws Exception {
        UUID sourceId = seedSource(slug);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body,"
                     + "  fetched_at, status,"
                     + "  stage1_done, stage1_flagged, stage2_done, stage2_failed,"
                     + "  tagger_done, tagger_fallback, embedding_done, tags, re_eval_attempts"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, ?, 'body',"
                     + "  ?, 'NEEDS_REVIEW',"
                     + "  TRUE, TRUE, TRUE, FALSE,"
                     + "  FALSE, FALSE, FALSE, '{}', 0"
                     + ")")) {
            ps.setString(1, "needs-review-" + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "upstream-nr-" + slug);
            ps.setString(4, "NR Post " + slug);
            ps.setTimestamp(5, Timestamp.from(fetchedAt));
            ps.executeUpdate();
        }
    }

    private void seedQuarantineRow(SeededPost post, String placeholderId, String originalHtml)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO quarantine ("
                     + "  id, post_id, post_uid, post_fetched_at, flagged_at, flagged_by,"
                     + "  rule_id, placeholder_id, original_html, status"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, 'uid', ?, now(), 'stage1',"
                     + "  'regex-test', ?, ?, 'PENDING'"
                     + ")")) {
            ps.setObject(1, post.id);
            ps.setTimestamp(2, Timestamp.from(post.fetchedAt));
            ps.setString(3, placeholderId);
            ps.setString(4, originalHtml);
            ps.executeUpdate();
        }
    }

    /** Seed a PENDING quarantine row with a caller-supplied flagged_by. */
    private void seedQuarantineRowFlaggedBy(SeededPost post, String placeholderId,
                                            String originalHtml, String flaggedBy) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO quarantine ("
                     + "  id, post_id, post_uid, post_fetched_at, flagged_at, flagged_by,"
                     + "  rule_id, placeholder_id, original_html, status"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, 'uid', ?, now(), ?,"
                     + "  'regex-test', ?, ?, 'PENDING'"
                     + ")")) {
            ps.setObject(1, post.id);
            ps.setTimestamp(2, Timestamp.from(post.fetchedAt));
            ps.setString(3, flaggedBy);
            ps.setString(4, placeholderId);
            ps.setString(5, originalHtml);
            ps.executeUpdate();
        }
    }

    private String readQuarantineStatusByFlaggedBy(UUID postId, String flaggedBy) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status FROM quarantine WHERE post_id = ? AND flagged_by = ?")) {
            ps.setObject(1, postId);
            ps.setString(2, flaggedBy);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected a quarantine row with flagged_by=" + flaggedBy);
                return rs.getString(1);
            }
        }
    }

    private int countQuarantineRows(UUID postId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(*) FROM quarantine WHERE post_id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private UUID readSoleQuarantineId(UUID postId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id FROM quarantine WHERE post_id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected a quarantine row for post " + postId);
                return (UUID) rs.getObject(1);
            }
        }
    }

    // The re-announce bump stamp (the injected Clock's "now") must beat a
    // known old updated_at; aging flagged_at too would make the row a TTL
    // candidate, so only updated_at is moved.
    private static final Timestamp AGED_UPDATED_AT = Timestamp.from(Instant.parse("2026-05-20T10:00:00Z"));

    private void ageQuarantineRows(UUID postId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE quarantine SET updated_at = ? WHERE post_id = ?")) {
            ps.setTimestamp(1, AGED_UPDATED_AT);
            ps.setObject(2, postId);
            ps.executeUpdate();
        }
    }

    private void assertQuarantineUpdatedAtAdvanced(UUID postId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT updated_at > ? FROM quarantine WHERE post_id = ?")) {
            ps.setTimestamp(1, AGED_UPDATED_AT);
            ps.setObject(2, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getBoolean(1),
                    "the re-hide must bump the existing PENDING row's updated_at (Provider cursor)");
            }
        }
    }

    /**
     * Read the re-hide-inserted row through {@code quarantine_review_view}
     * — the redacted projection {@code /quarantine list} queries — and
     * assert the shape the admin queue surfaces: {@code flagged_by='stage2'},
     * {@code status='PENDING'}, the {@code reeval_injection} rule id, and a
     * whole-body span {@code [0, bodyLength)}. Returns the row's id for the
     * NOTIFY assertion. (M1-738)
     */
    private UUID assertReviewViewRow(UUID postId, int bodyLength) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, flagged_by, status, rule_id, span_start, span_end "
                     + "FROM quarantine_review_view WHERE post_id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(),
                    "the inserted row must be visible through quarantine_review_view (the admin queue)");
                UUID quarantineId = (UUID) rs.getObject(1);
                assertEquals("stage2", rs.getString(2), "flagged_by must name the re-evaluation actor");
                assertEquals("PENDING", rs.getString(3));
                assertEquals("reeval_injection", rs.getString(4));
                assertEquals(0, rs.getInt(5), "whole-body span start");
                assertEquals(bodyLength, rs.getInt(6), "whole-body span end");
                return quarantineId;
            }
        }
    }

    private void assertOriginalHtmlAndPlaceholder(UUID quarantineId, String expectedBody) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT original_html, placeholder_id FROM quarantine WHERE id = ?")) {
            ps.setObject(1, quarantineId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expectedBody, rs.getString(1),
                    "original_html must hold the exact body the re-judge saw");
                String placeholderId = rs.getString(2);
                assertNotNull(placeholderId, "placeholder_id is NOT NULL in V10");
                assertFalse(placeholderId.isEmpty(), "placeholder_id must be a real id");
            }
        }
    }

    private UUID seedSource(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('rss', ?, ?, 'news', '{}'::text[]) "
                     + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            ps.setString(1, "https://reeval-test.example/" + slug);
            ps.setString(2, "ReEval " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void clearNeedsReviewPosts() throws Exception {
        // The depth count sees the whole shared test DB; start the
        // exclusion assertion from a clean NEEDS_REVIEW slate (nothing
        // references these rows, so the delete is FK-safe).
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM post WHERE status = 'NEEDS_REVIEW'")) {
            ps.executeUpdate();
        }
    }

    private void setPostBody(SeededPost post, String body) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE post SET body = ? WHERE id = ? AND fetched_at = ?")) {
            ps.setString(1, body);
            ps.setObject(2, post.id);
            ps.setTimestamp(3, Timestamp.from(post.fetchedAt));
            ps.executeUpdate();
        }
    }

    private void setPostStatus(SeededPost post, String status) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE post SET status = ?, status_changed_at = now() "
                     + "WHERE id = ? AND fetched_at = ?")) {
            ps.setString(1, status);
            ps.setObject(2, post.id);
            ps.setTimestamp(3, Timestamp.from(post.fetchedAt));
            ps.executeUpdate();
        }
    }

    private void setReEvalAttempts(UUID postId, Instant fetchedAt, int attempts) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE post SET re_eval_attempts = ? WHERE id = ? AND fetched_at = ?")) {
            ps.setInt(1, attempts);
            ps.setObject(2, postId);
            ps.setTimestamp(3, Timestamp.from(fetchedAt));
            ps.executeUpdate();
        }
    }

    private void assertPostField(UUID postId, String field, Object expected) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT " + field + " FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expected, rs.getObject(1));
            }
        }
    }

    private void assertPostStatus(UUID postId, String expected) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expected, rs.getString(1));
            }
        }
    }

    private void assertPostBodyContains(UUID postId, String substring) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT body FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                String body = rs.getString(1);
                assertTrue(body.contains(substring),
                    "post.body should contain '" + substring + "' but was: " + body);
            }
        }
    }

    private void assertQuarantineStatus(UUID postId, String expected) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status FROM quarantine WHERE post_id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expected, rs.getString(1));
            }
        }
    }

    private void assertReEvalAttempts(UUID postId, int expected) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT re_eval_attempts FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expected, rs.getInt(1));
            }
        }
    }

    private void assertReEvalReleasedCount(UUID postId, int expected) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(*) FROM audit_log "
                     + "WHERE target_id = (SELECT uid FROM post WHERE id = ?) "
                     + "AND action = 'RE_EVAL_RELEASED'")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expected, rs.getInt(1),
                    "RE_EVAL_RELEASED rows for post " + postId);
            }
        }
    }

    private void assertReEvalReleasedPriorVerdict(UUID postId, String expected) throws Exception {
        // ->> extraction instead of a raw-substring match: the jsonb
        // column re-renders with its own spacing on read-back.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT details_json->>'prior_verdict' FROM audit_log "
                     + "WHERE target_id = (SELECT uid FROM post WHERE id = ?) "
                     + "AND action = 'RE_EVAL_RELEASED'")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Expected RE_EVAL_RELEASED audit row for " + postId);
                assertEquals(expected, rs.getString(1),
                    "RE_EVAL_RELEASED details_json.prior_verdict");
            }
        }
    }

    /**
     * Establish a clean LISTEN on {@code channel} for the supplied
     * connection. PostgreSQL only delivers a NOTIFY to connections that
     * were already LISTENing when the emitting transaction commits, so the
     * caller must hold this connection open across the job invocation. The
     * {@code UNLISTEN *} + drain resets any registration the pooled
     * connection carried from a prior test. Mirrors ReEvalVerdictNotifyIT.
     */
    private PGConnection listenTo(Connection conn, String channel) throws Exception {
        conn.setAutoCommit(true);
        try (Statement s = conn.createStatement()) {
            s.execute("UNLISTEN *");
            s.execute("LISTEN " + channel);
        }
        PGConnection pg = conn.unwrap(PGConnection.class);
        pg.getNotifications();
        return pg;
    }

    /** Poll until at least {@code minimum} notifications arrive or the 10s deadline lapses. */
    private PGNotification[] awaitNotifications(PGConnection pg, int minimum) throws Exception {
        long deadlineNanos = System.nanoTime() + 10_000_000_000L;
        List<PGNotification> collected = new ArrayList<>();
        while (System.nanoTime() < deadlineNanos) {
            PGNotification[] batch = pg.getNotifications(500);
            if (batch != null) {
                for (PGNotification n : batch) {
                    collected.add(n);
                }
                if (collected.size() >= minimum) {
                    return collected.toArray(new PGNotification[0]);
                }
            }
        }
        return collected.isEmpty() ? null : collected.toArray(new PGNotification[0]);
    }

    /**
     * Assert that exactly one quarantine_review NOTIFY arrived carrying the
     * expected target_kind/target_id/new_status (the QuarantineNotifyEmitter
     * JSON payload). A regression that drops the emission yields zero
     * notifications and fails on assertNotNull; a wrong status/target fails
     * the payload check.
     */
    private void assertQuarantineReviewNotify(PGConnection pg, String targetKind,
                                              UUID targetId, String newStatus) throws Exception {
        PGNotification[] notifications = awaitNotifications(pg, 1);
        assertNotNull(notifications,
            "expected a quarantine_review NOTIFY for " + targetKind + " " + targetId);
        assertEquals(1, notifications.length,
            "exactly one quarantine_review NOTIFY for the transition");
        String payload = notifications[0].getParameter();
        assertTrue(payload.contains("\"target_kind\":\"" + targetKind + "\""),
            "NOTIFY payload target_kind must be " + targetKind + "; got: " + payload);
        assertTrue(payload.contains("\"target_id\":\"" + targetId + "\""),
            "NOTIFY payload target_id must be " + targetId + "; got: " + payload);
        assertTrue(payload.contains("\"new_status\":\"" + newStatus + "\""),
            "NOTIFY payload new_status must be " + newStatus + "; got: " + payload);
    }

    private void assertAdminNotification(String errorClass) throws Exception {
        var state = throttledAdminNotifier.getState(errorClass);
        assertTrue(state.isPresent(),
            "Expected admin notification with error_class=" + errorClass);
    }

    record SeededPost(UUID id, Instant fetchedAt) {
    }
}
