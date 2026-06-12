package app.zcat.infochat.collector.config;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the shipped loopback bind of the collector's HTTP listener
 * ({@code quarkus.http.host=127.0.0.1}): the only HTTP consumer in v1 is
 * the unauthenticated SmallRye Health probes, whose payload enumerates
 * operational topology, so the listener must stay host-local unless an
 * operator explicitly overrides it (docs/design/07-deployment.md
 * §7.12.1). A widened bind — in the base default or sneaked in by a
 * profile override — must be a deliberate, reviewed change.
 *
 * <p>Same plain-JUnit, main-properties-only harness as
 * {@link ReevalConfigKeysResolutionTest} and for the same reason: the
 * test-classpath config shadows the main file whose defaults are the
 * thing under test.
 */
class HttpBindDefaultConfigTest {

    private static final Path MAIN_PROPERTIES =
        Path.of("src/main/resources/application.properties");

    private static final String KEY = "quarkus.http.host";

    private static String shippedBind(String... profile) throws Exception {
        URL url = MAIN_PROPERTIES.toUri().toURL();
        SmallRyeConfigBuilder builder = new SmallRyeConfigBuilder()
            .withSources(new PropertiesConfigSource(url));
        if (profile.length > 0) {
            builder.withProfile(profile[0]);
        }
        return builder.build().getValue(KEY, String.class);
    }

    @Test
    void baseDefaultBindsHttpListenerToLoopback() throws Exception {
        assertEquals("127.0.0.1", shippedBind(),
            "the shipped base default must bind the HTTP listener (health"
                + " probes) to loopback");
    }

    @ParameterizedTest
    @ValueSource(strings = {"laptop", "vps", "pi", "remote-llm"})
    void everyOperatorProfileKeepsTheLoopbackBind(String profile) throws Exception {
        assertEquals("127.0.0.1", shippedBind(profile),
            "profile '" + profile + "' must not silently widen the shipped"
                + " loopback bind — widening is an explicit operator override");
    }

    @Test
    void shippedPropertiesCarryNoProfilePrefixedBindOverride() throws Exception {
        // The profile resolutions above cover the named operator profiles,
        // but a %prod-prefixed override (prod is the default runtime profile
        // of a packaged jar) would widen every production deployment while
        // each resolution here still returns loopback. Pinning the literal
        // line list closes the whole hole class: ANY quarkus.http.host=
        // line other than the base default fails, whatever its prefix.
        List<String> httpHostLines = Files.readAllLines(MAIN_PROPERTIES).stream()
            .filter(line -> !line.startsWith("#"))
            .filter(line -> line.contains(KEY + "="))
            .toList();

        assertEquals(List.of(KEY + "=127.0.0.1"), httpHostLines,
            "the shipped properties must carry exactly the loopback base"
                + " bind — a profile-prefixed override would widen the"
                + " packaged-jar runtime bind without failing the"
                + " profile-resolution pins");
    }
}
