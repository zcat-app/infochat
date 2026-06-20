package app.zcat.infochat.collector.eval.reeval;

import app.zcat.infochat.collector.eval.PartitionScan;
import app.zcat.infochat.collector.eval.TransactionHelper;
import app.zcat.infochat.collector.eval.stage2.Stage2VerdictHandler;
import app.zcat.infochat.collector.eval.stage2.Stage2Worker;
import app.zcat.infochat.collector.notify.QuarantineNotifyEmitter;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.log.SafeLog;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Periodic re-evaluation job for quarantined posts. Two post classes
 * feed the queue:
 * <ol>
 *   <li><b>Infra-failure</b> — {@code stage2_failed=true}: Stage 2
 *       didn't run (LLM unreachable). Released with Stage 1 redactions
 *       but eligible for re-evaluation to clear the failure flag.</li>
 *   <li><b>UNKNOWN-verdict</b> — {@code status='QUARANTINED' AND
 *       stage2_done=true AND stage2_failed=false AND
 *       (stage2_verdict='UNKNOWN' OR re_eval_attempts>0)}: Stage 2
 *       ran but returned UNKNOWN. Quarantined but eligible for a
 *       second opinion with a lower attempt cap. First-pass
 *       INJECTION/MALWARE posts are excluded — they stay QUARANTINED
 *       until admin review per {@code docs/spec/security.md}
 *       §Failure handling, never auto-released. The non-zero-counter
 *       disjunct keeps UNKNOWN-entry posts whose interim rolls
 *       recorded a non-BENIGN verdict enumerable, so cap exhaustion
 *       → NEEDS_REVIEW stays reachable.</li>
 * </ol>
 *
 * <p>Each class has an independent attempt cap. On BENIGN re-eval:
 * infra-failure posts get {@code stage2_failed} cleared (a post
 * sitting QUARANTINED is additionally requeued to {@code RAW} so the
 * normal pipeline finishes the release) and quarantine
 * PENDING→BENIGN_CLOSED; UNKNOWN posts are requeued to {@code RAW} so
 * the normal tagger/entity/embedding workers and ReadyPromoter finish
 * the release (Invariant 5 routes the post to the next uncompleted
 * stage; ReadyPromoter owns the only {@code new_post} NOTIFY), with
 * quarantine PENDING→BENIGN_CLOSED. Non-BENIGN re-evals record the
 * verdict, increment the counter, and re-hide a released post to
 * QUARANTINED per {@code docs/spec/security.md} §Re-evaluation job —
 * a post the judge now classifies hostile must not stay user-visible
 * for the rest of the attempt budget. Cap exhaustion transitions to
 * NEEDS_REVIEW and emits the quarantine_review NOTIFY; the Provider's
 * consumer owns the throttled admin page for that transition.
 */
@ApplicationScoped
public class ReEvaluationJob {

    static final String ERROR_CLASS_REEVAL_CAP_EXHAUSTION = "re-eval-cap-exhaustion";
    static final String ERROR_CLASS_REEVAL_RELEASED = "re-eval-released";
    static final String ERROR_CLASS_NEEDS_REVIEW_DEPTH = "needs-review-depth";

    private static final Logger LOG = LoggerFactory.getLogger(ReEvaluationJob.class);

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

    // Minimum spacing between successive re-judges of the same fail-open
    // (infra-failure) post. enumerateCandidates excludes a candidate whose
    // last_reeval_at falls within this window, so the steady-recovery re-judge
    // load tracks the RATE of new fail-open posts rather than the standing
    // backlog (M1-370). Default is a multiple of poll-interval (see
    // application.properties); the UNKNOWN second-opinion disjunct is
    // deliberately left outside the window.
    @ConfigProperty(name = "infochat.reeval.cooldown")
    Duration reEvalCooldown;

    // The post partition retention horizon (live-data span). Reused as the
    // candidate-scan window so the fetched_at floor never excludes a live
    // post; no re-eval-specific knob is introduced. Same property
    // PartitionPruner uses to age partitions out.
    @ConfigProperty(name = "infochat.partitions.retention-days.post")
    int postRetentionDays;

    @Scheduled(every = "{infochat.reeval.poll-interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void onTick() {
        List<ReEvalCandidate> candidates;
        try {
            candidates = enumerateCandidates();
        } catch (SQLException e) {
            // SafeLog, never the raw Throwable (docs/spec/security.md
            // §Secrets handling — User content in exceptions).
            SafeLog.warn(LOG, "ReEvaluationJob: failed to enumerate candidates; skipping tick", e);
            return;
        }
        // Per-tick provider-down latch (M1-342). The first candidate whose
        // Stage-2 re-judge returns INFRA_FAILURE proves the LLM is unreachable
        // this tick; every remaining candidate would issue an identical failing
        // provider call and gain nothing (an INFRA_FAILURE verdict never
        // advances re_eval_attempts, so there is no progress to make). Bound the
        // outage-time fan-out to one provider call per tick and defer the rest
        // to the next tick's ordered scan from the top.
        boolean providerDown = false;
        for (ReEvalCandidate candidate : candidates) {
            if (providerDown) {
                break;
            }
            try {
                providerDown = processOne(candidate);
            } catch (RuntimeException e) {
                // SafeLog, never the raw Throwable: processOne weaves
                // the reconstructed pre-redaction body into the Stage 2
                // prompt, and the provider exception can echo its
                // request context (docs/spec/security.md §Secrets
                // handling — User content in exceptions).
                SafeLog.warn(LOG, "ReEvaluationJob: processing failed for post_id="
                    + candidate.postId() + "; will retry next tick", e);
            }
        }
        checkNeedsReviewDepth();
    }

    /**
     * Re-judge one candidate. Returns {@code true} iff the Stage-2 verdict
     * was INFRA_FAILURE — the signal {@link #onTick} latches to bound the
     * per-tick provider-call fan-out during an outage (M1-342).
     */
    boolean processOne(ReEvalCandidate candidate) {
        int cap = candidate.stage2Failed() ? infraFailureCap : unknownCap;

        if (candidate.reEvalAttempts() >= cap) {
            transitionToNeedsReview(candidate);
            return false;
        }

        String originalBody = reconstructOriginalBody(candidate);
        Stage2VerdictHandler.Verdict verdict = stage2Worker.judgeBody(candidate.postId(), originalBody);

        if (verdict == Stage2VerdictHandler.Verdict.BENIGN) {
            applyBenignReEval(candidate);
            return false;
        } else if (verdict == Stage2VerdictHandler.Verdict.INFRA_FAILURE) {
            // Transient LLM outage — do not consume an attempt.
            // The spec limits counter increments to INJECTION/MALWARE/UNKNOWN.
            LOG.info("ReEvaluationJob: INFRA_FAILURE for post_id={} — skipping attempt increment",
                candidate.postId());
            return true;
        } else {
            applyNonBenignReEval(candidate, verdict);
            return false;
        }
    }

    private void applyBenignReEval(ReEvalCandidate candidate) {
        // prior_verdict reflects the recorded stage2_verdict — interim
        // non-BENIGN rolls overwrite it, so an INJECTION-then-BENIGN
        // release is logged as the hostile flip it is, not the post's
        // entry class. NULL means the judge never produced a verdict:
        // the infra-failure entry state.
        String priorVerdict = candidate.stage2Verdict() != null
            ? candidate.stage2Verdict() : "INFRA_FAILURE";
        TransactionHelper.inTransaction(dataSource, "ReEvaluationJob.applyBenign", conn -> {
            if (candidate.stage2Failed()) {
                // Infra-failure: clear stage2_failed; a QUARANTINED
                // post is requeued to RAW, a RAW/READY one keeps its
                // status. Quarantine rows close below either way.
                clearStage2FailedAndRequeueIfQuarantined(conn, candidate);
            } else {
                // UNKNOWN: requeue QUARANTINED→RAW, close quarantine.
                requeueForPipeline(conn, candidate);
            }
            closeQuarantineRows(conn, candidate.postId());
            writeReEvalReleasedAudit(conn, candidate, priorVerdict, candidate.reEvalAttempts() + 1);
        });
        throttledAdminNotifier.notifyOnce(
            ERROR_CLASS_REEVAL_RELEASED,
            ERROR_CLASS_REEVAL_RELEASED,
            "Re-eval released post_id=" + candidate.postId()
                + " prior_verdict=" + priorVerdict);
        LOG.info("ReEvaluationJob: BENIGN re-eval for post_id={} (prior={}) — released",
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
            quarantineNotifyEmitter.emit(conn, QuarantineNotifyEmitter.TargetKind.POST,
                candidate.postId(), QuarantineNotifyEmitter.NewStatus.NEEDS_REVIEW);
        });
        // No Collector-side admin page here: the NEEDS_REVIEW NOTIFY
        // emitted above is what drives the Provider's throttled admin
        // notifier, and one transition must produce exactly one page
        // (docs/spec/architecture.md §Inter-service communication,
        // Consumer behavior). Paging here too would double-notify.
        LOG.info("ReEvaluationJob: cap exhausted for post_id={} — transitioned to NEEDS_REVIEW",
            candidate.postId());
    }

    /**
     * Non-BENIGN re-eval verdict (INJECTION / MALWARE / UNKNOWN):
     * record the verdict and increment the counter for both post
     * classes, and re-hide a post that is not currently QUARANTINED
     * (a Stage-2-infra-failure post released READY, or still RAW in
     * the pipeline) per {@code docs/spec/security.md} §Re-evaluation
     * job. {@code stage2_failed} is deliberately untouched — the spec
     * says it is preserved, and flipping it for UNKNOWN-class posts
     * would migrate them into the infra-failure enumeration branch
     * and its higher attempt cap, contradicting the same section's
     * lower-UNKNOWN-cap rationale. The re-hide and its NOTIFYs run in
     * ONE transaction so the announce can never outlive a rolled-back
     * transition.
     */
    private void applyNonBenignReEval(ReEvalCandidate candidate, Stage2VerdictHandler.Verdict verdict) {
        boolean reHidden = TransactionHelper.inTransactionReturning(dataSource, "ReEvaluationJob.applyNonBenign", conn -> {
            recordVerdictAndIncrementCounter(conn, candidate, verdict);
            boolean hidden = reHideToQuarantined(conn, candidate);
            if (hidden) {
                reAnnouncePendingQuarantineRows(conn, candidate.postId());
            }
            return hidden;
        });
        LOG.info("ReEvaluationJob: {} re-eval for post_id={} — verdict recorded, counter incremented{}",
            verdict, candidate.postId(), reHidden ? ", post re-hidden to QUARANTINED" : "");
    }

    private static void recordVerdictAndIncrementCounter(Connection conn, ReEvalCandidate candidate,
                                                         Stage2VerdictHandler.Verdict verdict) throws SQLException {
        // last_reeval_at rides the same UPDATE as the counter increment so the
        // cooldown stamp and the re-eval progress record can never diverge (M1-370).
        final String sql =
            "UPDATE post SET stage2_verdict = ?, re_eval_attempts = re_eval_attempts + 1, "
                + "last_reeval_at = now() "
                + "WHERE id = ? AND fetched_at = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, verdict.name());
            ps.setObject(2, candidate.postId());
            ps.setTimestamp(3, Timestamp.from(candidate.fetchedAt()));
            ps.executeUpdate();
        }
    }

    /**
     * Re-hide: {@code status='QUARANTINED'} for a post that isn't
     * already there. The {@code status <> 'QUARANTINED'} predicate
     * makes the UNKNOWN-class case (already QUARANTINED on every
     * tick) a no-op, so the PENDING re-announce below fires only on
     * an actual visibility transition — not on every re-eval attempt.
     */
    private static boolean reHideToQuarantined(Connection conn, ReEvalCandidate candidate) throws SQLException {
        final String sql =
            "UPDATE post SET status = 'QUARANTINED', status_changed_at = now() "
                + "WHERE id = ? AND fetched_at = ? AND status <> 'QUARANTINED'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, candidate.postId());
            ps.setTimestamp(2, Timestamp.from(candidate.fetchedAt()));
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Announce the re-hide on quarantine_review by re-emitting
     * PENDING for the post's open quarantine rows. No new channel or
     * payload shape — the wire contract is closed; the Provider's
     * high-water mark makes a duplicate (quarantine_id, PENDING)
     * idempotent. The {@code updated_at} bump is load-bearing: the
     * Provider catch-up scan cursors on {@code quarantine.updated_at},
     * so without it a missed NOTIFY could never be recovered.
     */
    private void reAnnouncePendingQuarantineRows(Connection conn, UUID postId) throws SQLException {
        final String sql =
            "UPDATE quarantine SET updated_at = now() "
                + "WHERE post_id = ? AND status = 'PENDING' RETURNING id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID quarantineId = (UUID) rs.getObject(1);
                    quarantineNotifyEmitter.emit(conn, QuarantineNotifyEmitter.TargetKind.QUARANTINE,
                        quarantineId, QuarantineNotifyEmitter.NewStatus.PENDING);
                }
            }
        }
    }

    /**
     * BENIGN on an infra-failure post: clear the flag, and requeue a
     * QUARANTINED post (release-on-stage2-failure=false, or re-hidden
     * by a prior non-BENIGN roll) to RAW so the normal pipeline and
     * ReadyPromoter finish the release — mirroring
     * {@link #requeueForPipeline}. A RAW or READY post keeps its
     * status: it is already in the pipeline or already visible, and
     * the flag clear completes the release on its own. Either way the
     * post ends on the release path, so the single RE_EVAL_RELEASED
     * row written by the caller records an actual release — never a
     * release-that-never-happened for a post left QUARANTINED.
     */
    private void clearStage2FailedAndRequeueIfQuarantined(Connection conn, ReEvalCandidate candidate)
            throws SQLException {
        final String sql =
            "UPDATE post SET stage2_failed = FALSE, "
                + "status = CASE WHEN status = 'QUARANTINED' THEN 'RAW' ELSE status END, "
                + "status_changed_at = CASE WHEN status = 'QUARANTINED' THEN now() "
                + "                         ELSE status_changed_at END, "
                + "re_eval_attempts = re_eval_attempts + 1, "
                // Stamp with the counter increment so the cooldown cannot
                // diverge from re-eval progress (M1-370).
                + "last_reeval_at = now() "
                + "WHERE id = ? AND fetched_at = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, candidate.postId());
            ps.setTimestamp(2, Timestamp.from(candidate.fetchedAt()));
            ps.executeUpdate();
        }
    }

    /**
     * BENIGN on an UNKNOWN post: requeue QUARANTINED→RAW instead of
     * flipping READY directly. A direct flip would orphan the post —
     * TaggerWorker picks only {@code status='RAW'}, so tags would stay
     * {@code '{}'} forever (invisible to tag-filtered retrieval), and
     * ReadyPromoter (the only {@code new_post} NOTIFY emit) would
     * never announce it. With {@code stage1_done / stage2_done} TRUE
     * and the later flags FALSE, Invariant 5 routes the requeued post
     * to Tagger → Entity → Embedding → ReadyPromoter, which flips
     * READY and fires {@code new_post}. {@code body} is untouched —
     * Stage 1 redactions stay until {@code /quarantine approve}. The
     * counter increment stays here: {@code writeReEvalReleasedAudit}'s
     * {@code attempt = reEvalAttempts()+1} math depends on it.
     */
    private void requeueForPipeline(Connection conn, ReEvalCandidate candidate) throws SQLException {
        final String sql =
            "UPDATE post SET status = 'RAW', status_changed_at = now(), "
                + "re_eval_attempts = re_eval_attempts + 1, "
                // Stamp with the counter increment so the cooldown cannot
                // diverge from re-eval progress (M1-370).
                + "last_reeval_at = now() "
                + "WHERE id = ? AND fetched_at = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, candidate.postId());
            ps.setTimestamp(2, Timestamp.from(candidate.fetchedAt()));
            ps.executeUpdate();
        }
    }

    /**
     * Close the post's open quarantine rows PENDING→BENIGN_CLOSED and
     * emit one quarantine_review NOTIFY per row closed HERE — the
     * channel contract fires on BENIGN_CLOSED transitions, and
     * UPDATE…RETURNING scopes the emit so rows closed by an earlier
     * verdict never re-fire. Runs on the caller's connection inside
     * applyBenignReEval's transaction (same-transaction NOTIFY rule).
     *
     * <p>Only Stage 1 rows are touched ({@code flagged_by='stage1'}),
     * matching {@code Stage2VerdictHandler}'s BENIGN-close twin: a
     * future non-stage1 quarantine writer (none in M1) must not be
     * auto-closed by a re-eval BENIGN release.
     */
    private void closeQuarantineRows(Connection conn, UUID postId) throws SQLException {
        final String sql =
            "UPDATE quarantine SET status = 'BENIGN_CLOSED', updated_at = now() "
                + "WHERE post_id = ? AND flagged_by = 'stage1' AND status = 'PENDING' RETURNING id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID quarantineId = (UUID) rs.getObject(1);
                    quarantineNotifyEmitter.emit(conn, QuarantineNotifyEmitter.TargetKind.QUARANTINE,
                        quarantineId, QuarantineNotifyEmitter.NewStatus.BENIGN_CLOSED);
                }
            }
        }
    }

    private void writeReEvalReleasedAudit(Connection conn, ReEvalCandidate candidate,
                                          String priorVerdict, int attempt) throws SQLException {
        String detailsJson = "{\"prior_verdict\":\"" + priorVerdict
            + "\",\"new_verdict\":\"BENIGN\",\"attempt\":" + attempt + "}";
        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
            .actorContactId("re_eval_job")
            .action(AuditAction.RE_EVAL_RELEASED)
            .targetKind(TargetKind.POST)
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
        String body = candidate.body();
        if (body == null) {
            // post.body is a nullable column; a re-eval candidate with no
            // body cannot be spliced. The candidate scan carries the body,
            // so this is the scan-boundary equivalent of the prior
            // readPostBody NULL guard — reject per-candidate (onTick catches
            // RuntimeException per candidate) rather than splicing an empty
            // body or aborting the whole batch.
            throw new IllegalStateException(
                "ReEvaluationJob: post body is NULL: " + candidate.postId());
        }
        try (Connection conn = dataSource.getConnection()) {
            Map<String, String> originalsByPlaceholderId = new HashMap<>();
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
                            originalsByPlaceholderId.put(placeholderId, originalHtml);
                        }
                    }
                }
            }
            return splicePlaceholders(body, originalsByPlaceholderId);
        } catch (SQLException e) {
            throw new IllegalStateException(
                "ReEvaluationJob: failed to reconstruct body for post_id=" + candidate.postId(), e);
        }
    }

    /**
     * Single-pass, position-anchored splice of {@code [REDACTED:<id>]}
     * tokens. Spliced original_html is emitted straight to the output
     * and never rescanned — original_html is untrusted quarantined
     * content, and a span containing a placeholder-shaped literal must
     * survive byte-exact (an order-dependent sequence of global
     * String.replace calls would substitute into already-spliced
     * content). A token whose id has no quarantine row is user content
     * wearing a placeholder costume: emitted verbatim, with the scan
     * resuming one char past its '[' so a real token overlapping it is
     * still found.
     */
    private static String splicePlaceholders(String body,
                                             Map<String, String> originalsByPlaceholderId) {
        final String prefix = "[REDACTED:";
        StringBuilder reconstructed = new StringBuilder(body.length());
        int cursor = 0;
        while (true) {
            int tokenStart = body.indexOf(prefix, cursor);
            if (tokenStart < 0) {
                break;
            }
            int tokenEnd = body.indexOf(']', tokenStart + prefix.length());
            if (tokenEnd < 0) {
                break;
            }
            String placeholderId = body.substring(tokenStart + prefix.length(), tokenEnd);
            String originalHtml = originalsByPlaceholderId.get(placeholderId);
            if (originalHtml == null) {
                reconstructed.append(body, cursor, tokenStart + 1);
                cursor = tokenStart + 1;
                continue;
            }
            reconstructed.append(body, cursor, tokenStart).append(originalHtml);
            cursor = tokenEnd + 1;
        }
        reconstructed.append(body, cursor, body.length());
        return reconstructed.toString();
    }

    List<ReEvalCandidate> enumerateCandidates() throws SQLException {
        // Two classes: infra-failure (stage2_failed=true, any status that
        // isn't NEEDS_REVIEW) and UNKNOWN (QUARANTINED + stage2_done +
        // !stage2_failed). The UNKNOWN branch keys on the recorded
        // first-pass verdict: a first-pass INJECTION/MALWARE post stays
        // QUARANTINED for admin review (docs/spec/security.md §Failure
        // handling) and must never re-roll toward auto-release; the
        // re_eval_attempts > 0 disjunct keeps UNKNOWN-entry posts whose
        // interim rolls overwrote stage2_verdict enumerable, so cap
        // exhaustion → NEEDS_REVIEW stays reachable. No cap filter here:
        // cap-reached rows must still enter processOne so its >= cap
        // branch fires the NEEDS_REVIEW transition.
        // transitionToNeedsReview flips status to NEEDS_REVIEW, which
        // excludes the row from both branches on the next tick — so a
        // cap-exhausted row is enumerated exactly once more, then drops out.
        //
        // The infra-failure disjunct additionally excludes a post re-judged
        // within infochat.reeval.cooldown (last_reeval_at inside the window):
        // a fail-open post is re-judged at most once per cooldown, so the
        // re-judge load tracks the RATE of new fail-open posts rather than the
        // standing backlog (M1-370). The IS NULL leg keeps a never-attempted
        // post immediately eligible. The cooldown is scoped to the
        // infra-failure disjunct only — the UNKNOWN second-opinion cadence is
        // unchanged.
        //
        // The fetched_at >= now() - (retention horizon + slack) bound is the
        // partition-pruning predicate: post is RANGE(fetched_at) partitioned,
        // so without a fetched_at floor the planner scans every live partition
        // each tick. The window spans the full retention horizon so no live
        // candidate is excluded; the partial index paired with this scan in
        // the V47 migration carries the disjunction below so the planner can
        // use it inside the surviving partitions.
        final String sql =
            "SELECT id, fetched_at, stage2_failed, re_eval_attempts, stage2_verdict, body FROM post "
                + "WHERE fetched_at >= now() - ?::INTERVAL"
                + "  AND ("
                + "  (stage2_failed = TRUE AND status != 'NEEDS_REVIEW'"
                + "   AND (last_reeval_at IS NULL OR last_reeval_at < now() - ?::INTERVAL))"
                + "  OR "
                + "  (status = 'QUARANTINED' AND stage2_done = TRUE AND stage2_failed = FALSE"
                + "   AND (stage2_verdict = 'UNKNOWN' OR re_eval_attempts > 0))"
                + ") ORDER BY fetched_at, id LIMIT ?";
        List<ReEvalCandidate> candidates = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, partitionScanWindow());
            ps.setString(2, cooldownInterval());
            ps.setInt(3, batchSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    candidates.add(new ReEvalCandidate(
                        (UUID) rs.getObject(1),
                        rs.getTimestamp(2).toInstant(),
                        rs.getBoolean(3),
                        rs.getInt(4),
                        rs.getString(5),
                        rs.getString(6)));
                }
            }
        }
        return candidates;
    }

    void checkNeedsReviewDepth() {
        long depth;
        try {
            depth = countNeedsReviewWithinScanWindow();
        } catch (SQLException e) {
            // SafeLog, never the raw Throwable (docs/spec/security.md
            // §Secrets handling — User content in exceptions).
            SafeLog.warn(LOG, "ReEvaluationJob: failed to check NEEDS_REVIEW depth", e);
            return;
        }
        if (depth > needsReviewDepthThreshold) {
            throttledAdminNotifier.notifyOnce(
                ERROR_CLASS_NEEDS_REVIEW_DEPTH,
                ERROR_CLASS_NEEDS_REVIEW_DEPTH,
                "NEEDS_REVIEW queue depth " + depth
                    + " exceeds threshold " + needsReviewDepthThreshold);
        }
    }

    /**
     * NEEDS_REVIEW depth bounded by the same fetched_at floor as
     * {@link #enumerateCandidates} — without it this every-5-minutes
     * COUNT scans every live partition of the RANGE(fetched_at) post
     * table. The depth alert is an operator signal about the actionable
     * review queue; a row older than the retention horizon + slack is
     * about to age out via partition drop and stops counting toward the
     * alert, the same window trade-off the candidate scan accepts.
     */
    long countNeedsReviewWithinScanWindow() throws SQLException {
        final String sql = "SELECT COUNT(*) FROM post WHERE status = 'NEEDS_REVIEW' "
            + "AND fetched_at >= now() - ?::INTERVAL";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, partitionScanWindow());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** The shared candidate/depth scan floor: retention horizon + slack, as a SQL INTERVAL string. */
    private String partitionScanWindow() {
        return (postRetentionDays + PartitionScan.PARTITION_SCAN_SLACK.toDays()) + " days";
    }

    /** The infra-failure re-judge cooldown as a SQL INTERVAL string (M1-370). */
    private String cooldownInterval() {
        return reEvalCooldown.toSeconds() + " seconds";
    }

    /**
     * {@code stage2Verdict} is the recorded {@code post.stage2_verdict}
     * — NULL only for infra-failure posts the judge never produced a
     * verdict for (Stage2VerdictHandler records every parsed verdict;
     * non-BENIGN re-eval rolls overwrite it).
     */
    record ReEvalCandidate(UUID postId, Instant fetchedAt, boolean stage2Failed, int reEvalAttempts,
                           @Nullable String stage2Verdict, @Nullable String body) {
    }
}
