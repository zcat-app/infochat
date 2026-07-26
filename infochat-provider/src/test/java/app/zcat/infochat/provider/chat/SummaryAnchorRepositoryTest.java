package app.zcat.infochat.provider.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Handler-tier (plain JUnit, no Quarkus boot) tests for
 * {@link SummaryAnchorRepository}. The DB round-trip (write → read → clear)
 * is covered by the IT ({@code InboundRouterStopRetryIT}); this test
 * exercises the in-memory retry count tracking and verifies the SQL calls
 * are issued correctly.
 */
class SummaryAnchorRepositoryTest {

    private static final UUID USER_A = UUID.randomUUID();
    private static final UUID SCOPE_A = UUID.randomUUID();

    private SummaryAnchorRepository repo;
    private RecordingDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new RecordingDataSource();
        repo = new SummaryAnchorRepository();
        repo.dataSource = dataSource;
    }

    @Test
    void writesAndClearsAnchor() {
        List<String> postUids = List.of("p-test-uid-1", "p-test-uid-2");
        String clusterMapJson = "[{\"topicId\":\"t-abc\",\"postUids\":[]}]";

        repo.write(USER_A, "dm", SCOPE_A, "summary", "bare", "hash123", postUids, clusterMapJson);

        assertEquals(1, dataSource.executedUpdateCount,
                "write must issue one SQL update (UPSERT)");
        assertTrue(dataSource.lastSql.contains("INSERT INTO summary_anchor"),
                "write must use the UPSERT SQL. Got: " + dataSource.lastSql);
        assertTrue(dataSource.lastSql.contains("render_form"),
                "UPSERT must carry the render_form dispatch column (M1-699). Got: " + dataSource.lastSql);

        dataSource.resetCounters();
        repo.clear(USER_A, "dm", SCOPE_A);

        assertEquals(1, dataSource.executedUpdateCount,
                "clear must issue one SQL update (DELETE)");
        assertTrue(dataSource.lastSql.contains("DELETE FROM summary_anchor"),
                "clear must use the DELETE SQL. Got: " + dataSource.lastSql);
    }

    @Test
    void retryCountTracking() {
        assertEquals(1, repo.incrementAndGetRetryCount(USER_A, "dm", SCOPE_A),
                "first increment returns 1");
        assertEquals(2, repo.incrementAndGetRetryCount(USER_A, "dm", SCOPE_A),
                "second increment returns 2");
        assertEquals(3, repo.incrementAndGetRetryCount(USER_A, "dm", SCOPE_A),
                "third increment returns 3");

        repo.clearRetryCount(USER_A, "dm", SCOPE_A);

        assertEquals(1, repo.incrementAndGetRetryCount(USER_A, "dm", SCOPE_A),
                "after clear, first increment returns 1 again");
    }

    @Test
    void writeResetsRetryCount() {
        repo.incrementAndGetRetryCount(USER_A, "dm", SCOPE_A);
        repo.incrementAndGetRetryCount(USER_A, "dm", SCOPE_A);

        List<String> postUids = List.of("p-test-uid-3");
        repo.write(USER_A, "dm", SCOPE_A, "summary", "bare", "hash", postUids, null);

        assertEquals(1, repo.incrementAndGetRetryCount(USER_A, "dm", SCOPE_A),
                "write must reset the retry count");
    }

    @Test
    void clearResetsRetryCount() {
        repo.incrementAndGetRetryCount(USER_A, "dm", SCOPE_A);
        repo.incrementAndGetRetryCount(USER_A, "dm", SCOPE_A);

        repo.clear(USER_A, "dm", SCOPE_A);

        assertEquals(1, repo.incrementAndGetRetryCount(USER_A, "dm", SCOPE_A),
                "clear must reset the retry count");
    }

    @Test
    void independentScopesHaveIndependentRetryCounts() {
        UUID scopeB = UUID.randomUUID();

        repo.incrementAndGetRetryCount(USER_A, "dm", SCOPE_A);
        repo.incrementAndGetRetryCount(USER_A, "dm", SCOPE_A);

        assertEquals(1, repo.incrementAndGetRetryCount(USER_A, "dm", scopeB),
                "different scope must have independent retry count");
        assertEquals(3, repo.incrementAndGetRetryCount(USER_A, "dm", SCOPE_A),
                "original scope count must be unaffected");
    }

    // ----- stubs -----------------------------------------------------------

    private static class RecordingDataSource implements DataSource {
        int executedUpdateCount = 0;
        String lastSql = "";

        void resetCounters() {
            executedUpdateCount = 0;
            lastSql = "";
        }

        @Override
        public Connection getConnection() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] { Connection.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> newPreparedStatement((String) args[0]);
                        case "createArrayOf" -> newArray();
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(
                                "Connection." + method.getName());
                    });
        }

        private PreparedStatement newPreparedStatement(String sql) {
            lastSql = sql;
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[] { PreparedStatement.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "setObject", "setString", "setArray", "setInt" -> null;
                        case "executeUpdate" -> {
                            executedUpdateCount++;
                            yield 1;
                        }
                        case "executeQuery" -> emptyResultSet();
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(
                                "PS." + method.getName());
                    });
        }

        private Array newArray() {
            return (Array) Proxy.newProxyInstance(
                    Array.class.getClassLoader(),
                    new Class<?>[] { Array.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getArray" -> new String[0];
                        case "free" -> null;
                        default -> throw new UnsupportedOperationException(
                                "Array." + method.getName());
                    });
        }

        private ResultSet emptyResultSet() {
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[] { ResultSet.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> false;
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(
                                "RS." + method.getName());
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
}
