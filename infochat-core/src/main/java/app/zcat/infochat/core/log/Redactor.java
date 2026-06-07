package app.zcat.infochat.core.log;

import io.quarkus.logging.LoggingFilter;

import java.util.List;
import java.util.logging.Filter;
import java.util.logging.LogRecord;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Closed API-key-shape catalogue, fail-closed redaction engine, and
 * JBoss LogManager console filter in one class. Single source of
 * truth for the v1 catalogue (spec baseline in
 * {@code docs/spec/security.md} §Secrets handling). Registered as
 * {@code quarkus.log.console.filter=api-key-redactor} on both
 * Collector and Provider.
 *
 * <p>Any future caller (e.g. the audit_log writer) consumes the
 * same {@link #redact(String)} entry point so the catalogue cannot
 * drift.</p>
 *
 * <p>The filter redacts the message template and any {@link String}
 * parameters before the formatter renders them. If any redaction
 * times out, the entire message is replaced with
 * {@link #TIMEOUT_SENTINEL} (fail-closed).</p>
 */
@LoggingFilter(name = "api-key-redactor")
public final class Redactor implements Filter {

    public static final String REDACTED = "[REDACTED]";
    public static final String TIMEOUT_SENTINEL = "[REDACTED:timeout]";

    // Per-input wall-clock cap on the catalogue pass. Same shape as
    // Stage 1 and the audit redaction hook; independent budget.
    static final long DEFAULT_TIMEOUT_MS = 100L;

    /**
     * Closed catalogue from docs/spec/security.md §Secrets handling.
     * Order matters: Anthropic {@code sk-ant-…} before OpenAI
     * {@code sk-…} (strict-prefix overlap); provider-pinned families
     * before the generic adjacent-to-keyword shape (broadest).
     */
    static final List<Pattern> CATALOGUE = List.of(
            Pattern.compile("sk-ant-[A-Za-z0-9_-]{20,}"),
            Pattern.compile("sk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{20,}"),
            Pattern.compile("gh[opusr]_[A-Za-z0-9]{20,}"),
            Pattern.compile("(?:AKIA|ASIA)[0-9A-Z]{16}"),
            Pattern.compile("AIza[0-9A-Za-z_-]{35}"),
            Pattern.compile("xox[abprs]-[A-Za-z0-9-]{10,}"),
            // Separator class is spelled explicitly — no \s shorthand —
            // because Java's \s is ASCII-only while PostgreSQL's is
            // [[:space:]]: only an explicit class (ASCII \s spelled out,
            // plus NBSP and the punctuation set , | < > ( ) -) makes the
            // textual identity with the SQL mirror in V33's
            // redact_secrets_jsonb imply semantic identity too (guarded
            // by RedactorSqlParityIT). The bound stays finite so the
            // spec's "adjacent" keeps meaning and backtracking is capped
            // at 65 retries per position; {0,64} covers column-aligned
            // config dumps, and a 65+-char pure-separator run is no
            // longer plausibly a key/value gap — a deliberate cliff,
            // pinned by negative tests on both engines.
            Pattern.compile(
                    "(?i)((?:api[_-]?key|secret|token|password|bearer)[\"' \\t\\n\\x0B\\f\\r\\u00A0:=,|<>()-]{0,64})[A-Za-z0-9+/=_-]{32,}")
    );

    /**
     * Apply the closed API-key catalogue to {@code input} and return
     * the redacted form. On regex timeout, fail-closed: return
     * {@link #TIMEOUT_SENTINEL} for the entire input rather than
     * emitting raw text.
     */
    public static String redact(String input) {
        return redact(input, DEFAULT_TIMEOUT_MS);
    }

    static String redact(String input, long timeoutMs) {
        if (input.isEmpty()) {
            return input;
        }
        long deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
        String current = input;
        try {
            for (Pattern pattern : CATALOGUE) {
                Matcher m = pattern.matcher(new InterruptibleCharSequence(current, deadlineNanos));
                if (m.find()) {
                    if (m.groupCount() > 0) {
                        current = m.replaceAll("$1" + Matcher.quoteReplacement(REDACTED));
                    } else {
                        current = m.replaceAll(Matcher.quoteReplacement(REDACTED));
                    }
                }
            }
        } catch (RegexInterruptedException e) {
            return TIMEOUT_SENTINEL;
        }
        return current;
    }

    // --- Filter implementation ---

    @Override
    public boolean isLoggable(LogRecord record) {
        String msg = record.getMessage();
        if (msg != null) {
            String redacted = redact(msg);
            if (TIMEOUT_SENTINEL.equals(redacted)) {
                record.setMessage(TIMEOUT_SENTINEL);
                record.setParameters(null);
                return true;
            }
            if (!redacted.equals(msg)) {
                record.setMessage(redacted);
            }
        }

        Object[] params = record.getParameters();
        if (params != null) {
            for (int i = 0; i < params.length; i++) {
                if (params[i] == null) {
                    continue;
                }
                String str = params[i] instanceof String s ? s : params[i].toString();
                String redacted = redact(str);
                if (TIMEOUT_SENTINEL.equals(redacted)) {
                    record.setMessage(TIMEOUT_SENTINEL);
                    record.setParameters(null);
                    return true;
                }
                if (!redacted.equals(str)) {
                    params[i] = redacted;
                }
            }
        }
        return true;
    }

    /**
     * Wraps a string with a wall-clock deadline. Every
     * {@code charAt} call checks the clock and throws after the
     * deadline. Same pattern as Stage 1 and the audit redaction hook.
     */
    static final class InterruptibleCharSequence implements CharSequence {
        private final CharSequence delegate;
        private final long deadlineNanos;

        InterruptibleCharSequence(CharSequence delegate, long deadlineNanos) {
            this.delegate = delegate;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() > deadlineNanos) {
                throw new RegexInterruptedException();
            }
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new InterruptibleCharSequence(delegate.subSequence(start, end), deadlineNanos);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }

    static final class RegexInterruptedException extends RuntimeException {
        RegexInterruptedException() {
            super("Redactor regex watchdog fired");
        }
    }
}
