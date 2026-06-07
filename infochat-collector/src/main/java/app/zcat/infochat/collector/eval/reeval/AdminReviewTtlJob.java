package app.zcat.infochat.collector.eval.reeval;

import app.zcat.infochat.collector.eval.TransactionHelper;
import app.zcat.infochat.collector.notify.QuarantineNotifyEmitter;
import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.log.SafeLog;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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
import java.util.List;
import java.util.UUID;

/**
 * Enforces Invariant 6: admin-review TTL auto-reject. A PENDING
 * quarantine row aged past the profile-driven TTL transitions to
 * REJECTED; the attached NEEDS_REVIEW post transitions to
 * QUARANTINED; the placeholder becomes permanent. No admin
 * notification fires (the notifier already paged on NEEDS_REVIEW
 * entry).
 *
 * <p>BENIGN_CLOSED rows are NOT subject to the TTL — they stay
 * BENIGN_CLOSED with no transition.
 */
@ApplicationScoped
public class AdminReviewTtlJob {

    private static final Logger LOG = LoggerFactory.getLogger(AdminReviewTtlJob.class);

    @Inject
    DataSource dataSource;

    @Inject
    QuarantineNotifyEmitter quarantineNotifyEmitter;

    @Inject
    AuditLogWriter auditLogWriter;

    @ConfigProperty(name = "infochat.reeval.admin-review-ttl")
    Duration adminReviewTtl;

    @ConfigProperty(name = "infochat.reeval.ttl-batch-size", defaultValue = "32")
    int batchSize;

    @Scheduled(every = "{infochat.reeval.ttl-poll-interval}")
    public void onTick() {
        List<TtlCandidate> candidates;
        try {
            candidates = enumerateExpired();
        } catch (SQLException e) {
            // SafeLog, never the raw Throwable (docs/spec/security.md
            // §Secrets handling — User content in exceptions).
            SafeLog.warn(LOG, "AdminReviewTtlJob: failed to enumerate expired rows; skipping tick", e);
            return;
        }
        for (TtlCandidate candidate : candidates) {
            try {
                rejectExpired(candidate);
            } catch (RuntimeException e) {
                SafeLog.warn(LOG, "AdminReviewTtlJob: failed to reject quarantine_id="
                    + candidate.quarantineId() + "; will retry next tick", e);
            }
        }
    }

    List<TtlCandidate> enumerateExpired() throws SQLException {
        Instant cutoff = Instant.now().minus(adminReviewTtl);
        // No join on post: q.post_fetched_at is the denormalized copy of
        // p.fetched_at carried so TTL processing survives post-partition
        // drops — joining would silently exempt a dropped partition's
        // PENDING rows from Invariant 6's auto-reject. The post-side
        // UPDATE in rejectExpired tolerates the missing row (0-row
        // no-op).
        final String sql =
            "SELECT q.id, q.post_id, q.post_fetched_at "
                + "FROM quarantine q "
                + "WHERE q.status = 'PENDING' "
                + "  AND q.flagged_at <= ? "
                + "ORDER BY q.flagged_at "
                + "LIMIT ?";
        List<TtlCandidate> candidates = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            ps.setInt(2, batchSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    candidates.add(new TtlCandidate(
                        (UUID) rs.getObject(1),
                        (UUID) rs.getObject(2),
                        rs.getTimestamp(3).toInstant()));
                }
            }
        }
        return candidates;
    }

    void rejectExpired(TtlCandidate candidate) {
        TransactionHelper.inTransaction(dataSource, "AdminReviewTtlJob.reject", conn -> {
            // Transition quarantine row PENDING→REJECTED.
            final String rejectSql =
                "UPDATE quarantine SET status = 'REJECTED', updated_at = now() "
                    + "WHERE id = ? AND status = 'PENDING'";
            try (PreparedStatement ps = conn.prepareStatement(rejectSql)) {
                ps.setObject(1, candidate.quarantineId());
                int updated = ps.executeUpdate();
                if (updated == 0) {
                    return;
                }
            }
            // Transition post NEEDS_REVIEW→QUARANTINED (placeholder
            // becomes permanent).
            final String postSql =
                "UPDATE post SET status = 'QUARANTINED', status_changed_at = now() "
                    + "WHERE id = ? AND fetched_at = ? AND status = 'NEEDS_REVIEW'";
            try (PreparedStatement ps = conn.prepareStatement(postSql)) {
                ps.setObject(1, candidate.postId());
                ps.setTimestamp(2, Timestamp.from(candidate.postFetchedAt()));
                ps.executeUpdate();
            }
            quarantineNotifyEmitter.emit(conn, QuarantineNotifyEmitter.TargetKind.QUARANTINE,
                candidate.quarantineId(), QuarantineNotifyEmitter.NewStatus.REJECTED);
            RedactionHook.AuditRow auditRow = RedactionHook.AuditRow.builder()
                .actorContactId("admin_review_ttl_job")
                .action(AuditAction.QUARANTINE_TTL_REJECT)
                .targetKind("quarantine")
                .targetId(candidate.quarantineId().toString())
                .detailsJson("{\"post_id\":\"" + candidate.postId() + "\"}")
                .build();
            auditLogWriter.write(conn, auditRow);
        });
        LOG.info("AdminReviewTtlJob: TTL expired for quarantine_id={} — auto-rejected",
            candidate.quarantineId());
    }

    record TtlCandidate(UUID quarantineId, UUID postId, Instant postFetchedAt) {
    }
}
