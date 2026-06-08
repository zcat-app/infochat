package app.zcat.infochat.messaging.impl.signal;

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
 * jboss-logmanager is the active LogManager under surefire — same
 * pattern as the SimpleX transport tests. Top-level package-private
 * per the project's avoid-inner-class-fakes rule.
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
