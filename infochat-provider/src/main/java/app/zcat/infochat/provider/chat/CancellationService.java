package app.zcat.infochat.provider.chat;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
        inFlightTracker.release(userId, scopeKind, scopeId);

        return true;
    }

    /**
     * Apply the profile-driven statement_timeout to a DB connection.
     * Called by interruptible query code before executing long-running
     * read-only queries — bounds the worst case even when pg_cancel_backend
     * fails or the cancellation handle is never registered.
     */
    public void applyStatementTimeout(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.execute("SET statement_timeout = " + statementTimeout.toMillis());
        }
    }

    /**
     * Expose the configured statement timeout for test verification.
     */
    public Duration statementTimeout() {
        return statementTimeout;
    }

    private void cancelPgBackend(int pid) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(PG_CANCEL_BACKEND)) {
            ps.setInt(1, pid);
            ps.execute();
            log.info("pg_cancel_backend({}) issued", pid);
        } catch (SQLException e) {
            // Best-effort: the worker discards the result regardless.
            log.warn("pg_cancel_backend({}) failed (best-effort)", pid, e);
        }
    }
}
