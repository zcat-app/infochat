package app.zcat.infochat.provider.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Build-time guard against configuration-key drift in the docs (M1-708): every
 * {@code infochat.*} key named in {@code docs/spec/**}, {@code docs/design/**}
 * or a root guide must either exist in the real key set — the
 * {@code @ConfigProperty} / config-expression sites in the modules' main
 * sources plus every {@code src/main/resources/application.properties} — or
 * carry an entry in {@code documented-config-key-exemptions.txt} saying why it
 * legitimately has no literal.
 *
 * <p>The configuration surface is the one doc-vs-code contract in this repo
 * that was never build-gated, which is why ~15 phantom keys accumulated across
 * three design notes and three root guides before the 2026-07-27 audit went
 * looking. {@code CommandCatalogueParityTest} is the pattern this copies,
 * including the working-directory handling: surefire runs with the module
 * directory as the working directory, so the repo root is one level up.
 *
 * <p><b>Two ways to answer a failure, and only one of them is ever right for a
 * given key.</b> If the doc merely mis-NAMES a key that exists, fix the name in
 * the doc. If the doc states a REQUIREMENT the code has not built, the
 * requirement stays and gets an exemption citing the gap that owns it —
 * deleting a documented control to green a test is a test-integrity violation,
 * not a fix.
 *
 * <p><b>The vacuous pass is the real hazard.</b> An extraction that silently
 * matches nothing finds no drift and reads as coverage forever, so the checker
 * is exercised against synthetic input by
 * {@link #checkerReportsADocumentedKeyThatIsNeitherRealNorExempt}, the live
 * extractions carry floor assertions, and an exemption may not be an unbounded
 * wildcard ({@link #exemptionEntriesCannotBeUnboundedWildcards}).
 */
class DocumentedConfigKeyParityTest {

    // Surefire runs with the module directory (infochat-provider) as the
    // working directory, so the repo root is one level up. Same trap, same
    // handling as CommandCatalogueParityTest (M1-527).
    private static final Path REPO_ROOT = Path.of("..");

    private static final String EXEMPTIONS_RESOURCE = "documented-config-key-exemptions.txt";

    /**
     * A doc-side key reference. The lookbehind is what keeps the check
     * false-positive-free: without it, a Java package name
     * ({@code app.zcat.infochat.core.schema}) and a clone URL
     * ({@code .../infochat.git}) both parse as config keys, and neither is one.
     * The trailing class excludes {@code .}, {@code *} and {@code -} so a
     * family reference written as {@code infochat.llm.breaker.*} or
     * {@code infochat.bootstrap.{sources,assets}-file} yields its prefix rather
     * than a malformed key; those prefixes are carried in the exemption list.
     */
    private static final Pattern DOC_KEY = Pattern.compile(
            "(?<![\\w./-])infochat\\.[a-z][a-zA-Z0-9._<>*-]*[a-zA-Z0-9>]");

    // Real-key sites. The quoted form covers @ConfigProperty(name = "...") and
    // the KEY constants those annotations reference; the braced form covers
    // config expressions such as @Scheduled(every = "{infochat.x.y}").
    private static final Pattern JAVA_QUOTED_KEY = Pattern.compile("\"(infochat\\.[a-zA-Z0-9._-]+)\"");
    private static final Pattern JAVA_CONFIG_EXPRESSION = Pattern.compile("\\{(infochat\\.[a-zA-Z0-9._-]+)");
    private static final Pattern PROPERTIES_KEY =
            Pattern.compile("^(?:%[a-zA-Z-]+\\.)?(infochat\\.[a-zA-Z0-9._-]+)");

    // Floors, not exact counts: they catch an extraction that has stopped
    // matching (a docs reorganisation, a regex edit) without pinning numbers
    // that every unrelated config or doc change would have to update. Live
    // values at authoring time were 186 real and 135 documented.
    private static final int MIN_REAL_KEYS = 50;
    private static final int MIN_DOCUMENTED_KEYS = 50;

    /** An exemption entry: the bounded glob it matches, and why the key is absent. */
    private record Exemption(String glob, String reason, Pattern compiled) {

        boolean matches(String key) {
            return compiled.matcher(key).matches();
        }
    }

    @Test
    void everyDocumentedConfigKeyExistsOrIsExempt() throws IOException {
        Set<String> realKeys = realKeys();
        Map<String, String> documentedKeys = documentedKeys();
        List<Exemption> exemptions = exemptions();

        assertTrue(realKeys.size() >= MIN_REAL_KEYS, () ->
                "Extracted only " + realKeys.size() + " real config keys from " + REPO_ROOT.toAbsolutePath()
                + " (working directory " + Path.of("").toAbsolutePath() + "). The extraction has stopped "
                + "matching — a checker that finds no real keys would flag every documented key, and one "
                + "that finds no documented keys would flag nothing at all.");
        assertTrue(documentedKeys.size() >= MIN_DOCUMENTED_KEYS, () ->
                "Extracted only " + documentedKeys.size() + " documented config keys from docs/spec, "
                + "docs/design and the root guides under " + REPO_ROOT.toAbsolutePath() + ". The doc-side "
                + "extraction has stopped matching, which would make this gate pass vacuously.");

        Map<String, String> drifted = drift(documentedKeys, realKeys, exemptions);

        assertTrue(drifted.isEmpty(), () ->
                "These documented `infochat.*` keys exist in no @ConfigProperty site, no config expression "
                + "and no application.properties, and carry no exemption:\n"
                + drifted.entrySet().stream()
                        .map(e -> "  " + e.getKey() + "  (" + e.getValue() + ")")
                        .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b)
                + "\n\nTwo remedies, and the key decides which one applies. If the doc mis-NAMES a key "
                + "that exists, fix the name in the doc. If the doc states a requirement the code has not "
                + "built, keep the requirement and add an entry to "
                + EXEMPTIONS_RESOURCE + " citing the gap that owns it. Deleting a documented control to "
                + "green this test is a test-integrity violation.");
    }

    /**
     * The anti-vacuity proof: the checker is driven with a synthetic document
     * whose three keys are one real, one exempt and one drifted, and must
     * report exactly the drifted one. A regex that matched nothing, a real-key
     * lookup that matched everything, and an exemption that swallowed the
     * unknown key all fail this.
     */
    @Test
    void checkerReportsADocumentedKeyThatIsNeitherRealNorExempt() {
        String syntheticDoc = """
                The chat model is `infochat.llm.chat.model` (real).
                Routing reads `infochat.llm.chat.provider` (exempt: dynamic per-task key).
                Operators tune `infochat.ghost.knob-that-never-shipped` (drifted).
                """;

        Map<String, String> documented = documentedKeysIn(syntheticDoc, "synthetic.md");
        assertEquals(
                Set.of("infochat.llm.chat.model",
                        "infochat.llm.chat.provider",
                        "infochat.ghost.knob-that-never-shipped"),
                documented.keySet(),
                "The doc-side extraction did not find the synthetic keys, so any parity result over real "
                + "docs would be meaningless.");

        Map<String, String> drifted = drift(
                documented,
                Set.of("infochat.llm.chat.model"),
                List.of(exemption("infochat.llm.*.provider | synthetic fixture reason")));

        assertEquals(Set.of("infochat.ghost.knob-that-never-shipped"), drifted.keySet(),
                "The checker must report the key that is neither real nor exempt, and only that key.");
    }

    /**
     * An exemption is a named survivor, never a blanket suppression: a pattern
     * must keep at least two literal segments, so no future entry can widen to
     * {@code infochat.*} and silently retire the gate. Both halves are asserted
     * — that the committed entries pass the bound, and that the bound actually
     * rejects the catch-all shapes.
     */
    @Test
    void exemptionEntriesCannotBeUnboundedWildcards() throws IOException {
        List<Exemption> exemptions = exemptions();
        assertFalse(exemptions.isEmpty(), "The exemption list parsed to nothing — "
                + EXEMPTIONS_RESOURCE + " is missing from the test classpath or its format changed.");

        for (Exemption exemption : exemptions) {
            assertTrue(isBounded(exemption.glob()), () ->
                    "Exemption `" + exemption.glob() + "` is an unbounded wildcard: an entry must keep at "
                    + "least two literal dot segments so it cannot disable the gate for a whole subtree.");
            assertFalse(exemption.reason().isBlank(), () ->
                    "Exemption `" + exemption.glob() + "` states no reason. The list is a ledger — an entry "
                    + "without a WHY cannot be retired when the reason expires.");
        }

        assertFalse(isBounded("infochat.*"), "The bound must reject the catch-all.");
        assertFalse(isBounded("infochat.*.*"), "The bound must reject a wildcard-only pattern.");
        assertFalse(isBounded("infochat"), "The bound must reject the bare namespace.");
        assertTrue(isBounded("infochat.llm.*.provider"), "The bound must admit a segment-scoped wildcard.");
        assertTrue(isBounded("infochat.actor_id"), "The bound must admit an exact key.");
    }

    // --- checker -----------------------------------------------------------

    /** Documented keys that are in neither the real key set nor the exemption list, mapped to their first occurrence. */
    private static Map<String, String> drift(Map<String, String> documentedKeys,
                                             Set<String> realKeys,
                                             List<Exemption> exemptions) {
        Map<String, String> drifted = new TreeMap<>();
        for (Map.Entry<String, String> documented : documentedKeys.entrySet()) {
            String key = documented.getKey();
            if (realKeys.contains(key)) {
                continue;
            }
            if (exemptions.stream().anyMatch(exemption -> exemption.matches(key))) {
                continue;
            }
            drifted.put(key, documented.getValue());
        }
        return drifted;
    }

    // --- real key set ------------------------------------------------------

    private static Set<String> realKeys() throws IOException {
        Set<String> keys = new TreeSet<>();
        for (Path module : modules()) {
            Path javaRoot = module.resolve("src/main/java");
            if (Files.isDirectory(javaRoot)) {
                try (Stream<Path> sources = Files.walk(javaRoot)) {
                    sources.filter(path -> path.getFileName().toString().endsWith(".java"))
                            .forEach(path -> {
                                String source = read(path);
                                collect(JAVA_QUOTED_KEY, source, keys);
                                collect(JAVA_CONFIG_EXPRESSION, source, keys);
                            });
                }
            }
            Path properties = module.resolve("src/main/resources/application.properties");
            if (Files.isRegularFile(properties)) {
                for (String line : Files.readAllLines(properties, StandardCharsets.UTF_8)) {
                    collect(PROPERTIES_KEY, line, keys);
                }
            }
        }
        return keys;
    }

    /**
     * Every {@code infochat-*} module, not only the two services: an
     * adapter-module constant such as {@code SimpleXConfig.BINARY_KEY} is as
     * real a key site as a service's own {@code @ConfigProperty}, and the docs
     * name those keys. This is the enumeration the ticket's census runs.
     */
    private static List<Path> modules() throws IOException {
        try (Stream<Path> children = Files.list(REPO_ROOT)) {
            return children.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("infochat-"))
                    .sorted()
                    .toList();
        }
    }

    private static void collect(Pattern pattern, String text, Set<String> keys) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            // A key written as `infochat.foo.` (trailing separator) or
            // `infochat.foo:` (config-expression default) names infochat.foo.
            keys.add(matcher.group(1).replaceAll("[.:]+$", ""));
        }
    }

    // --- documented key set ------------------------------------------------

    private static Map<String, String> documentedKeys() throws IOException {
        Map<String, String> keys = new TreeMap<>();
        for (Path doc : docFiles()) {
            // putIfAbsent, not putAll: the recorded occurrence is the FIRST one
            // across the whole corpus, which is what a reader chasing a failure
            // wants to open.
            documentedKeysIn(read(doc), REPO_ROOT.relativize(doc).toString())
                    .forEach(keys::putIfAbsent);
        }
        return keys;
    }

    /** Maps each key the text names to {@code <label>:<line>} of its first occurrence. */
    private static Map<String, String> documentedKeysIn(String text, String label) {
        Map<String, String> keys = new LinkedHashMap<>();
        String[] lines = text.split("\n", -1);
        for (int lineNumber = 1; lineNumber <= lines.length; lineNumber++) {
            Matcher matcher = DOC_KEY.matcher(lines[lineNumber - 1]);
            while (matcher.find()) {
                keys.putIfAbsent(matcher.group(), label + ":" + lineNumber);
            }
        }
        return keys;
    }

    /** {@code docs/spec} and {@code docs/design} recursively, plus the root guides. */
    private static List<Path> docFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String tree : List.of("docs/spec", "docs/design")) {
            try (Stream<Path> paths = Files.walk(REPO_ROOT.resolve(tree))) {
                paths.filter(path -> path.getFileName().toString().endsWith(".md")).sorted().forEach(files::add);
            }
        }
        try (Stream<Path> paths = Files.list(REPO_ROOT)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .forEach(files::add);
        }
        return files;
    }

    // --- exemptions --------------------------------------------------------

    private static List<Exemption> exemptions() throws IOException {
        List<Exemption> exemptions = new ArrayList<>();
        try (InputStream stream = DocumentedConfigKeyParityTest.class.getClassLoader()
                .getResourceAsStream(EXEMPTIONS_RESOURCE)) {
            if (stream == null) {
                throw new IOException(EXEMPTIONS_RESOURCE + " is not on the test classpath");
            }
            for (String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1)) {
                String entry = line.strip();
                if (entry.isEmpty() || entry.startsWith("#")) {
                    continue;
                }
                exemptions.add(exemption(entry));
            }
        }
        return exemptions;
    }

    private static Exemption exemption(String entry) {
        int separator = entry.indexOf('|');
        assertTrue(separator > 0, () ->
                "Malformed exemption entry `" + entry + "` — the format is `<pattern> | <reason>`.");
        String glob = entry.substring(0, separator).strip();
        String reason = entry.substring(separator + 1).strip();
        return new Exemption(glob, reason, compile(glob));
    }

    /** {@code *} matches one or more characters inside a single dot segment; everything else is literal. */
    private static Pattern compile(String glob) {
        StringBuilder regex = new StringBuilder();
        String[] literals = glob.split("\\*", -1);
        for (int i = 0; i < literals.length; i++) {
            if (i > 0) {
                regex.append("[^.]+");
            }
            if (!literals[i].isEmpty()) {
                regex.append(Pattern.quote(literals[i]));
            }
        }
        return Pattern.compile(regex.toString());
    }

    /**
     * A pattern is bounded when it names the namespace and keeps at least two
     * literal segments. That is what stops an exemption from degenerating into
     * {@code infochat.*}, which would exempt every key ever documented.
     */
    private static boolean isBounded(String glob) {
        String[] segments = glob.split("\\.", -1);
        if (segments.length < 2 || !"infochat".equals(segments[0])) {
            return false;
        }
        if (Arrays.stream(segments).anyMatch(String::isEmpty)) {
            return false;
        }
        return Arrays.stream(segments).filter(segment -> segment.indexOf('*') < 0).count() >= 2;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path.toAbsolutePath()
                    + " (working directory " + Path.of("").toAbsolutePath() + ")", e);
        }
    }
}
