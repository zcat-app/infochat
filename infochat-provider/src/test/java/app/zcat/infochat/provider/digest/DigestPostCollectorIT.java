package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-tier IT for {@link DigestPostCollector}: the SQL LIMIT enforces the
 * {@code infochat.summary.cluster-cap} bound the on-demand /summary path
 * applies (M1-263 acceptance item 3), and the collector's queries run under
 * the profile-driven {@code statement_timeout} (item 5). Mirrors the
 * {@code EligiblePostQueryIT} / {@code EligiblePostQueryStatementTimeoutIT}
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
        cleanupFixtures();
        groupId = insertGroup("group");
        sourceId = insertSource();
        insertSubscription(groupId, sourceId);

        collector = new DigestPostCollector();
        collector.dataSource = dataSource;
        collector.cancellationService = cancellationService;
        collector.clusterCap = 2;
    }

    // @AfterEach too: a bootstrap-origin fixture source is visible to EVERY
    // scope under the D59 world predicate, so one left behind would pollute
    // other classes' scope-isolated retrieval assertions.
    @AfterEach
    void tearDown() throws Exception {
        cleanupFixtures();
    }

    private void cleanupFixtures() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM post WHERE uid LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM scope_tag "
                    + "WHERE tag_id IN (SELECT id FROM tag WHERE name LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM scope_preferences "
                    + "WHERE scope_id IN (SELECT id FROM groups "
                    + "                    WHERE upstream_group_id LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM tag WHERE name LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM source_exclusion "
                    + "WHERE source_id IN (SELECT id FROM source "
                    + "                     WHERE identifier LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM source_subscription "
                    + "WHERE source_id IN (SELECT id FROM source "
                    + "                     WHERE identifier LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM source WHERE identifier LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM groups WHERE upstream_group_id LIKE '" + PREFIX + "%'");
        }
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
    void bootstrapPostCollectedForGroupWithZeroSubscriptions() throws Exception {
        // Acceptance item 2 test (a), periodic-digest half (M1-621): the
        // implicit bootstrap corpus reaches a group that never subscribed
        // to anything — the empty-digest cliff this redesign removes.
        UUID freshGroup = insertGroup("fresh-group");
        UUID bootstrapSource = insertBootstrapSource("boot-src");
        insertPost("boot-0", bootstrapSource, "Bootstrap digest post",
                Instant.now().minus(Duration.ofMinutes(1)));

        DigestPostCollector.CollectionResult result =
                collector.collectForGroup(freshGroup, Instant.now().minus(Duration.ofHours(1)));

        assertEquals(1, result.posts().size(),
                "a subscription-less group's digest collects the bootstrap corpus");
        assertEquals("Bootstrap digest post", result.posts().get(0).title());
    }

    @Test
    void exclusionHidesBootstrapSourceForThatGroupOnly() throws Exception {
        UUID excludingGroup = insertGroup("excl-group");
        UUID otherGroup = insertGroup("other-group");
        UUID bootstrapSource = insertBootstrapSource("excl-src");
        insertPost("excl-0", bootstrapSource, "Excluded for one group",
                Instant.now().minus(Duration.ofMinutes(1)));
        insertExclusion(excludingGroup, bootstrapSource);

        Instant since = Instant.now().minus(Duration.ofHours(1));
        assertTrue(collector.collectForGroup(excludingGroup, since).posts().isEmpty(),
                "the excluding group's digest drops the source");
        assertEquals(1, collector.collectForGroup(otherGroup, since).posts().size(),
                "another group still collects it (exclusion is per-scope)");
    }

    @Test
    void explicitTagModeStillNarrowsTheDigest() throws Exception {
        // The digest KEEPS follow-tag narrowing under D59 (only chat/RAG
        // decoupled, acceptance item 3): an EXPLICIT group collects only
        // posts carrying a followed tag, bootstrap corpus included.
        UUID explicitGroup = insertGroup("explicit-group");
        UUID bootstrapSource = insertBootstrapSource("explicit-src");
        UUID alphaTag = insertTag("alpha");
        insertScopeTag(explicitGroup, alphaTag);
        insertScopePreferences(explicitGroup, "EXPLICIT");
        Instant now = Instant.now();
        insertPost("explicit-a", bootstrapSource, "Alpha post",
                now.minus(Duration.ofMinutes(1)), List.of(PREFIX + "alpha"));
        insertPost("explicit-b", bootstrapSource, "Beta post",
                now.minus(Duration.ofMinutes(2)), List.of(PREFIX + "beta"));

        DigestPostCollector.CollectionResult result =
                collector.collectForGroup(explicitGroup, now.minus(Duration.ofHours(1)));

        assertEquals(1, result.posts().size(),
                "EXPLICIT mode narrows the digest to followed tags");
        assertEquals("Alpha post", result.posts().get(0).title());
    }

    @Test
    void nullPublishedAtPostIsCollected() throws Exception {
        // published_at is nullable (V7__joins_post.sql:145) — a source need
        // not supply a date. Under the old `published_at >= ?` window such a
        // post could never satisfy the comparison and was invisible for its
        // entire lifetime; the ready_at window reaches it (M1-689).
        Instant now = Instant.now();
        insertPost("null-pub", sourceId, "No publication date",
                null, now.minus(Duration.ofMinutes(1)), List.of());

        DigestPostCollector.CollectionResult result =
                collector.collectForGroup(groupId, now.minus(Duration.ofHours(1)));

        assertEquals(1, result.posts().size(),
                "a post whose source supplied no published_at is still readable "
                        + "and must reach the digest");
        assertEquals("No publication date", result.posts().get(0).title());
        assertNull(result.posts().get(0).publishedAt(),
                "the absent publication date survives collection as null rather "
                        + "than throwing or being substituted");
    }

    @Test
    void postBecomingReadyAfterAnEmptySlotIsCollectedByTheNextSlot() throws Exception {
        // The zero-post boundary property M1-688 deliberately left to this
        // ticket (see its §Notes): an empty slot still writes its own run time
        // as the next slot's `since`. Under the published_at window a post
        // published before that boundary but evaluated after it fell between
        // the two slots and was never delivered — not late, never. Keyed on
        // ready_at the advance is lossless.
        Instant emptySlotBoundary = Instant.now().minus(Duration.ofMinutes(30));
        insertPost("late-arrival", sourceId, "Late arrival",
                emptySlotBoundary.minus(Duration.ofHours(6)),
                emptySlotBoundary.plus(Duration.ofMinutes(5)),
                List.of());

        DigestPostCollector.CollectionResult result =
                collector.collectForGroup(groupId, emptySlotBoundary);

        assertEquals(1, result.posts().size(),
                "a post that became READY after the empty slot's boundary must be "
                        + "collected by the next slot, however old its feed date");
        assertEquals("Late arrival", result.posts().get(0).title());
    }

    @Test
    void collectForGroupQueriesRunUnderStatementTimeout() throws Exception {
        RecordingDataSource recordingDataSource = new RecordingDataSource(dataSource);
        collector.dataSource = recordingDataSource;

        collector.collectForGroup(groupId, Instant.now());

        assertTrue(recordingDataSource.executedSql().stream()
                        .anyMatch(sql -> sql.contains("SET LOCAL statement_timeout")),
                "DigestPostCollector's connection must run under statement_timeout. "
                        + "Got: " + recordingDataSource.executedSql());
    }

    // -- fixture helpers ------------------------------------------------------

    private UUID insertGroup(String suffix) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO groups (adapter, upstream_group_id, display_name, timezone) "
                             + "VALUES ('inmemory', ?, 'Collector IT Group', 'UTC') RETURNING id")) {
            ps.setString(1, PREFIX + suffix);
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

    /** A live bootstrap-origin source — implicitly in every scope's world (D59). */
    private UUID insertBootstrapSource(String suffix) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "bootstrap_tags, status, source_origin) "
                             + "VALUES ('rss', ?, 'Collector IT Bootstrap', 'news', '{}', "
                             + "'active', 'bootstrap') RETURNING id")) {
            ps.setString(1, PREFIX + suffix);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void insertExclusion(UUID scopeId, UUID excludedSourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source_exclusion (scope_kind, scope_id, source_id) "
                             + "VALUES ('group', ?, ?)")) {
            ps.setObject(1, scopeId);
            ps.setObject(2, excludedSourceId);
            ps.executeUpdate();
        }
    }

    private UUID insertTag(String suffix) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tag (name, display, source_origin) "
                             + "VALUES (?, ?, 'user') RETURNING id")) {
            ps.setString(1, PREFIX + suffix);
            ps.setString(2, PREFIX + suffix);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void insertScopeTag(UUID scopeId, UUID tagId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO scope_tag (scope_kind, scope_id, tag_id) "
                             + "VALUES ('group', ?, ?)")) {
            ps.setObject(1, scopeId);
            ps.setObject(2, tagId);
            ps.executeUpdate();
        }
    }

    private void insertScopePreferences(UUID scopeId, String tagMode) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO scope_preferences (scope_kind, scope_id, tag_mode) "
                             + "VALUES ('group', ?, ?) ON CONFLICT (scope_kind, scope_id) "
                             + "DO UPDATE SET tag_mode = EXCLUDED.tag_mode")) {
            ps.setObject(1, scopeId);
            ps.setString(2, tagMode);
            ps.executeUpdate();
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
        insertPost(uidSuffix, sourceId, title, publishedAt, List.of());
    }

    private void insertPost(String uidSuffix, UUID postSourceId, String title,
                            Instant publishedAt) throws Exception {
        insertPost(uidSuffix, postSourceId, title, publishedAt, List.of());
    }

    /**
     * Seeds a post whose {@code ready_at} mirrors its {@code published_at} —
     * the negligible-lag shape, where both windows agree on membership.
     */
    private void insertPost(String uidSuffix, UUID postSourceId, String title,
                            Instant publishedAt, List<String> tags) throws Exception {
        insertPost(uidSuffix, postSourceId, title, publishedAt, publishedAt, tags);
    }

    /**
     * Seeds a post with independent publication and readiness instants.
     * {@code publishedAt} may be null — the column is nullable and a source
     * need not supply a date. {@code readyAt} is what the digest window
     * compares against and is always set, matching every {@code
     * status='READY'} writer in the pipeline.
     */
    private void insertPost(String uidSuffix, UUID postSourceId, String title,
                            @Nullable Instant publishedAt, Instant readyAt,
                            List<String> tags) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (uid, source_id, title, body, published_at, ready_at, "
                             + "status, tags, upstream_identifier) "
                             + "VALUES (?, ?, ?, ?, ?, ?, 'READY', ?, ?)")) {
            ps.setString(1, PREFIX + uidSuffix);
            ps.setObject(2, postSourceId);
            ps.setString(3, title);
            ps.setString(4, "Body for " + title);
            ps.setTimestamp(5, publishedAt == null ? null : Timestamp.from(publishedAt));
            ps.setTimestamp(6, Timestamp.from(readyAt));
            ps.setArray(7, conn.createArrayOf("TEXT", tags.toArray(new String[0])));
            ps.setString(8, PREFIX + uidSuffix);
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
     * {@code SET LOCAL statement_timeout} lands), delegating every other call to
     * the real connection — same shape as the recorder in
     * {@code EligiblePostQueryStatementTimeoutIT}.
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
