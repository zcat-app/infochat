package app.zcat.infochat.collector.notifier;

import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @QuarkusTest exercising {@link ThrottledAdminNotifier} against a
 * real Postgres via Quarkus dev-services. Covers the four
 * spec-pinned scenarios:
 * <ol>
 *   <li>first call emits + persists</li>
 *   <li>within-window calls suppress + bump suppressed_count</li>
 *   <li>after-window call emits again</li>
 *   <li>concurrent calls for the same key produce exactly one
 *       EMITTED and N-1 SUPPRESSED (UPSERT race-safety)</li>
 * </ol>
 *
 * <p>Clock seam: {@link MutableClock} is installed via
 * {@link QuarkusMock#installMockForType} in {@link #setUp}; tests
 * advance simulated time by mutating the same instance, so the
 * within-window vs after-window branch is exercised without sleeping
 * real time. {@code @Inject Clock} below documents that the test
 * resolves the SAME CDI bean the notifier resolves — both see the
 * MutableClock after installation.</p>
 *
 * <p>Log capture follows the {@code InstanceLockGuardIT} (M1-009)
 * precedent: an inner {@link CapturingHandler} attached to the JUL
 * logger for {@link ThrottledAdminNotifier} records every WARN line
 * emitted during the test, so assertions can verify both the
 * presence of {@code ADMIN-NOTIFY} on emit calls and the absence of
 * a second line on suppressed calls.</p>
 */
@QuarkusTest
class ThrottledAdminNotifierTest {

    private static final Instant T0 = Instant.parse("2026-05-24T12:00:00Z");

    @Inject
    ThrottledAdminNotifier notifier;

    @Inject
    DataSource dataSource;

    @Inject
    Clock clock;

    private MutableClock mutableClock;
    private CapturingHandler logCapture;
    private Logger notifierJulLogger;

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("TRUNCATE admin_notification_state");
        }
        mutableClock = new MutableClock(T0);
        QuarkusMock.installMockForType(mutableClock, Clock.class);

        logCapture = new CapturingHandler();
        notifierJulLogger = Logger.getLogger(ThrottledAdminNotifier.class.getName());
        notifierJulLogger.addHandler(logCapture);
    }

    @AfterEach
    void tearDown() {
        notifierJulLogger.removeHandler(logCapture);
    }

    @Test
    void firstCallEmitsAndPersistsRow() {
        NotifyOutcome outcome = notifier.notifyOnce(
            "stage1-regex-timeout",
            "STAGE1_REGEX_TIMEOUT",
            "regex watchdog fired on post_id=abc");

        assertEquals(NotifyOutcome.EMITTED, outcome,
            "first call for a fresh key must emit");

        Optional<AdminNotificationRecord> state = notifier.getState("stage1-regex-timeout");
        assertTrue(state.isPresent(), "row must be persisted after first call");
        assertEquals(1L, state.get().notificationCount(),
            "notification_count starts at 1 on insert");
        assertEquals(0L, state.get().suppressedCount(),
            "suppressed_count starts at 0 on insert");
        assertEquals("STAGE1_REGEX_TIMEOUT", state.get().errorClass());
        assertEquals(T0, state.get().firstSeenAt());

        assertTrue(
            logCapture.records.stream().anyMatch(r ->
                r.getMessage() != null
                    && r.getMessage().contains("ADMIN-NOTIFY")
                    && r.getMessage().contains("key=stage1-regex-timeout")),
            "WARN log must include ADMIN-NOTIFY with the notification key; captured: "
                + logCapture.formatted());
    }

    @Test
    void withinWindowSuppressesAndBumpsCounter() {
        notifier.notifyOnce("k1", "EC1", "first");
        logCapture.records.clear();

        // Advance well inside the default 1h window — 5 minutes.
        mutableClock.advance(Duration.ofMinutes(5));
        NotifyOutcome outcome = notifier.notifyOnce("k1", "EC1", "second");
        assertEquals(NotifyOutcome.SUPPRESSED, outcome,
            "second call inside the throttle window must suppress");

        Optional<AdminNotificationRecord> state = notifier.getState("k1");
        assertTrue(state.isPresent());
        assertEquals(1L, state.get().notificationCount(),
            "notification_count must stay at 1 inside the window");
        assertEquals(1L, state.get().suppressedCount(),
            "suppressed_count bumps to 1 after one within-window call");

        // A third within-window call bumps suppressed_count to 2.
        mutableClock.advance(Duration.ofMinutes(1));
        notifier.notifyOnce("k1", "EC1", "third");
        Optional<AdminNotificationRecord> state2 = notifier.getState("k1");
        assertEquals(2L, state2.get().suppressedCount(),
            "suppressed_count bumps once per within-window call");

        assertTrue(
            logCapture.records.stream().noneMatch(r ->
                r.getMessage() != null && r.getMessage().contains("ADMIN-NOTIFY")),
            "no second ADMIN-NOTIFY WARN line should be emitted inside the window; captured: "
                + logCapture.formatted());
    }

    @Test
    void afterWindowEmitsAgainAndIncrementsCount() {
        notifier.notifyOnce("k2", "EC2", "first");
        // Advance past the default 1h throttle window.
        mutableClock.advance(Duration.ofHours(2));

        NotifyOutcome outcome = notifier.notifyOnce("k2", "EC2", "second");
        assertEquals(NotifyOutcome.EMITTED, outcome,
            "call after the throttle window elapsed must emit again");

        Optional<AdminNotificationRecord> state = notifier.getState("k2");
        assertTrue(state.isPresent());
        assertEquals(2L, state.get().notificationCount(),
            "notification_count must increment from 1 to 2 on the second emit");
        assertEquals(T0.plus(Duration.ofHours(2)), state.get().lastNotifiedAt(),
            "last_notified_at refreshes to the new clock instant on emit");

        long warnAdminNotifyCount = logCapture.records.stream()
            .filter(r -> r.getMessage() != null && r.getMessage().contains("ADMIN-NOTIFY"))
            .count();
        assertEquals(2L, warnAdminNotifyCount,
            "two ADMIN-NOTIFY WARN lines must be captured across both emits; captured: "
                + logCapture.formatted());
    }

    @Test
    void notifyOnceStripsControlCharactersFromInputs() throws SQLException {
        // Each input carries an embedded "\n" or "\r\n" plus a fake
        // ADMIN-NOTIFY substring that would, if not sanitized, render
        // as a second log line that operator's `grep ADMIN-NOTIFY`
        // scrape would mistake for a genuine notification.
        String maliciousKey = "k1\nADMIN-NOTIFY key=spoofed error=spoof message=fake";
        String maliciousError = "EC1\r\nfaked";
        String maliciousMessage =
            "real-detail\nADMIN-NOTIFY key=second error=second message=second";

        NotifyOutcome outcome =
            notifier.notifyOnce(maliciousKey, maliciousError, maliciousMessage);
        assertEquals(NotifyOutcome.EMITTED, outcome);

        // Exactly one LogRecord — JBoss warnf is one log event regardless,
        // but pin the count so a future implementation change that splits
        // the warnf into multiple emits would surface here.
        long warnRecords = logCapture.records.stream()
            .filter(r -> r.getMessage() != null
                && r.getMessage().startsWith("ADMIN-NOTIFY"))
            .count();
        assertEquals(1L, warnRecords,
            "exactly one ADMIN-NOTIFY log record must be emitted; captured: "
                + logCapture.formatted());

        // The substituted message field must contain no CR/LF — log
        // appenders would otherwise render the embedded newline as a
        // real line break in the operator's log file, faking a second
        // ADMIN-NOTIFY line.
        LogRecord emitted = logCapture.records.stream()
            .filter(r -> r.getMessage() != null
                && r.getMessage().startsWith("ADMIN-NOTIFY"))
            .findFirst().orElseThrow();
        assertFalse(emitted.getMessage().contains("\n"),
            "sanitized log message must not embed a newline; was: "
                + emitted.getMessage());
        assertFalse(emitted.getMessage().contains("\r"),
            "sanitized log message must not embed a CR; was: "
                + emitted.getMessage());

        // Persisted notification_key + error_class must be CR/LF-free
        // — the DB row's identity is the sanitized key, not the
        // attacker's multi-line one, so subsequent throttling decisions
        // bucket on the sanitized form.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT notification_key, error_class FROM admin_notification_state");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "exactly one row must be persisted");
            String storedKey = rs.getString("notification_key");
            String storedError = rs.getString("error_class");
            assertFalse(storedKey.contains("\n") || storedKey.contains("\r"),
                "stored notification_key must not contain CR/LF; was: " + storedKey);
            assertFalse(storedError.contains("\n") || storedError.contains("\r"),
                "stored error_class must not contain CR/LF; was: " + storedError);
            assertFalse(rs.next(), "only one row must be persisted");
        }
    }

    @Test
    void notifyOnceTruncatesOverLongInputsAndAppendsSuffix() throws SQLException {
        // Inputs over the documented caps (256 key / 256 error_class /
        // 2048 message); each must be trimmed and carry the
        // "...[truncated]" suffix so the trim is visible to a reader.
        String longKey = "k".repeat(500);
        String longErrorClass = "E".repeat(500);
        String longMessage = "m".repeat(3000);

        NotifyOutcome outcome =
            notifier.notifyOnce(longKey, longErrorClass, longMessage);
        assertEquals(NotifyOutcome.EMITTED, outcome);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT notification_key, error_class FROM admin_notification_state");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "exactly one row must be persisted");
            String storedKey = rs.getString("notification_key");
            String storedError = rs.getString("error_class");
            assertEquals(256, storedKey.length(),
                "stored notification_key must be truncated to the 256 cap");
            assertTrue(storedKey.endsWith("...[truncated]"),
                "truncated key must carry the documented suffix; was: " + storedKey);
            assertEquals(256, storedError.length(),
                "stored error_class must be truncated to the 256 cap");
            assertTrue(storedError.endsWith("...[truncated]"),
                "truncated error_class must carry the suffix; was: " + storedError);
        }

        LogRecord emitted = logCapture.records.stream()
            .filter(r -> r.getMessage() != null
                && r.getMessage().startsWith("ADMIN-NOTIFY"))
            .findFirst().orElseThrow();
        assertTrue(emitted.getMessage().contains("...[truncated]"),
            "log line must carry the truncation suffix when any field was truncated; was: "
                + emitted.getMessage());
        // Format overhead = "ADMIN-NOTIFY key= error= message=" (~30 chars)
        // plus the three capped fields (256 + 256 + 2048 = 2560). Allow
        // some slack but pin the upper bound so an un-capped field would
        // blow past it.
        assertTrue(emitted.getMessage().length() <= 2700,
            "bounded log line must not exceed sum-of-caps + format overhead; was length "
                + emitted.getMessage().length());
    }

    @Test
    void sqlExceptionFallbackEmitsCanonicalAdminNotifyFormat() throws SQLException {
        // Force the notifier's UPSERT to fail by attaching an
        // unsatisfiable CHECK constraint to the table — any INSERT
        // then raises SQLException at execution time. The constraint
        // is removed in finally so subsequent tests (and any other
        // @QuarkusTest sharing the dev-services DB) can INSERT
        // normally. CHECK-violation over DROP/CREATE keeps the test
        // decoupled from the V16 DDL (no DDL duplication to drift)
        // and over QuarkusMock-swap because the Agroal DataSource is
        // @Singleton, not normal-scoped, so QuarkusMock refuses to
        // swap it.
        final String constraintName = "test_fallback_force_failure";
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE admin_notification_state ADD CONSTRAINT "
                + constraintName + " CHECK (false)");
        }
        try {
            NotifyOutcome outcome = notifier.notifyOnce("any-key", "EC", "boom");
            assertEquals(NotifyOutcome.SUPPRESSED, outcome,
                "SQLException fallback must return SUPPRESSED so callers don't retry-spam");

            LogRecord fallback = logCapture.records.stream()
                .filter(r -> r.getMessage() != null
                    && r.getMessage().startsWith("ADMIN-NOTIFY"))
                .findFirst().orElseThrow(() ->
                    new AssertionError(
                        "SQLException fallback must emit on the ADMIN-NOTIFY pattern; captured: "
                            + logCapture.formatted()));

            String formatted = fallback.getMessage();
            assertTrue(formatted.contains("key=admin-notifier-persistence-failed"),
                "fallback line must carry the canonical persistence-failed key for operator scraping; was: "
                    + formatted);
            assertTrue(formatted.contains("error="),
                "fallback line must carry an error= field naming the SQLException class; was: "
                    + formatted);
            assertTrue(formatted.contains("message="),
                "fallback line must carry a message= field with sanitized exception detail; was: "
                    + formatted);
        } finally {
            try (Connection conn = dataSource.getConnection();
                 Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE admin_notification_state DROP CONSTRAINT IF EXISTS "
                    + constraintName);
            }
        }
    }

    @Test
    void concurrentNotifyOnceRaceSafeForSameKey() throws Exception {
        final int parallel = 20;
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        try {
            // CompletableFuture.allOf gathers all N futures so the
            // race-window assertion holds across every thread.
            CompletableFuture<?>[] futures = new CompletableFuture<?>[parallel];
            CopyOnWriteArrayList<NotifyOutcome> outcomes = new CopyOnWriteArrayList<>();
            for (int i = 0; i < parallel; i++) {
                futures[i] = CompletableFuture.runAsync(
                    () -> outcomes.add(notifier.notifyOnce("k-race", "EC_RACE", "concurrent")),
                    pool);
            }
            CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);

            long emitted = outcomes.stream().filter(o -> o == NotifyOutcome.EMITTED).count();
            long suppressed = outcomes.stream().filter(o -> o == NotifyOutcome.SUPPRESSED).count();
            assertEquals(1L, emitted,
                "exactly one of N concurrent first-time callers must emit; outcomes=" + outcomes);
            assertEquals((long) parallel - 1, suppressed,
                "the remaining N-1 callers must suppress; outcomes=" + outcomes);

            Optional<AdminNotificationRecord> state = notifier.getState("k-race");
            assertTrue(state.isPresent());
            assertEquals(1L, state.get().notificationCount(),
                "notification_count must be exactly 1 after the race");
            assertEquals((long) parallel - 1, state.get().suppressedCount(),
                "suppressed_count must equal N-1 after the race");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Settable {@link Clock} for the test seam. Quarkus-CDI proxies
     * a normal-scoped Clock bean per its standard rules, so an
     * {@code @Inject Clock} field — both here and in the notifier —
     * resolves through the same proxy and observes the mutations.
     */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant initial) {
            assertNotNull(initial, "initial instant must not be null");
            this.now = initial;
        }

        void advance(Duration d) {
            this.now = this.now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        String formatted() {
            StringBuilder sb = new StringBuilder("[");
            for (LogRecord r : records) {
                sb.append(r.getLevel()).append(": ").append(r.getMessage()).append("; ");
            }
            return sb.append("]").toString();
        }
    }
}
