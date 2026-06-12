package app.zcat.infochat.provider.config;

import app.zcat.infochat.provider.command.StatusCommandHandler;
import app.zcat.infochat.provider.command.asset.AssetSnapshotReader;
import app.zcat.infochat.provider.summary.EligiblePostQuery;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pins the config-default-convergence invariants for M1-210 (acceptance
 * items 3 and 4): the {@code @ConfigProperty} declarations these beans
 * carry must agree on how a key resolves, so the same key never resolves
 * differently in two readers.
 *
 * <p>The test reads the {@code defaultValue} off each field's
 * {@code @ConfigProperty} annotation reflectively — it asserts the
 * <i>declaration</i>, which is the contract, without booting Quarkus or
 * resolving config. Cross-module declarations (the collector's
 * {@code StartupReleaseOnStage2FailureWarn.profileLabel} and
 * {@code AssetSnapshotFetcher} refresh keys) are not on this module's
 * classpath, so they cannot be reflected here; they carry the same
 * literals by construction and are exercised by the collector suite + a
 * full {@code mvn verify} boot.
 *
 * <p>For the shared {@code infochat.assets.refresh.*} cadence keys the
 * declaration-only checks above are not enough: the two services bind the
 * same key family and must agree on the <i>shipped</i> per-profile value AND
 * grammar, or an operator's documented {@code -Dinfochat.assets.refresh.<host>}
 * override is valid in only one service. {@link #assetsRefreshKeysConvergeAcrossServices()}
 * reads both modules' main {@code application.properties} straight off the
 * filesystem (surefire's working directory is the module basedir — the same
 * assumption {@code ReevalConfigKeysResolutionTest} already relies on) and
 * compares the raw key/value strings. Raw-string comparison is deliberate:
 * Quarkus' {@code DurationConverter} parses bare {@code "90"} and {@code "90s"}
 * to the same {@link java.time.Duration}, so resolving each side through the
 * converter would hide grammar drift that the raw strings expose.
 */
class ConfigDefaultsConvergenceTest {

    /**
     * Item 3: every provider reader of {@code infochat.profile.label}
     * defaults to {@code "unknown"} — the honest no-profile sentinel. The
     * collector's {@code StartupReleaseOnStage2FailureWarn} carries the
     * same {@code "unknown"} literal, so all three readers agree.
     */
    @Test
    void profileLabelDefaultIsUnknownAcrossProviderBeans() throws NoSuchFieldException {
        assertEquals("unknown", configDefault(EligiblePostQuery.class, "profileLabel"),
                "EligiblePostQuery.profileLabel must default to \"unknown\"");
        assertEquals("unknown", configDefault(StatusCommandHandler.class, "profileLabel"),
                "StatusCommandHandler.profileLabel must default to \"unknown\"");
    }

    /**
     * Item 4: the profile-driven {@code infochat.assets.refresh.*} keys
     * carry NO inline {@code defaultValue} in the provider reader —
     * application.properties is the source of truth, matching the
     * collector's {@code AssetSnapshotFetcher} / FetchScheduler convention.
     * "Equally required" means the annotation default is the
     * {@link ConfigProperty#UNCONFIGURED_VALUE} sentinel.
     */
    @Test
    void assetsRefreshKeysHaveNoInlineDefaultInProvider() throws NoSuchFieldException {
        for (String field : new String[]{
                "coingeckoRefresh", "krakenRefresh", "bitfinexRefresh"}) {
            assertEquals(ConfigProperty.UNCONFIGURED_VALUE,
                    configDefault(AssetSnapshotReader.class, field),
                    "AssetSnapshotReader." + field + " must carry no inline defaultValue");
        }
    }

    private static final Path PROVIDER_PROPERTIES =
            Path.of("src/main/resources/application.properties");
    private static final Path COLLECTOR_PROPERTIES =
            Path.of("..", "infochat-collector", "src", "main", "resources", "application.properties");

    /**
     * Item 2 (M1-300): both services must ship the {@code infochat.assets.refresh.*}
     * cadence keys with identical value AND grammar across every profile prefix,
     * so a single {@code -Dinfochat.assets.refresh.<host>=<duration>} override is
     * valid in both. Compares the raw shipped strings of the two main property
     * files; fails if the key sets differ or any shared key carries a different
     * value or grammar (e.g. {@code "90"} vs {@code "90s"}).
     */
    @Test
    void assetsRefreshKeysConvergeAcrossServices() throws IOException {
        Map<String, String> collector = refreshKeys(COLLECTOR_PROPERTIES);
        Map<String, String> provider = refreshKeys(PROVIDER_PROPERTIES);

        assertFalse(collector.isEmpty(),
                "no infochat.assets.refresh.* keys found in " + COLLECTOR_PROPERTIES.toAbsolutePath()
                        + " (surefire CWD must be the module basedir)");
        assertEquals(collector.keySet(), provider.keySet(),
                "both services must ship the same infochat.assets.refresh.* key set");
        for (Map.Entry<String, String> entry : collector.entrySet()) {
            assertEquals(entry.getValue(), provider.get(entry.getKey()),
                    "shared key " + entry.getKey()
                            + " must ship the same value and grammar in both services");
        }
    }

    /** Loads {@code file} and returns its {@code infochat.assets.refresh.*} keys (any profile prefix). */
    private static Map<String, String> refreshKeys(Path file) throws IOException {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            props.load(reader);
        }
        Map<String, String> out = new TreeMap<>();
        for (String name : props.stringPropertyNames()) {
            if (name.contains("infochat.assets.refresh.")) {
                out.put(name, props.getProperty(name));
            }
        }
        return out;
    }

    private static String configDefault(Class<?> type, String fieldName) throws NoSuchFieldException {
        Field field = type.getDeclaredField(fieldName);
        ConfigProperty annotation = field.getAnnotation(ConfigProperty.class);
        if (annotation == null) {
            throw new AssertionError(type.getSimpleName() + "." + fieldName
                    + " is not annotated with @ConfigProperty");
        }
        return annotation.defaultValue();
    }
}
