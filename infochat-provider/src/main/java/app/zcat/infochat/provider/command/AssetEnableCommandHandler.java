package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.group.GroupMembershipRepository;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@code /asset-enable <asset> [sub-verb]} per
 * {@code docs/spec/commands.md} §Asset commands + §Permission model:
 * the bot-admin-only reset of a tripped D42 asset ladder — a
 * non-{@code active} {@code asset_config} pair goes to
 * {@code status='active'} with {@code consecutive_failures} zeroed, so
 * the Collector's next per-host fetch tick resumes the pair. The bare
 * form addresses the {@code is_default} pair; {@code enabled = false}
 * pairs are refused naming the bootstrap re-list path (enablement is
 * operator-curated, D39 — the command never writes it). No URL probe
 * (fetch URLs are collector-constructed, never stored on the pair) and
 * no confirm gate (non-destructive). The
 * {@code ASSET_ENABLE} audit row is written before the UPDATE in the
 * same transaction; error branches write no audit row and no state.
 */
@ApplicationScoped
public class AssetEnableCommandHandler implements CommandHandler {

    private static final java.util.regex.Pattern VOCABULARY_TOKEN =
            java.util.regex.Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

    private static final String SELECT_GROUP_ID_SQL =
            "SELECT id FROM groups WHERE adapter = ? AND upstream_group_id = ?";

    private static final String SELECT_SUB_VERBS_SQL =
            "SELECT sub_verb FROM asset_config WHERE asset = ? ORDER BY sub_verb";

    private static final String SELECT_DEFAULT_PAIR_SQL =
            "SELECT sub_verb FROM asset_config WHERE asset = ? AND is_default = true";

    private static final String SELECT_PAIR_SQL =
            "SELECT enabled, status FROM asset_config WHERE asset = ? AND sub_verb = ?";

    private static final String SELECT_PAIR_FOR_UPDATE_SQL =
            "SELECT enabled, status FROM asset_config WHERE asset = ? AND sub_verb = ? "
                    + "FOR UPDATE";

    // Exactly the columns the D42 ladder consults (the runbook reset in
    // design §10.8b); every other asset_config column stays unwritten.
    private static final String UPDATE_PAIR_RESET_SQL =
            "UPDATE asset_config SET status = 'active', consecutive_failures = 0 "
                    + "WHERE asset = ? AND sub_verb = ?";

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    @Inject
    InboundContext inboundContext;

    @Inject
    AuditLogWriter auditLogWriter;

    @Inject
    GroupMembershipRepository groupMembershipRepository;

    @Inject
    UserRepository userRepository;

    @Override
    public String name() {
        return "asset-enable";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        if (scope instanceof ScopeRef.Group group) {
            if (!isGroupAdmin(group)) {
                return reply(scope, bundleLoader.get(BundleKeys.ERROR_GROUP_ADMIN_NOT_IN_V1, inboundContext.effectiveLanguage()));
            }
        }

        String adapter = inboundContext.adapterName();
        String callerContactId = scope instanceof ScopeRef.Dm dm
                ? dm.contactId() : inboundContext.senderContactId();

        // Admin gate first — pair existence is never revealed to a
        // non-admin (error.admin_only regardless of the named pair).
        Optional<UserRow> actorOpt = lookupUser(adapter, callerContactId);
        if (actorOpt.isEmpty() || !actorOpt.get().isAdmin) {
            return reply(scope, bundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY, inboundContext.effectiveLanguage()));
        }
        UserRow actor = actorOpt.get();

        String[] tokens = rawText.trim().split("\\s+");
        if (tokens.length < 2) {
            return reply(scope, usageError());
        }
        // Registry vocabulary shape (bootstrap-assets ids are lowercase
        // slugs); the gate bounds every token this handler echoes.
        String asset = tokens[1].toLowerCase(java.util.Locale.ROOT);
        // Trailing text beyond one sub-verb (e.g. a confirm-shaped
        // argument) routes to the unknown-pair error, never a confirm flow.
        String explicitSubVerb = tokens.length == 3
                ? tokens[2].toLowerCase(java.util.Locale.ROOT) : null;
        if (!VOCABULARY_TOKEN.matcher(asset).matches()
                || (explicitSubVerb != null && !VOCABULARY_TOKEN.matcher(explicitSubVerb).matches())
                || tokens.length > 3) {
            return reply(scope,
                    bundleLoader.get(BundleKeys.ERROR_ASSET_ENABLE_UNKNOWN_PAIR, inboundContext.effectiveLanguage()));
        }

        List<String> subVerbs = listSubVerbs(asset);
        if (subVerbs.isEmpty()) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_ASSET_NOT_CONFIGURED, inboundContext.effectiveLanguage()),
                    asset, ""));
        }

        String subVerb = explicitSubVerb;
        if (subVerb == null) {
            // Bare form addresses ONLY the is_default pair — same rule as
            // bare /<asset>; never a fleet-wide reset, never a fallback.
            Optional<String> defaultPair = lookupDefaultSubVerb(asset);
            if (defaultPair.isEmpty()) {
                return reply(scope, MessageFormat.format(
                        bundleLoader.get(BundleKeys.ERROR_ASSET_NOT_CONFIGURED, inboundContext.effectiveLanguage()),
                        asset, String.join(", ", subVerbs)));
            }
            subVerb = defaultPair.get();
        }

        Optional<PairRow> pairOpt = lookupPair(asset, subVerb);
        if (pairOpt.isEmpty()) {
            return reply(scope,
                    bundleLoader.get(BundleKeys.ERROR_ASSET_ENABLE_UNKNOWN_PAIR, inboundContext.effectiveLanguage()));
        }
        PairRow pair = pairOpt.get();
        if (!pair.enabled) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_ASSET_ENABLE_NOT_ENABLED, inboundContext.effectiveLanguage()),
                    asset, subVerb));
        }
        if ("active".equals(pair.status)) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_ASSET_ENABLE_ALREADY_ACTIVE, inboundContext.effectiveLanguage()),
                    asset, subVerb));
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // TOCTOU re-check under the row lock: the concurrent
                // writer is the Collector's ladder.
                PairRow locked = selectPairForUpdate(conn, asset, subVerb);
                if (locked == null) {
                    conn.rollback();
                    return reply(scope,
                            bundleLoader.get(BundleKeys.ERROR_ASSET_ENABLE_UNKNOWN_PAIR, inboundContext.effectiveLanguage()));
                }
                if (!locked.enabled) {
                    conn.rollback();
                    return reply(scope, MessageFormat.format(
                            bundleLoader.get(BundleKeys.ERROR_ASSET_ENABLE_NOT_ENABLED, inboundContext.effectiveLanguage()),
                            asset, subVerb));
                }
                if ("active".equals(locked.status)) {
                    conn.rollback();
                    return reply(scope, MessageFormat.format(
                            bundleLoader.get(BundleKeys.ERROR_ASSET_ENABLE_ALREADY_ACTIVE, inboundContext.effectiveLanguage()),
                            asset, subVerb));
                }
                insertAudit(conn, asset, subVerb, actor, adapter, UUID.randomUUID().toString());
                updatePairReset(conn, asset, subVerb);
                conn.commit();
                return reply(scope, MessageFormat.format(
                        bundleLoader.get(BundleKeys.REPLY_ASSET_ENABLE_SUCCESS, inboundContext.effectiveLanguage()),
                        asset, subVerb));
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "AssetEnableCommandHandler.reset failed for adapter=" + adapter, e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "AssetEnableCommandHandler connection failed for adapter=" + adapter, e);
        }
    }

    private String usageError() {
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.ERROR_USAGE_MISSING_ARGUMENT, inboundContext.effectiveLanguage()),
                "/asset-enable <asset> [sub-verb]");
    }

    private Optional<UserRow> lookupUser(String adapter, String contactId) {
        if (adapter == null || contactId == null) {
            return Optional.empty();
        }
        return userRepository.findByAdapterAndContactId(adapter, contactId)
                .map(u -> new UserRow(u.id(), u.contactId(), u.isAdmin()));
    }

    private List<String> listSubVerbs(String asset) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_SUB_VERBS_SQL)) {
            ps.setString(1, asset);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "AssetEnableCommandHandler.listSubVerbs failed for asset=" + asset, e);
        }
    }

    private Optional<String> lookupDefaultSubVerb(String asset) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_DEFAULT_PAIR_SQL)) {
            ps.setString(1, asset);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "AssetEnableCommandHandler.lookupDefaultSubVerb failed for asset=" + asset, e);
        }
    }

    private Optional<PairRow> lookupPair(String asset, String subVerb) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_PAIR_SQL)) {
            ps.setString(1, asset);
            ps.setString(2, subVerb);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new PairRow(rs.getBoolean("enabled"), rs.getString("status")))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "AssetEnableCommandHandler.lookupPair failed for " + asset + "/" + subVerb, e);
        }
    }

    private @Nullable PairRow selectPairForUpdate(Connection conn, String asset, String subVerb)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_PAIR_FOR_UPDATE_SQL)) {
            ps.setString(1, asset);
            ps.setString(2, subVerb);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new PairRow(rs.getBoolean("enabled"), rs.getString("status"));
            }
        }
    }

    private void updatePairReset(Connection conn, String asset, String subVerb) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(UPDATE_PAIR_RESET_SQL)) {
            ps.setString(1, asset);
            ps.setString(2, subVerb);
            ps.executeUpdate();
        }
    }

    private void insertAudit(Connection conn, String asset, String subVerb,
                             UserRow actor, String adapter, String requestId) throws SQLException {
        RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                .actorUserId(actor.id)
                .actorContactId(actor.contactId)
                .actorAdapter(adapter)
                .action(AuditAction.ASSET_ENABLE)
                .targetKind(TargetKind.ASSET)
                .targetId(asset + "/" + subVerb)
                .requestId(requestId)
                .build();
        auditLogWriter.write(conn, row);
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    private boolean isGroupAdmin(ScopeRef.Group group) {
        String adapter = inboundContext.adapterName();
        String senderContact = inboundContext.senderContactId();
        Optional<UserRow> user = lookupUser(adapter, senderContact);
        if (user.isEmpty()) {
            return false;
        }
        if (user.get().isAdmin) {
            return true;
        }
        UUID groupDbId = lookupGroupId(adapter, group.adapterGroupId());
        return groupDbId != null
                && groupMembershipRepository.isGroupAdmin(groupDbId, user.get().id);
    }

    private @Nullable UUID lookupGroupId(String adapter, String upstreamGroupId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_GROUP_ID_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, upstreamGroupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getObject("id", UUID.class);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "AssetEnableCommandHandler.lookupGroupId failed", e);
        }
    }

    private record UserRow(UUID id, String contactId, boolean isAdmin) {}

    private record PairRow(boolean enabled, String status) {}
}
