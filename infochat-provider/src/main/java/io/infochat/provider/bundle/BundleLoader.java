package io.infochat.provider.bundle;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Loads the deterministic-string bundle from
 * {@code src/main/resources/bundles/<lang>.properties} and exposes
 * {@link #get(String)} for {@code HelpCommandHandler} and the
 * post-umbrella callers that replace M1-035b's inlined literals with
 * bundle lookups.
 *
 * <p>MVP hardcodes the language to {@code en}. The per-scope-language
 * lookup chain — {@code TranslationProvider} per-scope language →
 * bundle for the scope's {@code lang} → fallback to {@code en} — lands
 * in T2-C alongside {@code cs.properties}; today's hardcode keeps the
 * diff small while still routing every Provider string through the
 * bundle so T2-C's extension is purely additive.</p>
 *
 * <p>{@link #get(String)} throws {@link IllegalStateException} on a
 * missing key. Silently returning the empty string would defeat the
 * cross-ticket bundle-completeness CI invariant (an empty value looks
 * the same as a successful lookup, so a typo in {@link BundleKeys}
 * would ship a blank reply to users); throwing is the load-bearer
 * that pairs with {@code BundleLoaderTest}'s reflection-based
 * completeness assertion.</p>
 */
@ApplicationScoped
public class BundleLoader {

    private static final String BUNDLE_RESOURCE = "/bundles/en.properties";

    private final Properties bundle = new Properties();

    @PostConstruct
    void load() {
        try (InputStream stream = BundleLoader.class.getResourceAsStream(BUNDLE_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "Bundle resource not found on classpath: " + BUNDLE_RESOURCE);
            }
            // Properties.load(Reader) is UTF-8 by default; Properties.load(InputStream)
            // is ISO-8859-1 per the JDK contract. Wrap in an InputStreamReader so the
            // bundle file can carry non-ASCII text directly (the en.properties shipped
            // here is ASCII, but T2-C's cs.properties needs UTF-8).
            bundle.load(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read bundle resource: " + BUNDLE_RESOURCE, e);
        }
    }

    /**
     * Resolve the value for {@code key} from the loaded bundle. Throws
     * {@link IllegalStateException} when {@code key} is not present —
     * the load-bearing behavior the bundle-completeness CI check
     * relies on (an empty/missing value is treated as a build-time
     * defect, not silently swallowed).
     */
    public String get(String key) {
        String value = bundle.getProperty(key);
        if (value == null) {
            throw new IllegalStateException(
                    "Missing bundle key: " + key + " (resource=" + BUNDLE_RESOURCE + ")");
        }
        return value;
    }
}
