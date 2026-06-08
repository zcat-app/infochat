package app.zcat.infochat.provider.config;

import app.zcat.infochat.provider.command.StatusCommandHandler;
import app.zcat.infochat.provider.command.asset.AssetSnapshotReader;
import app.zcat.infochat.provider.summary.EligiblePostQuery;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                "refreshCoingeckoSeconds", "refreshKrakenSeconds", "refreshBitfinexSeconds"}) {
            assertEquals(ConfigProperty.UNCONFIGURED_VALUE,
                    configDefault(AssetSnapshotReader.class, field),
                    "AssetSnapshotReader." + field + " must carry no inline defaultValue");
        }
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
