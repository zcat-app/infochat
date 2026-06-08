package app.zcat.infochat.collector.db;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Boots the full Quarkus app against a DevServices-managed Postgres and
 * asserts the V43 infochat_admin operator surface holds at the role level
 * (docs/spec/security.md §DB roles):
 *
 * <ul>
 *   <li>The admin role CAN perform each V43-granted action: SELECT on the
 *       redacted {@code audit_log_view}, EXECUTE on the
 *       {@code approve_quarantine} / {@code reject_quarantine} functions,
 *       SELECT on {@code quarantine} (raw {@code original_html}
 *       inspection), DELETE on {@code heartbeat}, TRUNCATE on
 *       {@code invite_code_attempt}, and DELETE on {@code source}
 *       (the Invariant 4 escape hatch).</li>
 *   <li>The service roles CANNOT perform the admin-only actions:
 *       heartbeat DELETE (V3), invite_code_attempt TRUNCATE (V12), and
 *       source DELETE (V6 revoke, Invariant 4) must all raise
 *       insufficient_privilege for both {@code infochat_collector} and
 *       {@code infochat_provider}.</li>
 * </ul>
 *
 * <p>Role switching uses {@code SET ROLE} on the owner-role seed seam
 * ({@code @SeedDataSource}), paired with RESET ROLE in a finally block —
 * the seam hands out pooled connections, and a leaked role would poison
 * later tests (same discipline as DbGrantsRevocationIT).
 *
 * <p>The quarantine-function leg distinguishes privilege failure from
 * domain failure by SQLState exactly as DbGrantsRevocationIT does: with
 * random UUID arguments both functions raise {@code P0001} from inside
 * their bodies, which proves the EXECUTE gate passed, while a privilege
 * denial never enters the body and raises {@code 42501}.
 *
 * <p>The admin TRUNCATE leg runs inside an explicitly rolled-back
 * transaction: TRUNCATE is transactional in Postgres, and the shared
 * DevServices DB must keep its {@code invite_code_attempt} rows for
 * other tests. The DELETE legs use WHERE clauses that match no rows —
 * the ACL check fires before row matching, so a zero-row DELETE still
 * proves the grant.
 *
 * <p>Named with the {@code IT} suffix and bound to the failsafe plugin
 * (see {@code infochat-collector/pom.xml}) so this test runs in the
 * verify phase, matching the placement of DbRoleMatrixIT.
 */
@QuarkusTest
class AdminRoleGrantsIT {

    /** SQLState insufficient_privilege — raised by the ACL check itself. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    /** SQLState raise_exception — a plpgsql RAISE EXCEPTION inside the body. */
    private static final String PLPGSQL_RAISE = "P0001";

    private static final String[] SERVICE_ROLES =
        {"infochat_collector", "infochat_provider"};

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Test
    void adminSelectsRedactedAuditLogView() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE infochat_admin");
                try (ResultSet rs = st.executeQuery(
                        "SELECT count(*) FROM audit_log_view")) {
                    assertTrue(rs.next(),
                        "V43 must let infochat_admin SELECT the redacted audit_log_view");
                }
            } finally {
                resetRole(conn);
            }
        }
    }

    @Test
    void adminExecutesQuarantineReviewFunctions() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE infochat_admin");
                for (String function : new String[] {"approve_quarantine", "reject_quarantine"}) {
                    SQLException domainError = assertThrows(SQLException.class,
                        () -> callQuarantineFunction(conn, function),
                        function + " with random UUIDs must reach the body's actor check");
                    assertEquals(PLPGSQL_RAISE, domainError.getSQLState(),
                        function + " as infochat_admin must pass the V43 EXECUTE ACL and "
                            + "raise from the body (P0001); was: " + domainError.getSQLState()
                            + " — " + domainError.getMessage());
                }
            } finally {
                resetRole(conn);
            }
        }
    }

    @Test
    void adminReadsRawQuarantineOriginal() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE infochat_admin");
                // Naming original_html in the SELECT list proves the grant
                // covers the raw column the quarantine_review_view omits.
                try (ResultSet rs = st.executeQuery(
                        "SELECT count(original_html) FROM quarantine")) {
                    assertTrue(rs.next(),
                        "V43 must let infochat_admin read quarantine.original_html");
                }
            } finally {
                resetRole(conn);
            }
        }
    }

    @Test
    void adminDeletesHeartbeatRows() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE infochat_admin");
                assertEquals(0, st.executeUpdate(
                        "DELETE FROM heartbeat WHERE service = 'm1207-no-such-service'"),
                    "V43 must let infochat_admin DELETE heartbeat rows "
                        + "(zero-row WHERE still exercises the ACL)");
            } finally {
                resetRole(conn);
            }
        }
    }

    @Test
    void adminTruncatesInviteCodeAttempt() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE infochat_admin");
                st.execute("TRUNCATE invite_code_attempt");
            } finally {
                // Roll back so the shared DevServices DB keeps its rows for
                // other tests; the rollback also undoes SET ROLE, but RESET
                // ROLE runs anyway to match the suite-wide discipline.
                conn.rollback();
                conn.setAutoCommit(true);
                resetRole(conn);
            }
        }
    }

    @Test
    void adminHardDeletesSourceRows() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE infochat_admin");
                assertEquals(0, st.executeUpdate(
                        "DELETE FROM source WHERE identifier = 'm1207-no-such-source'"),
                    "V43 must let infochat_admin hard-DELETE source rows "
                        + "(Invariant 4 escape hatch; zero-row WHERE still exercises the ACL)");
            } finally {
                resetRole(conn);
            }
        }
    }

    @Test
    void serviceRolesCannotDeleteHeartbeat() throws Exception {
        assertServiceRolesDenied(
            "DELETE FROM heartbeat WHERE service = 'm1207-no-such-service'",
            "heartbeat DELETE is the V3 admin-only operator path");
    }

    @Test
    void serviceRolesCannotTruncateInviteCodeAttempt() throws Exception {
        assertServiceRolesDenied(
            "TRUNCATE invite_code_attempt",
            "invite_code_attempt TRUNCATE is the V12 admin-only purge path");
    }

    @Test
    void serviceRolesCannotHardDeleteSource() throws Exception {
        assertServiceRolesDenied(
            "DELETE FROM source WHERE identifier = 'm1207-no-such-source'",
            "source DELETE is revoked from both service roles (Invariant 4)");
    }

    private void assertServiceRolesDenied(String sql, String why) throws Exception {
        for (String role : SERVICE_ROLES) {
            try (Connection conn = dataSource.getConnection()) {
                try (Statement st = conn.createStatement()) {
                    st.execute("SET ROLE " + role);
                    SQLException denied = assertThrows(SQLException.class,
                        () -> {
                            try (Statement attempt = conn.createStatement()) {
                                attempt.executeUpdate(sql);
                            }
                        },
                        why + "; must be denied as " + role);
                    assertEquals(INSUFFICIENT_PRIVILEGE, denied.getSQLState(),
                        "[" + sql + "] as " + role + " must fail with "
                            + "insufficient_privilege (42501); was: " + denied.getSQLState()
                            + " — " + denied.getMessage());
                } finally {
                    resetRole(conn);
                }
            }
        }
    }

    private static void callQuarantineFunction(Connection conn, String function)
            throws SQLException {
        try (PreparedStatement call = conn.prepareStatement(
                "SELECT " + function + "(?, ?)")) {
            call.setObject(1, UUID.randomUUID());
            call.setObject(2, UUID.randomUUID());
            call.execute();
        }
    }

    private static void resetRole(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("RESET ROLE");
        }
    }
}
