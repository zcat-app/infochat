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

    /** Deterministic reply for an unknown slash command. InboundRouter still uses its own literal at M1-035b's commit; replacing the literal with this lookup is a post-umbrella follow-up. */
    public static final String ERROR_UNKNOWN_COMMAND = "error.unknown_command";

    /** Deterministic reply for any uncaught dispatch exception. Same M1-035b literal/bundle divergence note as {@link #ERROR_UNKNOWN_COMMAND}. */
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

    /** Empty result — zero eligible posts in the window (or zero subscriptions). */
    public static final String REPLY_SUMMARY_NO_POSTS_YET = "reply.summary.no_posts_yet";

    /** LLM unreachable: reply prefix announcing the degraded-fallback form. */
    public static final String REPLY_SUMMARY_DEGRADED_NOTICE = "reply.summary.degraded_notice";

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

    private BundleKeys() {
        throw new AssertionError("BundleKeys is a constant holder and must not be instantiated");
    }
}
