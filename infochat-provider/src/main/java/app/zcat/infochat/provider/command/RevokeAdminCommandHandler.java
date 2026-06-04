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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 *   <li>Self-revoke guard — if {@code callerContactId.equals(
 *       targetContactId)} (both scoped to the same inbound adapter,
 *       so a contact-id match implies identity), return
 *       {@code error.revoke_admin.cannot_revoke_self}. This is the
 *       first-line UX defense; the V5
 *       {@code trg_last_admin_protection_update} trigger is the
 *       last-line invariant. The trigger has no signal of which
 *       connection issued the UPDATE (M1-008a red-team finding), so
 *       the handler is the load-bearing self-revoke check. The check
 *       runs OUTSIDE the tx so a self-revoke against an unknown
 *       contact surfaces the self-revoke error rather than opening a
 *       throwaway transaction.</li>
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
 *           literal substring is the load-bearing match key; the
 *           handler catches the {@code SQLException} and surfaces
 *           {@code error.revoke_admin.last_admin}.</li>
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
 * closes that window.</p>
 *
 * <p>Audit-before-effect transactionally — the audit INSERT and the
 * users UPDATE run in one transaction. If the trigger raises, the
 * audit INSERT rolls back too: the audit log carries no row for the
 * failed attempt. The spec's audit-before-effect rule is about the
 * audit row preceding the side effect WITHIN THE SAME TRANSACTION,
 * not about audit rows surviving trigger-raised rollbacks.</p>
 */
@ApplicationScoped
public class RevokeAdminCommandHandler implements CommandHandler {

    // FOR UPDATE locks the actor row for the rest of the transaction
    // so a concurrent /revoke-admin UPDATE on the same row serializes
    // against this SELECT (M1-046 redteam PERM-ESCAL closure).
    private static final String SELECT_ACTOR_FOR_UPDATE_SQL =
            "SELECT id, contact_id, is_admin, is_banned FROM users "
                    + "WHERE adapter = ? AND contact_id = ? FOR UPDATE";

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

    @Override
    public String name() {
        return "revoke-admin";
    }

    @Override
    public OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
        if (scope instanceof ScopeRef.Group) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_COMMAND_DM_ONLY));
        }

        String adapter = inboundContext.adapterName();
        String callerContactId = contactIdOf(scope);

        // DM-only convention. Short-circuit with error.admin_only so
        // we do not open a transaction just to immediately roll it
        // back when lookupActorForUpdate(null) inside the tx returns
        // empty.
        if (callerContactId == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
        }

        // Step 2 — parse positional <contact>.
        String targetContactId = parseTargetContact(rawText);
        if (targetContactId == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
        }

        // Step 3 — self-revoke guard (first-line UX defense). The
        // (adapter, contact_id) identity is sufficient: actor and
        // target are scoped to the same inbound adapter, so a
        // contact_id match implies identity. The check runs BEFORE
        // opening the transaction so a self-revoke surfaces the
        // self-revoke error without a throwaway tx.
        if (callerContactId.equals(targetContactId)) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_REVOKE_ADMIN_CANNOT_REVOKE_SELF));
        }

        // Step 4 — audit-before-effect transaction. All
        // authorization-sensitive reads (admin gate, probation,
        // target lookup) and the audit/UPDATE run inside this one
        // transaction; the actor row is SELECT ... FOR UPDATE-locked
        // so a concurrent /revoke-admin against the caller
        // serializes (M1-046 redteam PERM-ESCAL closure).
        return executeRevoke(scope, adapter, callerContactId, targetContactId);
    }

    private OutboundMessage executeRevoke(ScopeRef scope, String adapter,
                                          String callerContactId,
                                          String targetContactId) {
        String requestId = UUID.randomUUID().toString();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Step 4a — admin gate INSIDE the tx via SELECT FOR
                // UPDATE on the actor row. The row lock blocks a
                // concurrent /revoke-admin against this caller; the
                // subsequent is_admin read reflects the caller's
                // current state. The trigger-fire path (V5
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
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
                }
                UserRow actor = actorOpt.get();
                try (Statement st = conn.createStatement()) {
                    st.execute("SET LOCAL infochat.actor_id = '" + actor.id + "'");
                }

                // Step 4b — probation guard (defense-in-depth).
                if (probationCheck.inProbation(actor.id)) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_PROBATION_BLOCKED));
                }

                // Step 4c — target lookup INSIDE the tx.
                Optional<UserRow> targetOpt =
                        lookupTargetInTx(conn, adapter, targetContactId);
                if (targetOpt.isEmpty()) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_CONTACT_NOT_REGISTERED));
                }
                UserRow target = targetOpt.get();

                // Step 4d — not-admin no-op.
                if (!target.isAdmin) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_REVOKE_ADMIN_NOT_ADMIN));
                }

                // Step 4e — pre-write REVOKE_ADMIN audit row BEFORE
                // the UPDATE. If the V5 trigger raises, the audit
                // INSERT rolls back with the failed UPDATE.
                insertAudit(conn, AuditAction.REVOKE_ADMIN, "user",
                        target.id.toString(), target.contactId, actor,
                        adapter, requestId, revokeAdminDetailsJson(adapter));

                // Step 4f — UPDATE users SET is_admin = FALSE WHERE id = ?
                try (PreparedStatement ps = conn.prepareStatement(UPDATE_REVOKE_ADMIN_SQL)) {
                    ps.setObject(1, target.id);
                    ps.executeUpdate();
                }

                conn.commit();

                String body = MessageFormat.format(
                        bundleLoader.get(BundleKeys.REPLY_REVOKE_ADMIN_SUCCESS),
                        ContactIds.redact(target.contactId));
                return reply(scope, body);
            } catch (SQLException e) {
                conn.rollback();
                // V5 trg_last_admin_protection_update raises
                // RAISE EXCEPTION 'last_admin_protection: cannot
                // leave the deployment with zero bot admins'. The
                // literal substring is the load-bearing match key
                // (spec-pinned in V5).
                if (e.getMessage() != null && e.getMessage().contains("last_admin_protection")) {
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_REVOKE_ADMIN_LAST_ADMIN));
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

    private Optional<UserRow> lookupActorForUpdate(Connection conn,
                                                   String adapter,
                                                   String contactId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_ACTOR_FOR_UPDATE_SQL)) {
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

    private static String quoteJsonString(String s) {
        return "\"" + JsonEscaper.escape(s) + "\"";
    }

    private record UserRow(UUID id, String contactId, boolean isAdmin, boolean isBanned) {}
}
