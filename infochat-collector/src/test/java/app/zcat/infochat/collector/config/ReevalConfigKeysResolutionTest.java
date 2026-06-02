package app.zcat.infochat.collector.config;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the nine default-less {@code infochat.reeval.*} keys consumed
 * by {@code ReEvaluationJob}, {@code PerSourceUnknownTracker}, and
 * {@code AdminReviewTtlJob} resolve from the collector's <em>main</em>
 * {@code application.properties} under every operator profile
 * (laptop / vps / pi / remote-llm).
 *
 * <p>The collector boot bug this guards against was masked precisely because
 * the keys existed in {@code src/test/resources/application.properties} (so
 * every {@code @QuarkusTest} booted) but not in main config (so production
 * boots threw {@code NoSuchElementException} / scheduler-config-parse errors).
 * A {@code @QuarkusTest} cannot reproduce that — it loads the test-classpath
 * config, where test resources shadow main. So this is a plain-JUnit test that
 * loads the <em>main</em> properties file directly off the filesystem (the
 * surefire working directory is the module basedir, the same assumption the
 * fixture-loading tests in this module already rely on) and applies SmallRye's
 * profile-override semantics with no test config in the picture.
 *
 * <p>For each profile the test asserts every key resolves and parses to its
 * consumer type; for the five profile-driven keys it additionally pins the
 * exact per-profile value from docs/design/04-security.md §Re-evaluation job
 * (re-eval cadence + caps table and the per-source UNKNOWN auto-disable table),
 * so a flat-across-profiles regression is caught, not just an absent key.
 */
class ReevalConfigKeysResolutionTest {

    private static final Path MAIN_PROPERTIES =
        Path.of("src/main/resources/application.properties");

    /**
     * Builds a config view over ONLY the main application.properties with the
     * given infochat profile active. No default sources are added, so the
     * test-classpath application.properties (which carries the %test values
     * and would otherwise shadow main) never participates.
     */
    private static SmallRyeConfig mainConfigFor(String profile) throws Exception {
        URL url = MAIN_PROPERTIES.toUri().toURL();
        return new SmallRyeConfigBuilder()
            .addDiscoveredConverters()
            .withProfile(profile)
            .withSources(new PropertiesConfigSource(url))
            .build();
    }

    @Test
    void mainPropertiesFileExists() {
        assertTrue(MAIN_PROPERTIES.toFile().isFile(),
            "Expected main config at " + MAIN_PROPERTIES.toAbsolutePath()
                + " (surefire CWD must be the module basedir)");
    }

    @Test
    void laptopProfileResolvesAllReevalKeys() throws Exception {
        SmallRyeConfig config = mainConfigFor("laptop");
        assertProfileDrivenKeys(config, "10m", 6, 3, 0.40, Duration.ofHours(6));
        assertSingleGlobalKeys(config);
    }

    @Test
    void vpsProfileResolvesAllReevalKeys() throws Exception {
        SmallRyeConfig config = mainConfigFor("vps");
        assertProfileDrivenKeys(config, "5m", 12, 6, 0.30, Duration.ofHours(1));
        assertSingleGlobalKeys(config);
    }

    @Test
    void piProfileResolvesAllReevalKeys() throws Exception {
        SmallRyeConfig config = mainConfigFor("pi");
        assertProfileDrivenKeys(config, "30m", 4, 2, 0.50, Duration.ofHours(12));
        assertSingleGlobalKeys(config);
    }

    @Test
    void remoteLlmProfileResolvesAllReevalKeys() throws Exception {
        SmallRyeConfig config = mainConfigFor("remote-llm");
        assertProfileDrivenKeys(config, "5m", 12, 6, 0.25, Duration.ofHours(1));
        assertSingleGlobalKeys(config);
    }

    private static void assertProfileDrivenKeys(SmallRyeConfig config, String pollInterval,
            int infraFailureCap, int unknownCap, double unknownRateThreshold,
            Duration unknownRateWindow) {
        // poll-interval feeds @Scheduled(every=...) as a string expression.
        assertEquals(pollInterval,
            config.getValue("infochat.reeval.poll-interval", String.class));
        assertEquals(infraFailureCap,
            config.getValue("infochat.reeval.infra-failure-cap", Integer.class));
        assertEquals(unknownCap,
            config.getValue("infochat.reeval.unknown-cap", Integer.class));
        assertEquals(unknownRateThreshold,
            config.getValue("infochat.reeval.unknown-rate-threshold", Double.class), 1e-9);
        assertEquals(unknownRateWindow,
            config.getValue("infochat.reeval.unknown-rate-window", Duration.class));
    }

    private static void assertSingleGlobalKeys(SmallRyeConfig config) {
        assertEquals(Duration.ofDays(14),
            config.getValue("infochat.reeval.admin-review-ttl", Duration.class));
        assertEquals(200,
            config.getValue("infochat.reeval.needs-review-depth-threshold", Integer.class));
        // ttl-poll-interval and unknown-tracker-poll-interval feed @Scheduled as
        // string expressions; assert they resolve and parse without throwing.
        assertEquals("24h",
            config.getValue("infochat.reeval.ttl-poll-interval", String.class));
        assertEquals("15m",
            config.getValue("infochat.reeval.unknown-tracker-poll-interval", String.class));
    }
}
