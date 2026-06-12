package app.zcat.infochat.collector.config;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shipped per-profile defaults of
 * {@code infochat.security.release-on-stage2-failure} — the ops-posture
 * decision recorded in docs/design/07-deployment.md §7.12.1 (profile table
 * in docs/design/04-security.md §4.7): the local-LLM profiles
 * (base / laptop / pi) ship <em>fail-open</em> (Stage-2 infra failure
 * releases the post with Stage 1 redactions retained), the hosted shapes
 * (vps / remote-llm) ship <em>fail-closed</em> (post quarantined).
 *
 * <p>A flipped default would silently change what an existing deployment
 * does with unjudged posts on its next upgrade, so whichever value ships
 * must be a deliberate, reviewed change — this test makes the flip loud.
 *
 * <p>Same plain-JUnit, main-properties-only harness as
 * {@link ReevalConfigKeysResolutionTest} and for the same reason: a
 * {@code @QuarkusTest} reads the test-classpath config, where test
 * resources shadow the main file whose defaults are the thing under test.
 */
class Stage2FailOpenDefaultConfigTest {

    private static final Path MAIN_PROPERTIES =
        Path.of("src/main/resources/application.properties");

    private static final String KEY = "infochat.security.release-on-stage2-failure";

    private static boolean shippedDefaultFor(String profile) throws Exception {
        URL url = MAIN_PROPERTIES.toUri().toURL();
        return new SmallRyeConfigBuilder()
            .withProfile(profile)
            .withSources(new PropertiesConfigSource(url))
            .build()
            .getValue(KEY, Boolean.class);
    }

    private static boolean shippedBaseDefault() throws Exception {
        URL url = MAIN_PROPERTIES.toUri().toURL();
        return new SmallRyeConfigBuilder()
            .withSources(new PropertiesConfigSource(url))
            .build()
            .getValue(KEY, Boolean.class);
    }

    @Test
    void baseDefaultShipsFailOpen() throws Exception {
        assertTrue(shippedBaseDefault(),
            "the no-profile base default must stay fail-open"
                + " (release-on-stage2-failure=true)");
    }

    @Test
    void laptopProfileShipsFailOpen() throws Exception {
        assertTrue(shippedDefaultFor("laptop"),
            "laptop must stay fail-open: the co-located local judge being"
                + " down is routine there, not exceptional");
    }

    @Test
    void piProfileShipsFailOpen() throws Exception {
        assertTrue(shippedDefaultFor("pi"),
            "pi must stay fail-open: the co-located local judge being"
                + " down is routine there, not exceptional");
    }

    @Test
    void vpsProfileShipsFailClosed() throws Exception {
        assertFalse(shippedDefaultFor("vps"),
            "vps must stay fail-closed: hosted shapes quarantine posts"
                + " whose Stage-2 judgement could not run");
    }

    @Test
    void remoteLlmProfileShipsFailClosed() throws Exception {
        assertFalse(shippedDefaultFor("remote-llm"),
            "remote-llm must stay fail-closed: hosted shapes quarantine"
                + " posts whose Stage-2 judgement could not run");
    }
}
