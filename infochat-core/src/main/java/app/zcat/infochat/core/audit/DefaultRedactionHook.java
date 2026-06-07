package app.zcat.infochat.core.audit;

import app.zcat.infochat.core.log.Redactor;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Default {@link RedactionHook} delegating to the shared
 * {@link Redactor} catalogue from {@code docs/spec/security.md}
 * §Secrets handling. The catalogue and watchdog engine live in
 * {@link Redactor} — this class adapts the string-level redaction
 * to the {@link RedactionHook.AuditRow} shape and translates the
 * timeout sentinel to valid JSONB.
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
}
