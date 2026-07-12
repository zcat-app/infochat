package app.zcat.infochat.provider.chat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance item 1: an in-flight chat tool query is actually cancellable.
 * Per commands.md §Conversation control, the cancellation primitive is
 * {@code pg_cancel_backend(pid)} at the released connection. This IT arms a
 * connection exactly as {@link app.zcat.infochat.provider.chat.tool.SearchPostsTool}
 * does ({@link CancellationService#armToolConnection}, which registers the
 * connection's backend pid on the in-flight handle), runs a long read-only
 * query on it, then issues the {@code /stop} primitive
 * ({@link CancellationService#cancel}) and asserts the query aborts with the
 * server's "canceling statement due to user request" signal — i.e.
 * {@code pg_cancel_backend} reached the backend running the query, not the
 * {@code statement_timeout} backstop and not the (ineffective for a blocking
 * pgjdbc read) thread interrupt.
 *
 * <p>The worker runs on the default (service-role) datasource — the same
 * datasource production tool calls and {@code CancellationService} use — so
 * the cancel connection and the query connection share a role and
 * {@code pg_cancel_backend} is permitted (same-role backends).
 *
 * <p>Best-effort, not timing-flaky: the discriminator is the cancellation
 * signal in the exception message, not a wall-clock race on an exact abort
 * instant.
 */
@QuarkusTest
class StopToolQueryCancellationIT {

    @Inject
    DataSource dataSource;

    @Inject
    CancellationService cancellationService;

    @Inject
    InFlightTracker inFlightTracker;

    @Test
    void stopAbortsInFlightToolQuery() throws Exception {
        UUID userId = UUID.randomUUID();
        String scopeKind = "dm";
        UUID scopeId = userId;

        CountDownLatch armed = new CountDownLatch(1);
        AtomicReference<Throwable> queryOutcome = new AtomicReference<>();

        // The worker mirrors an in-flight chat tool call: hold the (user,
        // scope) slot, arm the connection (registers the backend pid on the
        // handle), then run a long read-only query that /stop must abort.
        Thread worker = new Thread(() -> {
            InFlightTracker.CancellationHandle slot =
                    inFlightTracker.tryAcquire(userId, scopeKind, scopeId);
            try (Connection conn = dataSource.getConnection()) {
                cancellationService.armToolConnection(conn, userId, scopeKind, scopeId);
                // Widen THIS connection's statement_timeout so pg_cancel_backend
                // deterministically beats the backstop even under full-suite
                // load (M1-615). SET LOCAL inside the transaction that
                // armToolConnection opened overrides its %test 5s value for
                // this transaction only — the shared profile value and every
                // other test are untouched. 15s stays below the 20s
                // worker.join so a genuinely lost cancel still surfaces as
                // the discriminating "statement timeout" message.
                try (Statement widen = conn.createStatement()) {
                    widen.execute("SET LOCAL statement_timeout = 15000");
                }
                armed.countDown();
                // 30s sleep >> the cancel arrival (~ms): pg_cancel_backend
                // aborts it long before the sleep elapses (and before this
                // connection's widened 15s statement_timeout backstop).
                try (PreparedStatement ps = conn.prepareStatement("SELECT pg_sleep(30)")) {
                    ps.execute();
                }
            } catch (Throwable t) {
                queryOutcome.set(t);
            } finally {
                if (slot != null) {
                    inFlightTracker.release(userId, scopeKind, scopeId, slot);
                }
            }
        }, "stop-cancellation-it-worker");
        worker.start();

        assertTrue(armed.await(10, TimeUnit.SECONDS),
                "worker must arm its connection and start the slow query");
        InFlightTracker.CancellationHandle handle =
                inFlightTracker.getCancellationHandle(userId, scopeKind, scopeId).orElseThrow();
        // armToolConnection registers the pid before counting down `armed`,
        // so it is already present; poll briefly only to be robust to ordering.
        long pidDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!handle.hasPgBackendPid() && System.nanoTime() < pidDeadline) {
            Thread.sleep(20);
        }
        assertTrue(handle.hasPgBackendPid(),
                "the armed tool connection must register its pg backend pid");

        // PostgreSQL discards pg_cancel_backend against an idle backend, and
        // the armed latch counts down BEFORE ps.execute() reaches the server —
        // so a descheduled worker would silently lose a cancel issued now and
        // the statement_timeout backstop would fire instead (the full-suite
        // flake this gate removes, M1-615). Only issue /stop once the slow
        // query is observably executing on the registered backend.
        boolean slowQueryActive = false;
        long activeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (Connection probe = dataSource.getConnection();
             PreparedStatement stateQuery = probe.prepareStatement(
                     "SELECT state FROM pg_stat_activity"
                             + " WHERE pid = ? AND query LIKE '%pg_sleep%'")) {
            stateQuery.setInt(1, handle.pgBackendPid());
            while (System.nanoTime() < activeDeadline) {
                try (ResultSet rs = stateQuery.executeQuery()) {
                    if (rs.next() && "active".equals(rs.getString(1))) {
                        slowQueryActive = true;
                        break;
                    }
                }
                Thread.sleep(20);
            }
        }
        assertTrue(slowQueryActive,
                "the slow query must be observed running (pg_stat_activity"
                        + " state=active) before /stop is issued");

        boolean cancelled = cancellationService.cancel(userId, scopeKind, scopeId);
        assertTrue(cancelled, "cancel must report that an in-flight slot existed");

        worker.join(TimeUnit.SECONDS.toMillis(20));
        assertFalse(worker.isAlive(), "the worker must finish, not run the full 30s sleep");

        Throwable outcome = queryOutcome.get();
        assertNotNull(outcome,
                "the in-flight query must abort with an exception, not complete normally");
        String message = causeChainMessage(outcome).toLowerCase();
        assertTrue(message.contains("user request"),
                "the query must be aborted by pg_cancel_backend (\"canceling statement due to "
                        + "user request\"), not the statement_timeout backstop. Got: " + message);
    }

    /** Concatenate the messages along a throwable's cause chain. */
    private static String causeChainMessage(Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        for (@Nullable Throwable current = throwable; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null) {
                sb.append(message).append(" | ");
            }
        }
        return sb.toString();
    }
}
