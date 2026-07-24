package app.zcat.infochat.provider.group;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class GroupMembershipRepositoryTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID USER_ID_2 = UUID.randomUUID();
    private static final String TEST_ADAPTER = "inmemory";
    private static final String TEST_UPSTREAM_ID = "mem-test-" + UUID.randomUUID();

    @Inject @SeedDataSource DataSource dataSource;
    @Inject GroupRepository groupRepository;
    @Inject GroupMembershipRepository repository;

    private UUID groupId;
    private UUID botAdminId;

    @BeforeEach
    void setup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            botAdminId = resolveBotAdmin(conn);
            // Clean up prior test membership and group rows
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM group_membership WHERE group_id IN "
                  + "(SELECT id FROM groups WHERE upstream_group_id = ?)")) {
                ps.setString(1, TEST_UPSTREAM_ID);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM groups WHERE upstream_group_id = ?")) {
                ps.setString(1, TEST_UPSTREAM_ID);
                ps.executeUpdate();
            }
            // Seed prerequisite users
            seedUser(conn, USER_ID, "mem-" + USER_ID);
            seedUser(conn, USER_ID_2, "mem-" + USER_ID_2);
        }
        groupId = groupRepository.findOrCreateByAdapterAndUpstreamId(TEST_ADAPTER, TEST_UPSTREAM_ID);
    }

    /**
     * Resolve any live bot admin to act as the fixture's actor. The V62
     * routines behind {@code promoteToAdmin} / {@code demoteAdmin} gate on
     * {@code infochat.actor_id} naming an {@code is_admin} row, so these
     * fixtures need one — but they deliberately reuse an existing admin
     * rather than seeding another: an extra unbanned admin row would be a
     * permanent fixture leak that breaks the last-admin-protection tests,
     * whose premise is that exactly one such row remains. The
     * {@code AdminBootstrap} startup bean seeds one for the {@code %test}
     * inmemory contact at every boot.
     */
    private UUID resolveBotAdmin(Connection conn) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM users WHERE is_admin = TRUE AND is_banned = FALSE LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(),
                    "fixture precondition: the %test cluster must carry at least one "
                          + "live bot admin (AdminBootstrap seeds one at boot)");
            return rs.getObject(1, UUID.class);
        }
    }

    /**
     * Drive {@link GroupMembershipRepository#promoteToAdmin} the way
     * production drives a routine-mediated write: one transaction, actor
     * GUC bound first. {@code set_config(..., true)} is transaction-local,
     * so the bind and the call must share a connection. A rejected promote
     * leaves the transaction in Postgres's failed state, so only the
     * successful path has anything to commit.
     */
    private boolean promoteToAdmin(UUID gId, UUID uId) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            bindActor(conn);
            boolean promoted = repository.promoteToAdmin(conn, gId, uId);
            if (promoted) {
                conn.commit();
            } else {
                conn.rollback();
            }
            return promoted;
        }
    }

    private void demoteAdmin(UUID gId, UUID uId) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            bindActor(conn);
            repository.demoteAdmin(conn, gId, uId);
            conn.commit();
        }
    }

    private void bindActor(Connection conn) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT set_config('infochat.actor_id', ?, true)")) {
            ps.setString(1, botAdminId.toString());
            ps.execute();
        }
    }

    private void seedUser(Connection conn, UUID userId, String contactId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (id, adapter, contact_id, registration_state) "
              + "VALUES (?, 'inmemory', ?, 'vouched') "
              + "ON CONFLICT (id) DO NOTHING")) {
            ps.setObject(1, userId);
            ps.setString(2, contactId);
            ps.executeUpdate();
        }
    }

    @Test
    void addMember_insertsRow() throws Exception {
        repository.addMember(groupId, USER_ID);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM group_membership WHERE group_id = ? AND user_id = ?")) {
            ps.setObject(1, groupId);
            ps.setObject(2, USER_ID);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
            }
        }
    }

    @Test
    void isGroupAdmin_returnsTrueForAdminRow() throws Exception {
        repository.addMember(groupId, USER_ID);
        promoteToAdmin(groupId, USER_ID);
        assertTrue(repository.isGroupAdmin(groupId, USER_ID));
    }

    @Test
    void isGroupAdmin_returnsFalseForNonAdminOrAbsent() {
        repository.addMember(groupId, USER_ID);
        assertFalse(repository.isGroupAdmin(groupId, USER_ID));
        assertFalse(repository.isGroupAdmin(groupId, UUID.randomUUID()));
    }

    @Test
    void promoteToAdmin_setsFlag() throws Exception {
        repository.addMember(groupId, USER_ID);
        boolean promoted = promoteToAdmin(groupId, USER_ID);
        assertTrue(promoted);
        assertTrue(readAdminFlag(groupId, USER_ID));
    }

    @Test
    void demoteAdmin_clearsFlag() throws Exception {
        repository.addMember(groupId, USER_ID);
        promoteToAdmin(groupId, USER_ID);
        demoteAdmin(groupId, USER_ID);
        assertFalse(readAdminFlag(groupId, USER_ID));
    }

    @Test
    void partialUniqueIndex_rejectsSecondAdmin() throws Exception {
        repository.addMember(groupId, USER_ID);
        repository.addMember(groupId, USER_ID_2);
        assertTrue(promoteToAdmin(groupId, USER_ID));
        assertFalse(promoteToAdmin(groupId, USER_ID_2));
    }

    // Verifies the V5 trigger clears is_group_admin when removed_at is set.
    @Test
    void markMemberRemoved_triggersAdminFlagClear() throws Exception {
        repository.addMember(groupId, USER_ID);
        promoteToAdmin(groupId, USER_ID);
        assertTrue(readAdminFlag(groupId, USER_ID));

        repository.markMemberRemoved(groupId, USER_ID);

        // The trigger should have cleared is_group_admin at the DB level
        assertFalse(readAdminFlag(groupId, USER_ID));
        // Verify removed_at was set
        assertNotNull(readRemovedAt(groupId, USER_ID));
    }

    private boolean readAdminFlag(UUID gId, UUID uId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_group_admin FROM group_membership "
                   + "WHERE group_id = ? AND user_id = ?")) {
            ps.setObject(1, gId);
            ps.setObject(2, uId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean("is_group_admin");
            }
        }
    }

    private java.sql.Timestamp readRemovedAt(UUID gId, UUID uId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT removed_at FROM group_membership "
                   + "WHERE group_id = ? AND user_id = ?")) {
            ps.setObject(1, gId);
            ps.setObject(2, uId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getTimestamp("removed_at") : null;
            }
        }
    }
}
