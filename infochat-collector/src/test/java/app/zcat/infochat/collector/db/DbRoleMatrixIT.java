package app.zcat.infochat.collector.db;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Boots the full Quarkus app against a DevServices-managed Postgres and asserts
 * that the three application role principals described in docs/spec/security.md
 * §DB roles exist and carry the expected login attributes: the two service
 * roles can LOGIN, the admin role cannot.
 *
 * <p>The presence check pins V2__roles.sql's outcome: each of
 * {@code infochat_collector}, {@code infochat_provider}, {@code infochat_admin}
 * must appear in {@code pg_roles}. Silent absence of a principal would leave
 * per-table GRANTs in the M1-008 umbrella with no target.
 *
 * <p>The login-attribute check pins the V4 → V31 progression:
 * {@code infochat_collector} and {@code infochat_provider} must carry
 * {@code rolcanlogin = true} (V31 makes them the connection principals of the
 * per-service datasource wiring), while {@code infochat_admin} must remain
 * {@code rolcanlogin = false} — it is the operator-psql / admin-procedure
 * principal, never a service login. An admin role that silently gained LOGIN
 * would widen the deployment's connectable surface. The test reads roles via
 * the owner-role seed seam ({@code @SeedDataSource}); {@code pg_roles} is
 * queried (rather than {@code pg_authid}) so the assertion does not require
 * superuser privilege.
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
    void applicationRolesHaveExpectedLoginAttributes() throws Exception {
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
        assertEquals(Set.of("infochat_collector", "infochat_provider"), withLogin,
            "V31 must grant LOGIN to exactly the two service roles and leave "
                + "infochat_admin NOLOGIN; roles with LOGIN: " + withLogin);
    }

    /**
     * Pins the effective DML privilege matrix on {@code price_snapshot} for
     * both service roles, the grants end-state of V17 (SELECT+INSERT to the
     * Collector, SELECT to the Provider, DELETE revoked everywhere — retention
     * is operator-driven partition drop, schema Invariant 6) plus V39's
     * UPDATE revoke from the Collector (snapshots are immutable history).
     * A privilege appearing in neither expected set must test false, so a
     * future migration that widens either role's write surface fails here.
     */
    @Test
    void priceSnapshotPrivilegeMatrixMatchesImmutabilityGrants() throws Exception {
        Map<String, Set<String>> expectedGranted = Map.of(
            "infochat_collector", Set.of("SELECT", "INSERT"),
            "infochat_provider", Set.of("SELECT"));

        Map<String, Set<String>> actualGranted = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT has_table_privilege(?, 'price_snapshot', ?)")) {
            for (String role : expectedGranted.keySet()) {
                Set<String> rolePrivileges = new TreeSet<>();
                for (String privilege : new String[] {"SELECT", "INSERT", "UPDATE", "DELETE"}) {
                    ps.setString(1, role);
                    ps.setString(2, privilege);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        if (rs.getBoolean(1)) {
                            rolePrivileges.add(privilege);
                        }
                    }
                }
                actualGranted.put(role, rolePrivileges);
            }
        }

        assertEquals(expectedGranted, actualGranted,
            "price_snapshot must stay INSERT-only for the Collector (V17 grant, "
                + "V39 UPDATE revoke) and SELECT-only for the Provider, with DELETE "
                + "denied to both (retention is operator partition-drop); "
                + "effective grants: " + actualGranted);
    }
}
