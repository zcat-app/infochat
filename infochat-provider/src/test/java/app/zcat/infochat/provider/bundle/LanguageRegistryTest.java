package app.zcat.infochat.provider.bundle;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit tests for {@link LanguageRegistry} (no Quarkus boot — the
 * registry's only collaborator is {@link BundleLoader}, stubbed by an
 * anonymous subclass; Mockito is intentionally absent from the Provider
 * classpath). The registry's field is package-private, so the test —
 * same package — wires the stub directly.
 */
class LanguageRegistryTest {

    /** Registry wired to a stub loader reporting the given codes as loaded. */
    private static LanguageRegistry registryOverLoaded(Set<String> loadedCodes) {
        BundleLoader stubLoader = new BundleLoader() {
            @Override
            public Set<String> supportedLanguages() {
                return loadedCodes;
            }
        };
        LanguageRegistry registry = new LanguageRegistry();
        registry.bundleLoader = stubLoader;
        registry.validate();
        return registry;
    }

    @Test
    void enabledSetIsExactlyEnCsAndEs() {
        LanguageRegistry registry = registryOverLoaded(Set.of("en", "cs", "es"));

        assertEquals(Set.of("en", "cs", "es"), registry.enabledLanguages(),
                "the enabled set must be exactly {en, cs, es} — the same codes users "
                        + "can select today; widening it is a ticketed, reviewed change");
    }

    @Test
    void loadedBundleIsNotEnabledUnlessDeclared() {
        // "th" is present on the classpath and listed in
        // BundleLoader.LOADED_LANGUAGES (stubbed) — but NOT declared
        // enabled. The registry must still reject it: loading does not
        // imply availability.
        LanguageRegistry registry = registryOverLoaded(Set.of("en", "cs", "es", "th"));

        assertEquals(Set.of("en", "cs", "es"), registry.enabledLanguages(),
                "a loaded-but-undeclared bundle must not widen the enabled set");
        assertFalse(registry.isEnabled("th"),
                "loaded bundle present, yet 'th' must be rejected — it was never declared enabled");
        assertTrue(registry.isEnabled("cs"),
                "control: a declared code stays enabled");
    }

    @Test
    void declaredButMissingBundleFailsFast() {
        // The reverse direction IS a startup error: declaring a language
        // enabled without its loaded bundle would break every reply
        // resolved in that code.
        BundleLoader stubLoader = new BundleLoader() {
            @Override
            public Set<String> supportedLanguages() {
                return Set.of("en");
            }
        };
        LanguageRegistry registry = new LanguageRegistry();
        registry.bundleLoader = stubLoader;

        assertThrows(IllegalStateException.class, registry::validate,
                "a declared-enabled code with no loaded bundle must fail fast at startup");
    }
}
