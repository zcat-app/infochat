package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.Identity;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link AutoRegisterService} against the
 * DevServices Postgres container (Flyway-applied V5 users table).
 * Four invariants are pinned, each in its own {@code @Test}:
 *
 * <ol>
 *   <li>{@code groupFreshInsert} — a fresh
 *       {@code (adapter, contact_id)} insert writes
 *       {@code registration_state='group_only'} AND
 *       {@code probation_until ≈ NOW() + 24h} (the default value of
 *       the {@code infochat.probation.duration} property).</li>
 *   <li>{@code groupIdempotent} — a second call with the same
 *       identity returns the existing row's id, writes no second
 *       row, and does NOT modify the existing row's
 *       {@code registration_state} or {@code probation_until}.</li>
 *   <li>Concurrent first-{@code @mentions} from the same
 *       {@code contact_id} produce exactly one row via the
 *       {@code ON CONFLICT (adapter, contact_id) DO NOTHING} race
 *       protection.</li>
 *   <li>The same {@code contact_id} across two different adapters
 *       produces two distinct rows — cross-adapter isolation per
 *       D46 + the V5 UNIQUE (adapter, contact_id) constraint.</li>
 * </ol>
 */
@QuarkusTest
class AutoRegisterServiceTest {

    @Inject
    AutoRegisterService autoRegisterService;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void cleanTestContacts() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM users WHERE contact_id LIKE 'test-%' "
                             + "OR contact_id LIKE 'race-%' "
                             + "OR contact_id LIKE 'dup-%'")) {
            ps.executeUpdate();
        }
    }

    @Test
    void groupFreshInsert() throws Exception {
        Instant before = Instant.now();
        UUID id = autoRegisterService.resolveOrRegisterGroup(
                identity("test-1", "Test One"), "inmemory");
        Instant after = Instant.now();
        assertNotNull(id, "resolveOrRegisterGroup must return a non-null UUID");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, adapter, contact_id, display_name, is_admin, "
                             + "registration_state, probation_until "
                             + "FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, "inmemory");
            ps.setString(2, "test-1");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "row must exist after resolveOrRegisterGroup");
                assertEquals(id, rs.getObject("id", UUID.class));
                assertEquals("inmemory", rs.getString("adapter"));
                assertEquals("test-1", rs.getString("contact_id"));
                assertEquals("Test One", rs.getString("display_name"));
                assertFalse(rs.getBoolean("is_admin"),
                        "is_admin must be FALSE — hardcoded in AutoRegisterService.UPSERT_SQL");
                assertEquals("group_only", rs.getString("registration_state"),
                        "registration_state must be 'group_only' per spec §Authorization model step 3");

                Timestamp probation = rs.getTimestamp("probation_until");
                assertNotNull(probation,
                        "probation_until must be populated to NOW() + slow_start_window");
                // The default infochat.probation.duration is 24h. Allow a
                // generous +/- 30s tolerance to absorb test-VM scheduling
                // slack between the JVM clock and Postgres NOW().
                Instant expectedMin = before.plus(Duration.ofHours(24)).minusSeconds(30);
                Instant expectedMax = after.plus(Duration.ofHours(24)).plusSeconds(30);
                Instant actual = probation.toInstant();
                assertTrue(!actual.isBefore(expectedMin) && !actual.isAfter(expectedMax),
                        "probation_until=" + actual + " must lie in ["
                                + expectedMin + ", " + expectedMax + "]");

                assertFalse(rs.next(), "exactly one row must match (adapter, contact_id)");
            }
        }
    }

    @Test
    void groupIdempotent() throws Exception {
        UUID first = autoRegisterService.resolveOrRegisterGroup(
                identity("test-1", "Test One"), "inmemory");
        assertEquals(1L, countRows("inmemory", "test-1"));

        // Capture the existing row's probation_until + registration_state
        // so the idempotent assertion can pin "no modification".
        Snapshot beforeSecond = snapshot("inmemory", "test-1");

        UUID second = autoRegisterService.resolveOrRegisterGroup(
                identity("test-1", "Test One"), "inmemory");
        assertEquals(first, second,
                "idempotent call must return the same UUID as the first call");
        assertEquals(1L, countRows("inmemory", "test-1"),
                "second resolveOrRegisterGroup must NOT insert a second row");

        Snapshot afterSecond = snapshot("inmemory", "test-1");
        assertEquals(beforeSecond.registrationState, afterSecond.registrationState,
                "registration_state must NOT change on the idempotent call");
        assertEquals(beforeSecond.probationUntil, afterSecond.probationUntil,
                "probation_until must NOT change on the idempotent call");
    }

    @Test
    void concurrentFirstMentionsFromSameContactIdProduceExactlyOneRowViaOnConflictRaceProtection()
            throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<UUID> a = pool.submit(() -> {
                latch.await();
                return autoRegisterService.resolveOrRegisterGroup(
                        identity("race-1", "Racer A"), "inmemory");
            });
            Future<UUID> b = pool.submit(() -> {
                latch.await();
                return autoRegisterService.resolveOrRegisterGroup(
                        identity("race-1", "Racer B"), "inmemory");
            });

            latch.countDown();

            UUID idA;
            UUID idB;
            try {
                idA = a.get(15, TimeUnit.SECONDS);
                idB = b.get(15, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                throw new AssertionError(
                        "concurrent resolveOrRegisterGroup raised — ON CONFLICT DO NOTHING "
                                + "must absorb the race", e);
            }

            assertEquals(idA, idB,
                    "both threads must observe the same users.id (one wins the insert; "
                            + "the other reads it back)");
            assertEquals(1L, countRows("inmemory", "race-1"),
                    "exactly one row must exist for the raced (adapter, contact_id)");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void crossAdapterContactIdsProduceTwoDistinctRows() throws Exception {
        UUID inmemoryId = autoRegisterService.resolveOrRegisterGroup(
                identity("dup-1", "Inmemory User"), "inmemory");
        UUID inmemory2Id = autoRegisterService.resolveOrRegisterGroup(
                identity("dup-1", "Inmemory2 User"), "inmemory2");

        assertNotNull(inmemoryId);
        assertNotNull(inmemory2Id);
        assertFalse(inmemoryId.equals(inmemory2Id),
                "rows under different adapters must have distinct ids; "
                        + "cross-adapter isolation per messaging.md §Per-adapter trust level");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM users WHERE contact_id = 'dup-1'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(2L, rs.getLong(1),
                        "exactly two rows must exist for contact_id='dup-1' "
                                + "(one per adapter); UNIQUE is on (adapter, contact_id), not contact_id alone");
            }
        }
    }

    private static Identity identity(String contactId, String displayName) {
        return new Identity(contactId, displayName, Instant.now());
    }

    private long countRows(String adapter, String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    private Snapshot snapshot(String adapter, String contactId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT registration_state, probation_until FROM users "
                             + "WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return new Snapshot(rs.getString("registration_state"),
                        rs.getTimestamp("probation_until"));
            }
        }
    }

    private record Snapshot(String registrationState, Timestamp probationUntil) {}
}
