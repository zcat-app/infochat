package app.zcat.infochat.provider.bundle;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Declares which languages users may actually select via
 * {@code /lang <code>} — availability is declared, not inferred from
 * bundle presence. {@link BundleLoader} loads every code in its
 * {@code LOADED_LANGUAGES}; this registry gates which of those are
 * offered. A loaded bundle therefore does NOT by itself make a
 * language selectable: enabling a language is a reviewed one-line code
 * change here, gated on that language's measured quality bar, never a
 * side effect of dropping in a {@code <lang>.properties} file.
 *
 * <p>The enabled set ships as a code constant, mirroring
 * {@code BundleLoader.LOADED_LANGUAGES}, rather than as a config
 * property: a runtime flag would be a feature flag (forbidden by the
 * engineering rules) and would let an operator enable a language whose
 * quality was never measured — the exact failure this class exists to
 * prevent.</p>
 *
 * <p>One record per language, not a bare code list, so follow-on
 * per-language metadata — the expected Unicode script the Russian
 * ticket's {@code TranslationPipeline} target-script check needs —
 * has a home here without reopening {@link BundleLoader}, whose job
 * stays "load and resolve bundle keys".</p>
 */
@ApplicationScoped
public class LanguageRegistry {

    /**
     * A language offered to users. Today only the code is carried;
     * per-language metadata (expected Unicode script, …) lands on this
     * record with the ticket that needs it.
     */
    public record EnabledLanguage(String code) {}

    /**
     * The declared enabled set — exactly {@code {en, cs}}, the same
     * codes users can select today. Flipping an entry is a ticketed,
     * reviewed change with measured evidence behind it.
     */
    private static final List<EnabledLanguage> ENABLED_LANGUAGES = List.of(
            new EnabledLanguage("en"),
            new EnabledLanguage("cs"));

    private static final Set<String> ENABLED_CODES =
            ENABLED_LANGUAGES.stream()
                    .map(EnabledLanguage::code)
                    .collect(Collectors.toUnmodifiableSet());

    @Inject BundleLoader bundleLoader;

    /**
     * Fail fast when a declared-enabled code has no loaded bundle —
     * enabling a language without its bundle would break every reply
     * resolved in that code. The reverse direction is NOT validated:
     * loaded-but-not-declared is the normal state of a bundle that
     * shipped ahead of its quality gate.
     */
    @PostConstruct
    void validate() {
        Set<String> loaded = bundleLoader.supportedLanguages();
        for (EnabledLanguage language : ENABLED_LANGUAGES) {
            if (!loaded.contains(language.code())) {
                throw new IllegalStateException(
                        "Enabled language has no loaded bundle: " + language.code());
            }
        }
    }

    /**
     * The codes {@code /lang} accepts. Source of truth for
     * {@code LangCommandHandler}'s unsupported-code rejection: a code
     * absent here is rejected even when its bundle is loaded.
     */
    public Set<String> enabledLanguages() {
        return ENABLED_CODES;
    }

    /** Whether {@code code} is in the declared enabled set. */
    public boolean isEnabled(String code) {
        return ENABLED_CODES.contains(code);
    }
}
