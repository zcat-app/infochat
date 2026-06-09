package app.zcat.infochat.llm.routing;

import app.zcat.infochat.llm.impl.AnthropicProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-136: verifies the broadened local-only startup guard. Beyond the
 * per-task base-url scan covered by {@code LocalOnlyConflictStartupIT}
 * (in {@code infochat-collector}), the guard must also:
 *
 * <ol>
 *   <li>Fail startup when {@code infochat.llm.local-only=true} and
 *       {@code infochat.embeddings.base-url} points off-host — a remote
 *       embedding endpoint ships post title+summary off the host
 *       (acceptance item 2).</li>
 *   <li>Fail startup when a per-task provider override names a cloud-only
 *       provider ({@code anthropic}) under local-only, naming the
 *       offending task and provider.</li>
 *   <li>Emit the spec-promised confirmation log line when the embedding
 *       endpoint is remote and local-only is NOT set, so operators see
 *       when post bodies start leaving the host (acceptance item 3).</li>
 * </ol>
 *
 * <p>Plain JUnit5 (no Quarkus boot): the guard's pure-function
 * {@link LlmRouterStartupGuard#validateLocalOnlyConfiguration(Map)} is
 * invoked directly with hand-rolled snapshots — the same seam the
 * collector IT uses. A remote host literal ({@code api.openai.com}) is
 * treated as non-loopback whether or not DNS resolves it (a failed
 * lookup also counts as non-loopback), so the assertions are stable
 * offline.
 */
class LlmRouterStartupGuardLocalOnlyTest {

    private static final String REMOTE_BASE_URL = "https://api.openai.com/v1";
    private static final String LOOPBACK_BASE_URL = "http://localhost:11434/v1";

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
    void localOnlyTrueWithRemoteEmbeddingBaseUrlRefusesStartup() {
        Map<String, String> conflict = new LinkedHashMap<>();
        conflict.put(LlmRouterStartupGuard.CONFIG_KEY_LOCAL_ONLY, "true");
        conflict.put(LlmRouterStartupGuard.CONFIG_KEY_EMBEDDINGS_BASE_URL, REMOTE_BASE_URL);

        LlmRouterStartupGuard.LocalOnlyConflictException ex = assertThrows(
            LlmRouterStartupGuard.LocalOnlyConflictException.class,
            () -> LlmRouterStartupGuard.validateLocalOnlyConfiguration(conflict),
            "remote embedding base-url under local-only=true must throw");
        String msg = ex.getMessage();
        assertTrue(msg.contains(LlmRouterStartupGuard.CONFIG_KEY_EMBEDDINGS_BASE_URL),
            "fatal message must name the embedding base-url key; got: " + msg);
        assertTrue(msg.contains(REMOTE_BASE_URL),
            "fatal message must name the offending base-url; got: " + msg);
        assertTrue(msg.contains("local-only"),
            "fatal message must mention local-only; got: " + msg);
    }

    @Test
    void localOnlyTrueWithRemoteProviderOverrideRefusesStartup() {
        // Per-task provider override naming a cloud-only provider is a
        // conflict even when that task's base-url is loopback (or unset):
        // the operator selected a remote provider while claiming local-only.
        Map<String, String> conflict = new LinkedHashMap<>();
        conflict.put(LlmRouterStartupGuard.CONFIG_KEY_LOCAL_ONLY, "true");
        conflict.put("infochat.llm.chat.provider", AnthropicProvider.PROVIDER_NAME);

        LlmRouterStartupGuard.LocalOnlyConflictException ex = assertThrows(
            LlmRouterStartupGuard.LocalOnlyConflictException.class,
            () -> LlmRouterStartupGuard.validateLocalOnlyConfiguration(conflict),
            "remote provider override under local-only=true must throw");
        String msg = ex.getMessage();
        assertTrue(msg.contains("CHAT_AGENT"),
            "fatal message must name the offending task; got: " + msg);
        assertTrue(msg.contains(AnthropicProvider.PROVIDER_NAME),
            "fatal message must name the offending provider; got: " + msg);
        assertTrue(msg.contains("infochat.llm.chat.provider"),
            "fatal message must name the offending provider key; got: " + msg);
    }

    @Test
    void localOnlyTrueWithRemoteDefaultProviderRefusesStartup() {
        // The default provider is the route every task without a per-task
        // override resolves to — a cloud-only default under local-only
        // must produce the same conflict error as a per-task override.
        Map<String, String> conflict = new LinkedHashMap<>();
        conflict.put(LlmRouterStartupGuard.CONFIG_KEY_LOCAL_ONLY, "true");
        conflict.put(LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER, AnthropicProvider.PROVIDER_NAME);

        LlmRouterStartupGuard.LocalOnlyConflictException ex = assertThrows(
            LlmRouterStartupGuard.LocalOnlyConflictException.class,
            () -> LlmRouterStartupGuard.validateLocalOnlyConfiguration(conflict),
            "remote default provider under local-only=true must throw");
        String msg = ex.getMessage();
        assertTrue(msg.contains(LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER),
            "fatal message must name the default-provider key; got: " + msg);
        assertTrue(msg.contains(AnthropicProvider.PROVIDER_NAME),
            "fatal message must name the offending provider; got: " + msg);
        assertTrue(msg.contains("local-only"),
            "fatal message must mention local-only; got: " + msg);
    }

    @Test
    void localOnlyTrueWithRemoteProviderReachableViaLanguagesKeyRefusesStartup() {
        // A cloud-only provider declaring a non-English language is
        // selectable through the router's priority-2 capability branch
        // with no override or default naming it — the same conflict as
        // an explicit override, previously invisible to the guard.
        Map<String, String> conflict = new LinkedHashMap<>();
        conflict.put(LlmRouterStartupGuard.CONFIG_KEY_LOCAL_ONLY, "true");
        conflict.put("infochat.llm.anthropic.languages", "en,cs");

        LlmRouterStartupGuard.LocalOnlyConflictException ex = assertThrows(
            LlmRouterStartupGuard.LocalOnlyConflictException.class,
            () -> LlmRouterStartupGuard.validateLocalOnlyConfiguration(conflict),
            "remote provider reachable via the languages key under local-only=true must throw");
        String msg = ex.getMessage();
        assertTrue(msg.contains("infochat.llm.anthropic.languages"),
            "fatal message must name the offending languages key; got: " + msg);
        assertTrue(msg.contains(AnthropicProvider.PROVIDER_NAME),
            "fatal message must name the offending provider; got: " + msg);
        assertTrue(msg.contains("languages=cs"),
            "fatal message must list only the non-English reachable languages; got: " + msg);
    }

    @Test
    void localOnlyTrueWithEnglishOnlyLanguagesDeclarationDoesNotThrow() {
        // The priority-2 branch never runs for "en" scopes, so an
        // en-only declaration does not make the remote provider
        // reachable — flagging it would over-constrain valid configs.
        Map<String, String> ok = new LinkedHashMap<>();
        ok.put(LlmRouterStartupGuard.CONFIG_KEY_LOCAL_ONLY, "true");
        ok.put("infochat.llm.anthropic.languages", "en");

        assertDoesNotThrow(
            () -> LlmRouterStartupGuard.validateLocalOnlyConfiguration(ok),
            "an en-only languages declaration is unreachable via priority 2 and must NOT throw");
    }

    @Test
    void localOnlyTrueAllOnHostDoesNotThrow() {
        // Loopback embedding + loopback base-url + the host-neutral
        // openai-compatible provider — nothing leaves the host.
        Map<String, String> ok = new LinkedHashMap<>();
        ok.put(LlmRouterStartupGuard.CONFIG_KEY_LOCAL_ONLY, "true");
        ok.put(LlmRouterStartupGuard.CONFIG_KEY_EMBEDDINGS_BASE_URL, LOOPBACK_BASE_URL);
        ok.put("infochat.llm.security.base-url", LOOPBACK_BASE_URL);
        ok.put("infochat.llm.chat.provider", "openai-compatible");
        assertDoesNotThrow(
            () -> LlmRouterStartupGuard.validateLocalOnlyConfiguration(ok),
            "loopback embedding + base-url + host-neutral provider must NOT throw");
    }

    @Test
    void remoteEmbeddingWithoutLocalOnlyEmitsConfirmationLog() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put(LlmRouterStartupGuard.CONFIG_KEY_LOCAL_ONLY, "false");
        snapshot.put(LlmRouterStartupGuard.CONFIG_KEY_EMBEDDINGS_BASE_URL, REMOTE_BASE_URL);

        assertDoesNotThrow(
            () -> LlmRouterStartupGuard.validateLocalOnlyConfiguration(snapshot),
            "remote embedding without local-only is allowed (must NOT throw)");

        List<LogRecord> warns = capturer.recordsAtLevel(Level.WARNING);
        assertTrue(warns.stream().anyMatch(r -> {
            String m = formatMessage(r);
            return m.contains(REMOTE_BASE_URL) && m.contains("leave the host");
        }), "remote embedding without local-only must emit a confirmation log "
            + "naming the base-url and that post bodies leave the host; captured: "
            + capturer.formattedAll());
    }

    @Test
    void loopbackEmbeddingWithoutLocalOnlyEmitsNoConfirmationLog() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put(LlmRouterStartupGuard.CONFIG_KEY_LOCAL_ONLY, "false");
        snapshot.put(LlmRouterStartupGuard.CONFIG_KEY_EMBEDDINGS_BASE_URL, LOOPBACK_BASE_URL);

        assertDoesNotThrow(
            () -> LlmRouterStartupGuard.validateLocalOnlyConfiguration(snapshot),
            "loopback embedding without local-only must NOT throw");

        assertTrue(capturer.recordsAtLevel(Level.WARNING).isEmpty(),
            "loopback embedding must NOT emit a confirmation log; captured: "
                + capturer.formattedAll());
    }

    private static String formatMessage(LogRecord record) {
        String raw = record.getMessage();
        if (raw == null) {
            return "";
        }
        Object[] params = record.getParameters();
        if (params == null || params.length == 0) {
            return raw;
        }
        return String.format(raw, params);
    }

    /**
     * Minimal JUL handler that records every {@link LogRecord} the logger
     * emits — supports both the JBoss-LogManager bootstrap (Quarkus
     * production) and the stock JUL bootstrap (plain JUnit5). Identical
     * pattern to {@code LlmRouterUnknownDefaultTest}'s CapturingHandler.
     */
    private static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
            // no-op
        }

        @Override
        public void close() {
            // no-op
        }

        List<LogRecord> recordsAtLevel(Level level) {
            return records.stream()
                .filter(r -> r.getLevel().intValue() >= level.intValue())
                .toList();
        }

        String formattedAll() {
            StringBuilder sb = new StringBuilder();
            for (LogRecord r : records) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(r.getLevel()).append(": ").append(formatMessage(r));
            }
            return sb.toString();
        }
    }

    static {
        // Quarkus tests can leave WARNING-and-above on the root logger
        // suppressed depending on the LogManager bootstrap order. Pin
        // ALL on the class logger explicitly so the @BeforeEach attach
        // captures every record.
        Logger.getLogger(LlmRouterStartupGuard.class.getName()).setLevel(Level.ALL);
    }
}
