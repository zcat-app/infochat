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

    static final int MAX_SUPPRESSED_WIDTH = 5;

    private SafeLog() {}

    /**
     * Replace every control or line-integrity-breaking character with a
     * single space: C0 (0x00–0x1F), DEL (0x7F), C1 (0x80–0x9F), the full
     * Unicode bidi-control set — U+061C (ALM), U+200E / U+200F (LRM / RLM),
     * U+202A–U+202E (LRE / RLE / PDF / LRO / RLO) and the directional
     * isolates U+2066–U+2069 (LRI / RLI / FSI / PDI) — plus the line /
     * paragraph separators U+2028 / U+2029. The C1 range matters because
     * 0x9B is the single-byte CSI — it opens an ANSI escape sequence exactly
     * like ESC-[ does, so stripping C0 alone leaves terminal-control forgery
     * open. The bidi controls and the two separators are not ISO controls
     * and so survive the C0/C1 sweep, yet each breaks one-line log
     * integrity: the bidi controls visually reorder the remainder of the
     * line to spoof it (the Trojan-Source class, CVE-2021-42574), and
     * U+2028 / U+2029 are line / paragraph breaks many log viewers honour —
     * the same line-splitting forgery CR/LF give. This is the same
     * bidi-control set IngestTextNormalizer.stripBidiAndZeroWidth strips on
     * the ingest path; SafeLog must keep replacing each with a space (its
     * one-line log semantics) rather than deleting, as the ingest path does.
     * Full-range sweeps for the control bands, not an enumerated blacklist:
     * no control character has a legitimate place in a one-line log value,
     * and replacing the whole ranges leaves no gaps.
     */
    public static String stripControls(String s) {
        StringBuilder stripped = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean control = c < 0x20
                    || (c >= 0x7F && c <= 0x9F)
                    || c == '\u061C'
                    || c == '\u200E'
                    || c == '\u200F'
                    || (c >= '\u202A' && c <= '\u202E')
                    || (c >= '\u2066' && c <= '\u2069')
                    || c == '\u2028'
                    || c == '\u2029';
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
     * Build the sanitized log line: {@code stripControls(redact(msg)) +
     * " | exception=" + className + suppressed? +
     * (" > " + causeClassName + suppressed?)*}.
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
        // Strip controls in addition to redacting: the caller msg is the one
        // segment of the line a caller can fill with CR/LF/ANSI to forge a
        // second log line, exactly the surface the peer ThrottledAdminNotifier
        // already sanitizes (security.md §"User content in exceptions"). Strip
        // after redact so the emitted text carries neither an API key nor a
        // control character.
        String redacted = stripControls(Redactor.redact(msg));
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
        // Width-cap the per-node suppressed list the same way the cause chain is
        // depth-capped (MAX_CAUSE_DEPTH): a pathological throwable can carry
        // thousands of suppressed exceptions (e.g. a try-with-resources loop over
        // attacker-sized input), which would otherwise expand into an unbounded
        // single log line. Emit at most MAX_SUPPRESSED_WIDTH names and name the
        // elision with a +Nmore token, mirroring the truncate-and-name shape.
        int emitted = Math.min(suppressed.length, MAX_SUPPRESSED_WIDTH);
        sb.append("[");
        for (int i = 0; i < emitted; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("+").append(suppressed[i].getClass().getName());
        }
        if (suppressed.length > emitted) {
            sb.append(",+").append(suppressed.length - emitted).append("more");
        }
        sb.append("]");
    }
}
