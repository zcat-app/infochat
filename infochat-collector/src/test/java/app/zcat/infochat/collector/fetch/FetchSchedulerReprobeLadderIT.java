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
import org.junit.jupiter.api.AfterEach;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of the D42 re-probe ladder (M1-754) under a
 * mutable injected {@link Clock}: park via the REAL
 * {@code SourceRepository.recordFailure} writer, schedule
 * initialization at first-delay, exponential backoff with ceiling on
 * failed probes, restore semantics on the first success (status,
 * counter, reason, RECOVERED notifyOnce keyed on the source UUID, the
 * same-transaction audit row), the cap counter surviving the restore,
 * and the separate-path property (a parked row never rides the active
 * enumeration).
 */
@QuarkusTest
class FetchSchedulerReprobeLadderIT {

    private static final String PREFIX = "m1-754-ladder-";
    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

    @Inject
    ReprobeScheduler reprobeScheduler;

    @Inject
    FetchScheduler fetchScheduler;

    @Inject
    SourceRepository sourceRepository;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @ConfigProperty(name = "infochat.fetch.failure-threshold")
    int failureThreshold;

    @ConfigProperty(name = "infochat.fetch.reprobe.first-delay")
    Duration firstDelay;

    @ConfigProperty(name = "infochat.fetch.reprobe.backoff-factor")
    double backoffFactor;

    @ConfigProperty(name = "infochat.fetch.reprobe.backoff-ceiling")
    Duration backoffCeiling;

    @ConfigProperty(name = "infochat.fetch.reprobe.cap")
    int reprobeCap;

    private MutableClock clock;
    private ControllableRssFetcher mockFetcher;

    @BeforeEach
    void setup() throws Exception {
        clock = new MutableClock(T0);
        mockFetcher = new ControllableRssFetcher();
        QuarkusMock.installMockForType(clock, Clock.class);
        QuarkusMock.installMockForType(mockFetcher, RssFetcher.class,
            new FetcherKind.Literal("rss"));
        deleteFixtures();
    }

    /**
     * Delete this class's rows AFTER each test as well as before.
     * {@link #reprobePathIsSeparateFromActiveEnumeration} deliberately
     * seeds a park whose probe slot is already due and never sweeps it,
     * so without this the row outlives the class and the next test
     * class's sweep probes it — inflating the exact-probe-count
     * assertions in {@code ReprobeSelectionGuardIT} and
     * {@code CycleCapReprobeExclusionIT}. A {@code @BeforeEach}-only
     * cleanup leaves that outcome riding on which method JUnit happens
     * to run last.
     */
    @AfterEach
    void cleanupFixtures() throws Exception {
        deleteFixtures();
    }

    private void deleteFixtures() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM admin_notification_state WHERE notification_key LIKE ?")) {
                ps.setString(1, ReprobeScheduler.ERROR_CLASS_REPROBE_RECOVERED + ":%");
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
    void parkedSourceIsProbedAfterFirstDelayAndRestoredOnSuccess() throws Exception {
        // Park via the REAL ladder writer so the recorded reason is the
        // production one, not a fixture artifact.
        UUID sourceId = seedActiveSource("restore", failureThreshold - 1);
        sourceRepository.recordFailure(sourceId, failureThreshold);
        assertEquals("failed", readText(sourceId, "status"),
            "precondition: the ladder parks the row");
        assertEquals("fetch-failure", readText(sourceId, "park_reason"),
            "precondition: the ladder records its reason");

        // First sweep initializes the schedule off the INJECTED clock —
        // no probe yet (first-delay has not elapsed).
        reprobeScheduler.runOnce();
        assertEquals(0, mockFetcher.callCount.get(),
            "no probe may fire before first-delay elapses");
        assertEquals(T0.plus(firstDelay), readInstant(sourceId, "next_reprobe_at"),
            "the first probe slot must be first-delay after the sweep's clock "
                + "instant — the injected Clock, not the DB clock");

        // Not due yet: a sweep 1 second early must not probe.
        clock.set(T0.plus(firstDelay).minusSeconds(1));
        reprobeScheduler.runOnce();
        assertEquals(0, mockFetcher.callCount.get(),
            "a sweep before the slot must not probe");

        // Due: the probe fires, succeeds, and restores.
        Instant probeTime = T0.plus(firstDelay).plusSeconds(1);
        clock.set(probeTime);
        mockFetcher.failNext.set(false);
        reprobeScheduler.runOnce();

        assertEquals(1, mockFetcher.callCount.get(), "the due sweep must probe exactly once");
        assertEquals("active", readText(sourceId, "status"),
            "the first successful re-probe must restore status='active'");
        assertEquals(0, readInt(sourceId, "consecutive_failures"),
            "the restore must zero consecutive_failures");
        assertNull(readText(sourceId, "park_reason"), "the restore must clear the park reason");
        assertNull(readText(sourceId, "parked_at"), "the restore must clear parked_at");
        assertNull(readText(sourceId, "next_reprobe_at"),
            "the restore must clear the probe schedule");
        assertEquals(1, readInt(sourceId, "reprobe_count"),
            "the restore must NOT clear the absolute cap counter — that is gated "
                + "on the sustained-success window");
        assertEquals(probeTime, readInstant(sourceId, "reprobe_restored_at"),
            "the restore must stamp the sustained-success anchor from the injected Clock");

        Optional<AdminNotificationRecord> recovered = throttledAdminNotifier.getState(
            ReprobeScheduler.ERROR_CLASS_REPROBE_RECOVERED + ":" + sourceId);
        assertTrue(recovered.isPresent(),
            "the restore must fire a RECOVERED notifyOnce keyed on the source UUID");
        assertEquals(ReprobeScheduler.ERROR_CLASS_REPROBE_RECOVERED,
            recovered.get().errorClass(), "error_class must identify the re-probe ladder");

        assertEquals(1, countRestoreAudit(sourceId),
            "the restore must write exactly one SOURCE_REPROBE_RESTORED audit row "
                + "in the same transaction as the UPDATE");
    }

    @Test
    void failedProbesBackOffExponentiallyUpToCeiling() throws Exception {
        UUID sourceId = seedParked("backoff");
        reprobeScheduler.runOnce();
        assertEquals(T0.plus(firstDelay), readInstant(sourceId, "next_reprobe_at"),
            "initialization must schedule the first probe at first-delay");

        mockFetcher.failNext.set(true);
        Instant now = T0;
        for (int attempt = 1; attempt <= 5; attempt++) {
            now = readInstant(sourceId, "next_reprobe_at").plusSeconds(1);
            clock.set(now);
            int callsBefore = mockFetcher.callCount.get();
            reprobeScheduler.runOnce();

            assertEquals(callsBefore + 1, mockFetcher.callCount.get(),
                "attempt " + attempt + ": the due sweep must probe exactly once");
            assertEquals("failed", readText(sourceId, "status"),
                "attempt " + attempt + ": a failed probe must leave the park standing");
            assertEquals("fetch-failure", readText(sourceId, "park_reason"),
                "attempt " + attempt + ": a failed probe must not touch the reason");
            assertEquals(attempt, readInt(sourceId, "reprobe_count"),
                "attempt " + attempt + ": each probe must consume one budget unit");

            long expectedMillis = Math.min(
                (long) (firstDelay.toMillis() * Math.pow(backoffFactor, attempt)),
                backoffCeiling.toMillis());
            assertEquals(now.plusMillis(expectedMillis),
                readInstant(sourceId, "next_reprobe_at"),
                "attempt " + attempt + ": the next slot must follow "
                    + "min(first-delay * factor^k, ceiling) off the injected Clock");

            // A second sweep at the same instant must not double-probe —
            // the slot just moved into the future.
            reprobeScheduler.runOnce();
            assertEquals(callsBefore + 1, mockFetcher.callCount.get(),
                "attempt " + attempt + ": a sweep before the new slot must not probe");
        }
        // With 6h * 2^k against a 4d ceiling, attempts 4 and 5 both hit
        // the ceiling — the loop's formula assertions above have pinned
        // the cap twice by now; this line just documents the intent.
        assertTrue(firstDelay.toMillis() * Math.pow(backoffFactor, 5) > backoffCeiling.toMillis(),
            "config sanity: the loop must actually have exercised the ceiling");
    }

    @Test
    void reprobePathIsSeparateFromActiveEnumeration() throws Exception {
        UUID parkedDue = seedParked("separatePath");
        setNextReprobeAt(parkedDue, T0.minusSeconds(60));

        List<FetchScheduler.SourceRow> active = fetchScheduler.enumerateActiveSources();
        assertFalse(active.stream().anyMatch(r -> r.uuid().equals(parkedDue)),
            "a parked row must never ride the active fetch enumeration — the "
                + "re-probe ladder is a separate scheduling path");
        assertTrue(sourceRepository.selectDueReprobes(T0, reprobeCap).stream()
                .anyMatch(c -> c.uuid().equals(parkedDue)),
            "the same row must be visible to the re-probe selection (sanity: the "
                + "separation is two paths, not a dropped row)");
    }

    // ----- helpers ---------------------------------------------------------

    private UUID seedActiveSource(String slug, int failureCount) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, "
                     + "  bootstrap_tags, status, consecutive_failures) "
                     + "VALUES ('rss', ?, ?, 'news', '{}', 'active', ?) RETURNING id")) {
            ps.setString(1, "https://example.com/" + PREFIX + slug);
            ps.setString(2, PREFIX + slug + "-name");
            ps.setInt(3, failureCount);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID seedParked(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, "
                     + "  bootstrap_tags, status, park_reason, parked_at, "
                     + "  consecutive_failures) "
                     + "VALUES ('rss', ?, ?, 'news', '{}', 'failed', 'fetch-failure', "
                     + "  now(), 3) RETURNING id")) {
            ps.setString(1, "https://example.com/" + PREFIX + slug);
            ps.setString(2, PREFIX + slug + "-name");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void setNextReprobeAt(UUID sourceId, Instant when) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE source SET next_reprobe_at = ? WHERE id = ?")) {
            ps.setTimestamp(1, Timestamp.from(when));
            ps.setObject(2, sourceId);
            ps.executeUpdate();
        }
    }

    private String readText(UUID sourceId, String column) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT " + column + "::TEXT FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private Instant readInstant(UUID sourceId, String column) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT " + column + " FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Timestamp ts = rs.getTimestamp(1);
                return ts != null ? ts.toInstant() : null;
            }
        }
    }

    private int readInt(UUID sourceId, String column) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT " + column + " FROM source WHERE id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int countRestoreAudit(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT count(*) FROM audit_log "
                     + "WHERE action = 'SOURCE_REPROBE_RESTORED' AND target_id = ?")) {
            ps.setString(1, sourceId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Settable test clock — the fixed-Clock idiom, but advanceable mid-test. */
    static final class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void set(Instant now) {
            this.instant = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /** Pass/fail-switchable probe returning an empty batch on success. */
    static final class ControllableRssFetcher extends RssFetcher {
        final AtomicBoolean failNext = new AtomicBoolean(false);
        final AtomicInteger callCount = new AtomicInteger();

        @Override
        public List<NormalizedPost> fetch(long dispatchKey, String identifier) {
            callCount.incrementAndGet();
            if (failNext.get()) {
                throw new RuntimeException("test-controlled probe failure");
            }
            return List.of();
        }
    }
}
