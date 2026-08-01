package app.zcat.infochat.collector.eval.stage1;

import app.zcat.infochat.collector.notify.QuarantineNotifyEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * The sole production-side write path to the {@code quarantine}
 * table in M1. Every Stage 1 regex hit lands one row via this DAO;
 * every Stage 1 watchdog abort lands one whole-body row via this
 * DAO. The future Stage 2 (M1-033) BENIGN-verdict UPDATE on a prior
 * Stage 1 row also routes through this DAO. The re-evaluation job's
 * re-hide of a row-less post lands one whole-body
 * {@code flagged_by='stage2'} row via {@link #insertStage2Row}
 * (M1-738); EVERY first-pass non-BENIGN verdict lands the same row
 * via the same method (unconditional since M1-742 — M1-739's
 * "no PENDING row" dedup predicate is gone).
 *
 * <h2>Why a dedicated DAO</h2>
 * <p>Centralizing the INSERT/UPDATE shape here means the reviewer's
 * {@code grep -rE 'INSERT INTO quarantine'} negative-space check can
 * find every write at a single file path. The M2 admin commands
 * write through the {@code approve_quarantine} /
 * {@code reject_quarantine} stored procedures from
 * {@code docs/design/02-schema.md} §2.5.2 (not landed in M1's V10);
 * those procedures are the SECOND write path, not the first.
 *
 * <h2>Caller-supplied {@link Connection}</h2>
 * <p>The DAO is connection-passive: the caller owns the
 * {@link Connection} so the insert and the parent
 * {@code post} UPDATE can run inside one transaction. Per Invariant 5,
 * an in-flight Stage 1 evaluation is durable only via
 * {@code post.stage1_done=true}; if the quarantine insert committed
 * separately from the post UPDATE, a crash between them would orphan
 * the quarantine row and let the outbox rehydrator re-enqueue the
 * post, producing duplicate quarantine rows on re-run.
 *
 * <h2>Column shape</h2>
 * <p>One row per Stage 1 match: {@code flagged_by='stage1'},
 * {@code status='PENDING'}, {@code placeholder_id=<the id woven
 * into post.body>}, {@code original_html=<the verbatim matched
 * span>}, {@code rule_id=<the matched pattern's stable id>},
 * {@code span_start}/{@code span_end} as Java char (UTF-16) offsets
 * in the original body, {@code post_id}/{@code post_uid}/{@code
 * post_fetched_at} locating the parent post for the partition-aware
 * lookup that survives the post's eventual partition drop (the
 * denormalization is load-bearing per
 * {@code docs/design/02-schema.md} §2.5.1 comment "denormalized for
 * survival past partition drop").
 *
 * <h2>{@code original_html} is the audit truth</h2>
 * <p>The DAO writes the verbatim span (or, on a watchdog abort, the
 * whole unredacted normalized body) to {@code original_html}. The
 * Provider role has NO SELECT on this column — the
 * {@code quarantine_review_view} from V10 omits it. The original is
 * admin-only.
 */
@ApplicationScoped
public class QuarantineDao {

    @Inject
    QuarantineNotifyEmitter quarantineNotifyEmitter;

    /**
     * INSERT one quarantine row on the caller's {@link Connection}
     * and emit the PENDING {@code quarantine_review} NOTIFY for it
     * in the same transaction (the spec's "fires on PENDING insert"
     * contract — the NOTIFY commits or rolls back together with the
     * row). The caller is responsible for commit/rollback so the
     * insert groups atomically with the parent {@code post} UPDATE.
     */
    public void insert(Connection conn, QuarantineRow row) {
        final String sql =
            "INSERT INTO quarantine ("
                + "  post_id, post_uid, post_fetched_at,"
                + "  flagged_by, rule_id, span_start, span_end,"
                + "  original_html, placeholder_id, status"
                + ") VALUES ("
                + "  ?, ?, ?, 'stage1', ?, ?, ?, ?, ?, 'PENDING'"
                + ") RETURNING id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, row.postId());
            ps.setString(2, row.postUid());
            ps.setTimestamp(3, Timestamp.from(row.postFetchedAt()));
            ps.setString(4, row.ruleId());
            ps.setInt(5, row.spanStart());
            ps.setInt(6, row.spanEnd());
            ps.setString(7, row.originalHtml());
            ps.setString(8, row.placeholderId());
            UUID quarantineId;
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                quarantineId = (UUID) rs.getObject(1);
            }
            quarantineNotifyEmitter.emit(conn, QuarantineNotifyEmitter.TargetKind.QUARANTINE,
                quarantineId, QuarantineNotifyEmitter.NewStatus.PENDING);
        } catch (SQLException e) {
            throw new IllegalStateException(
                "QuarantineDao.insert: INSERT INTO quarantine failed for post_id="
                    + row.postId() + " rule_id=" + row.ruleId(), e);
        }
    }

    /**
     * INSERT one whole-body quarantine row for a Stage 2 non-BENIGN
     * judgment ({@code flagged_by='stage2'}, {@code status='PENDING'})
     * and emit the same PENDING {@code quarantine_review} NOTIFY the
     * Stage 1 insert emits, on the caller's {@link Connection} so the
     * insert commits or rolls back together with the parent
     * {@code post} UPDATE (the same atomicity rule
     * {@link #insert} documents for Stage 1). Two callers:
     * the re-evaluation job's re-hide of a row-less post (M1-738) and
     * {@code Stage2VerdictHandler}'s first-pass non-BENIGN verdict on
     * a post with no PENDING row (M1-739).
     *
     * <p>Why this row exists: {@code quarantine_review_view} (V10)
     * projects {@code quarantine} rows only, so a post that reaches
     * QUARANTINED with no PENDING row — released READY during a Stage
     * 2 outage and later re-hidden (M1-738), or stripped of its Stage
     * 1 rows by an admin racing an in-flight first-pass verdict
     * (M1-739) — would otherwise never enter the admin review queue,
     * contradicting
     * {@code docs/spec/security.md} §Quarantine workflow's "Every
     * Stage 1 or Stage 2 hit creates a quarantine row" and its
     * "stays QUARANTINED until admin review."
     *
     * <p>Row shape mirrors the Stage 1 fail-closed whole-body rows
     * ({@code regex_timeout} / {@code match_overflow} /
     * {@code sanitizer_exception}): the span covers the entire judged
     * body and {@code original_html} holds it verbatim. Unlike those
     * rows the {@code placeholder_id} is NOT woven into
     * {@code post.body} — neither Stage 2 path touches the body — so
     * the id exists only to satisfy the NOT NULL column; nothing ever
     * splices it.
     *
     * @param ruleId        a stable per-source id in the
     *                      {@code reeval_<verdict>} (re-eval) or
     *                      {@code stage2_<verdict>} (first-pass) shape,
     *                      supplied by the caller.
     * @param originalHtml  the exact body the Stage 2 judge saw (the
     *                      reconstructed original on the re-eval path,
     *                      the Stage 1 pre-redaction original on the
     *                      first-pass path); never null.
     */
    public void insertStage2Row(Connection conn, UUID postId, String postUid,
                                Instant postFetchedAt, String ruleId, String originalHtml) {
        final String sql =
            "INSERT INTO quarantine ("
                + "  post_id, post_uid, post_fetched_at,"
                + "  flagged_by, rule_id, span_start, span_end,"
                + "  original_html, placeholder_id, status"
                + ") VALUES ("
                + "  ?, ?, ?, 'stage2', ?, 0, ?, ?, ?, 'PENDING'"
                + ") RETURNING id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, postId);
            ps.setString(2, postUid);
            ps.setTimestamp(3, Timestamp.from(postFetchedAt));
            ps.setString(4, ruleId);
            ps.setInt(5, originalHtml.length());
            ps.setString(6, originalHtml);
            ps.setString(7, PlaceholderIds.next());
            UUID quarantineId;
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                quarantineId = (UUID) rs.getObject(1);
            }
            quarantineNotifyEmitter.emit(conn, QuarantineNotifyEmitter.TargetKind.QUARANTINE,
                quarantineId, QuarantineNotifyEmitter.NewStatus.PENDING);
        } catch (SQLException e) {
            throw new IllegalStateException(
                "QuarantineDao.insertStage2Row: INSERT INTO quarantine failed for post_id="
                    + postId + " rule_id=" + ruleId, e);
        }
    }

    /**
     * One quarantine row's worth of fields. Constructed by
     * {@link Stage1Pipeline} per regex hit and per watchdog abort,
     * passed verbatim into {@link #insert(Connection, QuarantineRow)}.
     *
     * @param postId         the parent post's {@code id}.
     * @param postUid        the parent post's {@code uid} (denormalized
     *                       so the row survives partition drop).
     * @param postFetchedAt  the parent post's {@code fetched_at}
     *                       (partition locator; no FK).
     * @param ruleId         the Stage1RegexSet rule id (or
     *                       {@code "regex_timeout"} on a watchdog abort).
     * @param spanStart      Java char (UTF-16) offset of the matched span
     *                       in the original body; {@code 0} on a watchdog
     *                       abort (whole-body span).
     * @param spanEnd        Java char (UTF-16) offset (exclusive) of the
     *                       matched span end; {@code body.length()} on a
     *                       watchdog abort.
     * @param originalHtml   the verbatim matched span (or the whole
     *                       unredacted normalized body on a watchdog
     *                       abort). Never null per the V10 schema.
     * @param placeholderId  the {@code <id>} woven into
     *                       {@code post.body} as
     *                       {@code [REDACTED:<id>]}.
     */
    public record QuarantineRow(
        UUID postId,
        String postUid,
        Instant postFetchedAt,
        String ruleId,
        int spanStart,
        int spanEnd,
        String originalHtml,
        String placeholderId
    ) {
    }
}
