package app.zcat.infochat.core.schema;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Shared Testcontainers + Flyway fixture for the §2.1 identity/audit
 * schema-level tests.
 *
 * <p>Singleton container: ONE Postgres started per JVM (not per test
 * class) so test wall-clock cost doesn't multiply by the number of
 * concrete subclasses. The container is created in a static
 * initializer and intentionally never stopped — Testcontainers' Ryuk
 * sidecar reaps the container at JVM exit. State is reset between
 * tests via {@link #truncateAll()} called from {@code @BeforeEach};
 * re-spinning the container per test would be orders of magnitude
 * slower than a TRUNCATE.
 *
 * <p>Image: read from this module's test {@code application.properties}
 * key {@code quarkus.datasource.devservices.image-name} — the single pin the
 * dev-services container also uses (see {@link #pgVectorImageName()}). V1
 * declares {@code CREATE EXTENSION vector} and V11 adds {@code vector(N)}
 * columns, so a non-pgvector image would fail Flyway; reading the configured
 * image rather than a duplicated literal keeps this container and the
 * dev-services container provably on the same image.
 *
 * <p>Flyway runs once on container start, applying every migration
 * under {@code classpath:db/migration} (V1 through the current head —
 * the {@code migrate()} call sets no {@code target}) against the
 * container's JDBC URL.
 * Tests open Connections as the bootstrap superuser (the container's
 * default credentials) so they can issue both privileged setup
 * statements and the application-role grants are validated only
 * through migration outcomes — exercising the role grants themselves
 * is a separate Tier-1 task.
 */
public abstract class PostgresSchemaTestBase {

    protected static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>(
                DockerImageName.parse(pgVectorImageName())
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("infochat_test")
                .withUsername("infochat")
                .withPassword("infochat");
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /**
     * The pgvector image, read from the single pin in this module's test
     * {@code application.properties} ({@code quarkus.datasource.devservices
     * .image-name}) so this raw-Testcontainers base and the {@code @QuarkusTest}
     * dev-services container cannot drift onto different images. The base runs
     * outside the Quarkus runtime (a static initializer, before any CDI/config
     * bootstrap), so it loads the property file off the classpath directly
     * rather than through {@code ConfigProvider}.
     */
    private static String pgVectorImageName() {
        Properties props = new Properties();
        try (InputStream in =
                PostgresSchemaTestBase.class.getResourceAsStream("/application.properties")) {
            props.load(in);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        return props.getProperty("quarkus.datasource.devservices.image-name");
    }

    /**
     * Open a fresh JDBC connection as the container's bootstrap
     * superuser. Each connection is independent (autoCommit defaults
     * to true); callers that need transactional control flip
     * autoCommit explicitly.
     */
    protected static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /**
     * Reset all migration-created tables to empty state. The table
     * list is derived at runtime from {@code pg_tables} so a future
     * {@code CREATE TABLE} migration cannot silently reintroduce
     * cross-test pollution by being absent from a hand-maintained
     * list. TRUNCATE skips row-level triggers, so the last-admin /
     * append-only guards are bypassed for the fixture reset —
     * exactly the intent. RESTART IDENTITY rolls sequences (e.g.
     * {@code audit_log_id_seq}) back to 1 so each test starts with
     * predictable id values. CASCADE handles self-references and
     * cross-table foreign keys.
     */
    @BeforeEach
    void truncateAll() throws SQLException {
        try (Connection c = newConnection(); Statement s = c.createStatement()) {
            // Excluded from truncation:
            //  - flyway_schema_history: Flyway's own migration ledger;
            //    the schema is migrated once per JVM and must stay
            //    recorded as applied.
            //  - embedding_metadata: V11 seeds the reference row
            //    ('nomic-embed-text', 768) that the embedding-dimension
            //    startup check reads; truncating would erase a
            //    migration-established invariant, not test data.
            //  - provider_state: V9/V21 seed the per-channel cursor rows
            //    ('new_post', 'quarantine_review') whose existence the
            //    provider relies on; same migration-seeded-reference
            //    rationale.
            List<String> tables = new ArrayList<>();
            try (ResultSet tableNames = s.executeQuery(
                    "SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
                            + "AND tablename NOT IN "
                            + "('flyway_schema_history', 'embedding_metadata', 'provider_state')")) {
                while (tableNames.next()) {
                    tables.add(tableNames.getString(1));
                }
            }
            s.execute("TRUNCATE TABLE " + String.join(", ", tables)
                    + " RESTART IDENTITY CASCADE");
        }
    }
}
