package app.zcat.infochat.provider.digest;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SummaryCacheRepositoryTest {

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    SummaryCacheRepository repository;

    private UUID groupId;

    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO groups (adapter, upstream_group_id, display_name, timezone) "
                             + "VALUES ('inmemory', ?, 'test-group', 'UTC') "
                             + "ON CONFLICT (adapter, upstream_group_id) DO UPDATE "
                             + "  SET removed_at = NULL "
                             + "RETURNING id")) {
            ps.setString(1, "digest-cache-test-" + UUID.randomUUID());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                groupId = rs.getObject("id", UUID.class);
            }
        }
    }

    @Test
    void insert_writesRow() throws Exception {
        Instant firedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = firedAt.plus(1, ChronoUnit.HOURS);

        repository.insert(groupId, "morning", firedAt, 1L, 2L,
                "digest content", false, expiresAt);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT content, is_degraded, tag_subscription_version, "
                             + "source_subscription_version FROM summary_cache "
                             + "WHERE group_id = ? AND slot_kind = 'morning' AND slot_fired_at = ?")) {
            ps.setObject(1, groupId);
            ps.setTimestamp(2, java.sql.Timestamp.from(firedAt));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("digest content", rs.getString("content"));
                assertFalse(rs.getBoolean("is_degraded"));
                assertEquals(1L, rs.getLong("tag_subscription_version"));
                assertEquals(2L, rs.getLong("source_subscription_version"));
            }
        }
    }

    @Test
    void findByGroupAndSlot_returnsLatestNonExpired() throws Exception {
        Instant firedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);

        repository.insert(groupId, "evening", firedAt, 5L, 10L,
                "evening digest", false, expiresAt);

        Optional<SummaryCacheRepository.CacheEntry> result =
                repository.findByGroupAndSlot(groupId, "evening", firedAt);

        assertTrue(result.isPresent());
        assertEquals("evening digest", result.get().content());
        assertEquals(5L, result.get().tagSubscriptionVersion());
        assertEquals(10L, result.get().sourceSubscriptionVersion());
    }

    @Test
    void expiredRows_notReturnedByFind() throws Exception {
        Instant firedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant alreadyExpired = Instant.now().minus(1, ChronoUnit.HOURS);

        repository.insert(groupId, "morning", firedAt, 1L, 1L,
                "stale digest", false, alreadyExpired);

        Optional<SummaryCacheRepository.CacheEntry> result =
                repository.findByGroupAndSlot(groupId, "morning", firedAt);

        assertFalse(result.isPresent());
    }
}
