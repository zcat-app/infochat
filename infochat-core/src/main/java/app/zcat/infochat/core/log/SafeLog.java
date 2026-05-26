package app.zcat.infochat.core.log;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

/**
 * Exception-safe SLF4J wrapper that drops exception message bodies
 * to prevent user-authored prose from leaking into operator logs.
 * Emits only the exception class name and a depth-capped cause
 * chain of class names. The caller-supplied msg is run through the
 * closed API-key catalogue ({@link Redactor#redact}) before emission
 * so an API key embedded in the msg is also caught.
 *
 * <p>The original {@link Throwable} is never passed to the underlying
 * SLF4J logger — no stack trace, no message body. Operators debugging
 * exceptions reproduce locally where the unredacted trace is available.
 */
public final class SafeLog {

    static final int MAX_CAUSE_DEPTH = 5;

    private SafeLog() {}

    public static void error(@NonNull Logger logger, @NonNull String msg, @NonNull Throwable t) {
        logger.error(formatSafe(msg, t));
    }

    public static void warn(@NonNull Logger logger, @NonNull String msg, @NonNull Throwable t) {
        logger.warn(formatSafe(msg, t));
    }

    public static void info(@NonNull Logger logger, @NonNull String msg, @NonNull Throwable t) {
        logger.info(formatSafe(msg, t));
    }

    /**
     * Build the sanitized log line: {@code redact(msg) + " | exception=" +
     * className + (" > " + causeClassName)*}. Cause chain is depth-capped
     * at {@value #MAX_CAUSE_DEPTH}.
     */
    static @NonNull String formatSafe(@NonNull String msg, @NonNull Throwable t) {
        String redacted = Redactor.redact(msg);
        StringBuilder sb = new StringBuilder(redacted.length() + 80);
        sb.append(redacted)
                .append(" | exception=")
                .append(t.getClass().getName());
        Throwable cause = t.getCause();
        int depth = 0;
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            sb.append(" > ").append(cause.getClass().getName());
            cause = cause.getCause();
            depth++;
        }
        return sb.toString();
    }
}
