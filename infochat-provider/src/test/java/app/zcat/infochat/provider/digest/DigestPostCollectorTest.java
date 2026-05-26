package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.summary.EligiblePostQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link DigestPostCollector}'s SQL-driven post collection for
 * a group scope. JDBC is stubbed via dynamic proxies (Mockito is
 * intentionally absent from the Provider classpath).
 */
class DigestPostCollectorTest {

    private static final UUID GROUP_ID = UUID.randomUUID();
    private static final Instant SINCE = Instant.parse("2026-05-25T00:00:00Z");

    private DigestPostCollector collector;

    @BeforeEach
    void setUp() {
        collector = new DigestPostCollector();
    }

    @Test
    void collectForGroup_filtersOnActiveSubscriptions() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        Instant published = Instant.parse("2026-05-25T10:00:00Z");

        collector.dataSource = new StubDataSource(
                "EXPLICIT", 3L, 5L,
                List.of(new PostRow(postId, "uid-1", sourceId, "TechCrunch",
                        "Bitcoin $100k", "https://tc.com/btc", "body",
                        published, new String[]{"crypto", "bitcoin"})));

        DigestPostCollector.CollectionResult result =
                collector.collectForGroup(GROUP_ID, SINCE);

        assertEquals(1, result.posts().size());
        EligiblePostQuery.Post post = result.posts().getFirst();
        assertEquals(postId, post.id());
        assertEquals("uid-1", post.uid());
        assertEquals("TechCrunch", post.sourceDisplayName());
        assertEquals("Bitcoin $100k", post.title());
        assertEquals(published, post.publishedAt());
        assertEquals(List.of("crypto", "bitcoin"), post.tags());
        assertEquals(3L, result.tagSubscriptionVersion());
        assertEquals(5L, result.sourceSubscriptionVersion());
    }

    @Test
    void collectForGroup_returnsEmptyWhenNoSubscriptions() throws SQLException {
        collector.dataSource = new StubDataSource("ALL", 0L, 0L, List.of());

        DigestPostCollector.CollectionResult result =
                collector.collectForGroup(GROUP_ID, SINCE);

        assertTrue(result.posts().isEmpty());
        assertEquals(0L, result.tagSubscriptionVersion());
        assertEquals(0L, result.sourceSubscriptionVersion());
    }

    // ----- JDBC stubs (no Mockito) -----------------------------------------

    record PostRow(UUID id, String uid, UUID sourceId, String displayName,
                   String title, String url, String body, Instant publishedAt,
                   String[] tags) {}

    /**
     * Hand-rolled DataSource stub that returns canned scope_preferences
     * and post rows. Routes queries by inspecting the SQL string.
     */
    private static class StubDataSource implements DataSource {
        private final String tagMode;
        private final long tagVer;
        private final long srcVer;
        private final List<PostRow> posts;

        StubDataSource(String tagMode, long tagVer, long srcVer, List<PostRow> posts) {
            this.tagMode = tagMode;
            this.tagVer = tagVer;
            this.srcVer = srcVer;
            this.posts = posts;
        }

        @Override
        public Connection getConnection() {
            return proxyConnection();
        }

        @Override
        public Connection getConnection(String u, String p) { return getConnection(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int s) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
        @Override public <T> T unwrap(Class<T> i) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> i) { return false; }

        private Connection proxyConnection() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> newPreparedStatement((String) args[0]);
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(
                                "Connection." + method.getName());
                    });
        }

        private PreparedStatement newPreparedStatement(String sql) {
            boolean isScopePrefs = sql.contains("scope_preferences");
            AtomicReference<Timestamp> capturedTimestamp = new AtomicReference<>();
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "setObject", "setString" -> null;
                        case "setTimestamp" -> {
                            capturedTimestamp.set((Timestamp) args[1]);
                            yield null;
                        }
                        case "executeQuery" ->
                                isScopePrefs ? scopePrefsResultSet() : postsResultSet();
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(
                                "PreparedStatement." + method.getName());
                    });
        }

        private ResultSet scopePrefsResultSet() {
            boolean[] consumed = {false};
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> {
                            if (consumed[0]) yield false;
                            consumed[0] = true;
                            yield true;
                        }
                        case "getString" -> {
                            String col = (String) args[0];
                            yield "tag_mode".equals(col) ? tagMode : null;
                        }
                        case "getLong" -> {
                            String col = (String) args[0];
                            yield "tag_subscription_version".equals(col) ? tagVer : srcVer;
                        }
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(
                                "ResultSet." + method.getName());
                    });
        }

        private ResultSet postsResultSet() {
            int[] cursor = {-1};
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> {
                            cursor[0]++;
                            yield cursor[0] < posts.size();
                        }
                        case "getObject" -> {
                            String col = (String) args[0];
                            PostRow row = posts.get(cursor[0]);
                            yield switch (col) {
                                case "id" -> row.id();
                                case "source_id" -> row.sourceId();
                                default -> throw new UnsupportedOperationException(
                                        "getObject(" + col + ")");
                            };
                        }
                        case "getString" -> {
                            String col = (String) args[0];
                            PostRow row = posts.get(cursor[0]);
                            yield switch (col) {
                                case "uid" -> row.uid();
                                case "display_name" -> row.displayName();
                                case "title" -> row.title();
                                case "url" -> row.url();
                                case "body" -> row.body();
                                default -> null;
                            };
                        }
                        case "getTimestamp" -> {
                            PostRow row = posts.get(cursor[0]);
                            yield Timestamp.from(row.publishedAt());
                        }
                        case "getArray" -> {
                            PostRow row = posts.get(cursor[0]);
                            yield stubArray(row.tags());
                        }
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(
                                "ResultSet." + method.getName());
                    });
        }

        private static Array stubArray(String[] values) {
            return (Array) Proxy.newProxyInstance(
                    Array.class.getClassLoader(),
                    new Class<?>[]{Array.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getArray" -> values;
                        case "free" -> null;
                        default -> throw new UnsupportedOperationException(
                                "Array." + method.getName());
                    });
        }
    }
}
