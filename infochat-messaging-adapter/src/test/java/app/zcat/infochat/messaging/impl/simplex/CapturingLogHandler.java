package app.zcat.infochat.messaging.impl.simplex;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.jboss.logmanager.LogContext;

/**
 * Test-only JUL handler that records every {@link LogRecord} a target
 * named logger publishes. Attaches to BOTH the jboss-logmanager Logger
 * and the JUL Logger so the capture is robust to whether
 * jboss-logmanager is the active LogManager under surefire. Shared by
 * the SimpleX transport tests instead of a per-test inner-class copy
 * (avoid-inner-class-fakes rule).
 *
 * <p>This is the parameter-aware variant: {@link #formatted()} appends
 * each record's parameters as well as its message pattern, so an
 * absence assertion ("the log must not carry secret X") still holds when
 * the secret rides in a {@code *f} parameter rather than the rendered
 * message — the gap the prior message-only simplex copies missed. It
 * mirrors the {@code impl.signal} package's identical helper; the two
 * stay separate because package-private visibility cannot span the
 * sibling {@code impl.simplex} / {@code impl.signal} test packages.
 */
final class CapturingLogHandler extends Handler {

    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final org.jboss.logmanager.Logger jbossLogger;
    private final Logger julLogger;

    private CapturingLogHandler(org.jboss.logmanager.Logger jbossLogger,
                                Logger julLogger) {
        this.jbossLogger = jbossLogger;
        this.julLogger = julLogger;
        jbossLogger.addHandler(this);
        julLogger.addHandler(this);
    }

    static CapturingLogHandler attach(Class<?> target) {
        org.jboss.logmanager.Logger jboss =
                LogContext.getLogContext().getLogger(target.getName());
        Logger jul = Logger.getLogger(target.getName());
        return new CapturingLogHandler(jboss, jul);
    }

    void detach() {
        jbossLogger.removeHandler(this);
        julLogger.removeHandler(this);
    }

    @Override
    public void publish(LogRecord record) {
        records.add(record);
    }

    @Override
    public void flush() { }

    @Override
    public void close() { }

    /**
     * Every captured record's message pattern AND parameters,
     * concatenated. jboss-logging's {@code *f} methods may publish the
     * printf pattern with parameters unsubstituted (logmanager
     * backend) or pre-formatted (plain-JUL backend); appending both
     * means an absence assertion ("the log must not carry X") covers
     * either path.
     */
    String formatted() {
        StringBuilder sb = new StringBuilder("[");
        for (LogRecord r : records) {
            sb.append(r.getLevel()).append(": ").append(r.getMessage());
            Object[] parameters = r.getParameters();
            if (parameters != null) {
                for (Object parameter : parameters) {
                    sb.append(' ').append(parameter);
                }
            }
            sb.append("; ");
        }
        return sb.append("]").toString();
    }
}
