package app.zcat.infochat.provider.bundle;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.lang.Character.UnicodeScript;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
     * A language offered to users, and the Unicode script its prose is
     * written in. The script is a property of the language, not of any
     * one consumer, so a new bundle declares it here once and every
     * script-aware check inherits it — see
     * {@code TranslationPipeline}'s failure condition (d).
     */
    public record EnabledLanguage(String code, UnicodeScript script) {}

    /**
     * The declared enabled set — exactly {@code {en, cs, es, ru}}, the same
     * codes users can select today. Flipping an entry is a ticketed,
     * reviewed change with measured evidence behind it.
     *
     * <p>{@code es} joined in M1-718. What cleared it was not a
     * per-language embedder score but the English pivot (D29 amended,
     * D58): the corpus is anchored in English at ingest and a
     * non-English query is translated into that anchor, so both
     * retrieval arms compare English to English and a Spanish scope
     * retrieves exactly as well as an English one. The quality bar this
     * class exists to enforce is therefore met by construction rather
     * than by measurement — see the ticket for why the embedder-swap
     * gate it was originally filed behind no longer applies.</p>
     */
    private static final List<EnabledLanguage> ENABLED_LANGUAGES = List.of(
            new EnabledLanguage("en", UnicodeScript.LATIN),
            new EnabledLanguage("cs", UnicodeScript.LATIN),
            new EnabledLanguage("es", UnicodeScript.LATIN),
            new EnabledLanguage("ru", UnicodeScript.CYRILLIC));

    private static final Set<String> ENABLED_CODES =
            ENABLED_LANGUAGES.stream()
                    .map(EnabledLanguage::code)
                    .collect(Collectors.toUnmodifiableSet());

    private static final Map<String, UnicodeScript> SCRIPT_BY_CODE =
            ENABLED_LANGUAGES.stream()
                    .collect(Collectors.toUnmodifiableMap(
                            EnabledLanguage::code, EnabledLanguage::script));

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

    /**
     * The Unicode script {@code code}'s prose is written in, or empty
     * when the code declares none — which is every code outside the
     * enabled set. A {@code scope_preferences.language} row written
     * before a code was retired outlives the declaration, so callers
     * get "no expectation" rather than a guess: a script-aware check
     * with nothing to check against must not invent one.
     *
     * <p>Static, unlike the instance accessors above: the mapping is a
     * compile-time constant for the same reason the enabled set is one
     * (a runtime source would be the forbidden feature flag), so a
     * consumer needs no injection point to read it. The bean's injected
     * state exists solely for the startup {@link #validate()} check,
     * which this lookup does not depend on.</p>
     */
    public static Optional<UnicodeScript> scriptOf(String code) {
        return Optional.ofNullable(SCRIPT_BY_CODE.get(code));
    }
}
