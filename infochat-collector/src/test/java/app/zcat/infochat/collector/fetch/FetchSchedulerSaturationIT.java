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
 * <p>Also covers the separate feed-truncation signal (M1-753), which
 * shares this thread-local hand-off but NOT the streak policy: it
 * reports per truncating tick, so a feed oscillating across the cap —
 * the case the streak-gated design silently missed — still produces a
 * durable record. The independence of the two signals is asserted
 * directly, because collapsing them back into one flag is the tempting
 * wrong fix.</p>
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
    private static final String TRUNCATION_KEY_PREFIX = "feed_truncated:";

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
                    + "WHERE notification_key LIKE ? OR notification_key LIKE ?")) {
                ps.setString(1, NOTIFY_KEY_PREFIX + "%");
                ps.setString(2, TRUNCATION_KEY_PREFIX + "%");
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

    @Test
    void truncationNotifiesEvenWhenNoConsecutiveStreakEverForms() throws Exception {
        // THE discriminating case for M1-753's redteam finding. The rejected
        // implementation routed truncation through the streak-gated
        // saturation counter, where any non-saturated tick clears the streak
        // (PaginationSaturationTracker.recordTick). A feed oscillating across
        // the cap therefore never reached the threshold and never notified,
        // while discarding content on the ticks in between. A test that drives
        // only CONSECUTIVE truncating ticks passes against that broken design,
        // so it would not close the finding — this one interleaves a clean
        // tick often enough that no streak of `threshold` ever forms.
        UUID sourceId = seedSource("oscillating");
        FetchScheduler.SourceRow row = newRow(sourceId, "oscillating");
        String truncationKey = TRUNCATION_KEY_PREFIX + sourceId;
        int threshold = saturationTracker.saturationThreshold();

        for (int cycle = 0; cycle < 3; cycle++) {
            mockFetcher.truncateNext.set(true);
            for (int i = 0; i < threshold - 1; i++) {
                fetchScheduler.tickOnce(row);
            }
            mockFetcher.truncateNext.set(false);
            fetchScheduler.tickOnce(row);
        }

        Optional<AdminNotificationRecord> record =
            throttledAdminNotifier.getState(truncationKey);
        assertTrue(record.isPresent(),
            "a truncating tick must notify on its own, not on a consecutive "
                + "streak — this sequence never forms a streak of " + threshold
                + ", which is exactly the case the streak-gated design missed");
        assertEquals("feed_truncated", record.get().errorClass(),
            "the notification's error_class must identify the truncation path, "
                + "distinct from fetch_saturation");
    }

    @Test
    void noTruncationNotificationWhenNoTickTruncates() throws Exception {
        // Negative control: without this, the oscillation test above could
        // pass on a implementation that notifies unconditionally.
        UUID sourceId = seedSource("notrunc");
        FetchScheduler.SourceRow row = newRow(sourceId, "notrunc");

        mockFetcher.truncateNext.set(false);
        for (int i = 0; i < 3; i++) {
            fetchScheduler.tickOnce(row);
        }

        assertTrue(throttledAdminNotifier.getState(TRUNCATION_KEY_PREFIX + sourceId).isEmpty(),
            "a source that never truncates must never produce a truncation record");
    }

    @Test
    void truncationAndPaginationSaturationAreIndependentSignals() throws Exception {
        // Regression guard for the forbidden fix. The tempting way to close
        // the oscillation finding is to stop recordTick clearing the streak,
        // which would redefine pagination saturation for Bluesky and Reddit.
        // The two signals must stay separate: truncation must not touch the
        // saturation counter, and saturation must not emit a truncation
        // record.
        UUID sourceId = seedSource("independent");
        FetchScheduler.SourceRow row = newRow(sourceId, "independent");
        String truncationKey = TRUNCATION_KEY_PREFIX + sourceId;
        String saturationKey = NOTIFY_KEY_PREFIX + sourceId;
        int threshold = saturationTracker.saturationThreshold();

        // Truncation alone: notifies on the truncation key, and leaves the
        // pagination cap-hit counter untouched.
        mockFetcher.truncateNext.set(true);
        fetchScheduler.tickOnce(row);
        mockFetcher.truncateNext.set(false);

        assertTrue(throttledAdminNotifier.getState(truncationKey).isPresent(),
            "the truncating tick must emit its own record");
        assertEquals(0L, saturationTracker.capHitCount(sourceId),
            "truncation must NOT increment the pagination cap-hit counter — "
                + "they are different conditions with different policies");
        assertTrue(throttledAdminNotifier.getState(saturationKey).isEmpty(),
            "truncation must not fire the pagination-saturation notification");

        long truncationCountAfterTruncating =
            throttledAdminNotifier.getState(truncationKey).get().notificationCount();

        // Saturation alone: fires the saturation transition without adding
        // any further truncation record.
        mockFetcher.hitCapNext.set(true);
        for (int i = 0; i < threshold; i++) {
            fetchScheduler.tickOnce(row);
        }
        mockFetcher.hitCapNext.set(false);

        assertTrue(throttledAdminNotifier.getState(saturationKey).isPresent(),
            "pagination saturation must still fire on its threshold tick — "
                + "its streak semantics are unchanged by M1-753");
        assertEquals(truncationCountAfterTruncating,
            throttledAdminNotifier.getState(truncationKey).get().notificationCount(),
            "a cap-hit tick that did not truncate must not emit a truncation record");
    }

    @Test
    void capHitFlagDoesNotLeakToTheNextSourceWhenFetchThrows() throws Exception {
        // M1-757 regression guard. A fetcher that raises the cap-hit
        // signal and then throws leaves the static ThreadLocal set on
        // the dispatch thread; the scheduler must drain it on the
        // failure path, or the NEXT source ticked on that thread
        // inherits the signal. The leak is observable only on the
        // second source — the first source behaves identically in both
        // designs, so a test asserting only on it could not detect it.
        UUID firstSourceId = seedSource("leak-first");
        UUID secondSourceId = seedSource("leak-second");
        FetchScheduler.SourceRow firstRow = newRow(firstSourceId, "leak-first");
        FetchScheduler.SourceRow secondRow = newRow(secondSourceId, "leak-second");
        String secondNotificationKey = NOTIFY_KEY_PREFIX + secondSourceId;

        // Tick 1: a source whose fetcher signals a cap hit and then
        // throws — the exact window the failure path must drain.
        mockFetcher.throwAfterCapHitNext.set(true);
        fetchScheduler.tickOnce(firstRow);
        mockFetcher.throwAfterCapHitNext.set(false);

        // Tick 2: a DIFFERENT source whose fetcher signals nothing.
        // The second source's cap-hit counter is the leak detector: an
        // undrained flag from tick 1 would be read as this source's cap
        // hit and counted against its uuid.
        fetchScheduler.tickOnce(secondRow);

        assertEquals(0L, saturationTracker.capHitCount(secondSourceId),
            "the second source must not inherit the first source's cap-hit signal — "
                + "an undrained flag leaks onto the next tick on the same thread");
        assertTrue(throttledAdminNotifier.getState(secondNotificationKey).isEmpty(),
            "no fetch_saturation notification may name the second source");
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
        final AtomicBoolean truncateNext = new AtomicBoolean(false);
        final AtomicBoolean throwAfterCapHitNext = new AtomicBoolean(false);

        @Override
        public List<NormalizedPost> fetch(long dispatchKey, String identifier) {
            if (hitCapNext.get()) {
                PaginationSaturationTracker.signalCapHit();
            }
            // Independently settable: the two flags are separate signals
            // with separate notification policies, and the tests need to
            // raise either without the other.
            if (truncateNext.get()) {
                PaginationSaturationTracker.signalTruncation();
            }
            // M1-757: raise the cap-hit signal and then throw — the
            // failure window the scheduler's catch block must drain.
            if (throwAfterCapHitNext.get()) {
                PaginationSaturationTracker.signalCapHit();
                throw new RuntimeException("simulated post-raise failure");
            }
            return List.of();
        }
    }
}
