package io.infochat.core.schema;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

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
 * <p>Image: {@code pgvector/pgvector:pg16}. V5 doesn't exercise
 * pgvector but V1 declares {@code CREATE EXTENSION vector} so a
 * non-pgvector image would fail Flyway at V1. Using the same image
 * the rest of the stack uses keeps DB shape consistent.
 *
 * <p>Flyway runs once on container start, applying V1..V5 against the
 * container's JDBC URL pointed at {@code classpath:db/migration}.
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
                DockerImageName.parse("pgvector/pgvector:pg16")
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
     * Reset the §2.1 tables to empty state. TRUNCATE skips row-level
     * triggers, so the last-admin / append-only guards are bypassed
     * for the fixture reset — exactly the intent. RESTART IDENTITY
     * rolls the {@code audit_log_id_seq} back to 1 so each test
     * starts with predictable id values. CASCADE handles the
     * {@code users.banned_by} self-reference and the foreign keys
     * from {@code group_membership}, {@code invite_code},
     * {@code audit_log.actor_user_id}.
     */
    @BeforeEach
    void truncateAll() throws SQLException {
        try (Connection c = newConnection(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE TABLE audit_log, invite_code, group_membership, groups, users "
                    + "RESTART IDENTITY CASCADE");
        }
    }
}
