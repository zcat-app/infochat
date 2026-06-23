package app.zcat.infochat.llm.routing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import app.zcat.infochat.llm.impl.AnthropicProvider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-432: the non-local-only disclosure WARN must cover the language-capability
 * route, not only per-task overrides and the default provider. A cloud-only
 * provider made reachable ONLY via its non-English {@code languages} key (the
 * router's priority-2 branch, with no override or default naming it) routes
 * non-English summarizer/translator output off-host — the exact route the
 * local-only FATAL branch already treats as an offender. Without this WARN the
 * two postures drift on what counts as off-host.
 *
 * <p>Plain JUnit5 (no Quarkus boot), same direct
 * {@link LlmRouterStartupGuard#validateLocalOnlyConfiguration(Map)} seam and
 * {@link CapturingHandler} the sibling {@code LlmRouterStartupGuardRedactionTest}
 * uses. {@code anthropic} is the only member of {@code REMOTE_PROVIDER_NAMES};
 * its languages key is resolved through the guard's own
 * {@link LlmRouterStartupGuard#languagesKeyFor(String)} seam so the test cannot
 * drift from the key the production path reads.
 */
class LlmRouterStartupGuardLanguageRouteDisclosureTest {

    private static final String LANGUAGES_KEY =
        LlmRouterStartupGuard.languagesKeyFor(AnthropicProvider.PROVIDER_NAME);
    private static final String LANGUAGE_ROUTE_MARKER = "reachable via languages";

    private Logger jul;
    private CapturingHandler capturer;

    @BeforeEach
    void attachLogHandler() {
        jul = Logger.getLogger(LlmRouterStartupGuard.class.getName());
        capturer = new CapturingHandler();
        capturer.setLevel(Level.ALL);
        jul.addHandler(capturer);
        jul.setLevel(Level.ALL);
    }

    @AfterEach
    void detachLogHandler() {
        jul.removeHandler(capturer);
    }

    @Test
    void nonEnglishLanguagesKeyAloneEmitsDisclosureWarn() {
        // local-only unset, no per-task override, no default — the remote
        // provider is reachable ONLY through its non-English languages key.
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put(LlmRouterStartupGuard.CONFIG_KEY_LOCAL_ONLY, "false");
        snapshot.put(LANGUAGES_KEY, "cs");

        assertDoesNotThrow(
            () -> LlmRouterStartupGuard.validateLocalOnlyConfiguration(snapshot),
            "a non-English languages key without local-only is allowed (but loud)");

        List<LogRecord> warns = capturer.recordsAtLevel(Level.WARNING);
        assertTrue(warns.stream().anyMatch(r -> {
            String m = CapturingHandler.formatMessage(r);
            return m.contains(LANGUAGE_ROUTE_MARKER)
                && m.contains(AnthropicProvider.PROVIDER_NAME)
                && m.contains("cs")
                && m.contains("leave the host");
        }), "the language-route disclosure WARN must name the provider and the reachable "
            + "non-English language; captured: " + capturer.formattedAll());
    }

    @Test
    void englishOnlyLanguagesKeyEmitsNoLanguageRouteWarn() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put(LlmRouterStartupGuard.CONFIG_KEY_LOCAL_ONLY, "false");
        snapshot.put(LANGUAGES_KEY, "en");

        assertDoesNotThrow(() -> LlmRouterStartupGuard.validateLocalOnlyConfiguration(snapshot));

        assertNoLanguageRouteWarn();
    }

    @Test
    void absentLanguagesKeyEmitsNoLanguageRouteWarn() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put(LlmRouterStartupGuard.CONFIG_KEY_LOCAL_ONLY, "false");

        assertDoesNotThrow(() -> LlmRouterStartupGuard.validateLocalOnlyConfiguration(snapshot));

        assertNoLanguageRouteWarn();
    }

    private void assertNoLanguageRouteWarn() {
        for (LogRecord record : capturer.records) {
            String m = CapturingHandler.formatMessage(record);
            assertFalse(m.contains(LANGUAGE_ROUTE_MARKER),
                "no language-route disclosure WARN must be emitted; leaking record: " + m);
        }
    }

    static {
        // Mirror the sibling test's bootstrap pin: Quarkus can leave
        // WARNING-and-above on the class logger suppressed depending on the
        // LogManager bootstrap order, so pin ALL before the @BeforeEach attach.
        Logger.getLogger(LlmRouterStartupGuard.class.getName()).setLevel(Level.ALL);
    }
}
