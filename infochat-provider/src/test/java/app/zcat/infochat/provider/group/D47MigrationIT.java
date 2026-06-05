package app.zcat.infochat.provider.group;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema-shape verification for the V26 D47 migration
 * ({@code groups.approval_status} + {@code groups.activated_by}).
 * Asserts the runtime contract the application sees after V26 has been
 * applied by Flyway at boot — column existence, the NOT NULL DEFAULT on
 * approval_status, the CHECK constraint, and the nullability of
 * activated_by — via plain JDBC INSERT/SELECT against the test
 * datasource. No Flyway callback, no two-phase migration, no
 * programmatic Flyway reconfiguration.
 */
@QuarkusTest
@TestProfile(D47MigrationIT.Profile.class)
class D47MigrationIT {

    private static final String ADAPTER = "inmemory";
    private static final String UPSTREAM_PREFIX = "d47-migration-it-";

    @Inject @SeedDataSource DataSource dataSource;

    @BeforeEach
    void cleanTestGroups() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM groups WHERE upstream_group_id LIKE ?",
                    UPSTREAM_PREFIX + "%");
        }
    }

    @Test
    void insertWithoutApprovalStatusDefaultsToPending() throws Exception {
        String upstreamId = UPSTREAM_PREFIX + "default";
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "INSERT INTO groups (adapter, upstream_group_id) VALUES (?, ?)",
                    ADAPTER, upstreamId);
            assertEquals("pending", selectApprovalStatus(conn, upstreamId),
                    "approval_status column must exist and apply its NOT NULL "
                  + "DEFAULT 'pending' when the INSERT omits it");
        }
    }

    @Test
    void insertWithOutOfSetApprovalStatusRaisesCheckViolation() throws Exception {
        String upstreamId = UPSTREAM_PREFIX + "check";
        try (Connection conn = dataSource.getConnection()) {
            // 'maybe' is non-NULL, so the CHECK is the failure cause — a
            // NULL value would trip the NOT NULL constraint first and mask
            // the CHECK from this assertion.
            assertThrows(SQLException.class, () ->
                    exec(conn,
                            "INSERT INTO groups (adapter, upstream_group_id, approval_status) "
                          + "VALUES (?, ?, 'maybe')",
                            ADAPTER, upstreamId),
                    "approval_status CHECK must reject a value outside "
                  + "{pending, approved, rejected}");
        }
    }

    @Test
    void insertWithNullActivatedBySucceeds() throws Exception {
        String upstreamId = UPSTREAM_PREFIX + "nullactivatedby";
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                    "INSERT INTO groups (adapter, upstream_group_id, activated_by) "
                  + "VALUES (?, ?, NULL)",
                    ADAPTER, upstreamId);
            assertTrue(groupExists(conn, upstreamId),
                    "activated_by must be nullable — an INSERT leaving it NULL "
                  + "must succeed");
        }
    }

    // --- helpers ---

    private String selectApprovalStatus(Connection conn, String upstreamId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT approval_status FROM groups WHERE upstream_group_id = ?")) {
            ps.setString(1, upstreamId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("approval_status") : null;
            }
        }
    }

    private boolean groupExists(Connection conn, String upstreamId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM groups WHERE upstream_group_id = ?")) {
            ps.setString(1, upstreamId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void exec(Connection conn, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "infochat.adapters", "inmemory",
                    "infochat.adapters.inmemory.allow-low-trust", "true");
        }
    }
}
