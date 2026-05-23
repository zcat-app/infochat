package app.zcat.infochat.core.audit;

/**
 * Closed-set enum of v1 {@code audit_log.action} verbs.
 *
 * <p>The audit-log {@code action} column is TEXT (V5 §2.1.7) and not
 * pinned by a SQL CHECK constraint (V5 lines 28-29, 272-273: "the
 * verb catalogue is open-ended for v2 additions and the
 * application-layer audit-write helper is the closure enforcer").
 * This enum IS that application-layer closure: every audit row
 * written through {@link AuditLogWriter} must name its verb via an
 * {@link AuditAction} constant. The DB column value is
 * {@link #name()} — the enum's identifier is the wire format.</p>
 *
 * <p>The set tracks the V5 §2.1.8 line-comment catalogue plus
 * post-V5 additions:</p>
 * <ul>
 *   <li>V12 added {@link #INVITE_BRUTE_FORCE_BREACH} for the
 *       per-(adapter, contact_id) brute-force breach audit row.</li>
 *   <li>V13 (this ticket) adds {@link #LLM_OUTPUT_SANITIZED} for
 *       the per-occurrence sanitizer hit audit row.</li>
 * </ul>
 *
 * <p>{@link #STARTUP_RELEASE_ON_STAGE2_FAILURE_TRUE} pre-dates this
 * enum: it was minted inline by M1-033's
 * {@code StartupReleaseOnStage2FailureWarn} bean and is centralized
 * here so the writer-migration call site has a single symbol.</p>
 *
 * <p>SECURITY DEFINER stored procedures
 * ({@code delete_preban_user}, {@code approve_quarantine},
 * {@code reject_quarantine}) carve out their own INSERT path and
 * write verbs directly in SQL ({@code UNBAN_PREBAN_DELETE},
 * {@code APPROVE_QUARANTINE}, {@code REJECT_QUARANTINE}). Those
 * verbs are still represented here so application-layer code
 * (read-paths, future admin reviews) can reference one symbol.</p>
 */
public enum AuditAction {
    BOOTSTRAP_ADMIN,
    BOOTSTRAP_SOURCE_LOAD,
    BOOTSTRAP_ASSET_LOAD,
    GRANT_ADMIN,
    REVOKE_ADMIN,
    BAN,
    UNBAN,
    UNBAN_PREBAN_DELETE,
    VOUCH,
    INVITE_CREATE,
    INVITE_REVOKE,
    INVITE_CONSUME,
    INVITE_BRUTE_FORCE_BREACH,
    PROMOTE_GROUP_ADMIN,
    DEMOTE_GROUP_ADMIN,
    ADD_SOURCE,
    REMOVE_SOURCE,
    SOURCE_ENABLE,
    SOURCE_DISABLE,
    APPROVE_QUARANTINE,
    REJECT_QUARANTINE,
    FORGET,
    SET_LANG,
    SET_TIMEZONE,
    LLM_OUTPUT_SANITIZED,
    STARTUP_RELEASE_ON_STAGE2_FAILURE_TRUE
}
