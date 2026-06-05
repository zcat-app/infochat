package app.zcat.infochat.provider.outbox;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for {@link ProviderStateDao} against the DevServices
 * Postgres container (Flyway-managed schema with V9 applied). Exercises:
 *
 * <ul>
 *   <li>CAS no-op when the supplied cursor is {@code <=} the stored cursor
 *       — earlier AND equal cases (the predicate is strict {@code <}).</li>
 *   <li>CAS success when the supplied cursor is strictly {@code >} the
 *       stored cursor, updating all four mutable columns atomically.</li>
 *   <li>First-boot {@code INSERT … ON CONFLICT (channel) DO NOTHING}
 *       idempotency — re-running the seed insert against the already-
 *       present row is a no-op, never duplicates the singleton row.</li>
 * </ul>
 */
@QuarkusTest
class ProviderStateDaoIT {

    private static final String TEST_CHANNEL = "test_provider_state_dao";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    ProviderStateDao providerStateDao;

    @BeforeEach
    void resetTestRow() throws Exception {
        // Use a test-only channel so this IT does not race with the
        // `new_post` channel the production NewPostListener bean LISTENs on
        // inside the same test JVM. Each @Test method seeds the row to a
        // known state inside this hook.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO provider_state (channel, cursor_high, cursor_low_kind, cursor_low_id, updated_at) "
                     + "VALUES (?, 'epoch'::TIMESTAMPTZ, '', '', now()) "
                     + "ON CONFLICT (channel) DO UPDATE "
                     + "SET cursor_high = 'epoch'::TIMESTAMPTZ, "
                     + "    cursor_low_kind = '', "
                     + "    cursor_low_id = '', "
                     + "    updated_at = now()")) {
            ps.setString(1, TEST_CHANNEL);
            ps.executeUpdate();
        }
    }

    @Test
    void casIsNoOpWhenSuppliedCursorIsEarlierOrEqual() throws Exception {
        // Seed: cursor = (T+10s, 'post', uuidHigh).
        Instant seedHigh = Instant.parse("2026-05-15T12:00:00Z").plus(10, ChronoUnit.SECONDS);
        UUID uuidHigh = UUID.fromString("11111111-1111-1111-1111-111111111111");
        seedCursor(seedHigh, "post", uuidHigh.toString());

        // Earlier on cursor_high: strictly < the stored value → no-op.
        Instant earlier = seedHigh.minus(5, ChronoUnit.SECONDS);
        UUID uuidLow = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertFalse(providerStateDao.advanceCursor(TEST_CHANNEL, earlier, "post", uuidLow.toString()),
            "earlier-cursor advance must be a CAS no-op (return false)");
        assertCursorEquals(seedHigh, "post", uuidHigh.toString());

        // Equal on the full tuple: predicate is strict <, so equal is also no-op.
        assertFalse(providerStateDao.advanceCursor(TEST_CHANNEL, seedHigh, "post", uuidHigh.toString()),
            "equal-cursor advance must be a CAS no-op (return false) — the predicate is strict <");
        assertCursorEquals(seedHigh, "post", uuidHigh.toString());
    }

    @Test
    void casSucceedsAndUpdatesAllFourColumnsAtomicallyWhenSuppliedCursorIsStrictlyGreater() throws Exception {
        // Seed: cursor = (T, 'post', lowUuid).
        Instant seedHigh = Instant.parse("2026-05-15T12:00:00Z");
        UUID lowUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        seedCursor(seedHigh, "post", lowUuid.toString());

        Timestamp beforeUpdatedAt = readUpdatedAt();

        // Strictly greater on cursor_high → CAS succeeds, all 4 columns update.
        Instant later = seedHigh.plus(5, ChronoUnit.SECONDS);
        UUID higherUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
        assertTrue(providerStateDao.advanceCursor(TEST_CHANNEL, later, "post", higherUuid.toString()),
            "strictly-greater advance must return true (one row updated)");

        // All four mutable columns advanced atomically (cursor_high, cursor_low_kind,
        // cursor_low_id, updated_at).
        assertCursorEquals(later, "post", higherUuid.toString());
        Timestamp afterUpdatedAt = readUpdatedAt();
        assertTrue(afterUpdatedAt.compareTo(beforeUpdatedAt) >= 0,
            "updated_at must be at least as new as before the advance "
                + "(before=" + beforeUpdatedAt + ", after=" + afterUpdatedAt + ")");
    }

    @Test
    void seedInsertIsIdempotentUnderOnConflictDoNothing() throws Exception {
        // The migration already seeded the production `new_post` row. Repeat
        // the same INSERT shape — ON CONFLICT (channel) DO NOTHING guarantees
        // the second insert is a no-op and the singleton-row invariant holds.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO provider_state (channel, cursor_high, cursor_low_kind, cursor_low_id, updated_at) "
                     + "VALUES ('new_post', 'epoch'::TIMESTAMPTZ, '', '', now()) "
                     + "ON CONFLICT (channel) DO NOTHING")) {
            int affected = ps.executeUpdate();
            assertEquals(0, affected,
                "the second seed insert must affect zero rows (ON CONFLICT DO NOTHING)");
        }

        // Confirm the singleton invariant: exactly one row for channel='new_post'.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT count(*) FROM provider_state WHERE channel = 'new_post'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1),
                "channel='new_post' must remain a singleton row after the duplicate insert");
        }
    }

    private void seedCursor(Instant cursorHigh, String cursorLowKind, String cursorLowId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE provider_state "
                     + "   SET cursor_high = ?, cursor_low_kind = ?, cursor_low_id = ?, updated_at = now() "
                     + " WHERE channel = ?")) {
            ps.setTimestamp(1, Timestamp.from(cursorHigh));
            ps.setString(2, cursorLowKind);
            ps.setString(3, cursorLowId);
            ps.setString(4, TEST_CHANNEL);
            int updated = ps.executeUpdate();
            assertEquals(1, updated, "seed UPDATE must affect exactly one test row");
        }
    }

    private void assertCursorEquals(Instant expectedHigh, String expectedKind, String expectedId)
            throws Exception {
        Optional<ProviderStateDao.Cursor> stored = providerStateDao.readCursor(TEST_CHANNEL);
        assertTrue(stored.isPresent(), "test row must exist");
        ProviderStateDao.Cursor c = stored.get();
        assertEquals(expectedHigh, c.cursorHigh(), "cursor_high mismatch");
        assertEquals(expectedKind, c.cursorLowKind(), "cursor_low_kind mismatch");
        assertEquals(expectedId, c.cursorLowId(), "cursor_low_id mismatch");
    }

    private Timestamp readUpdatedAt() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT updated_at FROM provider_state WHERE channel = ?")) {
            ps.setString(1, TEST_CHANNEL);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "test row must exist when reading updated_at");
                return rs.getTimestamp("updated_at");
            }
        }
    }
}
