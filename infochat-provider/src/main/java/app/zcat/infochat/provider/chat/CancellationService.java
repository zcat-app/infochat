package app.zcat.infochat.provider.chat;

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
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates cancellation of an in-flight interruptible request:
 * interrupts the worker thread and issues pg_cancel_backend on any
 * registered tool-call DB connection. Best-effort — the worker
 * discards the in-flight result regardless of whether Postgres
 * completes the query before the cancel takes effect.
 */
@ApplicationScoped
public class CancellationService {

    private static final Logger log = LoggerFactory.getLogger(CancellationService.class);

    private static final String PG_CANCEL_BACKEND = "SELECT pg_cancel_backend(?)";

    @Inject
    InFlightTracker inFlightTracker;

    @Inject
    DataSource dataSource;

    @ConfigProperty(name = "infochat.stop.statement-timeout", defaultValue = "30s")
    Duration statementTimeout;

    /**
     * Cancel the in-flight interruptible request for the given (user, scope).
     * Returns true if an in-flight slot existed and cancellation was attempted;
     * false if nothing was in flight.
     */
    public boolean cancel(UUID userId, String scopeKind, UUID scopeId) {
        Optional<InFlightTracker.CancellationHandle> handleOpt =
                inFlightTracker.getCancellationHandle(userId, scopeKind, scopeId);
        if (handleOpt.isEmpty()) {
            return false;
        }

        InFlightTracker.CancellationHandle handle = handleOpt.get();

        // Interrupt the worker thread — this propagates through the
        // LlmProvider.generate() HTTP call (virtual thread interruption
        // raises ClosedByInterruptException on the underlying channel).
        handle.workerThread().interrupt();

        // Best-effort pg_cancel_backend on any registered tool-call connection.
        if (handle.hasPgBackendPid()) {
            cancelPgBackend(handle.pgBackendPid());
        }

        // Release the in-flight slot so the user can issue new requests.
        // Handle-keyed: frees the slot only if it is still held by the
        // handle this cancel targeted, never a newer holder's.
        inFlightTracker.release(userId, scopeKind, scopeId, handle);

        return true;
    }

    /**
     * Apply the profile-driven statement_timeout to a DB connection.
     * Called by interruptible query code before executing long-running
     * read-only queries — bounds the worst case even when pg_cancel_backend
     * fails or the cancellation handle is never registered.
     *
     * <p>The timeout is transaction-local: autocommit is switched off so
     * {@code SET LOCAL} binds to the transaction pgJDBC opens at the next
     * statement, and the setting dies with that transaction — a plain
     * session-level {@code SET} on a pooled connection leaks the timeout
     * to subsequent borrowers. Callers run read-only queries on the armed
     * connection and never commit, so the pool's release-time rollback
     * discards nothing.</p>
     */
    public void applyStatementTimeout(Connection conn) throws SQLException {
        long timeoutMillis = validatedTimeoutMillis();
        conn.setAutoCommit(false);
        try (var stmt = conn.createStatement()) {
            stmt.execute("SET LOCAL statement_timeout = " + timeoutMillis);
        }
    }

    /**
     * The configured timeout as a validated positive integer of
     * milliseconds. PostgreSQL rejects bind parameters in SET, so the
     * value is formatted into the statement text; the range check
     * guarantees the formatted text is a plain positive integer within
     * PostgreSQL's int4 statement_timeout domain, never raw
     * operator-supplied text.
     */
    private long validatedTimeoutMillis() {
        long millis = statementTimeout.toMillis();
        if (millis <= 0 || millis > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "infochat.stop.statement-timeout must be a positive duration"
                            + " of at most " + Integer.MAX_VALUE + " ms, got: "
                            + statementTimeout);
        }
        return millis;
    }

    /**
     * Arm a chat-tool DB connection for /stop. Applies the profile-driven
     * statement_timeout (the safety net) and registers this connection's
     * Postgres backend pid on the in-flight cancellation handle for
     * (userId, scope), so a concurrent /stop can pg_cancel_backend the
     * in-flight tool query at exactly the connection running it. Pid
     * registration is a no-op when no slot is currently held (e.g. /stop
     * already released it, or the tool is exercised outside a chat turn);
     * the statement_timeout still applies in that case.
     */
    public void armToolConnection(Connection conn, UUID userId,
                                  String scopeKind, UUID scopeId) throws SQLException {
        applyStatementTimeout(conn);
        int pid = readBackendPid(conn);
        inFlightTracker.getCancellationHandle(userId, scopeKind, scopeId)
                .ifPresent(handle -> handle.registerPgBackendPid(pid));
    }

    /**
     * Expose the configured statement timeout for test verification.
     */
    public Duration statementTimeout() {
        return statementTimeout;
    }

    private static int readBackendPid(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT pg_backend_pid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void cancelPgBackend(int pid) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(PG_CANCEL_BACKEND)) {
            ps.setInt(1, pid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getBoolean(1)) {
                    log.info("pg_cancel_backend({}) issued", pid);
                } else {
                    // false: no backend with that pid (query already
                    // finished) or not cancellable by this role. The
                    // worker discards the in-flight result regardless;
                    // the WARN is for operator visibility only.
                    log.warn("pg_cancel_backend({}) returned false"
                            + " (backend gone or not cancellable)", pid);
                }
            }
        } catch (SQLException e) {
            // Best-effort: the worker discards the result regardless.
            log.warn("pg_cancel_backend({}) failed (best-effort)", pid, e);
        }
    }
}
