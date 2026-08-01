package app.zcat.infochat.collector.eval.stage2;

import app.zcat.infochat.collector.eval.TransactionHelper;
import app.zcat.infochat.collector.eval.stage1.QuarantineDao;
import app.zcat.infochat.collector.notify.QuarantineNotifyEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stage 2's per-verdict dispatch + SQL writer. Owns the
 * {@code post} status + per-stage flag transitions and the
 * {@code quarantine} state-machine move for every Stage 2 outcome.
 * Per {@code docs/spec/security.md} §Failure handling — the
 * verdict-vs-infrastructure split is the heart of the policy.
 *
 * <h2>Verdict semantics</h2>
 * <ul>
 *   <li><b>BENIGN</b> — the judge said the content is safe. Release
 *       to the rest of the pipeline (Tagger → Embedding → READY in
 *       M1-034 Stage 5). {@code post.status} STAYS {@code 'RAW'} per
 *       {@code docs/spec/schema.md} §Invariants Invariant 5 ("Posts
 *       in RAW with one or more stage-outcome flags already set
 *       resume from the next uncompleted stage; the per-stage flags
 *       are the durable cursor"). Quarantine rows for this post
 *       transition {@code PENDING → BENIGN_CLOSED} per
 *       {@code docs/spec/security.md} §Failure handling. Stage 1
 *       redactions are NOT lifted — only admin {@code /quarantine
 *       approve} (T2-G) lifts them.</li>
 *   <li><b>INJECTION</b>, <b>MALWARE</b>, <b>UNKNOWN</b> — the judge
 *       said the content is unsafe (UNKNOWN is treated as a soft
 *       INJECTION signal per {@code docs/spec/security.md} §Failure
 *       handling: "The judge model treating UNKNOWN as a soft
 *       injection signal is intentional: a degraded judge must
 *       never auto-release"). {@code post.status='QUARANTINED'};
 *       quarantine rows stay PENDING (no state-machine move). If the
 *       post carries NO PENDING quarantine row at verdict time —
 *       reachable when an admin approves/rejects the Stage 1 rows
 *       while the judge call is in flight — one whole-body
 *       {@code flagged_by='stage2'} PENDING row is inserted in the
 *       same transaction so the QUARANTINED post still enters the
 *       admin review queue (M1-739; same row shape as M1-738's
 *       re-eval re-hide insert). The
 *       T2-G re-eval queue reads
 *       {@code (status='QUARANTINED' AND stage2_done=true AND
 *       stage2_failed=false)} to find UNKNOWN posts eligible for
 *       another judgment attempt — that feed is set here, the
 *       periodic re-submitter itself is T2-G.</li>
 *   <li><b>INFRA_FAILURE</b> — the judge did NOT run (LLM
 *       unreachable, timeout, unparseable/schema-violating reply
 *       after retry). Distinct from any verdict because the threat
 *       profile differs: a verdict is what the judge said; an infra
 *       failure is whether the judge ran at all. The dispatch
 *       branches on {@code infochat.security.release-on-stage2-failure}:
 *       <ul>
 *         <li><b>true</b> — release with Stage 1 redactions retained;
 *             {@code post.stage2_failed=true}; {@code post.status}
 *             STAYS {@code 'RAW'} so Tagger/Embedding still run (the
 *             literal flip to {@code 'READY'} happens in M1-034
 *             Stage 5 per Invariant 5; the design wording
 *             {@code docs/design/04-security.md} §4.7 "post.status=READY"
 *             is shorthand for "enters the release path that ends at
 *             READY").</li>
 *         <li><b>false</b> — quarantine; {@code post.status='QUARANTINED'},
 *             {@code post.stage2_failed=true}; quarantine rows stay
 *             PENDING.</li>
 *       </ul>
 *       Either branch logs WARN with {@link #ERROR_CLASS_STAGE2_INFRA_FAILURE}.
 *       The throttled admin notifier (T2-G) coalesces on
 *       {@code (channel, error_class)}; landing the canonical
 *       string here lets that notifier pick the log line up
 *       without diff churn.</li>
 * </ul>
 *
 * <h2>Transactional shape</h2>
 * <p>Per-verdict writes (the {@code post} UPDATE and any
 * {@code quarantine} UPDATE) run in ONE transaction so a partial
 * commit cannot leave (a) {@code post.stage2_done=true} without
 * the corresponding quarantine transition (BENIGN case), or (b)
 * {@code post.status='QUARANTINED'} without the
 * {@code stage2_done=true} cursor that the rehydrator reads to
 * decide whether to re-enqueue. Uses the shared
 * {@link TransactionHelper#inTransaction} pattern; raw JDBC, no ORM.
 *
 * <h2>Original (pre-Stage-1) content is NEVER auto-released</h2>
 * <p>The BENIGN and infra-fail-release paths leave the
 * {@code [REDACTED:<id>]} placeholders in {@code post.body}.
 * The original spans live only in {@code quarantine.original_html}
 * (admin-only column per V10). The only path that lifts
 * redactions is {@code /quarantine approve} (T2-G) — out of scope
 * here. Per {@code docs/spec/security.md} §Ingest pipeline:
 * "Stage 1 is a coarse filter, not a complete defense. It exists
 * to ... provide a degraded mode (Stage-1-redacted-but-released)
 * when the judge can't run."
 */
@ApplicationScoped
public class Stage2VerdictHandler {

    /**
     * Canonical error_class string for the throttled admin notifier
     * (T2-G). All Stage 2 infrastructure-failure log lines use this
     * value so the future coalescer keys on a single string per
     * {@code docs/spec/security.md} §Failure handling.
     */
    public static final String ERROR_CLASS_STAGE2_INFRA_FAILURE = "stage2.infra_failure";

    private static final Logger LOG = Logger.getLogger(Stage2VerdictHandler.class);

    // Cumulative count of posts released through the Stage-2 fail-open
    // path (release-on-stage2-failure=true + INFRA_FAILURE), i.e. posts
    // that reached the pipeline with Stage 1 redactions only because the
    // judge could not run. The startup audit row records that the posture
    // is active; this counter records how often it actually fired, so an
    // operator can size the exposure. No metrics backend is wired in v1,
    // so the accessor is the status surface (see docs/design/07-deployment.md).
    private final AtomicLong releasedStage2FailedCount = new AtomicLong();

    @Inject
    DataSource dataSource;

    @Inject
    QuarantineNotifyEmitter quarantineNotifyEmitter;

    @Inject
    QuarantineDao quarantineDao;

    @ConfigProperty(name = "infochat.security.release-on-stage2-failure")
    boolean releaseOnStage2Failure;

    /**
     * Apply the Stage 2 outcome for one post. The {@code outcome}
     * carries either a parsed verdict (BENIGN / INJECTION / MALWARE
     * / UNKNOWN) or {@link Verdict#INFRA_FAILURE} when the retry
     * harness exhausted without a parseable reply.
     *
     * @param judgedBody the exact body the judge saw (the Stage 1
     *                   pre-redaction original). Read ONLY when a
     *                   non-BENIGN verdict hits a post with no PENDING
     *                   quarantine row and the M1-739
     *                   {@code flagged_by='stage2'} row is inserted —
     *                   the column is NOT NULL, so a verdict outcome
     *                   requires it non-null (the sole production
     *                   caller always has it). Ignored on the BENIGN
     *                   and INFRA_FAILURE paths; null there (the
     *                   judge never ran on INFRA_FAILURE, so there is
     *                   no judged body).
     */
    public void apply(UUID postId, Instant postFetchedAt, Verdict outcome, @Nullable String judgedBody) {
        switch (outcome) {
            case BENIGN -> applyBenign(postId, postFetchedAt);
            case INJECTION, MALWARE, UNKNOWN -> applyQuarantineVerdict(postId, postFetchedAt, outcome, judgedBody);
            case INFRA_FAILURE -> applyInfraFailure(postId, postFetchedAt);
        }
    }

    private void applyBenign(UUID postId, Instant postFetchedAt) {
        TransactionHelper.inTransaction(dataSource, "Stage2VerdictHandler", conn -> {
            updatePostStage2DoneRaw(conn, postId, postFetchedAt, /* stage2Failed */ false, "BENIGN");
            closeStage1QuarantineRowsAndEmit(conn, postId);
        });
        LOG.infof("Stage 2 verdict: BENIGN post_id=%s — released to Tagger/Embedding (stage2_done=true, status=RAW)",
            postId);
    }

    private void applyQuarantineVerdict(UUID postId, Instant postFetchedAt, Verdict verdict,
                                        @Nullable String judgedBody) {
        TransactionHelper.inTransaction(dataSource, "Stage2VerdictHandler", conn -> {
            // Lock the PENDING rows BEFORE the post UPDATE: reject_quarantine
            // and approve_quarantine take the same row locks, so the dedup
            // decision is serialized against an admin review racing this
            // verdict — a reject committing between a lock-free check and
            // this tx's commit could otherwise suppress the insert and strand
            // the post QUARANTINED with zero PENDING rows (redteam
            // M1-739-2026-08-01, low). Lock order quarantine→post matches
            // approve_quarantine's (row, then post UPDATE), so there is no
            // deadlock cycle with the post-row lock taken next.
            boolean pendingRowsExist = lockPendingQuarantineRows(conn, postId);
            updatePostQuarantined(conn, postId, postFetchedAt, /* stage2Failed */ false, verdict.name());
            if (!pendingRowsExist) {
                // M1-739: a non-BENIGN verdict with NO PENDING row leaves the
                // post QUARANTINED yet invisible to quarantine_review_view /
                // /quarantine list (the view projects quarantine rows only).
                // Reachable when an admin approves/rejects the Stage 1 rows
                // while the judge call is still in flight (the rows go PENDING
                // at the Stage 1 commit; the verdict write lands seconds-to-
                // minutes later under semaphore wait + retry backoff). Insert
                // the whole-body stage2 row — the same shape M1-738's re-eval
                // re-hide writes — in THIS transaction so the queue row
                // commits or rolls back together with the post UPDATE.
                quarantineDao.insertStage2Row(conn, postId, readPostUid(conn, postId, postFetchedAt),
                    postFetchedAt, "stage2_" + verdict.name().toLowerCase(Locale.ROOT),
                    // The insert branch is the ONLY reader of judgedBody and
                    // requires it non-null (apply()'s contract: a verdict
                    // outcome always carries the judged body; the sole
                    // production caller has it by construction).
                    // requireNonNull re-states that for the type system.
                    java.util.Objects.requireNonNull(judgedBody,
                        "Stage2VerdictHandler: verdict outcome without a judged body"));
            }
        });
        LOG.infof("Stage 2 verdict: %s post_id=%s — quarantined (stage2_done=true, status=QUARANTINED)",
            verdict, postId);
    }

    private void applyInfraFailure(UUID postId, Instant postFetchedAt) {
        if (releaseOnStage2Failure) {
            TransactionHelper.inTransaction(dataSource, "Stage2VerdictHandler", conn ->
                updatePostStage2DoneRaw(conn, postId, postFetchedAt, /* stage2Failed */ true, /* stage2Verdict */ null));
            // Count only after the release commits — the counter measures
            // posts that actually entered the pipeline fail-open, not
            // attempts that rolled back.
            releasedStage2FailedCount.incrementAndGet();
            LOG.warnf("Stage 2 infrastructure failure (error_class=%s) post_id=%s — released with Stage 1 redactions "
                    + "(stage2_done=true, stage2_failed=true, status=RAW); release-on-stage2-failure=true",
                ERROR_CLASS_STAGE2_INFRA_FAILURE, postId);
        } else {
            TransactionHelper.inTransaction(dataSource, "Stage2VerdictHandler", conn ->
                updatePostQuarantined(conn, postId, postFetchedAt, /* stage2Failed */ true, /* stage2Verdict */ null));
            LOG.warnf("Stage 2 infrastructure failure (error_class=%s) post_id=%s — quarantined "
                    + "(stage2_done=true, stage2_failed=true, status=QUARANTINED); release-on-stage2-failure=false",
                ERROR_CLASS_STAGE2_INFRA_FAILURE, postId);
        }
    }

    /**
     * Number of posts released through the Stage-2 fail-open path
     * (release-on-stage2-failure=true + INFRA_FAILURE) since process
     * start. The operator-visible counter complementing the boot-time
     * {@code STARTUP_RELEASE_ON_STAGE2_FAILURE} audit row: the row
     * says the posture is armed, this says how often it fired.
     */
    public long releasedStage2FailedCount() {
        return releasedStage2FailedCount.get();
    }

    /**
     * UPDATE post SET stage2_done=TRUE [, stage2_failed=TRUE]
     * [, stage2_verdict=?] — the RAW-retained release path. Used by
     * BENIGN and the release-on-stage2-failure=true infra-failure
     * branch. Status stays RAW because Tagger and Embedding still
     * need to run (Invariant 5 — the literal flip to READY is M1-034
     * Stage 5). The verdict write is folded into this UPDATE rather
     * than issued as a second statement on the same (id, fetched_at)
     * row; {@code COALESCE(?, stage2_verdict)} leaves the column
     * untouched when {@code stage2Verdict} is null (the infra-failure
     * branch, where the judge produced no verdict).
     */
    private static void updatePostStage2DoneRaw(Connection conn, UUID postId, Instant postFetchedAt,
                                                boolean stage2Failed, @Nullable String stage2Verdict)
            throws SQLException {
        final String sql =
            "UPDATE post SET stage2_done = TRUE, stage2_failed = ?, "
                + "       stage2_verdict = COALESCE(?, stage2_verdict) "
                + "WHERE id = ? AND fetched_at = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, stage2Failed);
            ps.setString(2, stage2Verdict);
            ps.setObject(3, postId);
            ps.setTimestamp(4, Timestamp.from(postFetchedAt));
            ps.executeUpdate();
        }
    }

    /**
     * UPDATE post SET status='QUARANTINED', stage2_done=TRUE
     * [, stage2_failed=TRUE] [, stage2_verdict=?]. Used by INJECTION /
     * MALWARE / UNKNOWN verdicts and the release-on-stage2-failure=false
     * infra-failure branch. {@code status_changed_at} is advanced so the
     * future NEEDS_REVIEW transition's NOTIFY cursor (M2 quarantine_review
     * listener) sees the new state. The verdict write is folded into this
     * UPDATE rather than issued as a second statement on the same
     * (id, fetched_at) row; {@code COALESCE(?, stage2_verdict)} leaves the
     * column untouched when {@code stage2Verdict} is null (the infra-failure
     * branch, where the judge produced no verdict).
     */
    private static void updatePostQuarantined(Connection conn, UUID postId, Instant postFetchedAt,
                                              boolean stage2Failed, @Nullable String stage2Verdict)
            throws SQLException {
        final String sql =
            "UPDATE post SET status = 'QUARANTINED', stage2_done = TRUE, stage2_failed = ?, "
                + "       stage2_verdict = COALESCE(?, stage2_verdict), status_changed_at = now() "
                + "WHERE id = ? AND fetched_at = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, stage2Failed);
            ps.setString(2, stage2Verdict);
            ps.setObject(3, postId);
            ps.setTimestamp(4, Timestamp.from(postFetchedAt));
            ps.executeUpdate();
        }
    }

    /**
     * The M1-739 dedup predicate: true when the post carries ANY
     * PENDING quarantine row. A PENDING row already places the post in
     * the admin review queue, so a second row would only duplicate the
     * queue entry — insert nothing. A BENIGN_CLOSED / APPROVED /
     * REJECTED row is closed history and does NOT suppress the insert:
     * a fresh non-BENIGN judgment needs a fresh review row (the same
     * reasoning as M1-738's re-announce-count==0 gate).
     *
     * <p>{@code FOR UPDATE} is load-bearing (redteam M1-739-2026-08-01,
     * low): it locks the found rows until this transaction commits, so
     * a concurrent {@code reject_quarantine} / {@code approve_quarantine}
     * — both take the same row locks — cannot commit between the check
     * and the verdict commit and flip the basis of the decision. A
     * zero-row result locks nothing, and no new PENDING row can appear
     * mid-verdict: the only PENDING-insert writers are Stage 1
     * (committed before Stage 2 runs) and the re-eval job (cannot
     * enumerate a {@code stage2_done=false} post).
     */
    private static boolean lockPendingQuarantineRows(Connection conn, UUID postId) throws SQLException {
        final String sql =
            "SELECT 1 FROM quarantine WHERE post_id = ? AND status = 'PENDING' FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Read the post's {@code uid} for the quarantine row's denormalized
     * {@code post_uid} (the partition-drop survival denormalization per
     * {@code docs/design/02-schema.md} §2.5.1). Read on the verdict
     * transaction's own connection so it is consistent with the post
     * UPDATE the insert groups with.
     */
    private static String readPostUid(Connection conn, UUID postId, Instant postFetchedAt)
            throws SQLException {
        final String sql = "SELECT uid FROM post WHERE id = ? AND fetched_at = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, postId);
            ps.setTimestamp(2, Timestamp.from(postFetchedAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    /**
     * Transition the Stage 1 PENDING quarantine rows to
     * BENIGN_CLOSED on a BENIGN verdict and emit one
     * quarantine_review NOTIFY per row this verdict transitioned.
     * Only Stage 1 rows are touched ({@code flagged_by='stage1'});
     * a future Stage 2-written quarantine row (if any — none in M1)
     * is filtered out. The {@code WHERE status='PENDING'} predicate
     * is the idempotency guard: a re-enqueue that re-runs Stage 2
     * sees BENIGN_CLOSED rows and the UPDATE is a no-op (consistent
     * with Invariant 5's "stage-flags are the durable cursor").
     * UPDATE…RETURNING scopes the emit to exactly the rows closed
     * HERE — rows closed by an earlier verdict already had their
     * NOTIFY and must not re-fire.
     */
    private void closeStage1QuarantineRowsAndEmit(Connection conn, UUID postId) throws SQLException {
        final String sql =
            "UPDATE quarantine SET status = 'BENIGN_CLOSED', updated_at = now() "
                + "WHERE post_id = ? AND flagged_by = 'stage1' AND status = 'PENDING' "
                + "RETURNING id";
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

    /**
     * The closed set of Stage 2 outcomes the verdict handler
     * dispatches on. {@link #INFRA_FAILURE} is intentionally
     * NOT a verdict — it's the "judge didn't run" signal — but
     * unifying both in one enum keeps the switch expression
     * exhaustive and avoids a second dispatch method.
     */
    public enum Verdict {
        BENIGN,
        INJECTION,
        MALWARE,
        UNKNOWN,
        INFRA_FAILURE
    }
}
