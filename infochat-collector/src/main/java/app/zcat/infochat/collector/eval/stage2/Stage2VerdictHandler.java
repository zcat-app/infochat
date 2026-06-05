package app.zcat.infochat.collector.eval.stage2;

import app.zcat.infochat.collector.eval.TransactionHelper;
import app.zcat.infochat.collector.notify.QuarantineNotifyEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

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
 *       quarantine rows stay PENDING (no state-machine move). The
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

    @Inject
    DataSource dataSource;

    @Inject
    QuarantineNotifyEmitter quarantineNotifyEmitter;

    @ConfigProperty(name = "infochat.security.release-on-stage2-failure")
    boolean releaseOnStage2Failure;

    /**
     * Apply the Stage 2 outcome for one post. The {@code outcome}
     * carries either a parsed verdict (BENIGN / INJECTION / MALWARE
     * / UNKNOWN) or {@link Verdict#INFRA_FAILURE} when the retry
     * harness exhausted without a parseable reply.
     */
    public void apply(UUID postId, Instant postFetchedAt, Verdict outcome) {
        switch (outcome) {
            case BENIGN -> applyBenign(postId, postFetchedAt);
            case INJECTION, MALWARE, UNKNOWN -> applyQuarantineVerdict(postId, postFetchedAt, outcome);
            case INFRA_FAILURE -> applyInfraFailure(postId, postFetchedAt);
        }
    }

    private void applyBenign(UUID postId, Instant postFetchedAt) {
        TransactionHelper.inTransaction(dataSource, "Stage2VerdictHandler", conn -> {
            updatePostStage2DoneRaw(conn, postId, postFetchedAt, /* stage2Failed */ false);
            setStage2Verdict(conn, postId, postFetchedAt, "BENIGN");
            updateStage1QuarantineRowsToBenignClosed(conn, postId);
            emitQuarantineNotifyForClosedRows(conn, postId);
        });
        LOG.infof("Stage 2 verdict: BENIGN post_id=%s — released to Tagger/Embedding (stage2_done=true, status=RAW)",
            postId);
    }

    private void applyQuarantineVerdict(UUID postId, Instant postFetchedAt, Verdict verdict) {
        TransactionHelper.inTransaction(dataSource, "Stage2VerdictHandler", conn -> {
            updatePostQuarantined(conn, postId, postFetchedAt, /* stage2Failed */ false);
            setStage2Verdict(conn, postId, postFetchedAt, verdict.name());
        });
        LOG.infof("Stage 2 verdict: %s post_id=%s — quarantined (stage2_done=true, status=QUARANTINED)",
            verdict, postId);
    }

    private void applyInfraFailure(UUID postId, Instant postFetchedAt) {
        if (releaseOnStage2Failure) {
            TransactionHelper.inTransaction(dataSource, "Stage2VerdictHandler", conn ->
                updatePostStage2DoneRaw(conn, postId, postFetchedAt, /* stage2Failed */ true));
            LOG.warnf("Stage 2 infrastructure failure (error_class=%s) post_id=%s — released with Stage 1 redactions "
                    + "(stage2_done=true, stage2_failed=true, status=RAW); release-on-stage2-failure=true",
                ERROR_CLASS_STAGE2_INFRA_FAILURE, postId);
        } else {
            TransactionHelper.inTransaction(dataSource, "Stage2VerdictHandler", conn ->
                updatePostQuarantined(conn, postId, postFetchedAt, /* stage2Failed */ true));
            LOG.warnf("Stage 2 infrastructure failure (error_class=%s) post_id=%s — quarantined "
                    + "(stage2_done=true, stage2_failed=true, status=QUARANTINED); release-on-stage2-failure=false",
                ERROR_CLASS_STAGE2_INFRA_FAILURE, postId);
        }
    }

    /**
     * UPDATE post SET stage2_done=TRUE [, stage2_failed=TRUE] — the
     * RAW-retained release path. Used by BENIGN and the
     * release-on-stage2-failure=true infra-failure branch. Status
     * stays RAW because Tagger and Embedding still need to run
     * (Invariant 5 — the literal flip to READY is M1-034 Stage 5).
     */
    private static void updatePostStage2DoneRaw(Connection conn, UUID postId, Instant postFetchedAt,
                                                boolean stage2Failed) throws SQLException {
        final String sql =
            "UPDATE post SET stage2_done = TRUE, stage2_failed = ? "
                + "WHERE id = ? AND fetched_at = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, stage2Failed);
            ps.setObject(2, postId);
            ps.setTimestamp(3, Timestamp.from(postFetchedAt));
            ps.executeUpdate();
        }
    }

    /**
     * UPDATE post SET status='QUARANTINED', stage2_done=TRUE
     * [, stage2_failed=TRUE]. Used by INJECTION / MALWARE / UNKNOWN
     * verdicts and the release-on-stage2-failure=false infra-failure
     * branch. {@code status_changed_at} is advanced so the future
     * NEEDS_REVIEW transition's NOTIFY cursor (M2 quarantine_review
     * listener) sees the new state.
     */
    private static void updatePostQuarantined(Connection conn, UUID postId, Instant postFetchedAt,
                                              boolean stage2Failed) throws SQLException {
        final String sql =
            "UPDATE post SET status = 'QUARANTINED', stage2_done = TRUE, stage2_failed = ?, "
                + "       status_changed_at = now() "
                + "WHERE id = ? AND fetched_at = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, stage2Failed);
            ps.setObject(2, postId);
            ps.setTimestamp(3, Timestamp.from(postFetchedAt));
            ps.executeUpdate();
        }
    }

    private static void setStage2Verdict(Connection conn, UUID postId, Instant postFetchedAt,
                                          String verdict) throws SQLException {
        final String sql = "UPDATE post SET stage2_verdict = ? WHERE id = ? AND fetched_at = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, verdict);
            ps.setObject(2, postId);
            ps.setTimestamp(3, Timestamp.from(postFetchedAt));
            ps.executeUpdate();
        }
    }

    /**
     * Transition the Stage 1 PENDING quarantine rows to
     * BENIGN_CLOSED on a BENIGN verdict. Only Stage 1 rows are
     * touched ({@code flagged_by='stage1'}); a future Stage 2-
     * written quarantine row (if any — none in M1) is filtered out.
     * The {@code WHERE status='PENDING'} predicate is the
     * idempotency guard: a re-enqueue that re-runs Stage 2 sees
     * BENIGN_CLOSED rows and the UPDATE is a no-op (consistent
     * with Invariant 5's "stage-flags are the durable cursor").
     */
    private static void updateStage1QuarantineRowsToBenignClosed(Connection conn, UUID postId) throws SQLException {
        final String sql =
            "UPDATE quarantine SET status = 'BENIGN_CLOSED', updated_at = now() "
                + "WHERE post_id = ? AND flagged_by = 'stage1' AND status = 'PENDING'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, postId);
            ps.executeUpdate();
        }
    }

    /**
     * Emit quarantine_review NOTIFY for each BENIGN_CLOSED quarantine
     * row belonging to this post. Called after the BENIGN verdict
     * transitions Stage 1 rows from PENDING to BENIGN_CLOSED.
     */
    private void emitQuarantineNotifyForClosedRows(Connection conn, UUID postId) throws SQLException {
        final String sql =
            "SELECT id FROM quarantine WHERE post_id = ? AND status = 'BENIGN_CLOSED'";
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
