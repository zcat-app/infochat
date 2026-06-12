package app.zcat.infochat.core.notifier;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that the notifier's two degraded-path WARN sites no longer bind
 * the raw {@link SQLException} as the log throwable: a bound throwable
 * renders its unredacted message and cause-chain text around the
 * sanitized line. Class name yes, message body no (the SafeLog
 * convention).
 */
class ThrottledAdminNotifierThrowableHygieneTest {

    private static final char ESC = (char) 0x1B;
    private static final String DRIVER_MESSAGE =
            "connection refused" + ESC + "[2J; host=db.internal";

    private ThrottledAdminNotifier notifierWithThrowingDataSource() {
        var notifier = new ThrottledAdminNotifier();
        notifier.dataSource = (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    throw new SQLException(DRIVER_MESSAGE);
                });
        // The degraded-DB fallback throttle reads clock + throttleWindow; a
        // hand-built bean (no CDI injection) must supply them or notifyOnce's
        // catch path NPEs. Each test method builds a fresh notifier, so the
        // throttle's first-in-window call always emits the one expected WARN.
        notifier.clock = Clock.systemUTC();
        notifier.throttleWindow = Duration.ofHours(1);
        return notifier;
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
        // jboss-logging routes through the JBoss LogManager only when it
        // is installed as the JVM's LogManager; in a plain surefire JVM
        // it falls back to stock JUL. Attach to both hierarchies — the
        // identity check prevents double capture when they are the same
        // logger object.
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

    private static String renderedText(LogRecord record) {
        return record.getMessage() + " " + Arrays.toString(record.getParameters());
    }

    @Test
    void degradedDbAdminNotifyWarnDoesNotBindThrowable() {
        var notifier = notifierWithThrowingDataSource();

        List<LogRecord> captured = captureWarnRecords(
                () -> notifier.notifyOnce("some-key", "some-error", "some message"));

        assertEquals(1, captured.size(), "exactly one degraded-DB WARN expected");
        LogRecord record = captured.get(0);
        assertNull(record.getThrown(),
                "the SQLException must not be bound as the log throwable");
        String text = renderedText(record);
        assertTrue(text.contains("admin-notifier-persistence-failed"),
                "the canonical persistence-failed key must be on the scrape surface");
        assertTrue(text.contains(SQLException.class.getSimpleName()),
                "the exception class name must still be named");
        assertFalse(text.indexOf(ESC) >= 0,
                "driver message control characters must be stripped from the WARN line");
    }

    @Test
    void getStateWarnDoesNotBindThrowable() {
        var notifier = notifierWithThrowingDataSource();

        List<LogRecord> captured = captureWarnRecords(
                () -> notifier.getState("some-key"));

        assertEquals(1, captured.size(), "exactly one read-state WARN expected");
        LogRecord record = captured.get(0);
        assertNull(record.getThrown(),
                "the SQLException must not be bound as the log throwable");
        assertTrue(renderedText(record).contains(SQLException.class.getSimpleName()),
                "the exception class name must still be named");
    }
}
