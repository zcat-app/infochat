package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-tier IT for {@link DigestPostCollector}: the SQL LIMIT enforces the
 * {@code infochat.summary.cluster-cap} bound the on-demand /summary path
 * applies (M1-263 acceptance item 3), and the collector's queries run under
 * the profile-driven {@code statement_timeout} (item 5). Mirrors the
 * {@code EligiblePostQueryIT} / {@code EligiblePostQueryStatementTimeoutTest}
 * patterns in the summary package. Fixtures are keyed on the
 * {@code m1-263c-} prefix and deleted before each test.
 */
@QuarkusTest
class DigestPostCollectorIT {

    private static final String PREFIX = "m1-263c-";

    @Inject @SeedDataSource DataSource dataSource;

    @Inject CancellationService cancellationService;

    private DigestPostCollector collector;
    private UUID groupId;
    private UUID sourceId;

    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM post WHERE uid LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM source_subscription "
                    + "WHERE source_id IN (SELECT id FROM source "
                    + "                     WHERE identifier LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM source WHERE identifier LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM groups WHERE upstream_group_id LIKE '" + PREFIX + "%'");
        }
        groupId = insertGroup();
        sourceId = insertSource();
        insertSubscription(groupId, sourceId);

        collector = new DigestPostCollector();
        collector.dataSource = dataSource;
        collector.cancellationService = cancellationService;
        collector.clusterCap = 2;
    }

    @Test
    void collectForGroupCapsRowsAtClusterCap() throws Exception {
        // 3 eligible posts against cap 2: the SQL LIMIT keeps the freshest
        // two (head of the DESC ordering) and drops the oldest, before post
        // bodies leave the database — the same bound /summary applies.
        Instant now = Instant.now();
        insertPost("cap-0", "Cap 0", now.minus(Duration.ofMinutes(1)));
        insertPost("cap-1", "Cap 1", now.minus(Duration.ofMinutes(2)));
        insertPost("cap-2", "Cap 2", now.minus(Duration.ofMinutes(3)));

        DigestPostCollector.CollectionResult result =
                collector.collectForGroup(groupId, now.minus(Duration.ofHours(1)));

        assertEquals(2, result.posts().size(),
                "3 eligible posts against cap 2 — the SQL LIMIT bounds the rows");
        assertEquals("Cap 0", result.posts().get(0).title(),
                "the freshest posts are kept, the oldest dropped");
        assertEquals("Cap 1", result.posts().get(1).title());
    }

    @Test
    void collectForGroupQueriesRunUnderStatementTimeout() throws Exception {
        RecordingDataSource recordingDataSource = new RecordingDataSource(dataSource);
        collector.dataSource = recordingDataSource;

        collector.collectForGroup(groupId, Instant.now());

        assertTrue(recordingDataSource.executedSql().stream()
                        .anyMatch(sql -> sql.contains("SET statement_timeout")),
                "DigestPostCollector's connection must run under statement_timeout. "
                        + "Got: " + recordingDataSource.executedSql());
    }

    // -- fixture helpers ------------------------------------------------------

    private UUID insertGroup() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO groups (adapter, upstream_group_id, display_name, timezone) "
                             + "VALUES ('inmemory', ?, 'Collector IT Group', 'UTC') RETURNING id")) {
            ps.setString(1, PREFIX + "group");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID insertSource() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "bootstrap_tags, status) "
                             + "VALUES ('rss', ?, 'Collector IT Source', 'news', '{}', 'active') "
                             + "RETURNING id")) {
            ps.setString(1, PREFIX + "src");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void insertSubscription(UUID scopeId, UUID subscribedSourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source_subscription (scope_kind, scope_id, source_id) "
                             + "VALUES ('group', ?, ?)")) {
            ps.setObject(1, scopeId);
            ps.setObject(2, subscribedSourceId);
            ps.executeUpdate();
        }
    }

    private void insertPost(String uidSuffix, String title, Instant publishedAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (uid, source_id, title, body, published_at, status, tags) "
                             + "VALUES (?, ?, ?, ?, ?, 'READY', '{}')")) {
            ps.setString(1, PREFIX + uidSuffix);
            ps.setObject(2, sourceId);
            ps.setString(3, title);
            ps.setString(4, "Body for " + title);
            ps.setTimestamp(5, Timestamp.from(publishedAt));
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    /**
     * Wraps the real {@link DataSource} and records the SQL run via
     * {@code createStatement().execute(...)} on each connection (where the
     * {@code SET statement_timeout} lands), delegating every other call to
     * the real connection — same shape as the recorder in
     * {@code EligiblePostQueryStatementTimeoutTest}.
     */
    static final class RecordingDataSource implements DataSource {
        private final DataSource delegate;
        private final List<String> executedSql = new ArrayList<>();

        RecordingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        List<String> executedSql() {
            return executedSql;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection real = delegate.getConnection();
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] { Connection.class },
                    (proxy, method, args) -> {
                        if (method.getName().equals("createStatement")) {
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
                        if (method.getName().equals("execute")
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
