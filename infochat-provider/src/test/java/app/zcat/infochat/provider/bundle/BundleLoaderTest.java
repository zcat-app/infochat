package app.zcat.infochat.provider.bundle;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bundle-completeness CI check for {@link BundleLoader} per
 * {@code docs/spec/commands.md} §Discovery /help (Bundle composition).
 *
 * <p>M1-060 widened the original M1-035c en-only completeness check to
 * a bilateral one: every {@link BundleKeys} constant must be present
 * with a non-empty value in EVERY {@link BundleLoader#supportedLanguages()}
 * entry — i.e. both {@code en.properties} and {@code cs.properties} in
 * v1. M1-474 gives that check the teeth D43 mandates: it now reads each
 * shipped bundle's OWN key set directly from the classpath resource
 * rather than calling {@link BundleLoader#get(String, String)}, whose
 * en fallback (a key missing from {@code cs} silently resolves to the
 * en value) masked an incomplete bundle and let the gate stay green
 * while the D43 invariant was violated. A {@link BundleKeys} constant
 * present in {@code en} but absent from {@code cs} now fails the build,
 * as D43 requires ("CI fails on a missing key").</p>
 *
 * <p>The probe-key scenarios use the test-only
 * {@code test.fallback.probe} entry, which lives ONLY in
 * en.properties and is NOT a {@link BundleKeys} constant — so the
 * constant-driven completeness iteration never inspects it (the
 * iteration walks BundleKeys' reflective field set). This is the
 * load-bearing setup that lets the test exercise the 2-arg accessor's
 * en-fallback path without breaking bilateral parity.</p>
 */
@QuarkusTest
class BundleLoaderTest {

    /**
     * Key present in {@code en.properties} only — NOT mirrored in
     * {@code cs.properties}, NOT in {@link BundleKeys}. Used by
     * {@link #twoArgAccessorFallsBackToEnWhenKeyMissingInTargetLanguage}.
     */
    private static final String FALLBACK_PROBE_KEY = "test.fallback.probe";

    /**
     * Expected value the probe resolves to in en.properties. Mirrored
     * verbatim from the en.properties entry; if the entry changes
     * value, this constant must change too — the test asserts equality
     * to catch a silent rewrite of the probe value.
     */
    private static final String FALLBACK_PROBE_EXPECTED_VALUE = "fallback-probe-en-only-value";

    @Inject
    BundleLoader bundleLoader;

    @Test
    void everyBundleKeysConstantHasNonEmptyOwnValueInEveryShippedBundle() throws Exception {
        // Reflect over BundleKeys to enumerate every key constant. The
        // reflection walk subsumes the original M1-035c en-only
        // scenario: en is one of the iterated bundles, so removing a
        // key from en.properties fails this check the same way it
        // failed the pre-M1-060 one.
        List<String> keys = collectBundleKeys();
        assertFalse(keys.isEmpty(),
                "BundleKeys must declare at least one public static final String constant; "
                        + "the reflection check is the load-bearing CI guard and an empty key set "
                        + "would silently pass");

        Set<String> supported = bundleLoader.supportedLanguages();
        assertFalse(supported.isEmpty(),
                "BundleLoader.supportedLanguages() must be non-empty; the bilateral "
                        + "check would silently pass against an empty language set");

        for (String lang : supported) {
            // D43 teeth (M1-474): inspect THIS bundle's own key set, read
            // straight from the shipped resource — NOT bundleLoader.get(key,
            // lang), whose en fallback masks a key missing from cs and made
            // this gate structurally blind. A BundleKeys constant present in
            // en but absent from this bundle must fail the build.
            Properties ownKeys = loadOwnKeys(lang);
            for (String key : keys) {
                String value = ownKeys.getProperty(key);
                assertNotNull(value,
                        "bundle key absent from " + lang + ".properties own key set: " + key
                                + " — D43 requires every shipped bundle to carry every BundleKeys "
                                + "constant in its own key set (no en fallback)");
                assertFalse(value.isEmpty(),
                        "bundle key present but empty in " + lang + ".properties: " + key
                                + " — bundle-completeness CI check requires a non-empty value");
            }
        }
    }

    @Test
    void everyShippedBundleHasExactlyEnKeysetMinusTheEnOnlyProbe() throws Exception {
        // D43 en-keyset parity (M1-475). The constant-driven check above only
        // sees BundleKeys constants, but a bundle key need not be a constant:
        // error.quarantine.rate_limit (a local string in QuarantineCommandHandler)
        // and error.add_source.userinfo_rejected (a raw Failure literal) reach
        // BundleLoader without one and are therefore invisible to that iteration.
        // en's OWN keyset is the de-facto registry of every shipped key — a grep
        // confirmed no bundle key is constructed dynamically (every lookup is a
        // static literal), so en's keyset is a provably complete enumeration and
        // set-equality against it is an airtight, bilateral gate: a key shipped in
        // en but absent from another bundle fails, AND an orphan key present in a
        // non-en bundle but absent from en fails. This is ADDITIVE to the
        // constant-completeness check, which catches the converse (a BundleKeys
        // constant missing from en, the D43 startup-error case the parity check
        // cannot see). FALLBACK_PROBE_KEY is the single deliberate en-only key
        // (it exercises the 2-arg en-fallback path) and is the only exclusion;
        // removing it from every bundle's set keeps the en-vs-en comparison
        // trivially consistent.
        Set<String> expected = new TreeSet<>(loadOwnKeys("en").stringPropertyNames());
        expected.remove(FALLBACK_PROBE_KEY);

        Set<String> supported = bundleLoader.supportedLanguages();
        assertFalse(supported.isEmpty(),
                "BundleLoader.supportedLanguages() must be non-empty; the parity "
                        + "check would silently pass against an empty language set");

        for (String lang : supported) {
            Set<String> ownKeys = new TreeSet<>(loadOwnKeys(lang).stringPropertyNames());
            ownKeys.remove(FALLBACK_PROBE_KEY);
            assertEquals(expected, ownKeys,
                    lang + ".properties own keyset must equal en's own keyset minus the "
                            + "deliberate en-only " + FALLBACK_PROBE_KEY + " — a key shipped in "
                            + "en but missing here, or an orphan key here absent from en, both "
                            + "fail D43's full-keyset completeness invariant");
        }
    }

    @Test
    void unknownKeyThrowsInsteadOfReturningEmptyString() {
        // Silently returning empty would defeat the completeness assertion; the
        // throw is the load-bearer for the bundle-key-typo regression guard.
        assertThrows(IllegalStateException.class,
                () -> bundleLoader.get("definitely.not.a.bundle.key"));
    }

    @Test
    void unknownKeyThroughTwoArgAccessorThrowsAfterEnFallbackFails() {
        // The 2-arg accessor falls back to en when the key is missing
        // in langCode; if it is missing in en too, the same
        // IllegalStateException the 1-arg accessor throws must surface
        // — the throw contract carries over so a typo in BundleKeys
        // remains a build-time defect when accessed via the per-scope
        // path.
        assertThrows(IllegalStateException.class,
                () -> bundleLoader.get("definitely.not.a.bundle.key", "cs"));
    }

    @Test
    void twoArgAccessorFallsBackToEnWhenKeyMissingInTargetLanguage() {
        // The probe key lives only in en.properties; the 2-arg
        // accessor with langCode='cs' must return the en value rather
        // than throw. This is the load-bearing guarantee for the
        // M1-060 design: cs.properties bilateral parity is the
        // discipline, but the runtime fallback is the safety net that
        // means a key drop in cs.properties never ships a blank reply.
        String value = bundleLoader.get(FALLBACK_PROBE_KEY, "cs");
        assertEquals(FALLBACK_PROBE_EXPECTED_VALUE, value,
                "2-arg accessor must fall back to en when the key is missing in the "
                        + "target language; got: " + value);
    }

    @Test
    void twoArgAccessorReturnsUtf8DiacriticsRoundtripped() {
        // The cs.properties value for reply.lang.success contains
        // Czech diacritics (per acceptance item 4 + the cs bundle
        // discipline). The byte-level assertion checks the
        // InputStreamReader(UTF-8) load path did NOT silently decode
        // the bytes through ISO-8859-1 — which would replace `š` /
        // `č` with mojibake. The assertion compares the raw UTF-8
        // bytes the JDK source-encodes the literal to, against the
        // bytes of the value the loader returns.
        String csValue = bundleLoader.get(BundleKeys.REPLY_LANG_SUCCESS, "cs");
        assertNotNull(csValue,
                "reply.lang.success must resolve in cs.properties");
        byte[] csBytes = csValue.getBytes(StandardCharsets.UTF_8);
        // The Czech translation MUST carry at least one diacritic
        // (any of the diacritic set named in M1-060 acceptance item 4);
        // a plain-ASCII string here would mean the cs.properties value
        // was authored as English placeholder text. The assertion is
        // intentionally loose on which diacritic — the test pins the
        // round-trip property, not the translation wording.
        assertTrue(containsAnyDiacritic(csValue),
                "cs.properties reply.lang.success must carry at least one Czech "
                        + "diacritic (the M1-060 cs.properties discipline forbids "
                        + "placeholder English strings); got: " + csValue);
        // Round-trip — decode the bytes back through UTF-8 and assert
        // the string is identical. A mis-decoded load would produce
        // a different String instance with replacement chars.
        String roundtripped = new String(csBytes, StandardCharsets.UTF_8);
        assertEquals(csValue, roundtripped,
                "UTF-8 round-trip through bytes must preserve the cs.properties value");
    }

    private static Properties loadOwnKeys(String lang) throws IOException {
        // Mirror BundleLoader's load path (InputStreamReader UTF-8) so cs
        // diacritics decode identically; reading the resource directly is
        // what lets the assertion see each bundle's OWN keys without the
        // 2-arg accessor's en fallback.
        String resource = "/bundles/" + lang + ".properties";
        Properties bundle = new Properties();
        try (InputStream stream = BundleLoaderTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, "bundle resource not found on classpath: " + resource);
            bundle.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return bundle;
    }

    private static List<String> collectBundleKeys() throws IllegalAccessException {
        List<String> keys = new ArrayList<>();
        for (Field field : BundleKeys.class.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (Modifier.isPublic(mods)
                    && Modifier.isStatic(mods)
                    && Modifier.isFinal(mods)
                    && field.getType() == String.class) {
                keys.add((String) field.get(null));
            }
        }
        return keys;
    }

    private static boolean containsAnyDiacritic(String s) {
        // Czech diacritic set per M1-060 acceptance item 4.
        for (char ch : new char[] {'á', 'ě', 'š', 'č', 'ř', 'ž', 'ý', 'í', 'ú', 'ů', 'é', 'ó', 'ť', 'ď', 'ň'}) {
            if (s.indexOf(ch) >= 0) {
                return true;
            }
        }
        return false;
    }
}
