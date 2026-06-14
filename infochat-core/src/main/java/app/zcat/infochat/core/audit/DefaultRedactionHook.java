package app.zcat.infochat.core.audit;

import app.zcat.infochat.core.log.Redactor;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Default {@link RedactionHook} delegating to the shared
 * {@link Redactor} catalogue from {@code docs/spec/security.md}
 * §Secrets handling. The catalogue and watchdog engine live in
 * {@link Redactor} — this class adapts the string-level redaction
 * to the {@link RedactionHook.AuditRow} shape and guarantees the
 * returned row's {@code details_json} is null/empty or a valid JSON
 * document.
 *
 * <h2>Contact-id handling</h2>
 * <p>The hook does NOT touch {@code target_contact_id} or any
 * contact-id-shaped substring inside {@code details_json}. Per
 * spec §Secrets handling, contact-id redaction is "outside the
 * audit log" and is handled at read time by the
 * {@code audit_log_view} (V5 §2.1.9).</p>
 */
@ApplicationScoped
public class DefaultRedactionHook implements RedactionHook {

    /**
     * Whole-field fallback when the regex watchdog fires. Must be
     * valid JSONB because {@link AuditLogWriter} binds
     * {@code details_json} with {@code ?::jsonb}.
     */
    public static final String REDACTED_FIELD_JSONB =
            "{\"_redacted\":true,\"reason\":\"regex_watchdog_timeout\"}";

    @Override
    public AuditRow redact(AuditRow row) {
        String detailsJson = row.detailsJson();
        if (detailsJson == null || detailsJson.isEmpty()) {
            return row;
        }
        String redacted = Redactor.redact(detailsJson);
        if (Redactor.TIMEOUT_SENTINEL.equals(redacted)) {
            redacted = REDACTED_FIELD_JSONB;
        }
        // Fail-closed post-condition: the value we hand back must survive
        // AuditLogWriter's ?::jsonb cast. Off-contract input (a non-JSON
        // detailsJson built by a buggy caller) would otherwise reach the cast
        // and abort the surrounding audit-before-effect transaction with an
        // opaque SQLException, taking the admin action down and losing the
        // audit row. Substituting the sentinel here keeps the failure inside
        // the redaction layer that already owns the 'valid JSONB' promise. The
        // check is a cheap structural heuristic, not a full parse — the
        // authoritative parse runs server-side at the cast.
        if (!isJsonShaped(redacted)) {
            redacted = REDACTED_FIELD_JSONB;
        }
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
     * Cheap structural check that {@code value} could be a JSON
     * document: it starts with {@code {} or {@code [} and its braces,
     * brackets, and quotes are balanced (quotes scanned with escape
     * awareness so a brace inside a string literal is not miscounted),
     * and nothing follows the balanced top-level token (so
     * {@code {"a":1}garbage} and two concatenated documents are
     * rejected, matching what the {@code ?::jsonb} cast would reject).
     * This is deliberately not a full parse — the authoritative parse
     * runs server-side at {@link AuditLogWriter}'s {@code ?::jsonb}
     * cast; this guard only has to be tight enough to keep off-contract
     * non-JSON from reaching that cast.
     */
    private static boolean isJsonShaped(String value) {
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return false;
        }
        char first = trimmed.charAt(0);
        if (first != '{' && first != '[') {
            return false;
        }
        int braces = 0;
        int brackets = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{' -> braces++;
                case '}' -> braces--;
                case '[' -> brackets++;
                case ']' -> brackets--;
                default -> { }
            }
            if (braces < 0 || brackets < 0) {
                return false;
            }
            // Reject anything after the balanced top-level token closes. The
            // first char is guaranteed '{' or '[', so braces==brackets==0 here
            // can only mean the top-level structure just closed; any further
            // (non-whitespace, since the value is stripped) char is trailing
            // junk like {"a":1}garbage or a second concatenated document. The
            // ?::jsonb cast rejects these, so catching them here keeps the gate
            // fail-closed instead of aborting the audit transaction downstream.
            if (!inString && braces == 0 && brackets == 0 && i < trimmed.length() - 1) {
                return false;
            }
        }
        return !inString && braces == 0 && brackets == 0;
    }
}
