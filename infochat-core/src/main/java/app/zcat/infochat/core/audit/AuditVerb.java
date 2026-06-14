package app.zcat.infochat.core.audit;

/**
 * Sealed supertype of the two {@code audit_log.action} verb
 * catalogues. The verb name (the enum's {@link #name()}) is the
 * TEXT wire value written to {@code audit_log.action}; the closure
 * stays application-layer (V5 §2.1.8 — no SQL CHECK constraint).
 *
 * <p>The split that this interface heads moves the "who may write
 * this verb" contract from comment-and-vigilance to the compiler:</p>
 * <ul>
 *   <li>{@link AuditAction} — application-writable verbs.
 *       {@link AuditLogWriter#write} accepts only {@link AuditAction}
 *       (via {@link RedactionHook.AuditRow}), so a Java caller
 *       physically cannot route a procedure-only verb through the
 *       writer.</li>
 *   <li>{@link ProcedureOnlyAction} — verbs that ONLY the SECURITY
 *       DEFINER stored procedures write, directly in SQL. No Java
 *       call site may construct an audit row with one of these, which
 *       is exactly the audit-before-effect guarantee: an
 *       {@code APPROVE_QUARANTINE} row can be produced only by the
 *       procedure that performs the approval, never by an arbitrary
 *       Java caller that skipped the procedure.</li>
 * </ul>
 *
 * <p>Read paths that must enumerate or resolve EVERY verb regardless
 * of who writes it — the {@code /audit --action} filter, schema
 * cross-checks — use {@link #values()} / {@link #valueOf(String)}
 * here rather than either concrete enum.</p>
 */
public sealed interface AuditVerb permits AuditAction, ProcedureOnlyAction {

    /**
     * The {@code audit_log.action} TEXT wire value. Both permitted
     * subtypes are enums, so this is satisfied by {@code Enum.name()}.
     */
    String name();

    /**
     * Every verb across both catalogues, application-writable first.
     * The read-path complement to each enum's own {@code values()}.
     */
    static AuditVerb[] values() {
        AuditAction[] app = AuditAction.values();
        ProcedureOnlyAction[] procedureOnly = ProcedureOnlyAction.values();
        AuditVerb[] all = new AuditVerb[app.length + procedureOnly.length];
        System.arraycopy(app, 0, all, 0, app.length);
        System.arraycopy(procedureOnly, 0, all, app.length, procedureOnly.length);
        return all;
    }

    /**
     * Resolve a verb by its wire name across both catalogues. Throws
     * {@link IllegalArgumentException} for an unknown name, mirroring
     * {@code Enum.valueOf} so the {@code /audit} parse boundary keeps
     * its existing catch-and-render behaviour.
     */
    static AuditVerb valueOf(String name) {
        for (AuditVerb verb : values()) {
            if (verb.name().equals(name)) {
                return verb;
            }
        }
        throw new IllegalArgumentException("No AuditVerb named " + name);
    }
}
