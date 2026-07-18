package app.zcat.infochat.core.testsupport;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermeticity ratchet (M1-650) for the configured LLM/embedding endpoints:
 * every {@code infochat.*base-url} that resolves under the {@code %test}
 * profile MUST be the unreachable sentinel {@code http://localhost:9} (port 9
 * is the discard port, so nothing can ever answer).
 *
 * <p>This makes executable a commitment {@code docs/spec/verification.md}
 * §Test layers only stated in prose — layer-3 ITs run against a fake LLM. The
 * M1-644 incident is what a missing check costs: the provider's
 * {@code EmbeddingProvider} was unstubbed, so the suite quietly embedded
 * against whatever daemon held {@code localhost:11434}. It stayed green for
 * weeks only because a prod ollama was usually up; when the operator stopped
 * it, 15 router-concurrency ITs went red. M1-644 fixed the *bean* half (a
 * {@code @Alternative} stub holds the CDI slot); this guard fixes the *config*
 * half, so hermeticity no longer depends on a bean registration nobody guards.
 *
 * <p><b>Fail-CLOSED, and deliberately without a baseline.</b> The sibling
 * {@link IntegrationTestNamingGuardTest} asserts its found-set is a SUBSET of a
 * checked-in baseline, which is fail-OPEN (a stale baseline entry is never
 * detected — its own javadoc concedes this). This guard needs no baseline: the
 * assertion is a property of each VALUE, not membership in a list, so a NEW
 * {@code infochat.*base-url} key added by a future ticket is covered the moment
 * it is written, with no guard edit.
 *
 * <p><b>The second arm matters as much as the first.</b> A key is a violation
 * not only when its {@code %test} line names a live endpoint, but also when it
 * has NO {@code %test} override at all and the unprofiled value leaks into the
 * test profile. That case was not hypothetical: the Collector's
 * {@code infochat.embeddings.base-url} was exactly this shape, and a lint that
 * inspected only literal {@code %test.} lines would have passed it.
 *
 * <p><b>Scope.</b> {@code infochat.*} keys only. {@code quarkus.*} datasource
 * and HTTP URLs are DevServices-managed with dynamic loopback ports — hermetic
 * by a different mechanism that a sentinel rule would break. Non-test profiles
 * are untouched: {@code %laptop}/{@code %vps}/{@code %pi}/{@code %dev} SHOULD
 * point at a real local ollama. Nor does the guard require any key to EXIST —
 * {@code %remote-llm} deliberately bakes no default base-url so an unrouted
 * task refuses boot rather than silently resolving (D56); only a key that
 * actually resolves under {@code %test} is constrained. A hardcoded URL literal
 * in a Java source (KrakenSnapshotSource) is structurally invisible to a
 * properties lint and is out of scope rather than falsely assured.
 *
 * <p>Plain JUnit, NOT a {@code @QuarkusTest}: it walks the on-disk resources of
 * every {@code infochat-*} module, so a single guard in {@code infochat-core}
 * sees modules that do not depend on core (the classpath could not). Repo-root
 * location mirrors {@link IntegrationTestNamingGuardTest}.
 */
class TestEndpointSentinelGuardTest {

    /**
     * The repo's established unreachable endpoint (also used by
     * {@code OpenAiCompatibleEmbeddingProviderTest}, {@code AnthropicProviderTest},
     * {@code OpenAiCompatibleProviderTest}, {@code HttpProviderSharedPipelineTest}).
     * Exact spelling: no trailing slash, no {@code /v1}.
     */
    private static final String SENTINEL = "http://localhost:9";

    private static final String TEST_PROFILE = "test";

    @Test
    void everyTestProfileBaseUrlIsTheUnreachableSentinel() throws IOException {
        Path repoRoot = locateRepoRoot();
        List<String> violations = new ArrayList<>();
        for (Path propertiesFile : locateModuleProperties(repoRoot)) {
            violations.addAll(sentinelViolations(propertiesFile, repoRoot));
        }

        assertTrue(violations.isEmpty(),
                "These infochat.*base-url keys resolve to a REACHABLE endpoint under "
                        + "the %test profile, so an unstubbed path would dial a real "
                        + "daemon and the suite's greenness would depend on whether "
                        + "that daemon happens to be up (the M1-644 incident). Point "
                        + "each at the sentinel " + SENTINEL + " by adding or fixing a "
                        + "%test. line — leave the other profiles alone. Offenders: "
                        + violations);
    }

    /**
     * Computes each {@code infochat.*base-url} key's {@code %test}-effective
     * value for one properties file and returns one message per key that is not
     * the sentinel.
     */
    private static List<String> sentinelViolations(Path propertiesFile, Path repoRoot)
            throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(propertiesFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }

        Map<String, String> unprofiled = new HashMap<>();
        Map<String, String> testProfile = new HashMap<>();
        for (String rawKey : properties.stringPropertyNames()) {
            String value = properties.getProperty(rawKey, "").strip();
            if (!rawKey.startsWith("%")) {
                if (isBaseUrlKey(rawKey)) {
                    unprofiled.put(rawKey, value);
                }
                continue;
            }
            int profileEnd = rawKey.indexOf('.');
            if (profileEnd < 0) {
                continue;
            }
            String baseKey = rawKey.substring(profileEnd + 1);
            if (!isBaseUrlKey(baseKey)) {
                continue;
            }
            // Quarkus permits a comma-separated profile list (%test,dev.key).
            // Comparing the whole segment would skip such a line and fall back
            // to the unprofiled value — a fail-OPEN miss — so split first.
            String profiles = rawKey.substring(1, profileEnd);
            if (List.of(profiles.split(",")).contains(TEST_PROFILE)) {
                testProfile.put(baseKey, value);
            }
        }

        // A %test line overrides the unprofiled default; a key with NO %test
        // line keeps the unprofiled value, which is the leak arm this guard
        // exists to catch, so it stays in the map rather than being skipped.
        Map<String, String> testEffective = new TreeMap<>(unprofiled);
        testEffective.putAll(testProfile);

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> entry : testEffective.entrySet()) {
            if (!SENTINEL.equals(entry.getValue())) {
                violations.add(repoRoot.relativize(propertiesFile) + ": " + entry.getKey()
                        + " resolves to '" + entry.getValue() + "' under %test");
            }
        }
        return violations;
    }

    /**
     * Deliberately broader than "ends with base-url" so a variant key cannot
     * slip past the lint; a false positive fails loudly and is fixed by hand,
     * whereas a false negative reinstates the M1-644 silence.
     */
    private static boolean isBaseUrlKey(String key) {
        return key.startsWith("infochat.") && key.contains("base-url");
    }

    /** Every {@code infochat-*} module's main application.properties, if it has one. */
    private static List<Path> locateModuleProperties(Path repoRoot) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> entries = Files.list(repoRoot)) {
            entries.filter(Files::isDirectory)
                    .filter(dir -> fileName(dir).startsWith("infochat-"))
                    .map(dir -> dir.resolve("src/main/resources/application.properties"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .forEach(out::add);
        }
        return out;
    }

    /**
     * Walk up from the working directory (the module basedir under surefire)
     * until a directory holds both {@code infochat-collector} and
     * {@code infochat-provider} — the multi-module checkout root.
     */
    private static Path locateRepoRoot() {
        for (@Nullable Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("infochat-collector"))
                    && Files.isDirectory(dir.resolve("infochat-provider"))) {
                return dir;
            }
        }
        throw new IllegalStateException(
                "could not locate the multi-module repo root (a directory containing "
                        + "infochat-collector and infochat-provider) walking up from "
                        + Path.of("").toAbsolutePath());
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        if (name == null) {
            throw new IllegalStateException("path has no file name: " + path);
        }
        return name.toString();
    }
}
