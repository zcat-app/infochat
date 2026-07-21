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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-tier IT for {@link DigestSectionRepository}: exercises the V61
 * {@code digest_section} table under the {@code infochat_provider} grants
 * the migration lands, the slot-replace atomicity (sections + delivery
 * records wiped together), and the cache-row-join prune (acceptance items
 * 1, 8, 9). Mirrors {@code DigestRetryConcurrencyIT}'s
 * {@code @SeedDataSource} seeding + service-role write pattern.
 */
@QuarkusTest
class DigestSectionRepositoryIT {

    private static final UUID GROUP = UUID.fromString("d1e52320-0652-4000-8000-000000000001");
    private static final UUID OTHER_GROUP = UUID.fromString("d1e52320-0652-4000-8000-000000000002");
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
    void sectionsSurviveRepositoryReconstruction() throws Exception {
        // Acceptance item 8: write via one instance, read via a freshly
        // constructed instance on the same DataSource — the persisted state
        // must live in the table, not in process memory, so a Provider
        // restart finds it (the whole point of the gap-fill design).
        DigestSectionRepository writer = newRepo();
        writer.replaceSlotSections(GROUP, WINDOW_START, List.of(
                new RenderedSection("security", "section A bytes"),
                new RenderedSection("crypto", "section B bytes"),
                new RenderedSection(null, "section Other bytes")),
                Instant.now());

        DigestSectionRepository reader = newRepo();
        List<RenderedSection> readBack = reader.findOrderedSections(GROUP, WINDOW_START);

        assertEquals(List.of(
                new RenderedSection("security", "section A bytes"),
                new RenderedSection("crypto", "section B bytes"),
                new RenderedSection(null, "section Other bytes")),
                readBack,
                "persisted sections must survive repository reconstruction in position order");
    }

    @Test
    void replaceSlotSections_secondReplaceFullySupersedes() throws Exception {
        // The slot-replace is atomic across BOTH tables: a second replace
        // for the same slot wipes every prior section AND every prior
        // delivery record before inserting the new list, so stale state
        // from a superseded render can never leak into a later replay
        // (the "replay never half-applies" property).
        DigestSectionRepository sections = newRepo();
        DigestCategoryDeliveryRepository deliveries = newDeliveryRepo();
        sections.replaceSlotSections(GROUP, WINDOW_START, List.of(
                new RenderedSection("security", "old A"),
                new RenderedSection("crypto", "old B")),
                Instant.now());
        deliveries.recordDelivery(GROUP, WINDOW_START, "security");
        deliveries.recordDelivery(GROUP, WINDOW_START, "crypto");

        sections.replaceSlotSections(GROUP, WINDOW_START, List.of(
                new RenderedSection("ai", "new A bytes"),
                new RenderedSection("news", "new B bytes")),
                Instant.now());

        assertEquals(List.of(
                new RenderedSection("ai", "new A bytes"),
                new RenderedSection("news", "new B bytes")),
                sections.findOrderedSections(GROUP, WINDOW_START),
                "second replace fully supersedes — prior sections are gone, not merged");
        assertTrue(deliveries.findDeliveredSlugs(GROUP, WINDOW_START).isEmpty(),
                "second replace wipes prior delivery records too — a regeneration's bytes "
                        + "diverge, so the old delivery records must not survive to suppress "
                        + "the next replay");
    }

    @Test
    void replaceSlotSections_emptyListPersistsNothingButWipesPriorState() throws Exception {
        // Degraded and zero-post slots persist no sections (acceptance item
        // 2), but the replace must STILL wipe prior state — a slot that
        // re-runs degraded after a sectioned render must not leave the old
        // sections behind to mislead a later replay.
        DigestSectionRepository sections = newRepo();
        sections.replaceSlotSections(GROUP, WINDOW_START, List.of(
                new RenderedSection("security", "A")), Instant.now());
        sections.replaceSlotSections(GROUP, WINDOW_START, List.of(), Instant.now());

        assertTrue(sections.findOrderedSections(GROUP, WINDOW_START).isEmpty(),
                "a degraded/zero-post replace leaves no sections behind");
    }

    @Test
    void pruneExpiredForGroup_dropsOnlyExpiredSlotsSections() throws Exception {
        // Acceptance item 9: prune reads `now` from the injected Clock the
        // caller passes (never SQL now()), and joins summary_cache so a
        // slot's sections live exactly as long as its cache row. Seeding
        // one expired + one live cache row, only the expired slot's
        // sections drop.
        seedGroup(GROUP);
        seedGroup(OTHER_GROUP);
        seedCacheRow(GROUP, WINDOW_START, EXPIRED);
        seedCacheRow(OTHER_GROUP, WINDOW_START, LIVE);
        // Pass an EARLY now during the seed replace so the internal prune
        // does not drop the sections before the test's explicit prune call.
        Instant seedNow = Instant.parse("2026-07-21T05:00:00Z");
        DigestSectionRepository sections = newRepo();
        sections.replaceSlotSections(GROUP, WINDOW_START, List.of(
                new RenderedSection("security", "expired-slot section")), seedNow);
        sections.replaceSlotSections(OTHER_GROUP, WINDOW_START, List.of(
                new RenderedSection("security", "live-slot section")), seedNow);

        // now is AFTER GROUP's cache expires_at but BEFORE OTHER_GROUP's.
        Instant now = Instant.parse("2026-07-21T07:30:00Z");
        int deleted = sections.pruneExpiredForGroup(GROUP, now);
        // The prune is per-group, so only GROUP's expired-slot section drops.
        assertEquals(1, deleted,
                "one section row (the expired slot's) was pruned");
        assertTrue(sections.findOrderedSections(GROUP, WINDOW_START).isEmpty(),
                "the expired slot's sections are gone");
        assertEquals(1, sections.findOrderedSections(OTHER_GROUP, WINDOW_START).size(),
                "the live slot's sections in a different group are untouched");
    }

    // -- helpers ----------------------------------------------------------------

    private DigestSectionRepository newRepo() {
        DigestSectionRepository repo = new DigestSectionRepository();
        repo.dataSource = dataSource;
        return repo;
    }

    private DigestCategoryDeliveryRepository newDeliveryRepo() {
        DigestCategoryDeliveryRepository repo = new DigestCategoryDeliveryRepository();
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
