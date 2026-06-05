package app.zcat.infochat.collector.db;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the full Quarkus app against a DevServices-managed Postgres and asserts
 * that the three application role principals described in docs/spec/security.md
 * §DB roles exist and carry the NOLOGIN attribute.
 *
 * <p>The presence check pins V2__roles.sql's outcome: each of
 * {@code infochat_collector}, {@code infochat_provider}, {@code infochat_admin}
 * must appear in {@code pg_roles}. Silent absence of a principal would leave
 * per-table GRANTs in the M1-008 umbrella with no target.
 *
 * <p>The NOLOGIN check pins V4__nologin.sql's outcome: each principal must have
 * {@code rolcanlogin = false}. V2's {@code IF NOT EXISTS} guard creates a role
 * only when absent, so a pre-seeded role with {@code LOGIN} would survive V2
 * with the wrong attribute; V4's idempotent {@code ALTER ROLE … NOLOGIN}
 * forces the attribute uniformly. The test connects via the bootstrap
 * {@code infochat} superuser per M1-006's wiring; {@code pg_roles} is queried
 * (rather than {@code pg_authid}) so the assertion remains portable if the
 * named-datasource wiring ticket later switches the test connection to a
 * non-superuser role.
 *
 * <p>Named with the {@code IT} suffix and bound to the failsafe plugin (see
 * {@code infochat-collector/pom.xml}) so this test runs in the verify phase,
 * matching the placement of FlywayMigrationIT.
 */
@QuarkusTest
class DbRoleMatrixIT {

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Test
    void applicationRolesAreCreatedAndNologin() throws Exception {
        assertNotNull(dataSource, "DataSource must be injectable when quarkus-jdbc-postgresql is on the classpath");

        Set<String> expected = Set.of("infochat_collector", "infochat_provider", "infochat_admin");
        Set<String> found = new HashSet<>();
        Set<String> withLogin = new TreeSet<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT rolname, rolcanlogin FROM pg_roles WHERE rolname IN (?, ?, ?)")) {
            ps.setString(1, "infochat_collector");
            ps.setString(2, "infochat_provider");
            ps.setString(3, "infochat_admin");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String rolname = rs.getString("rolname");
                    found.add(rolname);
                    if (rs.getBoolean("rolcanlogin")) {
                        withLogin.add(rolname);
                    }
                }
            }
        }

        assertEquals(expected, found,
            "V2__roles.sql must create exactly the three role principals; found: " + found);
        assertTrue(withLogin.isEmpty(),
            "V4__nologin.sql must enforce NOLOGIN on every application role; roles still LOGIN: " + withLogin);
    }
}
