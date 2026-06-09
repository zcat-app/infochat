package app.zcat.infochat.provider.outbox;

import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import java.util.Objects;

/**
 * LISTEN/NOTIFY consumer for the {@code quarantine_review} channel.
 * Parallel to {@link NewPostListener} — dedicated long-lived connection,
 * virtual-thread worker, reconnect-resilient backoff. Routes actionable
 * events (row status PENDING, NEEDS_REVIEW) to a throttled admin
 * notification; routes terminal transitions (BENIGN_CLOSED, APPROVED,
 * REJECTED) to cursor-advance only.
 *
 * <p><b>Row truth, not payload truth.</b> The NOTIFY payload is purely
 * the wake-up signal (docs/spec/architecture.md §Inter-service
 * communication): {@link #handleEvent} re-reads the row's current
 * status and event time from quarantine_review_view / post and decides
 * actionability from the ROW — the payload's {@code new_status} is
 * shape-validated at the wire boundary but never drives a decision.
 *
 * <p><b>Same-transaction invariant.</b> {@code handleEvent} is
 * {@code @Transactional(rollbackOn = SQLException.class)}: the cursor
 * advance and the admin-notification persistence commit atomically
 * ("the high-water mark advances both fields in the same DB
 * transaction as the side effect it triggers"). {@code rollbackOn} is
 * load-bearing — JTA does NOT roll back on checked exceptions by
 * default, so without it a notification-write {@link SQLException}
 * would commit the cursor advance anyway. The live dispatch path
 * invokes the handler through the injected {@code self} client proxy
 * because a plain {@code this.handleEvent(...)} call would bypass the
 * interceptor and silently lose the transaction boundary.
 *
 * <p>The handler method {@link #handleEvent} is package-private so
 * {@link QuarantineReviewReconciler} can invoke it during startup
 * catch-up without duplicating cursor-advance / notification logic;
 * the reconnect path here symmetrically invokes the reconciler's
 * catch-up. Both beans are normal-scoped, so ArC resolves the cycle
 * via client proxies — with the consequence that the reconciler's
 * {@code @PostConstruct} (priority 250) touching the listener proxy
 * instantiates this bean EARLY whenever a missed event exists. That
 * is benign for this channel: the CAS cursor advance and the
 * throttle-coalesced notification make concurrent live dispatch and
 * catch-up idempotent, and out-of-order handling is tolerated by
 * design (an older actionable event still notifies; see
 * {@code handleEvent}). Do not copy the new_post ordered-replay
 * priority reasoning here.
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

    @Inject DataSource dataSource;
    @Inject ProviderStateDao providerStateDao;
    @Inject ThrottledAdminNotifier throttledAdminNotifier;
    @Inject QuarantineReviewReconciler reconciler;

    // Client-proxy self-reference: dispatch() must route handleEvent
    // through the proxy so the @Transactional interceptor applies —
    // a direct this-call would run the handler without a transaction
    // while proxy-routed tests keep passing (see class javadoc).
    @Inject QuarantineReviewListener self;

    // listenConnection and workerThread are (re)assigned across the
    // LISTEN lifecycle and reset to null on close/shutdown — both are
    // genuinely nullable.
    private @Nullable Connection listenConnection;
    private @Nullable Thread workerThread;
    private volatile boolean stopRequested;

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
     * Shared handler for both live NOTIFY dispatch and reconciler
     * catch-up. Reads the row's current state from the base table,
     * advances the cursor, and routes actionable statuses to the
     * throttled admin notifier — all inside one JTA transaction (class
     * javadoc §Same-transaction invariant).
     *
     * <p>The notification is deliberately NOT gated on the cursor
     * advance: an actionable event arriving after a newer event has
     * already advanced the cursor is a CAS no-op on the cursor but
     * must still reach the admin (throttling may coalesce it, never
     * silently drop it). The throttle key encodes the per-row error
     * class, so a PENDING page cannot suppress a following
     * NEEDS_REVIEW page (docs/spec/security.md §Failure handling —
     * coalescing is per {@code (channel, error_class)}).
     *
     * @return {@code true} if the cursor advanced
     */
    @Transactional(rollbackOn = SQLException.class)
    boolean handleEvent(String targetKind, UUID targetId) throws SQLException {
        RowState row = lookupRowState(targetKind, targetId);
        if (row == null) {
            LOG.warnf("QuarantineReviewListener: no matching row for %s/%s (dropped)",
                    targetKind, targetId);
            return false;
        }

        boolean advanced = providerStateDao.advanceCursor(
                CHANNEL, row.eventTime(), targetKind, targetId.toString());

        if (isActionable(row.status())) {
            String errorClass = "quarantine_review." + row.status().toLowerCase();
            String message = "Quarantine review action needed: " + targetKind + " "
                    + targetId + " → " + row.status();
            // The notifier runs on a connection enlisted in this
            // method's transaction and propagates SQLException, so a
            // failed notification write rolls back the cursor advance
            // above instead of being swallowed after it committed.
            try (Connection conn = dataSource.getConnection()) {
                throttledAdminNotifier.notifyOnce(conn, errorClass, errorClass, message);
            }
        }
        return advanced;
    }

    private static boolean isActionable(String status) {
        return "PENDING".equals(status) || "NEEDS_REVIEW".equals(status);
    }

    /**
     * Reads the row's current status and event time from the base
     * table — quarantine_review_view (the redacted Provider view) for
     * quarantine events, post for post events. The NOTIFY payload
     * carries neither field authoritatively: NOTIFY is the wake-up
     * signal, the row is the data (docs/spec/architecture.md
     * §Inter-service communication). Both timestamp columns are
     * NOT NULL by schema, so a present row always yields a full state.
     */
    private @Nullable RowState lookupRowState(String targetKind, UUID targetId) throws SQLException {
        String sql = "quarantine".equals(targetKind)
                ? "SELECT updated_at, status FROM quarantine_review_view WHERE id = ?"
                : "SELECT status_changed_at, status FROM post WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, targetId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new RowState(rs.getString(2), rs.getTimestamp(1).toInstant());
            }
        }
    }

    /** Current row state read from the base table. */
    record RowState(String status, Instant eventTime) {}

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
                        "QuarantineReviewListener: reconnect sequence failed "
                                + "(acquire/LISTEN/catch-up); backing off %dms", backoffMs);
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

    // Package-private for unit testing, like parsePayload.
    void dispatch(PGNotification n) {
        if (!CHANNEL.equals(n.getName())) return;
        Payload payload;
        try {
            payload = parsePayload(n.getParameter());
        } catch (RuntimeException e) {
            // Do NOT echo the raw payload (NewPostListener parity): the
            // unparseable bytes need no payload content to be actionable
            // and must stay out of the operator's log.
            LOG.error("QuarantineReviewListener: unparseable payload (dropped)", e);
            return;
        }
        try {
            // Through the CDI client proxy, NOT this.handleEvent —
            // the @Transactional interceptor only intercepts
            // proxy-routed calls (class javadoc).
            self.handleEvent(payload.targetKind(), payload.targetId());
        } catch (SQLException e) {
            LOG.errorf(e, "QuarantineReviewListener: handler failed for %s/%s",
                    payload.targetKind(), payload.targetId());
        }
    }

    /**
     * Validates the wire shape: all three payload fields must be
     * present and well-formed (the emit side's closed contract), and
     * {@code target_kind} must name one of the two enumerated base
     * tables. The parsed {@code new_status} is retained for shape
     * validation only — actionability comes from the row (class
     * javadoc §Row truth).
     */
    static Payload parsePayload(String json) {
        Matcher kindMatcher = TARGET_KIND_PATTERN.matcher(json);
        Matcher idMatcher = TARGET_ID_PATTERN.matcher(json);
        Matcher statusMatcher = NEW_STATUS_PATTERN.matcher(json);
        if (!kindMatcher.find() || !idMatcher.find() || !statusMatcher.find()) {
            // No payload echo (NewPostListener parity): this is the
            // NOTIFY-deserialization boundary and the message flows into
            // the dispatch log above.
            throw new IllegalArgumentException(
                    "quarantine_review payload must contain target_kind, target_id, "
                            + "and new_status fields");
        }
        // Reject an out-of-set discriminator at the wire boundary so it
        // is dropped-with-log by dispatch, never silently routed: a
        // target_kind other than "quarantine" would otherwise fall
        // through to the "post" base-table lookup in lookupRowState.
        String targetKind = kindMatcher.group(1);
        if (!"quarantine".equals(targetKind) && !"post".equals(targetKind)) {
            throw new IllegalArgumentException(
                    "quarantine_review payload target_kind must be \"quarantine\" "
                            + "or \"post\"");
        }
        return new Payload(
                targetKind,
                UUID.fromString(idMatcher.group(1)),
                statusMatcher.group(1));
    }

    record Payload(String targetKind, UUID targetId, String newStatus) {}

    // ---- LISTEN connection management ----
    //
    // The lifecycle methods below are synchronized: the worker's
    // check-reopen-catch-up sequence races against a concurrent close
    // (the @PreDestroy shutdown path, or the test hook) — an
    // unsynchronized close landing between the reopen and the field
    // read would null out the FRESH connection and NPE-kill the worker
    // thread. The poll itself (getNotifications in runLoop) stays
    // outside the monitor so a close is never blocked for the full
    // notification timeout.

    private synchronized PGConnection ensureListenConnection() throws SQLException {
        if (listenConnection == null || listenConnection.isClosed()) {
            closeListenConnectionQuietly();
            openListenConnection();
            LOG.info("QuarantineReviewListener: (re)acquired LISTEN connection "
                    + "and re-issued LISTEN " + CHANNEL);
            reconcileAfterReconnect();
        }
        return Objects.requireNonNull(listenConnection).unwrap(PGConnection.class);
    }

    /**
     * Post-reconnect catch-up (mirrors {@link NewPostListener}):
     * NOTIFYs fired between the connection loss and the re-LISTEN
     * above were dropped by Postgres, so the reconciler replays
     * quarantine_review events past the cursor — advancing the cursor
     * AND notifying actionable ones through the same
     * {@link #handleEvent} path. A catch-up failure closes the fresh
     * connection and rethrows so the caller's backoff path retries
     * the full reconnect-plus-reconcile sequence — otherwise a live
     * connection with an unreconciled gap would short-circuit future
     * {@link #ensureListenConnection()} calls and the gap would
     * persist silently.
     */
    private void reconcileAfterReconnect() throws SQLException {
        try {
            reconciler.runCatchUp();
        } catch (SQLException e) {
            closeListenConnectionQuietly();
            throw e;
        }
    }

    private synchronized void openListenConnection() throws SQLException {
        listenConnection = dataSource.getConnection();
        listenConnection.setAutoCommit(true);
        try (Statement stmt = listenConnection.createStatement()) {
            stmt.execute("LISTEN " + CHANNEL);
        }
    }

    private synchronized void closeListenConnectionQuietly() {
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
