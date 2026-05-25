package app.zcat.infochat.collector.eval.reeval;

import app.zcat.infochat.collector.eval.TransactionHelper;
import app.zcat.infochat.collector.eval.stage2.Stage2VerdictHandler;
import app.zcat.infochat.collector.eval.stage2.Stage2Worker;
import app.zcat.infochat.collector.notify.QuarantineNotifyEmitter;
import app.zcat.infochat.collector.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jspecify.annotations.NonNull;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Periodic re-evaluation job for quarantined posts. Two post classes
 * feed the queue:
 * <ol>
 *   <li><b>Infra-failure</b> — {@code stage2_failed=true}: Stage 2
 *       didn't run (LLM unreachable). Released with Stage 1 redactions
 *       but eligible for re-evaluation to clear the failure flag.</li>
 *   <li><b>UNKNOWN-verdict</b> — {@code status='QUARANTINED' AND
 *       stage2_done=true AND stage2_failed=false}: Stage 2 ran but
 *       returned UNKNOWN. Quarantined but eligible for a second
 *       opinion with a lower attempt cap.</li>
 * </ol>
 *
 * <p>Each class has an independent attempt cap. On BENIGN re-eval:
 * infra-failure posts get {@code stage2_failed} cleared and quarantine
 * PENDING→BENIGN_CLOSED; UNKNOWN posts get promoted
 * QUARANTINED→READY with quarantine PENDING→BENIGN_CLOSED. Non-BENIGN
 * re-evals leave the post in place with the counter incremented. Cap
 * exhaustion transitions to NEEDS_REVIEW with a throttled admin
 * notification.
 */
@ApplicationScoped
public class ReEvaluationJob {

    static final String ERROR_CLASS_REEVAL_CAP_EXHAUSTION = "re-eval-cap-exhaustion";
    static final String ERROR_CLASS_REEVAL_RELEASED = "re-eval-released";
    static final String ERROR_CLASS_NEEDS_REVIEW_DEPTH = "needs-review-depth";

    private static final Logger LOG = Logger.getLogger(ReEvaluationJob.class);

    @Inject
    DataSource dataSource;

    @Inject
    Stage2Worker stage2Worker;

    @Inject
    QuarantineNotifyEmitter quarantineNotifyEmitter;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @Inject
    AuditLogWriter auditLogWriter;

    @ConfigProperty(name = "infochat.reeval.infra-failure-cap")
    int infraFailureCap;

    @ConfigProperty(name = "infochat.reeval.unknown-cap")
    int unknownCap;

    @ConfigProperty(name = "infochat.reeval.batch-size", defaultValue = "16")
    int batchSize;

    @ConfigProperty(name = "infochat.reeval.needs-review-depth-threshold")
    int needsReviewDepthThreshold;

    @Scheduled(every = "{infochat.reeval.poll-interval}")
    public void onTick() {
        List<ReEvalCandidate> candidates;
        try {
            candidates = enumerateCandidates();
        } catch (SQLException e) {
            LOG.warn("ReEvaluationJob: failed to enumerate candidates; skipping tick", e);
            return;
        }
        for (ReEvalCandidate candidate : candidates) {
            try {
                processOne(candidate);
            } catch (RuntimeException e) {
                LOG.warnf(e, "ReEvaluationJob: processing failed for post_id=%s; will retry next tick",
                    candidate.postId());
            }
        }
        checkNeedsReviewDepth();
    }

    void processOne(@NonNull ReEvalCandidate candidate) {
        int cap = candidate.stage2Failed() ? infraFailureCap : unknownCap;

        if (candidate.reEvalAttempts() >= cap) {
            transitionToNeedsReview(candidate);
            return;
        }

        String originalBody = reconstructOriginalBody(candidate);
        Stage2VerdictHandler.Verdict verdict = stage2Worker.judgeBody(candidate.postId(), originalBody);

        if (verdict == Stage2VerdictHandler.Verdict.BENIGN) {
            applyBenignReEval(candidate);
        } else if (verdict == Stage2VerdictHandler.Verdict.INFRA_FAILURE) {
            // Transient LLM outage — do not consume an attempt.
            // The spec limits counter increments to INJECTION/MALWARE/UNKNOWN.
            LOG.infof("ReEvaluationJob: INFRA_FAILURE for post_id=%s — skipping attempt increment",
                candidate.postId());
        } else {
            incrementAttemptCounter(candidate);
        }
    }

    private void applyBenignReEval(ReEvalCandidate candidate) {
        String priorVerdict = candidate.stage2Failed() ? "INFRA_FAILURE" : "UNKNOWN";
        TransactionHelper.inTransaction(dataSource, "ReEvaluationJob.applyBenign", conn -> {
            if (candidate.stage2Failed()) {
                // Infra-failure: clear stage2_failed, post stays RAW
                // (it was released with redactions), close quarantine.
                clearStage2Failed(conn, candidate);
            } else {
                // UNKNOWN: promote QUARANTINED→READY, close quarantine.
                promoteToReady(conn, candidate);
            }
            closeQuarantineRows(conn, candidate.postId());
            writeReEvalReleasedAudit(conn, candidate, priorVerdict, candidate.reEvalAttempts() + 1);
        });
        throttledAdminNotifier.notifyOnce(
            ERROR_CLASS_REEVAL_RELEASED,
            ERROR_CLASS_REEVAL_RELEASED,
            "Re-eval released post_id=" + candidate.postId()
                + " prior_verdict=" + priorVerdict);
        LOG.infof("ReEvaluationJob: BENIGN re-eval for post_id=%s (prior=%s) — released",
            candidate.postId(), priorVerdict);
    }

    private void transitionToNeedsReview(ReEvalCandidate candidate) {
        TransactionHelper.inTransaction(dataSource, "ReEvaluationJob.needsReview", conn -> {
            final String sql =
                "UPDATE post SET status = 'NEEDS_REVIEW', status_changed_at = now() "
                    + "WHERE id = ? AND fetched_at = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, candidate.postId());
                ps.setTimestamp(2, Timestamp.from(candidate.fetchedAt()));
                ps.executeUpdate();
            }
            quarantineNotifyEmitter.emit(conn, "post", candidate.postId(), "NEEDS_REVIEW");
        });
        throttledAdminNotifier.notifyOnce(
            ERROR_CLASS_REEVAL_CAP_EXHAUSTION,
            ERROR_CLASS_REEVAL_CAP_EXHAUSTION,
            "Re-eval cap exhausted for post_id=" + candidate.postId());
        LOG.infof("ReEvaluationJob: cap exhausted for post_id=%s — transitioned to NEEDS_REVIEW",
            candidate.postId());
    }

    private void incrementAttemptCounter(ReEvalCandidate candidate) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE post SET re_eval_attempts = re_eval_attempts + 1 "
                     + "WHERE id = ? AND fetched_at = ?")) {
            ps.setObject(1, candidate.postId());
            ps.setTimestamp(2, Timestamp.from(candidate.fetchedAt()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(
                "ReEvaluationJob: failed to increment re_eval_attempts for post_id=" + candidate.postId(), e);
        }
    }

    private void clearStage2Failed(Connection conn, ReEvalCandidate candidate) throws SQLException {
        final String sql =
            "UPDATE post SET stage2_failed = FALSE, re_eval_attempts = re_eval_attempts + 1 "
                + "WHERE id = ? AND fetched_at = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, candidate.postId());
            ps.setTimestamp(2, Timestamp.from(candidate.fetchedAt()));
            ps.executeUpdate();
        }
    }

    private void promoteToReady(Connection conn, ReEvalCandidate candidate) throws SQLException {
        final String sql =
            "UPDATE post SET status = 'READY', ready_at = now(), status_changed_at = now(), "
                + "re_eval_attempts = re_eval_attempts + 1 "
                + "WHERE id = ? AND fetched_at = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, candidate.postId());
            ps.setTimestamp(2, Timestamp.from(candidate.fetchedAt()));
            ps.executeUpdate();
        }
    }

    private void closeQuarantineRows(Connection conn, UUID postId) throws SQLException {
        final String sql =
            "UPDATE quarantine SET status = 'BENIGN_CLOSED', updated_at = now() "
                + "WHERE post_id = ? AND status = 'PENDING'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, postId);
            ps.executeUpdate();
        }
    }

    private void writeReEvalReleasedAudit(Connection conn, ReEvalCandidate candidate,
                                          String priorVerdict, int attempt) throws SQLException {
        String detailsJson = "{\"prior_verdict\":\"" + priorVerdict
            + "\",\"new_verdict\":\"BENIGN\",\"attempt\":" + attempt + "}";
        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
            .actorContactId("re_eval_job")
            .action(AuditAction.RE_EVAL_RELEASED)
            .targetKind("post")
            .targetId(candidate.postId().toString())
            .detailsJson(detailsJson)
            .build();
        auditLogWriter.write(conn, row);
    }

    /**
     * Reconstruct the original (pre-redaction) body for the Stage 2
     * judge prompt. For each quarantine row with a placeholder_id,
     * replace the {@code [REDACTED:<id>]} token in the post body with
     * the original_html. For watchdog-abort posts (single quarantine
     * row where the original_html IS the entire body), the same
     * replacement logic works because the post body contains a single
     * placeholder covering the whole content.
     */
    String reconstructOriginalBody(ReEvalCandidate candidate) {
        try (Connection conn = dataSource.getConnection()) {
            String body = readPostBody(conn, candidate);
            final String sql =
                "SELECT placeholder_id, original_html FROM quarantine "
                    + "WHERE post_id = ? AND original_html IS NOT NULL";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, candidate.postId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String placeholderId = rs.getString(1);
                        String originalHtml = rs.getString(2);
                        if (placeholderId != null && originalHtml != null) {
                            body = body.replace("[REDACTED:" + placeholderId + "]", originalHtml);
                        }
                    }
                }
            }
            return body;
        } catch (SQLException e) {
            throw new IllegalStateException(
                "ReEvaluationJob: failed to reconstruct body for post_id=" + candidate.postId(), e);
        }
    }

    private String readPostBody(Connection conn, ReEvalCandidate candidate) throws SQLException {
        final String sql = "SELECT body FROM post WHERE id = ? AND fetched_at = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, candidate.postId());
            ps.setTimestamp(2, Timestamp.from(candidate.fetchedAt()));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
                throw new IllegalStateException(
                    "ReEvaluationJob: post not found: " + candidate.postId());
            }
        }
    }

    List<ReEvalCandidate> enumerateCandidates() throws SQLException {
        // Two classes: infra-failure (stage2_failed=true, any status that
        // isn't NEEDS_REVIEW) and UNKNOWN (QUARANTINED + stage2_done +
        // !stage2_failed). Union in one query with a cap filter.
        final String sql =
            "SELECT id, fetched_at, stage2_failed, re_eval_attempts FROM post "
                + "WHERE ("
                + "  (stage2_failed = TRUE AND status != 'NEEDS_REVIEW' AND re_eval_attempts < ?)"
                + "  OR "
                + "  (status = 'QUARANTINED' AND stage2_done = TRUE AND stage2_failed = FALSE "
                + "   AND re_eval_attempts < ?)"
                + ") ORDER BY fetched_at, id LIMIT ?";
        List<ReEvalCandidate> candidates = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, infraFailureCap);
            ps.setInt(2, unknownCap);
            ps.setInt(3, batchSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    candidates.add(new ReEvalCandidate(
                        (UUID) rs.getObject(1),
                        rs.getTimestamp(2).toInstant(),
                        rs.getBoolean(3),
                        rs.getInt(4)));
                }
            }
        }
        return candidates;
    }

    void checkNeedsReviewDepth() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(*) FROM post WHERE status = 'NEEDS_REVIEW'")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long depth = rs.getLong(1);
                    if (depth > needsReviewDepthThreshold) {
                        throttledAdminNotifier.notifyOnce(
                            ERROR_CLASS_NEEDS_REVIEW_DEPTH,
                            ERROR_CLASS_NEEDS_REVIEW_DEPTH,
                            "NEEDS_REVIEW queue depth " + depth
                                + " exceeds threshold " + needsReviewDepthThreshold);
                    }
                }
            }
        } catch (SQLException e) {
            LOG.warn("ReEvaluationJob: failed to check NEEDS_REVIEW depth", e);
        }
    }

    record ReEvalCandidate(UUID postId, Instant fetchedAt, boolean stage2Failed, int reEvalAttempts) {
    }
}
