package app.zcat.infochat.provider.chat.tool;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.chat.InFlightTracker;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral assertions for {@link SearchPostsTool}'s result shape:
 * the emitted {@code ready_at} JSON field carries the post's
 * {@code ready_at} column value (the spec's tool-catalogue shape),
 * not {@code published_at}. Seeds fixtures directly via JDBC against
 * the &#64;QuarkusTest DevServices DB.
 */
@QuarkusTest
class SearchPostsToolTest {

    private static final String PREFIX = "search-posts-test/";
    /** All fixtures share one fetched_at so they land in the V11/V28/V29 May 2026 partition. */
    private static final Instant FETCHED_AT = Instant.parse("2026-05-22T12:00:00Z");

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    SearchPostsTool tool;

    @Inject
    CancellationService cancellationService;

    @Inject
    InFlightTracker inFlightTracker;

    @BeforeEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                "DELETE FROM source_subscription WHERE source_id IN "
                    + "(SELECT id FROM source WHERE identifier LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM post WHERE uid LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM source WHERE identifier LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE '" + PREFIX + "%'");
        }
    }

    @Test
    void readyAtFieldCarriesReadyAtColumnValueNotPublishedAt() throws Exception {
        UUID userId = seedUser("ready-at");
        UUID sourceId = seedSource("ready-at-src", "Ready-at source");
        seedSubscription("dm", userId, sourceId);
        // published_at must sit inside the default search window
        // (published_at is the window filter); ready_at is a distinct
        // value so the assertion can tell the two columns apart.
        Instant publishedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                .minus(1, ChronoUnit.HOURS);
        Instant readyAt = publishedAt.plus(15, ChronoUnit.MINUTES);
        seedReadyPost("ready-at-post", sourceId, publishedAt, readyAt);

        String json = tool.execute(userId, "dm", userId, Map.of());

        assertTrue(json.contains("\"ready_at\":\"" + readyAt + "\""),
            "the ready_at JSON field carries the ready_at column value; got: " + json);
        assertFalse(json.contains("\"ready_at\":\"" + publishedAt + "\""),
            "the ready_at JSON field must not carry published_at; got: " + json);
    }

    @Test
    void searchAcquiresOneConnectionAppliesTimeoutAndRegistersPid() throws Exception {
        UUID userId = seedUser("arm");
        UUID sourceId = seedSource("arm-src", "Arm source");
        seedSubscription("dm", userId, sourceId);
        Instant publishedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                .minus(1, ChronoUnit.HOURS);
        seedReadyPost("arm-post", sourceId, publishedAt, publishedAt.plus(5, ChronoUnit.MINUTES));

        // Construct the tool against a counting/recording DataSource that
        // delegates to the seed DB, plus the CDI CancellationService (whose
        // InFlightTracker is the injected singleton). The tool runs real SQL;
        // the wrapper only observes connection acquisitions and executed SQL.
        CountingRecordingDataSource countingDs = new CountingRecordingDataSource(dataSource);
        SearchPostsTool directTool = new SearchPostsTool(countingDs, cancellationService);

        // Hold the in-flight slot as ChatAgent.handle() does for a chat turn,
        // so the tool has a handle to register the backend pid on.
        InFlightTracker.CancellationHandle slot =
                Objects.requireNonNull(inFlightTracker.tryAcquire(userId, "dm", userId));
        try {
            directTool.execute(userId, "dm", userId, Map.of());

            assertEquals(1, countingDs.connectionCount(),
                    "SearchPostsTool must acquire exactly one pooled connection per call");
            assertTrue(countingDs.executedSql().stream()
                            .anyMatch(s -> s.contains("SET statement_timeout")),
                    "the single connection must have statement_timeout applied. Got: "
                            + countingDs.executedSql());
            assertTrue(slot.hasPgBackendPid(),
                    "the tool must register the connection's pg backend pid on the in-flight handle");
        } finally {
            inFlightTracker.release(userId, "dm", userId, slot);
        }
    }

    // ---------- helpers ----------

    private UUID seedUser(String suffix) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                     + "VALUES ('inmemory', ?, FALSE, 'vouched') RETURNING id")) {
            ps.setString(1, PREFIX + suffix);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID seedSource(String suffix, String displayName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, "
                     + "bootstrap_tags, status) "
                     + "VALUES ('rss', ?, ?, 'news', '{}', 'active') RETURNING id")) {
            ps.setString(1, PREFIX + suffix);
            ps.setString(2, displayName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void seedSubscription(String scopeKind, UUID scopeId, UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source_subscription (scope_kind, scope_id, source_id) "
                     + "VALUES (?, ?, ?)")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setObject(3, sourceId);
            ps.executeUpdate();
        }
    }

    private void seedReadyPost(String slug, UUID sourceId,
                               Instant publishedAt, Instant readyAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post (uid, source_id, title, body, url, published_at, "
                     + "fetched_at, status, ready_at, tags) "
                     + "VALUES (?, ?, ?, ?, ?, ?, ?, 'READY', ?, '{}')")) {
            ps.setString(1, PREFIX + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "Title " + slug);
            ps.setString(4, "Body " + slug);
            ps.setString(5, "https://example.com/" + slug);
            ps.setTimestamp(6, Timestamp.from(publishedAt));
            ps.setTimestamp(7, Timestamp.from(FETCHED_AT));
            ps.setTimestamp(8, Timestamp.from(readyAt));
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    /**
     * Wraps a real {@link DataSource}, counting {@code getConnection()} calls
     * and recording the SQL executed on each connection (both
     * {@code createStatement().execute/executeQuery} — e.g. the
     * {@code SET statement_timeout} and {@code SELECT pg_backend_pid()} the
     * arming step issues — and {@code prepareStatement}). Every other call is
     * delegated to the real connection, so the tool's queries run for real.
     */
    static final class CountingRecordingDataSource implements DataSource {
        private final DataSource delegate;
        private int connectionCount;
        private final List<String> executedSql = new ArrayList<>();

        CountingRecordingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        int connectionCount() {
            return connectionCount;
        }

        List<String> executedSql() {
            return executedSql;
        }

        @Override
        public Connection getConnection() throws SQLException {
            connectionCount++;
            Connection real = delegate.getConnection();
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] { Connection.class },
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("prepareStatement") && args != null
                                && args.length > 0 && args[0] instanceof String sql) {
                            executedSql.add(sql);
                        } else if (name.equals("createStatement")) {
                            return wrapStatement(
                                    (Statement) Objects.requireNonNull(invoke(real, method, args)));
                        }
                        return invoke(real, method, args);
                    });
        }

        private Statement wrapStatement(Statement realStmt) {
            return (Statement) Proxy.newProxyInstance(
                    Statement.class.getClassLoader(),
                    new Class<?>[] { Statement.class },
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ((name.equals("execute") || name.equals("executeQuery"))
                                && args != null && args.length > 0 && args[0] instanceof String sql) {
                            executedSql.add(sql);
                        }
                        return invoke(realStmt, method, args);
                    });
        }

        private static @Nullable Object invoke(Object target, Method method,
                                               Object @Nullable [] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                throw cause != null ? cause : e;
            }
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
        @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
        @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
        @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
        @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
    }
}
