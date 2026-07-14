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
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards V59's {@code source.source_origin} column + backfill and the
 * {@code source_exclusion} table (M1-621, D59).
 *
 * <p>Owns a private container instead of extending
 * {@link PostgresSchemaTestBase}: the base's static initializer migrates
 * the full chain before any test could seed pre-V59 rows, but the
 * backfill proof needs a stop at V58, a seeded "prod-shaped" row, and
 * only then the migrate to head. The two-phase path here IS the
 * prod-shaped-DB proof of acceptance item 1; the fresh-DB proof is every
 * other IT's full-chain migrate (including the base's).
 */
class SourceOriginBackfillIT {

    private static PostgreSQLContainer<?> postgres;

    /** The pre-V59 seeded row whose backfilled origin the tests assert. */
    private static String preV59SourceId;

    @BeforeAll
    static void migrateStepwise() throws SQLException {
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse(pgVectorImageName())
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("infochat_backfill_test")
                .withUsername("infochat")
                .withPassword("infochat");
        postgres.start();

        // Phase 1: migrate to V58 (the pre-M1-621 head) and seed a
        // prod-shaped source row that predates source_origin — standing in
        // for a pre-upgrade /add-source'd custom, which the migration must
        // NOT publicize (red-team 2026-07-14).
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("58"))
                .load()
                .migrate();
        try (Connection c = newConnection();
             PreparedStatement stmt = c.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category) "
                             + "VALUES ('rss', ?, 'pre-V59 seeded source', 'news') RETURNING id")) {
            stmt.setString(1, "https://example.com/backfill-" + UUID.randomUUID());
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                preV59SourceId = rs.getString(1);
            }
        }

        // Phase 2: migrate the remaining chain (V59+) over the seeded data.
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void preV59RowBackfillsToUserFailClosed() throws SQLException {
        // No pre-V59 row is presumed operator-seeded: the migration must
        // never publicize a pre-upgrade /add-source'd private custom. The
        // operator-listed rows are promoted to 'bootstrap' by
        // BootstrapLoader's same-boot ON CONFLICT upsert — proven in
        // BootstrapLoaderIT.loaderPromotesUserOriginRowBackToBootstrap.
        try (Connection c = newConnection();
             PreparedStatement stmt = c.prepareStatement(
                     "SELECT source_origin FROM source WHERE id = ?::uuid")) {
            stmt.setString(1, preV59SourceId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next(), "seeded pre-V59 row must survive the migration");
                assertEquals("user", rs.getString(1),
                        "existing rows must backfill to the fail-closed 'user'");
            }
        }
    }

    @Test
    void freshInsertDefaultsToUser() throws SQLException {
        try (Connection c = newConnection()) {
            String id = insertSource(c);
            try (PreparedStatement stmt = c.prepareStatement(
                    "SELECT source_origin FROM source WHERE id = ?::uuid")) {
                stmt.setString(1, id);
                try (ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    assertEquals("user", rs.getString(1),
                            "post-V59 default must be the privacy-safe 'user'");
                }
            }
        }
    }

    @Test
    void explicitNullOriginIsRejected() throws SQLException {
        try (Connection c = newConnection();
             PreparedStatement stmt = c.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, source_origin) "
                             + "VALUES ('rss', ?, 'null-origin source', 'news', ?)")) {
            stmt.setString(1, "https://example.com/null-origin-" + UUID.randomUUID());
            stmt.setNull(2, Types.VARCHAR);
            SQLException ex = assertThrows(SQLException.class, stmt::executeUpdate);
            assertEquals("23502", ex.getSQLState(), "must be a not_null_violation");
        }
    }

    @Test
    void outOfSetOriginIsRejected() throws SQLException {
        try (Connection c = newConnection();
             PreparedStatement stmt = c.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, source_origin) "
                             + "VALUES ('rss', ?, 'bad-origin source', 'news', 'community')")) {
            stmt.setString(1, "https://example.com/bad-origin-" + UUID.randomUUID());
            SQLException ex = assertThrows(SQLException.class, stmt::executeUpdate);
            assertEquals("23514", ex.getSQLState(), "must be a check_violation");
        }
    }

    @Test
    void exclusionTableEnforcesPkCheckAndFk() throws SQLException {
        try (Connection c = newConnection()) {
            String sourceId = insertSource(c);
            UUID scopeId = UUID.randomUUID();

            insertExclusion(c, "dm", scopeId, sourceId);

            // PK dedup: the same (scope_kind, scope_id, source_id) again.
            SQLException dup = assertThrows(SQLException.class,
                    () -> insertExclusion(c, "dm", scopeId, sourceId));
            assertEquals("23505", dup.getSQLState(), "must be a unique_violation");

            // Closed scope_kind set.
            SQLException badKind = assertThrows(SQLException.class,
                    () -> insertExclusion(c, "channel", scopeId, sourceId));
            assertEquals("23514", badKind.getSQLState(), "must be a check_violation");

            // FK to source.
            SQLException badFk = assertThrows(SQLException.class,
                    () -> insertExclusion(c, "dm", scopeId, UUID.randomUUID().toString()));
            assertEquals("23503", badFk.getSQLState(), "must be a foreign_key_violation");
        }
    }

    @Test
    void exclusionGrantsMatchRoleMatrix() throws SQLException {
        try (Connection c = newConnection();
             PreparedStatement stmt = c.prepareStatement(
                     "SELECT has_table_privilege('infochat_provider', 'source_exclusion', 'SELECT'), "
                             + "has_table_privilege('infochat_provider', 'source_exclusion', 'INSERT'), "
                             + "has_table_privilege('infochat_provider', 'source_exclusion', 'DELETE'), "
                             + "has_table_privilege('infochat_provider', 'source_exclusion', 'UPDATE'), "
                             + "has_table_privilege('infochat_collector', 'source_exclusion', 'SELECT'), "
                             + "has_table_privilege('infochat_collector', 'source_exclusion', 'INSERT')");
             ResultSet rs = stmt.executeQuery()) {
            rs.next();
            assertTrue(rs.getBoolean(1), "provider SELECT");
            assertTrue(rs.getBoolean(2), "provider INSERT");
            assertTrue(rs.getBoolean(3), "provider DELETE");
            assertFalse(rs.getBoolean(4), "provider must NOT hold UPDATE (insert/delete-only rows)");
            assertTrue(rs.getBoolean(5), "collector SELECT");
            assertFalse(rs.getBoolean(6), "collector must NOT hold INSERT");
        }
    }

    private static void insertExclusion(Connection c, String scopeKind, UUID scopeId,
                                        String sourceId) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO source_exclusion (scope_kind, scope_id, source_id) "
                        + "VALUES (?, ?, ?::uuid)")) {
            stmt.setString(1, scopeKind);
            stmt.setObject(2, scopeId);
            stmt.setString(3, sourceId);
            stmt.executeUpdate();
        }
    }

    private static String insertSource(Connection c) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO source (kind, identifier, display_name, category) "
                        + "VALUES ('rss', ?, 'origin test source', 'news') RETURNING id")) {
            stmt.setString(1, "https://example.com/origin-" + UUID.randomUUID());
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
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
                SourceOriginBackfillIT.class.getResourceAsStream("/application.properties")) {
            props.load(in);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        return props.getProperty("quarkus.datasource.devservices.image-name");
    }
}
