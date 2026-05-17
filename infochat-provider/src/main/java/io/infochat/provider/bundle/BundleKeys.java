package io.infochat.provider.bundle;

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

    private BundleKeys() {
        throw new AssertionError("BundleKeys is a constant holder and must not be instantiated");
    }
}
