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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the V62 narrowing of {@code infochat_provider}'s grants on the four
 * identity/authz tables (M1-672; docs/spec/security.md §DB roles).
 *
 * <p>Two halves, matching the two things the migration has to get right:
 *
 * <ul>
 *   <li><b>The privilege columns are unreachable.</b> As
 *       {@code infochat_provider}, a direct write to {@code users.is_admin},
 *       {@code users.is_banned}, {@code users.registration_state},
 *       {@code groups.approval_status} or
 *       {@code group_membership.is_group_admin}, and any raw INSERT into
 *       {@code users} or {@code invite_code}, must fail with
 *       {@code 42501}.</li>
 *   <li><b>Everything legitimate still works.</b> The six column-scoped
 *       UPDATEs and the two column-scoped INSERTs the Provider actually
 *       performs must keep succeeding, and the SECURITY DEFINER routines
 *       that now carry the privilege writes must enforce their documented
 *       actor model.</li>
 * </ul>
 *
 * <p>Shape copied from {@link DbGrantsRevocationIT}: {@code SET ROLE} on the
 * owner-role seed seam, always paired with {@code RESET ROLE} in a finally
 * block — the seam hands out pooled connections and a leaked role poisons
 * later tests. Most cases are fixture-free because the ACL check fires
 * before row matching, so a {@code WHERE} that matches nothing still proves
 * the grant.
 *
 * <p>Named with the {@code IT} suffix and bound to the failsafe plugin so it
 * runs in the verify phase alongside {@link DbRoleMatrixIT}.
 */
@QuarkusTest
class ProviderIdentityGrantsIT {

    /** SQLState insufficient_privilege — raised by the ACL check itself. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    /** SQLState raise_exception — a plpgsql RAISE EXCEPTION inside the body. */
    private static final String PLPGSQL_RAISE = "P0001";

    /** SQLState of the last-admin protection triggers (V35/V40). */
    private static final String LAST_ADMIN_PROTECTION = "IC001";

    private static final String PREFIX = "m1-672-grants-";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    // ------------------------------------------------------------------
    // Half 1 — the privilege columns are unreachable
    // ------------------------------------------------------------------

    @Test
    void providerCannotWritePrivilegeColumnsDirectly() throws Exception {
        // Each statement targets a row that cannot exist, so what these
        // assert is purely the column ACL. Every one of them is a full
        // deployment compromise if it succeeds: mint an admin, unban,
        // force a registration state, walk a group past the D47 gate,
        // grant group-admin, or forge an invite.
        String[] forbidden = {
            "UPDATE users SET is_admin = TRUE WHERE id = '" + UUID.randomUUID() + "'",
            "UPDATE users SET is_banned = TRUE, banned_at = now(), banned_by = NULL,"
                + " ban_reason = 'x' WHERE id = '" + UUID.randomUUID() + "'",
            "UPDATE users SET registration_state = 'vouched' WHERE id = '"
                + UUID.randomUUID() + "'",
            "UPDATE groups SET approval_status = 'approved' WHERE id = '"
                + UUID.randomUUID() + "'",
            "UPDATE group_membership SET is_group_admin = TRUE WHERE group_id = '"
                + UUID.randomUUID() + "' AND user_id = '" + UUID.randomUUID() + "'",
            "INSERT INTO users (adapter, contact_id) VALUES ('inmemory', '"
                + PREFIX + "forged')",
            "INSERT INTO invite_code (code, invite_type, adapter, status)"
                + " VALUES ('" + UUID.randomUUID() + "', 'OPEN_ADAPTER', 'inmemory', 'PENDING')",
        };

        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE infochat_provider");
                for (String sql : forbidden) {
                    SQLException denied = assertThrows(SQLException.class,
                        () -> execute(conn, sql),
                        "V62 must leave infochat_provider unable to run: " + sql);
                    assertEquals(INSUFFICIENT_PRIVILEGE, denied.getSQLState(),
                        "must fail on the column/table ACL (42501), not later: " + sql
                            + " — was " + denied.getSQLState() + " " + denied.getMessage());
                }
            } finally {
                resetRole(conn);
            }
        }
    }

    @Test
    void inviteCodeRowLocksDieWithTheUpdateGrant() throws Exception {
        // invite_code keeps zero granted UPDATE columns, and a row lock is
        // a write intent, so FOR UPDATE on it is denied too — the reason
        // both invite row-locks moved inside routines. users keeps two
        // granted UPDATE columns, which is what keeps every handler's
        // in-transaction `SELECT ... FOR UPDATE` actor gate working.
        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE infochat_provider");

                execute(conn, "SELECT id FROM users WHERE id = '"
                    + UUID.randomUUID() + "' FOR UPDATE");

                SQLException denied = assertThrows(SQLException.class,
                    () -> execute(conn, "SELECT id FROM invite_code WHERE code = '"
                        + UUID.randomUUID() + "' FOR UPDATE"),
                    "FOR UPDATE on invite_code must be denied once every UPDATE"
                        + " column grant is gone");
                assertEquals(INSUFFICIENT_PRIVILEGE, denied.getSQLState(),
                    "was " + denied.getSQLState() + " " + denied.getMessage());
            } finally {
                resetRole(conn);
            }
        }
    }

    @Test
    void collectorIsDeniedExecuteOnIdentityRoutines() throws Exception {
        // Postgres grants EXECUTE to PUBLIC by default on new routines.
        // Without V62's REVOKE ALL ... FROM PUBLIC the Collector — which
        // holds SELECT-only on all four tables — would silently gain the
        // ability to mint bot admins through these routines, widening the
        // exact surface V62 narrows. Mirrors
        // DbGrantsRevocationIT.collectorIsDeniedExecuteOnQuarantineFunctions.
        String[] calls = {
            "SELECT grant_bot_admin('" + UUID.randomUUID() + "')",
            "SELECT revoke_bot_admin('" + UUID.randomUUID() + "')",
            "SELECT unban_user('" + UUID.randomUUID() + "')",
            "SELECT bootstrap_ensure_admin('inmemory', '" + PREFIX + "collector-probe')",
            "SELECT set_group_approval_status('" + UUID.randomUUID() + "', 'approved')",
        };

        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE infochat_collector");
                for (String sql : calls) {
                    SQLException denied = assertThrows(SQLException.class,
                        () -> execute(conn, sql),
                        "the Collector must hold no EXECUTE on: " + sql);
                    assertEquals(INSUFFICIENT_PRIVILEGE, denied.getSQLState(),
                        sql + " must fail on the EXECUTE ACL (42501); was "
                            + denied.getSQLState() + " " + denied.getMessage());
                }
            } finally {
                resetRole(conn);
            }
        }
    }

    // ------------------------------------------------------------------
    // Half 2 — every legitimate write still works
    // ------------------------------------------------------------------

    @Test
    void providerRetainsTheNonPrivilegeColumnUpdates() throws Exception {
        // The complete set of column-scoped UPDATEs the Provider performs.
        // Fixture-free: a non-matching WHERE still proves the grant,
        // because the ACL check precedes row matching. users.save_count
        // has no Java writer — V15's trg_saved_post_count is SECURITY
        // INVOKER, so /save and /unsave drive it under the caller's role.
        String[] permitted = {
            "UPDATE users SET probation_until = NULL WHERE id = '"
                + UUID.randomUUID() + "'",
            "UPDATE users SET save_count = save_count + 1 WHERE id = '"
                + UUID.randomUUID() + "'",
            "UPDATE groups SET timezone = 'UTC' WHERE id = '" + UUID.randomUUID() + "'",
            "UPDATE groups SET digest_enabled = TRUE WHERE id = '"
                + UUID.randomUUID() + "'",
            "UPDATE groups SET removed_at = now() WHERE id = '" + UUID.randomUUID() + "'",
            "UPDATE group_membership SET removed_at = now() WHERE group_id = '"
                + UUID.randomUUID() + "' AND user_id = '" + UUID.randomUUID() + "'",
        };

        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE infochat_provider");
                for (String sql : permitted) {
                    try (Statement update = conn.createStatement()) {
                        assertEquals(0, update.executeUpdate(sql),
                            "expected a no-op, not a mutation: " + sql);
                    }
                }
            } finally {
                resetRole(conn);
            }
        }
    }

    @Test
    void providerRetainsTheColumnScopedInserts() throws Exception {
        // These two need FK-valid data, so the prerequisite users row is
        // seeded as the owner before SET ROLE and everything is removed as
        // the owner afterwards — neither service role holds DELETE on
        // these tables (V5:384-386, deliberate).
        UUID userId = UUID.randomUUID();
        String upstreamGroupId = PREFIX + "insert-" + UUID.randomUUID();

        try (Connection conn = dataSource.getConnection()) {
            seedUser(conn, userId, PREFIX + "member-" + userId, false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE infochat_provider");

                UUID groupId;
                // The column set is the one the Provider's creation
                // statements actually write: V72 (M1-707) widened the
                // INSERT grant with timezone so the operator-configured
                // default zone lands at creation. A non-UTC literal plus
                // the read-back below pins that the value — not the DDL
                // default — is what was persisted.
                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO groups (adapter, upstream_group_id, activated_by, timezone)"
                            + " VALUES ('inmemory', ?, ?, 'Europe/Berlin') RETURNING id")) {
                    insert.setString(1, upstreamGroupId);
                    insert.setObject(2, userId);
                    try (ResultSet rs = insert.executeQuery()) {
                        assertTrue(rs.next(), "the groups INSERT must return the new id");
                        groupId = rs.getObject(1, UUID.class);
                    }
                }

                try (PreparedStatement readBack = conn.prepareStatement(
                        "SELECT timezone FROM groups WHERE id = ?")) {
                    readBack.setObject(1, groupId);
                    try (ResultSet rs = readBack.executeQuery()) {
                        assertTrue(rs.next());
                        assertEquals("Europe/Berlin", rs.getString(1),
                            "the widened INSERT grant must carry the timezone column (V72)");
                    }
                }

                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO group_membership (group_id, user_id) VALUES (?, ?)")) {
                    insert.setObject(1, groupId);
                    insert.setObject(2, userId);
                    assertEquals(1, insert.executeUpdate(),
                        "the group_membership INSERT must keep working");
                }
            } finally {
                resetRole(conn);
                cleanUp(conn, upstreamGroupId, userId);
            }
        }
    }

    @Test
    void removedAtUpdateStillClearsTheGroupAdminFlag() throws Exception {
        // The one Postgres behaviour this design assumes with no in-repo
        // precedent: V5's BEFORE UPDATE trigger
        // trg_group_membership_clear_admin assigns NEW.is_group_admin, a
        // column the Provider can no longer name in a SET list. Column
        // UPDATE privileges are checked against the statement's SET list,
        // not against a trigger's assignment to NEW — so the user-left
        // path must still free the one_admin_per_group slot. If this ever
        // fails, is_group_admin would have to return to the grant list,
        // which would defeat the migration's central control.
        UUID userId = UUID.randomUUID();
        String upstreamGroupId = PREFIX + "trigger-" + UUID.randomUUID();

        try (Connection conn = dataSource.getConnection()) {
            seedUser(conn, userId, PREFIX + "admin-member-" + userId, false);
            UUID groupId = seedGroupWithAdminMember(conn, upstreamGroupId, userId);
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE infochat_provider");
                try (PreparedStatement update = conn.prepareStatement(
                        "UPDATE group_membership SET removed_at = now()"
                            + " WHERE group_id = ? AND user_id = ? AND removed_at IS NULL")) {
                    update.setObject(1, groupId);
                    update.setObject(2, userId);
                    assertEquals(1, update.executeUpdate());
                }
            } finally {
                resetRole(conn);
            }

            try (PreparedStatement read = conn.prepareStatement(
                    "SELECT is_group_admin FROM group_membership"
                        + " WHERE group_id = ? AND user_id = ?")) {
                read.setObject(1, groupId);
                read.setObject(2, userId);
                try (ResultSet rs = read.executeQuery()) {
                    assertTrue(rs.next());
                    assertFalse(rs.getBoolean(1),
                        "the V5 BEFORE UPDATE trigger must still clear is_group_admin"
                            + " even though the caller cannot name that column");
                }
            }
            cleanUp(conn, upstreamGroupId, userId);
        }
    }

    // ------------------------------------------------------------------
    // Half 2b — the routines' actor model
    // ------------------------------------------------------------------

    @Test
    void adminGatedRoutinesRefuseAnUnsetActor() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE infochat_provider");
                for (String sql : allAdminGatedRefuseProbes()) {
                    SQLException refused = assertThrows(SQLException.class,
                        () -> execute(conn, sql),
                        "no infochat.actor_id set — must refuse: " + sql);
                    assertRefusalIsNotLastAdminProtection(refused, sql);
                }
            } finally {
                resetRole(conn);
            }
        }
    }

    @Test
    void adminGatedRoutinesRefuseANonAdminActor() throws Exception {
        UUID nonAdminId = UUID.randomUUID();
        try (Connection conn = dataSource.getConnection()) {
            seedUser(conn, nonAdminId, PREFIX + "nonadmin-" + nonAdminId, false);
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE infochat_provider");
                // set_config(..., true) is transaction-local, so the bind
                // and the calls have to share one transaction — and the
                // rollback below guarantees nothing leaks to the next
                // borrower of this pooled connection.
                bindActor(conn, nonAdminId);
                for (String sql : allAdminGatedRefuseProbes()) {
                    SQLException refused = assertThrows(SQLException.class,
                        () -> execute(conn, sql),
                        "actor is a registered non-admin — must refuse: " + sql);
                    assertRefusalIsNotLastAdminProtection(refused, sql);
                    // The failed statement aborts the transaction; restart
                    // it so the remaining probes each get a live one.
                    conn.rollback();
                    bindActor(conn, nonAdminId);
                }
            } finally {
                conn.rollback();
                resetRole(conn);
                conn.setAutoCommit(true);
                deleteUser(conn, nonAdminId);
            }
        }
    }

    @Test
    void adminGatedRoutinesAcceptABotAdmin() throws Exception {
        // The seeded admin row is deliberately left behind: V40's
        // trg_last_admin_protection_delete raises IC001 on deleting the
        // last live admin, and nothing guarantees another one exists in
        // this cluster. No collector test asserts on the admin count, and
        // the module's other admin-seeding ITs leave theirs the same way.
        UUID adminId = UUID.randomUUID();
        try (Connection conn = dataSource.getConnection()) {
            seedUser(conn, adminId, PREFIX + "admin-" + adminId, true);
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE infochat_provider");
                bindActor(conn, adminId);
                // Every probe targets an id that matches no row, so the
                // actor gate is proven without mutating shared cluster
                // state: reaching the body without raising IS the pass.
                for (String sql : adminGatedProbes()) {
                    execute(conn, sql);
                }
                // The two INSERT-only routines cannot be driven to a
                // no-op, so their accept leg is proven the other way: real
                // FK-valid inserts that the finally-block rollback discards
                // before commit. mint_invite_code's created_by references
                // the seeded admin; insert_preban_user's banned_by is
                // nullable. Reaching the INSERT (no P0001 from the gate) is
                // the pass.
                execute(conn, "SELECT insert_preban_user('" + UUID.randomUUID()
                    + "', 'inmemory', '" + PREFIX + "accept-preban-" + UUID.randomUUID()
                    + "', NULL, NULL)");
                execute(conn, "SELECT mint_invite_code('" + UUID.randomUUID()
                    + "', 'OPEN_ADAPTER', 'inmemory', NULL, '" + adminId
                    + "', NOW() + INTERVAL '7 days')");
            } finally {
                conn.rollback();
                resetRole(conn);
                conn.setAutoCommit(true);
            }
        }
    }

    @Test
    void systemActorRoutinesSucceedWithNoActorSet() throws Exception {
        // The four no-human-actor paths must work with the GUC unset:
        // AdminBootstrap runs at every boot before any admin exists,
        // SimpleXAdminClaim's proof is its token, InviteCodeConsumer's is
        // the code match, and GroupAutoPromoteService's actor is by
        // definition a non-admin (D47 first-mention auto-promote).
        UUID userId = UUID.randomUUID();
        String upstreamGroupId = PREFIX + "sysactor-" + UUID.randomUUID();
        String invitedContactId = PREFIX + "invited-" + UUID.randomUUID();

        try (Connection conn = dataSource.getConnection()) {
            seedUser(conn, userId, PREFIX + "sysactor-member-" + userId, false);
            UUID groupId = seedGroup(conn, upstreamGroupId, userId);
            UUID invitedId = null;
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE infochat_provider");

                // No-op consume: the code matches nothing PENDING.
                try (PreparedStatement call = conn.prepareStatement(
                        "SELECT consume_invite_code(?, ?, 'inmemory')")) {
                    call.setString(1, invitedContactId);
                    call.setObject(2, UUID.randomUUID());
                    try (ResultSet rs = call.executeQuery()) {
                        assertTrue(rs.next());
                        assertEquals(null, rs.getObject(1, UUID.class),
                            "a code matching no PENDING invite must return NULL");
                    }
                }

                // A real privilege-column INSERT with no actor set.
                try (PreparedStatement call = conn.prepareStatement(
                        "SELECT insert_invited_user('inmemory', ?, NULL)")) {
                    call.setString(1, invitedContactId);
                    try (ResultSet rs = call.executeQuery()) {
                        assertTrue(rs.next());
                        invitedId = rs.getObject(1, UUID.class);
                        assertTrue(invitedId != null,
                            "insert_invited_user must mint the row with no actor set");
                    }
                }

                // A real is_group_admin write with no actor set, returning
                // the row count GroupAutoPromoteService branches on.
                try (PreparedStatement call = conn.prepareStatement(
                        "SELECT auto_promote_group_admin(?, ?)")) {
                    call.setObject(1, groupId);
                    call.setObject(2, userId);
                    try (ResultSet rs = call.executeQuery()) {
                        assertTrue(rs.next());
                        assertEquals(1, rs.getInt(1),
                            "auto_promote_group_admin must report the affected row count");
                    }
                }
            } finally {
                resetRole(conn);
                cleanUp(conn, upstreamGroupId, userId);
                if (invitedId != null) {
                    deleteUser(conn, invitedId);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Every admin-gated routine's refuse-leg probe: the twelve that this
     * suite can drive to a domain no-op, plus the two INSERT-only routines
     * whose refuse leg needs no no-op — {@code require_bot_admin_actor()}
     * is the first statement of every routine, so an unset or non-admin
     * GUC raises before the INSERT is reached and the throwaway args never
     * matter. This is the full set of 14; acceptance item 6 requires each
     * to refuse both an unset and a non-admin actor.
     */
    private static String[] allAdminGatedRefuseProbes() {
        String[] base = adminGatedProbes();
        String[] insertOnly = {
            "SELECT insert_preban_user('" + UUID.randomUUID() + "', 'inmemory', '"
                + PREFIX + "refuse-preban', NULL, NULL)",
            "SELECT mint_invite_code('" + UUID.randomUUID() + "', 'OPEN_ADAPTER',"
                + " 'inmemory', NULL, '" + UUID.randomUUID() + "', NOW() + INTERVAL '7 days')",
        };
        String[] all = new String[base.length + insertOnly.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(insertOnly, 0, all, base.length, insertOnly.length);
        return all;
    }

    /**
     * One probe per admin-gated routine that can be aimed at an id matching
     * no row, so the call is a domain no-op and only the actor gate is
     * under test — used directly by the accept leg (reaching a no-op body
     * proves the gate passed without mutating shared cluster state).
     * {@code insert_preban_user} and {@code mint_invite_code} are absent
     * here because they INSERT unconditionally: their refuse legs ride in
     * {@link #allAdminGatedRefuseProbes()}, and their accept leg is proven
     * separately with real, rolled-back inserts in
     * {@link #adminGatedRoutinesAcceptABotAdmin()}.
     */
    private static String[] adminGatedProbes() {
        return new String[] {
            "SELECT ban_known_user('" + UUID.randomUUID() + "', NULL, NULL)",
            "SELECT unban_user('" + UUID.randomUUID() + "')",
            "SELECT grant_bot_admin('" + UUID.randomUUID() + "')",
            "SELECT revoke_bot_admin('" + UUID.randomUUID() + "')",
            "SELECT promote_group_admin('" + UUID.randomUUID() + "', '"
                + UUID.randomUUID() + "')",
            "SELECT demote_group_admins('" + UUID.randomUUID() + "')",
            "SELECT demote_group_admin('" + UUID.randomUUID() + "', '"
                + UUID.randomUUID() + "')",
            "SELECT lock_pending_invite('" + UUID.randomUUID() + "')",
            "SELECT revoke_invite_code('" + UUID.randomUUID() + "')",
            "SELECT * FROM lock_pending_contact_bound_invites('inmemory', '"
                + PREFIX + "absent')",
            "SELECT revoke_contact_bound_invites('inmemory', '" + PREFIX + "absent')",
            "SELECT set_group_approval_status('" + UUID.randomUUID() + "', 'approved')",
        };
    }

    /**
     * An authorization refusal must never borrow the last-admin protection
     * SQLSTATE: {@code BanCommandHandler} and {@code RevokeAdminCommandHandler}
     * branch on {@code IC001} to surface their typed last-admin replies, so
     * mislabelling would make those handlers' tests pass for the wrong
     * reason.
     */
    private static void assertRefusalIsNotLastAdminProtection(SQLException refused, String sql) {
        assertNotEquals(LAST_ADMIN_PROTECTION, refused.getSQLState(),
            "an actor-check refusal must not masquerade as last-admin protection: " + sql);
        assertEquals(PLPGSQL_RAISE, refused.getSQLState(),
            sql + " must refuse from the routine body (P0001); was "
                + refused.getSQLState() + " " + refused.getMessage());
    }

    private static void bindActor(Connection conn, UUID actorId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT set_config('infochat.actor_id', ?, true)")) {
            ps.setString(1, actorId.toString());
            ps.execute();
        }
    }

    private static void seedUser(Connection conn, UUID userId, String contactId,
                                 boolean isAdmin) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (id, adapter, contact_id, is_admin, registration_state)"
                    + " VALUES (?, 'inmemory', ?, ?, 'vouched')")) {
            ps.setObject(1, userId);
            ps.setString(2, contactId);
            ps.setBoolean(3, isAdmin);
            ps.executeUpdate();
        }
    }

    private static UUID seedGroup(Connection conn, String upstreamGroupId, UUID activatedBy)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO groups (adapter, upstream_group_id, activated_by)"
                    + " VALUES ('inmemory', ?, ?) RETURNING id")) {
            ps.setString(1, upstreamGroupId);
            ps.setObject(2, activatedBy);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private static UUID seedGroupWithAdminMember(Connection conn, String upstreamGroupId,
                                                 UUID userId) throws SQLException {
        UUID groupId = seedGroup(conn, upstreamGroupId, userId);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO group_membership (group_id, user_id, is_group_admin)"
                    + " VALUES (?, ?, TRUE)")) {
            ps.setObject(1, groupId);
            ps.setObject(2, userId);
            ps.executeUpdate();
        }
        return groupId;
    }

    private static void cleanUp(Connection conn, String upstreamGroupId, UUID userId)
            throws SQLException {
        execute(conn, "DELETE FROM group_membership WHERE group_id IN"
            + " (SELECT id FROM groups WHERE upstream_group_id = '" + upstreamGroupId + "')");
        execute(conn, "DELETE FROM groups WHERE upstream_group_id = '"
            + upstreamGroupId + "'");
        deleteUser(conn, userId);
    }

    private static void deleteUser(Connection conn, UUID userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM users WHERE id = ?")) {
            ps.setObject(1, userId);
            ps.executeUpdate();
        }
    }

    private static void execute(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static void resetRole(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("RESET ROLE");
        }
    }
}
