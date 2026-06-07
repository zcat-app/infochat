package app.zcat.infochat.collector.db;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Boots the full Quarkus app against a DevServices-managed Postgres and
 * asserts the V39 grant revocations hold at the role level
 * (docs/spec/security.md §DB roles, docs/spec/schema.md §Operational):
 *
 * <ul>
 *   <li>Only the Provider role may EXECUTE the SECURITY DEFINER
 *       {@code approve_quarantine} / {@code reject_quarantine} functions —
 *       the Collector must get permission-denied (V39 revokes the
 *       Postgres-default PUBLIC EXECUTE that V21/V25/V32/V41 left in
 *       place).</li>
 *   <li>{@code price_snapshot} is INSERT-only for the Collector — UPDATE
 *       must be denied (V39 revokes V17's UPDATE grant) while INSERT keeps
 *       working.</li>
 * </ul>
 *
 * <p>Role switching uses {@code SET ROLE} on the owner-role seed seam
 * ({@code @SeedDataSource}): the test-profile owner is the DevServices
 * container superuser, so it may assume either service role. Every
 * SET ROLE is paired with RESET ROLE in a finally block — the seam hands
 * out pooled connections, and a leaked role would poison later tests.
 *
 * <p>The Provider-leg assertions distinguish privilege failure from
 * domain failure by SQLState: with random UUID arguments both functions
 * raise {@code P0001} ("actor is not a bot admin") from inside their
 * bodies, which proves the EXECUTE gate passed; a privilege denial never
 * enters the body and raises {@code 42501} instead. This keeps the test
 * fixture-free — no quarantine/users seeding is needed to prove the ACL.
 *
 * <p>Named with the {@code IT} suffix and bound to the failsafe plugin
 * (see {@code infochat-collector/pom.xml}) so this test runs in the
 * verify phase, matching the placement of DbRoleMatrixIT.
 */
@QuarkusTest
class DbGrantsRevocationIT {

    /** SQLState insufficient_privilege — raised by the ACL check itself. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    /** SQLState raise_exception — a plpgsql RAISE EXCEPTION inside the body. */
    private static final String PLPGSQL_RAISE = "P0001";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Test
    void collectorIsDeniedExecuteOnQuarantineFunctions() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE infochat_collector");
                for (String function : new String[] {"approve_quarantine", "reject_quarantine"}) {
                    SQLException denied = assertThrows(SQLException.class,
                        () -> callQuarantineFunction(conn, function),
                        "V39 must leave the Collector without EXECUTE on " + function);
                    assertEquals(INSUFFICIENT_PRIVILEGE, denied.getSQLState(),
                        function + " as infochat_collector must fail on the EXECUTE ACL "
                            + "(42501), not inside the body; was: " + denied.getSQLState()
                            + " — " + denied.getMessage());
                }
            } finally {
                resetRole(conn);
            }
        }
    }

    @Test
    void providerStillExecutesQuarantineFunctions() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE infochat_provider");
                for (String function : new String[] {"approve_quarantine", "reject_quarantine"}) {
                    SQLException domainError = assertThrows(SQLException.class,
                        () -> callQuarantineFunction(conn, function),
                        function + " with random UUIDs must reach the body's actor check");
                    assertEquals(PLPGSQL_RAISE, domainError.getSQLState(),
                        function + " as infochat_provider must pass the EXECUTE ACL and "
                            + "raise from the body (P0001); was: " + domainError.getSQLState()
                            + " — " + domainError.getMessage());
                }
            } finally {
                resetRole(conn);
            }
        }
    }

    @Test
    void collectorUpdateOnPriceSnapshotIsDenied() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE infochat_collector");
                // The ACL check fires before row matching, so a WHERE clause
                // that matches nothing still proves the revocation.
                SQLException denied = assertThrows(SQLException.class,
                    () -> {
                        try (Statement update = conn.createStatement()) {
                            update.executeUpdate("UPDATE price_snapshot SET price = price"
                                + " WHERE asset = 'm1189-no-such-asset'");
                        }
                    },
                    "V39 must revoke V17's UPDATE grant on price_snapshot from the Collector");
                assertEquals(INSUFFICIENT_PRIVILEGE, denied.getSQLState(),
                    "UPDATE price_snapshot as infochat_collector must fail with "
                        + "insufficient_privilege (42501); was: " + denied.getSQLState()
                        + " — " + denied.getMessage());
            } finally {
                resetRole(conn);
            }
        }
    }

    @Test
    void collectorInsertOnPriceSnapshotStillSucceeds() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE infochat_collector");
                // captured_at is pinned inside the V30-created p202606
                // partition rather than NOW(): the migrations guarantee that
                // partition on every fresh test DB, while NOW() drifts out of
                // partition coverage once the calendar passes the last
                // migration-created month (the rotator is operator-driven and
                // never runs in tests).
                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO price_snapshot"
                            + " (asset, sub_verb, vs_currency, price, captured_at)"
                            + " VALUES (?, ?, ?, ?, TIMESTAMPTZ '2026-06-15 00:00:00+00')")) {
                    insert.setString(1, "m1189-grant-check");
                    insert.setString(2, "price");
                    insert.setString(3, "usd");
                    insert.setBigDecimal(4, java.math.BigDecimal.ONE);
                    assertEquals(1, insert.executeUpdate(),
                        "INSERT on price_snapshot as infochat_collector must keep working "
                            + "after V39's UPDATE revoke");
                }
            } finally {
                resetRole(conn);
                // Remove the probe row as the owner role (neither service role
                // holds DELETE) so the shared DevServices DB stays clean for
                // other tests.
                try (Statement cleanup = conn.createStatement()) {
                    cleanup.executeUpdate(
                        "DELETE FROM price_snapshot WHERE asset = 'm1189-grant-check'");
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
