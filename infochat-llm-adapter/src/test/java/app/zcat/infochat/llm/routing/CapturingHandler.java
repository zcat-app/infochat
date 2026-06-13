package app.zcat.infochat.llm.routing;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Minimal JUL handler that records every {@link LogRecord} the target
 * logger emits — supports both the JBoss-LogManager bootstrap (Quarkus
 * production) and the stock JUL bootstrap (plain JUnit5). Top-level
 * package-private per the project's avoid-inner-class-fakes rule; shared
 * by the routing tests that assert on {@link LlmRouter} /
 * {@link LlmRouterStartupGuard} WARN output instead of each carrying its
 * own copy.
 */
final class CapturingHandler extends Handler {

    final List<LogRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void publish(LogRecord record) {
        records.add(record);
    }

    @Override
    public void flush() {
        // no-op
    }

    @Override
    public void close() {
        // no-op
    }

    List<LogRecord> recordsAtLevel(Level level) {
        return records.stream()
            .filter(r -> r.getLevel().intValue() >= level.intValue())
            .toList();
    }

    String formattedAll() {
        StringBuilder sb = new StringBuilder();
        for (LogRecord r : records) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(r.getLevel()).append(": ").append(formatMessage(r));
        }
        return sb.toString();
    }

    /**
     * Render a record's message with its parameters substituted (the
     * parameter-aware variant), so an assertion sees the same text the
     * operator would — a message-only render would miss a secret carried
     * in a {@code *f} parameter rather than the rendered message.
     */
    static String formatMessage(LogRecord record) {
        String raw = record.getMessage();
        if (raw == null) {
            return "";
        }
        Object[] params = record.getParameters();
        if (params == null || params.length == 0) {
            return raw;
        }
        return String.format(raw, params);
    }
}
