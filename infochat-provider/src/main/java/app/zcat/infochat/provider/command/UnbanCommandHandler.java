package app.zcat.infochat.provider.command;

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
import java.sql.Statement;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@code /unban <contact>} per {@code docs/spec/security.md}
 * §User ban and {@code docs/spec/commands.md} §Admin (bot admin).
 *
 * <p>Dispatch sequence (acceptance items 9 + 10 in M1-044c):
 * <ol>
 *   <li>Admin gate — resolve actor by {@code (adapter, contact_id)};
 *       non-admin → {@code error.admin_only}, no DB write.</li>
 *   <li>Parse the positional {@code <contact>} argument.</li>
 *   <li>Resolve the target row by {@code (inbound_adapter,
 *       target_contact_id)}. No row → {@code error.contact_not_registered}
 *       per spec §Admin Unknown-contact rule (an {@code /unban} against a
 *       contact with no {@code users} row is distinct from the preban
 *       carve-out below).</li>
 *   <li>Preban path — when the target row's {@code registration_state =
 *       'preban'}, {@code SET LOCAL infochat.request_id = <uuid>} on the
 *       same Connection (so the V5
 *       {@code current_setting('infochat.request_id', TRUE)} read inside
 *       the procedure picks up our dispatch's request_id) and then
 *       {@code CALL delete_preban_user(target.id, actor.id)} via JDBC
 *       {@code CallableStatement}. The V5 procedure (SECURITY DEFINER)
 *       writes the {@code UNBAN_PREBAN_DELETE} audit row AND deletes the
 *       users row in one transaction. Reply:
 *       {@code reply.unban.preban_deleted}.</li>
 *   <li>Non-preban path — open one application-side transaction
 *       ({@code autoCommit=false}). SELECT the user's
 *       {@code is_group_admin=TRUE} rows (groups joined). PRE-WRITE the
 *       UNBAN audit row INSIDE the transaction BEFORE any mutation
 *       (audit-before-effect, Invariant 7); the audit row's
 *       {@code details_json.restored_group_admin} carries the list of
 *       group ids being reinstated. Then UPDATE the users row to clear
 *       {@code is_banned}, {@code banned_at}, {@code banned_by},
 *       {@code ban_reason}. COMMIT.</li>
 *   <li>Reply on the non-preban path —
 *       {@code reply.unban.group_admins_restored} when any group-admin
 *       rows were restored (interpolates the comma-joined display names
 *       and contains the literal {@code /demote} hint per spec);
 *       {@code reply.unban.plain} otherwise.</li>
 * </ol>
 *
 * <p><b>SET LOCAL parameterization caveat.</b> Postgres'
 * {@code SET LOCAL <name> = <value>} is a meta-command, not a normal
 * DML, and does NOT accept JDBC bind parameters for the value. The
 * handler interpolates the request id as a quoted string literal into
 * the SQL text. This is safe because the request id is the output of
 * {@code UUID.randomUUID().toString()} — well-formed, no injection
 * surface. The alternative
 * {@code SELECT set_config('infochat.request_id', ?, true)} accepts a
 * bind parameter but would not match the acceptance item 10 grep
 * predicate {@code SET LOCAL.*infochat[._]request_id}.</p>
 */
@ApplicationScoped
public class UnbanCommandHandler implements CommandHandler {

    private static final String SELECT_USER_SQL =
            "SELECT id, contact_id, is_admin, registration_state "
                    + "FROM users WHERE adapter = ? AND contact_id = ?";

    private static final String SELECT_GROUP_ADMINS_SQL =
            "SELECT g.id, g.display_name "
                    + "FROM group_membership gm "
                    + "JOIN groups g ON g.id = gm.group_id "
                    + "WHERE gm.user_id = ? AND gm.is_group_admin = TRUE "
                    + "ORDER BY g.display_name";

    private static final String INSERT_AUDIT_SQL =
            "INSERT INTO audit_log ("
                    + "actor_user_id, actor_contact_id, actor_adapter, "
                    + "action, target_kind, target_id, target_contact_id, "
                    + "scope_id, request_id, details_json) "
                    + "VALUES (?, ?, ?, 'UNBAN', 'user', ?, ?, NULL, ?, ?::jsonb)";

    private static final String UPDATE_UNBAN_NON_PREBAN_SQL =
            "UPDATE users SET is_banned = FALSE, banned_at = NULL, "
                    + "banned_by = NULL, ban_reason = NULL WHERE id = ?";

    // Use a plain prepareStatement with the literal CALL form. JDBC's
    // {@code prepareCall("{ CALL ... }")} delegates to the Postgres
    // driver's escape translator, which on current driver versions
    // rewrites to {@code SELECT delete_preban_user(?, ?)} — that fails
    // with "delete_preban_user is a procedure, use CALL" because the
    // V5 stored object is declared as a PROCEDURE, not a FUNCTION.
    // Sending the {@code CALL} form directly through {@code Statement}
    // is the spec-correct shape for invoking a PROCEDURE in Postgres.
    private static final String CALL_DELETE_PREBAN_USER_SQL =
            "CALL delete_preban_user(?, ?)";

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    @Inject
    InboundContext inboundContext;

    @Override
    public String name() {
        return "unban";
    }

    @Override
    public OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
        String adapter = inboundContext.adapterName();
        String callerContactId = contactIdOf(scope);

        // Step 1 — admin gate.
        Optional<UserRow> actorOpt = lookupUser(adapter, callerContactId);
        if (actorOpt.isEmpty() || !actorOpt.get().isAdmin) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
        }
        UserRow actor = actorOpt.get();

        // Step 2 — parse `<contact>`.
        UnbanArgs args = UnbanArgs.parse(rawText);
        if (args == null) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
        }
        String targetContactId = args.contact;

        // Step 3 — resolve target. Unknown contact → friendly error, no
        // DB write (Unknown-contact rule per spec §Admin).
        Optional<UserRow> targetOpt = lookupUser(adapter, targetContactId);
        if (targetOpt.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_CONTACT_NOT_REGISTERED));
        }
        UserRow target = targetOpt.get();

        String requestId = UUID.randomUUID().toString();

        // Step 4 — preban carve-out.
        if ("preban".equals(target.registrationState)) {
            executeDeletePrebanUser(adapter, targetContactId, target.id, actor.id, requestId);
            return reply(scope, bundleLoader.get(BundleKeys.REPLY_UNBAN_PREBAN_DELETED));
        }

        // Step 5 — non-preban path. One transaction: read group-admin
        // memberships, pre-write the UNBAN audit row carrying the
        // restored_group_admin list, UPDATE the users row, COMMIT.
        List<GroupRow> restored;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                restored = selectGroupAdminMemberships(conn, target.id);

                insertUnbanAudit(conn, actor, adapter, target.id, targetContactId,
                        requestId, unbanDetailsJson(restored));

                updateUserUnbanned(conn, target.id);

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "UnbanCommandHandler.handle failed for adapter="
                                + adapter + " contact_id="
                                + ContactIds.redact(targetContactId), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "UnbanCommandHandler.handle connection failed for adapter="
                            + adapter + " contact_id="
                            + ContactIds.redact(targetContactId), e);
        }

        // Step 6 / 7 — reply. Group-admin restoration disclosure when
        // any rows were restored, otherwise the plain reply.
        if (restored.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.REPLY_UNBAN_PLAIN));
        }
        String groupList = renderGroupList(restored);
        String body = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_UNBAN_GROUP_ADMINS_RESTORED),
                groupList);
        return reply(scope, body);
    }

    private void executeDeletePrebanUser(String adapter,
                                         String targetContactId,
                                         UUID targetId,
                                         UUID actorId,
                                         String requestId) {
        // The V5 procedure manages its own audit-INSERT + DELETE pair
        // atomically (SECURITY DEFINER). The handler's responsibility
        // here is the request_id propagation: SET LOCAL on the same
        // Connection before the CALL so the procedure's
        // current_setting('infochat.request_id', TRUE) read picks up
        // our dispatch's request_id and the procedure-written
        // UNBAN_PREBAN_DELETE row shares it with any other rows the
        // handler would write for the same dispatch.
        //
        // SET LOCAL is a Postgres meta-command and does NOT accept JDBC
        // bind parameters for the value; we interpolate the UUID
        // literal directly. UUID.randomUUID().toString() is well-formed
        // (16 hex chars + 4 dashes; characters drawn from [0-9a-f-])
        // so no injection surface exists.
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (Statement st = conn.createStatement()) {
                    st.execute("SET LOCAL infochat.request_id = '" + requestId + "'");
                }
                try (PreparedStatement cs = conn.prepareStatement(CALL_DELETE_PREBAN_USER_SQL)) {
                    cs.setObject(1, targetId);
                    cs.setObject(2, actorId);
                    cs.execute();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "UnbanCommandHandler.delete_preban_user failed for adapter="
                                + adapter + " contact_id="
                                + ContactIds.redact(targetContactId), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "UnbanCommandHandler.delete_preban_user connection failed for adapter="
                            + adapter + " contact_id="
                            + ContactIds.redact(targetContactId), e);
        }
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
                String registrationState = rs.getString("registration_state");
                return Optional.of(new UserRow(id, resolvedContactId, isAdmin,
                        registrationState));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "UnbanCommandHandler.lookupUser failed for adapter="
                            + adapter + " contact_id="
                            + ContactIds.redact(contactId), e);
        }
    }

    private List<GroupRow> selectGroupAdminMemberships(Connection conn, UUID userId)
            throws SQLException {
        List<GroupRow> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SELECT_GROUP_ADMINS_SQL)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = (UUID) rs.getObject("id");
                    String displayName = rs.getString("display_name");
                    rows.add(new GroupRow(id, displayName));
                }
            }
        }
        return rows;
    }

    private void insertUnbanAudit(Connection conn,
                                  UserRow actor,
                                  String adapter,
                                  UUID targetId,
                                  String targetContactId,
                                  String requestId,
                                  String detailsJson) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_AUDIT_SQL)) {
            ps.setObject(1, actor.id);
            ps.setString(2, actor.contactId);
            ps.setString(3, adapter);
            ps.setString(4, targetId.toString());
            ps.setString(5, targetContactId);
            ps.setString(6, requestId);
            ps.setString(7, detailsJson);
            ps.executeUpdate();
        }
    }

    private void updateUserUnbanned(Connection conn, UUID targetId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(UPDATE_UNBAN_NON_PREBAN_SQL)) {
            ps.setObject(1, targetId);
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
     * Render the restored group list for the reply template: the
     * comma-joined sequence of display names interpolated via
     * {@code {0}} into {@link BundleKeys#REPLY_UNBAN_GROUP_ADMINS_RESTORED}.
     */
    private static String renderGroupList(List<GroupRow> rows) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(rows.get(i).displayName);
        }
        return sb.toString();
    }

    /**
     * Build the UNBAN audit row's {@code details_json}. Carries the
     * list of restored group-admin group ids under
     * {@code restored_group_admin} per spec §User ban — the audit row
     * "carries the same list under {@code details_json.restored_group_admin}"
     * as the user-visible reply.
     */
    private static String unbanDetailsJson(List<GroupRow> rows) {
        if (rows.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"restored_group_admin\":[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(rows.get(i).id.toString()).append('"');
        }
        sb.append("]}");
        return sb.toString();
    }

    /** Minimal in-memory representation of a users row this handler needs. */
    private record UserRow(UUID id, String contactId, boolean isAdmin,
                           String registrationState) {}

    /** Restored group-admin row pair (id used in details_json, display_name in the reply). */
    private record GroupRow(UUID id, String displayName) {}

    /**
     * Parsed form of {@code /unban <contact>}. The {@code contact}
     * positional is required; no flags. Returns {@code null} when the
     * positional contact arg is missing.
     */
    record UnbanArgs(String contact) {

        static UnbanArgs parse(String rawText) {
            // Drop the leading /unban token.
            String[] split = rawText.trim().split("\\s+", 3);
            if (split.length < 2 || split[1].isBlank()) {
                return null;
            }
            return new UnbanArgs(split[1]);
        }
    }
}
