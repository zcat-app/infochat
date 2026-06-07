package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.log.ContactIds;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.AdapterRegistry;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.user.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Implements {@code /invite create / list / revoke} per
 * {@code docs/spec/security.md} §Invite-code registration and
 * {@code docs/spec/commands.md} §Admin (bot admin).
 *
 * <p>The handler dispatches on the first whitespace-delimited
 * subcommand token to a {@code "create"} / {@code "list"} /
 * {@code "revoke"} branch; any other token → {@code error.invite
 * .unknown_subcommand} (acceptance item 16). Each branch executes
 * the audit-before-effect transaction shape that
 * {@link BanCommandHandler} pins for {@code /ban}: the audit row
 * INSERT runs FIRST inside the same transaction as the state
 * mutation; a roll-back leaves no audit-vs-state divergence
 * (Invariant 7).</p>
 *
 * <p><b>Cross-adapter create.</b> {@code /invite create} reads the
 * target adapter from the {@code --adapter <name>} flag, NOT from
 * {@link InboundContext#adapterName()}. The inbound adapter (where
 * the bot admin ran the command) is the {@code actor_adapter} on the
 * audit row; the target adapter (the flag value) is the
 * {@code invite_code.adapter} column and the cap-query scope. Per spec
 * §Invite-code registration this is "the one admin command that may
 * name any adapter the deployment supports."</p>
 *
 * <p><b>Confirm gate (M1-051).</b> {@code /invite create --open} and
 * {@code /invite revoke} both pass through the {@link ConfirmStateService}
 * pre-dispatch confirm gate per spec §Surface conventions. First call:
 * validate inputs, store pending args under
 * {@code (actor.id, scope, "invite:create:open"|"invite:revoke")},
 * return the prompt. Second call (body ends with {@code " confirm"}):
 * {@code takeMatching} on the same key — non-empty pops the captured
 * args and runs the M1-044c transaction unchanged; empty returns
 * {@code error.confirm.no_pending}. {@code /invite create --contact}
 * is intentionally excluded from the gate (spec §Admin: "risk is
 * bounded to one specific identity").</p>
 */
@ApplicationScoped
public class InviteCommandHandler implements CommandHandler {

    // The open-cap query is scoped to one adapter per spec §Invite-code
    // registration ("per-adapter open cap"). The expires_at filter
    // matches the spec's active-PENDING definition — codes whose TTL
    // has passed do NOT count toward the cap.
    private static final String COUNT_OPEN_PENDING_PER_ADAPTER_SQL =
            "SELECT count(*) FROM invite_code "
                    + "WHERE invite_type = 'OPEN_ADAPTER' "
                    + "  AND status = 'PENDING' "
                    + "  AND adapter = ? "
                    + "  AND (expires_at IS NULL OR expires_at > NOW())";

    // The contact-cap query is global across adapters per spec
    // §Invite-code registration ("global --contact cap").
    private static final String COUNT_CONTACT_PENDING_GLOBAL_SQL =
            "SELECT count(*) FROM invite_code "
                    + "WHERE invite_type = 'CONTACT_BOUND' "
                    + "  AND status = 'PENDING' "
                    + "  AND (expires_at IS NULL OR expires_at > NOW())";

    // Pre-mint the code via a separate SELECT gen_random_uuid() so the
    // audit row can be written FIRST (audit-before-effect, Invariant 7)
    // with the code as its target_id, and the INSERT INTO invite_code
    // can then run with the same code passed as a JDBC bind parameter.
    // pgcrypto's gen_random_uuid() (cryptographically secure RNG)
    // remains the code source — the spec asks for it explicitly.
    private static final String SELECT_NEW_CODE_SQL =
            "SELECT gen_random_uuid() AS code";

    private static final String INSERT_INVITE_SQL =
            "INSERT INTO invite_code "
                    + "(code, invite_type, adapter, expected_contact_id, "
                    + " status, created_by, created_at, expires_at) "
                    + "VALUES (?, ?, ?, ?, 'PENDING', ?, NOW(), ?)";

    // Active-PENDING filter (status='PENDING' AND not expired). Sorts
    // by created_at DESC; limit + offset support `--page N` paging.
    private static final String SELECT_PENDING_LIST_SQL =
            "SELECT code, invite_type, adapter, expected_contact_id, expires_at "
                    + "FROM invite_code "
                    + "WHERE status = 'PENDING' "
                    + "  AND (expires_at IS NULL OR expires_at > NOW()) "
                    + "ORDER BY created_at DESC "
                    + "LIMIT ? OFFSET ?";

    // FOR UPDATE locks the row so the audit INSERT below cannot
    // reference an invite another transaction flips before the UPDATE.
    private static final String SELECT_INVITE_FOR_REVOKE_SQL =
            "SELECT id FROM invite_code WHERE code = ? AND status = 'PENDING' FOR UPDATE";

    private static final String UPDATE_INVITE_REVOKED_SQL =
            "UPDATE invite_code SET status = 'REVOKED' "
                    + "WHERE code = ? AND status = 'PENDING'";

    private static final int PAGE_SIZE = 20;

    @ConfigProperty(name = "infochat.invite.ttl", defaultValue = "7d")
    Duration inviteTtl;

    @ConfigProperty(name = "infochat.invite.open-cap-per-adapter", defaultValue = "3")
    int openCap;

    @ConfigProperty(name = "infochat.invite.contact-cap-global", defaultValue = "50")
    int contactCap;

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    @Inject
    InboundContext inboundContext;

    @Inject
    AdapterRegistry adapterRegistry;

    @Inject
    AuditLogWriter auditLogWriter;

    @Inject
    ConfirmStateService confirmStateService;

    @Inject
    UserRepository userRepository;

    @Override
    public String name() {
        return "invite";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        String inboundAdapter = inboundContext.adapterName();
        String callerContactId = contactIdOf(scope);

        // Step 1 — admin gate. Resolved by the inbound adapter regardless
        // of which --adapter the command targets (the actor identity is
        // per-inbound; the target adapter is a flag).
        Optional<UserRow> actorOpt = lookupUser(inboundAdapter, callerContactId);
        if (actorOpt.isEmpty() || !actorOpt.get().isAdmin) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY));
        }
        UserRow actor = actorOpt.get();

        // Step 2 — dispatch on the first subcommand token.
        String[] split = rawText.trim().split("\\s+", 3);
        String subcommand = split.length > 1 ? split[1].toLowerCase(java.util.Locale.ROOT) : "";
        String remainder = split.length > 2 ? split[2] : "";

        return switch (subcommand) {
            case "create" -> handleCreate(scope, actor, inboundAdapter, remainder);
            case "list" -> handleList(scope, remainder);
            case "revoke" -> handleRevoke(scope, actor, inboundAdapter, remainder);
            default -> reply(scope, bundleLoader.get(BundleKeys.ERROR_INVITE_UNKNOWN_SUBCOMMAND));
        };
    }

    // ------------------------------------------------------------------
    // /invite create --adapter <name> {--contact <id> | --open}
    // ------------------------------------------------------------------

    private OutboundMessage handleCreate(ScopeRef scope,
                                         UserRow actor,
                                         String inboundAdapter,
                                         String remainder) {
        // Confirm-gate fork for /invite create --open (M1-051). The
        // --contact branch has NO confirm gate per spec §Admin
        // ("No confirmation required (risk is bounded to one specific
        // identity)"); a body ending in ` confirm` against --contact
        // still reaches the first-call path and falls through to
        // missing-flag / mutually-exclusive validation since "confirm"
        // is not a flag.
        String trimmed = remainder.trim();
        if (trimmed.equals("confirm") || trimmed.endsWith(" confirm")) {
            // Confirm-call: identifier "invite:create:open" — namespaced
            // discriminator so /invite create --open and /invite revoke
            // can both pend through the same actor+scope key without
            // ambiguity.
            Optional<ConfirmStateService.PendingConfirm> taken =
                    confirmStateService.takeMatching(actor.id, scope, "invite:create:open");
            if (taken.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_CONFIRM_NO_PENDING));
            }
            InviteCreateOpenConfirm pending = (InviteCreateOpenConfirm) taken.get();
            return createOpen(scope, actor, inboundAdapter, pending.targetAdapter());
        }

        CreateArgs args = CreateArgs.parse(remainder);

        // Flag combination checks before adapter-name validation: the
        // spec's friendly errors fire in this priority order (mutually
        // exclusive before missing, both before unknown adapter / cap).
        if (args.contact != null && args.open) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_INVITE_MUTUALLY_EXCLUSIVE));
        }
        if (args.contact == null && !args.open) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_INVITE_MISSING_FLAG));
        }

        // Adapter validation against the currently-enabled set.
        Set<String> enabled = enabledAdapterNames();
        String targetAdapter = args.adapter;
        if (targetAdapter == null || !enabled.contains(targetAdapter)) {
            String body = MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_INVITE_UNKNOWN_ADAPTER),
                    targetAdapter == null ? "" : targetAdapter);
            return reply(scope, body);
        }

        if (args.contact != null) {
            // --contact path executes immediately — spec carves it out
            // of the confirm requirement (bounded blast radius).
            return createContactBound(scope, actor, inboundAdapter, targetAdapter, args.contact);
        }

        // --open path — first-call: validate adapter (done above), write
        // the spec §Authorization model step-8 audit-on-intent row,
        // then remember pending + return prompt. Confirm-call lands in
        // createOpen via the confirm-fork above with the captured
        // targetAdapter. The intent row's target_id is a synthetic
        // placeholder UUID because the invite_code row doesn't exist
        // yet (it gets INSERTed on confirm in createOpen); details_json
        // carries the requested targetAdapter so an operator can
        // correlate intent and completion.
        String intentRequestId = UUID.randomUUID().toString();
        try (Connection conn = dataSource.getConnection()) {
            insertAudit(conn, AuditAction.INVITE_CREATE_INTENT,
                    UUID.randomUUID().toString(), null, actor, inboundAdapter,
                    intentRequestId, inviteCreateOpenIntentDetailsJson(targetAdapter));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to write INVITE_CREATE_INTENT audit row", e);
        }
        confirmStateService.remember(actor.id, scope,
                new InviteCreateOpenConfirm(targetAdapter));
        String prompt = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_CONFIRM_PROMPT_INVITE_CREATE_OPEN),
                Long.toString(confirmStateService.timeoutSeconds()),
                targetAdapter);
        return reply(scope, prompt);
    }

    private static String inviteCreateOpenIntentDetailsJson(String targetAdapter) {
        return "{\"target_adapter\":\"" + targetAdapter.replace("\"", "\\\"") + "\"}";
    }

    private OutboundMessage createContactBound(ScopeRef scope,
                                               UserRow actor,
                                               String inboundAdapter,
                                               String targetAdapter,
                                               String targetContactId) {
        // Pre-banned-contact rejection: the (targetAdapter, targetContactId)
        // row must not be is_banned=TRUE per spec. The unknown-contact
        // case (no row at all) is permitted because /invite create
        // explicitly carves out a pre-bound invite for an unregistered
        // contact (spec §Admin Unknown-contact rule exception).
        Optional<UserRow> targetOpt = lookupUser(targetAdapter, targetContactId);
        if (targetOpt.isPresent() && targetOpt.get().isBanned) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_INVITE_BANNED_TARGET));
        }

        String requestId = UUID.randomUUID().toString();
        OffsetDateTime expiresAt = OffsetDateTime.now().plus(inviteTtl);
        UUID code;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                long current = countContactBoundPending(conn);
                if (current >= contactCap) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_INVITE_CONTACT_CAP_MET));
                }

                // Audit-before-effect: mint the code (pure read), write
                // the INVITE_CREATE audit row FIRST referencing that
                // code, then INSERT the invite_code row. A roll-back
                // anywhere below the audit INSERT discards both.
                code = generateInviteCode(conn);

                insertAudit(conn, AuditAction.INVITE_CREATE, code.toString(), targetContactId,
                        actor, inboundAdapter, requestId,
                        inviteCreateDetailsJson("CONTACT_BOUND", targetAdapter));

                insertInvite(conn, code, "CONTACT_BOUND", targetAdapter, targetContactId,
                        actor.id, expiresAt);

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "InviteCommandHandler.createContactBound failed for adapter="
                                + targetAdapter + " contact_id="
                                + ContactIds.redact(targetContactId), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "InviteCommandHandler.createContactBound connection failed for adapter="
                            + targetAdapter + " contact_id="
                            + ContactIds.redact(targetContactId), e);
        }

        String body = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_INVITE_CREATED), code.toString());
        return reply(scope, body);
    }

    private OutboundMessage createOpen(ScopeRef scope,
                                       UserRow actor,
                                       String inboundAdapter,
                                       String targetAdapter) {
        String requestId = UUID.randomUUID().toString();
        OffsetDateTime expiresAt = OffsetDateTime.now().plus(inviteTtl);
        UUID code;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                long current = countOpenPendingForAdapter(conn, targetAdapter);
                if (current >= openCap) {
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_INVITE_OPEN_CAP_MET));
                }

                // Audit-before-effect: see {@link #createContactBound}
                // for the rationale on the mint-audit-insert order.
                code = generateInviteCode(conn);

                insertAudit(conn, AuditAction.INVITE_CREATE, code.toString(), null,
                        actor, inboundAdapter, requestId,
                        inviteCreateDetailsJson("OPEN_ADAPTER", targetAdapter));

                insertInvite(conn, code, "OPEN_ADAPTER", targetAdapter, null,
                        actor.id, expiresAt);

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "InviteCommandHandler.createOpen failed for adapter="
                                + targetAdapter, e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "InviteCommandHandler.createOpen connection failed for adapter="
                            + targetAdapter, e);
        }

        String body = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_INVITE_CREATED), code.toString());
        return reply(scope, body);
    }

    private long countOpenPendingForAdapter(Connection conn, String adapter) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(COUNT_OPEN_PENDING_PER_ADAPTER_SQL)) {
            ps.setString(1, adapter);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countContactBoundPending(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(COUNT_CONTACT_PENDING_GLOBAL_SQL);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private UUID generateInviteCode(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_NEW_CODE_SQL);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return (UUID) rs.getObject("code");
        }
    }

    private void insertInvite(Connection conn,
                              UUID code,
                              String inviteType,
                              String adapter,
                              @Nullable String expectedContactId,
                              UUID createdBy,
                              OffsetDateTime expiresAt) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_INVITE_SQL)) {
            ps.setObject(1, code);
            ps.setString(2, inviteType);
            ps.setString(3, adapter);
            if (expectedContactId == null) {
                ps.setNull(4, Types.VARCHAR);
            } else {
                ps.setString(4, expectedContactId);
            }
            ps.setObject(5, createdBy);
            ps.setObject(6, expiresAt);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    // /invite list [--page N]
    // ------------------------------------------------------------------

    private OutboundMessage handleList(ScopeRef scope, String remainder) {
        int page = ListArgs.parse(remainder).page;
        int offset = Math.max(0, (page - 1) * PAGE_SIZE);

        List<PendingInviteRow> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_PENDING_LIST_SQL)) {
            ps.setInt(1, PAGE_SIZE);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID code = (UUID) rs.getObject("code");
                    String inviteType = rs.getString("invite_type");
                    String adapter = rs.getString("adapter");
                    String contactId = rs.getString("expected_contact_id");
                    Timestamp ts = rs.getTimestamp("expires_at");
                    Instant expiresAt = ts == null ? null : ts.toInstant();
                    rows.add(new PendingInviteRow(code, inviteType, adapter, contactId, expiresAt));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "InviteCommandHandler.handleList failed", e);
        }

        StringBuilder body = new StringBuilder(bundleLoader.get(BundleKeys.REPLY_INVITE_LIST_HEADER));
        for (PendingInviteRow row : rows) {
            body.append('\n');
            body.append(renderListEntry(row));
        }
        return reply(scope, body.toString());
    }

    private String renderListEntry(PendingInviteRow row) {
        // Full code, not a prefix: /invite revoke matches the full UUID,
        // so the list must display exactly what revoke accepts.
        String codeText = row.code.toString();
        String expiresAtIso = row.expiresAt == null ? "(no expiry)" : row.expiresAt.toString();
        if ("OPEN_ADAPTER".equals(row.inviteType)) {
            return MessageFormat.format(
                    bundleLoader.get(BundleKeys.REPLY_INVITE_LIST_ENTRY_OPEN),
                    codeText, row.adapter, expiresAtIso);
        }
        String target = row.expectedContactId == null
                ? ""
                : ContactIds.redact(row.expectedContactId);
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_INVITE_LIST_ENTRY),
                codeText, row.adapter, target, expiresAtIso);
    }

    // ------------------------------------------------------------------
    // /invite revoke <code>
    // ------------------------------------------------------------------

    private OutboundMessage handleRevoke(ScopeRef scope,
                                         UserRow actor,
                                         String inboundAdapter,
                                         String remainder) {
        // Confirm-gate fork (M1-051). identifier "invite:revoke" is the
        // colon-namespaced takeMatching key; the router's step 4.5
        // sweep recognizes the "invite revoke" sweepPrefix on the
        // pending payload (see ConfirmStateService.PendingConfirm).
        String trimmed = remainder.trim();
        if (trimmed.equals("confirm") || trimmed.endsWith(" confirm")) {
            Optional<ConfirmStateService.PendingConfirm> taken =
                    confirmStateService.takeMatching(actor.id, scope, "invite:revoke");
            if (taken.isEmpty()) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_CONFIRM_NO_PENDING));
            }
            InviteRevokeConfirm pending = (InviteRevokeConfirm) taken.get();
            return executeRevoke(scope, actor, inboundAdapter, pending.code());
        }

        String codeText = trimmed.split("\\s+", 2)[0];
        UUID code;
        try {
            code = UUID.fromString(codeText);
        } catch (IllegalArgumentException e) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_INVITE_REVOKE_NOT_PENDING));
        }

        // First-call path — validation passes only the UUID-parse here.
        // The PENDING-row existence check lives inside the transaction
        // (the FOR UPDATE lock in executeRevoke), so a /invite revoke
        // against a non-PENDING code STILL stores pending and prompts;
        // the user's confirm will then surface error.invite.revoke_not_pending.
        // This is acceptable UX — the spec doesn't require pre-flight
        // existence-checking, and the lock is the canonical race guard.
        //
        // Audit-on-intent (spec §Authorization model step 8): write
        // ONE INVITE_REVOKE_INTENT row referencing the parsed code
        // BEFORE remember() / prompt. The completion row (action=
        // INVITE_REVOKE) writes on the confirm leg inside the FOR
        // UPDATE transaction. Together they record both "the admin
        // attempted to revoke this code" and "the row actually
        // transitioned PENDING→REVOKED" — an admin who probes a code
        // prefix without confirming still leaves the intent row.
        String intentRequestId = UUID.randomUUID().toString();
        try (Connection conn = dataSource.getConnection()) {
            insertAudit(conn, AuditAction.INVITE_REVOKE_INTENT, code.toString(),
                    null, actor, inboundAdapter, intentRequestId,
                    inviteRevokeIntentDetailsJson());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to write INVITE_REVOKE_INTENT audit row", e);
        }
        confirmStateService.remember(actor.id, scope,
                new InviteRevokeConfirm(code));
        String prompt = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_CONFIRM_PROMPT_INVITE_REVOKE),
                Long.toString(confirmStateService.timeoutSeconds()),
                code.toString().substring(0, 8));
        return reply(scope, prompt);
    }

    private OutboundMessage executeRevoke(ScopeRef scope,
                                          UserRow actor,
                                          String inboundAdapter,
                                          UUID code) {
        String codeText = code.toString();
        String requestId = UUID.randomUUID().toString();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                UUID inviteId = lockPendingInviteId(conn, code);
                if (inviteId == null) {
                    // Zero rows pending under this code (already USED /
                    // REVOKED / absent). Roll back so no audit row
                    // survives — audit-before-effect with no effect = no
                    // audit row.
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_INVITE_REVOKE_NOT_PENDING));
                }

                insertAudit(conn, AuditAction.INVITE_REVOKE, inviteId.toString(), null,
                        actor, inboundAdapter, requestId, "{}");

                int updated = updateInviteRevoked(conn, code);
                if (updated == 0) {
                    // Race-condition guard: the FOR UPDATE above held the
                    // row, so this should be unreachable. Roll back to
                    // keep the invariant audit-row-iff-mutation.
                    conn.rollback();
                    return reply(scope, bundleLoader.get(BundleKeys.ERROR_INVITE_REVOKE_NOT_PENDING));
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "InviteCommandHandler.executeRevoke failed for code=" + codeText, e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "InviteCommandHandler.executeRevoke connection failed", e);
        }

        return reply(scope, bundleLoader.get(BundleKeys.REPLY_INVITE_REVOKED));
    }

    private @Nullable UUID lockPendingInviteId(Connection conn, UUID code) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_INVITE_FOR_REVOKE_SQL)) {
            ps.setObject(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return (UUID) rs.getObject("id");
            }
        }
    }

    private int updateInviteRevoked(Connection conn, UUID code) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(UPDATE_INVITE_REVOKED_SQL)) {
            ps.setObject(1, code);
            return ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    private void insertAudit(Connection conn,
                             AuditAction action,
                             String targetId,
                             @Nullable String targetContactId,
                             UserRow actor,
                             String inboundAdapter,
                             String requestId,
                             String detailsJson) throws SQLException {
        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                .actorUserId(actor.id)
                .actorContactId(actor.contactId)
                .actorAdapter(inboundAdapter)
                .action(action)
                .targetKind("invite")
                .targetId(targetId)
                .targetContactId(targetContactId)
                .requestId(requestId)
                .detailsJson(detailsJson)
                .build();
        auditLogWriter.write(conn, row);
    }

    private Optional<UserRow> lookupUser(String adapter, @Nullable String contactId) {
        if (adapter == null || contactId == null) {
            return Optional.empty();
        }
        return userRepository.findByAdapterAndContactId(adapter, contactId)
                .map(u -> new UserRow(u.id(), u.contactId(), u.isAdmin(), u.isBanned(),
                        u.registrationState()));
    }

    private Set<String> enabledAdapterNames() {
        Set<String> out = new LinkedHashSet<>();
        for (MessagingAdapter adapter : adapterRegistry.activatedAdapters()) {
            out.add(adapter.name());
        }
        return out;
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    private static @Nullable String contactIdOf(ScopeRef scope) {
        return scope instanceof ScopeRef.Dm dm ? dm.contactId() : null;
    }

    private static String inviteRevokeIntentDetailsJson() {
        return "{}";
    }

    private static String inviteCreateDetailsJson(String inviteType, String adapter) {
        // Both fields are closed-set / well-formed identifiers, so direct
        // concatenation into the JSON template is safe (no untrusted
        // user-supplied content). The invite_type set is the V5 CHECK
        // constraint values; the adapter is validated against
        // AdapterRegistry.activatedAdapters() upstream.
        return "{\"invite_type\":\"" + inviteType + "\",\"adapter\":\"" + adapter + "\"}";
    }

    /** Minimal in-memory representation of a users row this handler needs. */
    private record UserRow(UUID id, String contactId, boolean isAdmin, boolean isBanned,
                           String registrationState) {}

    /** One row of a {@code /invite list} result page. */
    private record PendingInviteRow(UUID code, String inviteType, String adapter,
                                    String expectedContactId, @Nullable Instant expiresAt) {}

    /**
     * Parsed form of {@code /invite create --adapter <name>
     * {--contact <id> | --open}}. The required-vs-optional shape and
     * mutually-exclusive validation live in {@link #handleCreate}; the
     * parser only extracts the supplied flag values.
     */
    record CreateArgs(@Nullable String adapter, @Nullable String contact, boolean open) {

        static CreateArgs parse(String remainder) {
            List<String> tokens = tokenize(remainder);
            String adapter = null;
            String contact = null;
            boolean open = false;
            int i = 0;
            while (i < tokens.size()) {
                String tok = tokens.get(i);
                if (tok.equals("--adapter") && i + 1 < tokens.size()) {
                    adapter = tokens.get(i + 1);
                    i += 2;
                } else if (tok.startsWith("--adapter=")) {
                    adapter = tok.substring("--adapter=".length());
                    i++;
                } else if (tok.equals("--contact") && i + 1 < tokens.size()) {
                    contact = tokens.get(i + 1);
                    i += 2;
                } else if (tok.startsWith("--contact=")) {
                    contact = tok.substring("--contact=".length());
                    i++;
                } else if (tok.equals("--open")) {
                    open = true;
                    i++;
                } else {
                    i++;
                }
            }
            return new CreateArgs(adapter, contact, open);
        }
    }

    /** Parsed form of {@code /invite list [--page N]}. */
    record ListArgs(int page) {

        static ListArgs parse(String remainder) {
            List<String> tokens = tokenize(remainder);
            int page = 1;
            for (int i = 0; i < tokens.size(); i++) {
                String tok = tokens.get(i);
                if (tok.equals("--page") && i + 1 < tokens.size()) {
                    try {
                        page = Math.max(1, Integer.parseInt(tokens.get(i + 1)));
                    } catch (NumberFormatException ignored) {
                        // Malformed --page N falls back to page 1.
                    }
                } else if (tok.startsWith("--page=")) {
                    try {
                        page = Math.max(1, Integer.parseInt(tok.substring("--page=".length())));
                    } catch (NumberFormatException ignored) {
                        // Malformed --page=N falls back to page 1.
                    }
                }
            }
            return new ListArgs(page);
        }
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
