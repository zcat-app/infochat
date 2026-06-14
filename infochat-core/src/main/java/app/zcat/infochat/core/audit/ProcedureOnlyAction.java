package app.zcat.infochat.core.audit;

/**
 * Audit verbs written ONLY by SECURITY DEFINER stored procedures,
 * directly in SQL inside the procedure body — never through
 * {@link AuditLogWriter}. Splitting them out of {@link AuditAction}
 * makes the "only SQL writes these" contract compiler-enforced:
 * because {@link AuditLogWriter#write} takes an {@link AuditAction}
 * (via {@link RedactionHook.AuditRow}), no Java caller can produce an
 * audit row carrying one of these verbs, which is what keeps the
 * audit-before-effect guarantee from being bypassable by a caller
 * that skipped the procedure.
 *
 * <ul>
 *   <li>{@link #UNBAN_PREBAN_DELETE} — written by
 *       {@code delete_preban_user} (V5).</li>
 *   <li>{@link #APPROVE_QUARANTINE} — written by
 *       {@code approve_quarantine} (V10). Acts on the post
 *       quarantine row, distinct from {@code APPROVE_GROUP} (group
 *       approval_status) and {@code PROMOTE_GROUP_ADMIN}
 *       (group_membership role).</li>
 *   <li>{@link #REJECT_QUARANTINE} — written by
 *       {@code reject_quarantine} (V10).</li>
 *   <li>{@link #D47_GROUP_ONLY_PREBAN_CONVERSION} — written by the
 *       V27 migration: one row recording the bulk group_only →
 *       preban conversion when the group_only registration path was
 *       removed.</li>
 * </ul>
 *
 * <p>These verbs remain part of the {@link AuditVerb} closed set so
 * read paths (the {@code /audit --action} filter, schema
 * cross-checks) can still reference and resolve them by one symbol.</p>
 */
public enum ProcedureOnlyAction implements AuditVerb {
    UNBAN_PREBAN_DELETE,
    APPROVE_QUARANTINE,
    REJECT_QUARANTINE,
    D47_GROUP_ONLY_PREBAN_CONVERSION
}
