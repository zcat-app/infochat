package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.NonNull;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@code /ban <contact> [--reason "..."]} per
 * {@code docs/spec/security.md} §User ban and
 * {@code docs/spec/commands.md} §Admin (bot admin).
 *
 * <p>Dispatch sequence (acceptance item 1 in M1-044c):
 * <ol>
 *   <li>Admin gate — resolve the actor by {@code (adapter, contact_id)};
 *       non-admin → {@code error.admin_only}, no DB write.</li>
 *   <li>Parse the positional {@code <contact>} argument plus the
 *       optional {@code --reason "..."} flag.</li>
 *   <li>Self-ban guard — if the resolved {@code actor.contact_id} equals
 *       the parsed target contact id on the same inbound adapter, return
 *       {@code error.ban.cannot_ban_self} and write no row. The trigger
 *       has no signal of which connection issued the UPDATE per the
 *       M1-008a red-team finding; the in-handler check is the only line
 *       of defense.</li>
 *   <li>(1.5) Open one application-side transaction
 *       ({@code autoCommit=false}). PRE-WRITE the BAN audit row INSIDE
 *       the transaction BEFORE any mutation per Invariant 7
 *       (audit-before-effect). For every CONTACT_BOUND pending invite
 *       for {@code (adapter, target_contact_id)} that step 7 below will
 *       revoke, ALSO pre-write the matching INVITE_REVOKE audit row at
 *       this step, sharing the same {@code request_id} as the BAN row
 *       (acceptance item 7).</li>
 *   <li>Last-admin guard — the V5
 *       {@code trg_last_admin_protection_update} trigger raises
 *       {@code last_admin_protection: ...} on UPDATEs that would leave
 *       the deployment with zero {@code is_admin=TRUE AND
 *       is_banned=FALSE} rows. The handler matches the literal
 *       {@code last_admin_protection} in the SQLException message, rolls
 *       back the transaction (so the pre-written audit rows go with the
 *       failed mutation), and surfaces {@code error.ban.last_admin}.</li>
 *   <li>Mutation — for an unknown target contact, MINT a {@code preban}
 *       row via {@code INSERT INTO users (...) VALUES (..., 'preban',
 *       NOW(), <actor.id>, <reason>)}. For a known target, UPDATE the
 *       existing row to {@code is_banned=TRUE} with the same metadata.</li>
 *   <li>Revoke contact-bound pending invites — in the SAME transaction,
 *       {@code UPDATE invite_code SET status = 'REVOKED' WHERE adapter
 *       = ? AND invite_type = 'CONTACT_BOUND' AND expected_contact_id =
 *       ? AND status = 'PENDING'}. Open invites (OPEN_ADAPTER) are NOT
 *       revoked on the ban per the spec interpretation: {@code --open}
 *       invites are not bound to any contact at creation time, so the
 *       spec's "open-but-bound-on-consume targeting that contact" phrase
 *       cannot apply to a still-PENDING open invite.</li>
 *   <li>COMMIT.</li>
 * </ol>
 *
 * <p>The handler writes audit rows directly to {@code audit_log} (the
 * M1-036 / M1-039 pattern). The M1-041 AuditLogWriter consolidation is
 * deferred. Every audit row in one dispatch shares one
 * {@code UUID.randomUUID().toString()} request id — the BAN +
 * INVITE_REVOKE correlation is the spec's canonical correlated-rows
 * shape.</p>
 */
@ApplicationScoped
public class BanCommandHandler implements CommandHandler {

    private static final String SELECT_USER_SQL =
            "SELECT id, contact_id, is_admin, is_banned, registration_state "
                    + "FROM users WHERE adapter = ? AND contact_id = ?";

    private static final String SELECT_PENDING_CONTACT_BOUND_INVITES_SQL =
            "SELECT id FROM invite_code "
                    + "WHERE adapter = ? AND invite_type = 'CONTACT_BOUND' "
                    + "  AND expected_contact_id = ? AND status = 'PENDING' "
                    + "FOR UPDATE";

    // The preban INSERT supplies its own UUID for `id` so the audit row's
    // target_id (pre-written before this INSERT) can reference it.
    private static final String INSERT_PREBAN_USER_SQL =
            "INSERT INTO users (id, adapter, contact_id, is_admin, is_banned, "
                    + "registration_state, banned_at, banned_by, ban_reason) "
                    + "VALUES (?, ?, ?, FALSE, TRUE, 'preban', NOW(), ?, ?)";

    private static final String UPDATE_BANNED_KNOWN_USER_SQL =
            "UPDATE users SET is_banned = TRUE, banned_at = NOW(), "
                    + "banned_by = ?, ban_reason = ? WHERE id = ?";

    private static final String UPDATE_INVITE_CODE_REVOKE_ON_BAN_SQL =
            "UPDATE invite_code SET status = 'REVOKED' "
                    + "WHERE adapter = ? AND invite_type = 'CONTACT_BOUND' "
                    + "  AND expected_contact_id = ? AND status = 'PENDING'";

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    @Inject
    InboundContext inboundContext;

    @Inject
    AuditLogWriter auditLogWriter;

    @Inject
    ConfirmStateService confirmStateService;

    @Override
    public String name() {
        return "ban";
    }

    @Override
    public OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
        String adapter = inboundContext.adapterName();
        String callerContactId = contactIdOf(scope);

        // Step 1 — admin gate. Resolve actor by (adapter, contact_id);
        // non-admin or absent actor short-circuits to error.admin_only
        // BEFORE any DB write AND before the confirm-gate fork (a
        // non-admin sending `/ban confirm` must see error.admin_only,
        // not error.confirm.no_pending — admin gate has precedence).
        Optional<UserRow> actorOpt = lookupUser(adapter, callerContactId);
        if (actorOpt.isEmpty() || !actorOpt.get().isAdmin) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
        }
        UserRow actor = actorOpt.get();

        // Confirm-gate fork (M1-051). A body that ends with the literal
        // ` confirm` token is the second leg of the pending-then-confirm
        // pair; takeMatching pops the previously-stored pending args
        // and we proceed to the existing M1-044c transaction with them.
        // Else we run the first-call pre-flight (parse + self-ban),
        // store pending args, and return the prompt template.
        if (rawText.trim().endsWith(" confirm")) {
            Optional<ConfirmStateService.PendingConfirm> taken =
                    confirmStateService.takeMatching(actor.id, scope, "ban");
            if (taken.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_CONFIRM_NO_PENDING));
            }
            BanConfirm pendingBan = (BanConfirm) taken.get();
            return executeBan(scope, actor, adapter,
                    pendingBan.targetContactId(), pendingBan.reason());
        }

        // First-call path — parse `<contact>` + optional `--reason "..."`.
        BanArgs args = BanArgs.parse(rawText);
        if (args == null) {
            // No positional contact arg. Fall back to error.admin_only;
            // no spec-named friendly error covers this shape (callers
            // who pass `/ban` with no args would only reach this branch
            // post-admin-gate — non-admin would short-circuit above).
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
        }
        String targetContactId = args.contact;

        // Self-ban guard. The (adapter, contact_id) identity is
        // sufficient: actor and target are scoped to the same inbound
        // adapter, so a contact_id match implies identity.
        if (callerContactId != null && callerContactId.equals(targetContactId)) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_BAN_CANNOT_BAN_SELF));
        }

        // Pre-flight validation passed — store pending args and prompt
        // for confirm. Last-admin protection is NOT pre-flighted here;
        // the V5 trg_last_admin_protection_update trigger remains the
        // canonical enforcement inside executeBan's transaction. A
        // confirm that arrives within the window re-runs the trigger
        // and surfaces error.ban.last_admin if the state changed
        // between prompt and confirm.
        //
        // Audit-on-intent (spec §Authorization model step 8): write
        // ONE BAN_INTENT row BEFORE remember() / prompt. The row is
        // an atomic single-statement INSERT with autoCommit=true; it
        // is intentionally NOT in a multi-statement transaction
        // because the prompt path mutates no other state. A failure
        // here surfaces as SQLException and the prompt is never sent
        // — preferable to a silent intent that lands no audit trail.
        // The intent row's target_id is a synthetic UUID when the
        // target user row doesn't exist yet (pre-ban case): we never
        // SELECT the target on the prompt leg, so the row's
        // target_user_id is always a fresh UUID; the
        // target_contact_id field carries the resolved identity.
        Optional<UserRow> targetOpt = lookupUser(adapter, targetContactId);
        UUID targetUserIdForIntent = targetOpt.map(u -> u.id).orElse(UUID.randomUUID());
        String intentRequestId = UUID.randomUUID().toString();
        try (Connection conn = dataSource.getConnection()) {
            insertAudit(conn, AuditAction.BAN_INTENT, "user",
                    targetUserIdForIntent.toString(), targetContactId, actor,
                    adapter, intentRequestId, banDetailsJson(args.reason));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to write BAN_INTENT audit row", e);
        }
        confirmStateService.remember(actor.id, scope,
                new BanConfirm(targetContactId, args.reason));
        String prompt = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_CONFIRM_PROMPT_BAN),
                Long.toString(confirmStateService.timeoutSeconds()),
                ContactIds.redact(targetContactId));
        return reply(scope, prompt);
    }

    /**
     * Run the existing M1-044c audit-before-effect ban transaction with
     * the {@code targetContactId} + {@code reason} captured from the
     * pending-confirm payload. The transaction body is byte-for-byte
     * unchanged from the M1-044c shape — only the call shape (called
     * from the confirm path, not the first-call path) changed for
     * M1-051.
     */
    private OutboundMessage executeBan(ScopeRef scope, UserRow actor, String adapter,
                                       String targetContactId, String reason) {
        // Step 1.5 + 4..7 — open the transaction and run the
        // audit-first / mutate-after sequence. Reads of the target row
        // and the pending-invite list happen inside the transaction so
        // their results are consistent with the writes that follow.
        Optional<UserRow> targetOpt = lookupUser(adapter, targetContactId);
        UUID targetUserId = targetOpt.map(u -> u.id).orElse(UUID.randomUUID());
        String requestId = UUID.randomUUID().toString();

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Lock + fetch the contact-bound pending invite ids the
                // step-7 UPDATE will revoke. FOR UPDATE holds the rows
                // for the rest of the transaction so the pre-written
                // INVITE_REVOKE audit rows below cannot reference an
                // invite that another transaction flips out from under
                // us between the audit INSERT and the step-7 UPDATE.
                List<UUID> pendingInviteIds = selectPendingContactBoundInvites(
                        conn, adapter, targetContactId);

                // Step 1.5a — pre-write the BAN audit row.
                insertAudit(conn, AuditAction.BAN, "user", targetUserId.toString(),
                        targetContactId, actor, adapter, requestId,
                        banDetailsJson(reason));

                // Step 1.5b — pre-write one INVITE_REVOKE audit row per
                // pending CONTACT_BOUND invite, sharing the same
                // request_id (acceptance item 7).
                for (UUID inviteId : pendingInviteIds) {
                    insertAudit(conn, AuditAction.INVITE_REVOKE, "invite",
                            inviteId.toString(), targetContactId, actor,
                            adapter, requestId, inviteRevokeOnBanDetailsJson());
                }

                // Step 5 (unknown) or step 6 (known) — mutate users.
                if (targetOpt.isEmpty()) {
                    insertPrebanRow(conn, targetUserId, adapter, targetContactId,
                            actor.id, reason);
                } else {
                    updateUserToBanned(conn, targetUserId, actor.id, reason);
                }

                // Step 7 — revoke CONTACT_BOUND pending invites. The row
                // count this UPDATE returns must equal the size of the
                // FOR-UPDATE-locked list above (no other transaction can
                // have raced past the row lock).
                updateInvitesToRevoked(conn, adapter, targetContactId);

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                // The V5 trg_last_admin_protection_update trigger raises
                // RAISE EXCEPTION 'last_admin_protection: cannot leave
                // the deployment with zero bot admins'. The literal
                // substring is the load-bearing match key (spec-pinned
                // in V5).
                if (e.getMessage() != null && e.getMessage().contains("last_admin_protection")) {
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_BAN_LAST_ADMIN));
                }
                // Redact the contact id in the wrapping message: §Secrets
                // handling commits "Contact IDs are logged in redacted
                // form outside the audit log." The SQLException cause is
                // preserved as-is.
                throw new IllegalStateException(
                        "BanCommandHandler.executeBan failed for adapter="
                                + adapter + " contact_id="
                                + ContactIds.redact(targetContactId), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "BanCommandHandler.executeBan connection failed for adapter="
                            + adapter + " contact_id="
                            + ContactIds.redact(targetContactId), e);
        }

        String body = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_BAN_SUCCESS),
                ContactIds.redact(targetContactId));
        return reply(scope, body);
    }

    private Optional<UserRow> lookupUser(String adapter, String contactId) {
        if (adapter == null || contactId == null) {
            return Optional.empty();
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_USER_SQL)) {
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
                String registrationState = rs.getString("registration_state");
                return Optional.of(new UserRow(id, resolvedContactId, isAdmin, isBanned,
                        registrationState));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "BanCommandHandler.lookupUser failed for adapter="
                            + adapter + " contact_id="
                            + ContactIds.redact(contactId), e);
        }
    }

    private List<UUID> selectPendingContactBoundInvites(Connection conn,
                                                        String adapter,
                                                        String targetContactId)
            throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                SELECT_PENDING_CONTACT_BOUND_INVITES_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, targetContactId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add((UUID) rs.getObject("id"));
                }
            }
        }
        return ids;
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

    private void insertPrebanRow(Connection conn,
                                 UUID targetId,
                                 String adapter,
                                 String targetContactId,
                                 UUID actorId,
                                 String reason) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_PREBAN_USER_SQL)) {
            ps.setObject(1, targetId);
            ps.setString(2, adapter);
            ps.setString(3, targetContactId);
            ps.setObject(4, actorId);
            if (reason == null) {
                ps.setNull(5, Types.VARCHAR);
            } else {
                ps.setString(5, reason);
            }
            ps.executeUpdate();
        }
    }

    private void updateUserToBanned(Connection conn,
                                    UUID targetId,
                                    UUID actorId,
                                    String reason) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(UPDATE_BANNED_KNOWN_USER_SQL)) {
            ps.setObject(1, actorId);
            if (reason == null) {
                ps.setNull(2, Types.VARCHAR);
            } else {
                ps.setString(2, reason);
            }
            ps.setObject(3, targetId);
            ps.executeUpdate();
        }
    }

    private void updateInvitesToRevoked(Connection conn,
                                        String adapter,
                                        String targetContactId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                UPDATE_INVITE_CODE_REVOKE_ON_BAN_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, targetContactId);
            ps.executeUpdate();
        }
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    private static String contactIdOf(ScopeRef scope) {
        return scope instanceof ScopeRef.Dm dm ? dm.contactId() : null;
    }

    /**
     * Build the BAN audit row's {@code details_json}. Carries the
     * caller-supplied {@code --reason} verbatim when present (escaped
     * for safe JSON embedding); empty object otherwise.
     */
    private static String banDetailsJson(String reason) {
        if (reason == null) {
            return "{}";
        }
        return "{\"reason\":" + quoteJsonString(reason) + "}";
    }

    private static String inviteRevokeOnBanDetailsJson() {
        // The INVITE_REVOKE rows written from the /ban dispatch carry a
        // {"trigger":"ban"} marker so a future audit-log reader can
        // distinguish them from operator-initiated /invite revoke rows.
        return "{\"trigger\":\"ban\"}";
    }

    private static String quoteJsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** Minimal in-memory representation of a users row the handler needs. */
    private record UserRow(UUID id, String contactId, boolean isAdmin, boolean isBanned,
                           String registrationState) {}

    /**
     * Parsed form of {@code /ban <contact> [--reason "..."]}. The
     * {@code contact} positional is required; the {@code --reason} flag
     * is optional and accepts a quoted or unquoted value. Returns
     * {@code null} when the positional contact arg is missing.
     */
    record BanArgs(String contact, String reason) {

        static BanArgs parse(String rawText) {
            // Drop the leading /ban token. Tokenize honoring double-quoted values.
            String[] split = rawText.trim().split("\\s+", 2);
            String remainder = split.length > 1 ? split[1].trim() : "";
            List<String> tokens = tokenize(remainder);

            String contact = null;
            String reason = null;
            int i = 0;
            while (i < tokens.size()) {
                String tok = tokens.get(i);
                if (tok.equals("--reason")) {
                    if (i + 1 < tokens.size()) {
                        reason = tokens.get(i + 1);
                        i += 2;
                    } else {
                        i++;
                    }
                } else if (tok.startsWith("--reason=")) {
                    reason = tok.substring("--reason=".length());
                    i++;
                } else if (contact == null) {
                    contact = tok;
                    i++;
                } else {
                    i++;
                }
            }
            if (contact == null) {
                return null;
            }
            return new BanArgs(contact, reason);
        }

        private static List<String> tokenize(String s) {
            List<String> out = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean inQuotes = false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"') {
                    inQuotes = !inQuotes;
                    continue;
                }
                if (!inQuotes && Character.isWhitespace(c)) {
                    if (current.length() > 0) {
                        out.add(current.toString());
                        current.setLength(0);
                    }
                    continue;
                }
                current.append(c);
            }
            if (current.length() > 0) {
                out.add(current.toString());
            }
            return out;
        }
    }
}
