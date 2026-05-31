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
     * className + suppressed? + (" > " + causeClassName + suppressed?)*}.
     * Cause chain is depth-capped at {@value #MAX_CAUSE_DEPTH}; suppressed
     * exceptions are emitted as {@code [+suppressedClassName,+...]} after
     * each exception's class name. Only class names traverse — message
     * bodies and stack frames stay out, mirroring the cause-chain
     * invariant. Walking suppressed closes the security.md §"User content
     * in exceptions" gap that future {@code try-with-resources} or parallel
     * {@code addSuppressed} call sites could otherwise carry user-authored
     * content into the log if a contributor later called the underlying
     * SLF4J {@code error(msg, t)} instead of this wrapper.
     */
    static @NonNull String formatSafe(@NonNull String msg, @NonNull Throwable t) {
        String redacted = Redactor.redact(msg);
        StringBuilder sb = new StringBuilder(redacted.length() + 80);
        sb.append(redacted)
                .append(" | exception=")
                .append(t.getClass().getName());
        appendSuppressedClassNames(sb, t);
        Throwable cause = t.getCause();
        int depth = 0;
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            sb.append(" > ").append(cause.getClass().getName());
            appendSuppressedClassNames(sb, cause);
            cause = cause.getCause();
            depth++;
        }
        return sb.toString();
    }

    private static void appendSuppressedClassNames(@NonNull StringBuilder sb, @NonNull Throwable t) {
        Throwable[] suppressed = t.getSuppressed();
        if (suppressed.length == 0) {
            return;
        }
        sb.append("[");
        for (int i = 0; i < suppressed.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("+").append(suppressed[i].getClass().getName());
        }
        sb.append("]");
    }
}
