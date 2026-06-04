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
 * {@code /revoke-admin}'s UPDATE until this transaction commits).</p>
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

    // FOR UPDATE locks the actor row for the rest of the transaction
    // so a concurrent /revoke-admin UPDATE on the same row serializes
    // against this SELECT. If /revoke-admin holds the row lock, this
    // SELECT blocks until /revoke-admin COMMITs; the subsequent
    // is_admin read then reflects the post-revoke state and the
    // handler short-circuits with error.admin_only. Closes the
    // M1-046 redteam PERM-ESCAL finding (actor-side TOCTOU).
    private static final String SELECT_ACTOR_FOR_UPDATE_SQL =
            "SELECT id, contact_id, is_admin, is_banned FROM users "
                    + "WHERE adapter = ? AND contact_id = ? FOR UPDATE";

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

    @Override
    public String name() {
        return "grant-admin";
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

        // Step 2 — parse positional <contact>. Missing → fall back
        // to error.admin_only (the spec catalogue assigns no
        // friendly error to "no positional arg"; the same fallback
        // shape as BanCommandHandler). Fail fast before opening the
        // transaction.
        String targetContactId = parseTargetContact(rawText);
        if (targetContactId == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
        }

        // Step 3 — audit-before-effect transaction. All
        // authorization-sensitive reads (admin gate, probation,
        // target lookup) and the audit/UPDATE run inside this one
        // transaction so a concurrent /revoke-admin against the
        // caller serializes on the FOR UPDATE row lock on the actor
        // row (M1-046 redteam PERM-ESCAL closure).
        return executeGrant(scope, adapter, callerContactId, targetContactId);
    }

    private OutboundMessage executeGrant(ScopeRef scope, String adapter,
                                         String callerContactId,
                                         String targetContactId) {
        String requestId = UUID.randomUUID().toString();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Step 3a — admin gate INSIDE the tx via SELECT FOR
                // UPDATE on the actor row. The lock blocks any
                // concurrent /revoke-admin UPDATE on this row until
                // the tx commits; the is_admin read therefore reflects
                // the actor's current state, not a stale snapshot.
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

                // Step 3b — probation guard (defense-in-depth).
                // M1-045's intake-side step-5 gate is the primary
                // defense; this check survives future changes that
                // might decouple probation from is_admin.
                if (probationCheck.inProbation(actor.id)) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_PROBATION_BLOCKED));
                }

                // Step 3c — target lookup, inbound-adapter-scoped,
                // INSIDE the tx for snapshot consistency with the
                // actor read.
                Optional<UserRow> targetOpt =
                        lookupTargetInTx(conn, adapter, targetContactId);
                if (targetOpt.isEmpty()) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_CONTACT_NOT_REGISTERED));
                }
                UserRow target = targetOpt.get();

                // Step 3d — banned-target reject.
                if (target.isBanned) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_GRANT_ADMIN_BANNED_TARGET));
                }

                // Step 3e — already-admin no-op.
                if (target.isAdmin) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_GRANT_ADMIN_ALREADY_ADMIN));
                }

                // Step 3f — pre-write GRANT_ADMIN audit row BEFORE
                // the UPDATE. Invariant 7 (audit-before-effect): if
                // the UPDATE raises, the audit row's INSERT rolls
                // back too.
                insertAudit(conn, AuditAction.GRANT_ADMIN, "user",
                        target.id.toString(), target.contactId, actor,
                        adapter, requestId, grantAdminDetailsJson(adapter));

                // Step 3g — UPDATE users SET is_admin = TRUE WHERE id = ?
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

    private static String quoteJsonString(String s) {
        return "\"" + JsonEscaper.escape(s) + "\"";
    }

    /** Minimal in-memory representation of a users row the handler needs. */
    private record UserRow(UUID id, String contactId, boolean isAdmin, boolean isBanned) {}
}
