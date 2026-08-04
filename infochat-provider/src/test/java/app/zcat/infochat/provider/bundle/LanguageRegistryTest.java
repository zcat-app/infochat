package app.zcat.infochat.provider.bundle;

import org.junit.jupiter.api.Test;

import java.lang.Character.UnicodeScript;
import java.util.Optional;
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
    void enabledSetIsExactlyEnCsEsRuAndTr() {
        LanguageRegistry registry = registryOverLoaded(Set.of("en", "cs", "es", "ru", "tr"));

        // Exact set equality, never contains(): the assertion has to fail
        // when the set GROWS, not only when it shrinks. Enabling a language
        // is the reviewed, ticketed decision this class exists to force —
        // a containment check would let an undeclared addition ship silently.
        assertEquals(Set.of("en", "cs", "es", "ru", "tr"), registry.enabledLanguages(),
                "the enabled set must be exactly {en, cs, es, ru, tr} — the same codes users "
                        + "can select today; widening it is a ticketed, reviewed change");
    }

    @Test
    void loadedBundleIsNotEnabledUnlessDeclared() {
        // "th" is present on the classpath and listed in
        // BundleLoader.LOADED_LANGUAGES (stubbed) — but NOT declared
        // enabled. The registry must still reject it: loading does not
        // imply availability.
        LanguageRegistry registry = registryOverLoaded(Set.of("en", "cs", "es", "ru", "tr", "th"));

        assertEquals(Set.of("en", "cs", "es", "ru", "tr"), registry.enabledLanguages(),
                "a loaded-but-undeclared bundle must not widen the enabled set");
        assertFalse(registry.isEnabled("th"),
                "loaded bundle present, yet 'th' must be rejected — it was never declared enabled");
        assertTrue(registry.isEnabled("cs"),
                "control: a declared code stays enabled");
    }

    @Test
    void eachEnabledLanguageDeclaresItsScriptAndUnknownCodesDeclareNone() {
        // The script is the registry's to own: TranslationPipeline's
        // failure condition (d) reads it from here so a fourth script is a
        // registry entry, not a pipeline edit.
        assertEquals(Optional.of(UnicodeScript.CYRILLIC), LanguageRegistry.scriptOf("ru"),
                "ru must declare Cyrillic — the script condition (d) tests translator output for");
        assertEquals(Optional.of(UnicodeScript.LATIN), LanguageRegistry.scriptOf("en"),
                "en must declare Latin");
        assertEquals(Optional.of(UnicodeScript.LATIN), LanguageRegistry.scriptOf("cs"),
                "cs must declare Latin");
        assertEquals(Optional.of(UnicodeScript.LATIN), LanguageRegistry.scriptOf("es"),
                "es must declare Latin");
        assertEquals(Optional.of(UnicodeScript.LATIN), LanguageRegistry.scriptOf("tr"),
                "tr must declare Latin — Turkish adds no new script, but every enabled "
                        + "language must still declare one or condition (d) has nothing to "
                        + "check a Turkish reply against");
        // A scope_preferences row can outlive its code's declaration, so an
        // undeclared code yields no expectation rather than a guessed one.
        assertEquals(Optional.empty(), LanguageRegistry.scriptOf("th"),
                "an undeclared code must declare no script");
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
