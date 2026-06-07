package app.zcat.infochat.collector.eval.reeval;

import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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
 * Integration test for the cap-exhaustion path through the full
 * scheduled tick. Unlike {@code ReEvaluationJobTest}, which hands
 * {@link ReEvaluationJob#processOne} a constructed candidate and so
 * bypasses {@link ReEvaluationJob#enumerateCandidates}, this test seeds a
 * cap-exhausted {@code QUARANTINED} row and drives {@link
 * ReEvaluationJob#onTick} — the real entry point — so the candidate must
 * survive {@code enumerateCandidates} to reach {@code processOne}.
 *
 * <p>This is the regression guard for REEVAL-CAP-UNREACHABLE: before the
 * fix, {@code enumerateCandidates} filtered {@code re_eval_attempts < cap}
 * and a cap-reached row never entered the queue, so the {@code >= cap →
 * NEEDS_REVIEW} transition was structurally unreachable from the scheduled
 * path. The seed sets {@code re_eval_attempts} at the cap, which the old
 * predicate would have excluded.
 *
 * <p>Also home to the enumeration-predicate guards for the queue's
 * feed classes ({@code docs/spec/security.md} §Failure handling,
 * §Re-evaluation job): a first-pass INJECTION post never enters the
 * queue, while an UNKNOWN-entry post whose interim roll recorded a
 * non-BENIGN verdict stays enumerable so cap exhaustion remains
 * reachable.
 */
@QuarkusTest
class ReEvaluationJobScheduledPathIT {

    private static final Instant FETCHED_AT = Instant.parse("2026-05-23T09:00:00Z");

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    ReEvaluationJob reEvaluationJob;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @ConfigProperty(name = "infochat.reeval.unknown-cap")
    int unknownCap;

    @Test
    void capExhaustedRowReachesNeedsReviewThroughScheduledTick() throws Exception {
        UUID postId = seedQuarantinedStage2Post("scheduled-cap", "UNKNOWN", unknownCap);
        // Establish a known precondition for the cap-exhaustion notifier
        // key: ReEvaluationJobTest shares this Quarkus instance and may
        // have already recorded (and thus throttled) this key. Clearing
        // the row guarantees this tick's notifyOnce takes the fresh-INSERT
        // (EMITTED) branch rather than being suppressed inside the window.
        resetCapExhaustionNotifier();

        reEvaluationJob.onTick();

        assertPostStatus(postId, "NEEDS_REVIEW");
        var state = throttledAdminNotifier.getState(
            ReEvaluationJob.ERROR_CLASS_REEVAL_CAP_EXHAUSTION);
        assertTrue(state.isPresent(),
            "cap-exhaustion admin notification must fire when the scheduled tick "
                + "transitions a cap-reached row to NEEDS_REVIEW");
    }

    @Test
    void firstPassInjectionPostIsNeverEnumerated() throws Exception {
        // First-pass INJECTION (and MALWARE) posts are not re-eval queue
        // feeds — they stay QUARANTINED until admin review
        // (docs/spec/security.md §Failure handling); only UNKNOWN
        // entries and infra failures re-roll. Non-enumeration is what
        // keeps the judge from ever being invoked for the post.
        UUID postId = seedQuarantinedStage2Post("first-pass-injection", "INJECTION", 0);

        boolean enumerated = reEvaluationJob.enumerateCandidates().stream()
            .anyMatch(candidate -> candidate.postId().equals(postId));
        assertFalse(enumerated,
            "a first-pass INJECTION post must never enter the re-eval queue");

        reEvaluationJob.onTick();

        assertPostStatus(postId, "QUARANTINED");
        assertReEvalAttempts(postId, 0);
    }

    @Test
    void unknownEntryPostWithInterimInjectionRollStaysEnumerated() throws Exception {
        // An UNKNOWN-entry post whose interim roll recorded INJECTION
        // (re_eval_attempts > 0) must stay enumerable so cap exhaustion
        // → NEEDS_REVIEW remains reachable ("the attempt counter
        // increments", docs/spec/security.md §Re-evaluation job).
        // Seeding at the cap drives the whole consequence through the
        // real tick: reaching NEEDS_REVIEW proves the row survived
        // enumeration.
        UUID postId = seedQuarantinedStage2Post("interim-injection-cap", "INJECTION", unknownCap);
        resetCapExhaustionNotifier();

        reEvaluationJob.onTick();

        assertPostStatus(postId, "NEEDS_REVIEW");
    }

    // ---------- helpers ----------

    private UUID seedQuarantinedStage2Post(String slug, String stage2Verdict, int reEvalAttempts)
            throws Exception {
        UUID sourceId = seedSource(slug);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body,"
                     + "  fetched_at, status, status_changed_at,"
                     + "  stage1_done, stage1_flagged, stage2_done, stage2_failed,"
                     + "  tagger_done, tagger_fallback, embedding_done, tags, re_eval_attempts,"
                     + "  stage2_verdict"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, ?, 'body',"
                     + "  ?, 'QUARANTINED', now(),"
                     + "  TRUE, TRUE, TRUE, FALSE,"
                     + "  FALSE, FALSE, FALSE, '{}', ?,"
                     + "  ?"
                     + ") RETURNING id")) {
            ps.setString(1, "reeval-it-" + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "upstream-it-" + slug);
            ps.setString(4, "ReEval IT " + slug);
            ps.setTimestamp(5, Timestamp.from(FETCHED_AT));
            ps.setInt(6, reEvalAttempts);
            ps.setString(7, stage2Verdict);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
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

    private UUID seedSource(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('rss', ?, ?, 'news', '{}'::text[]) "
                     + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            ps.setString(1, "https://reeval-it.example/" + slug);
            ps.setString(2, "ReEval IT " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void resetCapExhaustionNotifier() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM admin_notification_state WHERE notification_key = ?")) {
            ps.setString(1, ReEvaluationJob.ERROR_CLASS_REEVAL_CAP_EXHAUSTION);
            ps.executeUpdate();
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
}
