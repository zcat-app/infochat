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
import org.eclipse.microprofile.config.inject.ConfigProperty;
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
import java.util.Locale;
import java.util.Optional;
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
 *
 * <p><b>Per-command detail (M1-573).</b> {@code /help <command>} renders
 * a detail block (usage/arguments + examples, two bundle keys per
 * catalogue command) instead of the list. Visibility reuses the exact
 * bare-list predicate ({@link #visible}), so a command the caller cannot
 * invoke here resolves to the same friendly error as a nonexistent one —
 * the detail view never reveals surface the list already hides. The
 * bot-admin-only {@code /list-sources} flags live in a separate suffix
 * key appended only for a bot admin. An enabled asset renders its
 * existing dynamic short line (per-asset detail is not in v1); anything
 * else gets the unknown-command error with fuzzy suggestions drawn only
 * from the caller-visible names. Only the first argument token is
 * considered; trailing tokens are ignored.</p>
 */
@ApplicationScoped
public class HelpCommandHandler implements CommandHandler {

    private static final String SELECT_CALLER_SQL =
            "SELECT id, is_admin FROM users WHERE adapter = ? AND contact_id = ?";

    /**
     * M1-664: the chat-side tier resolver
     * ({@link #resolveCallerTier}) reads {@code is_admin} by user id
     * (the chat tool's dispatch identity is the {@code (userId, scope)}
     * pair the {@code ChatToolRegistry.ChatTool} SPI carries — no
     * adapter/contact_id, no {@code InboundContext}).
     */
    private static final String SELECT_CALLER_BY_ID_SQL =
            "SELECT is_admin FROM users WHERE id = ?";

    /** Max fuzzy-suggestion entries surfaced in the unknown-command error. */
    private static final int FUZZY_SUGGESTION_MAX = 5;

    /**
     * Per-command visibility tier. {@link #USER_OR_GROUP_ADMIN} captures
     * the v1 dual commands (e.g. {@code /add-source}, {@code /lang})
     * that any user may invoke in DM but only a group admin may invoke
     * in a group — spec §Permission model lists them under the
     * group-admin closed set "in groups" only.
     *
     * <p>M1-664 widens this from package-private to {@code public}: the
     * chat-side {@code HelpLookupTool} (in {@code provider.chat.tool})
     * applies the same tier predicate at lookup time. The enum's
     * semantics are unchanged.
     */
    public enum HelpTier {
        /** Any non-banned user, both scopes. */
        USER,
        /** Any user in DM; group admin (or bot admin) in a group. */
        USER_OR_GROUP_ADMIN,
        /** Group admin (or bot admin) in a group; never shown in DM (group-only command). */
        GROUP_ADMIN,
        /** Bot admin only, both scopes. */
        BOT_ADMIN
    }

    /** One catalogue entry: the command name (for the probation predicate), its short-help / usage-detail / examples bundle keys, and its visibility tier. */
    public record CommandHelp(String command, String bundleKey, String usageKey, String examplesKey, HelpTier tier) {}

    /** Resolved tier facts about the caller in the current dispatch. */
    public record CallerTier(boolean botAdmin, boolean groupAdmin, boolean probation, boolean group) {}

    /**
     * Closed {@code (command, bundleKey, tier)} catalogue, in display
     * order. The set mirrors the v1 dispatchable command handlers; the
     * tiers mirror spec §Permission model's closed privileged-tier list.
     *
     * <p>M1-664 widens this from package-private to {@code public}: the
     * chat-side {@code HelpLookupTool} reads it to compose the matched
     * command's one-line description at call time from the runtime
     * catalogue (the match-not-assert invariant — embedded text is used
     * only for MATCHING, never for ASSERTING). The list stays immutable
     * ({@link List#of}) and is never mutated by the tool.
     */
    public static final List<CommandHelp> CATALOGUE = List.of(
            new CommandHelp("help", BundleKeys.HELP_CMD_HELP_SHORT, BundleKeys.HELP_CMD_HELP_USAGE, BundleKeys.HELP_CMD_HELP_EXAMPLES, HelpTier.USER),
            new CommandHelp("status", BundleKeys.HELP_CMD_STATUS_SHORT, BundleKeys.HELP_CMD_STATUS_USAGE, BundleKeys.HELP_CMD_STATUS_EXAMPLES, HelpTier.USER),
            new CommandHelp("get-tags", BundleKeys.HELP_CMD_GET_TAGS_SHORT, BundleKeys.HELP_CMD_GET_TAGS_USAGE, BundleKeys.HELP_CMD_GET_TAGS_EXAMPLES, HelpTier.USER),
            new CommandHelp("get-sources", BundleKeys.HELP_CMD_GET_SOURCES_SHORT, BundleKeys.HELP_CMD_GET_SOURCES_USAGE, BundleKeys.HELP_CMD_GET_SOURCES_EXAMPLES, HelpTier.USER),
            new CommandHelp("summary", BundleKeys.HELP_CMD_SUMMARY_SHORT, BundleKeys.HELP_CMD_SUMMARY_USAGE, BundleKeys.HELP_CMD_SUMMARY_EXAMPLES, HelpTier.USER),
            new CommandHelp("list-sources", BundleKeys.HELP_CMD_LIST_SOURCES_SHORT, BundleKeys.HELP_CMD_LIST_SOURCES_USAGE, BundleKeys.HELP_CMD_LIST_SOURCES_EXAMPLES, HelpTier.USER),
            new CommandHelp("save", BundleKeys.HELP_CMD_SAVE_SHORT, BundleKeys.HELP_CMD_SAVE_USAGE, BundleKeys.HELP_CMD_SAVE_EXAMPLES, HelpTier.USER),
            new CommandHelp("saved", BundleKeys.HELP_CMD_SAVED_SHORT, BundleKeys.HELP_CMD_SAVED_USAGE, BundleKeys.HELP_CMD_SAVED_EXAMPLES, HelpTier.USER),
            new CommandHelp("unsave", BundleKeys.HELP_CMD_UNSAVE_SHORT, BundleKeys.HELP_CMD_UNSAVE_USAGE, BundleKeys.HELP_CMD_UNSAVE_EXAMPLES, HelpTier.USER),
            new CommandHelp("export", BundleKeys.HELP_CMD_EXPORT_SHORT, BundleKeys.HELP_CMD_EXPORT_USAGE, BundleKeys.HELP_CMD_EXPORT_EXAMPLES, HelpTier.USER),
            new CommandHelp("add-source", BundleKeys.HELP_CMD_ADD_SOURCE_SHORT, BundleKeys.HELP_CMD_ADD_SOURCE_USAGE, BundleKeys.HELP_CMD_ADD_SOURCE_EXAMPLES, HelpTier.USER_OR_GROUP_ADMIN),
            new CommandHelp("follow-all-sources", BundleKeys.HELP_CMD_FOLLOW_ALL_SOURCES_SHORT, BundleKeys.HELP_CMD_FOLLOW_ALL_SOURCES_USAGE, BundleKeys.HELP_CMD_FOLLOW_ALL_SOURCES_EXAMPLES, HelpTier.USER_OR_GROUP_ADMIN),
            new CommandHelp("unfollow-source", BundleKeys.HELP_CMD_UNFOLLOW_SOURCE_SHORT, BundleKeys.HELP_CMD_UNFOLLOW_SOURCE_USAGE, BundleKeys.HELP_CMD_UNFOLLOW_SOURCE_EXAMPLES, HelpTier.USER_OR_GROUP_ADMIN),
            new CommandHelp("follow-tag", BundleKeys.HELP_CMD_FOLLOW_TAG_SHORT, BundleKeys.HELP_CMD_FOLLOW_TAG_USAGE, BundleKeys.HELP_CMD_FOLLOW_TAG_EXAMPLES, HelpTier.USER_OR_GROUP_ADMIN),
            new CommandHelp("unfollow-tag", BundleKeys.HELP_CMD_UNFOLLOW_TAG_SHORT, BundleKeys.HELP_CMD_UNFOLLOW_TAG_USAGE, BundleKeys.HELP_CMD_UNFOLLOW_TAG_EXAMPLES, HelpTier.USER_OR_GROUP_ADMIN),
            new CommandHelp("lang", BundleKeys.HELP_CMD_LANG_SHORT, BundleKeys.HELP_CMD_LANG_USAGE, BundleKeys.HELP_CMD_LANG_EXAMPLES, HelpTier.USER_OR_GROUP_ADMIN),
            new CommandHelp("reply-mode", BundleKeys.HELP_CMD_REPLY_MODE_SHORT, BundleKeys.HELP_CMD_REPLY_MODE_USAGE, BundleKeys.HELP_CMD_REPLY_MODE_EXAMPLES, HelpTier.USER_OR_GROUP_ADMIN),
            new CommandHelp("clear", BundleKeys.HELP_CMD_CLEAR_SHORT, BundleKeys.HELP_CMD_CLEAR_USAGE, BundleKeys.HELP_CMD_CLEAR_EXAMPLES, HelpTier.USER),
            new CommandHelp("compress", BundleKeys.HELP_CMD_COMPRESS_SHORT, BundleKeys.HELP_CMD_COMPRESS_USAGE, BundleKeys.HELP_CMD_COMPRESS_EXAMPLES, HelpTier.USER),
            new CommandHelp("forget", BundleKeys.HELP_CMD_FORGET_SHORT, BundleKeys.HELP_CMD_FORGET_USAGE, BundleKeys.HELP_CMD_FORGET_EXAMPLES, HelpTier.USER),
            new CommandHelp("stop", BundleKeys.HELP_CMD_STOP_SHORT, BundleKeys.HELP_CMD_STOP_USAGE, BundleKeys.HELP_CMD_STOP_EXAMPLES, HelpTier.USER),
            new CommandHelp("retry", BundleKeys.HELP_CMD_RETRY_SHORT, BundleKeys.HELP_CMD_RETRY_USAGE, BundleKeys.HELP_CMD_RETRY_EXAMPLES, HelpTier.USER),
            new CommandHelp("image", BundleKeys.HELP_CMD_IMAGE_SHORT, BundleKeys.HELP_CMD_IMAGE_USAGE, BundleKeys.HELP_CMD_IMAGE_EXAMPLES, HelpTier.USER),
            new CommandHelp("group-timezone", BundleKeys.HELP_CMD_GROUP_TIMEZONE_SHORT, BundleKeys.HELP_CMD_GROUP_TIMEZONE_USAGE, BundleKeys.HELP_CMD_GROUP_TIMEZONE_EXAMPLES, HelpTier.GROUP_ADMIN),
            new CommandHelp("digest", BundleKeys.HELP_CMD_DIGEST_SHORT, BundleKeys.HELP_CMD_DIGEST_USAGE, BundleKeys.HELP_CMD_DIGEST_EXAMPLES, HelpTier.GROUP_ADMIN),
            new CommandHelp("grant-admin", BundleKeys.HELP_CMD_GRANT_ADMIN_SHORT, BundleKeys.HELP_CMD_GRANT_ADMIN_USAGE, BundleKeys.HELP_CMD_GRANT_ADMIN_EXAMPLES, HelpTier.BOT_ADMIN),
            new CommandHelp("revoke-admin", BundleKeys.HELP_CMD_REVOKE_ADMIN_SHORT, BundleKeys.HELP_CMD_REVOKE_ADMIN_USAGE, BundleKeys.HELP_CMD_REVOKE_ADMIN_EXAMPLES, HelpTier.BOT_ADMIN),
            new CommandHelp("ban", BundleKeys.HELP_CMD_BAN_SHORT, BundleKeys.HELP_CMD_BAN_USAGE, BundleKeys.HELP_CMD_BAN_EXAMPLES, HelpTier.BOT_ADMIN),
            new CommandHelp("unban", BundleKeys.HELP_CMD_UNBAN_SHORT, BundleKeys.HELP_CMD_UNBAN_USAGE, BundleKeys.HELP_CMD_UNBAN_EXAMPLES, HelpTier.BOT_ADMIN),
            new CommandHelp("promote", BundleKeys.HELP_CMD_PROMOTE_SHORT, BundleKeys.HELP_CMD_PROMOTE_USAGE, BundleKeys.HELP_CMD_PROMOTE_EXAMPLES, HelpTier.BOT_ADMIN),
            new CommandHelp("demote", BundleKeys.HELP_CMD_DEMOTE_SHORT, BundleKeys.HELP_CMD_DEMOTE_USAGE, BundleKeys.HELP_CMD_DEMOTE_EXAMPLES, HelpTier.BOT_ADMIN),
            new CommandHelp("vouch", BundleKeys.HELP_CMD_VOUCH_SHORT, BundleKeys.HELP_CMD_VOUCH_USAGE, BundleKeys.HELP_CMD_VOUCH_EXAMPLES, HelpTier.BOT_ADMIN),
            new CommandHelp("invite", BundleKeys.HELP_CMD_INVITE_SHORT, BundleKeys.HELP_CMD_INVITE_USAGE, BundleKeys.HELP_CMD_INVITE_EXAMPLES, HelpTier.BOT_ADMIN),
            new CommandHelp("quarantine", BundleKeys.HELP_CMD_QUARANTINE_SHORT, BundleKeys.HELP_CMD_QUARANTINE_USAGE, BundleKeys.HELP_CMD_QUARANTINE_EXAMPLES, HelpTier.BOT_ADMIN),
            new CommandHelp("audit", BundleKeys.HELP_CMD_AUDIT_SHORT, BundleKeys.HELP_CMD_AUDIT_USAGE, BundleKeys.HELP_CMD_AUDIT_EXAMPLES, HelpTier.BOT_ADMIN),
            new CommandHelp("remove-source", BundleKeys.HELP_CMD_REMOVE_SOURCE_SHORT, BundleKeys.HELP_CMD_REMOVE_SOURCE_USAGE, BundleKeys.HELP_CMD_REMOVE_SOURCE_EXAMPLES, HelpTier.BOT_ADMIN),
            new CommandHelp("source-enable", BundleKeys.HELP_CMD_SOURCE_ENABLE_SHORT, BundleKeys.HELP_CMD_SOURCE_ENABLE_USAGE, BundleKeys.HELP_CMD_SOURCE_ENABLE_EXAMPLES, HelpTier.BOT_ADMIN),
            new CommandHelp("source-disable", BundleKeys.HELP_CMD_SOURCE_DISABLE_SHORT, BundleKeys.HELP_CMD_SOURCE_DISABLE_USAGE, BundleKeys.HELP_CMD_SOURCE_DISABLE_EXAMPLES, HelpTier.BOT_ADMIN),
            new CommandHelp("asset-enable", BundleKeys.HELP_CMD_ASSET_ENABLE_SHORT, BundleKeys.HELP_CMD_ASSET_ENABLE_USAGE, BundleKeys.HELP_CMD_ASSET_ENABLE_EXAMPLES, HelpTier.BOT_ADMIN),
            new CommandHelp("approve-group", BundleKeys.HELP_CMD_APPROVE_GROUP_SHORT, BundleKeys.HELP_CMD_APPROVE_GROUP_USAGE, BundleKeys.HELP_CMD_APPROVE_GROUP_EXAMPLES, HelpTier.BOT_ADMIN),
            new CommandHelp("reject-group", BundleKeys.HELP_CMD_REJECT_GROUP_SHORT, BundleKeys.HELP_CMD_REJECT_GROUP_USAGE, BundleKeys.HELP_CMD_REJECT_GROUP_EXAMPLES, HelpTier.BOT_ADMIN),
            new CommandHelp("list-groups", BundleKeys.HELP_CMD_LIST_GROUPS_SHORT, BundleKeys.HELP_CMD_LIST_GROUPS_USAGE, BundleKeys.HELP_CMD_LIST_GROUPS_EXAMPLES, HelpTier.BOT_ADMIN),
            new CommandHelp("pending", BundleKeys.HELP_CMD_PENDING_SHORT, BundleKeys.HELP_CMD_PENDING_USAGE, BundleKeys.HELP_CMD_PENDING_EXAMPLES, HelpTier.BOT_ADMIN),
            new CommandHelp("recover-pool", BundleKeys.HELP_CMD_RECOVER_POOL_SHORT, BundleKeys.HELP_CMD_RECOVER_POOL_USAGE, BundleKeys.HELP_CMD_RECOVER_POOL_EXAMPLES, HelpTier.BOT_ADMIN));

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

    // D73 runtime gating (M1-803): /image exists only with a configured
    // infochat.image.base-url. CDI injects a non-null Optional; null arises
    // only in no-CDI construction and reads as configured.
    @Inject
    @ConfigProperty(name = "infochat.image.base-url")
    Optional<String> imageBaseUrl;

    @Override
    public String name() {
        return "help";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        CallerTier caller = resolveTier(scope);

        String[] tokens = rawText.trim().split("\\s+");
        if (tokens.length > 1) {
            return new OutboundMessage(scope, detailBody(tokens[1], caller),
                    Instant.now(), UUID.randomUUID().toString());
        }

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

    /**
     * Body of the {@code /help <command>} detail reply (M1-573): the
     * usage + examples block for a caller-visible catalogue command,
     * the dynamic short line for an enabled asset, or the
     * unknown-command friendly error. The visibility check runs the
     * exact bare-list predicate, so a hidden-but-existing command and
     * a nonexistent one are indistinguishable to the caller.
     */
    private String detailBody(String requested, CallerTier caller) {
        String language = inboundContext.effectiveLanguage();
        String name = normalizeCommandName(requested);
        for (CommandHelp entry : CATALOGUE) {
            if (entry.command().equals(name) && visible(entry, caller)) {
                return composeDetail(entry, caller, language);
            }
        }
        for (AssetRegistry.AssetEntry asset : assetRegistry.getEnabledAssets()) {
            if (asset.name().equals(name)) {
                StringBuilder line = new StringBuilder();
                appendAssetLine(line, asset, language);
                return line.toString();
            }
        }
        return unknownCommandReply(name, caller, language);
    }

    private String composeDetail(CommandHelp entry, CallerTier caller, String language) {
        StringBuilder body = new StringBuilder();
        body.append(bundleLoader.get(entry.usageKey(), language));
        // /list-sources --all / --include-deleted are bot-admin-only flags
        // (flag-as-identity, spec §Discovery); their lines live in a
        // separate suffix key so a non-admin never sees them. /get-sources
        // (the non-admin alias) strips these flags, so no suffix there.
        if (caller.botAdmin() && entry.command().equals("list-sources")) {
            body.append('\n');
            body.append(bundleLoader.get(BundleKeys.HELP_CMD_LIST_SOURCES_USAGE_ADMIN, language));
        }
        body.append("\n\n");
        body.append(bundleLoader.get(BundleKeys.HELP_DETAIL_EXAMPLES_HEADER, language));
        body.append('\n');
        body.append(bundleLoader.get(entry.examplesKey(), language));
        return body.toString();
    }

    /**
     * Unknown-command friendly error. The suggestion vocabulary is
     * restricted to the names the caller can see in bare {@code /help}
     * (visible catalogue entries plus enabled assets), so the
     * suggestions cannot leak the existence of admin-only or otherwise
     * hidden commands. When nothing clears the match threshold the
     * reply names no commands at all and points at {@code /help}
     * (M1-647) — the predecessor always filled the list, so a query
     * matching nothing was confidently offered the
     * alphabetically-first few names.
     *
     * <p><b>{@code requested} is never echoed.</b> It selects the
     * suggestions and then stops; every byte of the reply is fixed
     * bundle text or a caller-visible catalogue name. Unlike this
     * app's other friendly errors ({@code error.summary.unknown_tag}
     * and friends, which render a bare <code>{0}</code>), the command
     * template used to render <code>`/{0}`</code> — supplying the
     * slash itself, so an inbound word like {@code grant-admin} came
     * back out as the copy-pasteable {@code /grant-admin} to everyone
     * in a group. {@code security.md} §LLM output sanitizer exempts
     * deterministic command output from the admin-command strip on the
     * premise that such output is bot-authored; not interpolating is
     * what makes that premise true here, rather than a filter on which
     * inbound bytes look safe (an earlier attempt at one let every
     * bare privileged name through).
     *
     * <p>It also makes the §Permission model no-existence-leak
     * property exact: with nothing echoed, a hidden-but-real command
     * and a nonexistent one produce byte-identical replies, not merely
     * similarly-shaped ones.
     */
    private String unknownCommandReply(String requested, CallerTier caller, String language) {
        String suggestions = suggestionList(requested, caller);
        if (suggestions.isEmpty()) {
            return bundleLoader.get(BundleKeys.ERROR_UNKNOWN_COMMAND, language);
        }
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.ERROR_HELP_UNKNOWN_COMMAND, language), suggestions);
    }

    /**
     * Rendered {@code "/name, /name"} suggestion list for
     * {@code requested}, or the empty string when nothing is close
     * enough. The vocabulary handed to the resolver is already
     * tier-filtered, so no branch below it can name a hidden command.
     */
    private String suggestionList(String requested, CallerTier caller) {
        List<String> visibleNames = new ArrayList<>();
        for (CommandHelp entry : CATALOGUE) {
            if (visible(entry, caller)) {
                visibleNames.add(entry.command());
            }
        }
        for (AssetRegistry.AssetEntry asset : assetRegistry.getEnabledAssets()) {
            visibleNames.add(asset.name());
        }
        List<String> suggestions =
                CommandIntentSynonyms.suggest(requested, visibleNames, FUZZY_SUGGESTION_MAX);
        StringBuilder joined = new StringBuilder();
        for (String suggestion : suggestions) {
            if (joined.length() > 0) {
                joined.append(", ");
            }
            joined.append('/').append(suggestion);
        }
        return joined.toString();
    }

    /**
     * Suggestion reply for the router's unknown-slash path, or null
     * when no command is close enough to name (M1-647). Public because
     * {@code InboundRouter} reaches it through the CDI client proxy,
     * which only delegates public methods — a package-private call
     * would run against the proxy's own uninitialized fields.
     *
     * <p>Returning null rather than a reply of its own keeps the
     * genuine-no-match answer on the router's existing flat
     * {@code error.unknown_command}, which names no commands and
     * points at {@code /help}. Since neither path interpolates the
     * requested name, the two render byte-identical strings — so
     * {@code /mute} and {@code /help mute} give the same guidance by
     * construction rather than by keeping two templates in step.
     */
    public @Nullable String slashMissSuggestion(ScopeRef scope, String commandName, String language) {
        String suggestions =
                suggestionList(normalizeCommandName(commandName), resolveTier(scope));
        if (suggestions.isEmpty()) {
            return null;
        }
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.ERROR_HELP_UNKNOWN_COMMAND, language), suggestions);
    }

    /** First-token normalization: an optional leading {@code /} is stripped so {@code /help /summary} and {@code /help summary} are equivalent. */
    private static String normalizeCommandName(String requested) {
        String name = requested.startsWith("/") ? requested.substring(1) : requested;
        return name.toLowerCase(Locale.ROOT);
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
     *
     * <p>M1-664 widens this from {@code private} to {@code public}: the
     * chat-side {@code HelpLookupTool} applies the SAME tier predicate
     * at lookup time so a hidden command's name can never enter the
     * LLM's context (tier-filter-before-return, docs/spec/security.md
     * §Prompt-injection defenses). The semantics are unchanged; the
     * method is and remains stateless w.r.t. the instance — every
     * decision branch reads only the {@link CallerTier} argument (or
     * delegates to the stateless {@link CommandPermissions}
     * collaborator), so it is safe to call from any caller.
     */
    public boolean visible(CommandHelp entry, CallerTier caller) {
        if ("image".equals(entry.command()) && !imageConfigured()) {
            // D73 runtime gating: with no infochat.image.base-url the
            // command does not exist, so /help never lists it.
            return false;
        }
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

    /** D73 gate input: the base-url is present. Null-tolerant because a
     * plain-JUnit construction (no CDI) leaves the field null and must
     * still see the full catalogue surface. */
    private boolean imageConfigured() {
        return imageBaseUrl == null || imageBaseUrl.isPresent();
    }

    /**
     * The caller's visible command-name set, for SQL binding as
     * {@code target_ref = ANY(?)} inside the intent-lookup WHERE (M1-664,
     * reused by the M1-665 deterministic delivery trigger). Tier-filter-
     * before-return: an invisible command's name is absent from this
     * list, so it can never be matched by the pgvector probe. The same
     * {@link #visible} predicate {@code /help} applies at listing time.
     */
    public List<String> visibleCommandNames(CallerTier caller) {
        List<String> names = new ArrayList<>();
        for (CommandHelp entry : CATALOGUE) {
            if (visible(entry, caller)) {
                names.add(entry.command());
            }
        }
        return names;
    }

    /**
     * Compose the usage+examples block for a single command via the
     * same runtime path {@code /help <cmd>} takes (M1-665). Returns
     * empty when the command is not in {@link #CATALOGUE} OR not
     * visible to the caller — a defense-in-depth visibility check that
     * catches a match the SQL tier filter should already have excluded
     * (and would catch a future regression in that filter). Never
     * interpolates inbound bytes: every byte of the returned block is
     * fixed bundle text resolved against the caller's scope language.
     * The caller's tier is resolved by the caller of this method (the
     * chat delivery trigger passes the same {@code CallerTier} it used
     * to bind the SQL {@code ANY(?)}, so the two paths cannot diverge).
     */
    public Optional<String> composeUsageBlock(String commandName, CallerTier caller, String language) {
        String name = normalizeCommandName(commandName);
        for (CommandHelp entry : CATALOGUE) {
            if (entry.command().equals(name) && visible(entry, caller)) {
                return Optional.of(composeDetail(entry, caller, language));
            }
        }
        return Optional.empty();
    }

    private void appendEnabledAssets(StringBuilder body) {
        String language = inboundContext.effectiveLanguage();
        for (AssetRegistry.AssetEntry asset : assetRegistry.getEnabledAssets()) {
            body.append('\n');
            appendAssetLine(body, asset, language);
        }
    }

    private void appendAssetLine(StringBuilder body, AssetRegistry.AssetEntry asset, String language) {
        List<String> subVerbs = asset.enabledSubVerbNames();
        body.append(MessageFormat.format(
                bundleLoader.get(BundleKeys.HELP_CMD_ASSET_LINE, language),
                asset.name(), asset.displayName()));
        // The enabled sub-verb names are literal command tokens, not
        // translatable prose; the parenthetical wrapper stays in code.
        if (!subVerbs.isEmpty()) {
            body.append(" (").append(String.join(", ", subVerbs)).append(')');
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

    /**
     * Resolve the caller's tier facts from the chat-tool dispatch
     * identity ({@code userId, scopeKind, scopeId}), used by the
     * chat-side {@code HelpLookupTool} to apply the same tier filter
     * {@link #resolveTier(ScopeRef)} applies at listing time
     * (M1-664 — tier-filter-before-return,
     * docs/spec/security.md §Prompt-injection defenses).
     *
     * <p>The chat-tool dispatch identity is the {@code (userId, scope)}
     * pair the {@code ChatToolRegistry.ChatTool} SPI carries; it has
     * no {@link ScopeRef} and no {@link InboundContext} (the chat
     * worker runs under a fresh, seeded context, NOT the intake one),
     * so this method reads the {@code users} row by {@code id}
     * directly and resolves group-admin via the {@code scopeId}
     * (which IS the group UUID in group scope) — the
     * {@link GroupMembershipRepository#isGroupAdmin(UUID, UUID)} path
     * the listing-side {@code resolveTier} also bottoms-outs on after
     * resolving the {@link ScopeRef.Group} → {@code approval row.id}.
     *
     * <p>Probation is consulted even though the chat path is
     * dispatch-reachable from probation senders: a probation caller's
     * visible set collapses to the probation-allowlist subset (the
     * same {@link CommandPermissions#allowedDuringProbation}
     * predicate the intake gate uses), so a probation caller's LLM
     * can never learn an admin command's name from the index.
     */
    public CallerTier resolveCallerTier(UUID userId, String scopeKind, UUID scopeId) {
        boolean botAdmin = lookupIsAdminById(userId);
        boolean group = "group".equals(scopeKind);
        boolean groupAdmin = group
                && scopeId != null
                && groupMembershipRepository.isGroupAdmin(scopeId, userId);
        boolean probation = probationCheck.inProbation(userId);
        return new CallerTier(botAdmin, groupAdmin, probation, group);
    }

    private boolean lookupIsAdminById(UUID userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_CALLER_BY_ID_SQL)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "HelpCommandHandler: no users row for id=" + userId);
                }
                return rs.getBoolean("is_admin");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("HelpCommandHandler.lookupIsAdminById failed", e);
        }
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
