package app.zcat.infochat.core.source;

import java.util.List;
import java.util.Set;

/**
 * Declares which source languages an operator may declare for a source —
 * the {@code bootstrap-sources.json} {@code language} field and the
 * {@code /add-source --lang} flag. The language of a source is declared
 * by the operator, never inferred over the body (D29); this registry is
 * the reviewed set of codes that declaration may name, shared by both
 * entry points so the collector bootstrap path and the provider command
 * path enforce the same constant set.
 *
 * <p>The set ships as a code constant, mirroring the provider-side
 * {@code LanguageRegistry}: a runtime flag would be a feature flag
 * (forbidden by the engineering rules) and would let an operator declare
 * a language the ingest pipeline (M1-749) cannot actually serve. Extending
 * the set is a deliberate one-line reviewed change, never something an
 * operator input can do.</p>
 *
 * <p>This is a plain constant holder, not a CDI bean: both consumers
 * parse in plain-Java contexts ({@code AddSourceArgs.parse} is static;
 * {@code BootstrapSourcesParser} is constructed by hand), so the registry
 * exposes static members. The UI-facing {@code LanguageRegistry} (the
 * {@code /lang} user-language gate) is deliberately separate — user-UI
 * languages and source languages are different closed sets.</p>
 *
 * <p>Callers normalize their raw token ({@code Locale.ROOT} lower-case)
 * before validating; {@link #isSupported} is an exact match against the
 * canonical lower-case codes.</p>
 */
public final class SourceLanguageRegistry {

    private static final List<String> SUPPORTED_LANGUAGES = List.of("en", "cs");

    private static final Set<String> SUPPORTED_CODES = Set.copyOf(SUPPORTED_LANGUAGES);

    /** Canonical-order comma list — the {@code {0}} of {@code error.lang.unsupported_code}. */
    private static final String COMMA_JOINED = String.join(", ", SUPPORTED_LANGUAGES);

    private SourceLanguageRegistry() {
    }

    /** Whether {@code code} is in the reviewed supported set (exact match). */
    public static boolean isSupported(String code) {
        return SUPPORTED_CODES.contains(code);
    }

    /** The reviewed supported codes. */
    public static Set<String> supportedCodes() {
        return SUPPORTED_CODES;
    }

    /**
     * The supported codes joined with {@code ", "} in canonical order —
     * the deterministic interpolation value for the unsupported-code
     * friendly error.
     */
    public static String supportedCodesCommaList() {
        return COMMA_JOINED;
    }
}
