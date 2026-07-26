package app.zcat.infochat.core.schema;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards V65's {@code summary_anchor.render_form} column + backfill (M1-699,
 * D70).
 *
 * <p>Owns a private container instead of extending
 * {@link PostgresSchemaTestBase}: the base's static initializer migrates the
 * full chain before any test could seed pre-V65 rows, but the backfill proof
 * needs a stop at V64, seeded "prod-shaped" personal anchors, and only then
 * the migrate to head. The two-phase path here IS the prod-shaped-DB proof of
 * acceptance item 1's backfill rule; the fresh-DB proof is every other IT's
 * full-chain migrate (including the base's).
 */
class SummaryAnchorRenderFormBackfillIT {

    private static PostgreSQLContainer<?> postgres;

    /**
     * The pre-V65 seeded personal anchors' scope_ids, keyed by the seeded
     * command_name — each test re-reads render_form for these scope_ids.
     */
    private static final Map<String, UUID> seededByCommandName = new LinkedHashMap<>();

    @BeforeAll
    static void migrateStepwise() throws SQLException {
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse(pgVectorImageName())
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("infochat_render_form_backfill_test")
                .withUsername("infochat")
                .withPassword("infochat");
        postgres.start();

        // Phase 1: migrate to V64 (the pre-M1-699 head) and seed personal
        // anchors spanning every command_name variant a pre-upgrade /summary
        // could have written — the exact population the backfill must map.
        // scope_kind ('dm') is required from V37; user_id IS NOT NULL is
        // required by the personal/digest CHECK from V19.
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("64"))
                .load()
                .migrate();
        for (String commandName : new String[] {
                "summary", "/summary",
                "summary --full", "/summary --full",
                "summary ai --full"
        }) {
            seedPersonalAnchor(commandName);
        }

        // Phase 2: migrate the remaining chain (V65+) over the seeded data —
        // V65 backfills render_form from command_name.
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void fullFlagRowsBackfillToFlat() throws SQLException {
        // command_name LIKE '%--full%' ⇒ 'flat'. Covers the bare, leading-
        // slash, and tag-bearing variants of the --full invocation.
        for (String commandName : new String[] {
                "summary --full", "/summary --full", "summary ai --full"
        }) {
            assertEquals("flat", renderFormOf(commandName),
                    "a '--full' command_name must backfill to render_form='flat': " + commandName);
        }
    }

    @Test
    void nonFullRowsBackfillToBare() throws SQLException {
        // Anything without --full ⇒ 'bare', including the unnormalized
        // leading-slash '/summary' variant (the exact fragility the typed
        // column retires — RetryCommandHandler no longer string-matches).
        for (String commandName : new String[] {"summary", "/summary"}) {
            assertEquals("bare", renderFormOf(commandName),
                    "a non-'--full' command_name must backfill to render_form='bare': " + commandName);
        }
    }

    @Test
    void freshInsertDefaultsToBare() throws SQLException {
        // The DEFAULT 'bare' is a safety net defaulting to the safest replay
        // shape (categorized) for any path that inserts without naming the
        // column — mirrors V37's NOT-NULL-with-DEFAULT posture.
        try (Connection c = newConnection()) {
            UUID userId = UUID.randomUUID();
            UUID scopeId = UUID.randomUUID();
            try (PreparedStatement stmt = c.prepareStatement(
                    "INSERT INTO summary_anchor "
                            + "(user_id, scope_kind, scope_id, command_kind, command_name, arg_hash, post_uids) "
                            + "VALUES (?, 'dm', ?, 'personal', 'summary', 'hash', ARRAY['u1']) "
                            + "RETURNING render_form")) {
                stmt.setObject(1, userId);
                stmt.setObject(2, scopeId);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("bare", rs.getString(1),
                            "a post-V65 insert that omits render_form must default to 'bare'");
                }
            }
        }
    }

    @Test
    void outOfSetRenderFormIsRejected() throws SQLException {
        // The CHECK permits only (bare, short, full, flat) — short/full are
        // reserved for M1-700 but already in the closed list so M1-700 adds
        // no migration.
        try (Connection c = newConnection()) {
            UUID userId = UUID.randomUUID();
            UUID scopeId = UUID.randomUUID();
            try (PreparedStatement stmt = c.prepareStatement(
                    "INSERT INTO summary_anchor "
                            + "(user_id, scope_kind, scope_id, command_kind, command_name, render_form, "
                            + " arg_hash, post_uids) "
                            + "VALUES (?, 'dm', ?, 'personal', 'summary', 'compact', 'hash', ARRAY['u1'])")) {
                stmt.setObject(1, userId);
                stmt.setObject(2, scopeId);
                SQLException ex = assertThrows(SQLException.class, stmt::executeUpdate);
                assertEquals("23514", ex.getSQLState(), "must be a check_violation");
            }
        }
    }

    @Test
    void nullRenderFormIsRejected() throws SQLException {
        // NOT NULL (set after backfill) — a NULL insert must fail.
        try (Connection c = newConnection()) {
            UUID userId = UUID.randomUUID();
            UUID scopeId = UUID.randomUUID();
            try (PreparedStatement stmt = c.prepareStatement(
                    "INSERT INTO summary_anchor "
                            + "(user_id, scope_kind, scope_id, command_kind, command_name, render_form, "
                            + " arg_hash, post_uids) "
                            + "VALUES (?, 'dm', ?, 'personal', 'summary', ?, 'hash', ARRAY['u1'])")) {
                stmt.setObject(1, userId);
                stmt.setObject(2, scopeId);
                stmt.setNull(3, Types.VARCHAR);
                SQLException ex = assertThrows(SQLException.class, stmt::executeUpdate);
                assertEquals("23502", ex.getSQLState(), "must be a not_null_violation");
            }
        }
    }

    private static String renderFormOf(String commandName) throws SQLException {
        UUID scopeId = seededByCommandName.get(commandName);
        try (Connection c = newConnection();
             PreparedStatement stmt = c.prepareStatement(
                     "SELECT render_form FROM summary_anchor WHERE scope_id = ?")) {
            stmt.setObject(1, scopeId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next(), "seeded pre-V65 row must survive the migration: " + commandName);
                return rs.getString(1);
            }
        }
    }

    private static void seedPersonalAnchor(String commandName) throws SQLException {
        // Distinct (user_id, scope_id) per row: the V37 personal unique
        // index is (user_id, scope_kind, scope_id, command_kind), so two
        // personal anchors under the same key would conflict. scope_id is
        // the retrieval key recorded in seededByCommandName.
        UUID userId = UUID.randomUUID();
        UUID scopeId = UUID.randomUUID();
        try (Connection c = newConnection();
             PreparedStatement stmt = c.prepareStatement(
                     "INSERT INTO summary_anchor "
                             + "(user_id, scope_kind, scope_id, command_kind, command_name, arg_hash, post_uids) "
                             + "VALUES (?, 'dm', ?, 'personal', ?, 'hash', ARRAY['u1'])")) {
            stmt.setObject(1, userId);
            stmt.setObject(2, scopeId);
            stmt.setString(3, commandName);
            stmt.executeUpdate();
        }
        seededByCommandName.put(commandName, scopeId);
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /**
     * Same single-pin image read as {@link PostgresSchemaTestBase}: the
     * container must run the pgvector image (V1 declares the extension),
     * and reading the configured dev-services pin keeps the two containers
     * provably on one image.
     */
    private static String pgVectorImageName() {
        Properties props = new Properties();
        try (InputStream in =
                SummaryAnchorRenderFormBackfillIT.class.getResourceAsStream("/application.properties")) {
            props.load(in);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        return props.getProperty("quarkus.datasource.devservices.image-name");
    }
}
