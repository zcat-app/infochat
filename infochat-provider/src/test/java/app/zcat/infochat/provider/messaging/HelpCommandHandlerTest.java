package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.command.AssetCommandFamilyOracle;
import app.zcat.infochat.provider.command.CommandPermissions;
import app.zcat.infochat.provider.command.asset.AssetRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Handler-tier (plain JUnit, no Quarkus boot) test for
 * {@link HelpCommandHandler} per {@code docs/spec/commands.md}
 * §Discovery (context-aware, per-tier-filtered, scope-aware /help).
 *
 * <p>The DB-backed tier resolution ({@code resolveTier}) is a
 * package-private seam this test overrides with a fixed
 * {@link HelpCommandHandler.CallerTier}, so the catalogue-filtering
 * logic under test runs without a {@link javax.sql.DataSource} or the
 * group repositories — mirroring the {@code InboundRouter#lookupUser}
 * test seam. Behavioral invariants:</p>
 * <ol>
 *   <li>DM non-admin sees user commands, hides bot-admin and group-only
 *       commands; group scope swaps the header.</li>
 *   <li>Bot admin additionally sees bot-admin commands.</li>
 *   <li>Group scope shows group-admin-only commands only to the group
 *       admin; the dual (user-in-DM / group-admin-in-group) commands
 *       follow the same gate.</li>
 *   <li>A probation caller sees only the slow-start allowed subset plus
 *       the probation footer.</li>
 *   <li>A missing bundle key propagates rather than shipping a partial
 *       reply (bundle-completeness CI alignment).</li>
 *   <li>The reply contains no markdown link syntax / HTML anchors (D30).</li>
 * </ol>
 */
class HelpCommandHandlerTest {

    private BundleLoader productionBundleLoader;
    private CommandPermissions commandPermissions;

    @BeforeEach
    void buildCollaborators() throws Exception {
        // Without CDI driving the @PostConstruct lifecycle, the test
        // constructs BundleLoader by hand and invokes the
        // package-private load() via reflection so the production
        // properties land in the instance.
        productionBundleLoader = new BundleLoader();
        Method load = BundleLoader.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(productionBundleLoader);

        // The real probation classifier so the probation-filter test
        // exercises the genuine allowed-subset (the same predicate the
        // intake gate uses), not a stubbed always-true.
        commandPermissions = new CommandPermissions(new AssetCommandFamilyOracle());
    }

    @Test
    void handlerNameIsHelp() {
        assertEquals("help", handlerFor(dm(false, false, false), productionBundleLoader).name());
    }

    @Test
    void dmNonAdminSeesUserCommandsAndHidesAdminAndGroupOnlyCommands() {
        String body = handlerFor(dm(false, false, false), productionBundleLoader)
                .handle(new ScopeRef.Dm("alice"), "/help").text();

        assertTrue(body.contains(productionBundleLoader.get(BundleKeys.HELP_HEADER_DM_USER)),
                "DM reply must carry the DM header: " + body);
        // User-tier and dual-in-DM commands are visible.
        assertContainsLine(body, BundleKeys.HELP_CMD_HELP_SHORT);
        assertContainsLine(body, BundleKeys.HELP_CMD_SUMMARY_SHORT);
        assertContainsLine(body, BundleKeys.HELP_CMD_STATUS_SHORT);
        assertContainsLine(body, BundleKeys.HELP_CMD_GET_TAGS_SHORT);
        assertContainsLine(body, BundleKeys.HELP_CMD_GET_SOURCES_SHORT);
        assertContainsLine(body, BundleKeys.HELP_CMD_SAVE_SHORT);
        assertContainsLine(body, BundleKeys.HELP_CMD_ADD_SOURCE_SHORT);
        assertContainsLine(body, BundleKeys.HELP_CMD_LANG_SHORT);
        // Bot-admin commands hidden from a non-admin.
        assertOmitsLine(body, BundleKeys.HELP_CMD_BAN_SHORT);
        assertOmitsLine(body, BundleKeys.HELP_CMD_GRANT_ADMIN_SHORT);
        // Group-only command hidden in DM.
        assertOmitsLine(body, BundleKeys.HELP_CMD_GROUP_TIMEZONE_SHORT);
        // No probation footer for a non-probation caller.
        assertOmitsLine(body, BundleKeys.HELP_FOOTER_PROBATION);
    }

    @Test
    void dmBotAdminSeesBotAdminCommands() {
        String body = handlerFor(dm(true, false, false), productionBundleLoader)
                .handle(new ScopeRef.Dm("admin"), "/help").text();

        assertContainsLine(body, BundleKeys.HELP_CMD_BAN_SHORT);
        assertContainsLine(body, BundleKeys.HELP_CMD_GRANT_ADMIN_SHORT);
        assertContainsLine(body, BundleKeys.HELP_CMD_LIST_GROUPS_SHORT);
        // group-timezone is group-only — hidden in DM even for a bot admin.
        assertOmitsLine(body, BundleKeys.HELP_CMD_GROUP_TIMEZONE_SHORT);
    }

    @Test
    void groupNonGroupAdminHidesGroupAdminAndDualCommands() {
        String body = handlerFor(group(false, false, false), productionBundleLoader)
                .handle(new ScopeRef.Group("g1"), "/help").text();

        assertTrue(body.contains(productionBundleLoader.get(BundleKeys.HELP_HEADER_GROUP)),
                "group reply must carry the group header: " + body);
        // Plain user commands still visible in a group.
        assertContainsLine(body, BundleKeys.HELP_CMD_SUMMARY_SHORT);
        assertContainsLine(body, BundleKeys.HELP_CMD_STOP_SHORT);
        // Dual command is group-admin-gated in a group.
        assertOmitsLine(body, BundleKeys.HELP_CMD_ADD_SOURCE_SHORT);
        // Group-admin-only command hidden from a non-group-admin.
        assertOmitsLine(body, BundleKeys.HELP_CMD_GROUP_TIMEZONE_SHORT);
        // Bot-admin command hidden.
        assertOmitsLine(body, BundleKeys.HELP_CMD_BAN_SHORT);
    }

    @Test
    void groupAdminSeesGroupAdminAndDualCommandsButNotBotAdminCommands() {
        String body = handlerFor(group(false, true, false), productionBundleLoader)
                .handle(new ScopeRef.Group("g1"), "/help").text();

        assertContainsLine(body, BundleKeys.HELP_CMD_GROUP_TIMEZONE_SHORT);
        assertContainsLine(body, BundleKeys.HELP_CMD_ADD_SOURCE_SHORT);
        assertContainsLine(body, BundleKeys.HELP_CMD_FOLLOW_TAG_SHORT);
        // Still not a bot admin.
        assertOmitsLine(body, BundleKeys.HELP_CMD_BAN_SHORT);
    }

    @Test
    void probationCallerSeesOnlyAllowedSubsetPlusFooter() {
        String body = handlerFor(dm(false, false, true), productionBundleLoader)
                .handle(new ScopeRef.Dm("rookie"), "/help").text();

        assertTrue(body.contains(productionBundleLoader.get(BundleKeys.HELP_FOOTER_PROBATION)),
                "probation reply must carry the probation footer: " + body);
        // Allowed-during-probation commands present.
        assertContainsLine(body, BundleKeys.HELP_CMD_HELP_SHORT);
        assertContainsLine(body, BundleKeys.HELP_CMD_SUMMARY_SHORT);
        assertContainsLine(body, BundleKeys.HELP_CMD_STATUS_SHORT);
        assertContainsLine(body, BundleKeys.HELP_CMD_GET_TAGS_SHORT);
        assertContainsLine(body, BundleKeys.HELP_CMD_GET_SOURCES_SHORT);
        assertContainsLine(body, BundleKeys.HELP_CMD_LIST_SOURCES_SHORT);
        assertContainsLine(body, BundleKeys.HELP_CMD_SAVED_SHORT);
        assertContainsLine(body, BundleKeys.HELP_CMD_EXPORT_SHORT);
        assertContainsLine(body, BundleKeys.HELP_CMD_LANG_SHORT);
        assertContainsLine(body, BundleKeys.HELP_CMD_FORGET_SHORT);
        assertContainsLine(body, BundleKeys.HELP_CMD_STOP_SHORT);
        // Write / chat-mode commands NOT allowed during probation.
        assertOmitsLine(body, BundleKeys.HELP_CMD_SAVE_SHORT);
        assertOmitsLine(body, BundleKeys.HELP_CMD_CLEAR_SHORT);
        assertOmitsLine(body, BundleKeys.HELP_CMD_ADD_SOURCE_SHORT);
        // Admin commands never shown during probation.
        assertOmitsLine(body, BundleKeys.HELP_CMD_BAN_SHORT);
    }

    @Test
    void unfollowSourceShownToDmUserAndGroupAdminButNotPlainGroupMember() {
        // USER_OR_GROUP_ADMIN tier (M1-419): open to any user in DM,
        // group-admin-gated in a group.
        assertContainsLine(handlerFor(dm(false, false, false), productionBundleLoader)
                        .handle(new ScopeRef.Dm("alice"), "/help").text(),
                BundleKeys.HELP_CMD_UNFOLLOW_SOURCE_SHORT);
        assertContainsLine(handlerFor(group(false, true, false), productionBundleLoader)
                        .handle(new ScopeRef.Group("g1"), "/help").text(),
                BundleKeys.HELP_CMD_UNFOLLOW_SOURCE_SHORT);
        assertOmitsLine(handlerFor(group(false, false, false), productionBundleLoader)
                        .handle(new ScopeRef.Group("g1"), "/help").text(),
                BundleKeys.HELP_CMD_UNFOLLOW_SOURCE_SHORT);
    }

    @Test
    void missingBundleKeyCausesHandlerToFailInsteadOfShippingIncompleteReply() {
        // Spy permits no keys — every BundleLoader.get(...) throws as the
        // real BundleLoader would for a missing entry. Handler must
        // propagate; silently catching would defeat the bundle-completeness
        // CI guard.
        HelpCommandHandler handler = handlerFor(dm(false, false, false),
                new RecordingBundleLoader(Set.of()));

        assertThrows(IllegalStateException.class,
                () -> handler.handle(new ScopeRef.Dm("alice"), "/help"));
    }

    @Test
    void replyContainsNoMarkdownLinkSyntaxOrHtmlAnchors() {
        String body = handlerFor(dm(true, false, false), productionBundleLoader)
                .handle(new ScopeRef.Dm("admin"), "/help").text();

        assertFalse(containsMarkdownLink(body),
                "reply must not contain markdown link syntax [text](url): " + body);
        assertFalse(body.contains("<a href="),
                "reply must not contain HTML anchor tags: " + body);
    }

    @Test
    void enabledAssetLineResolvesThroughBundleInScopeLanguage() {
        // One enabled asset + a cs scope language: the asset /help line must
        // resolve through the bundle (no inline English) like every other line.
        HelpCommandHandler handler = new HelpCommandHandler() {
            @Override
            HelpCommandHandler.CallerTier resolveTier(ScopeRef scope) {
                return dm(false, false, false);
            }
        };
        handler.bundleLoader = productionBundleLoader;
        handler.commandPermissions = commandPermissions;
        InboundContext context = new InboundContext();
        context.setEffectiveLanguage("cs");
        handler.inboundContext = context;
        handler.assetRegistry = new AssetRegistry() {
            @Override
            public List<AssetRegistry.AssetEntry> getEnabledAssets() {
                return List.of(new AssetRegistry.AssetEntry(
                        "zcash", "Zcash",
                        List.of(new AssetRegistry.SubVerbEntry(
                                "price", true, true, "https://example.com", "usd")),
                        List.of("usd")));
            }
        };

        String body = handler.handle(new ScopeRef.Dm("alice"), "/help").text();

        assertTrue(body.contains("/zcash [sub-verb] [--vs <currency>] — tržní data Zcash (price)"),
                "asset line must render in cs through the bundle; got: " + body);
        assertFalse(body.contains("market data"),
                "cs asset line must not concatenate inline English 'market data'; got: " + body);
    }

    // ----- helpers ----------------------------------------------------------

    /**
     * Build a handler whose {@code resolveTier} is pinned to {@code tier},
     * wired with the supplied bundle loader and the real
     * {@link CommandPermissions}. The DB / repository collaborators are
     * left null because the overridden {@code resolveTier} never reaches
     * them.
     */
    private HelpCommandHandler handlerFor(HelpCommandHandler.CallerTier tier, BundleLoader loader) {
        HelpCommandHandler handler = new HelpCommandHandler() {
            @Override
            HelpCommandHandler.CallerTier resolveTier(ScopeRef scope) {
                return tier;
            }
        };
        handler.bundleLoader = loader;
        handler.commandPermissions = commandPermissions;
        // Fresh context → effectiveLanguage() returns the "en" default.
        handler.inboundContext = new InboundContext();
        // No-arg registry carries no assets → no asset lines in the reply.
        handler.assetRegistry = new AssetRegistry();
        return handler;
    }

    private static HelpCommandHandler.CallerTier dm(boolean botAdmin, boolean groupAdmin, boolean probation) {
        return new HelpCommandHandler.CallerTier(botAdmin, groupAdmin, probation, false);
    }

    private static HelpCommandHandler.CallerTier group(boolean botAdmin, boolean groupAdmin, boolean probation) {
        return new HelpCommandHandler.CallerTier(botAdmin, groupAdmin, probation, true);
    }

    private void assertContainsLine(String body, String bundleKey) {
        String line = productionBundleLoader.get(bundleKey);
        assertTrue(body.contains(line),
                "reply must contain the line for " + bundleKey + " (" + line + "); got: " + body);
    }

    private void assertOmitsLine(String body, String bundleKey) {
        String line = productionBundleLoader.get(bundleKey);
        assertFalse(body.contains(line),
                "reply must NOT contain the line for " + bundleKey + " (" + line + "); got: " + body);
    }

    /** Minimal {@code [text](url)} detector: a `[` followed (eventually) by `](` and a closing `)` on the same line. */
    private static boolean containsMarkdownLink(String body) {
        for (String line : body.split("\n", -1)) {
            int open = line.indexOf('[');
            if (open < 0) {
                continue;
            }
            int closeBracketParenOpen = line.indexOf("](", open);
            if (closeBracketParenOpen >= 0 && line.indexOf(')', closeBracketParenOpen) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hand-rolled spy: returns a synthetic stand-in value for every key
     * in {@code allowedKeys}, throws {@link IllegalStateException} for
     * everything else (same shape the real {@link BundleLoader} raises
     * for an unknown key), and records every lookup attempt for
     * assertion.
     */
    private static final class RecordingBundleLoader extends BundleLoader {
        private final Set<String> allowedKeys;
        final List<String> lookups = new ArrayList<>();

        RecordingBundleLoader(Set<String> allowedKeys) {
            this.allowedKeys = allowedKeys;
        }

        @Override
        public String get(String key) {
            lookups.add(key);
            if (!allowedKeys.contains(key)) {
                throw new IllegalStateException("Missing bundle key: " + key);
            }
            return "value-for:" + key;
        }

        @Override
        public String get(String key, String langCode) {
            return get(key);
        }
    }
}
