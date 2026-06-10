package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.command.CommandPermissions;
import app.zcat.infochat.provider.command.asset.AssetRegistry;
import app.zcat.infochat.provider.group.GroupMembershipRepository;
import app.zcat.infochat.provider.group.GroupRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Implements the context-aware {@code /help} command per
 * {@code docs/spec/commands.md} §Discovery. The reply is composed from
 * a fixed header, then one short-help line per command the caller is
 * currently permitted to invoke, then (for a probation caller) a footer.
 *
 * <p><b>Per-tier filtering.</b> Per spec §Discovery, the listing is
 * filtered to the exact set the caller may invoke:
 * <ul>
 *   <li>a slow-start probation caller (D45) sees <b>only</b> the
 *       allowed subset — driven from the same {@link CommandPermissions}
 *       predicate the intake probation gate uses, so the welcome message,
 *       the {@code /help} listing, and the probation reply stay mutually
 *       consistent — plus a footer noting fuller access unlocks when
 *       probation ends;</li>
 *   <li>a non-admin caller does not see bot-admin commands;</li>
 *   <li>a group member who is not group admin does not see
 *       group-admin-only commands.</li>
 * </ul>
 * The listing is driven from the closed {@link #CATALOGUE} of
 * {@code (command, bundleKey, tier)} entries rather than a hardcoded
 * line list. Each command's short-help line is its own bundle key (a
 * {@link BundleKeys} constant); the header (DM vs group) and the
 * probation footer are separate keys. CI's bundle-completeness check
 * ({@code BundleLoaderTest}) reflects over {@link BundleKeys} and asserts
 * every constant resolves in {@code en} and {@code cs} — so each catalogue
 * key is checked without that test enumerating the catalogue itself.
 *
 * <p><b>Scope.</b> The header key differs by scope (DM vs group); a
 * group-scope reply lists the group-admin-only commands only to the
 * group admin (or a bot admin acting in the group). Operator-enabled
 * asset commands from {@link AssetRegistry} are appended verbatim (they
 * carry no per-command bundle key — the list is dynamic). Per spec
 * §Asset commands, only operator-enabled assets and enabled sub-verbs
 * appear.</p>
 *
 * <p>Output is plain text per decision D30: no markdown links, no
 * emoji, no auto-formatting beyond the literal bundle strings. The
 * regression guard against an accidental markdown-link bundle value
 * lives in {@code HelpCommandHandlerTest}.</p>
 */
@ApplicationScoped
public class HelpCommandHandler implements CommandHandler {

    private static final String SELECT_CALLER_SQL =
            "SELECT id, is_admin FROM users WHERE adapter = ? AND contact_id = ?";

    /**
     * Per-command visibility tier. {@link #USER_OR_GROUP_ADMIN} captures
     * the v1 dual commands (e.g. {@code /add-source}, {@code /lang})
     * that any user may invoke in DM but only a group admin may invoke
     * in a group — spec §Permission model lists them under the
     * group-admin closed set "in groups" only.
     */
    enum HelpTier {
        /** Any non-banned user, both scopes. */
        USER,
        /** Any user in DM; group admin (or bot admin) in a group. */
        USER_OR_GROUP_ADMIN,
        /** Group admin (or bot admin) in a group; never shown in DM (group-only command). */
        GROUP_ADMIN,
        /** Bot admin only, both scopes. */
        BOT_ADMIN
    }

    /** One catalogue entry: the command name (for the probation predicate), its short-help bundle key, and its visibility tier. */
    record CommandHelp(String command, String bundleKey, HelpTier tier) {}

    /** Resolved tier facts about the caller in the current dispatch. */
    record CallerTier(boolean botAdmin, boolean groupAdmin, boolean probation, boolean group) {}

    /**
     * Closed {@code (command, bundleKey, tier)} catalogue, in display
     * order. The set mirrors the v1 dispatchable command handlers; the
     * tiers mirror spec §Permission model's closed privileged-tier list.
     */
    private static final List<CommandHelp> CATALOGUE = List.of(
            new CommandHelp("help", BundleKeys.HELP_CMD_HELP_SHORT, HelpTier.USER),
            new CommandHelp("status", BundleKeys.HELP_CMD_STATUS_SHORT, HelpTier.USER),
            new CommandHelp("get-tags", BundleKeys.HELP_CMD_GET_TAGS_SHORT, HelpTier.USER),
            new CommandHelp("get-sources", BundleKeys.HELP_CMD_GET_SOURCES_SHORT, HelpTier.USER),
            new CommandHelp("summary", BundleKeys.HELP_CMD_SUMMARY_SHORT, HelpTier.USER),
            new CommandHelp("list-sources", BundleKeys.HELP_CMD_LIST_SOURCES_SHORT, HelpTier.USER),
            new CommandHelp("save", BundleKeys.HELP_CMD_SAVE_SHORT, HelpTier.USER),
            new CommandHelp("saved", BundleKeys.HELP_CMD_SAVED_SHORT, HelpTier.USER),
            new CommandHelp("unsave", BundleKeys.HELP_CMD_UNSAVE_SHORT, HelpTier.USER),
            new CommandHelp("export", BundleKeys.HELP_CMD_EXPORT_SHORT, HelpTier.USER),
            new CommandHelp("add-source", BundleKeys.HELP_CMD_ADD_SOURCE_SHORT, HelpTier.USER_OR_GROUP_ADMIN),
            new CommandHelp("follow-tag", BundleKeys.HELP_CMD_FOLLOW_TAG_SHORT, HelpTier.USER_OR_GROUP_ADMIN),
            new CommandHelp("unfollow-tag", BundleKeys.HELP_CMD_UNFOLLOW_TAG_SHORT, HelpTier.USER_OR_GROUP_ADMIN),
            new CommandHelp("lang", BundleKeys.HELP_CMD_LANG_SHORT, HelpTier.USER_OR_GROUP_ADMIN),
            new CommandHelp("clear", BundleKeys.HELP_CMD_CLEAR_SHORT, HelpTier.USER),
            new CommandHelp("compress", BundleKeys.HELP_CMD_COMPRESS_SHORT, HelpTier.USER),
            new CommandHelp("forget", BundleKeys.HELP_CMD_FORGET_SHORT, HelpTier.USER),
            new CommandHelp("stop", BundleKeys.HELP_CMD_STOP_SHORT, HelpTier.USER),
            new CommandHelp("retry", BundleKeys.HELP_CMD_RETRY_SHORT, HelpTier.USER),
            new CommandHelp("group-timezone", BundleKeys.HELP_CMD_GROUP_TIMEZONE_SHORT, HelpTier.GROUP_ADMIN),
            new CommandHelp("digest", BundleKeys.HELP_CMD_DIGEST_SHORT, HelpTier.GROUP_ADMIN),
            new CommandHelp("grant-admin", BundleKeys.HELP_CMD_GRANT_ADMIN_SHORT, HelpTier.BOT_ADMIN),
            new CommandHelp("revoke-admin", BundleKeys.HELP_CMD_REVOKE_ADMIN_SHORT, HelpTier.BOT_ADMIN),
            new CommandHelp("ban", BundleKeys.HELP_CMD_BAN_SHORT, HelpTier.BOT_ADMIN),
            new CommandHelp("unban", BundleKeys.HELP_CMD_UNBAN_SHORT, HelpTier.BOT_ADMIN),
            new CommandHelp("promote", BundleKeys.HELP_CMD_PROMOTE_SHORT, HelpTier.BOT_ADMIN),
            new CommandHelp("demote", BundleKeys.HELP_CMD_DEMOTE_SHORT, HelpTier.BOT_ADMIN),
            new CommandHelp("vouch", BundleKeys.HELP_CMD_VOUCH_SHORT, HelpTier.BOT_ADMIN),
            new CommandHelp("invite", BundleKeys.HELP_CMD_INVITE_SHORT, HelpTier.BOT_ADMIN),
            new CommandHelp("quarantine", BundleKeys.HELP_CMD_QUARANTINE_SHORT, HelpTier.BOT_ADMIN),
            new CommandHelp("audit", BundleKeys.HELP_CMD_AUDIT_SHORT, HelpTier.BOT_ADMIN),
            new CommandHelp("remove-source", BundleKeys.HELP_CMD_REMOVE_SOURCE_SHORT, HelpTier.BOT_ADMIN),
            new CommandHelp("source-enable", BundleKeys.HELP_CMD_SOURCE_ENABLE_SHORT, HelpTier.BOT_ADMIN),
            new CommandHelp("source-disable", BundleKeys.HELP_CMD_SOURCE_DISABLE_SHORT, HelpTier.BOT_ADMIN),
            new CommandHelp("approve-group", BundleKeys.HELP_CMD_APPROVE_GROUP_SHORT, HelpTier.BOT_ADMIN),
            new CommandHelp("reject-group", BundleKeys.HELP_CMD_REJECT_GROUP_SHORT, HelpTier.BOT_ADMIN),
            new CommandHelp("list-groups", BundleKeys.HELP_CMD_LIST_GROUPS_SHORT, HelpTier.BOT_ADMIN));

    @Inject
    BundleLoader bundleLoader;

    @Inject
    AssetRegistry assetRegistry;

    @Inject
    DataSource dataSource;

    @Inject
    InboundContext inboundContext;

    @Inject
    ProbationCheck probationCheck;

    @Inject
    CommandPermissions commandPermissions;

    @Inject
    GroupRepository groupRepository;

    @Inject
    GroupMembershipRepository groupMembershipRepository;

    @Override
    public String name() {
        return "help";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        CallerTier caller = resolveTier(scope);

        StringBuilder body = new StringBuilder();
        body.append(bundleLoader.get(headerKey(caller), inboundContext.effectiveLanguage()));
        for (CommandHelp entry : CATALOGUE) {
            if (visible(entry, caller)) {
                body.append('\n');
                body.append(bundleLoader.get(entry.bundleKey(), inboundContext.effectiveLanguage()));
            }
        }
        appendEnabledAssets(body);
        if (caller.probation()) {
            body.append('\n');
            body.append(bundleLoader.get(BundleKeys.HELP_FOOTER_PROBATION, inboundContext.effectiveLanguage()));
        }

        return new OutboundMessage(scope, body.toString(), Instant.now(), UUID.randomUUID().toString());
    }

    private static String headerKey(CallerTier caller) {
        return caller.group() ? BundleKeys.HELP_HEADER_GROUP : BundleKeys.HELP_HEADER_DM_USER;
    }

    /**
     * Whether {@code entry} is shown to {@code caller}. A probation
     * caller sees only the allowed subset — delegated to the same
     * {@link CommandPermissions#allowedDuringProbation} predicate the
     * intake gate uses so the two surfaces never diverge. Otherwise the
     * tier decides: bot-admin commands need bot admin; group-admin
     * commands need group scope plus group-admin (or bot-admin); the
     * dual tier is open in DM and group-admin-gated in a group.
     */
    private boolean visible(CommandHelp entry, CallerTier caller) {
        if (caller.probation()) {
            return commandPermissions.allowedDuringProbation(entry.command());
        }
        return switch (entry.tier()) {
            case USER -> true;
            case BOT_ADMIN -> caller.botAdmin();
            case GROUP_ADMIN -> caller.group() && (caller.groupAdmin() || caller.botAdmin());
            case USER_OR_GROUP_ADMIN -> !caller.group() || caller.groupAdmin() || caller.botAdmin();
        };
    }

    private void appendEnabledAssets(StringBuilder body) {
        for (AssetRegistry.AssetEntry asset : assetRegistry.getEnabledAssets()) {
            List<String> subVerbs = asset.enabledSubVerbNames();
            body.append('\n');
            body.append('/').append(asset.name());
            body.append(" [sub-verb] [--vs <currency>] — ");
            body.append(asset.displayName()).append(" market data");
            if (!subVerbs.isEmpty()) {
                body.append(" (").append(String.join(", ", subVerbs)).append(')');
            }
        }
    }

    /**
     * Resolve the caller's tier facts for the current scope. The
     * one-row {@code users} read mirrors the per-handler pattern
     * ({@code StatusCommandHandler}, {@code AddSourceCommandHandler}):
     * the caller is identified by {@code (adapter, contact_id)} via the
     * request-scoped {@link InboundContext}. Group-admin is resolved
     * only in group scope.
     *
     * <p>Package-private + non-final so the plain-JUnit
     * {@code HelpCommandHandlerTest} can subclass and return a fixed
     * {@link CallerTier} without wiring a {@link DataSource} or the
     * group repositories — the catalogue-filtering logic under test
     * does not depend on the DB read. Mirrors the
     * {@code InboundRouter#lookupUser} test seam.</p>
     */
    CallerTier resolveTier(ScopeRef scope) {
        String adapter = inboundContext.adapterName();
        String contactId = scope instanceof ScopeRef.Dm dm
                ? dm.contactId() : inboundContext.senderContactId();
        CallerRow caller = lookupCaller(adapter, contactId);
        boolean probation = probationCheck.inProbation(caller.id());
        boolean groupScope = scope instanceof ScopeRef.Group;
        boolean groupAdmin = false;
        if (scope instanceof ScopeRef.Group group) {
            groupAdmin = groupRepository
                    .findApprovalRow(adapter, group.adapterGroupId())
                    .map(row -> groupMembershipRepository.isGroupAdmin(row.id(), caller.id()))
                    .orElse(false);
        }
        return new CallerTier(caller.isAdmin(), groupAdmin, probation, groupScope);
    }

    private CallerRow lookupCaller(String adapter, String contactId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_CALLER_SQL)) {
            ps.setString(1, adapter);
            ps.setString(2, contactId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // /help is dispatched only after the intake steps
                    // confirmed a registered users row; a miss here is a
                    // wiring invariant violation, not a user-facing case.
                    throw new IllegalStateException(
                            "HelpCommandHandler: no users row for adapter=" + adapter);
                }
                return new CallerRow(rs.getObject("id", UUID.class), rs.getBoolean("is_admin"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("HelpCommandHandler.lookupCaller failed", e);
        }
    }

    private record CallerRow(UUID id, boolean isAdmin) {}
}
