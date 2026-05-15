package io.infochat.provider.outbox;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live LISTEN/NOTIFY worker for the {@code new_post} channel
 * (docs/spec/architecture.md §Inter-service communication;
 * docs/design/02-schema.md §2.9.1).
 *
 * <p><b>Bean ordering.</b> {@code @Startup} at {@code @Priority(260)} —
 * strictly greater than the {@link NewPostReconciler}'s 250 per the
 * Provider startup table (docs/design/01-architecture.md §1.4.3). The
 * priority ordering guarantees the reconciler's {@code @PostConstruct}
 * returns before this bean's begins, so any NOTIFY arriving mid-catch-up
 * is queued in the Postgres backend and delivered to this listener only
 * after the older READY rows have been replayed in order.
 *
 * <p><b>Dedicated connection.</b> LISTEN is connection-scoped: a notification
 * fired on connection A is NOT delivered to a LISTEN registered on
 * connection B. This bean owns one {@link Connection} for its full
 * lifetime — acquired from the Agroal {@link DataSource} on
 * {@code @PostConstruct}, never returned to the pool, closed on
 * {@code @PreDestroy}. The standard pgjdbc driver's
 * {@link PGConnection#getNotifications(int)} blocks the calling thread
 * for up to the supplied timeout waiting for a notification — a JDK 25
 * virtual thread holds the block cheaply.
 *
 * <p><b>Payload format.</b> JSON object with exactly two fields per
 * docs/design/02-schema.md §2.9.1 ({@code new_post} cursor-only payload):
 * <pre>{@code {"ready_at":"<iso8601>","post_id":"<uuid>"}}</pre>
 * The format is the cross-service contract; the M1-028 outbox emit MUST
 * produce exactly this shape. Field order is not significant — the
 * parser locates each named field independently.
 *
 * <p><b>Idempotency.</b> Each notification dispatches to
 * {@link NewPostHandler#handle} which advances the cursor inside its
 * {@code @Transactional} boundary via the compare-and-swap UPDATE in
 * {@link ProviderStateDao}. A duplicate NOTIFY (the same
 * {@code (ready_at, post_id)} arriving twice) becomes a CAS no-op at the
 * cursor level and produces no additional handler side effect beyond a
 * log line — the idempotency promise from docs/spec/architecture.md
 * §Catch-up.
 */
@Startup
@Priority(260)
@ApplicationScoped
public class NewPostListener {

    /**
     * {@code getNotifications} timeout in milliseconds. Short enough that
     * shutdown wakes the worker promptly (the @PreDestroy stop-flag check
     * happens at the top of each loop iteration), long enough that the
     * worker spends most of its time blocked on the JDBC call rather than
     * polling.
     */
    static final int NOTIFICATION_TIMEOUT_MS = 1000;

    /** Maximum @PreDestroy wait for the worker thread to drain. */
    static final long SHUTDOWN_JOIN_TIMEOUT_MS = 5000;

    private static final Pattern READY_AT_PATTERN =
        Pattern.compile("\"ready_at\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern POST_ID_PATTERN =
        Pattern.compile("\"post_id\"\\s*:\\s*\"([^\"]+)\"");

    private static final Logger LOG = Logger.getLogger(NewPostListener.class);

    @Inject
    DataSource dataSource;

    @Inject
    NewPostHandler newPostHandler;

    // Long-lived; never returned to the pool while the JVM is alive. LISTEN
    // is bound to the underlying Postgres backend session, so returning this
    // connection to Agroal would silently end the subscription.
    private Connection listenConnection;
    private Thread workerThread;
    private volatile boolean stopRequested;

    @PostConstruct
    void onStartup() {
        try {
            listenConnection = dataSource.getConnection();
            listenConnection.setAutoCommit(true);
            try (Statement stmt = listenConnection.createStatement()) {
                stmt.execute("LISTEN new_post");
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                "NewPostListener could not acquire its dedicated Postgres session "
                    + "or issue LISTEN new_post", e);
        }
        workerThread = Thread.ofVirtual()
            .name("new-post-listener")
            .start(this::runLoop);
    }

    @PreDestroy
    void onShutdown() {
        stopRequested = true;
        if (workerThread != null) {
            try {
                workerThread.join(SHUTDOWN_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (listenConnection != null) {
            try {
                listenConnection.close();
            } catch (SQLException ignored) {
                // Shutdown path; the backend session ends regardless.
            }
        }
    }

    private void runLoop() {
        try {
            PGConnection pg = listenConnection.unwrap(PGConnection.class);
            while (!stopRequested) {
                PGNotification[] notifications;
                try {
                    notifications = pg.getNotifications(NOTIFICATION_TIMEOUT_MS);
                } catch (SQLException e) {
                    if (stopRequested) {
                        return;
                    }
                    LOG.errorf(e, "NewPostListener: getNotifications failed");
                    continue;
                }
                if (notifications == null) {
                    continue;
                }
                for (PGNotification n : notifications) {
                    dispatch(n);
                }
            }
        } catch (SQLException e) {
            // unwrap() failure — fatal for the listener, but stopRequested
            // shutdown should not log as an error.
            if (!stopRequested) {
                LOG.error("NewPostListener loop terminated unexpectedly", e);
            }
        }
    }

    private void dispatch(PGNotification n) {
        if (!"new_post".equals(n.getName())) {
            return;
        }
        Payload payload;
        try {
            payload = parsePayload(n.getParameter());
        } catch (RuntimeException e) {
            LOG.errorf(e, "NewPostListener: unparseable payload (dropped): %s",
                n.getParameter());
            return;
        }
        try {
            newPostHandler.handle(payload.postId(), payload.readyAt());
        } catch (SQLException e) {
            LOG.errorf(e, "NewPostListener: handler failed for post_id=%s",
                payload.postId());
        }
    }

    /**
     * Parses a {@code new_post} NOTIFY payload per the format documented in
     * the class Javadoc. Visible for unit testing; not part of the public
     * API.
     *
     * @throws IllegalArgumentException if either required field is missing
     *     or the {@code ready_at} value is not an ISO-8601 instant or the
     *     {@code post_id} value is not a valid UUID.
     */
    static Payload parsePayload(String json) {
        Matcher readyAtMatcher = READY_AT_PATTERN.matcher(json);
        Matcher postIdMatcher = POST_ID_PATTERN.matcher(json);
        if (!readyAtMatcher.find() || !postIdMatcher.find()) {
            throw new IllegalArgumentException(
                "new_post payload must contain both 'ready_at' and 'post_id' fields; got: "
                    + json);
        }
        return new Payload(
            UUID.fromString(postIdMatcher.group(1)),
            Instant.parse(readyAtMatcher.group(1)));
    }

    /** Test-visible accessor: is the worker thread still running? */
    boolean isWorkerAlive() {
        return workerThread != null && workerThread.isAlive();
    }

    /** Parsed payload tuple. */
    record Payload(UUID postId, Instant readyAt) {}
}
