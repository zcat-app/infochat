package app.zcat.infochat.provider.config;

import app.zcat.infochat.provider.command.StatusCommandHandler;
import app.zcat.infochat.provider.summary.EligiblePostQuery;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the config-default-convergence invariant for M1-210 (acceptance
 * item 3): every provider reader of {@code infochat.profile.label} must
 * declare the same {@code @ConfigProperty} default, so the key never
 * resolves differently in two readers.
 *
 * <p>The test reads the {@code defaultValue} off each field's
 * {@code @ConfigProperty} annotation reflectively — it asserts the
 * <i>declaration</i>, which is the contract, without booting Quarkus or
 * resolving config. The collector's
 * {@code StartupReleaseOnStage2FailureWarn.profileLabel} is not on this
 * module's classpath, so it cannot be reflected here; it carries the same
 * literal by construction and is exercised by the collector suite + a full
 * {@code mvn verify} boot.
 *
 * <p>The Provider's asset-staleness threshold no longer mirrors the
 * Collector's {@code infochat.assets.refresh.*} cadence keys — it is a
 * single Provider-owned {@code infochat.assets.freshness-window} property
 * (M1-340), so there is no cross-service cadence-key convergence left for
 * this test to pin.
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
