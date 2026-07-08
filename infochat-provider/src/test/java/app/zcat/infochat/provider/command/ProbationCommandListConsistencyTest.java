package app.zcat.infochat.provider.command;

import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.command.asset.AssetRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the M1-590 invariant: the three probation-command surfaces — the
 * registration welcome ({@code reply.welcome.dm_fresh}), the probation
 * rejection ({@code error.probation.blocked}), and {@code /help}'s
 * probation-visible listing — all enumerate the SAME set of slash
 * commands, derived from the one canonical source
 * {@link CommandPermissions#probationAllowedCommandNames()} (the static
 * allow-list plus the operator-enabled asset family). Consistency is
 * asserted over the command-name SET, not the display format: welcome and
 * rejection render one inline comma list, {@code /help} renders one line
 * per command — both are fine, the underlying set must match.
 *
 * <p><b>Red-before / green-after.</b> Before this ticket the welcome bundle
 * value hard-coded a list that omitted {@code /get-sources} and
 * {@code /stop}, and the rejection value hard-coded one that omitted
 * {@code /zcash} and {@code /monero} — the two drifted in opposite
 * directions, and {@code /help} (already canonical-driven) agreed with
 * neither. Both replies now carry a {@code MessageFormat} placeholder for
 * the canonical list, so this test is red against the old strings and green
 * after. The rejection omission is the live bug (SimpleX DM, 2026-07-08): a
 * probation user was told asset commands were unavailable while {@code
 * /zcash} in fact ran, so the enabled-asset case is exercised explicitly.
 *
 * <p>Plain JUnit (no Quarkus boot): the real {@link BundleLoader} is built
 * by hand and its package-private {@code load()} invoked via reflection
 * (mirroring {@code HelpCommandHandlerTest}) so the assertions run against
 * the production {@code en}/{@code cs} bundle values. Command names are not
 * translated, so every assertion holds in both languages.
 */
class ProbationCommandListConsistencyTest {

    /**
     * The operator-enabled asset family for the fixture — includes the
     * exact two commands ({@code /zcash}, {@code /monero}) the old
     * rejection string dropped, so the asset-family omission is genuinely
     * caught. Single source shared by the AssetRegistry stub and the
     * {@code /help} reconstruction below.
     */
    private static final Set<String> ENABLED_ASSET_NAMES =
            new LinkedHashSet<>(List.of("zcash", "monero"));

    private BundleLoader bundleLoader;
    private CommandPermissions commandPermissions;

    @BeforeEach
    void setUp() throws Exception {
        bundleLoader = new BundleLoader();
        Method load = BundleLoader.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(bundleLoader);

        // AssetRegistry with /zcash and /monero enabled. CommandPermissions
        // reads only the enabled NAMES through the oracle, so overriding the
        // two enabled-asset accessors is sufficient — the AssetEntry list
        // (getEnabledAssets) is never touched on this path.
        AssetRegistry enabledAssets = new AssetRegistry() {
            @Override
            public Set<String> getEnabledAssetNames() {
                return new LinkedHashSet<>(ENABLED_ASSET_NAMES);
            }

            @Override
            public boolean containsEnabledAsset(String name) {
                return ENABLED_ASSET_NAMES.contains(name);
            }
        };
        commandPermissions = new CommandPermissions(new AssetCommandFamilyOracle(enabledAssets));
    }

    @Test
    void rejectionListsEnabledAssetCommands() {
        for (String lang : List.of("en", "cs")) {
            String rejection = renderRejection(lang);
            // The live bug: these two were absent from the hard-coded string.
            assertTrue(rejection.contains("/zcash"),
                    lang + " probation rejection must list /zcash when the asset is enabled; got: " + rejection);
            assertTrue(rejection.contains("/monero"),
                    lang + " probation rejection must list /monero when the asset is enabled; got: " + rejection);
        }
    }

    @Test
    void welcomeListsCommandsTheOldStringOmitted() {
        for (String lang : List.of("en", "cs")) {
            String welcome = renderWelcome(lang);
            // The old welcome string omitted both of these.
            assertTrue(welcome.contains("/get-sources"),
                    lang + " welcome must list /get-sources; got: " + welcome);
            assertTrue(welcome.contains("/stop"),
                    lang + " welcome must list /stop; got: " + welcome);
        }
    }

    @Test
    void welcomeRejectionAndHelpAllRenderTheCanonicalSet() throws Exception {
        String canonicalList = commandPermissions.renderProbationCommandList();

        // Welcome and rejection embed the canonical list VERBATIM as their
        // sole placeholder arg, so containing it proves each surface's
        // command set equals canonical (order included) — a hand edit that
        // re-hardcodes a list drops the placeholder and fails here.
        for (String lang : List.of("en", "cs")) {
            assertTrue(renderWelcome(lang).contains(canonicalList),
                    lang + " welcome must render the canonical probation command list verbatim");
            assertTrue(renderRejection(lang).contains(canonicalList),
                    lang + " rejection must render the canonical probation command list verbatim");
        }

        // /help derives its probation listing from the same source: the
        // CATALOGUE entries whose command passes allowedDuringProbation, plus
        // the enabled asset names it appends. Reconstruct that set from
        // HelpCommandHandler's actual CATALOGUE (read reflectively — the test
        // lives in the command package, CATALOGUE in messaging) and assert it
        // equals canonical. This also catches a canonical command that is
        // missing from CATALOGUE, which /help would silently drop.
        Set<String> canonicalNames = new HashSet<>(commandPermissions.probationAllowedCommandNames());
        assertEquals(canonicalNames, helpProbationVisibleSet(),
                "/help probation-visible command set must equal the canonical probation set");
    }

    private String renderWelcome(String lang) {
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_WELCOME_DM_FRESH, lang),
                commandPermissions.renderProbationCommandList());
    }

    private String renderRejection(String lang) {
        // {0} = time-until-unlock (any value — irrelevant to this test);
        // {1} = the canonical command list.
        return MessageFormat.format(
                bundleLoader.get(BundleKeys.ERROR_PROBATION_BLOCKED, lang),
                "~23h",
                commandPermissions.renderProbationCommandList());
    }

    /**
     * Reconstructs {@code /help}'s probation-visible command-name set the
     * way {@code HelpCommandHandler.handle} does for a probation caller:
     * every CATALOGUE command that passes {@code allowedDuringProbation},
     * then the enabled asset names it appends. CATALOGUE is a
     * package-private static in the messaging package, so it is read
     * reflectively — the alternative (rendering real {@code /help}) needs a
     * DataSource-backed tier lookup this handler-tier test deliberately avoids.
     */
    private Set<String> helpProbationVisibleSet() throws Exception {
        Class<?> helpClass = Class.forName("app.zcat.infochat.provider.messaging.HelpCommandHandler");
        Field catalogueField = helpClass.getDeclaredField("CATALOGUE");
        catalogueField.setAccessible(true);
        List<?> catalogue = (List<?>) catalogueField.get(null);

        Set<String> visible = new HashSet<>();
        for (Object entry : catalogue) {
            Method commandAccessor = entry.getClass().getDeclaredMethod("command");
            commandAccessor.setAccessible(true);
            String command = (String) commandAccessor.invoke(entry);
            if (commandPermissions.allowedDuringProbation(command)) {
                visible.add(command);
            }
        }
        visible.addAll(ENABLED_ASSET_NAMES);
        return visible;
    }
}
