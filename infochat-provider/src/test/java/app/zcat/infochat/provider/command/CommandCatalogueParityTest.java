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
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
