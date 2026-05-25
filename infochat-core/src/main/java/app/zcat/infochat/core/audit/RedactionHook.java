package app.zcat.infochat.core.audit;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * SPI for the audit-log write-side redaction layer per
 * {@code docs/spec/security.md} §Secrets handling: "Audit-log
 * writes pass through a redaction hook that masks values matching
 * a closed catalogue of API-key shapes."
 *
 * <p>Every row that flows through {@link AuditLogWriter} is handed
 * to {@link #redact(AuditRow)} BEFORE the INSERT; the returned row
 * is what reaches the database. {@link DefaultRedactionHook}
 * implements the spec's seven-family API-key catalogue against the
 * {@code details_json} field. The hook does NOT touch
 * {@code target_contact_id}: per spec §Secrets handling,
 * contact-id redaction is "outside the audit log" and is handled
 * at read time by the {@code audit_log_view} via
 * {@code redact_contact_id()} (V5 §2.1.9).</p>
 *
 * <p>Tests inject an alternative implementation via CDI
 * {@code @Alternative} on a per-test {@code @ApplicationScoped}
 * class. The interface deliberately surfaces ONE method so the SPI
 * boundary is auditable in code review — no per-field redactor
 * helpers, no policy parameters.</p>
 */
public interface RedactionHook {

    /**
     * Apply redaction to {@code row} and return the redacted form.
     * Implementations are stateless — every call processes one row
     * independently; no per-call scratch state may persist across
     * calls.
     *
     * @param row the row built by the writer, BEFORE redaction.
     * @return the redacted row that reaches the audit_log INSERT.
     *         An impl that does not need to redact MAY return the
     *         input unchanged.
     */
    AuditRow redact(@NonNull AuditRow row);

    /**
     * In-memory representation of one audit_log row before the
     * writer INSERT. Field types mirror the V5 §2.1.7
     * {@code audit_log} column shapes:
     * <ul>
     *   <li>{@code actorUserId} — nullable; system-actor rows
     *       (bootstrap, startup beans) carry {@code null}.</li>
     *   <li>{@code actorContactId} — nullable; same rationale as
     *       above.</li>
     *   <li>{@code actorAdapter} — nullable for system-actor rows;
     *       carries the inbound adapter name for user-actor rows.</li>
     *   <li>{@code action} — non-null; the closed-set verb.</li>
     *   <li>{@code targetKind} — non-null; one of the V5 §2.1.7
     *       CHECK values ({@code user|group|source|post|invite|
     *       quarantine|asset|memory|system}).</li>
     *   <li>{@code targetId} — non-null; UUID-as-text for entity
     *       rows, file-content SHA for bootstrap rows,
     *       {@code <host>-<pid>} for startup-bean rows.</li>
     *   <li>{@code targetContactId} — nullable; carries the raw
     *       contact id for rows where the action targets a
     *       contact (BAN, UNBAN, INVITE_CREATE --contact, …). The
     *       redaction hook does NOT touch this column.</li>
     *   <li>{@code scopeId} — nullable; group UUID for group-
     *       scoped actions, null for DM / system rows.</li>
     *   <li>{@code requestId} — nullable; the dispatch correlation
     *       id propagated through {@code current_setting
     *       ('infochat.request_id')} on the V5-procedure carve-out
     *       paths.</li>
     *   <li>{@code detailsJson} — nullable; per-action context
     *       blob. The redaction hook scans this column for
     *       API-key shapes.</li>
     * </ul>
     */
    record AuditRow(
            @Nullable UUID actorUserId,
            @Nullable String actorContactId,
            @Nullable String actorAdapter,
            @NonNull AuditAction action,
            @NonNull String targetKind,
            @NonNull String targetId,
            @Nullable String targetContactId,
            @Nullable UUID scopeId,
            @Nullable String requestId,
            @Nullable String detailsJson) {

        /**
         * Start a builder. Java records expose only the positional
         * constructor; six of the ten columns are optional (nullable)
         * and most call sites set only three or four. The builder
         * lets each call site name the fields it sets and skip the
         * rest implicitly — clearer at the use site than
         * {@code new AuditRow(null, null, null, BAN, "user", id, ...)}
         * with positional nulls.
         */
        public static Builder builder() {
            return new Builder();
        }

        /**
         * Mutable builder for {@link AuditRow}. Unset reference
         * fields default to {@code null}; {@link #action(AuditAction)},
         * {@link #targetKind(String)}, and {@link #targetId(String)}
         * are the three required fields and the {@link #build()}
         * call rejects an attempt to construct a row without them.
         */
        public static final class Builder {
            private @Nullable UUID actorUserId;
            private @Nullable String actorContactId;
            private @Nullable String actorAdapter;
            private @Nullable AuditAction action;
            private @Nullable String targetKind;
            private @Nullable String targetId;
            private @Nullable String targetContactId;
            private @Nullable UUID scopeId;
            private @Nullable String requestId;
            private @Nullable String detailsJson;

            private Builder() {
            }

            public Builder actorUserId(@Nullable UUID v) {
                this.actorUserId = v;
                return this;
            }

            public Builder actorContactId(@Nullable String v) {
                this.actorContactId = v;
                return this;
            }

            public Builder actorAdapter(@Nullable String v) {
                this.actorAdapter = v;
                return this;
            }

            public Builder action(@NonNull AuditAction v) {
                this.action = v;
                return this;
            }

            public Builder targetKind(@NonNull String v) {
                this.targetKind = v;
                return this;
            }

            public Builder targetId(@NonNull String v) {
                this.targetId = v;
                return this;
            }

            public Builder targetContactId(@Nullable String v) {
                this.targetContactId = v;
                return this;
            }

            public Builder scopeId(@Nullable UUID v) {
                this.scopeId = v;
                return this;
            }

            public Builder requestId(@Nullable String v) {
                this.requestId = v;
                return this;
            }

            public Builder detailsJson(@Nullable String v) {
                this.detailsJson = v;
                return this;
            }

            public AuditRow build() {
                if (action == null || targetKind == null || targetId == null) {
                    throw new IllegalStateException(
                            "AuditRow requires non-null action, targetKind, and targetId");
                }
                return new AuditRow(
                        actorUserId, actorContactId, actorAdapter,
                        action, targetKind, targetId,
                        targetContactId, scopeId, requestId, detailsJson);
            }
        }
    }
}
