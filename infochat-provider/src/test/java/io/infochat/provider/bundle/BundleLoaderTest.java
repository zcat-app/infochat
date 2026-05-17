package io.infochat.provider.bundle;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Bundle-completeness CI check for {@link BundleLoader} per
 * {@code docs/spec/commands.md} §Discovery /help (Bundle composition).
 *
 * <p>The completeness assertion is the cross-ticket invariant that
 * keeps {@link BundleKeys} and {@code en.properties} in lock-step
 * as the bundle grows in T1-F, T2-A, T2-B, T2-C. It iterates every
 * {@code public static final String} constant declared on
 * {@link BundleKeys} via reflection and asserts each resolves to a
 * non-empty value in the loaded bundle. Adding a new constant to
 * {@link BundleKeys} automatically extends this check at the next
 * test run — no test edit required, which is the property that
 * makes the discipline durable.</p>
 *
 * <p>The unknown-key test is the regression guard against silently
 * empty output. {@link BundleLoader#get(String)} throws on a missing
 * key; if a future edit relaxed that to "return null" or "return
 * empty string," this test would fail and surface the regression
 * before it shipped.</p>
 */
@QuarkusTest
class BundleLoaderTest {

    @Inject
    BundleLoader bundleLoader;

    @Test
    void everyBundleKeysConstantResolvesInEnPropertiesToANonEmptyString() throws Exception {
        // Reflect over BundleKeys to enumerate every key constant.
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
        assertFalse(keys.isEmpty(),
                "BundleKeys must declare at least one public static final String constant; "
                        + "the reflection check is the load-bearing CI guard and an empty key set "
                        + "would silently pass");

        for (String key : keys) {
            String value = bundleLoader.get(key);
            assertNotNull(value, "bundle key resolved to null: " + key);
            assertFalse(value.isEmpty(),
                    "bundle key resolved to empty string: " + key
                            + " — bundle-completeness CI check requires every BundleKeys constant "
                            + "to have a non-empty en.properties value");
        }
    }

    @Test
    void unknownKeyThrowsInsteadOfReturningEmptyString() {
        // Silently returning empty would defeat the completeness assertion; the
        // throw is the load-bearer for the bundle-key-typo regression guard.
        assertThrows(IllegalStateException.class,
                () -> bundleLoader.get("definitely.not.a.bundle.key"));
    }
}
