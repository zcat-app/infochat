package app.zcat.infochat.llm.routing;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-042 item 1: verifies the priority-3 audit-loud-fallback posture.
 * When {@code infochat.llm.default.provider} is set to a name that
 * resolves to no registered {@link LlmProvider}, {@link LlmRouter#forTask}
 * MUST log a one-shot WARN naming the configured value AND the
 * registered provider set AND the fallback before returning
 * {@code entries.get(0).provider()} — the prior silent fallback (M1-033)
 * would route every Stage 2 call to whatever CDI happened to list first.
 *
 * <p>Three behavioral assertions:
 * <ol>
 *   <li>The fallback IS still {@code entries.get(0).provider()} —
 *       availability is preserved (M1-042 picks audit-loud-fallback
 *       over fail-startup).</li>
 *   <li>The WARN message contains the configured value, the registered
 *       set, the fallback name, AND the literal phrase
 *       {@code "unknown default provider"} (the
 *       {@code SECURITY-FLAG-CONSISTENT} grep clause on
 *       {@link LlmRouter}'s source pins this verbatim).</li>
 *   <li>The WARN is emitted EXACTLY ONCE across N invocations — the
 *       {@link LlmRouter#warnedUnknownDefault} {@link java.util.concurrent.atomic.AtomicBoolean}
 *       one-shot guard prevents log flooding when the misconfiguration
 *       persists for the JVM's lifetime.</li>
 * </ol>
 *
 * <p>A fourth assertion covers the negative case: when
 * {@code infochat.llm.default.provider} is UNSET and the implicit
 * default ({@link app.zcat.infochat.llm.impl.OpenAiCompatibleProvider#PROVIDER_NAME})
 * matches no registered entry, the fallback fires WITHOUT a WARN. This
 * is the legacy test-fixture path (e.g. {@code Stage2WorkerIT.TestStubLlmProvider}
 * registered under its class simple-name) — promoting it to WARN would
 * spam every test boot.
 */
class LlmRouterUnknownDefaultTest {

    private static final String NAME_REGISTERED = "registered-provider";
    private static final String NAME_UNKNOWN = "unknown-provider";

    private Logger jul;
    private CapturingHandler capturer;

    @BeforeEach
    void attachLogHandler() {
        jul = Logger.getLogger(LlmRouter.class.getName());
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
    void unknownConfiguredDefaultProviderFallsBackToFirstEntryWithOneShotWarn() {
        StubProvider registered = new StubProvider();
        LlmRouter router = new LlmRouter(
            List.of(new LlmRouter.Entry(NAME_REGISTERED, registered, Set.of("en"))),
            LlmRouter.ConfigReader.fromMap(Map.of(
                LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER, NAME_UNKNOWN)));

        // Drive the priority-3 branch N times — only one WARN should fire.
        LlmProvider first = router.forTask(ModelTask.SECURITY_JUDGE, "en");
        LlmProvider second = router.forTask(ModelTask.SECURITY_JUDGE, "en");
        LlmProvider third = router.forTask(ModelTask.TAGGER, "en");

        assertSame(registered, first,
            "fallback must return the first registered entry's provider");
        assertSame(registered, second, "fallback resolves consistently on repeat calls");
        assertSame(registered, third, "fallback resolves for every task");

        List<LogRecord> warns = capturer.recordsAtLevel(Level.WARNING);
        assertEquals(1, warns.size(),
            "unknown-default WARN must be emitted EXACTLY ONCE per JVM; captured: "
                + capturer.formattedAll());

        String message = CapturingHandler.formatMessage(warns.get(0));
        assertTrue(message.contains(NAME_UNKNOWN),
            "WARN must name the configured value '" + NAME_UNKNOWN
                + "'; got: " + message);
        assertTrue(message.contains(NAME_REGISTERED),
            "WARN must name the registered provider '" + NAME_REGISTERED
                + "' (so the operator can see what is available); got: " + message);
        assertTrue(message.contains("unknown default provider"),
            "WARN message must contain the verbatim phrase "
                + "'unknown default provider' (M1-042 acceptance grep clause); got: "
                + message);
        assertTrue(message.contains("falling back"),
            "WARN must name the fallback action; got: " + message);
    }

    @Test
    void unconfiguredDefaultProviderFallsBackSilently() {
        // Mirrors the legacy test-fixture path: no
        // infochat.llm.default.provider is set, so the implicit default
        // is OpenAiCompatibleProvider.PROVIDER_NAME — which is not
        // registered in this stub-only fixture. The fallback fires but
        // the WARN MUST NOT, or every test boot would spam.
        StubProvider registered = new StubProvider();
        LlmRouter router = new LlmRouter(
            List.of(new LlmRouter.Entry(NAME_REGISTERED, registered, Set.of("en"))),
            LlmRouter.ConfigReader.fromMap(Map.of()));

        LlmProvider resolved = router.forTask(ModelTask.SECURITY_JUDGE, "en");

        assertSame(registered, resolved,
            "unconfigured-default + name-mismatch must still fall back to first entry");

        List<LogRecord> warns = capturer.recordsAtLevel(Level.WARNING);
        assertTrue(warns.isEmpty(),
            "unconfigured default must NOT emit a WARN; captured: "
                + capturer.formattedAll());
    }

    @Test
    void unknownConfiguredDefaultDoesNotWarnWhenPerTaskOverrideResolves() {
        // The priority-1 per-task override resolves before priority-3
        // ever runs — so an unknown default-provider name does NOT
        // produce a WARN as long as every call has a per-task override.
        StubProvider registered = new StubProvider();
        StubProvider override = new StubProvider();
        LlmRouter router = new LlmRouter(
            List.of(
                new LlmRouter.Entry(NAME_REGISTERED, registered, Set.of("en")),
                new LlmRouter.Entry("override-name", override, Set.of("en"))),
            LlmRouter.ConfigReader.fromMap(Map.of(
                LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER, NAME_UNKNOWN,
                "infochat.llm.security.provider", "override-name")));

        LlmProvider resolved = router.forTask(ModelTask.SECURITY_JUDGE, "en");

        assertSame(override, resolved, "per-task override must win over priority-3");
        assertTrue(capturer.recordsAtLevel(Level.WARNING).isEmpty(),
            "priority-3 WARN must NOT fire when priority-1 resolves");
    }

    /**
     * Lightweight test stub: implements {@link LlmProvider} so the
     * router's resolution chain can be exercised end-to-end without
     * pulling Quarkus or constructing a concrete provider impl. The
     * stub's {@link #generate} is never invoked by router-resolution
     * tests — only the resolution path is under test.
     */
    private static final class StubProvider implements LlmProvider {
        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            throw new UnsupportedOperationException(
                "StubProvider.generate must not be invoked by router-resolution tests");
        }
    }

    static {
        // Quarkus tests can leave WARNING-and-above on the root logger
        // suppressed depending on the LogManager bootstrap order. Pin
        // ALL on the class logger explicitly so the @BeforeEach attach
        // captures every record.
        Logger.getLogger(LlmRouter.class.getName()).setLevel(Level.ALL);
    }
}
