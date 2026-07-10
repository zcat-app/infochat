package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.core.util.JsonEscaper;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.messaging.ProbationCheck;
import app.zcat.infochat.provider.user.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@code /revoke-admin <contact>} per
 * {@code docs/spec/security.md} §Authorization model (last-admin
 * protection + per-adapter scope), §Per-adapter admin threat profile,
 * and {@code docs/spec/commands.md} §Admin (bot admin).
 *
 * <p>Dispatch sequence:
 * <ol>
 *   <li>DM-only gate — {@link ScopeRef.Group} returns
 *       {@code error.command_dm_only}. Bot-admin commands are
 *       per-adapter and have no group-scope semantic per spec
 *       §Admin (bot admin).</li>
 *   <li>Parse one positional {@code <contact>} argument.</li>
 *   <li>Permission pre-check (spec §Authorization model step 7) —
 *       refusing and NON-LOCKING: resolve the actor by
 *       (adapter, contact_id); absent or non-admin →
 *       {@code error.admin_only}; in probation →
 *       {@code error.probation.blocked.generic}. Mirrors BanCommandHandler's
 *       step-1 admin gate. The step-6a {@code FOR UPDATE} gate stays
 *       authoritative for EXECUTION (M1-046 PERM-ESCAL closure): an
 *       actor revoked between this read and the transaction is
 *       refused in-tx; one granted admin in that window is refused
 *       here once and succeeds on retry — so execution can never
 *       happen without the step-4 intent row. Refusal-before-intent
 *       keeps unauthorized senders from growing the append-only
 *       audit_log.</li>
 *   <li>Audit-on-intent — write the REVOKE_ADMIN_INTENT row on a
 *       separate auto-commit connection (BAN_INTENT pattern) BEFORE
 *       the locking transaction below opens, unconditionally for
 *       every dispatch that passes step 3 and BEFORE any
 *       execution-semantics check: per spec §Authorization model
 *       steps 7→8→9 the intent row covers every permission-passing
 *       dispatch regardless of the execution outcome, so
 *       self-revoke, target-unknown, target-not-admin and
 *       trigger-refused attempts all leave a surviving row, and a
 *       target is_admin flip between this write and the transaction
 *       cannot skip it (M1-151 + M1-173 redteam findings). The
 *       row's target_id is the target's users id when registered, a
 *       synthetic UUID otherwise; target_contact_id carries the
 *       identity either way, and details_json records
 *       target_registered so a synthetic id is distinguishable from
 *       a real-but-since-deleted user id. The pre-transaction
 *       placement is load-bearing: the
 *       {@code audit_log.actor_user_id} FK takes FOR KEY SHARE on
 *       the actor row, which deadlocks against the step-6a FOR
 *       UPDATE if the write happens while that lock is held —
 *       undetectably, because this connection would be waiting in
 *       application code, not in the database. Remaining benign
 *       race: an actor revoked between step 3 and the transaction
 *       leaves a spurious intent row for an attempt the transaction
 *       then refuses.</li>
 *   <li>Self-revoke guard — first execution-semantics check (spec
 *       step 9): if {@code callerContactId.equals(targetContactId)}
 *       (both scoped to the same inbound adapter, so a contact-id
 *       match implies identity), return
 *       {@code error.revoke_admin.cannot_revoke_self}. This is the
 *       first-line UX defense; the V5
 *       {@code trg_last_admin_protection_update} trigger is the
 *       last-line invariant. The trigger has no signal of which
 *       connection issued the UPDATE (M1-008a red-team finding), so
 *       the handler is the load-bearing self-revoke check. Runs
 *       OUTSIDE the tx so a self-revoke never opens a throwaway
 *       transaction; the step-4 intent row already covers the
 *       refused attempt.</li>
 *   <li>Open the transaction ({@code autoCommit=false}). All
 *       authorization-sensitive reads and the audit/UPDATE run
 *       inside this one transaction:
 *     <ol type="a">
 *       <li>Admin gate INSIDE the tx via
 *           {@code SELECT ... FOR UPDATE} on the actor row
 *           (M1-045 VouchCommandHandler precedent; closes the
 *           M1-046 redteam PERM-ESCAL finding). The row lock
 *           serializes a concurrent {@code /revoke-admin} against
 *           this caller. Non-admin / absent → ROLLBACK +
 *           {@code error.admin_only}.</li>
 *       <li>Probation guard (defense-in-depth) — same pattern as
 *           M1-039's in-handler ban check.</li>
 *       <li>Target lookup INSIDE the tx —
 *           {@code (adapter, contact_id)} scoped. Absent → ROLLBACK +
 *           {@code error.contact_not_registered}.</li>
 *       <li>Not-already-admin no-op — ROLLBACK + friendly reply, no
 *           UPDATE, no audit row.</li>
 *       <li>Audit-before-effect: pre-write the REVOKE_ADMIN audit
 *           row BEFORE the UPDATE. If the V5 trigger raises, the
 *           audit INSERT rolls back too.</li>
 *       <li>{@code UPDATE users SET is_admin = FALSE WHERE id = ?}.
 *           The V5 {@code trg_last_admin_protection_update} trigger
 *           raises {@code last_admin_protection: ...} on UPDATEs
 *           that would leave the deployment with zero
 *           {@code is_admin=TRUE AND is_banned=FALSE} rows. The
 *           trigger's dedicated SQLSTATE {@code IC001} (V35,
 *           {@code USING ERRCODE}) is the load-bearing match key;
 *           the handler catches the {@code SQLException} and
 *           surfaces {@code error.revoke_admin.last_admin}.</li>
 *       <li>COMMIT.</li>
 *     </ol>
 *   </li>
 *   <li>Reply {@code reply.revoke_admin.success} with the redacted
 *       target contact id.</li>
 * </ol>
 *
 * <p>The pre-M1-046-redteam dispatch read the actor row OUTSIDE the
 * transaction. That left a TOCTOU window where a concurrent
 * {@code /revoke-admin} against the caller could commit between the
 * admin check and the UPDATE, letting a freshly-demoted admin still
 * complete a revoke against a co-admin. Moving the admin check
 * INSIDE the transaction with {@code FOR UPDATE} on the actor row
 * closes that window. The step-3 pre-check reintroduces an
 * outside-the-tx read, but only ever REFUSES on it — execution
 * authorization still happens exclusively at the in-tx locked read,
 * so the window stays closed.</p>
 *
 * <p>Audit-before-effect transactionally — the REVOKE_ADMIN audit
 * INSERT and the users UPDATE run in one transaction. If the trigger
 * raises, that row rolls back too; the separately-committed
 * REVOKE_ADMIN_INTENT row is what survives, so the audit log still
 * records the refused attempt (spec §Authorization model step 8
 * "Audit-log the intent" precedes step 9 "Execute"). Both rows share
 * one request id, so a successful revoke's intent + effect pair is
 * correlated.</p>
 */
@ApplicationScoped
public class RevokeAdminCommandHandler implements CommandHandler {

    // SQLSTATE raised by the last-admin protection triggers
    // (V35: RAISE ... USING ERRCODE = 'IC001').
    private static final String LAST_ADMIN_SQLSTATE = "IC001";

    private static final String SELECT_TARGET_SQL =
            "SELECT id, contact_id, is_admin, is_banned FROM users WHERE adapter = ? AND contact_id = ?";

    private static final String UPDATE_REVOKE_ADMIN_SQL =
            "UPDATE users SET is_admin = FALSE WHERE id = ?";

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    @Inject
    InboundContext inboundContext;

    @Inject
    AuditLogWriter auditLogWriter;

    @Inject
    ProbationCheck probationCheck;

    @Inject
    UserRepository userRepository;

    @Override
    public String name() {
        return "revoke-admin";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        if (scope instanceof ScopeRef.Group) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_COMMAND_DM_ONLY, inboundContext.effectiveLanguage()));
        }

        String adapter = inboundContext.adapterName();
        String callerContactId = contactIdOf(scope);

        // DM-only convention. Short-circuit with error.admin_only so
        // the step-3 pre-check never sees a null contact id.
        if (callerContactId == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY, inboundContext.effectiveLanguage()));
        }

        // Step 2 — parse positional <contact>.
        String targetContactId = parseTargetContact(rawText);
        if (targetContactId == null) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_USAGE_MISSING_ARGUMENT, inboundContext.effectiveLanguage()),
                    "/revoke-admin <contact>"));
        }

        // Step 3 — permission pre-check (spec §Authorization model
        // step 7), refusing and NON-LOCKING (plain MVCC read; mirrors
        // BanCommandHandler's step-1 admin gate). Refusing here keeps
        // unauthorized senders from growing the append-only audit_log
        // at step 4. The in-tx FOR UPDATE gate (step 6a) stays
        // authoritative for EXECUTION: an actor revoked between this
        // read and the transaction is still refused in-tx (M1-046
        // PERM-ESCAL closure); one granted admin inside that window
        // is refused here once and succeeds on retry — so execution
        // can never happen without the step-4 intent row (M1-173
        // redteam finding 2).
        Optional<UserRow> actorPre = lookupUser(adapter, callerContactId);
        if (actorPre.isEmpty() || !actorPre.get().isAdmin) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY, inboundContext.effectiveLanguage()));
        }
        UserRow actor = actorPre.get();
        if (probationCheck.inProbation(actor.id)) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_PROBATION_BLOCKED_GENERIC, inboundContext.effectiveLanguage()));
        }

        // Step 4 — audit-on-intent (spec step 8) on a separate
        // auto-commit connection (BAN_INTENT pattern), unconditional
        // once step 3 passes and BEFORE every execution-semantics
        // check — self-revoke included — so every permission-passing
        // dispatch leaves a surviving intent row regardless of the
        // execution outcome (M1-151 finding 1 + M1-173 finding 1).
        // Pre-transaction placement is mandatory, not stylistic: the
        // audit_log.actor_user_id FK takes FOR KEY SHARE on the actor
        // row, which deadlocks against executeRevoke's FOR UPDATE
        // admin gate if written while that lock is held (PostgreSQL
        // cannot detect it — this connection would wait in application
        // code, not in the database). The shared requestId correlates
        // the intent row with the REVOKE_ADMIN effect row.
        String requestId = UUID.randomUUID().toString();
        insertIntentAudit(adapter, targetContactId, actor, requestId);

        // Step 5 — self-revoke guard, first execution-semantics check
        // (spec step 9). The (adapter, contact_id) identity is
        // sufficient: actor and target are scoped to the same inbound
        // adapter, so a contact_id match implies identity. Runs
        // OUTSIDE the tx so a self-revoke never opens a throwaway
        // transaction; the step-4 intent row already covers this
        // refused attempt.
        if (callerContactId.equals(targetContactId)) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_REVOKE_ADMIN_CANNOT_REVOKE_SELF, inboundContext.effectiveLanguage()));
        }

        // Step 6 — audit-before-effect transaction. All execution-
        // sensitive reads (authoritative admin gate, probation,
        // target lookup) and the audit/UPDATE run inside this one
        // transaction; the actor row is SELECT ... FOR UPDATE-locked
        // so a concurrent /revoke-admin against the caller
        // serializes (M1-046 redteam PERM-ESCAL closure).
        return executeRevoke(scope, adapter, callerContactId, targetContactId, requestId);
    }

    private OutboundMessage executeRevoke(ScopeRef scope, String adapter,
                                          String callerContactId,
                                          String targetContactId,
                                          String requestId) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Step 6a — admin gate INSIDE the tx via SELECT FOR
                // UPDATE on the actor row, authoritative for
                // execution (the step-3 pre-check already refused
                // other unauthorized callers; this branch fires when
                // the caller was revoked between that read and this
                // lock). The row lock blocks a concurrent
                // /revoke-admin against this caller; the subsequent
                // is_admin read reflects the caller's current state. The trigger-fire path (V5
                // last_admin_protection) requires the target to be
                // the sole qualifying admin, which is unreachable
                // through this handler when the actor itself
                // qualifies — kept as defense-in-depth for the
                // banned-admin-bypass-intake edge case (the only
                // path that drives a single-qualifying-admin target
                // through this handler).
                Optional<UserRow> actorOpt =
                        lookupActorForUpdate(conn, adapter, callerContactId);
                if (actorOpt.isEmpty() || !actorOpt.get().isAdmin) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY, inboundContext.effectiveLanguage()));
                }
                UserRow actor = actorOpt.get();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT set_config('infochat.actor_id', ?, true)")) {
                    ps.setString(1, actor.id.toString());
                    ps.execute();
                }

                // Step 6b — probation guard (defense-in-depth).
                if (probationCheck.inProbation(actor.id)) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_PROBATION_BLOCKED_GENERIC, inboundContext.effectiveLanguage()));
                }

                // Step 6c — target lookup INSIDE the tx.
                Optional<UserRow> targetOpt =
                        lookupTargetInTx(conn, adapter, targetContactId);
                if (targetOpt.isEmpty()) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_CONTACT_NOT_REGISTERED, inboundContext.effectiveLanguage()));
                }
                UserRow target = targetOpt.get();

                // Step 6d — not-admin no-op.
                if (!target.isAdmin) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_REVOKE_ADMIN_NOT_ADMIN, inboundContext.effectiveLanguage()));
                }

                // Step 6e — pre-write REVOKE_ADMIN audit row BEFORE
                // the UPDATE. If the V5 trigger raises, the audit
                // INSERT rolls back with the failed UPDATE; the
                // separately-committed REVOKE_ADMIN_INTENT row from
                // step 4 is what survives.
                insertAudit(conn, AuditAction.REVOKE_ADMIN, TargetKind.USER,
                        target.id.toString(), target.contactId, actor,
                        adapter, requestId, revokeAdminDetailsJson(adapter));

                // Step 6f — UPDATE users SET is_admin = FALSE WHERE id = ?
                try (PreparedStatement ps = conn.prepareStatement(UPDATE_REVOKE_ADMIN_SQL)) {
                    ps.setObject(1, target.id);
                    ps.executeUpdate();
                }

                conn.commit();

                String body = MessageFormat.format(
                        bundleLoader.get(BundleKeys.REPLY_REVOKE_ADMIN_SUCCESS, inboundContext.effectiveLanguage()),
                        ContactIds.redact(target.contactId));
                return reply(scope, body);
            } catch (SQLException e) {
                conn.rollback();
                // The last-admin protection triggers raise USING
                // ERRCODE 'IC001' (V35); the SQLSTATE is the
                // load-bearing match key — message text is free to
                // reword.
                if (LAST_ADMIN_SQLSTATE.equals(e.getSQLState())) {
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_REVOKE_ADMIN_LAST_ADMIN, inboundContext.effectiveLanguage()));
                }
                throw new IllegalStateException(
                        "RevokeAdminCommandHandler.executeRevoke failed for adapter="
                                + adapter + " contact_id="
                                + ContactIds.redact(targetContactId), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "RevokeAdminCommandHandler.executeRevoke connection failed for adapter="
                            + adapter + " contact_id="
                            + ContactIds.redact(targetContactId), e);
        }
    }

    /**
     * Non-locking lookup used by the step-3 permission pre-check and
     * the step-4 intent row's target_id resolution. Plain MVCC SELECT
     * — takes no row lock, so it can never participate in the
     * FK-vs-FOR-UPDATE deadlock the pre-transaction intent placement
     * exists to avoid.
     */
    private Optional<UserRow> lookupUser(String adapter, String contactId) {
        return userRepository.findByAdapterAndContactId(adapter, contactId)
                .map(u -> new UserRow(u.id(), u.contactId(), u.isAdmin(), u.isBanned()));
    }

    private Optional<UserRow> lookupActorForUpdate(Connection conn,
                                                   String adapter,
                                                   String contactId) throws SQLException {
        return userRepository.findByAdapterAndContactIdForUpdate(conn, adapter, contactId)
                .map(u -> new UserRow(u.id(), u.contactId(), u.isAdmin(), u.isBanned()));
    }

    private Optional<UserRow> lookupTargetInTx(Connection conn,
                                               String adapter,
                                               String contactId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_TARGET_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                UUID id = (UUID) rs.getObject("id");
                String resolvedContactId = rs.getString("contact_id");
                boolean isAdmin = rs.getBoolean("is_admin");
                boolean isBanned = rs.getBoolean("is_banned");
                return Optional.of(new UserRow(id, resolvedContactId, isAdmin, isBanned));
            }
        }
    }

    /**
     * Write the REVOKE_ADMIN_INTENT row on its own auto-commit
     * connection (BAN_INTENT pattern). The target lookup here resolves
     * ONLY the row's target_id — the target's users id when registered,
     * a synthetic UUID otherwise (target_contact_id carries the
     * resolved identity either way) — and the details_json
     * target_registered flag that makes a synthetic id distinguishable
     * from a real-but-since-deleted user id; target state never gates
     * the write. A failure here surfaces before the mutation runs —
     * preferable to a destructive UPDATE that lands no intent trail.
     */
    private void insertIntentAudit(String adapter, String targetContactId, UserRow actor,
                                   String requestId) {
        Optional<UserRow> targetPre = lookupUser(adapter, targetContactId);
        UUID targetUserIdForIntent = targetPre.map(u -> u.id).orElse(UUID.randomUUID());
        try (Connection conn = dataSource.getConnection()) {
            insertAudit(conn, AuditAction.REVOKE_ADMIN_INTENT, TargetKind.USER,
                    targetUserIdForIntent.toString(), targetContactId, actor,
                    adapter, requestId,
                    revokeAdminIntentDetailsJson(adapter, targetPre.isPresent()));
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "RevokeAdminCommandHandler intent-audit write failed for adapter="
                            + adapter + " contact_id="
                            + ContactIds.redact(targetContactId), e);
        }
    }

    private void insertAudit(Connection conn,
                             AuditAction action,
                             TargetKind targetKind,
                             String targetId,
                             String targetContactId,
                             UserRow actor,
                             String adapter,
                             String requestId,
                             String detailsJson) throws SQLException {
        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                .actorUserId(actor.id)
                .actorContactId(actor.contactId)
                .actorAdapter(adapter)
                .action(action)
                .targetKind(targetKind)
                .targetId(targetId)
                .targetContactId(targetContactId)
                .requestId(requestId)
                .detailsJson(detailsJson)
                .build();
        auditLogWriter.write(conn, row);
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    private static @Nullable String contactIdOf(ScopeRef scope) {
        return scope instanceof ScopeRef.Dm dm ? dm.contactId() : null;
    }

    private static @Nullable String parseTargetContact(String rawText) {
        String[] split = rawText.trim().split("\\s+", 2);
        if (split.length < 2) {
            return null;
        }
        String remainder = split[1].trim();
        if (remainder.isEmpty()) {
            return null;
        }
        List<String> tokens = List.of(remainder.split("\\s+"));
        return tokens.get(0);
    }

    private static String revokeAdminDetailsJson(String adapter) {
        return "{\"target_adapter\":" + quoteJsonString(adapter) + "}";
    }

    /**
     * Intent rows additionally record whether the target had a users
     * row at intent time: {@code target_registered=false} marks the
     * row's target_id as synthetic. Effect rows omit the key — their
     * targets are registered by construction (the in-tx lookup
     * succeeded).
     */
    private static String revokeAdminIntentDetailsJson(String adapter, boolean targetRegistered) {
        return "{\"target_adapter\":" + quoteJsonString(adapter)
                + ",\"target_registered\":" + targetRegistered + "}";
    }

    private static String quoteJsonString(String s) {
        return "\"" + JsonEscaper.escape(s) + "\"";
    }

    private record UserRow(UUID id, String contactId, boolean isAdmin, boolean isBanned) {}
}
