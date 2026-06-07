package app.zcat.infochat.collector.fetch;

import app.zcat.infochat.collector.fetcher.PaginationSaturationTracker;
import app.zcat.infochat.collector.fetcher.rss.RssFetcher;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.core.notifier.AdminNotificationRecord;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of the spec §Ingest SPIs pagination-cap
 * saturation counter. Drives {@link FetchScheduler#tickOnce} against
 * a mock {@link RssFetcher} that raises the
 * {@link PaginationSaturationTracker#signalCapHit()} thread-local
 * signal on demand, and asserts:
 * <ul>
 *   <li>a cap-hit tick increments the per-source counter;</li>
 *   <li>crossing the consecutive-tick threshold fires exactly one
 *       throttled admin notification, keyed on the source UUID (the
 *       key — and the message it accompanies — names the source);</li>
 *   <li>sustained saturation past the threshold does not re-fire
 *       (once per saturation transition);</li>
 *   <li>a non-saturated tick resets the streak.</li>
 * </ul>
 *
 * <p>Test isolation mirrors {@link FetchSchedulerFailureLadderIT}:
 * fixture rows carry the {@code m1-216-it-} prefix; every test seeds
 * its own fresh source UUID so the tracker's in-memory per-source
 * state cannot leak across tests.</p>
 */
@QuarkusTest
class FetchSchedulerSaturationIT {

    private static final String PREFIX = "m1-216-it-";
    private static final String NOTIFY_KEY_PREFIX = "fetch_saturation:";

    @Inject
    FetchScheduler fetchScheduler;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @Inject
    PaginationSaturationTracker saturationTracker;

    private CapHittingRssFetcher mockFetcher;

    @BeforeEach
    void setupAndCleanup() throws Exception {
        mockFetcher = new CapHittingRssFetcher();
        QuarkusMock.installMockForType(mockFetcher, RssFetcher.class,
            new FetcherKind.Literal("rss"));

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM admin_notification_state "
                    + "WHERE notification_key LIKE ?")) {
                ps.setString(1, NOTIFY_KEY_PREFIX + "%");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM source WHERE identifier LIKE ?")) {
                ps.setString(1, "https://example.com/" + PREFIX + "%");
                ps.executeUpdate();
            }
        }
    }

    @Test
    void capHitTickIncrementsPerSourceCounter() throws Exception {
        UUID sourceId = seedSource("counter");
        FetchScheduler.SourceRow row = newRow(sourceId, "counter");

        assertEquals(0L, saturationTracker.capHitCount(sourceId),
            "fresh source must start with a zero cap-hit count");

        mockFetcher.hitCapNext.set(true);
        fetchScheduler.tickOnce(row);
        assertEquals(1L, saturationTracker.capHitCount(sourceId),
            "a cap-hit tick must increment the per-source counter");

        mockFetcher.hitCapNext.set(false);
        fetchScheduler.tickOnce(row);
        assertEquals(1L, saturationTracker.capHitCount(sourceId),
            "a non-cap-hit tick must NOT increment the counter");
    }

    @Test
    void thresholdCrossingFiresExactlyOneNotificationNamingTheSource() throws Exception {
        UUID sourceId = seedSource("crossing");
        FetchScheduler.SourceRow row = newRow(sourceId, "crossing");
        String notificationKey = NOTIFY_KEY_PREFIX + sourceId;
        int threshold = saturationTracker.saturationThreshold();
        mockFetcher.hitCapNext.set(true);

        // The first (threshold - 1) saturated ticks must NOT fire —
        // the notification coalesces on the transition, not on every
        // cap hit.
        for (int i = 0; i < threshold - 1; i++) {
            fetchScheduler.tickOnce(row);
        }
        assertTrue(throttledAdminNotifier.getState(notificationKey).isEmpty(),
            "no notification row may exist before the threshold-crossing tick");

        // The Nth consecutive saturated tick: the throttled
        // notification fires, keyed on the source UUID — the key (and
        // the message it accompanies) names the saturating source.
        fetchScheduler.tickOnce(row);
        Optional<AdminNotificationRecord> postCrossing =
            throttledAdminNotifier.getState(notificationKey);
        assertTrue(postCrossing.isPresent(),
            "the threshold-crossing tick must emit a throttled admin notification "
                + "keyed on the source UUID (key=" + notificationKey + ")");
        assertEquals("fetch_saturation", postCrossing.get().errorClass(),
            "the notification's error_class must identify the saturation path");
        assertEquals(1L, postCrossing.get().notificationCount(),
            "exactly one notification — fired on the crossing, not per cap hit");

        // Sustained saturation past the threshold must NOT re-fire:
        // recordTick returns the transition exactly once per streak.
        fetchScheduler.tickOnce(row);
        assertEquals(1L,
            throttledAdminNotifier.getState(notificationKey).get().notificationCount(),
            "sustained saturation must not re-fire (once per saturation transition)");
    }

    @Test
    void nonSaturatedTickResetsTheStreak() throws Exception {
        UUID sourceId = seedSource("reset");
        FetchScheduler.SourceRow row = newRow(sourceId, "reset");
        String notificationKey = NOTIFY_KEY_PREFIX + sourceId;
        int threshold = saturationTracker.saturationThreshold();

        // (threshold - 1) saturated ticks, then a clean tick: the
        // streak resets, so the next (threshold - 1) saturated ticks
        // still must not fire.
        mockFetcher.hitCapNext.set(true);
        for (int i = 0; i < threshold - 1; i++) {
            fetchScheduler.tickOnce(row);
        }
        mockFetcher.hitCapNext.set(false);
        fetchScheduler.tickOnce(row);
        mockFetcher.hitCapNext.set(true);
        for (int i = 0; i < threshold - 1; i++) {
            fetchScheduler.tickOnce(row);
        }
        assertTrue(throttledAdminNotifier.getState(notificationKey).isEmpty(),
            "a non-saturated tick must reset the streak — "
                + (threshold - 1) + " saturated ticks after a reset may not fire");

        // One more saturated tick completes a full streak — a NEW
        // transition, which fires.
        fetchScheduler.tickOnce(row);
        assertTrue(throttledAdminNotifier.getState(notificationKey).isPresent(),
            "a full consecutive streak after the reset is a new saturation "
                + "transition and must fire");
    }

    // ----- helpers ---------------------------------------------------------

    private UUID seedSource(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, "
                     + "  bootstrap_tags, status) "
                     + "VALUES ('rss', ?, ?, 'news', '{}', 'active') RETURNING id")) {
            ps.setString(1, "https://example.com/" + PREFIX + slug);
            ps.setString(2, PREFIX + slug + "-name");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private FetchScheduler.SourceRow newRow(UUID id, String slug) {
        return new FetchScheduler.SourceRow(
            id, "https://example.com/" + PREFIX + slug, 1L, "rss");
    }

    /**
     * Test-only {@link RssFetcher} subclass that raises the
     * pagination-cap thread-local signal on demand
     * ({@code hitCapNext = true}) — the same call a paginating
     * production Fetcher makes when its page loop exhausts the cap
     * with a cursor still outstanding. The signal is fetcher-agnostic,
     * so an RSS-kind mock exercises the scheduler-side contract
     * without HTTP stubbing.
     */
    private static final class CapHittingRssFetcher extends RssFetcher {
        final AtomicBoolean hitCapNext = new AtomicBoolean(false);

        @Override
        public List<NormalizedPost> fetch(long sourceId, String identifier) {
            if (hitCapNext.get()) {
                PaginationSaturationTracker.signalCapHit();
            }
            return List.of();
        }
    }
}
