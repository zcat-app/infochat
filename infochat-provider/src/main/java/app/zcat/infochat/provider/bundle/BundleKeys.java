package app.zcat.infochat.provider.bundle;

/**
 * Compile-time constants for every deterministic-bundle key the Provider
 * looks up via {@link BundleLoader}. Per decision D43 and
 * {@code docs/design/03-commands.md} §3.4, every user-visible
 * deterministic string Provider emits — slash-command help text,
 * friendly errors, the chat-mode-not-in-MVP stub reply — flows through
 * the bundle so T2-C can drop {@code cs.properties} in without code
 * changes.
 *
 * <p>A typo in a key name fails at compile time at the call site
 * (the constant disappears); the bundle-completeness assertion in
 * {@code BundleLoaderTest} catches a typo at test time when the
 * constant resolves to a missing bundle entry. The assertion
 * iterates every {@code public static final String} on this class
 * via reflection, so adding a new constant here automatically
 * extends the CI guard at the next test run — no test edit
 * required.</p>
 */
public final class BundleKeys {

    /** Header line at the top of the {@code /help} reply for the DM-user actor tier. */
    public static final String HELP_HEADER_DM_USER = "help.header.dm-user";

    /** Short-help line for {@code /help} itself. */
    public static final String HELP_CMD_HELP_SHORT = "help.cmd.help.short";

    /** Short-help line for {@code /add-source}. Authored here so T1-F's implementation lands without bundle churn. */
    public static final String HELP_CMD_ADD_SOURCE_SHORT = "help.cmd.add-source.short";

    /** Short-help line for {@code /summary}. Authored here so T1-F's implementation lands without bundle churn. */
    public static final String HELP_CMD_SUMMARY_SHORT = "help.cmd.summary.short";

    // ----- /help per-tier catalogue (M1-138) ------------------------------
    // Per docs/spec/commands.md §Discovery (Bundle composition): /help is
    // composed from one short-help bundle key per command, filtered by the
    // caller's tier (probation / non-admin / non-group-admin) and scope
    // (DM vs group header), with a probation-tier footer. The header keys,
    // the footer key, and every per-command help-line key below are
    // BundleKeys constants so the reflection-based bundle-completeness CI
    // (BundleLoaderTest) asserts each one resolves in en + cs. The closed
    // (command, bundleKey, tier) catalogue lives in HelpCommandHandler.

    /** Header line at the top of the {@code /help} reply when invoked in a group scope. */
    public static final String HELP_HEADER_GROUP = "help.header.group";

    /** Footer appended to the {@code /help} reply for a slow-start probation caller, noting that fuller access (and chat mode) unlocks when probation ends. */
    public static final String HELP_FOOTER_PROBATION = "help.footer.probation";

    /** Short-help line for {@code /status}. */
    public static final String HELP_CMD_STATUS_SHORT = "help.cmd.status.short";

    /** Short-help line for {@code /get-tags}. */
    public static final String HELP_CMD_GET_TAGS_SHORT = "help.cmd.get-tags.short";

    /** Short-help line for {@code /get-sources}. */
    public static final String HELP_CMD_GET_SOURCES_SHORT = "help.cmd.get-sources.short";

    /** Short-help line for {@code /list-sources}. */
    public static final String HELP_CMD_LIST_SOURCES_SHORT = "help.cmd.list-sources.short";

    /** Short-help line for {@code /save}. */
    public static final String HELP_CMD_SAVE_SHORT = "help.cmd.save.short";

    /** Short-help line for {@code /saved}. */
    public static final String HELP_CMD_SAVED_SHORT = "help.cmd.saved.short";

    /** Short-help line for {@code /unsave}. */
    public static final String HELP_CMD_UNSAVE_SHORT = "help.cmd.unsave.short";

    /** Short-help line for {@code /export}. */
    public static final String HELP_CMD_EXPORT_SHORT = "help.cmd.export.short";

    /** Short-help line for {@code /follow-tag}. */
    public static final String HELP_CMD_FOLLOW_TAG_SHORT = "help.cmd.follow-tag.short";

    /** Short-help line for {@code /unfollow-tag}. */
    public static final String HELP_CMD_UNFOLLOW_TAG_SHORT = "help.cmd.unfollow-tag.short";

    /** Short-help line for {@code /lang}. */
    public static final String HELP_CMD_LANG_SHORT = "help.cmd.lang.short";

    /** Short-help line for {@code /clear}. */
    public static final String HELP_CMD_CLEAR_SHORT = "help.cmd.clear.short";

    /** Short-help line for {@code /compress}. */
    public static final String HELP_CMD_COMPRESS_SHORT = "help.cmd.compress.short";

    /** Short-help line for {@code /forget}. */
    public static final String HELP_CMD_FORGET_SHORT = "help.cmd.forget.short";

    /** Short-help line for {@code /stop}. */
    public static final String HELP_CMD_STOP_SHORT = "help.cmd.stop.short";

    /** Short-help line for {@code /retry}. */
    public static final String HELP_CMD_RETRY_SHORT = "help.cmd.retry.short";

    /** Short-help line for {@code /group-timezone} (group-admin tier). */
    public static final String HELP_CMD_GROUP_TIMEZONE_SHORT = "help.cmd.group-timezone.short";

    /** Short-help line for {@code /grant-admin} (bot-admin tier). */
    public static final String HELP_CMD_GRANT_ADMIN_SHORT = "help.cmd.grant-admin.short";

    /** Short-help line for {@code /revoke-admin} (bot-admin tier). */
    public static final String HELP_CMD_REVOKE_ADMIN_SHORT = "help.cmd.revoke-admin.short";

    /** Short-help line for {@code /ban} (bot-admin tier). */
    public static final String HELP_CMD_BAN_SHORT = "help.cmd.ban.short";

    /** Short-help line for {@code /unban} (bot-admin tier). */
    public static final String HELP_CMD_UNBAN_SHORT = "help.cmd.unban.short";

    /** Short-help line for {@code /promote} (bot-admin tier). */
    public static final String HELP_CMD_PROMOTE_SHORT = "help.cmd.promote.short";

    /** Short-help line for {@code /demote} (bot-admin tier). */
    public static final String HELP_CMD_DEMOTE_SHORT = "help.cmd.demote.short";

    /** Short-help line for {@code /vouch} (bot-admin tier). */
    public static final String HELP_CMD_VOUCH_SHORT = "help.cmd.vouch.short";

    /** Short-help line for {@code /invite} (bot-admin tier). */
    public static final String HELP_CMD_INVITE_SHORT = "help.cmd.invite.short";

    /** Short-help line for {@code /quarantine} (bot-admin tier). */
    public static final String HELP_CMD_QUARANTINE_SHORT = "help.cmd.quarantine.short";

    /** Short-help line for {@code /audit} (bot-admin tier). */
    public static final String HELP_CMD_AUDIT_SHORT = "help.cmd.audit.short";

    /** Short-help line for {@code /remove-source} (bot-admin tier). */
    public static final String HELP_CMD_REMOVE_SOURCE_SHORT = "help.cmd.remove-source.short";

    /** Short-help line for {@code /unfollow-source} (user-or-group-admin tier). */
    public static final String HELP_CMD_UNFOLLOW_SOURCE_SHORT = "help.cmd.unfollow-source.short";

    /** Short-help line for {@code /follow-all-sources} (user-or-group-admin tier). */
    public static final String HELP_CMD_FOLLOW_ALL_SOURCES_SHORT = "help.cmd.follow-all-sources.short";

    /** Short-help line for {@code /source-enable} (bot-admin tier). */
    public static final String HELP_CMD_SOURCE_ENABLE_SHORT = "help.cmd.source-enable.short";

    /** Short-help line for {@code /source-disable} (bot-admin tier). */
    public static final String HELP_CMD_SOURCE_DISABLE_SHORT = "help.cmd.source-disable.short";

    /** Short-help line for {@code /approve-group} (bot-admin tier). */
    public static final String HELP_CMD_APPROVE_GROUP_SHORT = "help.cmd.approve-group.short";

    /** Short-help line for {@code /reject-group} (bot-admin tier). */
    public static final String HELP_CMD_REJECT_GROUP_SHORT = "help.cmd.reject-group.short";

    /** Short-help line for {@code /list-groups} (bot-admin tier). */
    public static final String HELP_CMD_LIST_GROUPS_SHORT = "help.cmd.list-groups.short";

    /** Short-help line for {@code /pending} (bot-admin tier). */
    public static final String HELP_CMD_PENDING_SHORT = "help.cmd.pending.short";

    /** Short-help line for {@code /recover-pool} (bot-admin tier). */
    public static final String HELP_CMD_RECOVER_POOL_SHORT = "help.cmd.recover-pool.short";

    /**
     * Per-asset {@code /help} line template (M1-303). One key for the whole
     * dynamic asset list (unlike the per-command {@code help.cmd.*.short}
     * keys) since the enabled-asset set is operator-driven, not a fixed
     * catalogue. Tokens: {@code {0}} = asset command name (e.g. {@code zcash}),
     * {@code {1}} = display name (e.g. {@code Zcash}). The command syntax
     * placeholders ({@code [sub-verb]}, {@code [--vs <currency>]}) stay
     * verbatim per the help-line convention; only the prose is translated.
     * The trailing enabled-sub-verb parenthetical is appended by the handler
     * (the names are literal command tokens, not translatable prose).
     */
    public static final String HELP_CMD_ASSET_LINE = "help.cmd.asset.line";

    // ----- /help <command> per-command detail (M1-573) --------------------
    // Per docs/spec/commands.md §Discovery (Per-command detail): every
    // catalogue command carries two detail keys — .usage (signature +
    // description + indented argument/flag lines) and .examples (indented
    // example invocations) — composed by HelpCommandHandler with the shared
    // examples-header key. help.cmd.list-sources.usage.admin holds the
    // bot-admin-only flag lines (--all / --include-deleted are
    // flag-as-identity, admin-only) appended only for a bot admin, so the
    // detail view never widens what the bare list already hides.

    /** Shared "Examples" section header for every {@code /help <command>} detail block. */
    public static final String HELP_DETAIL_EXAMPLES_HEADER = "help.detail.examples.header";

    /**
     * Unknown command (either {@code /help <command>} or a bare unrecognized slash) that HAS a
     * close match — suggestions drawn only from the caller-visible command set. Interpolates the
     * suggestions only, never the requested name: this template used to render the name as
     * <code>`/{0}`</code>, which supplied the slash and turned an inbound word into a
     * copy-pasteable command in bot output (M1-647).
     */
    public static final String ERROR_HELP_UNKNOWN_COMMAND = "error.help.unknown_command";

    /** {@code /help} usage detail. */
    public static final String HELP_CMD_HELP_USAGE = "help.cmd.help.usage";

    /** {@code /help} examples. */
    public static final String HELP_CMD_HELP_EXAMPLES = "help.cmd.help.examples";

    /** {@code /status} usage detail. */
    public static final String HELP_CMD_STATUS_USAGE = "help.cmd.status.usage";

    /** {@code /status} examples. */
    public static final String HELP_CMD_STATUS_EXAMPLES = "help.cmd.status.examples";

    /** {@code /get-tags} usage detail. */
    public static final String HELP_CMD_GET_TAGS_USAGE = "help.cmd.get-tags.usage";

    /** {@code /get-tags} examples. */
    public static final String HELP_CMD_GET_TAGS_EXAMPLES = "help.cmd.get-tags.examples";

    /** {@code /get-sources} usage detail. */
    public static final String HELP_CMD_GET_SOURCES_USAGE = "help.cmd.get-sources.usage";

    /** {@code /get-sources} examples. */
    public static final String HELP_CMD_GET_SOURCES_EXAMPLES = "help.cmd.get-sources.examples";

    /** {@code /summary} usage detail. */
    public static final String HELP_CMD_SUMMARY_USAGE = "help.cmd.summary.usage";

    /** {@code /summary} examples. */
    public static final String HELP_CMD_SUMMARY_EXAMPLES = "help.cmd.summary.examples";

    /** {@code /list-sources} usage detail (non-admin surface). */
    public static final String HELP_CMD_LIST_SOURCES_USAGE = "help.cmd.list-sources.usage";

    /** {@code /list-sources} bot-admin-only flag lines ({@code --all}, {@code --include-deleted}), appended after the usage detail for a bot admin only. */
    public static final String HELP_CMD_LIST_SOURCES_USAGE_ADMIN = "help.cmd.list-sources.usage.admin";

    /** {@code /list-sources} examples. */
    public static final String HELP_CMD_LIST_SOURCES_EXAMPLES = "help.cmd.list-sources.examples";

    /** {@code /save} usage detail. */
    public static final String HELP_CMD_SAVE_USAGE = "help.cmd.save.usage";

    /** {@code /save} examples. */
    public static final String HELP_CMD_SAVE_EXAMPLES = "help.cmd.save.examples";

    /** {@code /saved} usage detail. */
    public static final String HELP_CMD_SAVED_USAGE = "help.cmd.saved.usage";

    /** {@code /saved} examples. */
    public static final String HELP_CMD_SAVED_EXAMPLES = "help.cmd.saved.examples";

    /** {@code /unsave} usage detail. */
    public static final String HELP_CMD_UNSAVE_USAGE = "help.cmd.unsave.usage";

    /** {@code /unsave} examples. */
    public static final String HELP_CMD_UNSAVE_EXAMPLES = "help.cmd.unsave.examples";

    /** {@code /export} usage detail. */
    public static final String HELP_CMD_EXPORT_USAGE = "help.cmd.export.usage";

    /** {@code /export} examples. */
    public static final String HELP_CMD_EXPORT_EXAMPLES = "help.cmd.export.examples";

    /** {@code /add-source} usage detail. */
    public static final String HELP_CMD_ADD_SOURCE_USAGE = "help.cmd.add-source.usage";

    /** {@code /add-source} examples. */
    public static final String HELP_CMD_ADD_SOURCE_EXAMPLES = "help.cmd.add-source.examples";

    /** {@code /follow-all-sources} usage detail. */
    public static final String HELP_CMD_FOLLOW_ALL_SOURCES_USAGE = "help.cmd.follow-all-sources.usage";

    /** {@code /follow-all-sources} examples. */
    public static final String HELP_CMD_FOLLOW_ALL_SOURCES_EXAMPLES = "help.cmd.follow-all-sources.examples";

    /** {@code /unfollow-source} usage detail. */
    public static final String HELP_CMD_UNFOLLOW_SOURCE_USAGE = "help.cmd.unfollow-source.usage";

    /** {@code /unfollow-source} examples. */
    public static final String HELP_CMD_UNFOLLOW_SOURCE_EXAMPLES = "help.cmd.unfollow-source.examples";

    /** {@code /follow-tag} usage detail. */
    public static final String HELP_CMD_FOLLOW_TAG_USAGE = "help.cmd.follow-tag.usage";

    /** {@code /follow-tag} examples. */
    public static final String HELP_CMD_FOLLOW_TAG_EXAMPLES = "help.cmd.follow-tag.examples";

    /** {@code /unfollow-tag} usage detail. */
    public static final String HELP_CMD_UNFOLLOW_TAG_USAGE = "help.cmd.unfollow-tag.usage";

    /** {@code /unfollow-tag} examples. */
    public static final String HELP_CMD_UNFOLLOW_TAG_EXAMPLES = "help.cmd.unfollow-tag.examples";

    /** {@code /lang} usage detail. */
    public static final String HELP_CMD_LANG_USAGE = "help.cmd.lang.usage";

    /** {@code /lang} examples. */
    public static final String HELP_CMD_LANG_EXAMPLES = "help.cmd.lang.examples";

    /** {@code /clear} usage detail. */
    public static final String HELP_CMD_CLEAR_USAGE = "help.cmd.clear.usage";

    /** {@code /clear} examples. */
    public static final String HELP_CMD_CLEAR_EXAMPLES = "help.cmd.clear.examples";

    /** {@code /compress} usage detail. */
    public static final String HELP_CMD_COMPRESS_USAGE = "help.cmd.compress.usage";

    /** {@code /compress} examples. */
    public static final String HELP_CMD_COMPRESS_EXAMPLES = "help.cmd.compress.examples";

    /** {@code /forget} usage detail. */
    public static final String HELP_CMD_FORGET_USAGE = "help.cmd.forget.usage";

    /** {@code /forget} examples. */
    public static final String HELP_CMD_FORGET_EXAMPLES = "help.cmd.forget.examples";

    /** {@code /stop} usage detail. */
    public static final String HELP_CMD_STOP_USAGE = "help.cmd.stop.usage";

    /** {@code /stop} examples. */
    public static final String HELP_CMD_STOP_EXAMPLES = "help.cmd.stop.examples";

    /** {@code /retry} usage detail. */
    public static final String HELP_CMD_RETRY_USAGE = "help.cmd.retry.usage";

    /** {@code /retry} examples. */
    public static final String HELP_CMD_RETRY_EXAMPLES = "help.cmd.retry.examples";

    /** {@code /group-timezone} usage detail. */
    public static final String HELP_CMD_GROUP_TIMEZONE_USAGE = "help.cmd.group-timezone.usage";

    /** {@code /group-timezone} examples. */
    public static final String HELP_CMD_GROUP_TIMEZONE_EXAMPLES = "help.cmd.group-timezone.examples";

    /** {@code /digest} usage detail. */
    public static final String HELP_CMD_DIGEST_USAGE = "help.cmd.digest.usage";

    /** {@code /digest} examples. */
    public static final String HELP_CMD_DIGEST_EXAMPLES = "help.cmd.digest.examples";

    /** {@code /grant-admin} usage detail. */
    public static final String HELP_CMD_GRANT_ADMIN_USAGE = "help.cmd.grant-admin.usage";

    /** {@code /grant-admin} examples. */
    public static final String HELP_CMD_GRANT_ADMIN_EXAMPLES = "help.cmd.grant-admin.examples";

    /** {@code /revoke-admin} usage detail. */
    public static final String HELP_CMD_REVOKE_ADMIN_USAGE = "help.cmd.revoke-admin.usage";

    /** {@code /revoke-admin} examples. */
    public static final String HELP_CMD_REVOKE_ADMIN_EXAMPLES = "help.cmd.revoke-admin.examples";

    /** {@code /ban} usage detail. */
    public static final String HELP_CMD_BAN_USAGE = "help.cmd.ban.usage";

    /** {@code /ban} examples. */
    public static final String HELP_CMD_BAN_EXAMPLES = "help.cmd.ban.examples";

    /** {@code /unban} usage detail. */
    public static final String HELP_CMD_UNBAN_USAGE = "help.cmd.unban.usage";

    /** {@code /unban} examples. */
    public static final String HELP_CMD_UNBAN_EXAMPLES = "help.cmd.unban.examples";

    /** {@code /promote} usage detail. */
    public static final String HELP_CMD_PROMOTE_USAGE = "help.cmd.promote.usage";

    /** {@code /promote} examples. */
    public static final String HELP_CMD_PROMOTE_EXAMPLES = "help.cmd.promote.examples";

    /** {@code /demote} usage detail. */
    public static final String HELP_CMD_DEMOTE_USAGE = "help.cmd.demote.usage";

    /** {@code /demote} examples. */
    public static final String HELP_CMD_DEMOTE_EXAMPLES = "help.cmd.demote.examples";

    /** {@code /vouch} usage detail. */
    public static final String HELP_CMD_VOUCH_USAGE = "help.cmd.vouch.usage";

    /** {@code /vouch} examples. */
    public static final String HELP_CMD_VOUCH_EXAMPLES = "help.cmd.vouch.examples";

    /** {@code /invite} usage detail. */
    public static final String HELP_CMD_INVITE_USAGE = "help.cmd.invite.usage";

    /** {@code /invite} examples. */
    public static final String HELP_CMD_INVITE_EXAMPLES = "help.cmd.invite.examples";

    /** {@code /quarantine} usage detail. */
    public static final String HELP_CMD_QUARANTINE_USAGE = "help.cmd.quarantine.usage";

    /** {@code /quarantine} examples. */
    public static final String HELP_CMD_QUARANTINE_EXAMPLES = "help.cmd.quarantine.examples";

    /** {@code /audit} usage detail. */
    public static final String HELP_CMD_AUDIT_USAGE = "help.cmd.audit.usage";

    /** {@code /audit} examples. */
    public static final String HELP_CMD_AUDIT_EXAMPLES = "help.cmd.audit.examples";

    /** {@code /remove-source} usage detail. */
    public static final String HELP_CMD_REMOVE_SOURCE_USAGE = "help.cmd.remove-source.usage";

    /** {@code /remove-source} examples. */
    public static final String HELP_CMD_REMOVE_SOURCE_EXAMPLES = "help.cmd.remove-source.examples";

    /** {@code /source-enable} usage detail. */
    public static final String HELP_CMD_SOURCE_ENABLE_USAGE = "help.cmd.source-enable.usage";

    /** {@code /source-enable} examples. */
    public static final String HELP_CMD_SOURCE_ENABLE_EXAMPLES = "help.cmd.source-enable.examples";

    /** {@code /source-disable} usage detail. */
    public static final String HELP_CMD_SOURCE_DISABLE_USAGE = "help.cmd.source-disable.usage";

    /** {@code /source-disable} examples. */
    public static final String HELP_CMD_SOURCE_DISABLE_EXAMPLES = "help.cmd.source-disable.examples";

    /** {@code /approve-group} usage detail. */
    public static final String HELP_CMD_APPROVE_GROUP_USAGE = "help.cmd.approve-group.usage";

    /** {@code /approve-group} examples. */
    public static final String HELP_CMD_APPROVE_GROUP_EXAMPLES = "help.cmd.approve-group.examples";

    /** {@code /reject-group} usage detail. */
    public static final String HELP_CMD_REJECT_GROUP_USAGE = "help.cmd.reject-group.usage";

    /** {@code /reject-group} examples. */
    public static final String HELP_CMD_REJECT_GROUP_EXAMPLES = "help.cmd.reject-group.examples";

    /** {@code /list-groups} usage detail. */
    public static final String HELP_CMD_LIST_GROUPS_USAGE = "help.cmd.list-groups.usage";

    /** {@code /list-groups} examples. */
    public static final String HELP_CMD_LIST_GROUPS_EXAMPLES = "help.cmd.list-groups.examples";

    /** {@code /pending} usage detail. */
    public static final String HELP_CMD_PENDING_USAGE = "help.cmd.pending.usage";

    /** {@code /pending} examples. */
    public static final String HELP_CMD_PENDING_EXAMPLES = "help.cmd.pending.examples";

    /** {@code /recover-pool} usage detail. */
    public static final String HELP_CMD_RECOVER_POOL_USAGE = "help.cmd.recover-pool.usage";

    /** {@code /recover-pool} examples. */
    public static final String HELP_CMD_RECOVER_POOL_EXAMPLES = "help.cmd.recover-pool.examples";

    /** Deterministic reply for an unknown slash command, looked up by InboundRouter's slash dispatch in the requester's effective scope language. */
    public static final String ERROR_UNKNOWN_COMMAND = "error.unknown_command";

    /** Deterministic reply for any uncaught dispatch exception, looked up by InboundRouter's catch-all; the exception's own message is NEVER interpolated into it. */
    public static final String ERROR_INTERNAL = "error.internal";

    /** Deterministic reply for non-slash chat input until T2-D wires the chat dispatcher. Same M1-035b literal/bundle divergence note. */
    public static final String CHAT_MODE_NOT_IN_MVP = "chat_mode.not_in_mvp";

    // ----- /add-source friendly errors (M1-036) ---------------------------
    // Per docs/spec/commands.md §Source management. Every parse / probe
    // / permission rejection path the spec assigns a friendly error to
    // is keyed here so the handler never interpolates an exception
    // message into the user-visible body. {0}/{1} interpolation tokens
    // are filled by the caller via java.text.MessageFormat.

    /** {@code /add-source} called without {@code --tags}, or with empty {@code --tags=}. */
    public static final String ERROR_ADD_SOURCE_TAGS_REQUIRED = "error.add_source.tags_required";

    /** {@code /add-source --type=<unknown>} — value is not in the closed {@code source.kind} set. */
    public static final String ERROR_ADD_SOURCE_UNKNOWN_KIND = "error.add_source.unknown_kind";

    /** {@code /add-source --category=<unknown>} — value is not in {@code news|blog|social}. */
    public static final String ERROR_ADD_SOURCE_UNKNOWN_CATEGORY = "error.add_source.unknown_category";

    /** {@code /add-source <url>} where {@code <url>} lacks a scheme or host. */
    public static final String ERROR_ADD_SOURCE_MALFORMED_URL = "error.add_source.malformed_url";

    /** URL probe got 4xx/5xx or a connect failure that is not SSRF/timeout. */
    public static final String ERROR_ADD_SOURCE_URL_UNREACHABLE = "error.add_source.url_unreachable";

    /** SSRF guard rejected the probe (localhost / RFC1918 / metadata IP / blocked scheme). */
    public static final String ERROR_ADD_SOURCE_URL_BLOCKED_SSRF = "error.add_source.url_blocked_ssrf";

    /** Probe exceeded the per-read or total wall-clock deadline. */
    public static final String ERROR_ADD_SOURCE_URL_TIMEOUT = "error.add_source.url_timeout";

    /** Resolver could not pick a kind AND the probe's {@code Content-Type} contradicted the URL hint. */
    public static final String ERROR_ADD_SOURCE_AMBIGUOUS_URL = "error.add_source.ambiguous_url";

    /** Caller is banned (handler's own ban check; defense-in-depth — T2-A also gates upstream). */
    public static final String ERROR_ADD_SOURCE_BANNED = "error.add_source.banned";

    /** Caller invoked {@code /add-source} in a group scope without group-admin privilege. */
    public static final String ERROR_ADD_SOURCE_GROUP_ADMIN_ONLY = "error.add_source.group_admin_only";

    /**
     * Caller passed an explicit non-nitter {@code --type} for a URL whose host
     * is a configured Nitter instance (M1-456). {@code {0}} = the canonical
     * host. Forcing the wrong kind would duplicate-fetch the feed under two
     * {@code source.kind} rows, so the resolver refuses it.
     */
    public static final String ERROR_ADD_SOURCE_NITTER_HOST_TYPE_CONFLICT =
            "error.add_source.nitter_host_type_conflict";

    // ----- /add-source successful replies (M1-036) ------------------------

    /** Branch A — fresh insert. {@code {0}} = source display name. */
    public static final String REPLY_ADD_SOURCE_FRESH_INSERT = "reply.add_source.fresh_insert";

    /** Branch B — non-admin caller subscribed to an existing source (tags ignored). */
    public static final String REPLY_ADD_SOURCE_SUBSCRIBED_EXISTING = "reply.add_source.subscribed_existing";

    /** Branch C — bot-admin caller replaced {@code bootstrap_tags} on an existing source. */
    public static final String REPLY_ADD_SOURCE_ADMIN_TAGS_REPLACED = "reply.add_source.admin_tags_replaced";

    /**
     * Branch A appendix — operator-visibility disclosure per
     * {@code docs/spec/security.md} §Source URL visibility. The literal
     * substring {@code visible to bot admins} is asserted by
     * {@code AddSourceCommandHandlerTest}.
     */
    public static final String REPLY_ADD_SOURCE_URL_VISIBILITY_DISCLOSURE =
            "reply.add_source.url_visibility_disclosure";

    // ----- /summary friendly errors (M1-037) ------------------------------
    // Per docs/spec/commands.md §Content (`/summary`) +
    // docs/design/03-commands.md §Time window flag. The eight keys below
    // mirror the friendly-error / reply paths the spec assigns to /summary.

    /** {@code /summary -w 5m}: the {@code m} suffix is intentionally rejected per design §Time window flag. */
    public static final String ERROR_SUMMARY_WINDOW_MINUTES_NOT_ACCEPTED =
            "error.summary.window_minutes_not_accepted";

    /** {@code /summary -w 200h}: window outside the 1h–168h / 1d–30d / 1w–4w range. */
    public static final String ERROR_SUMMARY_WINDOW_OUT_OF_RANGE =
            "error.summary.window_out_of_range";

    /** {@code /summary <tag>}: tag is not in the controlled vocabulary; bundle template surfaces a fuzzy-suggestion footer. */
    public static final String ERROR_SUMMARY_UNKNOWN_TAG = "error.summary.unknown_tag";

    /** {@code /summary <tag>}: tag failed the {@code ^[a-z0-9][a-z0-9-]{0,47}$} inline regex / length cap. */
    public static final String ERROR_SUMMARY_TAG_MALFORMED = "error.summary.tag_malformed";

    // ----- /summary successful / informational replies (M1-037) -----------

    /** Empty result — subscribed, but zero eligible posts in the window. Zero-subscription scopes get {@link #REPLY_SUMMARY_NO_SUBSCRIPTIONS} instead (M1-593). */
    public static final String REPLY_SUMMARY_NO_POSTS_YET = "reply.summary.no_posts_yet";

    /**
     * Empty result — the scope follows NO sources yet, so nothing can arrive
     * (the fresh-user empty-feed cliff, M1-593). Distinct from
     * {@link #REPLY_SUMMARY_NO_POSTS_YET} (subscribed-but-empty-window): this
     * one names the real cause and steers the user to /follow-all-sources.
     */
    public static final String REPLY_SUMMARY_NO_SUBSCRIPTIONS = "reply.summary.no_subscriptions";

    /** LLM unreachable: reply prefix announcing the degraded-fallback form. */
    public static final String REPLY_SUMMARY_DEGRADED_NOTICE = "reply.summary.degraded_notice";

    /**
     * Window over the summarizer post cap (M1-623): reply prefix
     * announcing the explicit-decision degraded form + the narrow-with--w
     * steer. Two interpolation tokens: {@code {0}} = total eligible posts
     * in the window (before the retrieval cap), {@code {1}} = the
     * summarizer post cap.
     */
    public static final String REPLY_SUMMARY_WINDOW_TOO_LARGE_NOTICE =
            "reply.summary.window_too_large_notice";

    /**
     * Cluster-cap excess prefix. Four interpolation tokens:
     * {@code {0}} = included count (= cap), {@code {1}} = total eligible
     * before cap, {@code {2}} = profile name (e.g. {@code laptop}),
     * {@code {3}} = excluded count.
     */
    public static final String REPLY_SUMMARY_CAP_EXCESS_NOTICE =
            "reply.summary.cap_excess_notice";

    /** {@code >5 followed tags} top-3 restriction prefix. {@code {0}} = N = followed-tag count. */
    public static final String REPLY_SUMMARY_TOP_3_OF_N_PREFIX =
            "reply.summary.top_3_of_n_prefix";

    // ----- /summary + /retry cluster block labels (M1-303) ----------------
    // Per docs/design/05-llm.md §418 ("Cluster headers, classification
    // labels in summaries" — listed as Translated) + decision D43. The
    // shared ClusterBlockRenderer resolves these in the scope's reply
    // language for both /summary (terminal compose) and /retry (anchored
    // replay). The score line carries a MessageFormat {0,choice,...} plural
    // shape so Czech's three-form plural (1 zdroj / 2 zdroje / 5 zdrojů)
    // renders correctly; binary languages collapse it to two arms.

    /** Cluster-block {@code covered by: } label prefix; the comma-joined source list is appended after it. */
    public static final String REPLY_SUMMARY_CLUSTER_COVERED_BY = "reply.summary.cluster.covered_by";

    /** Cluster-block score line. Token {@code {0}} = distinct-source count; a {@code {0,choice,...}} plural shape renders the count-and-noun. */
    public static final String REPLY_SUMMARY_CLUSTER_SCORE = "reply.summary.cluster.score";

    /** Cluster-block {@code summary: } label prefix; the (possibly translated) LLM prose is appended after it. */
    public static final String REPLY_SUMMARY_CLUSTER_SUMMARY_LABEL = "reply.summary.cluster.summary_label";

    /** Cluster-block {@code classification: } label prefix; the comma-joined tag union is appended after it. */
    public static final String REPLY_SUMMARY_CLUSTER_CLASSIFICATION_LABEL = "reply.summary.cluster.classification_label";

    /** Cluster-block {@code tags: } label prefix; the comma-joined tag union is appended after it. */
    public static final String REPLY_SUMMARY_CLUSTER_TAGS_LABEL = "reply.summary.cluster.tags_label";

    // ----- Intake-step splice fixed replies (M1-044b) ---------------------
    // Per docs/spec/security.md §Authorization model + §User ban +
    // §Invite-code registration, and docs/design/03-commands.md §3.11
    // Welcome messages. These keys are looked up by InboundRouter at the
    // step 2 / step 4 branches of the intake splice.

    /**
     * Fixed reply for a DM that fails the step 2 invite-code consume
     * (Rejected or BruteForceThresholdBreached outcome). Per spec
     * §Invite-code registration: "rejected with the same fixed reply
     * as step 2's invalid path."
     */
    public static final String ERROR_INVITE_REQUIRED = "error.invite.required";

    /**
     * Fixed reply for the step 4 ban check. Per spec §User ban: "Banned
     * user receives one fixed reply per inbound message, regardless of
     * input." The exact English literal lives in
     * {@code docs/design/04-security.md} §Banning.
     */
    public static final String ERROR_BAN_FIXED = "error.ban.fixed";

    /**
     * Welcome reply sent after a successful step 2 invite-code consume
     * (DM, fresh user). Text from {@code docs/design/03-commands.md}
     * §3.11 Welcome messages, Mode 1.
     */
    public static final String REPLY_WELCOME_DM_FRESH = "reply.welcome.dm_fresh";

    /**
     * Welcome reply sent after a successful SimpleX bootstrap-admin claim
     * (D50). A claimed contact is created is_admin=true + vouched with no
     * probation, so it gets a distinct admin welcome rather than the shared
     * {@link #REPLY_WELCOME_DM_FRESH} probation welcome, which would misstate
     * a claimed admin's access (M1-624). Carries no {@code {0}} placeholder —
     * an admin has the full command surface, so the router sends it via the
     * raw {@code bundleLoader.get} rather than MessageFormat.
     */
    public static final String REPLY_WELCOME_ADMIN_CLAIM = "reply.welcome.admin_claim";

    // ----- Admin command handler errors + replies (M1-044c) ---------------
    // Per docs/spec/security.md §User ban + §Invite-code registration +
    // §Authorization model, and docs/spec/commands.md §Admin (bot admin).
    // The three handlers (/ban, /unban, /invite create/list/revoke) look
    // these up; every user-visible reply path the spec assigns to those
    // commands is keyed here. Plain text only — single backticks for
    // inline literals, bare URLs, no markdown link syntax.

    /** Command invoked in group scope that is permanently DM-only by design. */
    public static final String ERROR_COMMAND_DM_ONLY = "error.command_dm_only";

    /** Non-admin invoked an admin-tier command. */
    public static final String ERROR_ADMIN_ONLY = "error.admin_only";

    /** Required argument missing from a command invocation. {@code {0}} = the command's usage string (e.g. {@code /ban <contact> [--reason "..."]}). Shared across handlers; the usage string is supplied by the caller. */
    public static final String ERROR_USAGE_MISSING_ARGUMENT = "error.usage.missing_argument";

    /** {@code /unban <contact>} against a contact with no {@code users} row at all (unknown-contact rule per {@code commands.md} §Admin). */
    public static final String ERROR_CONTACT_NOT_REGISTERED = "error.contact_not_registered";

    /** {@code /ban <self>}: actor and target are the same {@code users.id}. */
    public static final String ERROR_BAN_CANNOT_BAN_SELF = "error.ban.cannot_ban_self";

    /** {@code /ban} of the only remaining {@code is_admin=TRUE AND is_banned=FALSE} row — caught from the V5 {@code trg_last_admin_protection_update} trigger. */
    public static final String ERROR_BAN_LAST_ADMIN = "error.ban.last_admin";

    /** {@code /unban} success on the preban-deletion path; value MUST contain the literals {@code pre-ban-only row removed} and {@code fresh invite required} per spec §User ban. */
    public static final String REPLY_UNBAN_PREBAN_DELETED = "reply.unban.preban_deleted";

    /** {@code /unban} success on the non-preban path with {@code is_group_admin=TRUE} rows restored; value MUST contain {@code {0}} for the comma-joined group list and the literal {@code /demote} hint. */
    public static final String REPLY_UNBAN_GROUP_ADMINS_RESTORED = "reply.unban.group_admins_restored";

    /** {@code /unban} success on the non-preban path with zero group-admin rows. */
    public static final String REPLY_UNBAN_PLAIN = "reply.unban.plain";

    /** {@code /ban} success reply. {@code {0}} = the redacted target contact id (per {@code ContactIds.redact}). */
    public static final String REPLY_BAN_SUCCESS = "reply.ban.success";

    /** {@code /invite} with no subcommand or an unrecognized one. */
    public static final String ERROR_INVITE_UNKNOWN_SUBCOMMAND = "error.invite.unknown_subcommand";

    /** {@code /invite create} with both {@code --contact} and {@code --open}. */
    public static final String ERROR_INVITE_MUTUALLY_EXCLUSIVE = "error.invite.mutually_exclusive";

    /**
     * {@code /invite create} whose remainder has an unconsumed token — a
     * typo'd flag, a value-less {@code --contact}, or a stray bare argument.
     * Fails safe with no state change instead of being defaulted into the
     * {@code --open} flow (D60; redteam M1-632 medium finding).
     */
    public static final String ERROR_INVITE_CREATE_MALFORMED = "error.invite.create_malformed";

    /** {@code /invite create --adapter <name>} where {@code <name>} is not a currently-enabled adapter. {@code {0}} = the rejected name. */
    public static final String ERROR_INVITE_UNKNOWN_ADAPTER = "error.invite.unknown_adapter";

    /**
     * {@code /invite create --open} with no {@code --adapter} in a deployment
     * where the target can't be inferred (more than one enabled adapter).
     * Names the requirement and the valid choices, replacing the confusing
     * empty-backtick {@link #ERROR_INVITE_UNKNOWN_ADAPTER} for the omitted-flag
     * {@code --open} case (M1-626). {@code {0}} = the enabled adapter names,
     * comma-separated.
     */
    public static final String ERROR_INVITE_ADAPTER_REQUIRED = "error.invite.adapter_required";

    /** {@code /invite create --contact <id>} against a {@code is_banned=TRUE} row; points the admin at {@code /unban}. */
    public static final String ERROR_INVITE_BANNED_TARGET = "error.invite.banned_target";

    /** {@code /invite create --open} when the per-adapter open cap is met; lists current open codes and a {@code /invite revoke} hint. */
    public static final String ERROR_INVITE_OPEN_CAP_MET = "error.invite.open_cap_met";

    /** {@code /invite create --contact} when the global contact-bound cap is met. */
    public static final String ERROR_INVITE_CONTACT_CAP_MET = "error.invite.contact_cap_met";

    /** {@code /invite revoke <code>} where {@code <code>} is absent, already {@code USED}, or already {@code REVOKED}. */
    public static final String ERROR_INVITE_REVOKE_NOT_PENDING = "error.invite.revoke_not_pending";

    /** {@code /invite create} success — the new code's UUID is interpolated as {@code {0}}. */
    public static final String REPLY_INVITE_CREATED = "reply.invite.created";

    /** {@code /invite list} header line printed before the rows. */
    public static final String REPLY_INVITE_LIST_HEADER = "reply.invite.list_header";

    /**
     * Per-row template for {@code /invite list} on CONTACT_BOUND rows. Tokens:
     * {@code {0}} = code prefix, {@code {1}} = adapter name, {@code {2}} = target
     * contact id, {@code {3}} = ISO expiry timestamp.
     */
    public static final String REPLY_INVITE_LIST_ENTRY = "reply.invite.list_entry";

    /**
     * Per-row template for {@code /invite list} on OPEN_ADAPTER rows; carries the
     * literal {@code OPEN} marker per spec §Invite-code registration ("the list
     * output must visually distinguish --open codes from --contact codes").
     * Tokens: {@code {0}} = code prefix, {@code {1}} = adapter name, {@code {2}}
     * = ISO expiry timestamp.
     */
    public static final String REPLY_INVITE_LIST_ENTRY_OPEN = "reply.invite.list_entry_open";

    /** {@code /invite revoke} success. */
    public static final String REPLY_INVITE_REVOKED = "reply.invite.revoked";

    /**
     * {@code /invite bot-contact} success — the bot's own shareable connect
     * contact, displayed once and never logged (D37, M1-620). Tokens:
     * {@code {0}} = adapter name, {@code {1}} = the contact value (a SimpleX
     * contact URL or a Signal number), rendered as a bare value on its own
     * line per the plain-text output convention.
     */
    public static final String REPLY_INVITE_BOT_CONTACT = "reply.invite.bot_contact";

    /** {@code /invite bot-contact} against an adapter with no shareable contact. {@code {0}} = adapter name. */
    public static final String ERROR_INVITE_BOT_CONTACT_UNSUPPORTED =
            "error.invite.bot_contact_unsupported";

    /** {@code /invite bot-contact} when the live address query fails or times out. {@code {0}} = adapter name. */
    public static final String ERROR_INVITE_BOT_CONTACT_UNAVAILABLE =
            "error.invite.bot_contact_unavailable";

    /**
     * {@code /invite bot-contact --adapter <name>} where {@code <name>} matches no
     * activated adapter. Distinct from {@link #ERROR_INVITE_UNKNOWN_ADAPTER}
     * (whose text belongs to the out-of-scope {@code create} flow) because
     * acceptance requires this reply to NAME the valid choices. Tokens:
     * {@code {0}} = the rejected name, {@code {1}} = the activated adapter names.
     */
    public static final String ERROR_INVITE_BOT_CONTACT_UNKNOWN_ADAPTER =
            "error.invite.bot_contact_unknown_adapter";

    /**
     * {@code /invite pending-contacts} header (M1-633, D60): the roster of
     * connected-but-unregistered contacts on the inbound adapter, sourced
     * from {@code invite_code_attempt}. Token {@code {0}} = adapter name.
     */
    public static final String REPLY_INVITE_PENDING_CONTACTS_HEADER =
            "reply.invite.pending_contacts_header";

    /**
     * Per-row template for {@code /invite pending-contacts}. The contact id
     * is deliberately FULL (not {@code ContactIds.redact}'d) so the admin
     * can paste it into {@code /invite create --contact}; the disclosure
     * and its bounds are recorded in {@code docs/spec/security.md}
     * §Invite-code registration. Tokens: {@code {0}} = full contact id,
     * {@code {1}} = last attempt timestamp (ISO-8601 UTC).
     */
    public static final String REPLY_INVITE_PENDING_CONTACTS_ENTRY =
            "reply.invite.pending_contacts_entry";

    /** {@code /invite pending-contacts} with an empty roster. */
    public static final String REPLY_INVITE_PENDING_CONTACTS_EMPTY =
            "reply.invite.pending_contacts_empty";

    // ----- Pre-dispatch confirm gate (M1-051) -----------------------------
    // Per docs/spec/commands.md §Surface conventions ("Confirmation for
    // destructive commands") + docs/spec/security.md §What's intentionally
    // NOT in v1 ("single-step confirm-within-window is enough for v1").
    // The five keys below back the prompt + cancellation + no-pending-error
    // replies for the three confirmable command surfaces (/ban,
    // /invite create --open, /invite revoke). Each prompt template
    // interpolates the timeout-in-seconds derived from
    // {@code infochat.confirm.timeout} so the user sees the same window
    // the service enforces.

    /** {@code /<cmd> confirm} arrived with no matching pending entry (no prior /<cmd> or window expired). */
    public static final String ERROR_CONFIRM_NO_PENDING = "error.confirm.no_pending";

    /**
     * Cancellation acknowledgement sent by the router's step 4.5 sweep
     * when any non-confirm-shape input arrives after a pending confirm
     * was registered. Token {@code {0}} = the display name of the
     * cancelled command (e.g. {@code "ban"}).
     */
    public static final String REPLY_CONFIRM_CANCELLED = "reply.confirm.cancelled";

    /**
     * First-call prompt template for {@code /ban}. Tokens:
     * {@code {0}} = timeout in whole seconds, {@code {1}} = redacted
     * target contact id (per {@code ContactIds.redact}).
     */
    public static final String REPLY_CONFIRM_PROMPT_BAN = "reply.confirm.prompt.ban";

    /**
     * First-call prompt template for {@code /invite create --open}. Tokens:
     * {@code {0}} = timeout in whole seconds, {@code {1}} = target adapter
     * name (validated against the currently-enabled set upstream).
     */
    public static final String REPLY_CONFIRM_PROMPT_INVITE_CREATE_OPEN =
            "reply.confirm.prompt.invite_create_open";

    /**
     * First-call prompt template for {@code /invite revoke <code>}. Tokens:
     * {@code {0}} = timeout in whole seconds, {@code {1}} = the
     * 8-char code prefix (full UUID is not echoed in case the prompt
     * is read by someone other than the issuing admin).
     */
    public static final String REPLY_CONFIRM_PROMPT_INVITE_REVOKE =
            "reply.confirm.prompt.invite_revoke";

    // ----- Slow-start probation + /vouch (M1-045) -------------------------
    // Per docs/spec/security.md §Slow-start tier ("Blocked operations
    // return a friendly reply stating when full access unlocks") +
    // docs/design/03-commands.md §3.3 (probation-aware reply shape).
    // The probation gate (InboundRouter step 5) emits
    // ERROR_PROBATION_BLOCKED when a probation user invokes a
    // non-allowed command; /vouch emits the success / no-op replies.

    /**
     * Probation gate rejection reply. Token {@code {0}} = the
     * approximate time until full access unlocks (formatted from
     * {@code probation_until - NOW()} by the caller). Per spec
     * §Slow-start tier, the reply never reaches the LLM or any
     * write path.
     */
    public static final String ERROR_PROBATION_BLOCKED = "error.probation.blocked";

    /**
     * Argument-free probation rejection reply for the {@code /grant-admin}
     * and {@code /revoke-admin} in-handler defense-in-depth probation
     * branches. Unlike {@link #ERROR_PROBATION_BLOCKED} — the router's
     * step-5 gate, which fills the {@code {0}} unlock-time and {@code {1}}
     * allowed-list tokens via {@code MessageFormat} — the admin handlers
     * carry no {@code Clock} / {@code CommandPermissions} to fill those
     * tokens, so they emit this placeholder-free reply via a plain
     * {@code bundleLoader.get}. The two-argument key stays for the router
     * (M1-600).
     */
    public static final String ERROR_PROBATION_BLOCKED_GENERIC = "error.probation.blocked.generic";

    /**
     * {@code /vouch} success reply. Sent on the happy path where
     * the handler cleared probation ({@code probation_until = NULL})
     * in one transaction with one VOUCH audit row. Per D47 the
     * command no longer advances {@code registration_state}.
     */
    public static final String REPLY_VOUCH_SUCCESS = "reply.vouch.success";

    /**
     * {@code /vouch} no-op reply. Sent when the target row is
     * already past probation — the UPDATE would change nothing. The
     * handler short-circuits BEFORE running the SQL and writes no
     * audit row, matching the M1-036 / {@code /unban} pattern for
     * in-effect no-ops.
     */
    public static final String REPLY_VOUCH_NOOP = "reply.vouch.noop";

    /**
     * {@code /vouch} banned-target reply (M1-045 redteam-fix). Sent
     * when the target row has {@code is_banned = true}: an admin
     * cannot vouch a banned user past probation when intake step 4
     * still blocks them. The handler short-circuits BEFORE opening
     * the transaction so no audit row and no UPDATE land — the
     * banned row's {@code registration_state} and
     * {@code probation_until} columns are preserved verbatim, which
     * is the state {@code /unban} restores into.
     */
    public static final String ERROR_VOUCH_BANNED_TARGET = "error.vouch.banned_target";

    // ----- /grant-admin + /revoke-admin (M1-046) --------------------------
    // Per docs/spec/security.md §Authorization model
    // (last-admin protection + per-adapter scope) +
    // docs/spec/commands.md §Admin (bot admin). The two handlers mutate
    // users.is_admin and are inbound-adapter-scoped; the V5
    // trg_last_admin_protection_update trigger is the load-bearing
    // last-line defense the revoke handler catches via SQLException
    // substring match on `last_admin_protection`.

    /** {@code /grant-admin <contact>} against a {@code is_banned=TRUE} row — granting admin to a banned user is incoherent. */
    public static final String ERROR_GRANT_ADMIN_BANNED_TARGET = "error.grant_admin.banned_target";

    /** {@code /grant-admin <contact>} against a row that is already {@code is_admin=TRUE} (no-op friendly reply). */
    public static final String ERROR_GRANT_ADMIN_ALREADY_ADMIN = "error.grant_admin.already_admin";

    /** {@code /grant-admin} success reply. {@code {0}} = the redacted target contact id (per {@code ContactIds.redact}). */
    public static final String REPLY_GRANT_ADMIN_SUCCESS = "reply.grant_admin.success";

    /** {@code /revoke-admin <self>}: actor and target are the same {@code users.id}. First-line UX guard; V5 trigger is the last-line defense. */
    public static final String ERROR_REVOKE_ADMIN_CANNOT_REVOKE_SELF = "error.revoke_admin.cannot_revoke_self";

    /** {@code /revoke-admin <contact>} against a row that is already {@code is_admin=FALSE} (no-op friendly reply). */
    public static final String ERROR_REVOKE_ADMIN_NOT_ADMIN = "error.revoke_admin.not_admin";

    /** {@code /revoke-admin} of the only remaining {@code is_admin=TRUE AND is_banned=FALSE} row — caught from the V5 {@code trg_last_admin_protection_update} trigger. Mentions the global last-admin invariant + a hint that another admin must be granted first. */
    public static final String ERROR_REVOKE_ADMIN_LAST_ADMIN = "error.revoke_admin.last_admin";

    /** {@code /revoke-admin} success reply. {@code {0}} = the redacted target contact id (per {@code ContactIds.redact}). */
    public static final String REPLY_REVOKE_ADMIN_SUCCESS = "reply.revoke_admin.success";

    /** Shared group-scope reject for /grant-admin and /revoke-admin: the ScopeRef.Group SPI does not carry the actor's contact id in v1, so the handler cannot verify bot-admin tier. T2-F lands the SPI widening. */
    public static final String ERROR_GROUP_ADMIN_NOT_IN_V1 = "error.group_admin_not_in_v1";

    // ----- /save + /saved + /unsave (M1-052) ------------------------------
    // Per docs/spec/commands.md §Content (/save, /saved, /unsave) +
    // docs/spec/schema.md §Per-user state + §Invariants (1 carve-out, 6
    // carve-out) + docs/design/02-schema.md §2.6.1 + docs/design/03-commands.md
    // §/save /§/saved /§/unsave. Saved-post library is per-user-globally
    // (decision D13); the /saved reply header MUST disclose this.
    //
    // The three _GROUP_NOT_IN_V1 keys cover the v1 group-scope short-
    // circuit: ScopeRef.Group carries adapterGroupId only (no actor
    // contact id), so the CommandHandler SPI cannot resolve the inbound
    // sender's identity in group scope (mirrors the AddSourceCommandHandler
    // / GrantAdminCommandHandler / RevokeAdminCommandHandler convention).
    // Each command gets its own key (rather than one shared key) so T2-F
    // can independently translate them when the SPI widens.

    /** {@code /save <uid>}: the post is missing, QUARANTINED, or NEEDS_REVIEW (visibility-of-target rule — non-READY posts are indistinguishable from a missing UID at the user surface). */
    public static final String ERROR_SAVE_UNKNOWN_UID = "error.save.unknown_uid";

    /** {@code /save <uid>} when the actor's {@code users.save_count} is already at the per-user cap; points the user at /unsave. */
    public static final String ERROR_SAVE_CAP_MET = "error.save.cap_met";

    /** {@code /save <uid>} on a post already in the actor's library — PK collision on (user_id, post_uid). */
    public static final String ERROR_SAVE_ALREADY_SAVED = "error.save.already_saved";

    /** {@code /save -t}: a personal tag exceeds the profile-driven per-tag length cap; rejected at the parser boundary before any DB work. {@code {0}} = the configured max length. */
    public static final String ERROR_SAVE_TAG_TOO_LONG = "error.save.tag_too_long";

    /** {@code /save -t}: the personal-tag list exceeds the profile-driven per-call count cap; rejected at the parser boundary before any DB work. {@code {0}} = the configured max count. */
    public static final String ERROR_SAVE_TOO_MANY_TAGS = "error.save.too_many_tags";

    /** {@code /save} invoked from group scope — v1 short-circuit; T2-F lands the group-actor seam. */
    public static final String ERROR_SAVE_GROUP_NOT_IN_V1 = "error.save.group_not_in_v1";

    /** {@code /save} success reply. {@code {0}} = the post UID the caller saved. */
    public static final String REPLY_SAVE_SUCCESS = "reply.save.success";

    /** {@code /saved} reply header — MUST disclose per-user-global semantics. {@code {0}} = displayed-count, {@code {1}} = total-count, {@code {2}} = current page, {@code {3}} = total pages, {@code {4}} = optional filter clause. */
    public static final String REPLY_SAVED_HEADER_GLOBAL = "reply.saved.header.global";

    /** {@code /saved} per-row template. {@code {0}} = post UID, {@code {1}} = title, {@code {2}} = saved_at relative, {@code {3}} = tag list (comma-joined). */
    public static final String REPLY_SAVED_LINE = "reply.saved.line";

    /** {@code /saved} empty-library reply. */
    public static final String REPLY_SAVED_EMPTY = "reply.saved.empty";

    /** {@code /saved} invoked from group scope — v1 short-circuit; T2-F lands the group-actor seam. */
    public static final String ERROR_SAVED_GROUP_NOT_IN_V1 = "error.saved.group_not_in_v1";

    /** {@code /unsave <uid>}: the actor has no saved_post row for that UID. */
    public static final String ERROR_UNSAVE_UNKNOWN_UID = "error.unsave.unknown_uid";

    /** {@code /unsave} success reply. {@code {0}} = the post UID the caller removed. */
    public static final String REPLY_UNSAVE_SUCCESS = "reply.unsave.success";

    /** {@code /unsave} invoked from group scope — v1 short-circuit; T2-F lands the group-actor seam. */
    public static final String ERROR_UNSAVE_GROUP_NOT_IN_V1 = "error.unsave.group_not_in_v1";

    // ----- Source-management admin commands (M1-053) ----------------------
    // /list-sources, /remove-source, /source-enable, /source-disable per
    // docs/spec/commands.md §Source management + §Permission model +
    // docs/spec/security.md §Source URL visibility + §Authorization model.
    // The `--all` and `--include-deleted` flags on /list-sources are
    // bot-admin-only flag-as-identity (a non-admin caller never sees the
    // flag silently stripped). /remove-source and /source-enable
    // (soft-deleted path only) are confirm-gated via M1-051
    // ConfirmStateService; the two new PendingConfirm record types
    // (RemoveSourceConfirm, SourceEnableConfirm) live as top-level files
    // alongside this constant catalogue.

    /** {@code /list-sources --all} or {@code --include-deleted} from a non-admin caller — flag-as-identity rejection. */
    public static final String ERROR_LIST_SOURCES_ADMIN_ONLY_FLAG = "error.list_sources.admin_only_flag";

    /** {@code /list-sources --include-deleted} without {@code --all} — even an admin must pair the two. */
    public static final String ERROR_LIST_SOURCES_INCLUDE_DELETED_REQUIRES_ALL =
            "error.list_sources.include_deleted_requires_all";

    /**
     * URL-visibility caveat appended to the header on the {@code --all}
     * paths per {@code docs/spec/security.md} §Source URL visibility.
     * The bundle text MUST contain the literal {@code visible to bot
     * admins} (asserted by {@code ListSourcesCommandHandlerTest}).
     */
    public static final String REPLY_LIST_SOURCES_URL_VISIBILITY_CAVEAT =
            "reply.list_sources.url_visibility_caveat";

    /** Header line printed before the per-source rows. */
    public static final String REPLY_LIST_SOURCES_HEADER = "reply.list_sources.header";

    /**
     * Per-row template. Tokens: {@code {0}} = display name, {@code {1}} = identifier (URL),
     * {@code {2}} = kind ({@code rss|bluesky|...}), {@code {3}} = status flag
     * ({@code active|failed|disabled|deleted}).
     */
    public static final String REPLY_LIST_SOURCES_LINE = "reply.list_sources.line";

    /** Reply when zero rows would be returned for the requested view. */
    public static final String REPLY_LIST_SOURCES_EMPTY = "reply.list_sources.empty";

    /**
     * Next-page hint footer (M1-630), appended on every page EXCEPT the last.
     * Tokens: {@code {0}} = the command name the caller invoked
     * ({@code /list-sources} or its {@code /get-sources} alias, echoed so the
     * hint points back at what the user typed), {@code {1}} = next page number.
     */
    public static final String REPLY_LIST_SOURCES_NEXT_PAGE_HINT =
            "reply.list_sources.next_page_hint";

    /** {@code /get-tags} header line printed before the per-tag rows (explains the {@code *} followed-marker). */
    public static final String REPLY_GET_TAGS_HEADER = "reply.get_tags.header";

    /** {@code /get-tags} reply when the controlled vocabulary (the {@code tag} table) is empty. */
    public static final String REPLY_GET_TAGS_EMPTY = "reply.get_tags.empty";

    /** {@code /remove-source <id>}: parse failure on the positional {@code <id>} (not a UUID literal). */
    public static final String ERROR_REMOVE_SOURCE_UNKNOWN_ID = "error.remove_source.unknown_id";

    /** {@code /remove-source <id>}: target row is already soft-deleted ({@code deleted_at IS NOT NULL}). */
    public static final String ERROR_REMOVE_SOURCE_ALREADY_DELETED = "error.remove_source.already_deleted";

    /**
     * First-call prompt template for {@code /remove-source}. Tokens:
     * {@code {0}} = source display name, {@code {1}} = affected-subscriber count
     * (rows in {@code source_subscription} that will be cascade-deleted),
     * {@code {2}} = timeout in whole seconds.
     */
    public static final String REPLY_CONFIRM_PROMPT_REMOVE_SOURCE = "reply.confirm.prompt.remove_source";

    /**
     * {@code /remove-source confirm} success reply. Tokens: {@code {0}} =
     * source display name, {@code {1}} = cascade-deleted subscription count.
     */
    public static final String REPLY_REMOVE_SOURCE_SUCCESS = "reply.remove_source.success";

    /** {@code /unfollow-source <id>}: parse failure on the positional {@code <id>}, or no source row with that id (mirrors {@code /remove-source}'s unknown-id case). */
    public static final String ERROR_UNFOLLOW_SOURCE_UNKNOWN_ID = "error.unfollow_source.unknown_id";

    /** {@code /unfollow-source <id>} in a group: caller is neither group admin nor bot admin (v1 has no per-contributor unfollow). */
    public static final String ERROR_UNFOLLOW_SOURCE_GROUP_ADMIN_ONLY = "error.unfollow_source.group_admin_only";

    /** {@code /unfollow-source <id>}: caller scope holds no subscription to that source — friendly no-op, no audit row written. */
    public static final String REPLY_UNFOLLOW_SOURCE_NOT_SUBSCRIBED = "reply.unfollow_source.not_subscribed";

    /** {@code /unfollow-source <id>} success reply. Token: {@code {0}} = source display name. */
    public static final String REPLY_UNFOLLOW_SOURCE_SUCCESS = "reply.unfollow_source.success";

    // ----- /follow-all-sources (M1-576) -----------------------------------

    /** {@code /follow-all-sources} in a group: caller is neither group admin nor bot admin (mirrors {@code /add-source}'s group gate). */
    public static final String ERROR_FOLLOW_ALL_SOURCES_GROUP_ADMIN_ONLY = "error.follow_all_sources.group_admin_only";

    /** {@code /follow-all-sources} success reply. Tokens: {@code {0}} = newly-subscribed count, {@code {1}} = total sources now followed in this scope. */
    public static final String REPLY_FOLLOW_ALL_SOURCES_DONE = "reply.follow_all_sources.done";

    /** {@code /source-enable <id>}: parse failure on the positional {@code <id>} (not a UUID literal). */
    public static final String ERROR_SOURCE_ENABLE_UNKNOWN_ID = "error.source_enable.unknown_id";

    /**
     * {@code /source-enable <id>}: probe failed (HTTP 4xx/5xx, SSRF block,
     * timeout, or unreachable). Single key collapses all failure shapes —
     * the source remains in its prior state regardless.
     */
    public static final String ERROR_SOURCE_ENABLE_PROBE_FAILED = "error.source_enable.probe_failed";

    /** {@code /source-enable <id>}: target row is already {@code status='active'} and not soft-deleted. */
    public static final String ERROR_SOURCE_ENABLE_ALREADY_ACTIVE = "error.source_enable.already_active";

    /**
     * {@code /source-enable <id>}: target row's {@code kind} is not in
     * the v1 HTTP-shaped probe-supported set (only {@code rss} qualifies
     * today; {@code nostr}/{@code bluesky}/etc. await {@code
     * StreamSourceSupervisor}).
     */
    public static final String ERROR_SOURCE_ENABLE_KIND_NOT_SUPPORTED_IN_V1 =
            "error.source_enable.kind_not_supported_in_v1";

    /**
     * First-call prompt template for {@code /source-enable} against a
     * soft-deleted row. Tokens: {@code {0}} = source display name,
     * {@code {1}} = timeout in whole seconds. The bundle text MUST
     * include the literal {@code No subscriptions will be restored}
     * (asserted by {@code SourceEnableCommandHandlerTest}).
     */
    public static final String REPLY_CONFIRM_PROMPT_SOURCE_ENABLE_SOFT_DELETED =
            "reply.confirm.prompt.source_enable_soft_deleted";

    /**
     * {@code /source-enable} success reply on the {@code failed}/{@code disabled}
     * path (no soft-delete revival). Token {@code {0}} = source display name.
     */
    public static final String REPLY_SOURCE_ENABLE_SUCCESS = "reply.source_enable.success";

    /**
     * {@code /source-enable confirm} success reply on the soft-deleted
     * revival path. Token {@code {0}} = source display name. The bundle
     * text MUST include the {@link #REPLY_SOURCE_ENABLE_NO_SUBSCRIPTIONS_RESTORED}
     * literal — the handler concatenates the two keys for one outbound.
     */
    public static final String REPLY_SOURCE_ENABLE_SUCCESS_FROM_SOFT_DELETED =
            "reply.source_enable.success.from_soft_deleted";

    /**
     * The required spec disclosure literal appended to the soft-deleted
     * revival reply. Per {@code docs/spec/commands.md} §Source management:
     * "No subscriptions were restored — affected scopes must /add-source
     * again to re-subscribe." Asserted as a literal substring.
     */
    public static final String REPLY_SOURCE_ENABLE_NO_SUBSCRIPTIONS_RESTORED =
            "reply.source_enable.no_subscriptions_restored";

    /** {@code /source-disable <id>}: parse failure on the positional {@code <id>} (not a UUID literal). */
    public static final String ERROR_SOURCE_DISABLE_UNKNOWN_ID = "error.source_disable.unknown_id";

    /** {@code /source-disable <id>}: target row is not currently {@code status='active' AND deleted_at IS NULL}. */
    public static final String ERROR_SOURCE_DISABLE_NOT_ACTIVE = "error.source_disable.not_active";

    /** {@code /source-disable} success reply. Token {@code {0}} = source display name. */
    public static final String REPLY_SOURCE_DISABLE_SUCCESS = "reply.source_disable.success";

    // ----- /follow-tag + /unfollow-tag (M1-054) ---------------------------
    // Per docs/spec/commands.md §Per-scope tag preferences +
    // docs/spec/schema.md §Sources and tags. The two handlers mutate
    // scope_tag + scope_preferences.tag_mode in one transaction; v1
    // group scope short-circuits to *_GROUP_ADMIN_ONLY because the
    // frozen CommandHandler SPI does not carry the inbound caller's
    // contact id in group scope (the same AddSourceCommandHandler /
    // GrantAdminCommandHandler reason — T2-F lands the actor seam).

    /** {@code /follow-tag <t>} where {@code <t>} is not in the controlled vocabulary; bundle template surfaces a fuzzy-suggestion footer. */
    public static final String ERROR_FOLLOW_TAG_UNKNOWN_TAG = "error.follow_tag.unknown_tag";

    /** {@code /follow-tag} from a non-admin in group scope (in v1, ALL group-scope callers — the actor-seam landing is T2-F). */
    public static final String ERROR_FOLLOW_TAG_GROUP_ADMIN_ONLY = "error.follow_tag.group_admin_only";

    /** {@code /follow-tag <t>} in {@code tag_mode='ALL'}: flipped to EXPLICIT, seeded a single tag. Token {@code {0}} = followed tag. */
    public static final String REPLY_FOLLOW_TAG_SUCCESS_FROM_ALL = "reply.follow_tag.success_from_all";

    /** {@code /follow-tag <t>} in {@code tag_mode='EXPLICIT'}: idempotent add in place. Token {@code {0}} = followed tag. */
    public static final String REPLY_FOLLOW_TAG_SUCCESS_IN_PLACE = "reply.follow_tag.success_in_place";

    /** {@code /unfollow-tag <t>} where {@code <t>} is not in the controlled vocabulary; bundle template surfaces a fuzzy-suggestion footer. */
    public static final String ERROR_UNFOLLOW_TAG_UNKNOWN_TAG = "error.unfollow_tag.unknown_tag";

    /** {@code /unfollow-tag} from a non-admin in group scope (in v1, ALL group-scope callers). */
    public static final String ERROR_UNFOLLOW_TAG_GROUP_ADMIN_ONLY = "error.unfollow_tag.group_admin_only";

    /** {@code /unfollow-tag <t> --all} — positional tag and {@code --all} flag are mutually exclusive. */
    public static final String ERROR_UNFOLLOW_TAG_MUTUALLY_EXCLUSIVE = "error.unfollow_tag.mutually_exclusive";

    /** {@code /unfollow-tag <t>} in {@code tag_mode='ALL'}: flipped to EXPLICIT, seeded the all-minus-one set. Token {@code {0}} = unfollowed tag. */
    public static final String REPLY_UNFOLLOW_TAG_SUCCESS_FROM_ALL = "reply.unfollow_tag.success_from_all";

    /** {@code /unfollow-tag <t>} in {@code tag_mode='EXPLICIT'}: row deleted in place (followed set still non-empty). Token {@code {0}} = unfollowed tag. */
    public static final String REPLY_UNFOLLOW_TAG_SUCCESS_IN_PLACE = "reply.unfollow_tag.success_in_place";

    /** {@code /unfollow-tag <t>} in {@code tag_mode='EXPLICIT'} that empties the set: post-delete count reached zero, mode flipped back to ALL. Token {@code {0}} = unfollowed tag. */
    public static final String REPLY_UNFOLLOW_TAG_FLIPS_BACK_TO_ALL = "reply.unfollow_tag.flips_back_to_all";

    /**
     * First-call prompt template for {@code /unfollow-tag --all}. Tokens:
     * {@code {0}} = timeout in whole seconds, {@code {1}} = current
     * scope_tag row count (so the user sees the size of the wipe).
     */
    public static final String REPLY_CONFIRM_PROMPT_UNFOLLOW_TAG_ALL =
            "reply.confirm.prompt.unfollow_tag_all";

    /** {@code /unfollow-tag --all confirm} success — bulk reset committed. Token {@code {0}} = count of deleted rows. */
    public static final String REPLY_UNFOLLOW_TAG_ALL_SUCCESS = "reply.unfollow_tag_all.success";

    // ----- /lang <code> (M1-060) ------------------------------------------
    // Per docs/spec/commands.md §Conversation control + §Permission model +
    // docs/spec/llm.md §Translation flow + docs/spec/schema.md §Per-scope
    // state. The handler is the user-facing mutator that lets
    // scope_preferences.language move off the V7 default 'en' to one of
    // the loaded-bundle codes. Group scope short-circuits to
    // _GROUP_ADMIN_NOT_IN_V1 per the M1-054 FollowTagCommandHandler /
    // UnfollowTagCommandHandler SPI-freeze precedent (the frozen
    // CommandHandler.handle(ScopeRef, String) SPI carries no inbound
    // caller's contact id in group scope; T2-F lands the actor seam).

    /**
     * {@code /lang <code>} success reply, resolved via the NEW 2-arg
     * {@code bundleLoader.get(key, langCode)} accessor with
     * {@code langCode} = the newly-written code, so the confirmation
     * reply itself lands in the just-set language. Token {@code {0}} =
     * the written language code (e.g. {@code cs}).
     */
    public static final String REPLY_LANG_SUCCESS = "reply.lang.success";

    /**
     * {@code /lang <code>} unsupported-code reply per spec §Conversation
     * control: "An unsupported code produces a friendly error that lists
     * the supported codes — never a silent no-op and never a fall-through
     * to the default." Token {@code {0}} = the comma-separated list of
     * supported codes derived from {@code bundleLoader.supportedLanguages()}.
     */
    public static final String ERROR_LANG_UNSUPPORTED_CODE = "error.lang.unsupported_code";

    /**
     * {@code /lang} in group scope: short-circuit per the M1-054
     * SPI-freeze precedent (group-actor identity is not carried by the
     * frozen CommandHandler SPI; T2-F lands the seam). Distinct from
     * {@link #ERROR_GROUP_ADMIN_NOT_IN_V1} (the shared M1-046 admin-tier
     * key) so T2-F can independently translate {@code /lang}'s group
     * reply when the SPI widens.
     */
    public static final String ERROR_LANG_GROUP_ADMIN_NOT_IN_V1 = "error.lang.group_admin_not_in_v1";

    // ----- Asset command reply layout (M1-055c) ----------------------------
    // Per docs/spec/commands.md §Asset commands + docs/design/10-asset-commands.md
    // §10.5 Reply layout. Plain text only — bare URLs per D30, no markdown
    // link syntax, no supportsCodeFormatting/supportsMarkdownLinks branch.
    // The renderer silently omits absent snapshot fields (never invents zeros).

    /** Header line: {@code <DisplayName> (<source>)} plus optional {@code  ⚠ stale} marker. */
    public static final String REPLY_ASSET_HEADER = "reply.asset.header";

    /** Price line: quote-currency price. Token {@code {0}} = formatted price. */
    public static final String REPLY_ASSET_PRICE_LINE = "reply.asset.price_line";

    /** 1h delta line (coingecko only). Token {@code {0}} = signed percentage. */
    public static final String REPLY_ASSET_DELTA_1H = "reply.asset.delta_1h";

    /** 24h delta line with spread (coingecko). Tokens: {@code {0}} = signed pct, {@code {1}} = high, {@code {2}} = low. */
    public static final String REPLY_ASSET_DELTA_24H = "reply.asset.delta_24h";

    /** 24h spread line (exchange sub-verbs where no delta is available). Tokens: {@code {0}} = high, {@code {1}} = low. */
    public static final String REPLY_ASSET_SPREAD = "reply.asset.spread";

    /** Capture timestamp + cache age line. Tokens: {@code {0}} = UTC time, {@code {1}} = cache age in seconds. */
    public static final String REPLY_ASSET_CAPTURE_LINE = "reply.asset.capture_line";

    /** Stale-data warning marker appended to the header when {@code now - captured_at > 2 * refresh_interval}. */
    public static final String REPLY_ASSET_STALE_MARKER = "reply.asset.stale_marker";

    /**
     * Attribution {@code source:} label preceding the bare source URL (M1-303).
     * The bundle holds only the translatable word; the renderer owns the line
     * indent and the separator space, so the value carries no fragile leading
     * or trailing whitespace.
     */
    public static final String REPLY_ASSET_SOURCE_LABEL = "reply.asset.source_label";

    // ----- Asset command friendly errors (M1-055c) ---------------------------
    // Per docs/spec/commands.md §Asset commands + docs/design/10-asset-commands.md
    // §10.8 Friendly errors. Mirrors the tag-argument error shape per
    // docs/spec/commands.md §Friendly errors.

    /** Bare {@code /<asset>} when no {@code is_default = true} row exists. */
    public static final String ERROR_ASSET_NOT_CONFIGURED = "error.asset.not_configured";

    /** Bare {@code /<asset>} when the default-flagged row has {@code enabled = false}. Token {@code {0}} = comma-joined enabled sub-verbs. */
    public static final String ERROR_ASSET_DEFAULT_DISABLED = "error.asset.default_disabled";

    /** Unknown sub-verb with fuzzy suggestion. Tokens: {@code {0}} = supplied, {@code {1}} = best match, {@code {2}} = asset name, {@code {3}} = comma-joined available. */
    public static final String ERROR_ASSET_UNKNOWN_SUB_VERB = "error.asset.unknown_sub_verb";

    /** Sub-verb exists globally but not enabled for this asset. Tokens: {@code {0}} = sub-verb, {@code {1}} = asset name, {@code {2}} = comma-joined available. */
    public static final String ERROR_ASSET_SUB_VERB_NOT_ENABLED = "error.asset.sub_verb_not_enabled";

    /** Unsupported {@code --vs} currency with fuzzy suggestion. Tokens: {@code {0}} = supplied, {@code {1}} = best match, {@code {2}} = asset name, {@code {3}} = comma-joined available. */
    public static final String ERROR_ASSET_UNSUPPORTED_QUOTE_CURRENCY = "error.asset.unsupported_quote_currency";

    /** No snapshot row at all for the requested {@code (asset, sub-verb, vs)} triple. */
    public static final String ERROR_ASSET_NO_DATA = "error.asset.no_data";

    // ----- /forget (M1-066) --------------------------------------------------
    // Per docs/spec/commands.md §Conversation control — /forget.

    /** First-call prompt template. Token {@code {0}} = timeout in whole seconds. */
    public static final String REPLY_CONFIRM_PROMPT_FORGET = "reply.confirm.prompt.forget";

    /** Confirmed purge, no remaining scopes — bare confirmation. */
    public static final String REPLY_FORGET_CLEARED = "reply.forget.cleared";

    /** Confirmed purge, N remaining scopes. Token {@code {0}} = remaining scope count. */
    public static final String REPLY_FORGET_CLEARED_WITH_REMAINING = "reply.forget.cleared_with_remaining";

    /** Idempotent no-op — nothing to purge. */
    public static final String REPLY_FORGET_NOOP = "reply.forget.noop";

    // ----- Chat-mode errors (M1-063) ----------------------------------------
    // Per docs/spec/commands.md §Chat mode + docs/spec/security.md §Failure
    // handling — Chat-mode replies. All three are emitted by the chat-mode
    // dispatch path (InboundRouter + ChatAgent); never by command handlers.

    /** Chat agent's LLM is unreachable (connection failure, timeout, provider error). Per spec §Failure handling: "friendly error from the bundle." */
    public static final String ERROR_CHAT_UNAVAILABLE = "error.chat.unavailable";

    /** Second chat-mode message while one is still in-flight. Per spec §One in-flight interruptible request per (user, scope). */
    public static final String ERROR_CHAT_IN_FLIGHT = "error.chat.in_flight";

    /** Non-slash message body exceeds the profile-driven chat-mode body cap (context_window / 8 chars). */
    public static final String ERROR_CHAT_BODY_TOO_LARGE = "error.chat.body_too_large";

    /** Slash-command body exceeds the profile-driven command body cap (infochat.command.body-cap). */
    public static final String ERROR_COMMAND_BODY_TOO_LARGE = "error.command.body_too_large";

    /** Per-user LLM-triggering rate cap exceeded (infochat.chat.llm-rate-cap-per-minute). */
    public static final String ERROR_CHAT_LLM_RATE_CAP = "error.chat.llm_rate_cap";

    /** Per-user cross-scope concurrent-request cap exceeded (infochat.chat.dispatch.per-user-cap, M1-636). */
    public static final String ERROR_CHAT_PER_USER_CAP = "error.chat.per_user_cap";

    /** Chat LLM emitted the D21 structured refusal marker; this deterministic notice replaces it — the marker is protocol surface and is never delivered (security.md §Prompt-injection defenses). */
    public static final String ERROR_CHAT_REFUSED = "error.chat.refused";

    // ----- Chat retrieval provenance (M1-617) --------------------------------
    // Per docs/spec/commands.md §Chat mode + D58: every successful chat
    // reply carries a deterministic, bundle-localized signal saying whether
    // it was grounded in feed posts or answered from general knowledge.
    // Composed by ChatAgent AFTER sanitize + translate and appended by the
    // router — deterministic bot prose takes the bundle path, never the
    // translator (D43 two-path rule).

    /** Grounded reply: {0} = count of distinct feed posts consulted this turn. Count-only — feed-derived text (uids/titles) is never interpolated into a deterministic surface (the D31 class). */
    public static final String CHAT_PROVENANCE_GROUNDED = "reply.chat.provenance.grounded";

    /** No feed post informed the reply. Also covers the breaker-open pre-fetch skip (M1-606), so the wording claims non-grounding only, never "searched and found nothing". */
    public static final String CHAT_PROVENANCE_GENERAL_KNOWLEDGE = "reply.chat.provenance.general_knowledge";

    // ----- Progress notifier stage strings (M1-212) --------------------------
    // Per docs/spec/messaging.md §Progress notifications + decision D43.
    // One key per ProgressStage value; the ProgressNotifier renders each
    // stage by enum from the deterministic bundle (StageProgressNotifier),
    // never interpolating user-authored text (security requirement). Both
    // locales gain all seven via the reflective BundleLoaderTest guard.

    /** Stage string for {@code ProgressStage.STARTED} — placeholder body. */
    public static final String PROGRESS_STARTED = "progress.started";

    /** Stage string for {@code ProgressStage.RETRIEVING}. */
    public static final String PROGRESS_RETRIEVING = "progress.retrieving";

    /** Stage string for {@code ProgressStage.GENERATING}. */
    public static final String PROGRESS_GENERATING = "progress.generating";

    /** Stage string for {@code ProgressStage.TRANSLATING}. */
    public static final String PROGRESS_TRANSLATING = "progress.translating";

    /** Stage string for {@code ProgressStage.FINALIZING}. */
    public static final String PROGRESS_FINALIZING = "progress.finalizing";

    /** Stage string for {@code ProgressStage.COMPLETED} (terminal success label). */
    public static final String PROGRESS_COMPLETED = "progress.completed";

    /** Terminal-failure string rendered by {@code ProgressNotifier.fail} for {@code ProgressStage.FAILED}. */
    public static final String PROGRESS_FAILED = "progress.failed";

    /**
     * Terminal "stopped" string rendered when /stop cancels an in-flight
     * request (decision D35 stopped state) — distinct from {@link #PROGRESS_FAILED}
     * so a user-initiated cancellation never renders the generic failure
     * reply, which D31/D35 forbid. Not a {@code ProgressStage} value: it is
     * a terminal finalize text, not a progress stage.
     */
    public static final String PROGRESS_STOPPED = "progress.stopped";

    // ----- /clear + /compress + auto-compress (M1-064) -----------------------
    // Per docs/spec/commands.md §Conversation control and
    // docs/design/03-commands.md §3.9 Conversation control.

    /**
     * First-call prompt template for {@code /clear}. Tokens:
     * {@code {0}} = timeout in whole seconds.
     */
    public static final String REPLY_CONFIRM_PROMPT_CLEAR = "reply.confirm.prompt.clear";

    /** {@code /clear confirm} success reply — context window wiped, chat_memory preserved (D25). */
    public static final String REPLY_CLEAR_SUCCESS = "reply.clear.success";

    /** {@code /clear confirm} on a (user, scope) with no active chat_session — idempotent no-op. */
    public static final String REPLY_CLEAR_NOOP = "reply.clear.noop";

    /** {@code /compress} success reply. Token {@code {0}} = number of messages compressed. */
    public static final String REPLY_COMPRESS_SUCCESS = "reply.compress.success";

    /** {@code /compress} no-op — no messages in the session to compress. */
    public static final String REPLY_COMPRESS_NOOP = "reply.compress.noop";

    /**
     * Shared failure reply for both manual {@code /compress} and auto-compress.
     * Per spec §Failure handling — Compression failure: session held at ceiling,
     * friendly error surfaces on the current or next chat-mode message.
     */
    public static final String ERROR_COMPRESS_FAILED = "error.compress.failed";

    /** One-line system message sent on successful auto-compress (D43 bundle string). */
    public static final String REPLY_AUTO_COMPRESS_NOTICE = "reply.auto_compress.notice";

    // ----- /stop (M1-065) -----------------------------------------------------
    // Per docs/spec/commands.md §Conversation control — /stop.

    /** Cancelled an in-flight interruptible request. */
    public static final String REPLY_STOP_CANCELLED = "reply.stop.cancelled";

    /** Cancelled a pending destructive-command confirmation. Token {@code {0}} = command name. */
    public static final String REPLY_STOP_CONFIRM_CANCELLED = "reply.stop.confirm_cancelled";

    /** Idempotent no-op — nothing in flight and no pending confirmation. */
    public static final String REPLY_STOP_NOOP = "reply.stop.noop";

    /** Both an in-flight request and a pending confirmation were cancelled. Token {@code {0}} = command name. */
    public static final String REPLY_STOP_BOTH_CANCELLED = "reply.stop.both_cancelled";

    // ----- /retry (M1-065) ----------------------------------------------------
    // Per docs/spec/commands.md §Conversation control — /retry.

    /** Retry cap exhausted. Token {@code {0}} = cap value. */
    public static final String ERROR_RETRY_CAP_EXHAUSTED = "error.retry.cap_exhausted";

    /** No eligible anchor (never ran /summary, or anchor was cleared). */
    public static final String ERROR_RETRY_NO_ANCHOR = "error.retry.no_anchor";

    /** The caller's previous request is still in flight — distinct from the no-anchor case. */
    public static final String ERROR_RETRY_IN_FLIGHT = "error.retry.in_flight";

    /** All frozen UIDs are no longer READY — nothing to retry. */
    public static final String ERROR_RETRY_NO_ELIGIBLE_POSTS = "error.retry.no_eligible_posts";

    /** Status-drift notice prepended to retry output. Token {@code {0}} = excluded count, {@code {1}} = original count. */
    public static final String REPLY_RETRY_STATUS_DRIFT_NOTICE = "reply.retry.status_drift_notice";

    // ----- /retry --digest (M1-080c) -----------------------------------------
    // Per docs/spec/commands.md §Periodic group digests + §Conversation control.
    // The --digest flag routes to DigestRetryService; the personal /retry
    // path (M1-065) is untouched.

    /** {@code /retry --digest} invoked from DM scope — digest retry is group-only. */
    public static final String ERROR_RETRY_DIGEST_GROUP_ONLY = "error.retry.digest_group_only";

    /** {@code /retry --digest} invoked by a non-admin caller. */
    public static final String ERROR_RETRY_DIGEST_GROUP_ADMIN_REQUIRED = "error.retry.digest_group_admin_required";

    /** {@code /retry --digest} when another retry is already in flight for this group. */
    public static final String ERROR_RETRY_DIGEST_ALREADY_IN_PROGRESS = "error.retry.digest_already_in_progress";

    /** {@code /retry --digest} when no prior digest exists for this group. */
    public static final String ERROR_RETRY_DIGEST_NO_PRIOR = "error.retry.digest_no_prior";

    /** {@code /retry --digest} rate-limited — too soon after the last retry for this group. */
    public static final String ERROR_RETRY_DIGEST_RATE_LIMITED = "error.retry.digest_rate_limited";

    /** {@code /retry --digest} success — digest re-generation initiated. */
    public static final String REPLY_RETRY_DIGEST_SUCCESS = "reply.retry.digest_success";

    // ----- /promote + /demote + /group-timezone (M1-079c) ---------------------
    // Per docs/spec/security.md §Authorization model (one group admin per
    // group, first-mention auto-promote, /promote swaps admin) +
    // docs/spec/commands.md §Conversation control (/group-timezone).

    /** {@code /promote} invoked from DM scope — requires group scope. */
    public static final String ERROR_PROMOTE_GROUP_SCOPE_REQUIRED = "error.promote.group_scope_required";

    /** {@code /promote <contact>} target is banned — reinstate first. */
    public static final String ERROR_PROMOTE_TARGET_BANNED = "error.promote.target_banned";

    /** {@code /promote <contact>} target is still in probation — ineligible per spec. */
    public static final String ERROR_PROMOTE_TARGET_PROBATION = "error.promote.target_probation";

    /** {@code /promote <contact>} target has no active membership in this group. */
    public static final String ERROR_PROMOTE_TARGET_NOT_IN_GROUP = "error.promote.target_not_in_group";

    /** {@code /promote} success. Token {@code {0}} = redacted target contact id. */
    public static final String REPLY_PROMOTE_SUCCESS = "reply.promote.success";

    /** {@code /demote} invoked from DM scope — requires group scope. */
    public static final String ERROR_DEMOTE_GROUP_SCOPE_REQUIRED = "error.demote.group_scope_required";

    /** {@code /demote <contact>} target is not the current group admin. */
    public static final String ERROR_DEMOTE_TARGET_NOT_ADMIN = "error.demote.target_not_admin";

    /** {@code /demote} success. Token {@code {0}} = redacted target contact id. */
    public static final String REPLY_DEMOTE_SUCCESS = "reply.demote.success";

    /** {@code /group-timezone} invoked from DM scope — group-only command. */
    public static final String ERROR_GROUP_TIMEZONE_DM_SCOPE = "error.group_timezone.dm_scope";

    /** {@code /group-timezone <tz>} where {@code <tz>} is not a valid IANA zone. Token {@code {0}} = supplied zone, {@code {1}} = fuzzy suggestions. */
    public static final String ERROR_GROUP_TIMEZONE_INVALID_ZONE = "error.group_timezone.invalid_zone";

    /** {@code /group-timezone} caller is neither group admin nor bot admin. */
    public static final String ERROR_GROUP_TIMEZONE_NOT_ADMIN = "error.group_timezone.not_admin";

    /** {@code /group-timezone} success. Token {@code {0}} = the timezone that was set. */
    public static final String REPLY_GROUP_TIMEZONE_SUCCESS = "reply.group_timezone.success";

    // ----- /quarantine list|approve|reject (M1-081b) ---------------------------
    // Per docs/spec/commands.md §Admin (bot admin) + docs/spec/security.md
    // §Quarantine workflow. The handler dispatches on list/approve/reject
    // subcommands; approve and reject call the SECURITY DEFINER stored
    // procedures from V21. Plain text only.

    /** {@code /quarantine} with no subcommand or an unrecognized one. */
    public static final String ERROR_QUARANTINE_UNKNOWN_SUBCOMMAND =
            "error.quarantine.unknown_subcommand";

    /** {@code /quarantine approve} or {@code /quarantine reject} with no quarantine ID argument. Token {@code {0}} = the subcommand name. */
    public static final String ERROR_QUARANTINE_MISSING_ID = "error.quarantine.missing_id";

    /** {@code /quarantine approve <id>} or {@code reject <id>} where {@code <id>} is not a valid UUID. Token {@code {0}} = the supplied string. */
    public static final String ERROR_QUARANTINE_INVALID_ID = "error.quarantine.invalid_id";

    /** Stored procedure raised "quarantine row not found" — the supplied UUID does not match a quarantine row. Token {@code {0}} = the supplied UUID. */
    public static final String ERROR_QUARANTINE_NOT_FOUND = "error.quarantine.not_found";

    /** Stored procedure raised "expected PENDING or BENIGN_CLOSED" — the quarantine row exists but is in a non-actionable state. Token {@code {0}} = the supplied UUID. */
    public static final String ERROR_QUARANTINE_INVALID_STATE = "error.quarantine.invalid_state";

    /**
     * Header line for {@code /quarantine list}. Tokens:
     * {@code {0}} = displayed row count, {@code {1}} = current page (1-indexed),
     * {@code {2}} = total pages.
     */
    public static final String REPLY_QUARANTINE_LIST_HEADER = "reply.quarantine.list.header";

    /**
     * Per-row template for {@code /quarantine list}. Tokens:
     * {@code {0}} = quarantine id, {@code {1}} = post UID,
     * {@code {2}} = flagged_by, {@code {3}} = flagged_at (ISO),
     * {@code {4}} = rule_id, {@code {5}} = status.
     */
    public static final String REPLY_QUARANTINE_LIST_LINE = "reply.quarantine.list.line";

    /** {@code /quarantine list} with zero matching rows. */
    public static final String REPLY_QUARANTINE_LIST_EMPTY = "reply.quarantine.list.empty";

    /** {@code /quarantine approve} success. Token {@code {0}} = quarantine id. */
    public static final String REPLY_QUARANTINE_APPROVE_SUCCESS =
            "reply.quarantine.approve.success";

    /** {@code /quarantine reject} success. Token {@code {0}} = quarantine id. */
    public static final String REPLY_QUARANTINE_REJECT_SUCCESS =
            "reply.quarantine.reject.success";

    /**
     * Confirm prompt for the forensic ({@code BENIGN_CLOSED}) leg of
     * {@code /quarantine reject} (M1-458). Token {@code {0}} = timeout
     * seconds. The routine {@code PENDING} reject is not confirm-gated.
     */
    public static final String REPLY_CONFIRM_PROMPT_QUARANTINE_REJECT =
            "reply.confirm.prompt.quarantine_reject";

    // ----- /audit (M1-081b) --------------------------------------------------
    // Per docs/spec/commands.md §Admin (bot admin) + docs/spec/security.md
    // §DB roles. Reads audit_log_view (V5 redacted view). Filters by
    // --actor, --action, --page. Plain text only.

    /**
     * {@code /audit --action <verb>} where {@code <verb>} is not in the
     * closed {@link app.zcat.infochat.core.audit.AuditAction} enum.
     * Token {@code {0}} = the supplied verb, {@code {1}} = comma-joined
     * accepted values.
     */
    public static final String ERROR_AUDIT_UNKNOWN_ACTION = "error.audit.unknown_action";

    /**
     * Header line for {@code /audit}. Tokens:
     * {@code {0}} = displayed row count, {@code {1}} = current page (1-indexed),
     * {@code {2}} = total pages.
     */
    public static final String REPLY_AUDIT_HEADER = "reply.audit.header";

    /**
     * Per-row template for {@code /audit}. Tokens:
     * {@code {0}} = created_at (ISO), {@code {1}} = action,
     * {@code {2}} = actor contact id (redacted), {@code {3}} = target_kind,
     * {@code {4}} = target_id.
     */
    public static final String REPLY_AUDIT_LINE = "reply.audit.line";

    /** {@code /audit} with zero matching rows. */
    public static final String REPLY_AUDIT_EMPTY = "reply.audit.empty";

    // ----- D47 group approval gate (M1-112) ----------------------------------
    // Per docs/spec/security.md §Authorization model step 3.5 +
    // docs/spec/messaging.md §Identity and groups + docs/design/04-security.md
    // §4.9 (per-user activation cap + global max-groups). The four keys back
    // the InboundRouter step-3.5 short-circuits + GroupApprovalService cap
    // rejections. Plain text only — single backticks for inline literals
    // (e.g. the redacted contact id), no markdown link syntax.

    /** Fixed reply when a group-scope @mention lands in a {@code approval_status='pending'} group (creation + subsequent @mentions until approval). */
    public static final String GROUP_PENDING = "group.pending";

    /** Fixed reply when a group-scope @mention lands in a {@code approval_status='rejected'} group. */
    public static final String GROUP_REJECTED = "group.rejected";

    /** Fixed reply when first @mention would exceed the per-user activation cap ({@code infochat.groups.per-user-activation-cap}). */
    public static final String GROUP_ACTIVATION_LIMIT = "group.activation_limit";

    /** Fixed reply when first @mention would exceed the global max-groups cap ({@code infochat.groups.global-max-groups}). */
    public static final String GROUP_GLOBAL_LIMIT = "group.global_limit";

    /**
     * Fixed reply when a group chat-mode message exhausts the per-group
     * LLM sub-bucket ({@code infochat.ratelimit.group-llm-per-15min},
     * M1-222). Per {@code docs/design/04-security.md} §4.9 the LLM-rate
     * overflow action is this fixed reply — unlike the reply-rate
     * bucket's silent drop.
     */
    public static final String GROUP_LLM_RATE_LIMIT = "group.llm_rate_limit";

    /**
     * Fixed reply when a group slash command exhausts the per-group
     * command sub-bucket
     * ({@code infochat.ratelimit.group-commands-per-15min}, M1-222
     * redteam follow-up). Per {@code docs/design/04-security.md} §4.9
     * the command-rate overflow action is this fixed reply — unlike
     * the reply-rate bucket's silent drop.
     */
    public static final String GROUP_COMMAND_RATE_LIMIT = "group.command_rate_limit";

    // ----- D47 admin commands — /approve-group + /reject-group + /list-groups (M1-113) -----
    // Per docs/spec/commands.md §Admin (bot admin) + decision D47.
    // The three handlers operate on groups.approval_status. /approve-group
    // is constructive (no confirm); /reject-group is destructive (confirm
    // via the M1-051 ConfirmStateService pattern, mirroring /ban). The
    // group.approved_message / group.rejected_message keys are sent to
    // the TARGET group (not the admin's scope), so they need self-contained
    // text that reads independently of any admin context.

    /** One-time message sent to the target group on /approve-group success. Plain text, no tokens. */
    public static final String GROUP_APPROVED_MESSAGE = "group.approved_message";

    /** One-time message sent to the target group on /reject-group success. Plain text, no tokens. */
    public static final String GROUP_REJECTED_MESSAGE = "group.rejected_message";

    /** {@code /approve-group} success reply to the admin. Token {@code {0}} = group id (UUID). */
    public static final String REPLY_APPROVE_GROUP_SUCCESS = "reply.approve_group.success";

    /** {@code /approve-group} no-op reply when the group is already approved. Token {@code {0}} = group id (UUID). */
    public static final String REPLY_APPROVE_GROUP_NOOP = "reply.approve_group.noop";

    /** {@code /reject-group confirm} success reply to the admin. Token {@code {0}} = group id (UUID). */
    public static final String REPLY_REJECT_GROUP_SUCCESS = "reply.reject_group.success";

    /** {@code /reject-group} no-op reply when the group is already rejected. Token {@code {0}} = group id (UUID). */
    public static final String REPLY_REJECT_GROUP_NOOP = "reply.reject_group.noop";

    /** {@code /list-groups} reply when no groups exist at all. */
    public static final String REPLY_LIST_GROUPS_EMPTY = "reply.list_groups.empty";

    /**
     * Header line for {@code /list-groups}. Tokens:
     * {@code {0}} = displayed row count, {@code {1}} = current page (1-indexed),
     * {@code {2}} = total pages.
     */
    public static final String REPLY_LIST_GROUPS_HEADER = "reply.list_groups.header";

    /**
     * Per-row template for {@code /list-groups}. Tokens:
     * {@code {0}} = group id (UUID), {@code {1}} = approval_status,
     * {@code {2}} = activated_by redacted contact id (or {@code -} if NULL),
     * {@code {3}} = member count, {@code {4}} = timezone.
     */
    public static final String REPLY_LIST_GROUPS_LINE = "reply.list_groups.line";

    /**
     * {@code /approve-group <id>} or {@code /reject-group <id>}: no
     * {@code groups} row with the supplied id exists. Token {@code {0}} =
     * the supplied id. Distinct from {@link #ERROR_CONTACT_NOT_REGISTERED}
     * because the target is a group, not a user.
     */
    public static final String ERROR_GROUP_NOT_FOUND = "error.group_not_found";

    /**
     * First-call prompt template for {@code /reject-group}. Tokens:
     * {@code {0}} = timeout in whole seconds, {@code {1}} = group id (UUID).
     */
    public static final String REPLY_CONFIRM_PROMPT_REJECT_GROUP = "reply.confirm.prompt.reject_group";

    // ----- /recover-pool (M1-526) ---------------------------------------------
    // Bot-admin in-band recovery of the auto_joined_group pool (remediates
    // M1-519 redteam Finding 2). No-arg invocation lists the active pool;
    // `/recover-pool <adapter> <upstream-group-id>` frees one slot by natural
    // key. Plain text only, bare values — no markdown.

    /** {@code /recover-pool} reply when the active auto-joined pool is empty. */
    public static final String REPLY_RECOVER_POOL_EMPTY = "reply.recover_pool.empty";

    /**
     * Header line for {@code /recover-pool} list mode. Token {@code {0}} =
     * number of active slots listed.
     */
    public static final String REPLY_RECOVER_POOL_HEADER = "reply.recover_pool.header";

    /**
     * Per-row template for {@code /recover-pool} list mode. Tokens:
     * {@code {0}} = adapter, {@code {1}} = upstream group id,
     * {@code {2}} = inviter user id (UUID), {@code {3}} = joined-at timestamp.
     */
    public static final String REPLY_RECOVER_POOL_LINE = "reply.recover_pool.line";

    /**
     * {@code /recover-pool <adapter> <upstream-group-id>} success reply. Tokens
     * (command-argument order): {@code {0}} = adapter, {@code {1}} = upstream
     * group id.
     */
    public static final String REPLY_RECOVER_POOL_FREED = "reply.recover_pool.freed";

    /**
     * {@code /recover-pool <adapter> <upstream-group-id>}: no active (non-freed)
     * auto-join slot matches the supplied natural key (unknown group, or already
     * freed). Tokens (command-argument order): {@code {0}} = adapter,
     * {@code {1}} = upstream group id.
     */
    public static final String ERROR_RECOVER_POOL_NOT_FOUND = "error.recover_pool.not_found";

    // ----- /status (M1-114) ---------------------------------------------------
    // Per docs/spec/commands.md §Discovery (/status — runtime status; admin
    // view includes a count of pending groups for passive discovery). Plain
    // text only — bare URLs per D30, no markdown link syntax. The pending-
    // groups line is appended ONLY when the caller resolves to is_admin=TRUE
    // (per-adapter actor lookup); non-admin callers do not see the count.

    /** {@code /status} profile line. Token {@code {0}} = the {@code infochat.profile.label} value (e.g. {@code laptop}). */
    public static final String REPLY_STATUS_PROFILE = "reply.status.profile";

    /** {@code /status} uptime line. Token {@code {0}} = human-readable uptime (e.g. {@code 3h 12m}). */
    public static final String REPLY_STATUS_UPTIME = "reply.status.uptime";

    /**
     * {@code /status} admin-only line: count of groups with
     * {@code approval_status='pending' AND removed_at IS NULL}.
     * Token {@code {0}} = the count. Appended only when the caller
     * resolves to {@code is_admin=TRUE}.
     */
    public static final String REPLY_STATUS_PENDING_GROUPS = "reply.status.pending_groups";

    // ----- /digest on|off (M1-227) -------------------------------------------
    // Per docs/spec/commands.md §Conversation control (/digest on|off) +
    // §Permission model. Group-admin-or-bot-admin toggle of
    // groups.digest_enabled; the scheduler ANDs the flag into its
    // group-selection query. Plain text only — single backticks for the
    // inline `on`/`off` literals, no markdown link syntax.

    /** Short-help line for {@code /digest} (group-admin tier). */
    public static final String HELP_CMD_DIGEST_SHORT = "help.cmd.digest.short";

    /** {@code /digest on} success — the scheduled digest is resumed for the group. */
    public static final String REPLY_DIGEST_ON = "reply.digest.on";

    /** {@code /digest off} success — the scheduled digest is paused for the group. */
    public static final String REPLY_DIGEST_OFF = "reply.digest.off";

    /** {@code /digest on} when the group is already enabled — idempotent no-op (no UPDATE, no audit row). */
    public static final String REPLY_DIGEST_ALREADY_ON = "reply.digest.already_on";

    /** {@code /digest off} when the group is already paused — idempotent no-op (no UPDATE, no audit row). */
    public static final String REPLY_DIGEST_ALREADY_OFF = "reply.digest.already_off";

    /** {@code /digest} invoked from DM scope — group-only command (there is no DM periodic digest in v1). */
    public static final String ERROR_DIGEST_DM_SCOPE = "error.digest.dm_scope";

    /** {@code /digest} caller is neither group admin nor bot admin. */
    public static final String ERROR_DIGEST_NOT_ADMIN = "error.digest.not_admin";

    /** {@code /digest} with a missing or unrecognized sub-verb — usage error naming the two sub-verbs (never a silent no-op). */
    public static final String ERROR_DIGEST_USAGE = "error.digest.usage";

    /** {@code /retry --digest} rejected because the group's digest is paused ({@code digest_enabled = false}); regenerating a stale cached digest around the pause is blocked. */
    public static final String ERROR_RETRY_DIGEST_PAUSED = "error.retry.digest_paused";

    // ----- Router size-cap + /export literal demotions (M1-268) -----------
    // Per decision D43: every user-visible deterministic string flows
    // through the bundle. These keys replace the last hardcoded English
    // reply literals in InboundRouter and ExportCommandHandler. {0}/{1}
    // interpolation tokens are filled by the caller via
    // java.text.MessageFormat.

    /**
     * Defense-in-depth oversize-inbound reply. Looked up by
     * InboundRouter BEFORE any DB step runs (the size cap fires ahead
     * of the users-row snapshot by design), so it always renders in the
     * pre-resolution context default {@code en} — the key exists so the
     * literal lives in the bundle, not so the reply localizes.
     */
    public static final String ERROR_ROUTER_MESSAGE_TOO_LARGE = "error.router.message_too_large";

    /** {@code /export} invoked in group scope — DM-only until the group actor seam lands. */
    public static final String ERROR_EXPORT_GROUP_NOT_SUPPORTED = "error.export.group_not_supported";

    /** {@code /export} caller's users row could not be resolved. */
    public static final String ERROR_EXPORT_NO_USER = "error.export.no_user";

    /** {@code /export --page N} beyond a single-page export. Token {0} = requested page. */
    public static final String ERROR_EXPORT_PAGE_OUT_OF_RANGE_ONE = "error.export.page_out_of_range_one";

    /** {@code /export --page N} beyond a multi-page export. Token {0} = requested page, {1} = total pages. */
    public static final String ERROR_EXPORT_PAGE_OUT_OF_RANGE_MANY = "error.export.page_out_of_range_many";

    // ----- Translation fallback note (M1-437) --------------------------------
    // Per docs/spec/llm.md §Failure handling (recap) + decision D43. When the
    // delivery-direction translation pipeline cannot produce usable target-
    // language output (provider error, blank output, or output identical to
    // the English input), TranslationPipeline returns the post-sanitizer-1
    // English text plus this one-line note, resolved in the scope language so
    // the user is told why the reply is in English rather than seeing a hung
    // or garbled response.

    /** One-line note appended to the English text on any translation fallback. No tokens; resolved via the 2-arg accessor in the scope language. */
    public static final String REPLY_TRANSLATION_UNAVAILABLE = "reply.translation.unavailable";

    // ----- Topic-grouped periodic digest (M1-641, D62) ------------------------
    // Per docs/spec/commands.md §Periodic group digests. The non-degraded
    // digest renders its clusters under deterministic category headers —
    // pure tag arithmetic, no LLM (D62) — with a per-section item cap and
    // one closing affordance line. Headers resolve in the group's scope
    // language and are uppercased in code (v1 output is plain text per D30,
    // so caps are the strongest header anchor); as deterministic bundle
    // strings they never pass through the translation pipeline.

    /** Category section header template. Token {@code {0}} = the category tag (e.g. {@code ai}); the formatted line is uppercased in code. */
    public static final String REPLY_DIGEST_CATEGORY_HEADER = "reply.digest.category.header";

    /** Header for the Other bucket (clusters with no qualifying category tag); uppercased in code. No tokens; resolved via the 2-arg accessor. */
    public static final String REPLY_DIGEST_CATEGORY_OTHER = "reply.digest.category.other";

    /** Capped-section overflow line. Token {@code {0}} = the count of clusters not shown; the cs value carries a {@code {0,choice,...}} plural shape. */
    public static final String REPLY_DIGEST_CATEGORY_MORE = "reply.digest.category.more";

    /** One closing affordance line ending every non-degraded digest (group scope, so it steers to @mention). No tokens; resolved via the 2-arg accessor. */
    public static final String REPLY_DIGEST_CLOSING_AFFORDANCE = "reply.digest.closing_affordance";

    private BundleKeys() {
        throw new AssertionError("BundleKeys is a constant holder and must not be instantiated");
    }
}
