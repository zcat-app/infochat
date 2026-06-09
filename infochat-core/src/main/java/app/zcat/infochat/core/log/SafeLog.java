package app.zcat.infochat.core.log;

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

    /**
     * Replace every control character with a single space: C0
     * (0x00–0x1F), DEL (0x7F), and C1 (0x80–0x9F). The C1 range
     * matters because 0x9B is the single-byte CSI — it opens an ANSI
     * escape sequence exactly like ESC-[ does, so stripping C0 alone
     * leaves terminal-control forgery open. Full-range sweeps, not an
     * enumerated blacklist: no control character has a legitimate
     * place in a one-line log value, and replacing the whole ranges
     * leaves no gaps.
     */
    public static String stripControls(String s) {
        StringBuilder stripped = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean control = c < 0x20 || (c >= 0x7F && c <= 0x9F);
            stripped.append(control ? ' ' : c);
        }
        return stripped.toString();
    }

    public static void error(Logger logger, String msg, Throwable t) {
        logger.error(formatSafe(msg, t));
    }

    public static void warn(Logger logger, String msg, Throwable t) {
        logger.warn(formatSafe(msg, t));
    }

    public static void info(Logger logger, String msg, Throwable t) {
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
    static String formatSafe(String msg, Throwable t) {
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

    private static void appendSuppressedClassNames(StringBuilder sb, Throwable t) {
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
