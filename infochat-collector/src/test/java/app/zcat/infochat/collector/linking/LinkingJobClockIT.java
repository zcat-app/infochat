package app.zcat.infochat.collector.linking;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the injected {@link Clock} to a FIXED instant and asserts the
 * {@link LinkingJob} driving-set scan window
 * ({@link LinkingJob#enumerateDriving}) decides against that instant — not the
 * wall-clock run date. (M1-452)
 *
 * <p>This closes the date-boundary time-bomb gap the M1-447 audit missed for
 * {@code LinkingJob}: before the conversion the four window cutoffs read
 * {@code Instant.now()}, so a post sitting on a window boundary aged out on a
 * date boundary and no test could pin it. The driving-set floor is the cleanest
 * of the four to exercise deterministically — and the four cutoffs all derive
 * from the single instant {@code onTick()} samples, so pinning the floor
 * demonstrates the whole job is decided against the injected Clock.
 *
 * <p>Two otherwise-eligible READY posts straddle the driving-set floor
 * ({@code PINNED_NOW − lookback-days}): one sitting exactly on it (must be
 * enumerated, since {@code fetched_at >= cutoff} is inclusive) and one a day
 * below it (must NOT be). With the wall-clock run date well past {@code
 * PINNED_NOW}, the on-boundary post only enumerates if the cutoff reads the
 * injected instant rather than the wall clock.
 */
@QuarkusTest
class LinkingJobClockIT {

    // A FIXED instant the driving-set scan reads via the injected Clock (pinned
    // in pinClock()). 2026-06-20 lands the lookback floor inside the seeded
    // June partition (post_202606) while staying earlier than any plausible
    // wall-clock run date, so the boundary discriminates injected-vs-wall clock.
    private static final Instant PINNED_NOW = Instant.parse("2026-06-20T12:00:00Z");
    private static final String UID_PREFIX = "linking-clock-it/";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    LinkingJob linkingJob;

    @ConfigProperty(name = "infochat.linking.lookback-days")
    int lookbackDays;

    @BeforeEach
    void pinClock() throws Exception {
        // Same QuarkusMock seam ThrottledAdminNotifier's Clock producer
        // documents (M1-444); pins the instant enumerateDriving reads.
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
        clearItData();
    }

    @Test
    void enumerateDriving_gatesOnInjectedClock() throws Exception {
        Instant cutoff = PINNED_NOW.minus(Duration.ofDays(lookbackDays));
        UUID onBoundaryId = seedReadyPost("on-boundary", cutoff);
        UUID belowFloorId = seedReadyPost("below-floor", cutoff.minus(Duration.ofDays(1)));

        List<UUID> driving = enumerateDrivingIds();

        assertTrue(driving.contains(onBoundaryId),
            "a READY post fetched exactly on the driving-set floor (injected-now − lookback) must be "
                + "enumerated — fetched_at >= cutoff is inclusive and the cutoff reads the injected "
                + "Clock, so the window boundary is pinnable");
        assertFalse(driving.contains(belowFloorId),
            "a READY post fetched a day below the floor must NOT be enumerated — the driving-set "
                + "window floor is decided against the injected instant, not the wall-clock date");
    }

    // ---------- helpers ----------

    private List<UUID> enumerateDrivingIds() throws Exception {
        List<UUID> out = new ArrayList<>();
        for (LinkingJob.DrivingPost d : linkingJob.enumerateDriving(Integer.MAX_VALUE)) {
            out.add(d.id());
        }
        return out;
    }

    private UUID seedReadyPost(String slug, Instant fetchedAt) throws Exception {
        UUID sourceId = seedSource(slug);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body,"
                     + "  fetched_at, status, ready_at,"
                     + "  stage1_done, stage1_flagged, stage2_done, stage2_failed,"
                     + "  tagger_done, tagger_fallback, entity_done, embedding_done, tags, re_eval_attempts"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, 'Linking Clock IT title', 'Linking Clock IT body',"
                     + "  ?, 'READY', ?,"
                     + "  TRUE, FALSE, TRUE, FALSE,"
                     + "  TRUE, FALSE, TRUE, TRUE, '{}', 0"
                     + ") RETURNING id")) {
            ps.setString(1, UID_PREFIX + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "linking-clock-it-upstream-" + slug);
            ps.setTimestamp(4, Timestamp.from(fetchedAt));
            ps.setTimestamp(5, Timestamp.from(fetchedAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID seedSource(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('rss', ?, ?, 'news', '{ai}') "
                     + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            ps.setString(1, "https://linking-clock-it.example/" + slug);
            ps.setString(2, "Linking Clock IT source " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void clearItData() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM post WHERE uid LIKE ?")) {
            ps.setString(1, UID_PREFIX + "%");
            ps.executeUpdate();
        }
    }
}
