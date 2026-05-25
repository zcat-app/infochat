package app.zcat.infochat.provider.outbox;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jspecify.annotations.NonNull;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LISTEN/NOTIFY consumer for the {@code quarantine_review} channel.
 * Parallel to {@link NewPostListener} — dedicated long-lived connection,
 * virtual-thread worker, reconnect-resilient backoff. Routes actionable
 * events (PENDING, NEEDS_REVIEW) to a throttled admin notification;
 * routes terminal transitions (BENIGN_CLOSED, APPROVED, REJECTED) to
 * cursor-advance only.
 *
 * <p>The handler method {@link #handleEvent} is package-private so
 * {@link QuarantineReviewReconciler} can invoke it during startup
 * catch-up without duplicating cursor-advance / notification logic.
 */
@Startup
@Priority(260)
@ApplicationScoped
public class QuarantineReviewListener {

    static final String CHANNEL = "quarantine_review";
    static final int NOTIFICATION_TIMEOUT_MS = 1000;
    static final long SHUTDOWN_JOIN_TIMEOUT_MS = 5000;
    static final long INITIAL_BACKOFF_MS = 1000;
    static final long MAX_BACKOFF_MS = 30_000;

    private static final Logger LOG = Logger.getLogger(QuarantineReviewListener.class);

    private static final Pattern TARGET_KIND_PATTERN =
            Pattern.compile("\"target_kind\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TARGET_ID_PATTERN =
            Pattern.compile("\"target_id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern NEW_STATUS_PATTERN =
            Pattern.compile("\"new_status\"\\s*:\\s*\"([^\"]+)\"");

    // Throttled admin notification — inline UPSERT following the
    // Collector's ThrottledAdminNotifier pattern. The V21 migration
    // grants INSERT/UPDATE on admin_notification_state to infochat_provider.
    private static final String ADMIN_NOTIFY_KEY = "quarantine-review-actionable";
    private static final String SUPPRESSED_BUMP_SQL =
            "UPDATE admin_notification_state SET suppressed_count = suppressed_count + 1 "
                    + "WHERE notification_key = ?";

    @Inject DataSource dataSource;
    @Inject ProviderStateDao providerStateDao;

    @Inject
    @ConfigProperty(name = "infochat.admin-notifier.throttle-window", defaultValue = "1h")
    Duration throttleWindow;

    private volatile String upsertSql;
    private Connection listenConnection;
    private Thread workerThread;
    private volatile boolean stopRequested;

    private String getUpsertSql() {
        String sql = upsertSql;
        if (sql == null) {
            long ms = throttleWindow.toMillis();
            String interval = "INTERVAL '" + ms + " milliseconds'";
            sql = "INSERT INTO admin_notification_state "
                    + "(notification_key, error_class, last_notified_at, notification_count, "
                    + "suppressed_count, first_seen_at) "
                    + "VALUES (?, ?, ?, 1, 0, ?) "
                    + "ON CONFLICT (notification_key) DO UPDATE SET "
                    + "last_notified_at = EXCLUDED.last_notified_at, "
                    + "notification_count = admin_notification_state.notification_count + 1, "
                    + "error_class = EXCLUDED.error_class "
                    + "WHERE admin_notification_state.last_notified_at + " + interval
                    + " <= EXCLUDED.last_notified_at "
                    + "RETURNING notification_key";
            upsertSql = sql;
        }
        return sql;
    }

    @PostConstruct
    void onStartup() {
        try {
            openListenConnection();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "QuarantineReviewListener could not acquire its dedicated Postgres session "
                            + "or issue LISTEN " + CHANNEL, e);
        }
        workerThread = Thread.ofVirtual()
                .name("quarantine-review-listener")
                .start(this::runLoop);
    }

    @PreDestroy
    void onShutdown() {
        stopRequested = true;
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                workerThread.join(SHUTDOWN_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closeListenConnectionQuietly();
    }

    /**
     * Shared handler for both live NOTIFY dispatch and reconciler catch-up.
     * Routes actionable statuses to admin notification; advances the cursor
     * for all events.
     *
     * @return {@code true} if the cursor advanced
     */
    boolean handleEvent(@NonNull String targetKind, @NonNull UUID targetId,
                        @NonNull String newStatus, @NonNull Instant eventTime) throws SQLException {
        boolean advanced = providerStateDao.advanceCursor(
                CHANNEL, eventTime, targetKind, targetId.toString());

        if (advanced && isActionable(newStatus)) {
            fireAdminNotification(targetKind, targetId, newStatus);
        }
        return advanced;
    }

    private static boolean isActionable(String status) {
        return "PENDING".equals(status) || "NEEDS_REVIEW".equals(status);
    }

    private void fireAdminNotification(String targetKind, UUID targetId, String newStatus) {
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        String errorClass = "quarantine_review." + newStatus.toLowerCase();
        String message = "Quarantine review action needed: " + targetKind + " "
                + targetId + " → " + newStatus;

        try (Connection conn = dataSource.getConnection()) {
            boolean emitted;
            try (PreparedStatement ps = conn.prepareStatement(getUpsertSql())) {
                ps.setString(1, ADMIN_NOTIFY_KEY);
                ps.setString(2, errorClass);
                ps.setObject(3, now);
                ps.setObject(4, now);
                try (ResultSet rs = ps.executeQuery()) {
                    emitted = rs.next();
                }
            }
            if (emitted) {
                LOG.warnf("ADMIN-NOTIFY key=%s error=%s message=%s",
                        ADMIN_NOTIFY_KEY, errorClass, message);
            } else {
                try (PreparedStatement bump = conn.prepareStatement(SUPPRESSED_BUMP_SQL)) {
                    bump.setString(1, ADMIN_NOTIFY_KEY);
                    bump.executeUpdate();
                }
            }
        } catch (SQLException e) {
            LOG.warnf(e, "ADMIN-NOTIFY key=%s error=%s message=persistence failed",
                    ADMIN_NOTIFY_KEY, errorClass);
        }
    }

    // ---- LISTEN/NOTIFY loop (mirrors NewPostListener) ----

    private void runLoop() {
        long backoffMs = INITIAL_BACKOFF_MS;
        while (!stopRequested) {
            PGConnection pg;
            try {
                pg = ensureListenConnection();
            } catch (SQLException e) {
                if (stopRequested) return;
                LOG.errorf(e,
                        "QuarantineReviewListener: cannot (re)acquire LISTEN connection; "
                                + "backing off %dms", backoffMs);
                if (!sleepBackoff(backoffMs)) return;
                backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                continue;
            }
            PGNotification[] notifications;
            try {
                notifications = pg.getNotifications(NOTIFICATION_TIMEOUT_MS);
            } catch (SQLException e) {
                if (stopRequested) return;
                LOG.errorf(e,
                        "QuarantineReviewListener: getNotifications failed; "
                                + "will reconnect after %dms backoff", backoffMs);
                closeListenConnectionQuietly();
                if (!sleepBackoff(backoffMs)) return;
                backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                continue;
            }
            backoffMs = INITIAL_BACKOFF_MS;
            if (notifications == null) continue;
            for (PGNotification n : notifications) {
                dispatch(n);
            }
        }
    }

    private void dispatch(PGNotification n) {
        if (!CHANNEL.equals(n.getName())) return;
        Payload payload;
        try {
            payload = parsePayload(n.getParameter());
        } catch (RuntimeException e) {
            LOG.errorf(e, "QuarantineReviewListener: unparseable payload (dropped): %s",
                    n.getParameter());
            return;
        }
        try {
            // Look up event timestamp from DB
            Instant eventTime = lookupEventTime(payload.targetKind(), payload.targetId());
            if (eventTime == null) {
                LOG.warnf("QuarantineReviewListener: no matching row for %s/%s (dropped)",
                        payload.targetKind(), payload.targetId());
                return;
            }
            handleEvent(payload.targetKind(), payload.targetId(),
                    payload.newStatus(), eventTime);
        } catch (SQLException e) {
            LOG.errorf(e, "QuarantineReviewListener: handler failed for %s/%s",
                    payload.targetKind(), payload.targetId());
        }
    }

    /**
     * Reads the event timestamp from the DB. The NOTIFY payload does not
     * carry a timestamp, so the listener must look it up. For quarantine
     * events: quarantine_review_view.updated_at. For post events:
     * post.status_changed_at.
     */
    private Instant lookupEventTime(String targetKind, UUID targetId) throws SQLException {
        String sql = "quarantine".equals(targetKind)
                ? "SELECT updated_at FROM quarantine_review_view WHERE id = ?"
                : "SELECT status_changed_at FROM post WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, targetId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Timestamp ts = rs.getTimestamp(1);
                return ts != null ? ts.toInstant() : null;
            }
        }
    }

    static Payload parsePayload(String json) {
        Matcher kindMatcher = TARGET_KIND_PATTERN.matcher(json);
        Matcher idMatcher = TARGET_ID_PATTERN.matcher(json);
        Matcher statusMatcher = NEW_STATUS_PATTERN.matcher(json);
        if (!kindMatcher.find() || !idMatcher.find() || !statusMatcher.find()) {
            throw new IllegalArgumentException(
                    "quarantine_review payload must contain target_kind, target_id, "
                            + "and new_status fields; got: " + json);
        }
        return new Payload(
                kindMatcher.group(1),
                UUID.fromString(idMatcher.group(1)),
                statusMatcher.group(1));
    }

    record Payload(String targetKind, UUID targetId, String newStatus) {}

    // ---- LISTEN connection management ----

    private PGConnection ensureListenConnection() throws SQLException {
        if (listenConnection == null || listenConnection.isClosed()) {
            closeListenConnectionQuietly();
            openListenConnection();
            LOG.info("QuarantineReviewListener: (re)acquired LISTEN connection "
                    + "and re-issued LISTEN " + CHANNEL);
        }
        return listenConnection.unwrap(PGConnection.class);
    }

    private void openListenConnection() throws SQLException {
        listenConnection = dataSource.getConnection();
        listenConnection.setAutoCommit(true);
        try (Statement stmt = listenConnection.createStatement()) {
            stmt.execute("LISTEN " + CHANNEL);
        }
    }

    private void closeListenConnectionQuietly() {
        if (listenConnection != null) {
            try {
                listenConnection.close();
            } catch (SQLException ignored) { }
            listenConnection = null;
        }
    }

    private boolean sleepBackoff(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ---- Test hooks ----

    void closeListenConnectionForTest() {
        closeListenConnectionQuietly();
    }

    boolean isWorkerAlive() {
        return workerThread != null && workerThread.isAlive();
    }
}
