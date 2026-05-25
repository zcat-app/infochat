package app.zcat.infochat.provider.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(handle.workerThread().isInterrupted(),
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
    void cancelReturnsFalseWhenNothingInFlight() {
        boolean cancelled = service.cancel(USER_A, "dm", SCOPE_A);
        assertFalse(cancelled, "cancel must return false when no in-flight slot exists");
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
        assertTrue(sql.contains("SET statement_timeout"),
                "must issue SET statement_timeout. Got: " + sql);
        assertTrue(sql.contains("30000"),
                "must use the configured timeout in millis (30s = 30000ms). Got: " + sql);
    }

    @Test
    void statementTimeoutExposesConfiguredValue() {
        assertEquals(Duration.ofSeconds(30), service.statementTimeout());
    }

    // ----- stubs -----------------------------------------------------------

    private static class RecordingDataSource implements DataSource {
        final List<String> executedSql = new ArrayList<>();

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
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(
                            "Connection." + method.getName());
                });
    }
}
