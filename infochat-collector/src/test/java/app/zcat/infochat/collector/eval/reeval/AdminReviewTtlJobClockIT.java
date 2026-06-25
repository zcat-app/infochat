package app.zcat.infochat.collector.eval.reeval;

import app.zcat.infochat.collector.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the injected {@link Clock} to a FIXED instant and asserts the
 * admin-review TTL gate ({@link AdminReviewTtlJob#enumerateExpired})
 * decides against that instant — not the wall-clock run date. This is the
 * deterministic complement to the relative-fixture assertions in
 * {@link AdminReviewTtlJobTest}, which seed {@code flagged_at} via
 * {@code Instant.now()} and therefore depend on the real date. (M1-447)
 *
 * <p>Two PENDING quarantine rows straddle the cutoff
 * ({@code PINNED_NOW − adminReviewTtl}): one just past it (must be
 * enumerated) and one just inside it (must NOT be). The rows are seeded
 * directly into {@code quarantine} with no {@code post} row, because the
 * gate query reads only {@code quarantine} (it uses the denormalized
 * {@code post_fetched_at}, no join on post).
 */
@QuarkusTest
class AdminReviewTtlJobClockIT {

    // A FIXED instant the gate reads via the injected Clock (installed in
    // pinClock()). The seed flagged_at values below are computed relative to
    // this constant and the configured TTL, so the boundary is exercised
    // deterministically regardless of the wall-clock date.
    private static final Instant PINNED_NOW = Instant.parse("2026-05-25T09:00:00Z");

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    AdminReviewTtlJob ttlJob;

    @ConfigProperty(name = "infochat.reeval.admin-review-ttl")
    Duration adminReviewTtl;

    @BeforeEach
    void pinClock() {
        // Same QuarkusMock seam ThrottledAdminNotifier's Clock producer
        // documents (M1-444); pins the instant enumerateExpired reads.
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
    }

    @Test
    void enumerateExpired_gatesOnInjectedClock() throws Exception {
        Instant cutoff = PINNED_NOW.minus(adminReviewTtl);
        // One full day past the cutoff: well within the gate and old enough to
        // sort early under ORDER BY flagged_at, so the batch LIMIT never hides
        // it behind unrelated rows.
        UUID pastId = seedPendingQuarantine(cutoff.minus(Duration.ofDays(1)));
        // One minute inside the cutoff: must NOT be enumerated.
        UUID insideId = seedPendingQuarantine(cutoff.plusSeconds(60));

        var candidates = ttlJob.enumerateExpired();

        assertTrue(candidates.stream().anyMatch(c -> c.quarantineId().equals(pastId)),
            "a row flagged before PINNED_NOW − adminReviewTtl must be enumerated");
        assertFalse(candidates.stream().anyMatch(c -> c.quarantineId().equals(insideId)),
            "a row flagged after the cutoff must NOT be enumerated — the gate "
                + "reads the injected Clock, so a fixed clock makes the boundary "
                + "deterministic");
    }

    private UUID seedPendingQuarantine(Instant flaggedAt) throws Exception {
        // Orphan post_id (no post row): enumerateExpired reads only quarantine.
        UUID orphanPostId = UUID.randomUUID();
        Instant fetchedAt = Instant.parse("2026-05-18T12:00:00Z");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO quarantine ("
                     + "  id, post_id, post_uid, post_fetched_at, flagged_at, flagged_by,"
                     + "  rule_id, placeholder_id, original_html, status"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, 'uid', ?, ?, 'stage1',"
                     + "  'regex-test', 'clock-ph', 'original', 'PENDING'"
                     + ") RETURNING id")) {
            ps.setObject(1, orphanPostId);
            ps.setTimestamp(2, Timestamp.from(fetchedAt));
            ps.setTimestamp(3, Timestamp.from(flaggedAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }
}
