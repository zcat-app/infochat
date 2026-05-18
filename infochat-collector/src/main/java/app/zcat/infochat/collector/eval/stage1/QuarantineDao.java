package app.zcat.infochat.collector.eval.stage1;

import jakarta.enterprise.context.ApplicationScoped;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * The sole production-side write path to the {@code quarantine}
 * table in M1. Every Stage 1 regex hit lands one row via this DAO;
 * every Stage 1 watchdog abort lands one whole-body row via this
 * DAO. The future Stage 2 (M1-033) BENIGN-verdict UPDATE on a prior
 * Stage 1 row also routes through this DAO.
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
 * {@code span_start}/{@code span_end} as byte offsets in the
 * original body, {@code post_id}/{@code post_uid}/{@code
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

    /**
     * INSERT one quarantine row on the caller's {@link Connection}.
     * The caller is responsible for commit/rollback so the insert
     * groups atomically with the parent {@code post} UPDATE.
     */
    public void insert(Connection conn, QuarantineRow row) {
        final String sql =
            "INSERT INTO quarantine ("
                + "  post_id, post_uid, post_fetched_at,"
                + "  flagged_by, rule_id, span_start, span_end,"
                + "  original_html, placeholder_id, status"
                + ") VALUES ("
                + "  ?, ?, ?, 'stage1', ?, ?, ?, ?, ?, 'PENDING'"
                + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, row.postId());
            ps.setString(2, row.postUid());
            ps.setTimestamp(3, Timestamp.from(row.postFetchedAt()));
            ps.setString(4, row.ruleId());
            ps.setInt(5, row.spanStart());
            ps.setInt(6, row.spanEnd());
            ps.setString(7, row.originalHtml());
            ps.setString(8, row.placeholderId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(
                "QuarantineDao.insert: INSERT INTO quarantine failed for post_id="
                    + row.postId() + " rule_id=" + row.ruleId(), e);
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
     * @param spanStart      byte offset of the matched span in the
     *                       original body; {@code 0} on a watchdog abort
     *                       (whole-body span).
     * @param spanEnd        byte offset (exclusive) of the matched
     *                       span end; {@code body.length()} on a
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
