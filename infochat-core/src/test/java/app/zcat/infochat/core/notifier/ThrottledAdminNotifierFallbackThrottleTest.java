package app.zcat.infochat.core.notifier;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins U-59: the notifier's degraded-DB fallback WARN is throttled to one line
 * per (key, window) on the fixed {@code admin-notifier-persistence-failed} key,
 * rather than one line per caller for the duration of a DB outage.
 *
 * <p>Pure unit test — constructs the bean directly with a {@link DataSource}
 * whose {@code getConnection()} throws, so every {@code notifyOnce} takes the
 * SQLException fallback path. Building the bean by hand (rather than injecting
 * the shared CDI singleton) isolates the in-memory throttle's {@code AtomicLong}
 * from other tests, so the window state cannot leak across the suite. Log
 * capture attaches to both the JUL and the JBoss LogContext logger (the same
 * dual-attach the sibling {@link ThrottledAdminNotifierThrowableHygieneTest}
 * uses), since jboss-logging routes to the JBoss LogManager only when it is the
 * installed JVM LogManager.
 */
class ThrottledAdminNotifierFallbackThrottleTest {

    private static final Instant T0 = Instant.parse("2026-06-11T00:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(1);

    private static ThrottledAdminNotifier notifierWithThrottle(Clock clock) {
        var notifier = new ThrottledAdminNotifier();
        notifier.dataSource = (DataSource) Proxy.newProxyInstance(
                ThrottledAdminNotifierFallbackThrottleTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    throw new SQLException("db down");
                });
        notifier.clock = clock;
        notifier.throttleWindow = WINDOW;
        // init() builds the UPSERT SQL, which the degraded-DB path never reaches
        // (getConnection throws first), so it is intentionally not called.
        return notifier;
    }

    @Test
    void nFallbackNotificationsInOneWindowEmitExactlyOneWarn() {
        var notifier = notifierWithThrottle(Clock.fixed(T0, ZoneOffset.UTC));

        List<LogRecord> warns = captureWarnRecords(() -> {
            for (int i = 0; i < 50; i++) {
                NotifyOutcome outcome =
                        notifier.notifyOnce("caller-key-" + i, "EC" + i, "boom " + i);
                assertEquals(NotifyOutcome.PERSISTENCE_FAILED, outcome,
                        "every degraded-DB fallback must return PERSISTENCE_FAILED");
            }
        });

        List<LogRecord> adminNotify = adminNotifyLines(warns);
        assertEquals(1, adminNotify.size(),
                "N fallback notifications inside one window must emit exactly one ADMIN-NOTIFY WARN; captured: "
                        + render(warns));
        // The one emitted line carries the canonical persistence-failed key
        // regardless of which caller key triggered it: the throttle coalesces
        // on the fixed fallback key, not on the per-caller notification key.
        assertTrue(adminNotify.get(0).getMessage().contains("key=admin-notifier-persistence-failed"),
                "fallback WARN must carry the canonical persistence-failed key; was: "
                        + adminNotify.get(0).getMessage());
    }

    @Test
    void fallbackEmitsAgainAfterTheWindowElapses() {
        MutableClock clock = new MutableClock(T0);
        var notifier = notifierWithThrottle(clock);

        List<LogRecord> warns = captureWarnRecords(() -> {
            notifier.notifyOnce("k", "EC", "first");      // first in window -> emits
            clock.advance(Duration.ofMinutes(5));
            notifier.notifyOnce("k", "EC", "second");     // within window -> suppressed
            clock.advance(WINDOW.plusMinutes(1));
            notifier.notifyOnce("k", "EC", "third");      // window elapsed -> emits again
        });

        assertEquals(2, adminNotifyLines(warns).size(),
                "fallback must emit once per window: one before and one after the window elapses; captured: "
                        + render(warns));
    }

    private static List<LogRecord> adminNotifyLines(List<LogRecord> records) {
        List<LogRecord> out = new ArrayList<>();
        for (LogRecord r : records) {
            if (r.getMessage() != null && r.getMessage().startsWith("ADMIN-NOTIFY")) {
                out.add(r);
            }
        }
        return out;
    }

    private List<LogRecord> captureWarnRecords(Runnable action) {
        List<LogRecord> captured = Collections.synchronizedList(new ArrayList<>());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    captured.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        java.util.logging.Logger jul = java.util.logging.Logger
                .getLogger(ThrottledAdminNotifier.class.getName());
        java.util.logging.Logger ctx = org.jboss.logmanager.LogContext.getLogContext()
                .getLogger(ThrottledAdminNotifier.class.getName());
        jul.addHandler(capture);
        if (ctx != jul) {
            ctx.addHandler(capture);
        }
        try {
            action.run();
        } finally {
            jul.removeHandler(capture);
            if (ctx != jul) {
                ctx.removeHandler(capture);
            }
        }
        return captured;
    }

    private static String render(List<LogRecord> records) {
        StringBuilder sb = new StringBuilder("[");
        for (LogRecord r : records) {
            sb.append(r.getLevel()).append(": ").append(r.getMessage()).append("; ");
        }
        return sb.append("]").toString();
    }

    /** Settable clock so a single notifier instance can be advanced across the window boundary. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant initial) {
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
}
