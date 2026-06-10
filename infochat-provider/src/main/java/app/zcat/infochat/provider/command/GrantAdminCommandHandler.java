package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
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
 * Implements {@code /grant-admin <contact>} per
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
 *   <li>Parse one positional {@code <contact>} argument — fail fast
 *       before opening the transaction.</li>
 *   <li>Permission pre-check (spec §Authorization model step 7) —
 *       refusing and NON-LOCKING: resolve the actor by
 *       (adapter, contact_id); absent or non-admin →
 *       {@code error.admin_only}; in probation →
 *       {@code error.probation_blocked}. Mirrors
 *       RevokeAdminCommandHandler's step-3 gate. The step-5a
 *       {@code FOR UPDATE} gate stays authoritative for EXECUTION
 *       (M1-046 PERM-ESCAL closure): an actor revoked between this
 *       read and the transaction is refused in-tx; one granted admin
 *       in that window is refused here once and succeeds on retry —
 *       so execution can never happen without the step-4 intent row.
 *       Refusal-before-intent keeps unauthorized senders from growing
 *       the append-only audit_log.</li>
 *   <li>Audit-on-intent — write the GRANT_ADMIN_INTENT row on a
 *       separate auto-commit connection (BAN_INTENT pattern) BEFORE
 *       the locking transaction below opens, unconditionally for
 *       every dispatch that passes step 3 and BEFORE any
 *       execution-semantics check: per spec §Authorization model
 *       steps 7→8→9 the intent row covers every permission-passing
 *       dispatch regardless of the execution outcome, so the
 *       unknown-contact, banned-target and already-admin refusal
 *       legs all leave a surviving row — without it {@code
 *       /grant-admin} lets a bot admin enumerate registration state,
 *       ban state and the admin bit with zero audit trace (the same
 *       probe-enumeration AUDIT-EVASION class as the M1-151/M1-173
 *       {@code /revoke-admin} findings). The row's target_id is the
 *       target's users id when registered, a synthetic UUID
 *       otherwise; target_contact_id carries the identity either
 *       way, and details_json records target_registered so a
 *       synthetic id is distinguishable from a
 *       real-but-since-deleted user id. The pre-transaction
 *       placement is load-bearing: the
 *       {@code audit_log.actor_user_id} FK takes FOR KEY SHARE on
 *       the actor row, which deadlocks against the step-5a FOR
 *       UPDATE if the write happens while that lock is held —
 *       undetectably, because this connection would be waiting in
 *       application code, not in the database. Remaining benign
 *       race: an actor revoked between step 3 and the transaction
 *       leaves a spurious intent row for an attempt the transaction
 *       then refuses.</li>
 *   <li>Open the transaction ({@code autoCommit=false}). All
 *       authorization-sensitive reads and the audit/UPDATE run
 *       inside this one transaction:
 *     <ol type="a">
 *       <li>Admin gate INSIDE the tx via
 *           {@code SELECT ... FOR UPDATE} on the actor row
 *           (M1-045 VouchCommandHandler precedent). The row lock
 *           serializes a concurrent {@code /revoke-admin} against the
 *           caller: if {@code /revoke-admin} holds the row lock, this
 *           {@code SELECT} blocks until {@code /revoke-admin} commits;
 *           the subsequent {@code is_admin} read then reflects the
 *           post-revoke state and the handler short-circuits with
 *           {@code error.admin_only}.</li>
 *       <li>Probation guard (defense-in-depth) — same pattern as
 *           M1-039's in-handler ban check that survives M1-044b's
 *           intake-side ban gate.</li>
 *       <li>Target lookup INSIDE the tx — {@code (adapter, contact_id)}
 *           scoped per spec §Authorization model. Absent → ROLLBACK +
 *           {@code error.contact_not_registered}.</li>
 *       <li>Banned-target reject — granting admin to a banned user is
 *           incoherent; ROLLBACK + {@code error.grant_admin.banned_target}.</li>
 *       <li>Already-admin no-op — ROLLBACK + friendly reply, no UPDATE,
 *           no audit row.</li>
 *       <li>Audit-before-effect: pre-write the GRANT_ADMIN audit row
 *           BEFORE the UPDATE per Invariant 7. If the UPDATE raises
 *           (e.g., a future trigger), the audit row's INSERT rolls
 *           back too.</li>
 *       <li>{@code UPDATE users SET is_admin = TRUE WHERE id = ?},
 *           then COMMIT.</li>
 *     </ol>
 *   </li>
 *   <li>Reply {@code reply.grant_admin.success} with the redacted
 *       target contact id.</li>
 * </ol>
 *
 * <p>The pre-M1-046-redteam dispatch read the actor row OUTSIDE the
 * transaction (in {@code lookupUser()} using its own short-lived
 * connection). That left a TOCTOU window between the admin check and
 * the row mutation: a concurrent {@code /revoke-admin} against the
 * caller could commit between the check and the mutation, letting a
 * freshly-demoted admin still complete a grant. Moving the admin
 * check INSIDE the transaction with {@code FOR UPDATE} on the actor
 * row closes that window (the row lock blocks the concurrent
 * {@code /revoke-admin}'s UPDATE until this transaction commits).
 * The step-3 pre-check reintroduces an outside-the-tx read, but only
 * ever REFUSES on it — execution authorization still happens
 * exclusively at the in-tx locked read, so the window stays
 * closed.</p>
 *
 * <p>Audit-before-effect transactionally — the GRANT_ADMIN audit
 * INSERT and the users UPDATE run in one transaction; the
 * separately-committed GRANT_ADMIN_INTENT row from step 4 is what
 * survives the rolled-back refusal legs (spec §Authorization model
 * step 8 "Audit-log the intent" precedes step 9 "Execute"). Both
 * rows share one request id, so a successful grant's intent + effect
 * pair is correlated.</p>
 *
 * <p>Per spec §Per-adapter admin threat profile: the
 * {@code <contact>} argument resolves against the INBOUND adapter
 * ({@link InboundContext#adapterName()}). A bot admin on SimpleX
 * cannot grant admin on Signal without running the command from
 * Signal. This bounds the blast radius of a single-adapter
 * compromise.</p>
 */
@ApplicationScoped
public class GrantAdminCommandHandler implements CommandHandler {

    // Target lookup runs INSIDE the same tx but without FOR UPDATE.
    // The target row's mutation is bounded by uniqueness on (adapter,
    // contact_id) plus the V5 trigger (no per-row lock needed beyond
    // what the UPDATE statement itself takes).
    private static final String SELECT_TARGET_SQL =
            "SELECT id, contact_id, is_admin, is_banned FROM users WHERE adapter = ? AND contact_id = ?";

    private static final String UPDATE_GRANT_ADMIN_SQL =
            "UPDATE users SET is_admin = TRUE WHERE id = ?";

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
        return "grant-admin";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        if (scope instanceof ScopeRef.Group) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_COMMAND_DM_ONLY));
        }

        String adapter = inboundContext.adapterName();
        String callerContactId = contactIdOf(scope);

        // DM-only convention. Short-circuit with error.admin_only so
        // the step-3 pre-check never sees a null contact id.
        if (callerContactId == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
        }

        // Step 2 — parse positional <contact>. Fail fast before
        // opening the transaction.
        String targetContactId = parseTargetContact(rawText);
        if (targetContactId == null) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_USAGE_MISSING_ARGUMENT),
                    "/grant-admin <contact>"));
        }

        // Step 3 — permission pre-check (spec §Authorization model
        // step 7), refusing and NON-LOCKING (plain MVCC read; mirrors
        // RevokeAdminCommandHandler's step-3 gate). Refusing here
        // keeps unauthorized senders from growing the append-only
        // audit_log at step 4. The in-tx FOR UPDATE gate (step 5a)
        // stays authoritative for EXECUTION: an actor revoked between
        // this read and the transaction is still refused in-tx
        // (M1-046 PERM-ESCAL closure); one granted admin inside that
        // window is refused here once and succeeds on retry — so
        // execution can never happen without the step-4 intent row.
        Optional<UserRow> actorPre = lookupUser(adapter, callerContactId);
        if (actorPre.isEmpty() || !actorPre.get().isAdmin) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
        }
        UserRow actor = actorPre.get();
        if (probationCheck.inProbation(actor.id)) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_PROBATION_BLOCKED));
        }

        // Step 4 — audit-on-intent (spec step 8) on a separate
        // auto-commit connection (BAN_INTENT pattern), unconditional
        // once step 3 passes and BEFORE every execution-semantics
        // check, so the unknown-contact, banned-target and
        // already-admin probes all leave a surviving intent row
        // regardless of the execution outcome (the M1-151/M1-173
        // /revoke-admin AUDIT-EVASION class on the mirror command).
        // Pre-transaction placement is mandatory, not stylistic: the
        // audit_log.actor_user_id FK takes FOR KEY SHARE on the actor
        // row, which deadlocks against executeGrant's FOR UPDATE
        // admin gate if written while that lock is held (PostgreSQL
        // cannot detect it — this connection would wait in application
        // code, not in the database). The shared requestId correlates
        // the intent row with the GRANT_ADMIN effect row.
        String requestId = UUID.randomUUID().toString();
        insertIntentAudit(adapter, targetContactId, actor, requestId);

        // Step 5 — audit-before-effect transaction. All execution-
        // sensitive reads (authoritative admin gate, probation,
        // target lookup) and the audit/UPDATE run inside this one
        // transaction so a concurrent /revoke-admin against the
        // caller serializes on the FOR UPDATE row lock on the actor
        // row (M1-046 redteam PERM-ESCAL closure).
        return executeGrant(scope, adapter, callerContactId, targetContactId, requestId);
    }

    private OutboundMessage executeGrant(ScopeRef scope, String adapter,
                                         String callerContactId,
                                         String targetContactId,
                                         String requestId) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Step 5a — admin gate INSIDE the tx via SELECT FOR
                // UPDATE on the actor row, authoritative for
                // execution (the step-3 pre-check already refused
                // other unauthorized callers; this branch fires when
                // the caller was revoked between that read and this
                // lock). The lock blocks any concurrent /revoke-admin
                // UPDATE on this row until the tx commits; the
                // is_admin read therefore reflects the actor's
                // current state, not a stale snapshot.
                Optional<UserRow> actorOpt =
                        lookupActorForUpdate(conn, adapter, callerContactId);
                if (actorOpt.isEmpty() || !actorOpt.get().isAdmin) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
                }
                UserRow actor = actorOpt.get();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT set_config('infochat.actor_id', ?, true)")) {
                    ps.setString(1, actor.id.toString());
                    ps.execute();
                }

                // Step 5b — probation guard (defense-in-depth).
                // M1-045's intake-side step-5 gate is the primary
                // defense; this check survives future changes that
                // might decouple probation from is_admin.
                if (probationCheck.inProbation(actor.id)) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_PROBATION_BLOCKED));
                }

                // Step 5c — target lookup, inbound-adapter-scoped,
                // INSIDE the tx for snapshot consistency with the
                // actor read.
                Optional<UserRow> targetOpt =
                        lookupTargetInTx(conn, adapter, targetContactId);
                if (targetOpt.isEmpty()) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_CONTACT_NOT_REGISTERED));
                }
                UserRow target = targetOpt.get();

                // Step 5d — banned-target reject.
                if (target.isBanned) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_GRANT_ADMIN_BANNED_TARGET));
                }

                // Step 5e — already-admin no-op.
                if (target.isAdmin) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_GRANT_ADMIN_ALREADY_ADMIN));
                }

                // Step 5f — pre-write GRANT_ADMIN audit row BEFORE
                // the UPDATE. Invariant 7 (audit-before-effect): if
                // the UPDATE raises, the audit row's INSERT rolls
                // back too; the separately-committed
                // GRANT_ADMIN_INTENT row from step 4 is what
                // survives.
                insertAudit(conn, AuditAction.GRANT_ADMIN, "user",
                        target.id.toString(), target.contactId, actor,
                        adapter, requestId, grantAdminDetailsJson(adapter));

                // Step 5g — UPDATE users SET is_admin = TRUE WHERE id = ?
                try (PreparedStatement ps = conn.prepareStatement(UPDATE_GRANT_ADMIN_SQL)) {
                    ps.setObject(1, target.id);
                    ps.executeUpdate();
                }

                conn.commit();

                String body = MessageFormat.format(
                        bundleLoader.get(BundleKeys.REPLY_GRANT_ADMIN_SUCCESS),
                        ContactIds.redact(target.contactId));
                return reply(scope, body);
            } catch (SQLException e) {
                conn.rollback();
                // Redact the contact id in the wrapping message per
                // spec §Secrets handling. The SQLException cause is
                // preserved.
                throw new IllegalStateException(
                        "GrantAdminCommandHandler.executeGrant failed for adapter="
                                + adapter + " contact_id="
                                + ContactIds.redact(targetContactId), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "GrantAdminCommandHandler.executeGrant connection failed for adapter="
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
     * Write the GRANT_ADMIN_INTENT row on its own auto-commit
     * connection (BAN_INTENT pattern). The target lookup here resolves
     * ONLY the row's target_id — the target's users id when registered,
     * a synthetic UUID otherwise (target_contact_id carries the
     * resolved identity either way) — and the details_json
     * target_registered flag that makes a synthetic id distinguishable
     * from a real-but-since-deleted user id; target state never gates
     * the write. A failure here surfaces before the mutation runs —
     * preferable to a privilege-granting UPDATE that lands no intent
     * trail.
     */
    private void insertIntentAudit(String adapter, String targetContactId, UserRow actor,
                                   String requestId) {
        Optional<UserRow> targetPre = lookupUser(adapter, targetContactId);
        UUID targetUserIdForIntent = targetPre.map(u -> u.id).orElse(UUID.randomUUID());
        try (Connection conn = dataSource.getConnection()) {
            insertAudit(conn, AuditAction.GRANT_ADMIN_INTENT, "user",
                    targetUserIdForIntent.toString(), targetContactId, actor,
                    adapter, requestId,
                    grantAdminIntentDetailsJson(adapter, targetPre.isPresent()));
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "GrantAdminCommandHandler intent-audit write failed for adapter="
                            + adapter + " contact_id="
                            + ContactIds.redact(targetContactId), e);
        }
    }

    private void insertAudit(Connection conn,
                             AuditAction action,
                             String targetKind,
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

    /**
     * Parse the first positional argument from {@code /grant-admin
     * <contact>}. Returns {@code null} when no positional arg is
     * present.
     */
    private static @Nullable String parseTargetContact(String rawText) {
        String[] split = rawText.trim().split("\\s+", 2);
        if (split.length < 2) {
            return null;
        }
        String remainder = split[1].trim();
        if (remainder.isEmpty()) {
            return null;
        }
        // First whitespace-delimited token is the contact id.
        List<String> tokens = List.of(remainder.split("\\s+"));
        return tokens.get(0);
    }

    /**
     * The GRANT_ADMIN audit row's {@code details_json} carries the
     * target adapter — same as the inbound adapter under the per-
     * adapter scoping rule, but recorded explicitly so future
     * cross-adapter audit-log readers don't have to infer it from
     * the actor_adapter column.
     */
    private static String grantAdminDetailsJson(String adapter) {
        return "{\"target_adapter\":" + quoteJsonString(adapter) + "}";
    }

    /**
     * Intent rows additionally record whether the target had a users
     * row at intent time: {@code target_registered=false} marks the
     * row's target_id as synthetic. Effect rows omit the key — their
     * targets are registered by construction (the in-tx lookup
     * succeeded).
     */
    private static String grantAdminIntentDetailsJson(String adapter, boolean targetRegistered) {
        return "{\"target_adapter\":" + quoteJsonString(adapter)
                + ",\"target_registered\":" + targetRegistered + "}";
    }

    private static String quoteJsonString(String s) {
        return "\"" + JsonEscaper.escape(s) + "\"";
    }

    /** Minimal in-memory representation of a users row the handler needs. */
    private record UserRow(UUID id, String contactId, boolean isAdmin, boolean isBanned) {}
}
