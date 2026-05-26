package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.digest.DigestRetryService.RetryResult;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigestRetryServiceTest {

    private static final UUID GROUP_ID = UUID.randomUUID();
    private static final String SLOT_KIND = "morning";
    private static final Instant SLOT_FIRED_AT = Instant.parse("2026-05-26T07:45:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-05-26T08:15:00Z");
    private static final String GROUP_TIMEZONE = "UTC";

    private DigestRetryService service;
    private RecordingDigestWorker digestWorker;
    private boolean[] deleteExecuted;

    @BeforeEach
    void setUp() {
        service = new DigestRetryService();
        digestWorker = new RecordingDigestWorker();
        service.digestWorker = digestWorker;
        service.retryCooldown = Duration.ofMinutes(2);
        deleteExecuted = new boolean[] { false };
    }

    @Test
    void retryDigest_replacesCacheRow() {
        service.dataSource = stubDataSource(
                SLOT_KIND, SLOT_FIRED_AT, EXPIRES_AT, false,
                GROUP_TIMEZONE, deleteExecuted);

        RetryResult result = service.retryDigest(GROUP_ID);

        assertEquals(RetryResult.SUCCESS, result);
        assertTrue(deleteExecuted[0], "old cache row must be deleted");
        assertEquals(1, digestWorker.executeCount,
                "DigestWorker.execute must be called once");
        assertEquals(GROUP_ID, digestWorker.lastSlot.groupId());
        assertEquals(SLOT_KIND, digestWorker.lastSlot.slotKind());
        assertEquals(SLOT_FIRED_AT, digestWorker.lastSlot.windowStart());
        assertEquals(EXPIRES_AT, digestWorker.lastSlot.windowEnd());
        assertEquals(GROUP_TIMEZONE, digestWorker.lastSlot.groupTimezone());
    }

    @Test
    void retryDigest_regeneratesFullProseFromDegraded() {
        // Cache row has isDegraded=true — retry should still call worker
        // (worker tries full prose first, falls back to degraded on LLM failure)
        service.dataSource = stubDataSource(
                SLOT_KIND, SLOT_FIRED_AT, EXPIRES_AT, true,
                GROUP_TIMEZONE, deleteExecuted);

        RetryResult result = service.retryDigest(GROUP_ID);

        assertEquals(RetryResult.SUCCESS, result);
        assertTrue(deleteExecuted[0], "degraded row must be deleted for replacement");
        assertEquals(1, digestWorker.executeCount,
                "worker must be called to regenerate full prose");
    }

    @Test
    void retryDigest_serializedPerGroup() {
        // Simulate an in-flight retry by acquiring the slot manually
        service.dataSource = stubDataSource(
                SLOT_KIND, SLOT_FIRED_AT, EXPIRES_AT, false,
                GROUP_TIMEZONE, deleteExecuted);

        // First call succeeds
        RetryResult first = service.retryDigest(GROUP_ID);
        assertEquals(RetryResult.SUCCESS, first);

        // Now simulate concurrent: put a blocking entry in the inFlight map
        // by using reflection (the map is private). Alternatively, use a
        // slow DataSource, but that's fragile. Instead, test the contract
        // indirectly: two sequential calls both succeed (proving the lock is
        // released), then verify the ConcurrentHashMap rejects overlap via
        // a stub that blocks.
        service.dataSource = new BlockingDataSource(
                stubDataSource(SLOT_KIND, SLOT_FIRED_AT, EXPIRES_AT, false,
                        GROUP_TIMEZONE, new boolean[] { false }));

        // Access internal maps via reflection to simulate overlap
        try {
            // Clear cooldown so the rate limiter doesn't fire before inFlight
            var cooldownField = DigestRetryService.class.getDeclaredField("lastRetryAt");
            cooldownField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var cooldownMap = (java.util.concurrent.ConcurrentHashMap<UUID, Instant>) cooldownField.get(service);
            cooldownMap.remove(GROUP_ID);

            var field = DigestRetryService.class.getDeclaredField("inFlight");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (java.util.concurrent.ConcurrentHashMap<UUID, Boolean>) field.get(service);
            map.put(GROUP_ID, Boolean.TRUE);

            RetryResult concurrent = service.retryDigest(GROUP_ID);
            assertEquals(RetryResult.ALREADY_IN_PROGRESS, concurrent,
                    "second concurrent retry must be rejected");

            // Clean up
            map.remove(GROUP_ID);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Reflection setup failed", e);
        }
    }

    // ----- stubs -------------------------------------------------------------

    static class RecordingDigestWorker extends DigestWorker {
        int executeCount = 0;
        DigestSlot lastSlot;

        @Override
        public void execute(@NonNull DigestSlot slot) {
            executeCount++;
            lastSlot = slot;
        }
    }

    /**
     * Trivial wrapper that delegates all calls — used only as a
     * marker type in the serialization test.
     */
    static class BlockingDataSource implements DataSource {
        private final DataSource delegate;

        BlockingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override public Connection getConnection() throws java.sql.SQLException { return delegate.getConnection(); }
        @Override public Connection getConnection(String u, String p) throws java.sql.SQLException { return delegate.getConnection(); }
        @Override public PrintWriter getLogWriter() { throw new UnsupportedOperationException(); }
        @Override public void setLogWriter(PrintWriter out) { throw new UnsupportedOperationException(); }
        @Override public void setLoginTimeout(int seconds) { throw new UnsupportedOperationException(); }
        @Override public int getLoginTimeout() { throw new UnsupportedOperationException(); }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
        @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    /**
     * Stub DataSource for DigestRetryService. Handles three SQL patterns:
     * 1. SELECT ... FROM summary_cache WHERE group_id = ? ORDER BY ... LIMIT 1
     * 2. SELECT timezone FROM groups WHERE id = ?
     * 3. DELETE FROM summary_cache WHERE ...
     */
    private static DataSource stubDataSource(
            String slotKind, Instant slotFiredAt, Instant expiresAt,
            boolean isDegraded, String timezone, boolean[] deleteFlag) {
        return new DataSource() {
            @Override
            public Connection getConnection() {
                return (Connection) Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] { Connection.class },
                        (proxy, method, args) -> switch (method.getName()) {
                            case "prepareStatement" -> {
                                String sql = (String) args[0];
                                yield stubPs(sql, slotKind, slotFiredAt, expiresAt,
                                        isDegraded, timezone, deleteFlag);
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
        };
    }

    private static PreparedStatement stubPs(
            String sql, String slotKind, Instant slotFiredAt, Instant expiresAt,
            boolean isDegraded, String timezone, boolean[] deleteFlag) {
        boolean isCacheQuery = sql.contains("summary_cache") && sql.contains("SELECT");
        boolean isTimezoneQuery = sql.contains("FROM groups");
        boolean isDelete = sql.contains("DELETE");
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] { PreparedStatement.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "setString", "setObject", "setTimestamp" -> null;
                    case "executeQuery" -> {
                        if (isCacheQuery) yield cacheResultSet(slotKind, slotFiredAt, expiresAt, isDegraded);
                        if (isTimezoneQuery) yield timezoneResultSet(timezone);
                        yield emptyResultSet();
                    }
                    case "executeUpdate" -> {
                        if (isDelete) deleteFlag[0] = true;
                        yield 1;
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(
                            "PS." + method.getName());
                });
    }

    private static ResultSet cacheResultSet(
            String slotKind, Instant slotFiredAt, Instant expiresAt, boolean isDegraded) {
        boolean[] consumed = { false };
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> {
                        if (consumed[0]) yield false;
                        consumed[0] = true;
                        yield true;
                    }
                    case "getString" -> slotKind;
                    case "getTimestamp" -> {
                        String col = (String) args[0];
                        yield switch (col) {
                            case "slot_fired_at" -> Timestamp.from(slotFiredAt);
                            case "expires_at" -> Timestamp.from(expiresAt);
                            default -> throw new UnsupportedOperationException("col: " + col);
                        };
                    }
                    case "getBoolean" -> isDegraded;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException("RS." + method.getName());
                });
    }

    private static ResultSet timezoneResultSet(String timezone) {
        boolean[] consumed = { false };
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> {
                        if (consumed[0]) yield false;
                        consumed[0] = true;
                        yield true;
                    }
                    case "getString" -> timezone;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException("RS." + method.getName());
                });
    }

    private static ResultSet emptyResultSet() {
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> false;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException("RS." + method.getName());
                });
    }
}
