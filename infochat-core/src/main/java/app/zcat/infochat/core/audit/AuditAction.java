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
 * <p>This enum is the authoritative catalogue that V5 §2.1.8 points to;
 * it carries the original v1 verbs plus post-V5 additions:</p>
 * <ul>
 *   <li>V12 added {@link #INVITE_BRUTE_FORCE_BREACH} for the
 *       per-(adapter, contact_id) brute-force breach audit row.</li>
 *   <li>V13 adds {@link #LLM_OUTPUT_SANITIZED} for
 *       the per-occurrence sanitizer hit audit row.</li>
 *   <li>M1-068 adds {@link #CHAT_MODE} for the per-request audit
 *       row written by {@code app.zcat.infochat.provider.chat.ChatAgent}
 *       before the LLM call in chat-mode dispatch. The row records
 *       actor + scope but never user-authored prose.</li>
 *   <li>M1-051 adds {@link #BAN_INTENT},
 *       {@link #INVITE_CREATE_INTENT}, and
 *       {@link #INVITE_REVOKE_INTENT} for the spec §Authorization
 *       model step-8 "Audit-log the intent" row written on the
 *       first-call path of confirm-gated destructive commands.
 *       The intent row is its own atomic INSERT (separate from the
 *       BAN / INVITE_CREATE / INVITE_REVOKE completion row written
 *       by the step-9 execute path), so an admin who probes and
 *       abandons leaves an audit trail even when no destructive
 *       mutation lands.</li>
 *   <li>{@link #LIST_SOURCES_ALL} records the privileged
 *       {@code /list-sources --all [--include-deleted]} read — an
 *       admin-only deployment-wide enumeration of source URLs that
 *       spec §Source URL visibility flags as operator-visible. The
 *       {@code --include-deleted} variant is encoded in the audit
 *       row's {@code details_json} so one verb covers both
 *       privileged forms. Unprivileged DM/group reads of
 *       {@code /list-sources} write NO audit row (matching the
 *       established read-only-doesn't-audit pattern); the gap the
 *       audit closes is specifically the privileged-read
 *       enumeration of every source URL across the deployment.</li>
 *   <li>{@link #LIST_GROUPS} records the bot-admin-only
 *       {@code /list-groups [--page N]} read — a deployment-wide
 *       enumeration of every {@code groups} row. Distinct from
 *       {@link #LIST_SOURCES_ALL} in that {@code /list-groups} has
 *       no unprivileged form (the whole command is admin-only), so
 *       the verb carries no {@code _ALL} suffix.</li>
 * </ul>
 *
 * <p>{@link #STARTUP_RELEASE_ON_STAGE2_FAILURE} pre-dates this
 * enum: it was minted inline by M1-033's
 * {@code StartupReleaseOnStage2FailureWarn} bean and is centralized
 * here so the writer-migration call site has a single symbol.</p>
 *
 * <p>This enum carries ONLY application-writable verbs. The verbs
 * that exclusively SECURITY DEFINER stored procedures write in SQL
 * live in {@link ProcedureOnlyAction} (M1-361) so that
 * {@link AuditLogWriter#write}, which takes an {@link AuditAction},
 * cannot be handed a procedure-only verb — the compiler now enforces
 * the audit-before-effect carve-out the comment used to police.</p>
 */
public enum AuditAction implements AuditVerb {
    BOOTSTRAP_ADMIN,
    BOOTSTRAP_SOURCE_LOAD,
    BOOTSTRAP_ASSET_LOAD,
    GRANT_ADMIN,
    // GRANT_ADMIN_INTENT is the audit-on-intent row written on a
    // separate auto-commit connection BEFORE the grant transaction's
    // is_admin=TRUE UPDATE (security.md §Authorization model: step 8
    // "Audit-log the intent" precedes step 9 "Execute"). Unlike
    // BAN_INTENT / INVITE_*_INTENT (first-call leg of confirm-gated
    // commands), /grant-admin is single-shot — and unlike
    // REVOKE_ADMIN_INTENT there is no trigger rollback to survive
    // (the V5 last-admin trigger guards revocation, not grants): the
    // row's job is purely probe visibility, so the unknown-contact,
    // banned-target and already-admin refusal legs no longer roll
    // back row-less.
    GRANT_ADMIN_INTENT,
    REVOKE_ADMIN,
    // REVOKE_ADMIN_INTENT is the audit-on-intent row written on a
    // separate auto-commit connection BEFORE the revoke transaction's
    // is_admin=FALSE UPDATE (security.md §Authorization model: step 8
    // "Audit-log the intent" precedes step 9 "Execute"). Unlike
    // BAN_INTENT / INVITE_*_INTENT (first-call leg of confirm-gated
    // commands), /revoke-admin is single-shot — the row's job is to
    // survive the last-admin trigger rollback, so a refused attempt
    // still leaves an operator-visible audit record.
    REVOKE_ADMIN_INTENT,
    BAN,
    BAN_INTENT,
    UNBAN,
    // UNBAN_INTENT, VOUCH_INTENT, PROMOTE_GROUP_ADMIN_INTENT and
    // DEMOTE_GROUP_ADMIN_INTENT are the audit-on-intent rows written
    // on a separate auto-commit connection BEFORE the mutation
    // transaction (security.md §Authorization model: step 8
    // "Audit-log the intent" precedes step 9 "Execute"), with the
    // GRANT_ADMIN_INTENT placement semantics: the row is written only
    // after the caller's permission gate passes and before every
    // execution-semantics check, so unknown-contact, banned-target
    // and no-op probes leave a surviving intent row while
    // non-admin-caller refusals stay audit-silent.
    UNBAN_INTENT,
    VOUCH,
    VOUCH_INTENT,
    INVITE_CREATE,
    INVITE_CREATE_INTENT,
    INVITE_REVOKE,
    INVITE_REVOKE_INTENT,
    INVITE_CONSUME,
    INVITE_BRUTE_FORCE_BREACH,
    PROMOTE_GROUP_ADMIN,
    // Intent counterpart of PROMOTE_GROUP_ADMIN — see the UNBAN_INTENT
    // comment for the shared placement semantics. Covers /promote only;
    // the auto-promote path (GroupAutoPromoteService) has no caller
    // intent to record.
    PROMOTE_GROUP_ADMIN_INTENT,
    DEMOTE_GROUP_ADMIN,
    // Intent counterpart of DEMOTE_GROUP_ADMIN — see the UNBAN_INTENT
    // comment for the shared placement semantics.
    DEMOTE_GROUP_ADMIN_INTENT,
    // D47 group authorization (M1-113). APPROVE_GROUP records the
    // bot-admin transition of groups.approval_status from
    // 'pending'/'rejected' to 'approved'; REJECT_GROUP records the
    // transition to 'rejected'. Distinct from APPROVE_QUARANTINE /
    // REJECT_QUARANTINE (which act on the post quarantine row, not the
    // group) and from PROMOTE_GROUP_ADMIN / DEMOTE_GROUP_ADMIN (which
    // act on the group_membership.is_group_admin role, orthogonal to
    // groups.approval_status). REJECT_GROUP_INTENT is the
    // audit-on-intent row written on the first call of confirm-gated
    // /reject-group per security.md §Authorization model step 8 (same
    // pattern as BAN_INTENT / INVITE_REVOKE_INTENT). /approve-group is
    // not confirm-gated, so no APPROVE_GROUP_INTENT counterpart exists.
    APPROVE_GROUP,
    REJECT_GROUP,
    REJECT_GROUP_INTENT,
    // LIST_GROUPS records the bot-admin-only /list-groups read — a
    // deployment-wide enumeration of every groups row (id,
    // approval_status, activated_by contact id (redacted),
    // member_count, timezone). Mirrors LIST_SOURCES_ALL's role for
    // /list-sources --all: closes the gap between "destructive admin
    // writes audited" and "privileged admin reads not audited" for
    // the §Source URL visibility-shaped disclosure that the groups
    // enumeration produces. Unlike /list-sources, /list-groups has
    // no unprivileged form (every call is admin-only), so the verb
    // carries no _ALL suffix.
    LIST_GROUPS,
    MEMBER_LEFT,
    BOT_REMOVED,
    ADD_SOURCE,
    REMOVE_SOURCE,
    REMOVE_SOURCE_INTENT,
    SOURCE_ENABLE,
    SOURCE_ENABLE_INTENT,
    SOURCE_DISABLE,
    // UNFOLLOW_SOURCE (M1-419) records a per-scope source unsubscribe:
    // the caller scope's source_subscription row is deleted while the
    // global source row is untouched (contrast REMOVE_SOURCE's
    // soft-delete + cascade). Written only on a real deletion — a
    // not-subscribed no-op writes no row. No _INTENT counterpart:
    // /unfollow-source is not confirm-gated (a per-scope unsubscribe is
    // not a deployment-wide destructive act).
    UNFOLLOW_SOURCE,
    LIST_SOURCES_ALL,
    AUDIT_READ,
    QUARANTINE_LIST,
    EXPORT,
    FORGET,
    CHAT_MODE,
    SET_LANG,
    SET_TIMEZONE,
    LLM_OUTPUT_SANITIZED,
    RE_EVAL_RELEASED,
    // DIGEST_ENABLE / DIGEST_DISABLE record a group admin's
    // /digest on|off toggle of groups.digest_enabled (mirroring
    // SOURCE_ENABLE / SOURCE_DISABLE). Written only on an actual
    // state flip — an idempotent no-op writes no row — with
    // target_kind='group' and target_id = the group's id. The digest
    // missed-slot pause carve-out reads the DIGEST_ENABLE rows to
    // derive its window, so the target_kind/target_id convention is
    // load-bearing.
    DIGEST_ENABLE,
    DIGEST_DISABLE,
    DIGEST_RETRY,
    DIGEST_SLOT_MISSED,
    QUARANTINE_TTL_REJECT,
    // Names the EVENT (the boot-time release-on-stage2-failure posture
    // warning), not the config VALUE: the observed
    // infochat.security.release-on-stage2-failure value rides in the row's
    // details_json at the emitting call site, so config evolution lands in
    // JSON without a verb rewrite.
    STARTUP_RELEASE_ON_STAGE2_FAILURE
}
