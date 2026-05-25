package app.zcat.infochat.core.audit;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default {@link RedactionHook} implementing the closed API-key-shape
 * catalogue from {@code docs/spec/security.md} §Secrets handling.
 *
 * <p>The seven shape families (spec v1 baseline) are applied in
 * order. Order matters for the family overlap: Anthropic
 * {@code sk-ant-…} is a strict prefix of OpenAI {@code sk-…}, so
 * Anthropic runs first and the Anthropic-match prevents the
 * OpenAI pattern from re-matching the same span.</p>
 *
 * <h2>Fail-closed on regex timeout</h2>
 * <p>Per spec §Secrets handling: "The redactor is fail-closed on
 * regex timeout (the same {@code java.util.regex}-plus-watchdog
 * discipline as Stage 1, see §Ingest pipeline): a timed-out match
 * treats the whole field as redacted rather than emitting it raw."
 * The {@link InterruptibleCharSequence} wrapper mirrors the Stage 1
 * pattern at {@code Stage1Pipeline.InterruptibleCharSequence}: every
 * {@code charAt} throws after the deadline, the
 * {@link RegexInterruptedException} unwinds the matcher cleanly,
 * and the outer try/catch substitutes the whole field with
 * {@link #REDACTED_FIELD_JSONB} — a valid-JSONB object self-describing
 * the timeout. Per-match replacements inside an otherwise-valid
 * details_json document still use {@link #REDACTED_PLACEHOLDER}, which
 * is valid because it lives inside the surrounding JSON string
 * quotes.</p>
 *
 * <h2>Contact-id handling</h2>
 * <p>The hook does NOT touch {@code target_contact_id} or any
 * contact-id-shaped substring inside {@code details_json}. Per
 * spec §Secrets handling, contact-id redaction is "outside the
 * audit log" and is handled at read time by the
 * {@code audit_log_view} (V5 §2.1.9). The closed catalogue
 * targets API-key SHAPES, not arbitrary identifier shapes.</p>
 */
@ApplicationScoped
public class DefaultRedactionHook implements RedactionHook {

    /**
     * Literal that replaces every catalogue match inside
     * {@code details_json}. Stays valid JSON because it lives inside
     * a JSON string context (e.g. {@code {"api_key":"sk-..."} }
     * becomes {@code {"api_key":"[REDACTED]"} } — the surrounding
     * quotes make {@code [REDACTED]} a string literal).
     */
    public static final String REDACTED_PLACEHOLDER = "[REDACTED]";

    /**
     * Whole-field fallback used when the regex watchdog fires and the
     * partial-redaction state cannot be trusted. Distinct from
     * {@link #REDACTED_PLACEHOLDER} because this string replaces the
     * ENTIRE {@code details_json} field — it must therefore be a
     * VALID JSONB document. {@link AuditLogWriter} binds
     * {@code details_json} with a {@code ?::jsonb} cast; if this
     * string is not valid JSON the surrounding audit INSERT (and the
     * dispatch transaction that contains it) rolls back. A JSON
     * object shape is also self-describing: an operator reviewing
     * {@code /audit} sees {@code reason:"regex_watchdog_timeout"}
     * directly in the row's {@code details_json::text} value.
     */
    public static final String REDACTED_FIELD_JSONB =
            "{\"_redacted\":true,\"reason\":\"regex_watchdog_timeout\"}";

    /**
     * Closed catalogue of API-key shape regexes from
     * {@code docs/spec/security.md} §Secrets handling lines 1005-1013.
     * Order: Anthropic before OpenAI (strict-prefix overlap),
     * provider-pinned families before the generic adjacent-to-keyword
     * shape (which is the broadest and could over-match if it ran
     * first).
     */
    private static final List<Pattern> CATALOGUE = List.of(
            // Anthropic sk-ant-… (must run before OpenAI sk-…)
            Pattern.compile("sk-ant-[A-Za-z0-9_-]{20,}"),
            // OpenAI sk-… including sk-proj-… / sk-svcacct-…
            Pattern.compile("sk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{20,}"),
            // GitHub ghp_/gho_/ghu_/ghs_/ghr_…
            Pattern.compile("gh[opusr]_[A-Za-z0-9]{20,}"),
            // AWS AKIA/ASIA access keys
            Pattern.compile("(?:AKIA|ASIA)[0-9A-Z]{16}"),
            // Google AIza…
            Pattern.compile("AIza[0-9A-Za-z_-]{35}"),
            // Slack xox[abprs]-…
            Pattern.compile("xox[abprs]-[A-Za-z0-9-]{10,}"),
            // Generic 32+-char hex/base64 adjacent to api_key/secret/token/password/bearer.
            // Group 1 captures keyword + separator so replaceAll("$1…") preserves them.
            Pattern.compile(
                    "(?i)((?:api[_-]?key|secret|token|password|bearer)[\"'\\s:=]{0,5})[A-Za-z0-9+/=_-]{32,}")
    );

    /**
     * Per-input wall-clock cap on the catalogue pass. Mirrors the
     * Stage 1 cap shape but is independent. Hardcoded at 100 ms in
     * v1; if operator tuning becomes necessary a follow-up ticket
     * promotes this to a config property (current Stage 1 precedent
     * for that promotion is M1-028).
     */
    static final long DEFAULT_TIMEOUT_MS = 100L;

    @Override
    public AuditRow redact(AuditRow row) {
        String detailsJson = row.detailsJson();
        if (detailsJson == null || detailsJson.isEmpty()) {
            return row;
        }
        String redacted = applyCatalogue(detailsJson, DEFAULT_TIMEOUT_MS);
        if (redacted.equals(detailsJson)) {
            return row;
        }
        return new AuditRow(
                row.actorUserId(),
                row.actorContactId(),
                row.actorAdapter(),
                row.action(),
                row.targetKind(),
                row.targetId(),
                row.targetContactId(),
                row.scopeId(),
                row.requestId(),
                redacted);
    }

    /**
     * Apply the closed catalogue with a per-input wall-clock cap.
     * On a watchdog fire, fail-closed: return {@link #REDACTED_PLACEHOLDER}
     * for the whole field rather than emitting the raw input. Visible
     * to tests so {@code RedactionHookTest} can exercise the timeout
     * branch without standing up a CDI runtime.
     */
    static String applyCatalogue(String input, long timeoutMsParam) {
        long deadlineNanos = System.nanoTime() + timeoutMsParam * 1_000_000L;
        String current = input;
        try {
            for (Pattern pattern : CATALOGUE) {
                Matcher m = pattern.matcher(new InterruptibleCharSequence(current, deadlineNanos));
                if (m.find()) {
                    if (m.groupCount() > 0) {
                        // Generic pattern captures keyword + separator in group 1;
                        // replacing only the trailing value keeps surrounding JSON valid.
                        current = m.replaceAll("$1" + Matcher.quoteReplacement(REDACTED_PLACEHOLDER));
                    } else {
                        current = m.replaceAll(Matcher.quoteReplacement(REDACTED_PLACEHOLDER));
                    }
                }
            }
        } catch (RegexInterruptedException e) {
            // Whole-field fallback: the partial-redaction state may
            // still contain raw key shapes, so we replace the entire
            // field. The replacement must be valid JSONB because
            // AuditLogWriter binds details_json with ?::jsonb — a
            // bare "[REDACTED]" would fail the cast and bubble up as
            // a SQLException, rolling back the surrounding admin
            // action. {@link #REDACTED_FIELD_JSONB} is a valid JSON
            // object self-describing the timeout.
            return REDACTED_FIELD_JSONB;
        }
        return current;
    }

    /**
     * Wraps a body string with a wall-clock deadline. Mirrors the
     * Stage 1 {@code InterruptibleCharSequence} pattern verbatim —
     * every {@code charAt} call checks the clock and throws after
     * the deadline. The exception unwinds {@link Matcher#find()}
     * cleanly because the underlying NFA engine never catches it.
     */
    private static final class InterruptibleCharSequence implements CharSequence {
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

    /**
     * Thrown by {@link InterruptibleCharSequence#charAt(int)} when
     * the wall-clock cap has elapsed. Unchecked because
     * {@code CharSequence#charAt(int)} cannot declare a checked
     * throws clause.
     */
    static final class RegexInterruptedException extends RuntimeException {
        RegexInterruptedException() {
            super("Audit redaction-hook regex watchdog fired");
        }
    }
}
