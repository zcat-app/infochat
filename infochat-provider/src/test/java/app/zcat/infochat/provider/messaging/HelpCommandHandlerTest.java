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
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 *   <li>{@code /help <command>} (M1-573) renders the per-command detail
 *       block gated by the same visibility predicate as the bare list; a
 *       hidden or nonexistent command gets the friendly error whose fuzzy
 *       suggestions never leak invisible names.</li>
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
        commandPermissions = new CommandPermissions(new AssetCommandFamilyOracle(new AssetRegistry()));
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
    void followAllSourcesShownToDmUserAndGroupAdminButNotPlainGroupMember() {
        // USER_OR_GROUP_ADMIN tier (M1-576): open to any user in DM,
        // group-admin-gated in a group — same gate as /add-source.
        assertContainsLine(handlerFor(dm(false, false, false), productionBundleLoader)
                        .handle(new ScopeRef.Dm("alice"), "/help").text(),
                BundleKeys.HELP_CMD_FOLLOW_ALL_SOURCES_SHORT);
        assertContainsLine(handlerFor(group(false, true, false), productionBundleLoader)
                        .handle(new ScopeRef.Group("g1"), "/help").text(),
                BundleKeys.HELP_CMD_FOLLOW_ALL_SOURCES_SHORT);
        assertOmitsLine(handlerFor(group(false, false, false), productionBundleLoader)
                        .handle(new ScopeRef.Group("g1"), "/help").text(),
                BundleKeys.HELP_CMD_FOLLOW_ALL_SOURCES_SHORT);
        // A write command → hidden during probation (absent from the allowed-set).
        assertOmitsLine(handlerFor(dm(false, false, true), productionBundleLoader)
                        .handle(new ScopeRef.Dm("rookie"), "/help").text(),
                BundleKeys.HELP_CMD_FOLLOW_ALL_SOURCES_SHORT);
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

    // ----- /help <command> per-command detail (M1-573) ---------------------

    @Test
    void helpSummaryDetailShowsUsageWindowFlagAndExample() {
        String body = handlerFor(dm(false, false, false), productionBundleLoader)
                .handle(new ScopeRef.Dm("alice"), "/help summary").text();

        assertTrue(body.contains(productionBundleLoader.get(BundleKeys.HELP_CMD_SUMMARY_USAGE)),
                "detail must carry the full /summary usage block; got: " + body);
        assertTrue(body.contains("/summary [tag] [-w <duration>]"),
                "detail must carry the signature line; got: " + body);
        assertTrue(body.contains("-w <duration>") && body.contains("default 24h"),
                "detail must describe the -w flag with its default; got: " + body);
        assertTrue(body.contains("1h-168h, 1d-30d, 1w-4w"),
                "detail must list the accepted -w ranges; got: " + body);
        // Header directly above the first example line, which keeps its
        // two-space indent (the bundle values escape it as \ \ so
        // Properties.load does not strip it).
        assertTrue(body.contains(productionBundleLoader.get(BundleKeys.HELP_DETAIL_EXAMPLES_HEADER) + "\n  /summary"),
                "detail must carry the Examples header above the indented examples; got: " + body);
        assertTrue(body.contains("\n  /summary -w 7d"),
                "detail must carry a concrete indented example; got: " + body);
    }

    @Test
    void helpDetailIgnoresTokensAfterTheFirstArgument() {
        HelpCommandHandler handler = handlerFor(dm(false, false, false), productionBundleLoader);

        assertEquals(handler.handle(new ScopeRef.Dm("alice"), "/help summary").text(),
                handler.handle(new ScopeRef.Dm("alice"), "/help summary -w 7d").text());
    }

    @Test
    void helpDetailAcceptsLeadingSlashOnTheArgument() {
        HelpCommandHandler handler = handlerFor(dm(false, false, false), productionBundleLoader);

        assertEquals(handler.handle(new ScopeRef.Dm("alice"), "/help summary").text(),
                handler.handle(new ScopeRef.Dm("alice"), "/help /summary").text());
    }

    @Test
    void helpUnknownCommandReturnsFriendlyErrorWithVisibleSuggestions() {
        String body = handlerFor(dm(false, false, false), productionBundleLoader)
                .handle(new ScopeRef.Dm("alice"), "/help summry").text();

        // M1-647 strengthened this from "contains the echoed name" to full
        // equality: the reply must be EXACTLY the suggestion template, which
        // simultaneously pins the suggestion and proves nothing else (notably
        // the requested name) leaked into it.
        assertEquals(MessageFormat.format(
                        productionBundleLoader.get(BundleKeys.ERROR_HELP_UNKNOWN_COMMAND),
                        "/summary"),
                body,
                "unknown reply must be the suggestion template naming /summary; got: " + body);
        assertFalse(body.contains("summry"),
                "the requested name must not appear in the reply; got: " + body);
        assertTrue(body.contains("/help"),
                "unknown reply must point back at /help; got: " + body);
    }

    @Test
    void suggestsUnfollowSourceForMute() {
        // M1-647: "mute" shares no prefix with any command, so the
        // shared-prefix predecessor scored it 0 across the board and offered
        // the alphabetically-first names instead.
        String body = handlerFor(dm(false, false, false), productionBundleLoader)
                .handle(new ScopeRef.Dm("alice"), "/help mute").text();

        assertEquals(MessageFormat.format(
                        productionBundleLoader.get(BundleKeys.ERROR_HELP_UNKNOWN_COMMAND),
                        "/unfollow-source"),
                body,
                "'mute' must resolve to /unfollow-source alone — not to the "
                        + "alphabetically-first names the prefix-only ranking returned");
    }

    @Test
    void noCloseMatchOffersNoCommandList() {
        // M1-647: the failure being fixed is confident misdirection, not
        // silence — a query matching nothing must be told so, not handed
        // irrelevant names it might mistake for an answer.
        String body = handlerFor(dm(false, false, false), productionBundleLoader)
                .handle(new ScopeRef.Dm("alice"), "/help xyzzy").text();

        assertEquals(productionBundleLoader.get(BundleKeys.ERROR_UNKNOWN_COMMAND), body,
                "a query with no close match must get the flat reply, which interpolates "
                        + "nothing at all");
        assertFalse(body.contains("xyzzy"),
                "the requested name must not appear in the reply; got: " + body);

        for (HelpCommandHandler.CommandHelp entry : HelpCommandHandler.CATALOGUE) {
            // /help itself is exempt: the reply's whole point is to redirect
            // there. Every other catalogue name is a suggestion this reply
            // must not make.
            if (entry.command().equals("help")) {
                continue;
            }
            assertFalse(body.contains("/" + entry.command()),
                    "no-close-match reply must name no commands, found /" + entry.command()
                            + "; got: " + body);
        }
    }

    @Test
    void synonymForAdminCommandLeaksNothingToNonAdmin() {
        // The security crux (docs/spec/commands.md §Permission model): an
        // intent word that resolves to a bot-admin command must produce the
        // same no-close-match reply an unmatched query does. Suggesting
        // /grant-admin here would turn the synonym map into the very
        // existence oracle visible() exists to prevent.
        String nonAdmin = handlerFor(dm(false, false, false), productionBundleLoader)
                .handle(new ScopeRef.Dm("alice"), "/help makeadmin").text();

        assertFalse(nonAdmin.contains("grant-admin"),
                "a non-admin's synonym must not name the bot-admin command; got: " + nonAdmin);
        assertEquals(productionBundleLoader.get(BundleKeys.ERROR_UNKNOWN_COMMAND), nonAdmin,
                "the tier-filtered synonym must fall back to the flat reply");

        // Control: the mapping genuinely exists, so the assertion above is
        // about the tier filter rather than a synonym that never resolved.
        String botAdmin = handlerFor(dm(true, false, false), productionBundleLoader)
                .handle(new ScopeRef.Dm("admin"), "/help makeadmin").text();
        assertTrue(botAdmin.contains("/grant-admin"),
                "a bot admin must get the mapped command; got: " + botAdmin);
    }

    @Test
    void commandUnknownReplyNeverReflectsInboundText() {
        // Redteam remediation, second audit (2026-07-18). The first attempt was
        // an [a-z0-9-] echo filter; the audit broke it immediately, because
        // `grant-admin` is itself inside that alphabet. The fix is structural:
        // the requested name selects suggestions and never reaches output, so
        // there is no filter left to bypass. This surface mattered more than
        // the app's other friendly errors because its template alone rendered
        // `/{0}`, supplying the slash that turns an inbound word into a
        // copy-pasteable command.
        HelpCommandHandler handler = handlerFor(dm(false, false, false), productionBundleLoader);
        String flatReply = productionBundleLoader.get(BundleKeys.ERROR_UNKNOWN_COMMAND);

        // Bare privileged names — the case that defeated the echo filter.
        for (String privileged : List.of("grant-admin", "ban", "promote", "remove-source")) {
            String body = handler.handle(new ScopeRef.Dm("alice"), "/help " + privileged).text();
            assertEquals(flatReply, body,
                    "/help " + privileged + " must return the flat reply; got: " + body);
            assertFalse(body.contains(privileged),
                    "bot output must not carry the privileged name " + privileged
                            + "; got: " + body);
        }

        // Tokens that DO produce a suggestion still leak nothing: ban-source
        // scores 0.7 against add-source via plain edit distance, so it reaches
        // the suggestion branch — which must name only the bot-authored match.
        String withSuggestion = handler.handle(new ScopeRef.Dm("alice"), "/help ban-source").text();
        assertEquals(MessageFormat.format(
                        productionBundleLoader.get(BundleKeys.ERROR_HELP_UNKNOWN_COMMAND),
                        "/add-source, /get-sources"),
                withSuggestion,
                "a matching hostile token must yield only bot-authored names; got: " + withSuggestion);
        assertFalse(withSuggestion.contains("ban-source"),
                "the requested name must not appear; got: " + withSuggestion);

        // Arbitrary punctuation and length are moot once nothing is echoed.
        for (String hostile : List.of("summary/grant-admin", "clear-/ban", "a".repeat(9000))) {
            String body = handler.handle(new ScopeRef.Dm("alice"), "/help " + hostile).text();
            assertFalse(body.contains(hostile),
                    "no inbound text may be reflected; got: " + body);
        }
    }

    @Test
    void probationCallerReceivesIntentSuggestionsWithinTheAllowedSubset() {
        // /help is in CommandPermissions.ALLOWED, so the users least likely to
        // know the vocabulary reach this path before probation ends — the
        // reason this ticket is not made redundant by chat-mode discovery,
        // which fails closed during probation.
        HelpCommandHandler handler = handlerFor(dm(false, false, true), productionBundleLoader);

        String allowed = handler.handle(new ScopeRef.Dm("rookie"), "/help news").text();
        assertTrue(allowed.contains("/summary"),
                "a probation caller's intent word must resolve within the allowed subset; got: "
                        + allowed);

        // The same filter still applies: /save is outside the slow-start subset,
        // so its synonym resolves to nothing rather than naming a command the
        // caller cannot invoke yet.
        String blocked = handler.handle(new ScopeRef.Dm("rookie"), "/help bookmark").text();
        assertFalse(blocked.contains("/save"),
                "a probation-hidden command must not be named by its synonym; got: " + blocked);
    }

    @Test
    void helpDetailOfCommandHiddenFromCallerIsUnknownCommandError() {
        // Non-admin asking for a bot-admin command: same reply as a
        // nonexistent name — the detail view must not confirm existence.
        String nonAdmin = handlerFor(dm(false, false, false), productionBundleLoader)
                .handle(new ScopeRef.Dm("alice"), "/help ban").text();
        // M1-647 strengthened this from "contains the echoed name" to full
        // equality with the flat reply. Byte equality is strictly stronger:
        // it subsumes the old substring checks (no signature, no suggestion
        // naming /ban) AND proves the reply carries no trace of the request.
        assertEquals(productionBundleLoader.get(BundleKeys.ERROR_UNKNOWN_COMMAND), nonAdmin,
                "hidden command must resolve to the flat unknown reply; got: " + nonAdmin);
        assertFalse(nonAdmin.contains("ban"),
                "the reply must carry no trace of the hidden name; got: " + nonAdmin);

        // A probation caller asking for a command outside the slow-start
        // allowed subset (e.g. /save, hidden in the probation list).
        String probation = handlerFor(dm(false, false, true), productionBundleLoader)
                .handle(new ScopeRef.Dm("rookie"), "/help save").text();
        // /save is outside the slow-start subset, but /saved is inside it, so
        // this query legitimately matches — the assertion is that the reply is
        // the suggestion template and nothing more, never the requested name.
        assertEquals(MessageFormat.format(
                        productionBundleLoader.get(BundleKeys.ERROR_HELP_UNKNOWN_COMMAND),
                        "/saved"),
                probation,
                "probation-hidden command must resolve to the suggestion reply; got: " + probation);
        assertFalse(probation.contains(productionBundleLoader.get(BundleKeys.HELP_CMD_SAVE_USAGE)),
                "probation caller must not see the /save detail; got: " + probation);

        // An admin asking the same names gets the real detail.
        String admin = handlerFor(dm(true, false, false), productionBundleLoader)
                .handle(new ScopeRef.Dm("admin"), "/help ban").text();
        assertTrue(admin.contains(productionBundleLoader.get(BundleKeys.HELP_CMD_BAN_USAGE)),
                "bot admin must get the /ban detail; got: " + admin);
    }

    @Test
    void botAdminSeesPendingAndRecoverPoolInListAndDetail() {
        // M1-646: both commands are dispatchable bot-admin handlers, so the
        // help surface must document them — the flat list carries their short
        // lines and /help <cmd> returns usage plus examples.
        HelpCommandHandler handler = handlerFor(dm(true, false, false), productionBundleLoader);
        String list = handler.handle(new ScopeRef.Dm("admin"), "/help").text();
        assertContainsLine(list, BundleKeys.HELP_CMD_PENDING_SHORT);
        assertContainsLine(list, BundleKeys.HELP_CMD_RECOVER_POOL_SHORT);

        String pending = handler.handle(new ScopeRef.Dm("admin"), "/help pending").text();
        assertTrue(pending.contains(productionBundleLoader.get(BundleKeys.HELP_CMD_PENDING_USAGE)),
                "/help pending must carry its usage block; got: " + pending);
        assertTrue(pending.contains(productionBundleLoader.get(BundleKeys.HELP_CMD_PENDING_EXAMPLES)),
                "/help pending must carry its examples block; got: " + pending);

        String recoverPool = handler.handle(new ScopeRef.Dm("admin"), "/help recover-pool").text();
        assertTrue(recoverPool.contains(productionBundleLoader.get(BundleKeys.HELP_CMD_RECOVER_POOL_USAGE)),
                "/help recover-pool must carry its usage block; got: " + recoverPool);
        assertTrue(recoverPool.contains(productionBundleLoader.get(BundleKeys.HELP_CMD_RECOVER_POOL_EXAMPLES)),
                "/help recover-pool must carry its examples block; got: " + recoverPool);
    }

    @Test
    void hiddenTierCommandIsIndistinguishableFromUnknown() {
        // Documenting an admin command must not turn /help into an existence
        // oracle (docs/spec/commands.md §Permission model, "no admin-command
        // existence leak"): to a non-admin, a real-but-hidden name must produce
        // the SAME unknown-command reply a nonexistent name does — never a
        // permission-denied reply, which would confirm the command exists.
        String deniedReply = productionBundleLoader.get(BundleKeys.ERROR_ADMIN_ONLY);
        HelpCommandHandler nonAdmin = handlerFor(dm(false, false, false), productionBundleLoader);

        for (Map.Entry<String, String> hiddenCommand : List.of(
                Map.entry("pending", BundleKeys.HELP_CMD_PENDING_USAGE),
                Map.entry("recover-pool", BundleKeys.HELP_CMD_RECOVER_POOL_USAGE))) {
            String hidden = hiddenCommand.getKey();
            String body = nonAdmin.handle(new ScopeRef.Dm("alice"), "/help " + hidden).text();
            assertEquals(productionBundleLoader.get(BundleKeys.ERROR_UNKNOWN_COMMAND), body,
                    "hidden /" + hidden + " must resolve to the flat unknown reply; got: " + body);
            assertFalse(body.contains(deniedReply),
                    "hidden /" + hidden + " must not return a permission-denied reply; got: " + body);
            // The reply shape matching is necessary but not sufficient: the
            // usage block carries the command's full argument syntax, which is
            // the recon payload a hidden command must never hand a non-admin.
            // Same strength as helpDetailOfCommandHiddenFromCallerIsUnknownCommandError
            // already asserts for /ban's signature.
            assertFalse(body.contains(productionBundleLoader.get(hiddenCommand.getValue())),
                    "hidden /" + hidden + " must not leak its usage block; got: " + body);
        }

        // The nonexistent-name control: a name no handler serves produces the
        // same reply shape, so the two cases are indistinguishable.
        String nonexistent = nonAdmin.handle(new ScopeRef.Dm("alice"), "/help pendinx").text();
        assertEquals(productionBundleLoader.get(BundleKeys.ERROR_UNKNOWN_COMMAND), nonexistent,
                "control: a nonexistent name must produce the flat unknown reply; got: " + nonexistent);

        // The property this test exists for, now stated exactly: M1-647 removed
        // the echo, so a hidden-but-real command and a nonexistent one are not
        // merely similar in shape — they are byte-identical.
        assertEquals(nonAdmin.handle(new ScopeRef.Dm("alice"), "/help pending").text(),
                nonexistent,
                "a hidden real command and a nonexistent name must be byte-identical");

        // Suggestions are drawn only from caller-visible names, so neither
        // hidden name may appear in the nonexistent name's near-miss list.
        assertFalse(nonexistent.contains("/pending"),
                "suggestions must not leak the hidden /pending; got: " + nonexistent);
        assertFalse(nonexistent.contains("/recover-pool"),
                "suggestions must not leak the hidden /recover-pool; got: " + nonexistent);
    }

    @Test
    void helpListSourcesDetailShowsAdminFlagsOnlyToBotAdmin() {
        String nonAdmin = handlerFor(dm(false, false, false), productionBundleLoader)
                .handle(new ScopeRef.Dm("alice"), "/help list-sources").text();
        assertTrue(nonAdmin.contains(productionBundleLoader.get(BundleKeys.HELP_CMD_LIST_SOURCES_USAGE)),
                "non-admin must still get the base /list-sources detail; got: " + nonAdmin);
        assertFalse(nonAdmin.contains("--all"),
                "non-admin detail must not show the --all flag; got: " + nonAdmin);
        assertFalse(nonAdmin.contains("--include-deleted"),
                "non-admin detail must not show --include-deleted; got: " + nonAdmin);

        String admin = handlerFor(dm(true, false, false), productionBundleLoader)
                .handle(new ScopeRef.Dm("admin"), "/help list-sources").text();
        assertTrue(admin.contains("--all") && admin.contains("--include-deleted"),
                "bot-admin detail must show the admin-only flags; got: " + admin);
    }

    @Test
    void helpDetailForEnabledAssetRendersDynamicShortLine() {
        HelpCommandHandler handler = new HelpCommandHandler() {
            @Override
            HelpCommandHandler.CallerTier resolveTier(ScopeRef scope) {
                return dm(false, false, false);
            }
        };
        handler.bundleLoader = productionBundleLoader;
        handler.commandPermissions = commandPermissions;
        handler.inboundContext = new InboundContext();
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

        String body = handler.handle(new ScopeRef.Dm("alice"), "/help zcash").text();

        assertEquals("/zcash [sub-verb] [--vs <currency>] — Zcash market data (price)", body,
                "enabled asset detail must be the existing dynamic short line");

        // Without the asset enabled, the same name is unknown.
        String disabled = handlerFor(dm(false, false, false), productionBundleLoader)
                .handle(new ScopeRef.Dm("alice"), "/help zcash").text();
        assertEquals(productionBundleLoader.get(BundleKeys.ERROR_UNKNOWN_COMMAND), disabled,
                "a non-enabled asset name must resolve to the unknown error; got: " + disabled);
    }

    @Test
    void everyCatalogueCommandRendersADetailBlockForAnEligibleCaller() {
        // botAdmin + groupAdmin in group scope sees all four tiers, so every
        // catalogue command must render: usage block, Examples header,
        // examples block — and stay markdown-free (D30).
        HelpCommandHandler handler = handlerFor(group(true, true, false), productionBundleLoader);
        for (HelpCommandHandler.CommandHelp entry : HelpCommandHandler.CATALOGUE) {
            String body = handler.handle(new ScopeRef.Group("g1"), "/help " + entry.command()).text();
            assertTrue(body.contains(productionBundleLoader.get(entry.usageKey())),
                    "detail for /" + entry.command() + " must carry its usage block; got: " + body);
            assertTrue(body.contains(productionBundleLoader.get(BundleKeys.HELP_DETAIL_EXAMPLES_HEADER)),
                    "detail for /" + entry.command() + " must carry the Examples header");
            assertTrue(body.contains(productionBundleLoader.get(entry.examplesKey())),
                    "detail for /" + entry.command() + " must carry its examples block");
            assertTrue(body.startsWith("/" + entry.command()),
                    "detail for /" + entry.command() + " must start with its signature; got: " + body);
            assertFalse(containsMarkdownLink(body),
                    "detail for /" + entry.command() + " must not contain markdown links: " + body);
            assertFalse(body.contains("<a href="),
                    "detail for /" + entry.command() + " must not contain HTML anchors: " + body);
        }
    }

    @Test
    void helpDetailRendersInScopeLanguage() {
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
        handler.assetRegistry = new AssetRegistry();

        String body = handler.handle(new ScopeRef.Dm("alice"), "/help summary").text();

        assertTrue(body.contains(productionBundleLoader.get(BundleKeys.HELP_CMD_SUMMARY_USAGE, "cs")),
                "cs detail must resolve the cs usage block; got: " + body);
        assertTrue(body.contains(productionBundleLoader.get(BundleKeys.HELP_DETAIL_EXAMPLES_HEADER, "cs")),
                "cs detail must resolve the cs Examples header; got: " + body);
    }

    @Test
    void helpDetailMissingBundleKeyPropagates() {
        HelpCommandHandler handler = handlerFor(dm(false, false, false),
                new RecordingBundleLoader(Set.of()));

        assertThrows(IllegalStateException.class,
                () -> handler.handle(new ScopeRef.Dm("alice"), "/help summary"));
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
