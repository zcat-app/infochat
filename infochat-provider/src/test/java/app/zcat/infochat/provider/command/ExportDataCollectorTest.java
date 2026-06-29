package app.zcat.infochat.provider.command;

import app.zcat.infochat.provider.chat.CancellationService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link ExportDataCollector} against the
 * DevServices Postgres container. Verifies the field-level positive
 * list, scope filtering, and authorization-field exclusion.
 */
@QuarkusTest
class ExportDataCollectorTest {

    private static final String PREFIX = "m1-067-coll-";
    private static final String ADAPTER = "inmemory";

    @Inject ExportDataCollector collector;
    @Inject @SeedDataSource DataSource dataSource;
    @Inject CancellationService cancellationService;

    @BeforeEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // FK-safe cleanup order: children first, then parents.
            exec(conn, "DELETE FROM saved_post WHERE user_id IN ("
                    + "SELECT id FROM users WHERE contact_id LIKE ?)", PREFIX + "%");
            exec(conn, "DELETE FROM source_subscription WHERE scope_id IN ("
                    + "SELECT id FROM users WHERE contact_id LIKE ?)", PREFIX + "%");
            exec(conn, "DELETE FROM chat_memory WHERE user_id IN ("
                    + "SELECT id FROM users WHERE contact_id LIKE ?)", PREFIX + "%");
            exec(conn, "DELETE FROM summary_anchor WHERE user_id IN ("
                    + "SELECT id FROM users WHERE contact_id LIKE ?)", PREFIX + "%");

            exec(conn, "ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_delete");
            try {
                exec(conn, "DELETE FROM audit_log WHERE actor_user_id IN ("
                        + "SELECT id FROM users WHERE contact_id LIKE ?)", PREFIX + "%");
                exec(conn, "DELETE FROM audit_log WHERE target_id IN ("
                        + "SELECT id::text FROM users WHERE contact_id LIKE ?)", PREFIX + "%");
            } finally {
                exec(conn, "ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_delete");
            }

            exec(conn, "DELETE FROM users WHERE contact_id LIKE ?", PREFIX + "%");
        }
    }

    @Test
    void collectsExactPositiveList() throws Exception {
        String contactId = PREFIX + "poslist-actor";
        UUID userId = seedUser(contactId);

        ExportDataCollector.ExportResult result =
                collector.collect(userId, "dm", userId);

        // Must contain exactly the 9 tables in the spec's positive
        // list, in order.
        assertEquals(ExportDataCollector.POSITIVE_LIST_TABLES,
                List.copyOf(result.tables().keySet()),
                "output keys must match positive-list tables in order");
    }

    @Test
    void excludesAuthorizationFields() throws Exception {
        String contactId = PREFIX + "auth-actor";
        UUID userId = seedUser(contactId);

        ExportDataCollector.ExportResult result =
                collector.collect(userId, "dm", userId);

        List<String> usersRows = result.tables().get("users");
        assertEquals(1, usersRows.size(), "users must have exactly one row");
        String userJson = usersRows.getFirst();

        // Authorization-state fields per spec §/export: is_admin,
        // banned_by, ban_reason, banned_at, probation_until.
        assertFalse(userJson.contains("\"is_admin\""),
                "users export must NOT contain is_admin");
        assertFalse(userJson.contains("\"banned_by\""),
                "users export must NOT contain banned_by");
        assertFalse(userJson.contains("\"ban_reason\""),
                "users export must NOT contain ban_reason");
        assertFalse(userJson.contains("\"banned_at\""),
                "users export must NOT contain banned_at");
        assertFalse(userJson.contains("\"probation_until\""),
                "users export must NOT contain probation_until");

        // Positive assertions: included fields should be present.
        assertTrue(userJson.contains("\"contact_id\""),
                "users export must contain contact_id");
        assertTrue(userJson.contains("\"registration_state\""),
                "users export must contain registration_state");
    }

    @Test
    void auditOnlyActorRows() throws Exception {
        String actorContactId = PREFIX + "audit-actor";
        String otherContactId = PREFIX + "audit-other";
        UUID actorId = seedUser(actorContactId);
        UUID otherId = seedUser(otherContactId);

        // Seed an audit row BY the actor (actor=actor, target=other).
        seedAuditRow(actorId, actorContactId, "EXPORT", "user", otherId.toString());
        // Seed an audit row BY the other user targeting the actor
        // (actor=other, target=actor).
        seedAuditRow(otherId, otherContactId, "EXPORT", "user", actorId.toString());

        ExportDataCollector.ExportResult result =
                collector.collect(actorId, "dm", actorId);

        List<String> auditRows = result.tables().get("audit_log_view");
        assertEquals(1, auditRows.size(),
                "audit export must include only actor-authored rows, not target-only rows");
        assertTrue(auditRows.getFirst().contains(actorId.toString()),
                "the included audit row must be the actor's own");
    }

    @Test
    void savedPostGlobalRegardlessOfScope() throws Exception {
        String contactId = PREFIX + "saved-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "saved-source");

        seedSavedPost(userId, sourceId, PREFIX + "saved-uid-1",
                new String[]{"tag1"}, Instant.now().minus(1, ChronoUnit.HOURS));
        seedSavedPost(userId, sourceId, PREFIX + "saved-uid-2",
                new String[]{"tag2"}, Instant.now());

        // Collect with DM scope — saved_post must include both rows
        // regardless of scope (D13: per-user-globally).
        ExportDataCollector.ExportResult result =
                collector.collect(userId, "dm", userId);

        List<String> savedRows = result.tables().get("saved_post");
        assertEquals(2, savedRows.size(),
                "saved_post must include all user's saves globally");
        assertTrue(savedRows.stream().anyMatch(r -> r.contains(PREFIX + "saved-uid-1")));
        assertTrue(savedRows.stream().anyMatch(r -> r.contains(PREFIX + "saved-uid-2")));
    }

    @Test
    void groupExportScopedCorrectly() throws Exception {
        String contactId = PREFIX + "group-actor";
        UUID userId = seedUser(contactId);
        UUID groupId = UUID.randomUUID();

        // Seed chat_memory in two scopes: the group and the user's DM.
        seedChatMemory(userId, "group", groupId, "group memory");
        seedChatMemory(userId, "dm", userId, "dm memory");

        // Collect with group scope.
        ExportDataCollector.ExportResult result =
                collector.collect(userId, "group", groupId);

        List<String> memoryRows = result.tables().get("chat_memory");
        assertEquals(1, memoryRows.size(),
                "group export must return only group-scoped chat_memory");
        assertTrue(memoryRows.getFirst().contains("group memory"),
                "the included row must be the group-scoped one");
    }

    @Test
    void ciShapeTestRefusesExtraKeys() throws Exception {
        String contactId = PREFIX + "shape-actor";
        UUID userId = seedUser(contactId);

        ExportDataCollector.ExportResult result =
                collector.collect(userId, "dm", userId);

        // The output must contain ONLY keys from the positive list.
        for (String key : result.tables().keySet()) {
            assertTrue(ExportDataCollector.POSITIVE_LIST_TABLES.contains(key),
                    "output contains unexpected table key: " + key);
        }
        // And it must contain ALL of them (even if empty).
        for (String expected : ExportDataCollector.POSITIVE_LIST_TABLES) {
            assertTrue(result.tables().containsKey(expected),
                    "output must contain table key: " + expected);
        }
    }

    @Test
    void auditExportExcludesTargetContactId() throws Exception {
        String actorContactId = PREFIX + "tci-actor";
        String targetContactId = PREFIX + "tci-target";
        UUID actorId = seedUser(actorContactId);
        UUID targetId = seedUser(targetContactId);

        seedAuditRow(actorId, actorContactId, "BAN", "user", targetId.toString());

        ExportDataCollector.ExportResult result =
                collector.collect(actorId, "dm", actorId);

        List<String> auditRows = result.tables().get("audit_log_view");
        assertEquals(1, auditRows.size());
        String row = auditRows.getFirst();
        // target_contact_id must NOT appear — defense against the V5
        // redact_contact_id stub leaking other users' full contact IDs.
        assertFalse(row.contains("\"target_contact_id\""),
                "audit export must NOT include target_contact_id column; got: " + row);
        assertFalse(row.contains(targetContactId),
                "audit export must NOT contain the target's full contact ID");
    }

    @Test
    void underCapTableNotFlaggedTruncated() throws Exception {
        String contactId = PREFIX + "trunc-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "trunc-source");

        // Seed 5 rows, well under the injected bean's default 10000-row
        // cap, so no table is flagged truncated. The over-cap path has its
        // own test (direct-instantiated with a small cap).
        for (int i = 0; i < 5; i++) {
            seedSavedPost(userId, sourceId, PREFIX + "trunc-uid-" + i,
                    new String[]{}, Instant.now().minus(i, ChronoUnit.HOURS));
        }

        ExportDataCollector.ExportResult result =
                collector.collect(userId, "dm", userId);

        assertTrue(result.truncatedTables().isEmpty(),
                "5 rows under the default cap must not trigger truncation");
        assertEquals(5, result.tables().get("saved_post").size());
    }

    @Test
    void exactlyCapFullTableNotFlaggedTruncated() throws Exception {
        String contactId = PREFIX + "capfull-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "capfull-source");
        for (int i = 0; i < 5; i++) {
            seedSavedPost(userId, sourceId, PREFIX + "capfull-uid-" + i,
                    new String[]{}, Instant.now().minus(i, ChronoUnit.HOURS));
        }

        // Direct instantiation: the injected bean carries the default
        // 10000-row cap, which cannot be overridden mid-test.
        ExportDataCollector smallCapCollector = new ExportDataCollector();
        smallCapCollector.dataSource = dataSource;
        smallCapCollector.cancellationService = cancellationService;
        smallCapCollector.maxRowsPerTable = 5;

        ExportDataCollector.ExportResult result =
                smallCapCollector.collect(userId, "dm", userId);

        assertFalse(result.truncatedTables().contains("saved_post"),
                "an exactly cap-full table must NOT be flagged truncated; got: "
                        + result.truncatedTables());
        assertEquals(5, result.tables().get("saved_post").size(),
                "all cap rows must be exported");
    }

    @Test
    void overCapTableFlaggedTruncatedAndCutAtCap() throws Exception {
        String contactId = PREFIX + "overcap-actor";
        UUID userId = seedUser(contactId);
        UUID sourceId = seedSource(PREFIX + "overcap-source");
        for (int i = 0; i < 6; i++) {
            seedSavedPost(userId, sourceId, PREFIX + "overcap-uid-" + i,
                    new String[]{}, Instant.now().minus(i, ChronoUnit.HOURS));
        }

        ExportDataCollector smallCapCollector = new ExportDataCollector();
        smallCapCollector.dataSource = dataSource;
        smallCapCollector.cancellationService = cancellationService;
        smallCapCollector.maxRowsPerTable = 5;

        ExportDataCollector.ExportResult result =
                smallCapCollector.collect(userId, "dm", userId);

        assertTrue(result.truncatedTables().contains("saved_post"),
                "a table with more rows than the cap must be flagged truncated");
        assertEquals(5, result.tables().get("saved_post").size(),
                "the probe row must not reach the export output");
    }

    /**
     * Acceptance pin (M1-410): {@code Types.OTHER} columns are
     * classified inline-vs-quote by their DECLARED SQL type, not by the
     * value's first character. JSONB columns ({@code cluster_map},
     * {@code details_json}) are emitted as raw inline JSON; UUID columns
     * — also {@code Types.OTHER} — are quoted strings. Exercising both
     * in one export proves the OTHER branch keys on the column type, not
     * the byte shape (UUID and JSONB share the same int type code).
     */
    @Test
    void otherColumnsClassifiedByDeclaredSqlType() throws Exception {
        String contactId = PREFIX + "othertype-actor";
        UUID userId = seedUser(contactId);

        seedSummaryAnchor(userId, "dm", userId, "{\"a\": 1}");
        seedAuditRowWithDetails(userId, contactId, "EXPORT", "user",
                userId.toString(), "{\"k\": \"v\"}");

        ExportDataCollector.ExportResult result =
                collector.collect(userId, "dm", userId);

        String anchorRow = result.tables().get("summary_anchor").getFirst();
        assertTrue(anchorRow.contains("\"cluster_map\":{"),
                "JSONB cluster_map must be inlined as raw JSON; got: " + anchorRow);
        assertFalse(anchorRow.contains("\"cluster_map\":\""),
                "JSONB cluster_map must NOT be quoted as a string; got: " + anchorRow);

        String auditRow = result.tables().get("audit_log_view").getFirst();
        assertTrue(auditRow.contains("\"details_json\":{"),
                "JSONB details_json must be inlined as raw JSON; got: " + auditRow);
        // actor_user_id is a UUID — also Types.OTHER, but must be quoted,
        // proving classification keys on the declared type, not the bytes.
        assertTrue(auditRow.contains("\"actor_user_id\":\"" + userId + "\""),
                "UUID actor_user_id must be a quoted string; got: " + auditRow);
    }

    /**
     * Acceptance pin: the export connection applies the standard
     * statement timeout before the first collection query, so a
     * pathological table cannot hold the connection unbounded.
     */
    @Test
    void appliesStatementTimeoutBeforeFirstCollectionQuery() throws Exception {
        String contactId = PREFIX + "timeout-actor";
        UUID userId = seedUser(contactId);

        SqlOrderRecordingDataSource recording = new SqlOrderRecordingDataSource(dataSource);
        ExportDataCollector timedCollector = new ExportDataCollector();
        timedCollector.dataSource = recording;
        timedCollector.cancellationService = cancellationService;
        timedCollector.maxRowsPerTable = 10_000;

        timedCollector.collect(userId, "dm", userId);

        List<String> sqlOrder = recording.sqlOrder();
        assertFalse(sqlOrder.isEmpty(), "the export must execute SQL");
        assertTrue(sqlOrder.get(0).contains("SET LOCAL statement_timeout"),
                "the statement timeout must be applied before any collection"
                        + " query runs. Got order: " + sqlOrder);
        assertEquals(1, sqlOrder.stream()
                        .filter(s -> s.contains("statement_timeout")).count(),
                "the timeout must be applied exactly once, on the shared"
                        + " export connection");
    }

    // -- helpers --

    /**
     * Wraps the real {@link DataSource} and records, in execution
     * order, every statement reaching the export connection — prepared
     * queries at prepare time, plain statements (where the
     * {@code SET LOCAL statement_timeout} lands) at execute time —
     * delegating every other call to the real Postgres connection.
     * Same shape as the recording wrappers in
     * {@code EligiblePostQueryStatementTimeoutIT} and
     * {@code DigestPostCollectorIT}.
     */
    static final class SqlOrderRecordingDataSource implements DataSource {
        private final DataSource delegate;
        private final List<String> sqlOrder = new ArrayList<>();

        SqlOrderRecordingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        List<String> sqlOrder() {
            return sqlOrder;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection real = delegate.getConnection();
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] { Connection.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> {
                            sqlOrder.add((String) args[0]);
                            yield invoke(real, method, args);
                        }
                        case "createStatement" ->
                                wrapStatement((Statement) invoke(real, method, args));
                        default -> invoke(real, method, args);
                    });
        }

        private Statement wrapStatement(Statement real) {
            return (Statement) Proxy.newProxyInstance(
                    Statement.class.getClassLoader(),
                    new Class<?>[] { Statement.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "execute", "executeQuery" -> {
                            sqlOrder.add((String) args[0]);
                            yield invoke(real, method, args);
                        }
                        default -> invoke(real, method, args);
                    });
        }

        // Rethrows the real cause (e.g. SQLException) instead of
        // InvocationTargetException so the code under test sees the
        // same exceptions a real connection would throw.
        private static Object invoke(Object target, Method method, Object[] args)
                throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        @Override public Connection getConnection(String u, String p) throws SQLException { return getConnection(); }
        @Override public PrintWriter getLogWriter() { throw new UnsupportedOperationException(); }
        @Override public void setLogWriter(PrintWriter out) { throw new UnsupportedOperationException(); }
        @Override public void setLoginTimeout(int seconds) { throw new UnsupportedOperationException(); }
        @Override public int getLoginTimeout() { throw new UnsupportedOperationException(); }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
        @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    private UUID seedUser(String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (adapter, contact_id, is_admin, is_banned,"
                             + " registration_state) VALUES (?, ?, FALSE, FALSE, 'vouched')"
                             + " RETURNING id")) {
            ps.setString(1, ADAPTER);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject("id", UUID.class);
            }
        }
    }

    private UUID seedSource(String identifier) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category,"
                             + " bootstrap_tags) VALUES ('rss', ?, ?, 'news', '{}')"
                             + " RETURNING id")) {
            ps.setString(1, identifier);
            ps.setString(2, "Test " + identifier);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject("id", UUID.class);
            }
        }
    }

    private void seedSavedPost(UUID userId, UUID sourceId, String postUid,
                               String[] personalTags, Instant savedAt) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO saved_post (user_id, post_uid, source_id, title,"
                             + " snapshot_tags, personal_tags, saved_at)"
                             + " VALUES (?, ?, ?, ?, '{}', ?, ?)")) {
            ps.setObject(1, userId);
            ps.setString(2, postUid);
            ps.setObject(3, sourceId);
            ps.setString(4, "Test " + postUid);
            ps.setArray(5, conn.createArrayOf("TEXT", personalTags));
            ps.setObject(6, OffsetDateTime.ofInstant(savedAt, ZoneOffset.UTC));
            ps.executeUpdate();
        }
    }

    private void seedChatMemory(UUID userId, String scopeKind, UUID scopeId,
                                String summary) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO chat_memory (user_id, scope_kind, scope_id,"
                             + " summary, keywords, referenced_posts, referenced_topics)"
                             + " VALUES (?, ?, ?, ?, '{}', '{}', '{}')")) {
            ps.setObject(1, userId);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            ps.setString(4, summary);
            ps.executeUpdate();
        }
    }

    private void seedSummaryAnchor(UUID userId, String scopeKind, UUID scopeId,
                                   String clusterMapJson) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO summary_anchor (user_id, scope_kind, scope_id,"
                             + " command_kind, command_name, arg_hash, post_uids,"
                             + " cluster_map)"
                             + " VALUES (?, ?, ?, 'personal', 'summary', 'h', '{}',"
                             + " ?::jsonb)")) {
            ps.setObject(1, userId);
            ps.setString(2, scopeKind);
            ps.setObject(3, scopeId);
            ps.setString(4, clusterMapJson);
            ps.executeUpdate();
        }
    }

    private void seedAuditRowWithDetails(UUID actorUserId, String actorContactId,
                                         String action, String targetKind,
                                         String targetId, String detailsJson)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO audit_log (actor_user_id, actor_contact_id,"
                             + " actor_adapter, action, target_kind, target_id,"
                             + " request_id, details_json)"
                             + " VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)")) {
            ps.setObject(1, actorUserId);
            ps.setString(2, actorContactId);
            ps.setString(3, ADAPTER);
            ps.setString(4, action);
            ps.setString(5, targetKind);
            ps.setString(6, targetId);
            ps.setString(7, UUID.randomUUID().toString());
            ps.setString(8, detailsJson);
            ps.executeUpdate();
        }
    }

    private void seedAuditRow(UUID actorUserId, String actorContactId,
                              String action, String targetKind,
                              String targetId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO audit_log (actor_user_id, actor_contact_id,"
                             + " actor_adapter, action, target_kind, target_id,"
                             + " request_id)"
                             + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, actorUserId);
            ps.setString(2, actorContactId);
            ps.setString(3, ADAPTER);
            ps.setString(4, action);
            ps.setString(5, targetKind);
            ps.setString(6, targetId);
            ps.setString(7, UUID.randomUUID().toString());
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql, Object... args) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }
}
