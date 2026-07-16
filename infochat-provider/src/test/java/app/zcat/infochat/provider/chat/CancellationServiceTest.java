package app.zcat.infochat.provider.chat;

import org.jboss.logmanager.LogContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellationServiceTest {

    private static final UUID USER_A = UUID.randomUUID();
    private static final UUID SCOPE_A = UUID.randomUUID();

    private CancellationService service;
    private InFlightTracker tracker;
    private RecordingDataSource recordingDataSource;

    @BeforeEach
    void setUp() {
        tracker = new InFlightTracker();
        recordingDataSource = new RecordingDataSource();
        service = new CancellationService();
        service.inFlightTracker = tracker;
        service.dataSource = recordingDataSource;
        service.statementTimeout = Duration.ofSeconds(30);
    }

    @Test
    void closesStreamAndCancelsPgBackend() {
        // Acquire the in-flight slot from a worker thread, then register
        // a PG backend PID on the handle.
        tracker.tryAcquire(USER_A, "dm", SCOPE_A);
        InFlightTracker.CancellationHandle handle =
                tracker.getCancellationHandle(USER_A, "dm", SCOPE_A).orElseThrow();
        handle.registerPgBackendPid(42);

        boolean cancelled = service.cancel(USER_A, "dm", SCOPE_A);

        assertTrue(cancelled, "cancel must return true when an in-flight slot existed");
        // The slot was acquired on this thread, so this thread is the
        // captured worker the gate-checked interrupt must reach.
        assertTrue(Thread.currentThread().isInterrupted(),
                "cancel must interrupt the worker thread");
        assertTrue(recordingDataSource.executedSql.stream()
                        .anyMatch(s -> s.contains("pg_cancel_backend")),
                "cancel must issue pg_cancel_backend when a PID is registered");
        assertFalse(tracker.isInFlight(USER_A, "dm", SCOPE_A),
                "cancel must release the in-flight slot");

        // Clear the interrupted flag so JUnit doesn't trip
        Thread.interrupted();
    }

    @Test
    void cancelMarksHandleCancelled() {
        // The handle is captured before cancel() releases it (release only
        // unmaps the slot; the handle object still carries the flag). The
        // mark is what lets the delivery boundary discard a result even when
        // the worker missed the interrupt.
        tracker.tryAcquire(USER_A, "dm", SCOPE_A);
        InFlightTracker.CancellationHandle handle =
                tracker.getCancellationHandle(USER_A, "dm", SCOPE_A).orElseThrow();
        assertFalse(handle.isCancelled(), "handle is not cancelled before /stop");

        boolean cancelled = service.cancel(USER_A, "dm", SCOPE_A);

        assertTrue(cancelled);
        assertTrue(handle.isCancelled(),
                "cancel() must mark the handle cancelled (before interrupting) so the "
                        + "delivery boundary discards the result even on a missed interrupt");

        Thread.interrupted();
    }

    @Test
    void cancelReturnsFalseWhenNothingInFlight() {
        boolean cancelled = service.cancel(USER_A, "dm", SCOPE_A);
        assertFalse(cancelled, "cancel must return false when no in-flight slot exists");
    }

    /**
     * M1-634 redteam remediation pin (stale-interrupt window): once the
     * worker has closed its cancellation gate (end of the in-flight
     * section), a cancel that already looked up the handle must issue
     * NEITHER the thread interrupt (the pool thread may have recycled to a
     * different user's turn) NOR pg_cancel_backend (the pid's pooled
     * connection may be serving another borrower's query).
     */
    @Test
    void cancelAfterWorkerReleaseInterruptsNothingAndSkipsPgCancel() {
        tracker.tryAcquire(USER_A, "dm", SCOPE_A);
        InFlightTracker.CancellationHandle handle =
                tracker.getCancellationHandle(USER_A, "dm", SCOPE_A).orElseThrow();
        handle.registerPgBackendPid(42);

        // The worker finishes its in-flight section; the slot mapping is
        // still present, modelling a /stop that read the handle before the
        // worker completed and was delayed past its completion.
        handle.releaseWorker();

        boolean cancelled = service.cancel(USER_A, "dm", SCOPE_A);

        assertTrue(cancelled, "the slot existed at lookup, so cancel reports an attempt");
        assertFalse(Thread.currentThread().isInterrupted(),
                "a cancel arriving after the worker released its section must not interrupt");
        assertTrue(recordingDataSource.executedSql.isEmpty(),
                "pg_cancel_backend must be suppressed once the worker released its section");
        assertFalse(tracker.isInFlight(USER_A, "dm", SCOPE_A),
                "cancel still releases the in-flight slot");
    }

    @Test
    void cancelWithoutPgPidSkipsPgCancelBackend() {
        tracker.tryAcquire(USER_A, "dm", SCOPE_A);

        boolean cancelled = service.cancel(USER_A, "dm", SCOPE_A);

        assertTrue(cancelled);
        assertTrue(recordingDataSource.executedSql.isEmpty(),
                "no pg_cancel_backend should be issued when no PID is registered");

        Thread.interrupted();
    }

    @Test
    void statementTimeoutApplied() throws SQLException {
        RecordingStatement stmt = new RecordingStatement();
        Connection conn = proxyConnection(stmt);

        service.applyStatementTimeout(conn);

        assertEquals(1, stmt.executedSql.size(),
                "applyStatementTimeout must execute exactly one SQL statement");
        String sql = stmt.executedSql.get(0);
        assertTrue(sql.contains("SET LOCAL statement_timeout"),
                "must issue SET LOCAL statement_timeout. Got: " + sql);
        assertTrue(sql.contains("30000"),
                "must use the configured timeout in millis (30s = 30000ms). Got: " + sql);
    }

    /**
     * Acceptance pin: the timeout is transaction-local. A pooled
     * session armed by a timeout-bearing call must serve the pool's
     * default statement_timeout to the next borrower — the leak fixed
     * here was a session-level SET surviving the connection's return
     * to the pool. {@link PgSessionFake} models the PostgreSQL
     * semantics the assertion needs: SET LOCAL binds to the open
     * transaction only, and the pool's release-time rollback + reset
     * discards it; a plain session-level SET would survive both.
     */
    @Test
    void connectionBorrowedAfterTimeoutBearingCallObservesPoolDefault() throws SQLException {
        PgSessionFake session = new PgSessionFake();

        Connection first = session.borrow();
        service.applyStatementTimeout(first);
        assertEquals("30000", session.effectiveStatementTimeout(),
                "the timeout must be in force while the armed transaction is open");
        first.close();

        session.borrow();
        assertEquals(PgSessionFake.DEFAULT_TIMEOUT, session.effectiveStatementTimeout(),
                "a connection borrowed after a timeout-bearing call must observe"
                        + " the pool's default statement_timeout");
    }

    /**
     * Acceptance pin: the value reaching the SET statement is a
     * validated positive integer within PostgreSQL's int4 domain —
     * never raw text. Rejection must happen before any SQL reaches
     * the connection.
     */
    @Test
    void applyStatementTimeoutRejectsNonPositiveAndOverflowDurations() {
        RecordingStatement recorder = new RecordingStatement();
        Connection conn = proxyConnection(recorder);

        service.statementTimeout = Duration.ZERO;
        assertThrows(IllegalStateException.class,
                () -> service.applyStatementTimeout(conn),
                "zero timeout must be rejected");

        service.statementTimeout = Duration.ofMillis(-5);
        assertThrows(IllegalStateException.class,
                () -> service.applyStatementTimeout(conn),
                "negative timeout must be rejected");

        service.statementTimeout = Duration.ofMillis((long) Integer.MAX_VALUE + 1);
        assertThrows(IllegalStateException.class,
                () -> service.applyStatementTimeout(conn),
                "timeout beyond PostgreSQL's int4 millisecond domain must be rejected");

        assertTrue(recorder.executedSql.isEmpty(),
                "validation must reject the value before any SQL reaches the connection");
    }

    /**
     * Acceptance pin: a false pg_cancel_backend result (backend gone
     * or not cancellable) logs WARN naming the pid instead of the
     * success INFO.
     */
    @Test
    void pgCancelBackendFalseResultLogsWarnNamingPid() {
        recordingDataSource.pgCancelResult = false;
        tracker.tryAcquire(USER_A, "dm", SCOPE_A);
        InFlightTracker.CancellationHandle handle =
                tracker.getCancellationHandle(USER_A, "dm", SCOPE_A).orElseThrow();
        handle.registerPgBackendPid(77);

        CapturingHandler logCapture = new CapturingHandler();
        org.jboss.logmanager.Logger jbossLogger =
                LogContext.getLogContext().getLogger(CancellationService.class.getName());
        Logger julLogger = Logger.getLogger(CancellationService.class.getName());
        jbossLogger.addHandler(logCapture);
        julLogger.addHandler(logCapture);
        try {
            service.cancel(USER_A, "dm", SCOPE_A);
        } finally {
            jbossLogger.removeHandler(logCapture);
            julLogger.removeHandler(logCapture);
        }

        assertTrue(logCapture.records.stream().anyMatch(r ->
                        r.getLevel().intValue() == Level.WARNING.intValue()
                                && logCapture.format(r).contains("pg_cancel_backend")
                                && logCapture.format(r).contains("77")),
                "a false pg_cancel_backend result must WARN naming the pid. Got: "
                        + logCapture.formatted());
        assertFalse(logCapture.records.stream().anyMatch(r ->
                        r.getLevel().intValue() == Level.INFO.intValue()
                                && logCapture.format(r).contains("pg_cancel_backend")),
                "the false path must not emit the success INFO");

        Thread.interrupted();
    }

    @Test
    void statementTimeoutExposesConfiguredValue() {
        assertEquals(Duration.ofSeconds(30), service.statementTimeout());
    }

    @Test
    void armToolConnectionAppliesTimeoutAndRegistersPid() throws SQLException {
        RecordingStatement recorder = new RecordingStatement();
        Connection conn = proxyConnectionWithBackendPid(recorder, 12345);

        // The chat turn holds the in-flight slot when the tool runs.
        tracker.tryAcquire(USER_A, "dm", SCOPE_A);

        service.armToolConnection(conn, USER_A, "dm", SCOPE_A);

        assertTrue(recorder.executedSql.stream().anyMatch(s -> s.contains("SET LOCAL statement_timeout")),
                "armToolConnection must apply statement_timeout. Got: " + recorder.executedSql);
        InFlightTracker.CancellationHandle handle =
                tracker.getCancellationHandle(USER_A, "dm", SCOPE_A).orElseThrow();
        assertTrue(handle.hasPgBackendPid(),
                "armToolConnection must register the backend pid on the in-flight handle");
        assertEquals(12345, handle.pgBackendPid(),
                "the registered pid must be the connection's pg_backend_pid()");
    }

    @Test
    void armToolConnectionWithoutSlotStillAppliesTimeout() throws SQLException {
        RecordingStatement recorder = new RecordingStatement();
        Connection conn = proxyConnectionWithBackendPid(recorder, 999);

        // No slot held (e.g. /stop already released it, or the tool runs
        // outside a chat turn): the timeout still applies; pid registration
        // is a no-op rather than throwing.
        service.armToolConnection(conn, USER_A, "dm", SCOPE_A);

        assertTrue(recorder.executedSql.stream().anyMatch(s -> s.contains("SET LOCAL statement_timeout")),
                "statement_timeout must still apply with no in-flight slot");
        assertTrue(tracker.getCancellationHandle(USER_A, "dm", SCOPE_A).isEmpty(),
                "no slot should exist, so pid registration is a no-op");
    }

    // ----- stubs -----------------------------------------------------------

    private static class RecordingDataSource implements DataSource {
        final List<String> executedSql = new ArrayList<>();
        // What the fake pg_cancel_backend(pid) reports back.
        boolean pgCancelResult = true;

        @Override
        public Connection getConnection() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] { Connection.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> {
                            String sql = (String) args[0];
                            yield (PreparedStatement) Proxy.newProxyInstance(
                                    PreparedStatement.class.getClassLoader(),
                                    new Class<?>[] { PreparedStatement.class },
                                    (pProxy, pMethod, pArgs) -> switch (pMethod.getName()) {
                                        case "setInt", "setString", "setObject" -> null;
                                        case "execute" -> {
                                            executedSql.add(sql);
                                            yield false;
                                        }
                                        case "executeQuery" -> {
                                            executedSql.add(sql);
                                            yield singleBooleanResultSet(pgCancelResult);
                                        }
                                        case "close" -> null;
                                        default -> throw new UnsupportedOperationException(
                                                "PS." + pMethod.getName());
                                    });
                        }
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(
                                "Conn." + method.getName());
                    });
        }

        @Override public Connection getConnection(String u, String p) { return getConnection(); }
        @Override public PrintWriter getLogWriter() { throw new UnsupportedOperationException(); }
        @Override public void setLogWriter(PrintWriter out) { throw new UnsupportedOperationException(); }
        @Override public void setLoginTimeout(int seconds) { throw new UnsupportedOperationException(); }
        @Override public int getLoginTimeout() { throw new UnsupportedOperationException(); }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
        @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    private static class RecordingStatement {
        final List<String> executedSql = new ArrayList<>();
    }

    private static Connection proxyConnection(RecordingStatement recorder) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "createStatement" -> (Statement) Proxy.newProxyInstance(
                            Statement.class.getClassLoader(),
                            new Class<?>[] { Statement.class },
                            (sProxy, sMethod, sArgs) -> switch (sMethod.getName()) {
                                case "execute" -> {
                                    recorder.executedSql.add((String) sArgs[0]);
                                    yield false;
                                }
                                case "close" -> null;
                                default -> throw new UnsupportedOperationException(
                                        "Statement." + sMethod.getName());
                            });
                    case "setAutoCommit" -> null;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(
                            "Connection." + method.getName());
                });
    }

    // Connection proxy that records SET statement_timeout (execute) and
    // serves SELECT pg_backend_pid() (executeQuery → one row → the given pid).
    private static Connection proxyConnectionWithBackendPid(RecordingStatement recorder, int pid) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "createStatement" -> (Statement) Proxy.newProxyInstance(
                            Statement.class.getClassLoader(),
                            new Class<?>[] { Statement.class },
                            (sProxy, sMethod, sArgs) -> switch (sMethod.getName()) {
                                case "execute" -> {
                                    recorder.executedSql.add((String) sArgs[0]);
                                    yield false;
                                }
                                case "executeQuery" -> {
                                    recorder.executedSql.add((String) sArgs[0]);
                                    yield singleIntResultSet(pid);
                                }
                                case "close" -> null;
                                default -> throw new UnsupportedOperationException(
                                        "Statement." + sMethod.getName());
                            });
                    case "setAutoCommit" -> null;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(
                            "Connection." + method.getName());
                });
    }

    // ResultSet proxy with exactly one row whose single int column is value.
    private static ResultSet singleIntResultSet(int value) {
        boolean[] advanced = { false };
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> {
                        if (advanced[0]) yield false;
                        advanced[0] = true;
                        yield true;
                    }
                    case "getInt" -> value;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(
                            "ResultSet." + method.getName());
                });
    }

    // ResultSet proxy with exactly one row whose single boolean column is value.
    private static ResultSet singleBooleanResultSet(boolean value) {
        boolean[] advanced = { false };
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> {
                        if (advanced[0]) yield false;
                        advanced[0] = true;
                        yield true;
                    }
                    case "getBoolean" -> value;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(
                            "ResultSet." + method.getName());
                });
    }

    /**
     * Minimal model of one pooled PostgreSQL session, covering exactly
     * the semantics the transaction-local pin needs:
     * <ul>
     *   <li>{@code SET statement_timeout} (session-level) survives the
     *       connection's return to the pool — the leak under test;</li>
     *   <li>{@code SET LOCAL statement_timeout} binds to the open
     *       transaction only (a no-op under autocommit, as in
     *       PostgreSQL), and the pool's release-time rollback + reset
     *       discards it;</li>
     *   <li>{@code borrow()} hands out the SAME underlying session
     *       again, as a pool reusing the physical connection does.</li>
     * </ul>
     */
    private static final class PgSessionFake {
        static final String DEFAULT_TIMEOUT = "default";

        private String sessionTimeout = DEFAULT_TIMEOUT;
        private String txLocalTimeout;
        private boolean autoCommit = true;

        Connection borrow() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] { Connection.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "setAutoCommit" -> {
                            autoCommit = (Boolean) args[0];
                            yield null;
                        }
                        case "createStatement" -> statement();
                        case "close" -> {
                            release();
                            yield null;
                        }
                        default -> throw new UnsupportedOperationException(
                                "Connection." + method.getName());
                    });
        }

        private Statement statement() {
            return (Statement) Proxy.newProxyInstance(
                    Statement.class.getClassLoader(),
                    new Class<?>[] { Statement.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "execute" -> {
                            applySql((String) args[0]);
                            yield false;
                        }
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(
                                "Statement." + method.getName());
                    });
        }

        // Pool release: roll back any open transaction (discarding
        // SET LOCAL) and reset autocommit.
        private void release() {
            txLocalTimeout = null;
            autoCommit = true;
        }

        private void applySql(String sql) {
            if (sql.startsWith("SET LOCAL statement_timeout")) {
                // Transaction-scoped; PostgreSQL discards it under
                // autocommit (each statement is its own transaction).
                if (!autoCommit) {
                    txLocalTimeout = valueOf(sql);
                }
            } else if (sql.startsWith("SET statement_timeout")) {
                sessionTimeout = valueOf(sql);
            } else {
                throw new UnsupportedOperationException("SQL: " + sql);
            }
        }

        private static String valueOf(String sql) {
            return sql.substring(sql.indexOf('=') + 1).trim();
        }

        String effectiveStatementTimeout() {
            return txLocalTimeout != null ? txLocalTimeout : sessionTimeout;
        }
    }

    // Mirrors the plain-JUnit log-capture pattern of
    // InboundRouterContactIdRedactionTest: a JUL Handler attached to both
    // the jboss-logmanager and JUL loggers so the capture works under
    // either active backend.
    private static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();
        private final SimpleFormatter formatter = new SimpleFormatter();

        CapturingHandler() {
            setLevel(Level.ALL);
        }

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        String format(LogRecord record) {
            return formatter.format(record);
        }

        String formatted() {
            StringBuilder sb = new StringBuilder();
            for (LogRecord r : records) {
                sb.append(formatter.format(r));
            }
            return sb.toString();
        }
    }
}
