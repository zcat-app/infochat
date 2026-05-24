package app.zcat.infochat.provider.bundle;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Loads the deterministic-string bundles from
 * {@code src/main/resources/bundles/<lang>.properties} for every code
 * in {@link #LOADED_LANGUAGES} and exposes both the legacy 1-arg
 * {@link #get(String)} accessor (preserved verbatim — returns the
 * {@code en} value) and the M1-060 2-arg
 * {@link #get(String, String)} accessor for per-scope language
 * resolution.
 *
 * <p>The 1-arg accessor is the load-bearing back-compat for the
 * 168 existing call sites in 19 handler/router files (see M1-060
 * out_of_scope item 14): every handler other than
 * {@code LangCommandHandler} continues to resolve through the
 * single-arg path until T2-D / T2-F migrate the chat-mode and digest
 * surfaces wholesale. The 2-arg accessor is consumed by
 * {@code LangCommandHandler}'s confirmation reply so a
 * {@code /lang cs} user sees the Czech version of the confirmation
 * immediately.</p>
 *
 * <p>{@link #LOADED_LANGUAGES} is hardcoded rather than auto-discovered
 * from the classpath because (a) auto-discovery is fragile under
 * different classloader topologies and (b) the spec's "bundle drop-in"
 * intent is satisfied by the list + the new properties file together:
 * adding a third language is one source-file drop plus a one-character
 * edit to the list (e.g. {@code List.of("en", "cs", "pl")}).</p>
 *
 * <p>Both accessors throw {@link IllegalStateException} on a missing
 * key after the en fallback fails. Silently returning the empty string
 * would defeat the cross-ticket bundle-completeness CI invariant — an
 * empty value looks the same as a successful lookup, so a typo in
 * {@link BundleKeys} would ship a blank reply to users.</p>
 */
@ApplicationScoped
public class BundleLoader {

    /**
     * v1 supported language set. Adding a language requires a new
     * {@code <lang>.properties} drop-in AND adding the code here; the
     * widened {@code BundleLoaderTest} reflective check enforces
     * bilateral parity against {@link BundleKeys} for every loaded
     * bundle at build time.
     */
    private static final List<String> LOADED_LANGUAGES = List.of("en", "cs");

    /** The fallback language used by both accessors when a key is missing in the target. */
    private static final String FALLBACK_LANGUAGE = "en";

    private final Map<String, Properties> bundlesByLang = new HashMap<>();

    @PostConstruct
    void load() {
        for (String lang : LOADED_LANGUAGES) {
            String resource = "/bundles/" + lang + ".properties";
            Properties bundle = new Properties();
            try (InputStream stream = BundleLoader.class.getResourceAsStream(resource)) {
                if (stream == null) {
                    throw new IllegalStateException(
                            "Bundle resource not found on classpath: " + resource);
                }
                // Properties.load(Reader) is UTF-8 by default;
                // Properties.load(InputStream) is ISO-8859-1 per the JDK
                // contract. Wrap in an InputStreamReader so cs.properties'
                // diacritics (`á`, `ě`, `š`, ...) round-trip correctly.
                bundle.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Failed to read bundle resource: " + resource, e);
            }
            bundlesByLang.put(lang, bundle);
        }
    }

    /**
     * Resolve the value for {@code key} from the {@code en} bundle.
     * Preserved verbatim from the pre-M1-060 signature so the 168
     * existing 1-arg call sites in 19 handler/router files continue
     * to compile and behave identically; the en value is the same
     * one those call sites returned before the multi-bundle refactor.
     *
     * <p>Throws {@link IllegalStateException} when {@code key} is not
     * present in {@code en.properties} — the load-bearing behavior the
     * bundle-completeness CI check relies on.</p>
     */
    public String get(@NonNull String key) {
        return resolveStrict(key, FALLBACK_LANGUAGE);
    }

    /**
     * Resolve the value for {@code key} from the {@code langCode}
     * bundle; if the key is missing in {@code langCode}, fall back to
     * the {@code en} bundle; if it is missing in {@code en}, throw
     * {@link IllegalStateException} (the same single-arg throw contract
     * the pre-M1-060 accessor carried).
     *
     * <p>Unknown {@code langCode} values short-circuit straight to the
     * en fallback — the handler-side
     * {@code bundleLoader.supportedLanguages()} pre-check is the
     * authoritative gate against unsupported codes (see
     * {@code LangCommandHandler}); this accessor is robust to it for
     * defense-in-depth.</p>
     */
    public String get(@NonNull String key, @NonNull String langCode) {
        Properties target = bundlesByLang.get(langCode);
        if (target != null) {
            String value = target.getProperty(key);
            if (value != null) {
                return value;
            }
        }
        return resolveStrict(key, FALLBACK_LANGUAGE);
    }

    /**
     * The loaded language set. Source of truth for
     * {@code LangCommandHandler}'s unsupported-code rejection: the
     * spec's "bundle drop-in" invariant means a new language must
     * appear in the unsupported-code error message automatically once
     * its bundle ships, with no handler edit.
     */
    public Set<String> supportedLanguages() {
        return bundlesByLang.keySet();
    }

    private String resolveStrict(String key, String langCode) {
        Properties bundle = bundlesByLang.get(langCode);
        if (bundle == null) {
            throw new IllegalStateException(
                    "Bundle for language not loaded: " + langCode);
        }
        String value = bundle.getProperty(key);
        if (value == null) {
            throw new IllegalStateException(
                    "Missing bundle key: " + key + " (language=" + langCode + ")");
        }
        return value;
    }
}
