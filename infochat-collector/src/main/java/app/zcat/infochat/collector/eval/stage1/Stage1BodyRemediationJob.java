package app.zcat.infochat.collector.eval.stage1;

import app.zcat.infochat.collector.eval.TransactionHelper;
import app.zcat.infochat.core.log.SafeLog;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.text.StringEscapeUtils;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-shot-draining remediation for {@code post.body} and
 * {@code saved_post.body} rows written before M1-784 made Stage 1 store
 * plain text (M1-786): those rows still carry the old OWASP
 * serializer's output — allowlisted tags standing, punctuation
 * rewritten as numeric entities — and no other writer ever revisits
 * them. {@code /saved} renders the permanent snapshot no retention
 * drop reaches, so the residue is user-visible until converted.
 *
 * <p>Each tick drains a bounded batch of rows whose
 * {@code body_remediated_at} marker (V79) is NULL and runs the stored
 * body through {@link Stage1Pipeline#convertAndRescan} — the SAME
 * convert-plus-scan path the live pipeline uses, never a second
 * decoder (P11) — then writes the converted body and stamps the
 * marker in one transaction (P14: at-most-once is a schema property,
 * not operator memory). The conversion decodes, so the rescan is what
 * keeps a revealed payload out of the stored body (P9). A row whose
 * conversion is a no-op still gets its marker. {@code post.status},
 * {@code ready_at} and every per-stage cursor flag are left alone —
 * this is a representation repair, not a re-evaluation.
 *
 * <p>Post rows are picked up only with {@code stage1_done = TRUE}: a
 * RAW row still awaiting Stage 1 belongs to the pipeline, which stamps
 * the marker itself when it writes the plain-text body. Fresh
 * {@code saved_post} snapshots never enter the conversion batch either:
 * {@link #propagateSnapshotStamps} stamps any snapshot whose body
 * byte-matches an already-stamped {@code post.body} (a /save snapshot
 * copies the post's body at save time, so byte-equality certifies the
 * representation). That gate is load-bearing, not hygiene — the
 * unescape+parse conversion decodes one entity level deeper than the
 * pipeline did and would corrupt the escaped prose a new-format body
 * legitimately carries (the P10 pin in Stage1PipelineIT). A propagation
 * failure therefore skips the whole tick: converting a snapshot that
 * should have been stamped is the fail direction this job must not take.
 *
 * <p>A {@code saved_post} match cannot produce a quarantine row — the
 * snapshot carries no {@code post_id}/{@code post_fetched_at} locator,
 * and the underlying post is never touched from this path — so a
 * redacted snapshot is recorded by a WARN line naming the rule ids.
 */
@ApplicationScoped
public class Stage1BodyRemediationJob {

    private static final Logger LOG = LoggerFactory.getLogger(Stage1BodyRemediationJob.class);

    /** Ids of {@code [REDACTED:<id>]} markers already in a stored body. */
    private static final Pattern EXISTING_PLACEHOLDER_ID =
        Pattern.compile("\\[REDACTED:([A-Z2-7]{26})\\]");

    @Inject
    DataSource dataSource;

    @Inject
    Stage1Pipeline stage1Pipeline;

    @Inject
    QuarantineDao quarantineDao;

    @ConfigProperty(name = "infochat.security.stage1.remediation-batch-size")
    int batchSize;

    @Scheduled(every = "{infochat.security.stage1.remediation-poll-interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void onTick() {
        try {
            propagateSnapshotStamps();
        } catch (SQLException e) {
            // SafeLog, never the raw Throwable (docs/spec/security.md
            // §Secrets handling — User content in exceptions).
            SafeLog.warn(LOG, "Stage1BodyRemediationJob: stamp propagation failed; "
                + "skipping tick rather than converting snapshots that may be plain text", e);
            return;
        }
        List<PostCandidate> postBatch;
        List<SavedPostCandidate> savedPostBatch;
        try {
            postBatch = enumeratePostBatch();
            savedPostBatch = enumerateSavedPostBatch();
        } catch (SQLException e) {
            SafeLog.warn(LOG, "Stage1BodyRemediationJob: failed to enumerate batch; "
                + "skipping tick", e);
            return;
        }
        for (PostCandidate candidate : postBatch) {
            try {
                remediatePost(candidate);
            } catch (RuntimeException e) {
                // A tripping row keeps its old body and NULL marker and
                // is retried next tick — the fail-closed QUARANTINED
                // transition is the pipeline's, not this job's.
                SafeLog.warn(LOG, "Stage1BodyRemediationJob: failed to remediate post_id="
                    + candidate.postId() + "; will retry next tick", e);
            }
        }
        for (SavedPostCandidate candidate : savedPostBatch) {
            try {
                remediateSavedPost(candidate);
            } catch (RuntimeException e) {
                SafeLog.warn(LOG, "Stage1BodyRemediationJob: failed to remediate saved_post"
                    + " user_id=" + candidate.userId() + " post_uid=" + candidate.postUid()
                    + "; will retry next tick", e);
            }
        }
    }

    /**
     * Stamp every snapshot whose body byte-matches an already-stamped
     * {@code post.body}: a /save snapshot copies the post's body at
     * save time, so byte-equality certifies the representation without
     * a conversion. Single statement, so no explicit transaction; the
     * stamp's NULL-ness, never its value, gates pickup (§9 exemption).
     */
    void propagateSnapshotStamps() throws SQLException {
        final String sql =
            "UPDATE saved_post sp SET body_remediated_at = now() "
                + "WHERE sp.body_remediated_at IS NULL "
                + "  AND EXISTS (SELECT 1 FROM post p "
                + "              WHERE p.uid = sp.post_uid "
                + "                AND p.body = sp.body "
                + "                AND p.body_remediated_at IS NOT NULL)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    List<PostCandidate> enumeratePostBatch() throws SQLException {
        final String sql =
            "SELECT id, uid, fetched_at, body FROM post "
                + "WHERE body_remediated_at IS NULL "
                + "  AND stage1_done = TRUE "
                + "ORDER BY fetched_at "
                + "LIMIT ?";
        List<PostCandidate> batch = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, batchSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    batch.add(new PostCandidate(
                        (UUID) rs.getObject(1),
                        rs.getString(2),
                        rs.getTimestamp(3).toInstant(),
                        rs.getString(4)));
                }
            }
        }
        return batch;
    }

    List<SavedPostCandidate> enumerateSavedPostBatch() throws SQLException {
        final String sql =
            "SELECT user_id, post_uid, body FROM saved_post "
                + "WHERE body_remediated_at IS NULL "
                + "ORDER BY saved_at "
                + "LIMIT ?";
        List<SavedPostCandidate> batch = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, batchSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    batch.add(new SavedPostCandidate(
                        (UUID) rs.getObject(1),
                        rs.getString(2),
                        rs.getString(3)));
                }
            }
        }
        return batch;
    }

    /**
     * Convert one post row: one quarantine row per redaction plus the
     * body write in one transaction, the same consistency property the
     * pipeline's own success path keeps. The UPDATE is guarded on the
     * body the batch read — an {@code approve_quarantine} restore
     * landing mid-conversion rewrites the row, and blindly overwriting
     * it would discard the restore with no recovery (the stamp would
     * gate any retry). A 0-row update rolls the whole transaction back
     * so the next tick re-reads and converts the current body.
     */
    void remediatePost(PostCandidate candidate) {
        Stage1Pipeline.ConvertedBody converted = convert(candidate.body(),
            harvestProtectedPlaceholderIds(candidate.body()));
        TransactionHelper.inTransaction(dataSource, "Stage1BodyRemediationJob.post", conn -> {
            for (Stage1Pipeline.Redaction redaction : converted.redactions()) {
                quarantineDao.insert(conn, new QuarantineDao.QuarantineRow(
                    candidate.postId(), candidate.postUid(), candidate.postFetchedAt(),
                    redaction.ruleId(), redaction.start(), redaction.end(),
                    redaction.span(), redaction.placeholderId()));
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE post SET body = ?, body_remediated_at = now() "
                    + "WHERE id = ? AND fetched_at = ? "
                    + "AND body IS NOT DISTINCT FROM ?")) {
                ps.setString(1, converted.storedBody());
                ps.setObject(2, candidate.postId());
                ps.setTimestamp(3, Timestamp.from(candidate.postFetchedAt()));
                ps.setString(4, candidate.body());
                if (ps.executeUpdate() == 0) {
                    throw new IllegalStateException(
                        "Stage1BodyRemediationJob: post_id=" + candidate.postId()
                            + " changed under the job; rolled back, retry next tick");
                }
            }
        });
    }

    /**
     * Convert one snapshot the same way, minus the quarantine rows. The
     * UPDATE is guarded like the post path's: an unsave/re-save landing
     * between the batch read and the write re-INSERTs the row with the
     * current post body, and overwriting it with the stale conversion
     * would permanently strand outdated text behind the stamp. A 0-row
     * update rolls back so the next tick (propagation first, then
     * conversion) handles the current row.
     */
    void remediateSavedPost(SavedPostCandidate candidate) {
        Stage1Pipeline.ConvertedBody converted = convert(candidate.body(),
            harvestProtectedPlaceholderIds(candidate.body()));
        TransactionHelper.inTransaction(dataSource, "Stage1BodyRemediationJob.savedPost", conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE saved_post SET body = ?, body_remediated_at = now() "
                    + "WHERE user_id = ? AND post_uid = ? "
                    + "AND body IS NOT DISTINCT FROM ?")) {
                ps.setString(1, converted.storedBody());
                ps.setObject(2, candidate.userId());
                ps.setString(3, candidate.postUid());
                ps.setString(4, candidate.body());
                if (ps.executeUpdate() == 0) {
                    throw new IllegalStateException(
                        "Stage1BodyRemediationJob: saved_post user_id=" + candidate.userId()
                            + " post_uid=" + candidate.postUid()
                            + " changed under the job; rolled back, retry next tick");
                }
            }
        });
        if (!converted.redactions().isEmpty()) {
            List<String> ruleIds = new ArrayList<>(converted.redactions().size());
            for (Stage1Pipeline.Redaction redaction : converted.redactions()) {
                ruleIds.add(redaction.ruleId());
            }
            LOG.warn("Stage1BodyRemediationJob: redacted {} match(es) in saved_post user_id={} "
                    + "post_uid={} (rule_ids={}); no quarantine row — a snapshot carries no "
                    + "post locator",
                converted.redactions().size(), candidate.userId(), candidate.postUid(), ruleIds);
        }
    }

    /**
     * Run the pipeline's own convert-plus-scan over a stored body,
     * opening with the same entity pre-decode + normalize
     * {@link Stage1Pipeline#process} runs, so the job's output for a
     * given input equals the pipeline's (P11). A null body coerces to
     * empty (SQL deserialization boundary, same as the pipeline).
     */
    private Stage1Pipeline.ConvertedBody convert(@Nullable String storedBody,
                                                 List<String> protectedPlaceholderIds) {
        String safeBody = storedBody == null ? "" : storedBody;
        String normalized = Stage1Pipeline.unicodeNormalize(
            StringEscapeUtils.unescapeHtml4(safeBody));
        return stage1Pipeline.convertAndRescan(normalized, protectedPlaceholderIds);
    }

    private static List<String> harvestProtectedPlaceholderIds(@Nullable String body) {
        List<String> ids = new ArrayList<>();
        if (body == null) {
            return ids;
        }
        Matcher matcher = EXISTING_PLACEHOLDER_ID.matcher(body);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    record PostCandidate(UUID postId, String postUid, Instant postFetchedAt,
                         @Nullable String body) {
    }

    record SavedPostCandidate(UUID userId, String postUid, @Nullable String body) {
    }
}
