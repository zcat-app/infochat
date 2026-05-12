package io.infochat.collector.db;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Boots the full Quarkus app against a DevServices-managed Postgres and asserts
 * that V2__roles.sql created the three role principals described in
 * docs/spec/security.md §DB roles. The check is a single JDBC query against
 * pg_roles so the test fails loudly if any role is missing — silent absence of
 * a principal would leave per-table GRANTs in M1-008 with no target.
 *
 * <p>Named with the {@code IT} suffix and bound to the failsafe plugin (see
 * {@code infochat-collector/pom.xml}) so this test runs in the verify phase,
 * matching the placement of FlywayMigrationIT.
 */
@QuarkusTest
class DbRoleMatrixIT {

    @Inject
    DataSource dataSource;

    @Test
    void v2CreatesThreeRolePrincipals() throws Exception {
        assertNotNull(dataSource, "DataSource must be injectable when quarkus-jdbc-postgresql is on the classpath");

        Set<String> expected = Set.of("infochat_collector", "infochat_provider", "infochat_admin");
        Set<String> found = new HashSet<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT rolname FROM pg_roles WHERE rolname IN (?, ?, ?)")) {
            ps.setString(1, "infochat_collector");
            ps.setString(2, "infochat_provider");
            ps.setString(3, "infochat_admin");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found.add(rs.getString("rolname"));
                }
            }
        }

        assertEquals(expected, found,
            "V2__roles.sql must create exactly the three role principals; found: " + found);
    }
}
