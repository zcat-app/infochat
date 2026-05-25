package app.zcat.infochat.provider.group;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class GroupRepositoryTest {

    private static final String TEST_ADAPTER = "inmemory";
    private static final String TEST_UPSTREAM_ID = "grp-test-" + UUID.randomUUID();

    @Inject DataSource dataSource;
    @Inject GroupRepository repository;

    @BeforeEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
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
        }
    }

    @Test
    void findOrCreateByAdapterAndUpstreamId_insertsNewGroup() throws Exception {
        UUID id = repository.findOrCreateByAdapterAndUpstreamId(TEST_ADAPTER, TEST_UPSTREAM_ID);
        assertNotNull(id);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT adapter, upstream_group_id, timezone, removed_at "
                   + "FROM groups WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(TEST_ADAPTER, rs.getString("adapter"));
                assertEquals(TEST_UPSTREAM_ID, rs.getString("upstream_group_id"));
                assertEquals("UTC", rs.getString("timezone"));
                assertNull(rs.getTimestamp("removed_at"));
            }
        }
    }

    @Test
    void findOrCreateByAdapterAndUpstreamId_returnsExistingOnDuplicate() {
        UUID first = repository.findOrCreateByAdapterAndUpstreamId(TEST_ADAPTER, TEST_UPSTREAM_ID);
        UUID second = repository.findOrCreateByAdapterAndUpstreamId(TEST_ADAPTER, TEST_UPSTREAM_ID);
        assertEquals(first, second);
    }

    @Test
    void markRemoved_setsRemovedAtTimestamp() throws Exception {
        UUID id = repository.findOrCreateByAdapterAndUpstreamId(TEST_ADAPTER, TEST_UPSTREAM_ID);
        repository.markRemoved(id);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT removed_at FROM groups WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                Timestamp removedAt = rs.getTimestamp("removed_at");
                assertNotNull(removedAt);
            }
        }
    }

    @Test
    void clearRemoved_unsetsRemovedAtOnRejoin() throws Exception {
        UUID id = repository.findOrCreateByAdapterAndUpstreamId(TEST_ADAPTER, TEST_UPSTREAM_ID);
        repository.markRemoved(id);
        repository.clearRemoved(id);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT removed_at FROM groups WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertNull(rs.getTimestamp("removed_at"));
            }
        }
    }
}
