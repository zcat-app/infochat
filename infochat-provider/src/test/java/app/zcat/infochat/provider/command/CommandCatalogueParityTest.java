package app.zcat.infochat.provider.command;

import app.zcat.infochat.provider.messaging.CommandHandler;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Build-time guard against command-catalogue name drift: the PRODUCTION set of
 * {@link CommandHandler} beans must exactly match BOTH the marker-delimited
 * canonical command index in {@code docs/spec/commands.md} (M1-527) and the
 * {@code /help} catalogue the bot renders (M1-646). The two axes are
 * independent — a command can be indexed in the spec and dispatchable yet
 * undocumented by {@code /help}, which is exactly how {@code /pending} and
 * {@code /recover-pool} drifted out.
 *
 * <p>A third axis (M1-651) constrains the catalogue's {@code HelpTier} rather
 * than its names: every base command in the spec's closed privileged-tier list
 * must carry the tier that list implies — {@code BOT_ADMIN} for the bot-admin
 * bullet ({@link #botAdminSpecCommandsCarryBotAdminHelpTier}), and
 * {@code GROUP_ADMIN} / {@code USER_OR_GROUP_ADMIN} for the group-admin bullet
 * per its "in groups" qualifier ({@link #groupAdminSpecCommandsCarryGroupTier}).
 * The first of those javadocs states what the tier axis does and does not cover.
 *
 * <p>The runtime side is enumerated from the real CDI bean graph
 * ({@link BeanManager#getBeans}) — authoritative, no fragile source regex —
 * but EXCLUDING test-only handlers: a {@code @QuarkusTest} container also
 * discovers {@code @ApplicationScoped} {@link CommandHandler} beans declared
 * in test sources (e.g. {@code BoomHandler} in {@code InboundRouterTest},
 * {@code name()="boom"}), which are not part of the shipped command surface.
 * Each bean's DECLARED class ({@link Bean#getBeanClass()}, not the ARC client
 * proxy the {@code @ApplicationScoped} beans expose) is checked against its
 * {@link CodeSource}; classes loaded from {@code target/test-classes} are
 * dropped. This keeps the assertion over production commands only and is
 * robust against any future test-defined handler, not just {@code boom}.
 *
 * <p>The doc side is parsed EXCLUSIVELY from between the
 * {@code <!-- command-index:begin -->} / {@code <!-- command-index:end -->}
 * markers, never from free prose — that is what makes the check
 * false-positive-free: a URL path or a negative mention elsewhere in the file
 * lives outside the marked region and cannot create a spurious match.
 *
 * <p>Asset commands ({@code /zcash}, {@code /monero}) are dynamic,
 * deployment-configured commands dispatched via {@code AssetHandler} /
 * {@code AssetRegistry}, not {@code CommandHandler} beans, so they are outside
 * both sides of this assertion by construction.
 */
@QuarkusTest
class CommandCatalogueParityTest {

    private static final String BEGIN_MARKER = "<!-- command-index:begin -->";
    private static final String END_MARKER = "<!-- command-index:end -->";

    // The compiled-test-output marker. A bean whose declared class loads from
    // here is a test-only handler and is excluded from the production surface.
    private static final String TEST_CLASSES_MARKER = "/test-classes";

    // Surefire runs with the module directory (infochat-provider) as the
    // working directory, so the repo-root spec file is one level up. M1-527.
    private static final Path COMMANDS_MD = Path.of("..", "docs", "spec", "commands.md");

    // The three anchors that bound the two halves of the spec's closed
    // privileged-tier list. Each region is [label, next-label): bot-admin runs
    // to the group-admin label, group-admin runs to the closing prose
    // paragraph. All three are asserted present, and all three are matched at
    // the START of a stripped line rather than anywhere in it — a bare
    // `contains` let a mere prose MENTION of the next label end a region early
    // and silently yield a subset. See closedListRegion.
    private static final String BOT_ADMIN_BULLET_LABEL = "- **Bot-admin only:**";
    private static final String GROUP_ADMIN_BULLET_LABEL = "- **Group-admin";
    private static final String CLOSED_LIST_END_MARKER = "The full per-actor-tier";

    // HelpTier is package-private to the messaging package, so the catalogue's
    // tier is read reflectively and compared by enum NAME rather than by value.
    private static final String BOT_ADMIN_TIER = "BOT_ADMIN";
    private static final String GROUP_ADMIN_TIER = "GROUP_ADMIN";
    private static final String USER_OR_GROUP_ADMIN_TIER = "USER_OR_GROUP_ADMIN";

    // Backticked spec token: `/word(-word)*` optionally followed by sub-verb or
    // flag words — the same shape LlmOutputSanitizerTest matches, before
    // normalization. The QUALIFIED variant additionally captures the spec's
    // trailing "in groups" prose qualifier, which is the group-admin tier
    // discriminator.
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("`(/[A-Za-z0-9\\-]+(?:\\s+(?:--?)?[A-Za-z0-9\\-]+)*)`");
    private static final Pattern QUALIFIED_TOKEN_PATTERN =
            Pattern.compile("`(/[A-Za-z0-9\\-]+(?:\\s+(?:--?)?[A-Za-z0-9\\-]+)*)`(\\s+in groups)?");

    /**
     * Flag-as-identity exemption — the one spec bot-admin base command that
     * legitimately carries a non-{@code BOT_ADMIN} tier. {@code /list-sources}
     * itself is open to everyone ({@code HelpTier.USER}); only its
     * {@code --all} / {@code --include-deleted} flags are bot-admin, and they
     * render from the separate {@code HELP_CMD_LIST_SOURCES_USAGE_ADMIN}
     * suffix key rather than from the entry's tier (spec §Discovery). So the
     * base entry is USER by design, not by mistake.
     *
     * <p>{@link #botAdminSpecCommandsCarryBotAdminHelpTier} asserts this set
     * equals exactly {@code {list-sources}}, so widening it later is a
     * deliberate, reviewable edit rather than a silent addition.
     */
    private static final Set<String> TIER_EXEMPT_BASE_COMMANDS = Set.of("list-sources");

    @Inject
    BeanManager beanManager;

    @Test
    void productionCommandSetMatchesMarkedIndex() {
        Set<String> inCode = productionCommandNames();
        Set<String> inDoc = parseMarkedIndex(COMMANDS_MD);

        assertEquals(inDoc, inCode, () -> diffMessage(inCode, inDoc));
    }

    /**
     * The SECOND parity axis (M1-646): every dispatchable production
     * {@link CommandHandler} must also carry a {@code /help} catalogue entry,
     * so no command is reachable at runtime yet undocumented by the bot itself.
     *
     * <p>{@link #productionCommandSetMatchesMarkedIndex} compares a different
     * pair — handlers against the SPEC index — which is why it stayed green
     * while {@code /pending} and {@code /recover-pool} were indexed and
     * dispatchable but absent from {@code HelpCommandHandler.CATALOGUE}.
     *
     * <p>Asset commands need no exclusion filter on either side: they are
     * dispatched via {@code AssetHandler}/{@code AssetRegistry} rather than as
     * {@link CommandHandler} beans, and are deliberately outside the catalogue
     * because the enabled-asset set is operator-driven, not static
     * (docs/spec/commands.md §Command catalogue, "Asset commands ... are
     * deliberately not in the index"). {@code HelpCommandHandler} appends them
     * to the help listing dynamically at render time.
     */
    @Test
    void everyCommandHandlerHasAHelpCatalogueEntry() throws Exception {
        Set<String> inCode = productionCommandNames();
        Set<String> inCatalogue = helpCatalogueCommandNames();

        assertEquals(inCatalogue, inCode, () -> catalogueDiffMessage(inCode, inCatalogue));
    }

    /**
     * The THIRD parity axis (M1-651): every base command in the "Bot-admin
     * only:" bullet of the spec's closed privileged-tier list
     * (docs/spec/commands.md §Permission model) must carry
     * {@code HelpTier.BOT_ADMIN} in {@code HelpCommandHandler.CATALOGUE}.
     *
     * <p>{@link #everyCommandHandlerHasAHelpCatalogueEntry} constrains the
     * NAME axis only — it reads {@code command()} and never {@code tier()}.
     * That makes a bot-admin command typed {@code HelpTier.USER} fully green:
     * name parity passes, spec-index parity passes, and any registered
     * post-probation non-admin then reads its short line via {@code /help} and
     * its full argument syntax via {@code /help <cmd>}. Execution still
     * requires {@code is_admin=true}, so the exposure is disclosure and recon,
     * not privilege escalation — but M1-646 changed the direction of failure:
     * a forgotten admin command used to fail SAFE (merely undocumented), and
     * now CI demands a CATALOGUE entry while saying nothing about which tier
     * to pick. This guard supplies the missing half.
     *
     * <p><b>Known limitation — do not mistake this for a structural
     * property.</b> The guard closes the case where a command is correctly
     * declared privileged in the spec but slips on the enum. It does NOT close
     * the case where a new privileged command is never declared privileged
     * anywhere: such a command is absent from the spec bullet, so there is
     * nothing to compare it against — and it would equally escape
     * {@code LlmOutputSanitizer.CLOSED_LIST}. Closing that would require
     * deriving the tier from each handler's own authorization code, which is a
     * much larger problem than this cross-check.
     */
    @Test
    void botAdminSpecCommandsCarryBotAdminHelpTier() throws Exception {
        assertEquals(Set.of("list-sources"), TIER_EXEMPT_BASE_COMMANDS,
                "The tier exemption set is meant to hold exactly /list-sources (flag-as-identity). "
                + "Widening it is a deliberate decision that must be reviewed, not a silent edit.");

        Set<String> specBotAdmin = parseBotAdminBaseCommands(COMMANDS_MD);
        // Vacuity guard: a parser that silently matched nothing would make every
        // assertion below trivially true. The exact count is deliberately NOT
        // pinned — a spec amendment that adds a bot-admin command must be covered
        // by this guard automatically, not require editing the test first.
        assertFalse(specBotAdmin.isEmpty(), () ->
                "Parsed no base commands from the '" + BOT_ADMIN_BULLET_LABEL + "' bullet of "
                + COMMANDS_MD.toAbsolutePath() + " — the spec's closed-list formatting changed "
                + "and this parser needs updating (a spec restructure must not silently "
                + "disable the guard).");

        Map<String, String> catalogueTiers = helpCatalogueTiers();

        Set<String> missing = specCommandsMissingFromCatalogue(specBotAdmin, catalogueTiers);
        assertTrue(missing.isEmpty(), () ->
                "Spec lists these as bot-admin commands but HelpCommandHandler.CATALOGUE has no "
                + "entry for them (ghost token — either the spec names a command that does not "
                + "exist, or the catalogue is missing it): " + missing);

        Set<String> misTiered = misTieredBotAdminCommands(specBotAdmin, catalogueTiers);
        assertTrue(misTiered.isEmpty(), () ->
                "These commands are bot-admin per docs/spec/commands.md §Permission model but "
                + "their CATALOGUE entry does not carry HelpTier." + BOT_ADMIN_TIER
                + ", so /help advertises them to non-admins: " + misTiered
                + " (actual tiers: " + misTiered.stream().map(c -> c + "=" + catalogueTiers.get(c)).toList()
                + "). A wrong tier is a disclosure finding, not a lint nit. EITHER side may be "
                + "the wrong one: if the command really is bot-admin, fix the CATALOGUE entry; "
                + "if it is not, the spec's closed list is wrong and needs an amendment. Do not "
                + "widen TIER_EXEMPT_BASE_COMMANDS to silence this.");
    }

    /**
     * The group-admin half of the same closed list (M1-651 redteam finding 2).
     * The threat model treats both privileged tiers as load-bearing — the LLM
     * output sanitizer's match set covers both — so guarding only the bot-admin
     * bullet would leave the identical mis-tier slip uncaught on the other half:
     * a new group-admin command typed {@code HelpTier.USER} is advertised, with
     * its full usage block, to every plain member of an approved group.
     *
     * <p>Unlike the bot-admin bullet this one is not single-tier, which is why
     * the original ticket deferred it. The mapping is nonetheless determinate,
     * because the spec's own "in groups" qualifier is precisely the distinction
     * {@code HelpTier} draws between {@code USER_OR_GROUP_ADMIN} and
     * {@code GROUP_ADMIN} — see {@link #parseGroupAdminExpectedTiers}.
     */
    @Test
    void groupAdminSpecCommandsCarryGroupTier() throws Exception {
        Map<String, String> expectedTiers = parseGroupAdminExpectedTiers(COMMANDS_MD);
        // Same vacuity guard as the bot-admin axis, and same reason for not
        // pinning a count: a spec amendment must be covered automatically.
        assertFalse(expectedTiers.isEmpty(), () ->
                "Parsed no base commands from the '" + GROUP_ADMIN_BULLET_LABEL + "' bullet of "
                + COMMANDS_MD.toAbsolutePath() + " — the spec's closed-list formatting changed "
                + "and this parser needs updating.");
        // Both tiers must actually occur, otherwise a qualifier-matching bug
        // that collapsed every entry onto one tier would pass unnoticed.
        assertEquals(Set.of(GROUP_ADMIN_TIER, USER_OR_GROUP_ADMIN_TIER),
                new TreeSet<>(expectedTiers.values()),
                () -> "The group-admin bullet must still yield BOTH tiers via the 'in groups' "
                      + "qualifier; got: " + expectedTiers);

        Map<String, String> catalogueTiers = helpCatalogueTiers();

        Set<String> missing = specCommandsMissingFromCatalogue(expectedTiers.keySet(), catalogueTiers);
        assertTrue(missing.isEmpty(), () ->
                "Spec lists these as group-admin-tier commands but HelpCommandHandler.CATALOGUE "
                + "has no entry for them (ghost token): " + missing);

        Set<String> misTiered = misTieredGroupAdminCommands(expectedTiers, catalogueTiers);
        assertTrue(misTiered.isEmpty(), () ->
                "These commands are group-admin-tier per docs/spec/commands.md §Permission model "
                + "but the CATALOGUE tier disagrees with the tier the spec's \"in groups\" "
                + "qualifier designates: " + misTiered
                + " (expected vs actual: " + misTiered.stream()
                        .map(c -> c + " expected=" + expectedTiers.get(c) + " actual=" + catalogueTiers.get(c))
                        .toList() + "). EITHER side may be the wrong one. If the CATALOGUE entry "
                + "is right, the spec's qualifier is wrong — adding or removing \"in groups\" is a "
                + "TIER CHANGE and a spec amendment (docs/spec/commands.md §Permission model), not "
                + "a copy-edit. If the spec is right, fix the CATALOGUE entry. Mislabelling a "
                + "group-only command as dual advertises it, with full argument syntax, to every "
                + "non-probation user in DM.");
    }

    /**
     * Spec group-admin-tier base commands whose {@code CATALOGUE} entry carries
     * a tier other than the one the spec's "in groups" qualifier implies.
     * Commands absent from the catalogue are left to
     * {@link #specCommandsMissingFromCatalogue}, as on the bot-admin axis.
     */
    static Set<String> misTieredGroupAdminCommands(Map<String, String> expectedTiers,
                                                   Map<String, String> catalogueTiers) {
        Set<String> violations = new TreeSet<>();
        for (Map.Entry<String, String> expected : expectedTiers.entrySet()) {
            String actual = catalogueTiers.get(expected.getKey());
            if (actual != null && !expected.getValue().equals(actual)) {
                violations.add(expected.getKey());
            }
        }
        return violations;
    }

    /**
     * Non-vacuity proof for the guard above: run the same comparison helper
     * over a SYNTHETIC catalogue in which one bot-admin command is mis-tiered,
     * and confirm it is reported. Without this, a helper that returned an empty
     * set unconditionally would leave
     * {@link #botAdminSpecCommandsCarryBotAdminHelpTier} passing forever and
     * nobody would know.
     */
    @Test
    void tierGuardRejectsAMisTieredCommand() {
        Set<String> specBotAdmin = Set.of("ban", "grant-admin", "list-sources");
        Map<String, String> synthetic = Map.of(
                "ban", "USER",                  // the planted defect
                "grant-admin", BOT_ADMIN_TIER,
                "list-sources", "USER");        // exempt: must NOT be reported

        assertEquals(Set.of("ban"), misTieredBotAdminCommands(specBotAdmin, synthetic),
                "the helper must report a bot-admin command tiered USER, and must not report "
                + "the documented /list-sources exemption");

        // Same proof for the group-admin axis, where "correct" is per-command
        // rather than one constant tier: /digest is planted USER against an
        // expected GROUP_ADMIN, while the correctly-tiered dual command is not
        // reported. This also proves the two tiers are not treated as
        // interchangeable.
        Map<String, String> expectedGroupTiers = Map.of(
                "digest", GROUP_ADMIN_TIER,
                "add-source", USER_OR_GROUP_ADMIN_TIER);
        Map<String, String> syntheticGroup = Map.of(
                "digest", "USER",                          // the planted defect
                "add-source", USER_OR_GROUP_ADMIN_TIER);

        assertEquals(Set.of("digest"), misTieredGroupAdminCommands(expectedGroupTiers, syntheticGroup),
                "the helper must report a group-admin command tiered USER and leave a correctly "
                + "tiered dual command alone");

        // A dual command mistyped as the STRICTER group-only tier is still a
        // mismatch: the two group tiers differ in DM visibility, so silently
        // accepting either would half-blind the guard.
        assertEquals(Set.of("add-source"),
                misTieredGroupAdminCommands(
                        Map.of("add-source", USER_OR_GROUP_ADMIN_TIER),
                        Map.of("add-source", GROUP_ADMIN_TIER)),
                "USER_OR_GROUP_ADMIN and GROUP_ADMIN must not be treated as interchangeable");
    }

    /**
     * Spec bot-admin base commands whose CATALOGUE entry carries a tier other
     * than {@code BOT_ADMIN}, excluding {@link #TIER_EXEMPT_BASE_COMMANDS}.
     * Commands absent from the catalogue are not reported here — that is the
     * distinct ghost-token failure {@link #specCommandsMissingFromCatalogue}
     * names, and keeping the two separate keeps each failure message actionable.
     */
    static Set<String> misTieredBotAdminCommands(Set<String> specBotAdminCommands,
                                                 Map<String, String> catalogueTiers) {
        Set<String> violations = new TreeSet<>();
        for (String command : specBotAdminCommands) {
            if (TIER_EXEMPT_BASE_COMMANDS.contains(command)) {
                continue;
            }
            String tier = catalogueTiers.get(command);
            if (tier != null && !BOT_ADMIN_TIER.equals(tier)) {
                violations.add(command);
            }
        }
        return violations;
    }

    /** Spec bot-admin base commands with no {@code CATALOGUE} entry at all. */
    static Set<String> specCommandsMissingFromCatalogue(Set<String> specBotAdminCommands,
                                                        Map<String, String> catalogueTiers) {
        Set<String> missing = new TreeSet<>(specBotAdminCommands);
        missing.removeAll(catalogueTiers.keySet());
        return missing;
    }

    /**
     * Base commands named by the "Bot-admin only:" bullet of the spec's closed
     * privileged-tier list, normalized to the key {@code CATALOGUE} uses.
     *
     * <p>Several of the bullet's tokens are sub-verb or flag forms of one
     * dispatchable command ({@code /invite create}, {@code /quarantine
     * approve}, {@code /list-sources --all}), so each token is reduced to its
     * first word: the bullet's 26 tokens yield 19 base commands.
     *
     * <p>Deliberately NOT shared with
     * {@code LlmOutputSanitizerTest.parseSpecClosedList}. That parser must keep
     * BOTH bullets with sub-verbs and flags verbatim — they are the strings the
     * sanitizer matches on — which is the opposite normalization from the one
     * this guard needs; one parser cannot serve both shapes without a mode flag.
     */
    private static Set<String> parseBotAdminBaseCommands(Path commandsMd) {
        Set<String> baseCommands = new TreeSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(
                closedListRegion(commandsMd, BOT_ADMIN_BULLET_LABEL, GROUP_ADMIN_BULLET_LABEL));
        while (matcher.find()) {
            baseCommands.add(baseCommandOf(matcher.group(1)));
        }
        return baseCommands;
    }

    /**
     * Base command -> expected {@code HelpTier} name, derived from the
     * "Group-admin (or bot admin acting in the group):" bullet of the same
     * closed list.
     *
     * <p>The bullet's entries map to TWO tiers, and the spec DESIGNATES its own
     * qualifier as the discriminator: a token qualified "in groups" names a
     * dual command any user may invoke in DM but only a group admin may invoke
     * in a group — {@code USER_OR_GROUP_ADMIN} — while a bare token names a
     * group-only command never offered in DM — {@code GROUP_ADMIN}. That is
     * exactly the distinction {@code HelpTier}'s own javadoc draws. Today it
     * yields 6 qualified and 2 bare, matching {@code CATALOGUE} exactly.
     *
     * <p>The designation is load-bearing and lives in the spec, not here:
     * docs/spec/commands.md §Permission model states that the qualifier is
     * tier-bearing and that adding or removing it is a tier change requiring a
     * spec amendment. Without that sentence this parser would be inferring an
     * authorization contract from incidental house style, and a future editor
     * could flip a command's DM visibility with what looked like a copy-edit.
     * (M1-651 redteam round-2 finding A.)
     */
    private static Map<String, String> parseGroupAdminExpectedTiers(Path commandsMd) {
        Map<String, String> expected = new TreeMap<>();
        Matcher matcher = QUALIFIED_TOKEN_PATTERN.matcher(
                closedListRegion(commandsMd, GROUP_ADMIN_BULLET_LABEL, CLOSED_LIST_END_MARKER));
        while (matcher.find()) {
            boolean inGroupsQualified = matcher.group(2) != null;
            expected.put(baseCommandOf(matcher.group(1)),
                    inGroupsQualified ? USER_OR_GROUP_ADMIN_TIER : GROUP_ADMIN_TIER);
        }
        return expected;
    }

    /** {@code "/invite create"} -> {@code "invite"}; {@code "/list-sources --all"} -> {@code "list-sources"}. */
    private static String baseCommandOf(String specToken) {
        return specToken.substring(1).split("\\s+")[0];
    }

    /**
     * The closed-list region running from {@code startLabel} up to (not
     * including) {@code endLabel}, joined into one string so a token and any
     * trailing qualifier stay adjacent even when the spec wraps them across
     * lines.
     *
     * <p><b>Why the region is bounded by explicit STRUCTURAL anchors rather
     * than by "the first blank line / next list item / next heading".</b> That
     * weaker rule made the guard silently shrinkable, which is the one failure
     * mode a closed-list guard must not have (docs/spec/commands.md §Permission
     * model: the closed set "cannot silently shrink across versions"): a spec
     * editor reformatting the bullet for readability — inserting a blank line,
     * or promoting the {@code /invite} family to a nested sub-bullet — truncated
     * the parsed set to a non-empty SUBSET, and every assertion downstream then
     * passed over that subset, leaving a later mis-tiered command outside the
     * window unguarded with no signal. (M1-651 redteam finding 1.)
     *
     * <p><b>What this method does and does not guarantee.</b> It guarantees
     * that the region's INTERIOR formatting is irrelevant — blank lines, nested
     * sub-bullets and headings inside a bullet no longer truncate — and that
     * every structural deviation is LOUD: a missing anchor fails, and so does a
     * second list item inside the region. It does NOT guarantee that any
     * conceivable spec restructure is detected; it guarantees that the ones it
     * cannot parse fail visibly instead of yielding a quiet subset. Matching
     * anchors at the START of a stripped line (rather than anywhere in it) is
     * what closes the remaining quiet case: a prose mention of the next
     * region's label — "(distinct from the **Group-admin** tier below)" — is an
     * indented continuation line, so it no longer ends the region.
     * (M1-651 redteam round-2 finding B.)
     *
     * <p>File I/O is a system boundary, so the explicit file-exists and
     * label-present checks are correct here, not defensive-code drift.
     */
    private static String closedListRegion(Path commandsMd, String startLabel, String endLabel) {
        assertTrue(Files.isRegularFile(commandsMd), () ->
                "Command spec not found at " + commandsMd.toAbsolutePath()
                + " (surefire working dir = " + Path.of("").toAbsolutePath() + ").");

        List<String> lines;
        try {
            lines = Files.readAllLines(commandsMd);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + commandsMd.toAbsolutePath(), e);
        }

        int begin = indexOfLineStartingWith(lines, startLabel, 0);
        assertTrue(begin >= 0, () ->
                "Region start '" + startLabel + "' not found at the start of any line in "
                + commandsMd.toAbsolutePath()
                + "; the closed privileged-tier list moved or was renamed.");

        int end = indexOfLineStartingWith(lines, endLabel, begin + 1);
        assertTrue(end > begin, () ->
                "Region end '" + endLabel + "' not found after '" + startLabel + "' in "
                + commandsMd.toAbsolutePath() + ". The closed privileged-tier list was "
                + "restructured; this guard refuses to parse an unbounded region because a "
                + "truncated parse would silently shrink the guarded command set.");

        // A second list item inside the region means the bullet was split or a
        // sub-bullet was introduced, so the region no longer corresponds to one
        // tier. Failing here is deliberate: the alternative is parsing a set
        // that LOOKS complete. The fix is to update this parser, never to
        // accommodate the truncation silently.
        for (int i = begin + 1; i < end; i++) {
            String line = lines.get(i);
            int lineNumber = i + 1;
            assertFalse(line.strip().startsWith("- "), () ->
                    "Unexpected list item inside the '" + startLabel + "' region at "
                    + commandsMd.toAbsolutePath() + ":" + lineNumber + " -> '" + line.strip()
                    + "'. The closed privileged-tier bullet was split or gained a sub-bullet; "
                    + "this guard fails loudly rather than silently guarding a subset of the "
                    + "commands. Update the parser to match the new structure.");
        }

        return String.join(" ", lines.subList(begin, end));
    }

    /** Index of the first line at or after {@code from} whose stripped form starts with {@code prefix}, or -1. */
    private static int indexOfLineStartingWith(List<String> lines, String prefix, int from) {
        for (int i = from; i < lines.size(); i++) {
            if (lines.get(i).strip().startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@code HelpCommandHandler.CATALOGUE} as command -> {@code HelpTier} enum
     * NAME. Read reflectively for the same reason
     * {@link #helpCatalogueCommandNames()} is: the catalogue, the
     * {@code CommandHelp} record and the {@code HelpTier} enum are all
     * package-private to the messaging package, and a test does not justify
     * widening production visibility. The tier is carried as its {@code name()}
     * string because the enum type itself is not referenceable from here.
     */
    private static Map<String, String> helpCatalogueTiers() throws Exception {
        Class<?> helpClass = Class.forName("app.zcat.infochat.provider.messaging.HelpCommandHandler");
        Field catalogueField = helpClass.getDeclaredField("CATALOGUE");
        catalogueField.setAccessible(true);
        List<?> catalogue = (List<?>) catalogueField.get(null);

        Map<String, String> tiers = new TreeMap<>();
        for (Object entry : catalogue) {
            Method commandAccessor = entry.getClass().getDeclaredMethod("command");
            commandAccessor.setAccessible(true);
            Method tierAccessor = entry.getClass().getDeclaredMethod("tier");
            tierAccessor.setAccessible(true);
            tiers.put((String) commandAccessor.invoke(entry), ((Enum<?>) tierAccessor.invoke(entry)).name());
        }
        return tiers;
    }

    /**
     * The command names {@code HelpCommandHandler.CATALOGUE} documents. The
     * catalogue is a package-private static in the messaging package, so it is
     * read reflectively — the same access this module's
     * {@code ProbationCommandListConsistencyTest} uses, rather than widening
     * the production surface for a test.
     */
    private static Set<String> helpCatalogueCommandNames() throws Exception {
        Class<?> helpClass = Class.forName("app.zcat.infochat.provider.messaging.HelpCommandHandler");
        Field catalogueField = helpClass.getDeclaredField("CATALOGUE");
        catalogueField.setAccessible(true);
        List<?> catalogue = (List<?>) catalogueField.get(null);

        Set<String> names = new TreeSet<>();
        for (Object entry : catalogue) {
            Method commandAccessor = entry.getClass().getDeclaredMethod("command");
            commandAccessor.setAccessible(true);
            names.add((String) commandAccessor.invoke(entry));
        }
        return names;
    }

    /** Command names of the production (non-test) {@link CommandHandler} beans, from the real CDI bean graph. */
    private Set<String> productionCommandNames() {
        Set<String> names = new TreeSet<>();
        for (Bean<?> bean : beanManager.getBeans(CommandHandler.class)) {
            if (!isProductionClass(bean.getBeanClass())) {
                continue;
            }
            CommandHandler handler = (CommandHandler) beanManager.getReference(
                    bean, CommandHandler.class, beanManager.createCreationalContext(bean));
            names.add(handler.name());
        }
        return names;
    }

    /**
     * A bean's declared class is "production" unless it loads from the
     * test-compilation output ({@code target/test-classes}). A null CodeSource
     * (no on-disk origin we can attribute) is treated as production: the
     * shipped handlers all carry a directory CodeSource, so the test beans this
     * guard targets are exactly the ones with a {@code /test-classes} origin.
     */
    private static boolean isProductionClass(Class<?> beanClass) {
        CodeSource codeSource = beanClass.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            return true;
        }
        return !codeSource.getLocation().getPath().contains(TEST_CLASSES_MARKER);
    }

    /**
     * Parse the marker-delimited canonical command index, returning the
     * command names (leading slash stripped) it lists, reading EXCLUSIVELY
     * between the markers.
     *
     * <p>File I/O is a system boundary, so the explicit file-exists and
     * marker-present checks here are correct, not defensive-code drift: a
     * missing file or absent markers is a spec/environment error this test
     * must report with an actionable message rather than an opaque NPE.
     */
    private static Set<String> parseMarkedIndex(Path commandsMd) {
        assertTrue(Files.isRegularFile(commandsMd), () ->
                "Command catalogue spec not found at " + commandsMd.toAbsolutePath()
                + " (surefire working dir = " + Path.of("").toAbsolutePath()
                + "); the parity test resolves it relative to the provider module dir.");

        List<String> lines;
        try {
            lines = Files.readAllLines(commandsMd);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + commandsMd.toAbsolutePath(), e);
        }

        int begin = lines.indexOf(BEGIN_MARKER);
        int end = lines.indexOf(END_MARKER);
        assertTrue(begin >= 0 && end > begin, () ->
                "Command index markers missing or malformed in " + commandsMd.toAbsolutePath()
                + ": expected '" + BEGIN_MARKER + "' followed by '" + END_MARKER + "'.");

        Set<String> names = new TreeSet<>();
        for (String raw : lines.subList(begin + 1, end)) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            // Each non-blank line in the marked region must be a single /name
            // token; a stray prose line here is a spec error worth a build
            // failure, not a silent skip.
            assertTrue(line.startsWith("/") && line.indexOf(' ') < 0, () ->
                    "Malformed command-index line (expected a single '/name' token) in "
                    + commandsMd.toAbsolutePath() + ": '" + raw + "'");
            names.add(line.substring(1));
        }
        return names;
    }

    private static String diffMessage(Set<String> inCode, Set<String> inDoc) {
        Set<String> codeOnly = new TreeSet<>(inCode);
        codeOnly.removeAll(inDoc);
        Set<String> docOnly = new TreeSet<>(inDoc);
        docOnly.removeAll(inCode);
        return "Command catalogue parity mismatch between the production CommandHandler "
               + "set and the command index in docs/spec/commands.md:\n"
               + "  in code but NOT indexed (you shipped a command — add it to the index): "
               + codeOnly + "\n"
               + "  indexed but NOT in code (the catalogue lists a ghost command): "
               + docOnly;
    }

    private static String catalogueDiffMessage(Set<String> inCode, Set<String> inCatalogue) {
        Set<String> codeOnly = new TreeSet<>(inCode);
        codeOnly.removeAll(inCatalogue);
        Set<String> catalogueOnly = new TreeSet<>(inCatalogue);
        catalogueOnly.removeAll(inCode);
        return "Help-catalogue coverage mismatch between the production CommandHandler "
               + "set and HelpCommandHandler.CATALOGUE:\n"
               + "  dispatchable but NOT in the help catalogue (the bot answers a command "
               + "it never documents — add a CATALOGUE entry plus its short/usage/examples "
               + "bundle keys in en and cs): " + codeOnly + "\n"
               + "  in the help catalogue but NOT dispatchable (help advertises a command "
               + "that no handler serves): " + catalogueOnly;
    }
}
