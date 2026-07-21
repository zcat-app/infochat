package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.digest.DigestRenderer.RenderedSection;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-tier IT for {@link DigestCategoryDeliveryRepository}: exercises the V61
 * {@code digest_category_delivery} table under the {@code infochat_provider}
 * grants, the idempotent INSERT ON CONFLICT DO NOTHING record (the
 * scheduled-vs-replay race D64 permits), and the {@link
 * DigestSectionRepository#pruneExpiredForGroup} cross-table prune that
 * covers both replay tables atomically (acceptance items 1, 8, 9).
 */
@QuarkusTest
class DigestCategoryDeliveryRepositoryIT {

    private static final UUID GROUP = UUID.fromString("d1e52320-0c52-4000-8000-000000000001");
    private static final UUID OTHER_GROUP = UUID.fromString("d1e52320-0c52-4000-8000-000000000002");
    private static final Instant WINDOW_START = Instant.parse("2026-07-21T07:00:00Z");
    private static final Instant EXPIRED = Instant.parse("2026-07-21T06:00:00Z");
    private static final Instant LIVE = Instant.parse("2026-07-22T00:00:00Z");

    @Inject @SeedDataSource DataSource dataSource;

    @BeforeEach
    @AfterEach
    void cleanup() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM digest_section WHERE group_id IN (?, ?)", GROUP, OTHER_GROUP);
            exec(conn, "DELETE FROM digest_category_delivery WHERE group_id IN (?, ?)", GROUP, OTHER_GROUP);
            exec(conn, "DELETE FROM summary_cache WHERE group_id IN (?, ?)", GROUP, OTHER_GROUP);
            exec(conn, "DELETE FROM groups WHERE id IN (?, ?)", GROUP, OTHER_GROUP);
        }
    }

    @Test
    void deliveryStateSurvivesRepositoryReconstruction() throws Exception {
        // Acceptance item 8: write via one instance, read via a freshly
        // constructed instance on the same DataSource — the delivery record
        // must survive a Provider restart so a retry after the crash knows
        // which categories already landed.
        DigestCategoryDeliveryRepository writer = newRepo();
        writer.recordDelivery(GROUP, WINDOW_START, "security");
        writer.recordDelivery(GROUP, WINDOW_START, "crypto");

        DigestCategoryDeliveryRepository reader = newRepo();
        Set<String> delivered = reader.findDeliveredSlugs(GROUP, WINDOW_START);

        assertEquals(Set.of("security", "crypto"), delivered,
                "delivery state must survive repository reconstruction");
    }

    @Test
    void recordDelivery_isIdempotentOnConflict() throws Exception {
        // The scheduled-vs-replay race (D64 at-least-once) means a category
        // may be delivered twice. The PK upsert with ON CONFLICT DO NOTHING
        // makes the second record a silent no-op rather than an error, so a
        // duplicate delivery cannot break the retry path.
        DigestCategoryDeliveryRepository repo = newRepo();
        repo.recordDelivery(GROUP, WINDOW_START, "security");
        repo.recordDelivery(GROUP, WINDOW_START, "security");
        repo.recordDelivery(GROUP, WINDOW_START, "security");

        assertEquals(Set.of("security"), repo.findDeliveredSlugs(GROUP, WINDOW_START),
                "duplicate records collapse to one row — ON CONFLICT DO NOTHING");
    }

    @Test
    void pruneExpiredForGroup_dropsOnlyExpiredSlotsDeliveryRecords() throws Exception {
        // Acceptance item 9 (cross-table coverage from the delivery side):
        // the prune in DigestSectionRepository deletes from BOTH tables
        // atomically. Seeding one expired + one live cache row, only the
        // expired slot's delivery records drop.
        seedGroup(GROUP);
        seedGroup(OTHER_GROUP);
        seedCacheRow(GROUP, WINDOW_START, EXPIRED);
        seedCacheRow(OTHER_GROUP, WINDOW_START, LIVE);
        DigestCategoryDeliveryRepository deliveries = newRepo();
        // Seed the delivery rows via the section repo's replace so the
        // cross-table replace also covers delivery (which it wipes first).
        // Pass an EARLY now so the internal prune does not drop the rows
        // before the test's explicit prune call.
        Instant seedNow = Instant.parse("2026-07-21T05:00:00Z");
        DigestSectionRepository seeder = newSectionRepo();
        seeder.replaceSlotSections(GROUP, WINDOW_START, List.of(
                new RenderedSection("security", "expired-slot section")), seedNow);
        seeder.replaceSlotSections(OTHER_GROUP, WINDOW_START, List.of(
                new RenderedSection("security", "live-slot section")), seedNow);
        // Record deliveries for both groups (the replace wiped them; add
        // them back directly for the prune test).
        deliveries.recordDelivery(GROUP, WINDOW_START, "security");
        deliveries.recordDelivery(OTHER_GROUP, WINDOW_START, "security");

        Instant now = Instant.parse("2026-07-21T07:30:00Z");
        int deleted = seeder.pruneExpiredForGroup(GROUP, now);

        assertEquals(2, deleted,
                "two rows pruned for the expired slot — one section + one delivery "
                        + "(the cross-table prune deletes atomically from both)");
        assertTrue(deliveries.findDeliveredSlugs(GROUP, WINDOW_START).isEmpty(),
                "the expired slot's delivery records are gone");
        assertEquals(Set.of("security"),
                deliveries.findDeliveredSlugs(OTHER_GROUP, WINDOW_START),
                "the live slot's delivery records in a different group are untouched");
    }

    // -- helpers ----------------------------------------------------------------

    private DigestCategoryDeliveryRepository newRepo() {
        DigestCategoryDeliveryRepository repo = new DigestCategoryDeliveryRepository();
        repo.dataSource = dataSource;
        return repo;
    }

    private DigestSectionRepository newSectionRepo() {
        DigestSectionRepository repo = new DigestSectionRepository();
        repo.dataSource = dataSource;
        return repo;
    }

    private void seedGroup(UUID groupId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO groups (id, adapter, upstream_group_id,"
                             + " display_name, timezone, approval_status)"
                             + " VALUES (?, 'inmemory', ?, 'IT group', 'UTC', 'approved')"
                             + " ON CONFLICT (id) DO NOTHING")) {
            ps.setObject(1, groupId);
            ps.setString(2, "upstream-" + groupId);
            ps.executeUpdate();
        }
    }

    private void seedCacheRow(UUID groupId, Instant windowStart, Instant expiresAt) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO summary_cache"
                     + " (group_id, slot_kind, slot_fired_at,"
                     + "  tag_subscription_version, source_subscription_version,"
                     + "  content, is_degraded, expires_at)"
                     + " VALUES (?, 'morning', ?, 1, 1, 'fixture', false, ?)")) {
            ps.setObject(1, groupId);
            ps.setTimestamp(2, Timestamp.from(windowStart));
            ps.setTimestamp(3, Timestamp.from(expiresAt));
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }
}
