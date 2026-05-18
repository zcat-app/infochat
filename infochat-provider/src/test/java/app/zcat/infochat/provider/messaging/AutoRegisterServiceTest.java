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
 * DevServices Postgres container (Flyway-applied schema, V5 users
 * table). Four invariants are pinned, each in its own {@code @Test}:
 *
 * <ol>
 *   <li>First-DM inserts a row with the spec-required defaults
 *       ({@code is_admin=FALSE}, {@code registration_state='invited'},
 *       slow-start probation column left at the NULL default).</li>
 *   <li>A second call with the same identity is idempotent — returns
 *       the existing row's id, no second insert.</li>
 *   <li>Concurrent first-DMs from the same {@code contact_id} produce
 *       exactly one row via the {@code ON CONFLICT (adapter,
 *       contact_id) DO NOTHING} race protection.</li>
 *   <li>The same {@code contact_id} across two different adapters
 *       produces two distinct rows — the cross-adapter isolation
 *       invariant per {@code docs/spec/messaging.md} §Per-adapter
 *       trust level.</li>
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
        // Each @Test uses contact_ids in the "test-" / "race-" / "dup-"
        // namespaces; clean only those so the test does not race the
        // bootstrap-admin row (deferred) or other tests' fixtures.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM users WHERE contact_id LIKE 'test-%' "
                             + "OR contact_id LIKE 'race-%' "
                             + "OR contact_id LIKE 'dup-%'")) {
            ps.executeUpdate();
        }
    }

    @Test
    void firstDmInsertsRowWithSpecRequiredDefaults() throws Exception {
        UUID id = autoRegisterService.resolveOrRegister(
                identity("test-1", "Test One"), "inmemory");
        assertNotNull(id, "resolveOrRegister must return a non-null UUID");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, adapter, contact_id, display_name, is_admin, "
                             + "registration_state, probation_until "
                             + "FROM users WHERE adapter = ? AND contact_id = ?")) {
            ps.setString(1, "inmemory");
            ps.setString(2, "test-1");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "row must exist after resolveOrRegister");
                assertEquals(id, rs.getObject("id", UUID.class));
                assertEquals("inmemory", rs.getString("adapter"));
                assertEquals("test-1", rs.getString("contact_id"));
                assertEquals("Test One", rs.getString("display_name"));
                assertFalse(rs.getBoolean("is_admin"),
                        "is_admin must be FALSE — hardcoded in AutoRegisterService.UPSERT_SQL");
                assertEquals("invited", rs.getString("registration_state"),
                        "registration_state must be 'invited' per the four-value V5 CHECK");
                // The slow-start column is left out of the INSERT so the V5 column
                // default (NULL) applies — T2-A's slow-start tier is not wired here.
                rs.getTimestamp("probation_until");
                assertTrue(rs.wasNull(),
                        "probation_until must be NULL (column omitted from INSERT)");
                assertFalse(rs.next(), "exactly one row must match (adapter, contact_id)");
            }
        }
    }

    @Test
    void secondCallWithSameIdentityIsIdempotentReturningExistingRow() throws Exception {
        UUID first = autoRegisterService.resolveOrRegister(
                identity("test-1", "Test One"), "inmemory");
        assertEquals(1L, countRows("inmemory", "test-1"));

        UUID second = autoRegisterService.resolveOrRegister(
                identity("test-1", "Test One"), "inmemory");
        assertEquals(first, second,
                "idempotent call must return the same UUID as the first call");
        assertEquals(1L, countRows("inmemory", "test-1"),
                "second resolveOrRegister must NOT insert a second row");
    }

    @Test
    void concurrentFirstDmsFromSameContactIdProduceExactlyOneRowViaOnConflictRaceProtection()
            throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<UUID> a = pool.submit(() -> {
                latch.await();
                return autoRegisterService.resolveOrRegister(
                        identity("race-1", "Racer A"), "inmemory");
            });
            Future<UUID> b = pool.submit(() -> {
                latch.await();
                return autoRegisterService.resolveOrRegister(
                        identity("race-1", "Racer B"), "inmemory");
            });

            // Release both threads simultaneously so the two upserts race
            // the UNIQUE (adapter, contact_id) constraint.
            latch.countDown();

            UUID idA;
            UUID idB;
            try {
                idA = a.get(15, TimeUnit.SECONDS);
                idB = b.get(15, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                throw new AssertionError(
                        "concurrent resolveOrRegister raised — ON CONFLICT DO NOTHING "
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
        UUID inmemoryId = autoRegisterService.resolveOrRegister(
                identity("dup-1", "Inmemory User"), "inmemory");
        UUID inmemory2Id = autoRegisterService.resolveOrRegister(
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
}
