package app.zcat.infochat.core.schema;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-cutting integration test for Invariant 1 — per-(user, scope)
 * isolation at the schema layer (docs/spec/schema.md §Invariants).
 *
 * <p>Each subticket (M1-008a/b/c) verifies its own slice; this IT
 * verifies the property the subtickets cannot prove in isolation:
 * a scope-filtered SELECT against the three per-scope join tables
 * (source_subscription, scope_tag, scope_preferences) returns ONLY
 * rows in the queried (scope_kind, scope_id), never leaking across
 * discriminator boundaries. The post-via-join chain is exercised
 * end-to-end so a join-shape bug that lets a subscription row in one
 * scope match the posts of a different scope through {@code
 * post.source_id} would surface here.
 *
 * <p>The {@code *IT} suffix is intentional: maven-failsafe (not
 * surefire) picks it up under {@code mvn verify}. M1-008a authored
 * the failsafe wiring anticipating this consumer.
 *
 * <p>Reuses {@link PostgresSchemaTestBase} for the Testcontainers
 * Postgres + Flyway-applied V1..V7 schema. Seeding uses raw JDBC; no
 * Quarkus context boots — the IT exercises the SCHEMA, not Quarkus
 * wiring. Per-test scope_ids are fresh UUIDs so leftover rows in
 * {@code scope_preferences} (which the parent's cascading TRUNCATE
 * does not reach — scope_preferences has no FK to any §2.1 table)
 * cannot interfere across @Test methods.
 */
class PerScopeIsolationIT extends PostgresSchemaTestBase {

    /**
     * Sanity-check the migration cursor BEFORE asserting anything
     * else. A misconfigured test profile that skipped V5/V6/V7 would
     * produce SELECTs against an empty schema that pass silently with
     * zero rows. The cursor check is the fast canary.
     */
    @Test
    void migrationCursorReachesV7() throws SQLException {
        try (Connection c = newConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT version FROM flyway_schema_history "
                             + "WHERE success = TRUE "
                             + "ORDER BY installed_rank DESC LIMIT 1")) {
            assertTrue(rs.next(), "expected at least one applied Flyway version");
            int maxVersion = Integer.parseInt(rs.getString("version"));
            assertTrue(maxVersion >= 7,
                    "expected Flyway max version >= 7 (V5/V6/V7 applied); got: " + maxVersion);
        }
    }

    /**
     * Seeds users A and B, group G with A and B both as members,
     * one source and one tag, and inserts per-scope rows for the
     * (dm, A), (dm, B), and (group, G) discriminators into each of
     * source_subscription, scope_tag, scope_preferences. Asserts:
     *
     * <ul>
     *   <li>For each per-scope join table, the scope-filtered SELECT
     *       under each of the three discriminators returns ONLY the
     *       row seeded under that discriminator — no cross-scope
     *       leak.</li>
     *   <li>The four logical (user, scope) combinations (A, DM),
     *       (B, DM), (A, group:G), (B, group:G) each resolve to a
     *       reachable row. (A, group:G) and (B, group:G) resolve to
     *       the SAME row — group scope is shared per
     *       docs/spec/schema.md §Sources and tags.</li>
     * </ul>
     */
    @Test
    void perScopeIsolationAcrossThreeTables() throws SQLException {
        try (Connection c = newConnection()) {
            String userA  = insertUser(c, "alice-" + UUID.randomUUID());
            String userB  = insertUser(c, "bob-"   + UUID.randomUUID());
            String groupG = insertGroup(c, "group-" + UUID.randomUUID());
            insertMembership(c, groupG, userA);
            insertMembership(c, groupG, userB);

            String sourceId = insertSource(c, "rss-iso-" + UUID.randomUUID());
            String tagId    = insertTag(c, "iso" + UUID.randomUUID().toString().substring(0, 8));

            // Three rows per table covering (dm, A), (dm, B), (group, G).
            // (A, group:G) and (B, group:G) share the (group, G) row —
            // group scope is shared, not per-user.
            insertSourceSubscription(c, "dm",    userA,  sourceId);
            insertSourceSubscription(c, "dm",    userB,  sourceId);
            insertSourceSubscription(c, "group", groupG, sourceId);

            insertScopeTag(c, "dm",    userA,  tagId);
            insertScopeTag(c, "dm",    userB,  tagId);
            insertScopeTag(c, "group", groupG, tagId);

            insertScopePreferences(c, "dm",    userA);
            insertScopePreferences(c, "dm",    userB);
            insertScopePreferences(c, "group", groupG);

            for (String table : new String[] {"source_subscription", "scope_tag", "scope_preferences"}) {
                assertExactlyOneRowMatchingScope(c, table, "dm",    userA);
                assertExactlyOneRowMatchingScope(c, table, "dm",    userB);
                assertExactlyOneRowMatchingScope(c, table, "group", groupG);

                // Four logical (user, scope) combinations resolve to ≥1 row
                // each. The group entries collapse onto the shared row by
                // design — this is Invariant 1's group-scope clause.
                assertEquals(1, countByScope(c, table, "dm",    userA),
                        table + " (A, DM) combination");
                assertEquals(1, countByScope(c, table, "dm",    userB),
                        table + " (B, DM) combination");
                assertEquals(1, countByScope(c, table, "group", groupG),
                        table + " (A, group:G) combination — shared group row");
                assertEquals(1, countByScope(c, table, "group", groupG),
                        table + " (B, group:G) combination — shared group row");
            }
        }
    }

    /**
     * Regression guard: an INSERT with {@code scope_kind = NULL} must
     * raise a NOT-NULL violation on each per-scope join. The PK on
     * {@code (scope_kind, scope_id, ...)} already implies NOT NULL,
     * but a future migration that reshapes the PK without re-asserting
     * the column-level NOT NULL would surface here.
     */
    @Test
    void nullScopeKindRejectedAcrossAllJoinTables() throws SQLException {
        try (Connection c = newConnection()) {
            String userA    = insertUser(c, "alice-" + UUID.randomUUID());
            String sourceId = insertSource(c, "rss-null-" + UUID.randomUUID());
            String tagId    = insertTag(c, "n" + UUID.randomUUID().toString().substring(0, 8));

            assertNullScopeKindRejected(c,
                    "INSERT INTO source_subscription (scope_kind, scope_id, source_id) "
                            + "VALUES (NULL, ?::uuid, ?::uuid)",
                    userA, sourceId);
            assertNullScopeKindRejected(c,
                    "INSERT INTO scope_tag (scope_kind, scope_id, tag_id) "
                            + "VALUES (NULL, ?::uuid, ?::uuid)",
                    userA, tagId);
            assertNullScopeKindRejected(c,
                    "INSERT INTO scope_preferences (scope_kind, scope_id) "
                            + "VALUES (NULL, ?::uuid)",
                    userA, null);
        }
    }

    /**
     * Post-via-join chain. A post inserted against a source that is
     * subscribed by (dm, A) only must be reachable through the
     * subscription join filtered on (dm, A) and NOT reachable through
     * the equivalent join filtered on (dm, B). Guards against a
     * join-shape bug that would let a {@code source_subscription} row
     * in one scope match the posts of a different scope through the
     * {@code post.source_id} FK.
     */
    @Test
    void postViaJoinChainRespectsScopeFilter() throws SQLException {
        try (Connection c = newConnection()) {
            String userA = insertUser(c, "alice-" + UUID.randomUUID());
            String userB = insertUser(c, "bob-"   + UUID.randomUUID());

            String sourceId = insertSource(c, "rss-post-" + UUID.randomUUID());
            insertSourceSubscription(c, "dm", userA, sourceId);
            // userB is intentionally not subscribed.

            insertPost(c, sourceId, "uid-" + UUID.randomUUID(), "Test post");

            assertEquals(1, countPostsViaJoin(c, "dm", userA),
                    "A's DM should reach the post via its subscription");
            assertEquals(0, countPostsViaJoin(c, "dm", userB),
                    "B's DM has no subscription to the source — no posts reachable");
        }
    }

    // ===== seeding helpers =====

    private static String insertUser(Connection c, String contactId) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO users (adapter, contact_id, registration_state) "
                        + "VALUES (?, ?, 'vouched') RETURNING id")) {
            stmt.setString(1, "simplex");
            stmt.setString(2, contactId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static String insertGroup(Connection c, String upstreamGroupId) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO groups (adapter, upstream_group_id) "
                        + "VALUES (?, ?) RETURNING id")) {
            stmt.setString(1, "simplex");
            stmt.setString(2, upstreamGroupId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static void insertMembership(Connection c, String groupId, String userId) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO group_membership (group_id, user_id) "
                        + "VALUES (?::uuid, ?::uuid)")) {
            stmt.setString(1, groupId);
            stmt.setString(2, userId);
            stmt.executeUpdate();
        }
    }

    private static String insertSource(Connection c, String identifier) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO source (kind, identifier, display_name, category) "
                        + "VALUES (?, ?, ?, ?) RETURNING id")) {
            stmt.setString(1, "rss");
            stmt.setString(2, identifier);
            stmt.setString(3, "Test source");
            stmt.setString(4, "news");
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static String insertTag(Connection c, String name) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO tag (name, display) VALUES (?, ?) RETURNING id")) {
            stmt.setString(1, name);
            stmt.setString(2, name);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static void insertSourceSubscription(Connection c, String scopeKind,
                                                 String scopeId, String sourceId) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO source_subscription (scope_kind, scope_id, source_id) "
                        + "VALUES (?, ?::uuid, ?::uuid)")) {
            stmt.setString(1, scopeKind);
            stmt.setString(2, scopeId);
            stmt.setString(3, sourceId);
            stmt.executeUpdate();
        }
    }

    private static void insertScopeTag(Connection c, String scopeKind,
                                       String scopeId, String tagId) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO scope_tag (scope_kind, scope_id, tag_id) "
                        + "VALUES (?, ?::uuid, ?::uuid)")) {
            stmt.setString(1, scopeKind);
            stmt.setString(2, scopeId);
            stmt.setString(3, tagId);
            stmt.executeUpdate();
        }
    }

    private static void insertScopePreferences(Connection c, String scopeKind,
                                               String scopeId) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO scope_preferences (scope_kind, scope_id) "
                        + "VALUES (?, ?::uuid)")) {
            stmt.setString(1, scopeKind);
            stmt.setString(2, scopeId);
            stmt.executeUpdate();
        }
    }

    private static void insertPost(Connection c, String sourceId, String uid, String title) throws SQLException {
        // fetched_at is pinned inside a V30-provisioned partition (post_202607)
        // rather than relying on the column's DEFAULT now(): the infochat-core
        // test datasource never runs the collector's PartitionCreator, so a
        // wall-clock fetched_at past the V30 horizon (>= '2026-08-01') would hit
        // "no partition of relation post found for row" with no code change (M1-479).
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO post (uid, source_id, title, fetched_at, upstream_identifier) "
                        + "VALUES (?, ?::uuid, ?, '2026-07-15 00:00:00+00', ?)")) {
            stmt.setString(1, uid);
            stmt.setString(2, sourceId);
            stmt.setString(3, title);
            stmt.setString(4, uid);
            stmt.executeUpdate();
        }
    }

    // ===== assertion helpers =====

    /**
     * Asserts the scope-filtered SELECT against {@code table} under
     * the given (scope_kind, scope_id) returns exactly one row, and
     * that row's scope_kind / scope_id columns match the filter
     * predicate. Catches a future leak where a SELECT returns rows
     * from a different scope (the predicate would be satisfied at
     * the storage layer but the row's columns would disagree).
     */
    private static void assertExactlyOneRowMatchingScope(Connection c, String table,
                                                         String scopeKind, String scopeId) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "SELECT scope_kind, scope_id::text AS sid FROM " + table
                        + " WHERE scope_kind = ? AND scope_id = ?::uuid")) {
            stmt.setString(1, scopeKind);
            stmt.setString(2, scopeId);
            try (ResultSet rs = stmt.executeQuery()) {
                int rows = 0;
                while (rs.next()) {
                    rows++;
                    assertEquals(scopeKind, rs.getString("scope_kind"),
                            table + " row's scope_kind disagrees with filter");
                    assertEquals(scopeId, rs.getString("sid"),
                            table + " row's scope_id disagrees with filter");
                }
                assertEquals(1, rows, table
                        + " expected exactly 1 row under (" + scopeKind + ", " + scopeId + ")");
            }
        }
    }

    private static int countByScope(Connection c, String table,
                                    String scopeKind, String scopeId) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "SELECT COUNT(*) FROM " + table
                        + " WHERE scope_kind = ? AND scope_id = ?::uuid")) {
            stmt.setString(1, scopeKind);
            stmt.setString(2, scopeId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static int countPostsViaJoin(Connection c, String scopeKind, String scopeId) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "SELECT COUNT(*) FROM source_subscription ss "
                        + "JOIN post p ON p.source_id = ss.source_id "
                        + "WHERE ss.scope_kind = ? AND ss.scope_id = ?::uuid")) {
            stmt.setString(1, scopeKind);
            stmt.setString(2, scopeId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void assertNullScopeKindRejected(Connection c, String sql,
                                                    String scopeId, String fkId) throws SQLException {
        SQLException ex;
        try (PreparedStatement stmt = c.prepareStatement(sql)) {
            stmt.setString(1, scopeId);
            if (fkId != null) {
                stmt.setString(2, fkId);
            }
            ex = assertThrows(SQLException.class, stmt::executeUpdate,
                    "expected NULL scope_kind to be rejected for: " + sql);
        }
        assertEquals("23502", ex.getSQLState(),
                "expected not_null_violation (23502), got: " + ex.getSQLState()
                        + " for sql: " + sql);
    }
}
