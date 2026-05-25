package app.zcat.infochat.provider.outbox;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Startup reconciler for the {@code quarantine_review} channel.
 * Mirrors {@link NewPostReconciler}: runs before the listener
 * ({@code @Priority(250)} vs the listener's 260) and scans
 * quarantine_review_view and the post table for events that arrived
 * while the Provider was down.
 *
 * <p>Unlike the live listener, the reconciler only advances the cursor
 * — it does not fire admin notifications for missed events. Admin
 * notifications are best-effort and the admin will see the quarantine
 * queue on the next {@code /quarantine list} invocation regardless.
 */
@Startup
@Priority(250)
@ApplicationScoped
public class QuarantineReviewReconciler {

    private static final Logger LOG = Logger.getLogger(QuarantineReviewReconciler.class);

    private static final String QUARANTINE_SCAN_SQL =
            "SELECT id, updated_at, status FROM quarantine_review_view "
                    + "WHERE (updated_at, 'quarantine', id::text) > (?, ?, ?) "
                    + "ORDER BY updated_at, id "
                    + "LIMIT ?";

    private static final String POST_SCAN_SQL =
            "SELECT id, status_changed_at FROM post "
                    + "WHERE status = 'NEEDS_REVIEW' "
                    + "  AND (status_changed_at, 'post', id::text) > (?, ?, ?) "
                    + "ORDER BY status_changed_at, id "
                    + "LIMIT ?";

    @Inject DataSource dataSource;
    @Inject ProviderStateDao providerStateDao;

    @Inject
    @ConfigProperty(name = "infochat.provider.catchup.quarantine-page-size", defaultValue = "500")
    int pageSize;

    private int caughtUpCount;
    private int pagesProcessed;

    @PostConstruct
    void onStartup() {
        try {
            runCatchUp();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "QuarantineReviewReconciler failed to complete catch-up scan", e);
        }
    }

    void runCatchUp() throws SQLException {
        caughtUpCount = 0;
        pagesProcessed = 0;

        Optional<ProviderStateDao.Cursor> maybeCursor =
                providerStateDao.readCursor(QuarantineReviewListener.CHANNEL);
        if (maybeCursor.isEmpty()) {
            throw new IllegalStateException(
                    "provider_state row for channel='" + QuarantineReviewListener.CHANNEL
                            + "' is missing — V21__quarantine_admin.sql INSERT did not apply");
        }
        ProviderStateDao.Cursor cursor = maybeCursor.get();
        Instant cursorHigh = cursor.cursorHigh();
        String cursorKind = cursor.cursorLowKind();
        String cursorId = cursor.cursorLowId();

        // Phase 1: quarantine events (all statuses — advance cursor past them)
        caughtUpCount += scanQuarantineEvents(cursorHigh, cursorKind, cursorId);

        // Re-read cursor after quarantine scan (it may have advanced)
        Optional<ProviderStateDao.Cursor> updated =
                providerStateDao.readCursor(QuarantineReviewListener.CHANNEL);
        if (updated.isPresent()) {
            cursorHigh = updated.get().cursorHigh();
            cursorKind = updated.get().cursorLowKind();
            cursorId = updated.get().cursorLowId();
        }

        // Phase 2: post NEEDS_REVIEW events
        caughtUpCount += scanPostEvents(cursorHigh, cursorKind, cursorId);

        LOG.infof("QuarantineReviewReconciler: caught up %d events in %d page(s)",
                caughtUpCount, pagesProcessed);
    }

    private int scanQuarantineEvents(Instant cursorHigh, String cursorKind,
                                     String cursorId) throws SQLException {
        int total = 0;
        Instant pagingHigh = cursorHigh;
        String pagingKind = cursorKind;
        String pagingId = cursorId;

        while (true) {
            int rowsInPage = 0;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(QUARANTINE_SCAN_SQL)) {
                ps.setTimestamp(1, Timestamp.from(pagingHigh));
                ps.setString(2, pagingKind);
                ps.setString(3, pagingId);
                ps.setInt(4, pageSize);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID id = rs.getObject("id", UUID.class);
                        Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
                        providerStateDao.advanceCursor(
                                QuarantineReviewListener.CHANNEL,
                                updatedAt, "quarantine", id.toString());
                        pagingHigh = updatedAt;
                        pagingKind = "quarantine";
                        pagingId = id.toString();
                        rowsInPage++;
                        total++;
                    }
                }
            }
            pagesProcessed++;
            if (rowsInPage < pageSize) break;
        }
        return total;
    }

    private int scanPostEvents(Instant cursorHigh, String cursorKind,
                               String cursorId) throws SQLException {
        int total = 0;
        Instant pagingHigh = cursorHigh;
        String pagingKind = cursorKind;
        String pagingId = cursorId;

        while (true) {
            int rowsInPage = 0;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(POST_SCAN_SQL)) {
                ps.setTimestamp(1, Timestamp.from(pagingHigh));
                ps.setString(2, pagingKind);
                ps.setString(3, pagingId);
                ps.setInt(4, pageSize);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID id = rs.getObject("id", UUID.class);
                        Instant statusChangedAt = rs.getTimestamp("status_changed_at").toInstant();
                        providerStateDao.advanceCursor(
                                QuarantineReviewListener.CHANNEL,
                                statusChangedAt, "post", id.toString());
                        pagingHigh = statusChangedAt;
                        pagingKind = "post";
                        pagingId = id.toString();
                        rowsInPage++;
                        total++;
                    }
                }
            }
            pagesProcessed++;
            if (rowsInPage < pageSize) break;
        }
        return total;
    }

    public int caughtUpCount() {
        return caughtUpCount;
    }

    public int pagesProcessed() {
        return pagesProcessed;
    }
}
