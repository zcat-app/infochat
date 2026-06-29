package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.collector.testsupport.SeedDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the reconnect since-cursor semantics on
 * {@link NostrStreamSource.Registrar#latestPublishedAtEpochSeconds}
 * after the fetched_at scan bound: a source with recent posts reads its
 * cursor from the partition-pruned window, and a stale source — no post
 * inside the window — falls back to the unbounded scan so the cursor is
 * identical to the pre-bound form (never silently empty, which would
 * make the relay replay its whole default window).
 *
 * <p>Fixture convention from ReEvaluationJobWindowTest: below-floor rows
 * sit in the oldest bootstrap partition (May 2026), always older than
 * the ~32-day retention+slack floor; in-window rows are fetched at
 * {@code now()} (current-month partition, provisioned at startup).
 */
@QuarkusTest
class NostrSinceCursorIT {

    private static final Instant BELOW_FLOOR_FETCHED_AT = Instant.parse("2026-05-01T00:00:00Z");

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    NostrStreamSource.Registrar registrar;

    @ConfigProperty(name = "infochat.partitions.retention-days.post")
    int postRetentionDays;

    @Test
    void recentSourceCursorIsItsMaxPublishedAt() throws Exception {
        UUID sourceUuid = seedNostrSource("nostr-cursor-test/recent");
        Instant fetchedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant olderPublished = fetchedAt.minusSeconds(7200);
        Instant newerPublished = fetchedAt.minusSeconds(3600);
        seedPost(sourceUuid, "cursor-recent-older", fetchedAt, olderPublished);
        seedPost(sourceUuid, "cursor-recent-newer", fetchedAt, newerPublished);

        OptionalLong cursor = registrar.latestPublishedAtEpochSeconds(sourceUuid);

        assertEquals(OptionalLong.of(newerPublished.getEpochSecond()), cursor,
            "a source with in-window posts reads MAX(published_at) as before");
    }

    @Test
    void staleSourceFallsBackToUnboundedScan() throws Exception {
        assertTrue(BELOW_FLOOR_FETCHED_AT.isBefore(
                Instant.now().minusSeconds((postRetentionDays + 2L) * 86400)),
            "test fixture invalid: BELOW_FLOOR_FETCHED_AT is inside the scan window");
        UUID sourceUuid = seedNostrSource("nostr-cursor-test/stale");
        Instant stalePublished = Instant.parse("2026-04-30T12:00:00Z");
        seedPost(sourceUuid, "cursor-stale", BELOW_FLOOR_FETCHED_AT, stalePublished);

        OptionalLong cursor = registrar.latestPublishedAtEpochSeconds(sourceUuid);

        assertEquals(OptionalLong.of(stalePublished.getEpochSecond()), cursor,
            "a stale source (no post inside the scan window) must fall back to the "
                + "unbounded scan — the cursor semantics are intact, not silently empty");
    }

    @Test
    void sourceWithNoPostsHasNoCursor() throws Exception {
        UUID sourceUuid = seedNostrSource("nostr-cursor-test/empty");

        assertEquals(OptionalLong.empty(), registrar.latestPublishedAtEpochSeconds(sourceUuid),
            "no posts at all -> no cursor; the reconnect omits `since`");
    }

    // ---------- helpers ----------

    private UUID seedNostrSource(String identifier) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('nostr', ?, 'Since cursor test source', 'social', '{}') "
                     + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            ps.setString(1, identifier);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void seedPost(UUID sourceUuid, String slug, Instant fetchedAt, Instant publishedAt)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body,"
                     + "  fetched_at, published_at, status,"
                     + "  stage1_done, stage1_flagged, stage2_done, stage2_failed,"
                     + "  tagger_done, tagger_fallback, embedding_done, tags, re_eval_attempts"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, ?, 'body',"
                     + "  ?, ?, 'READY',"
                     + "  TRUE, FALSE, TRUE, FALSE,"
                     + "  TRUE, FALSE, TRUE, '{}', 0"
                     + ")")) {
            ps.setString(1, "nostr-cursor-" + slug);
            ps.setObject(2, sourceUuid);
            ps.setString(3, "upstream-" + slug);
            ps.setString(4, "Cursor " + slug);
            ps.setTimestamp(5, Timestamp.from(fetchedAt));
            ps.setTimestamp(6, Timestamp.from(publishedAt));
            ps.executeUpdate();
        }
    }
}
