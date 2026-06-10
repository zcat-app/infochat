package app.zcat.infochat.collector.fetch;

import app.zcat.infochat.collector.fetcher.rss.RssFetcher;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.core.notifier.AdminNotificationRecord;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of the D42 fetch failure ladder. Drives
 * {@link FetchScheduler#tickOnce} against a mock {@link RssFetcher}
 * whose pass/fail mode is set per-test and asserts the four
 * scheduler-level invariants:
 * <ul>
 *   <li>N consecutive failures flip {@code status} to
 *       {@code 'failed'} (counter == N).</li>
 *   <li>A {@code 'failed'} source is excluded from
 *       {@link FetchScheduler#enumerateActiveSources}.</li>
 *   <li>A success after sub-threshold failures resets the counter
 *       back to 0.</li>
 *   <li>The throttled admin notification fires once on the
 *       threshold-crossing tick — not on the failures before it.</li>
 * </ul>
 *
 * <p>Threshold value: read from {@code infochat.fetch.failure-threshold}
 * (the test profile sets 3 — see {@code src/test/resources/application.properties}).
 * Tests loop {@link #failureThreshold} times rather than hardcoding 3
 * so the assertions track the configured value.</p>
 *
 * <p>Test isolation: every fixture row carries the
 * {@code m1-094-it-} prefix; {@link #cleanup} deletes only matching
 * rows plus their derived {@code admin_notification_state} entries.</p>
 */
@QuarkusTest
class FetchSchedulerFailureLadderIT {

    private static final String PREFIX = "m1-094-it-";
    private static final String NOTIFY_KEY_PREFIX = "fetch_failure_ladder:";

    @Inject
    FetchScheduler fetchScheduler;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @ConfigProperty(name = "infochat.fetch.failure-threshold")
    int failureThreshold;

    private ControllableRssFetcher mockFetcher;

    @BeforeEach
    void setupAndCleanup() throws Exception {
        mockFetcher = new ControllableRssFetcher();
        // QuarkusMock.installMockForType wires the substitute through
        // the same CDI client proxy the scheduler's @All List<Fetcher>
        // resolves to, so fetchScheduler.tickOnce on a kind='rss' row
        // dispatches into mockFetcher.fetch instead of the production
        // RssFetcher (whose SsrfGuardedHttpClient would refuse the
        // example.com identifiers seeded below). The mock is reset
        // between tests by Quarkus's per-test lifecycle.
        QuarkusMock.installMockForType(mockFetcher, RssFetcher.class,
            new FetcherKind.Literal("rss"));

        try (Connection conn = dataSource.getConnection()) {
            // Drop test-scoped admin_notification_state rows first so
            // the threshold-crossing test starts from a clean slate
            // (the FK / cascade story between source and notifier
            // state is one-way: notifier rows live in their own table
            // keyed by the caller-supplied notification_key).
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
    void consecutiveFailures_transitionsToFailed() throws Exception {
        UUID sourceId = seedSource("transitionToFailed", "active");
        FetchScheduler.SourceRow row = newRow(sourceId, "transitionToFailed");
        mockFetcher.failNext.set(true);

        for (int i = 0; i < failureThreshold; i++) {
            fetchScheduler.tickOnce(row);
        }

        assertEquals("failed", readStatus(sourceId),
            "after N=" + failureThreshold + " consecutive failures the source "
                + "must transition to status='failed'");
        assertEquals(failureThreshold, readConsecutiveFailures(sourceId),
            "consecutive_failures must equal the threshold after the crossing tick");
    }

    @Test
    void failedSourceSkippedByScheduler() throws Exception {
        UUID failedId = seedSource("skippedFailed", "failed");
        UUID activeId = seedSource("skippedActive", "active");

        // The enumerator is the mechanical exclusion path the spec
        // commits to: "the scheduler selects rows where status='active'
        // AND deleted_at IS NULL". Asserting against the enumerator
        // output covers the contract directly.
        List<FetchScheduler.SourceRow> active = fetchScheduler.enumerateActiveSources();
        boolean failedPresent = active.stream().anyMatch(r -> r.uuid().equals(failedId));
        boolean activePresent = active.stream().anyMatch(r -> r.uuid().equals(activeId));
        assertFalse(failedPresent,
            "enumerateActiveSources must NOT include a status='failed' row");
        assertTrue(activePresent,
            "enumerateActiveSources must include the status='active' sibling row "
                + "(sanity-check the filter is not over-broad)");

        // Belt-and-braces: directly invoking tickOnce on a failed row
        // is not a path the scheduler takes (enumeration filters it
        // out), so verify by counting Fetcher invocations across a
        // simulated heartbeat that iterates only the enumerator's
        // output.
        int callsBefore = mockFetcher.callCount.get();
        for (FetchScheduler.SourceRow r : active) {
            if (r.uuid().equals(failedId) || r.uuid().equals(activeId)) {
                fetchScheduler.tickOnce(r);
            }
        }
        int callsAfter = mockFetcher.callCount.get();
        assertEquals(callsBefore + 1, callsAfter,
            "the test Fetcher must be invoked exactly once (active row only); "
                + "the failed row's tick must be skipped by enumeration");
    }

    @Test
    void successResetsCounter() throws Exception {
        UUID sourceId = seedSourceWithFailureCount(
            "successResets", "active", failureThreshold - 1);
        FetchScheduler.SourceRow row = newRow(sourceId, "successResets");

        // Sanity-check the seeded counter is below threshold so the
        // success transition is the observable change.
        assertEquals(failureThreshold - 1, readConsecutiveFailures(sourceId),
            "seed precondition: counter starts at threshold - 1");

        mockFetcher.failNext.set(false);
        fetchScheduler.tickOnce(row);

        assertEquals(0, readConsecutiveFailures(sourceId),
            "a successful tick must reset consecutive_failures to 0");
        assertEquals("active", readStatus(sourceId),
            "a successful tick must NOT flip status");
    }

    @Test
    void thresholdCrossing_firesAdminNotification() throws Exception {
        UUID sourceId = seedSource("notifyOnCrossing", "active");
        FetchScheduler.SourceRow row = newRow(sourceId, "notifyOnCrossing");
        String notificationKey = NOTIFY_KEY_PREFIX + sourceId;
        mockFetcher.failNext.set(true);

        // The first (threshold - 1) failures keep status='active' and
        // must NOT fire a notification — the ladder coalesces on
        // crossing, not on every failure.
        for (int i = 0; i < failureThreshold - 1; i++) {
            fetchScheduler.tickOnce(row);
        }
        Optional<AdminNotificationRecord> preCrossing =
            throttledAdminNotifier.getState(notificationKey);
        assertTrue(preCrossing.isEmpty(),
            "no admin notification row must exist before the threshold-crossing tick "
                + "(observed " + readConsecutiveFailures(sourceId)
                + " consecutive_failures pre-crossing)");

        // The Nth failure: status flips active→failed AND the notifier
        // emits exactly one row keyed on the source UUID.
        fetchScheduler.tickOnce(row);

        Optional<AdminNotificationRecord> postCrossing =
            throttledAdminNotifier.getState(notificationKey);
        assertTrue(postCrossing.isPresent(),
            "the threshold-crossing tick must EMIT a throttled admin notification "
                + "(notification_key=" + notificationKey + ")");
        assertEquals("fetch_failure_ladder", postCrossing.get().errorClass(),
            "the notification's error_class must identify the ladder for operator-side "
                + "grep/coalesce on the ADMIN-NOTIFY scrape");
        assertEquals(1L, postCrossing.get().notificationCount(),
            "notification_count must be exactly 1 — the crossing fires once, not per failure");

        // Belt-and-braces: a further failure after the crossing must
        // NOT emit a fresh notification (it goes to SUPPRESSED inside
        // the throttle window). Suppressed_count should bump but
        // notification_count stays at 1.
        fetchScheduler.tickOnce(row);
        Optional<AdminNotificationRecord> postSuppressed =
            throttledAdminNotifier.getState(notificationKey);
        assertTrue(postSuppressed.isPresent(),
            "the notifier row must still exist after a post-crossing failure");
        // The post-crossing failure increments the counter but does
        // NOT cross the threshold (status was already 'failed'), so
        // recordFailure returns crossedThreshold=false and notifyOnce
        // is never called for this tick. notification_count therefore
        // stays at 1; suppressed_count does NOT bump from this path
        // (the ladder simply does not call the notifier when status
        // was already failed).
        assertEquals(1L, postSuppressed.get().notificationCount(),
            "post-crossing failures must not bump notification_count "
                + "(the ladder calls notifyOnce only on the crossing tick)");

        // And the schedule-level effect of the post-crossing failure:
        // counter still monotonic, status still 'failed'.
        assertEquals("failed", readStatus(sourceId),
            "status must remain 'failed' after a post-crossing failure");
        assertNotEquals(failureThreshold, readConsecutiveFailures(sourceId),
            "post-crossing failure must increment the counter beyond the threshold");
    }

    // ----- helpers ---------------------------------------------------------

    private UUID seedSource(String slug, String status) throws Exception {
        return seedSourceWithFailureCount(slug, status, 0);
    }

    private UUID seedSourceWithFailureCount(String slug, String status, int failureCount)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, "
                     + "  bootstrap_tags, status, consecutive_failures) "
                     + "VALUES ('rss', ?, ?, 'news', '{}', ?, ?) RETURNING id")) {
            ps.setString(1, "https://example.com/" + PREFIX + slug);
            ps.setString(2, PREFIX + slug + "-name");
            ps.setString(3, status);
            ps.setInt(4, failureCount);
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

    private String readStatus(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private int readConsecutiveFailures(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT consecutive_failures FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Test-only {@link RssFetcher} subclass that throws on demand
     * ({@code failNext = true}) or returns an empty post list
     * ({@code failNext = false}). Per-test mode is set in the test
     * body before calling {@link FetchScheduler#tickOnce}; the empty
     * success path is sufficient because no acceptance item depends
     * on a non-empty post list (M1-094 owns the failure ladder, not
     * the persist/enqueue path).
     */
    private static final class ControllableRssFetcher extends RssFetcher {
        final AtomicBoolean failNext = new AtomicBoolean(false);
        final AtomicInteger callCount = new AtomicInteger();

        @Override
        public List<NormalizedPost> fetch(long dispatchKey, String identifier) {
            callCount.incrementAndGet();
            if (failNext.get()) {
                throw new RuntimeException(
                    "test-controlled fetch failure for source " + dispatchKey);
            }
            return List.of();
        }
    }
}
