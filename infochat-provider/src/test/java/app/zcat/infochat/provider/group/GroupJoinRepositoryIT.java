package app.zcat.infochat.provider.group;

import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link GroupJoinRepository}, the durable join-tracking the D47
 * total group-count caps count (M1-519). Covers acceptance item 3 (durability)
 * and the ON CONFLICT idempotency the cap accounting relies on.
 */
@QuarkusTest
class GroupJoinRepositoryIT {

    private static final String TEST_ADAPTER = "inmemory";
    private static final String CONTACT_PREFIX = "gjr-" + UUID.randomUUID() + "-";
    private static final String GROUP_PREFIX = "gjr-grp-" + UUID.randomUUID() + "-";

    @Inject @SeedDataSource DataSource dataSource;
    @Inject GroupJoinRepository repository;

    @AfterEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // Join rows first — inviter_user_id FK-references users(id).
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM auto_joined_group WHERE adapter = ? AND upstream_group_id LIKE ?")) {
                ps.setString(1, TEST_ADAPTER);
                ps.setString(2, GROUP_PREFIX + "%");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM users WHERE adapter = ? AND contact_id LIKE ?")) {
                ps.setString(1, TEST_ADAPTER);
                ps.setString(2, CONTACT_PREFIX + "%");
                ps.executeUpdate();
            }
        }
    }

    @Test
    void recordedJoinIsCountedByInviterAndGlobally() throws Exception {
        UUID inviter = seedUser();
        long globalBefore = repository.countJoins();
        assertEquals(0, repository.countJoinsByInviter(inviter),
                "a fresh inviter has recorded no joins");

        repository.tryRecordJoin(TEST_ADAPTER, group("0"), inviter);
        repository.tryRecordJoin(TEST_ADAPTER, group("1"), inviter);

        assertEquals(2, repository.countJoinsByInviter(inviter),
                "both distinct groups count toward the inviter's activation cap");
        // Global count is across all rows; assert the delta is exact (test
        // classes run sequentially, so no concurrent writer perturbs it).
        assertEquals(globalBefore + 2, repository.countJoins(),
                "both joins count toward the global max-groups cap");
    }

    @Test
    void duplicateJoinForSameGroupCountsOnce() throws Exception {
        UUID inviter = seedUser();

        repository.tryRecordJoin(TEST_ADAPTER, group("dup"), inviter);
        repository.tryRecordJoin(TEST_ADAPTER, group("dup"), inviter); // same natural key

        assertEquals(1, repository.countJoinsByInviter(inviter),
                "the unique natural key means a re-recorded join of the same active group "
                        + "counts once (ON CONFLICT reactivates the single row in place), "
                        + "so a duplicate invitation cannot inflate the cap count");
    }

    @Test
    void recordedJoinIsCommittedAndReadableOnAFreshConnection() throws Exception {
        // Durability (acceptance item 3): a recorded join is committed to the DB,
        // not merely visible inside the writer's transaction. A committed row is
        // what survives a process restart; @QuarkusTest cannot fork the JVM, so a
        // read on an independent connection is the in-test durability proof.
        UUID inviter = seedUser();
        repository.tryRecordJoin(TEST_ADAPTER, group("durable"), inviter);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM auto_joined_group "
                             + "WHERE adapter = ? AND upstream_group_id = ?")) {
            ps.setString(1, TEST_ADAPTER);
            ps.setString(2, group("durable"));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(1, rs.getLong(1),
                        "the recorded join is persisted (committed) and visible to a new connection");
            }
        }
        assertEquals(1, repository.countJoinsByInviter(inviter),
                "the committed row is counted back, so the cap survives a restart");
    }

    @Test
    void freedJoinIsExcludedFromBothCounts() throws Exception {
        // M1-525: a slot freed via removed_at (bot left the group) must stop
        // counting against BOTH the per-inviter and the global D47 caps.
        UUID inviter = seedUser();
        long globalBefore = repository.countJoins();
        repository.tryRecordJoin(TEST_ADAPTER, group("keep"), inviter);
        repository.tryRecordJoin(TEST_ADAPTER, group("free"), inviter);
        assertEquals(2, repository.countJoinsByInviter(inviter),
                "both joins count before either is freed");
        assertEquals(globalBefore + 2, repository.countJoins(),
                "both joins count globally before either is freed");

        assertTrue(repository.markRemovedByNaturalKey(TEST_ADAPTER, group("free")),
                "freeing a non-removed slot reports a row was freed");

        assertEquals(1, repository.countJoinsByInviter(inviter),
                "a removed_at-set row is excluded from the per-inviter count");
        assertEquals(globalBefore + 1, repository.countJoins(),
                "a removed_at-set row is excluded from the global count");

        assertFalse(repository.markRemovedByNaturalKey(TEST_ADAPTER, group("free")),
                "freeing an already-freed slot is an idempotent no-op (no row freed)");
    }

    @Test
    void reJoinReactivatesFreedSlotAndReattributesInviter() throws Exception {
        // M1-525 item 4 (redteam HIGH remediation): after a slot is freed, a
        // re-join of the same natural key must REACTIVATE the row — re-count it
        // against both D47 caps and re-attribute it to the current inviter — so a
        // leave->re-join cycle cannot launder an active group into an uncounted
        // one.
        UUID firstInviter = seedUser();
        UUID secondInviter = seedUser();
        long globalBefore = repository.countJoins();

        repository.tryRecordJoin(TEST_ADAPTER, group("launder"), firstInviter);
        repository.markRemovedByNaturalKey(TEST_ADAPTER, group("launder"));
        assertEquals(globalBefore, repository.countJoins(),
                "the freed slot is uncounted globally");
        assertEquals(0, repository.countJoinsByInviter(firstInviter),
                "the freed slot is uncounted for the original inviter");

        // Re-join by a DIFFERENT inviter.
        repository.tryRecordJoin(TEST_ADAPTER, group("launder"), secondInviter);

        assertEquals(globalBefore + 1, repository.countJoins(),
                "re-join reactivates the slot: it counts globally again (cap not laundered)");
        assertEquals(1, repository.countJoinsByInviter(secondInviter),
                "the reactivated slot is re-attributed to the current inviter");
        assertEquals(0, repository.countJoinsByInviter(firstInviter),
                "the reactivated slot no longer counts against the original inviter");

        // Direct row check: exactly one row, removed_at NULL, inviter re-attributed.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT inviter_user_id, removed_at FROM auto_joined_group "
                             + "WHERE adapter = ? AND upstream_group_id = ?")) {
            ps.setString(1, TEST_ADAPTER);
            ps.setString(2, group("launder"));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the reactivated row exists");
                assertEquals(secondInviter, rs.getObject("inviter_user_id", UUID.class),
                        "inviter_user_id re-attributed to the current inviter");
                assertNull(rs.getTimestamp("removed_at"),
                        "removed_at cleared back to NULL on reactivation");
                assertFalse(rs.next(), "exactly one row for the natural key (reactivated in place)");
            }
        }
    }

    private static String group(String suffix) {
        return GROUP_PREFIX + suffix;
    }

    private UUID seedUser() throws Exception {
        UUID id = UUID.randomUUID();
        String contactId = CONTACT_PREFIX + id;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (id, adapter, contact_id, registration_state, is_banned) "
                             + "VALUES (?, ?, ?, 'vouched', FALSE)")) {
            ps.setObject(1, id);
            ps.setString(2, TEST_ADAPTER);
            ps.setString(3, contactId);
            ps.executeUpdate();
        }
        return id;
    }
}
