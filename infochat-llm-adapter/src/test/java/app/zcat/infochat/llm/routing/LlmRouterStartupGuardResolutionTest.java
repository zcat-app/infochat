package app.zcat.infochat.llm.routing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-835: a configured LLM/embedding endpoint whose host does not resolve
 * at boot is a backend-absent deployment running its documented degraded
 * mode (docs/spec/deployment.md §Bootstrap behavior on startup). The guard
 * must surface THAT at ERROR level naming the config key, must not abort
 * startup (the absent backend is a supported degraded mode, not a
 * conflict), and must not frame the dead endpoint as a remote route —
 * "post bodies will leave the host" is false when nothing can reach the
 * host. Under local-only=true the same unresolvable route still refuses
 * startup: the privacy gate fails closed on a host that cannot be proven
 * on-host (docs/spec/llm.md §Per-task routing rules).
 *
 * <p>Plain JUnit5 (no Quarkus boot), same direct-validator +
 * {@link CapturingHandler} pattern as the sibling guard tests, with the
 * resolver seam ({@link LlmRouterStartupGuard.HostResolver}) stubbed to
 * fail (or count) so the DNS outcomes are deterministic offline and on.
 */
class LlmRouterStartupGuardResolutionTest {

    private static final String DEAD_HOST = "backend.invalid";
    private static final String DEAD_EMBEDDING_URL = "https://user:pass@" + DEAD_HOST + "/v1";

    private static final LlmRouterStartupGuard.HostResolver DEAD_RESOLVER = host -> {
        throw new UnknownHostException(host);
    };

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
    void unresolvableConfiguredEndpointSignalsBackendAbsentWithoutAborting() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put(LlmRouterStartupGuard.CONFIG_KEY_LOCAL_ONLY, "false");
        snapshot.put(LlmRouterStartupGuard.CONFIG_KEY_EMBEDDINGS_BASE_URL, DEAD_EMBEDDING_URL);

        assertDoesNotThrow(
            () -> LlmRouterStartupGuard.validateLocalOnlyConfiguration(snapshot, DEAD_RESOLVER),
            "an unresolvable endpoint without local-only is the documented degraded mode, "
                + "not a startup conflict");

        List<LogRecord> errors = capturer.recordsAtLevel(Level.SEVERE);
        assertTrue(errors.stream().anyMatch(r -> {
            String m = CapturingHandler.formatMessage(r);
            return m.contains(LlmRouterStartupGuard.CONFIG_KEY_EMBEDDINGS_BASE_URL)
                && m.contains(DEAD_HOST)
                && m.contains("***")
                && m.contains("degraded");
        }), "an unresolvable configured endpoint must emit an ERROR line naming the config "
            + "key, the redacted URL, and the degraded-mode consequence; captured: "
            + capturer.formattedAll());
        assertFalse(capturer.formattedAll().contains("user:pass"),
            "no log record may contain the credential");
    }

    @Test
    void unresolvableHostIsNotReportedAsRemoteRoute() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put(LlmRouterStartupGuard.CONFIG_KEY_LOCAL_ONLY, "false");
        snapshot.put(LlmRouterStartupGuard.CONFIG_KEY_EMBEDDINGS_BASE_URL, DEAD_EMBEDDING_URL);

        assertDoesNotThrow(
            () -> LlmRouterStartupGuard.validateLocalOnlyConfiguration(snapshot, DEAD_RESOLVER));

        for (LogRecord record : capturer.records) {
            String m = CapturingHandler.formatMessage(record);
            assertFalse(m.contains(DEAD_HOST) && m.contains("leave the host"),
                "an unresolvable host must never be framed as a remote route whose post "
                    + "bodies leave the host; offending record: " + m);
        }
    }

    @Test
    void unresolvableHostUnderLocalOnlyStillRefusesStartup() {
        Map<String, String> conflict = new LinkedHashMap<>();
        conflict.put(LlmRouterStartupGuard.CONFIG_KEY_LOCAL_ONLY, "true");
        conflict.put(LlmRouterStartupGuard.CONFIG_KEY_EMBEDDINGS_BASE_URL, DEAD_EMBEDDING_URL);

        LlmRouterStartupGuard.LocalOnlyConflictException ex = assertThrows(
            LlmRouterStartupGuard.LocalOnlyConflictException.class,
            () -> LlmRouterStartupGuard.validateLocalOnlyConfiguration(conflict, DEAD_RESOLVER),
            "an unresolvable route under local-only=true must still refuse startup "
                + "(the privacy gate fails closed on a host that cannot be proven on-host)");
        String msg = ex.getMessage();
        assertTrue(msg.contains(LlmRouterStartupGuard.CONFIG_KEY_EMBEDDINGS_BASE_URL),
            "the fatal message must name the embedding key; got: " + msg);
        assertTrue(msg.contains(DEAD_HOST),
            "the fatal message must name the unresolvable host; got: " + msg);
    }

    @Test
    void eachDistinctHostIsResolvedOnce() {
        Map<String, Integer> resolutions = new LinkedHashMap<>();
        LlmRouterStartupGuard.HostResolver counting = host -> {
            resolutions.merge(host, 1, Integer::sum);
            throw new UnknownHostException(host);
        };
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put(LlmRouterStartupGuard.CONFIG_KEY_LOCAL_ONLY, "false");
        snapshot.put(LlmRouterStartupGuard.CONFIG_KEY_EMBEDDINGS_BASE_URL, DEAD_EMBEDDING_URL);
        snapshot.put(LlmRouter.CONFIG_KEY_DEFAULT_BASE_URL, "https://" + DEAD_HOST + "/v1");
        snapshot.put("infochat.llm.summarizer.base-url", "https://" + DEAD_HOST + "/v1");
        snapshot.put("infochat.llm.translator.base-url", "https://other.invalid/v1");

        assertDoesNotThrow(
            () -> LlmRouterStartupGuard.validateLocalOnlyConfiguration(snapshot, counting));

        assertEquals(2, resolutions.size(),
            "exactly the two distinct configured hosts may be resolved; got: " + resolutions);
        assertEquals(1, resolutions.get(DEAD_HOST),
            "the coinciding embeddings/default/per-task host must be resolved exactly once; "
                + "got: " + resolutions);
        assertEquals(1, resolutions.get("other.invalid"),
            "the second distinct host must be resolved exactly once; got: " + resolutions);
        assertEquals(1, absentLinesMentioning(DEAD_HOST),
            "at most one backend-absent line per distinct host (coinciding keys deduped); "
                + "captured: " + capturer.formattedAll());
        assertEquals(1, absentLinesMentioning("other.invalid"),
            "at most one backend-absent line per distinct host; captured: "
                + capturer.formattedAll());
    }

    @Test
    void malformedSharedDefaultWarnsOncePerScan() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put(LlmRouterStartupGuard.CONFIG_KEY_LOCAL_ONLY, "false");
        snapshot.put(LlmRouterStartupGuard.CONFIG_KEY_EMBEDDINGS_BASE_URL, "https://dead embed.invalid/v1");
        snapshot.put(LlmRouter.CONFIG_KEY_DEFAULT_BASE_URL, "ht!tp://broken.default/v1");

        assertDoesNotThrow(
            () -> LlmRouterStartupGuard.validateLocalOnlyConfiguration(snapshot, DEAD_RESOLVER),
            "a malformed base-url without local-only is allowed (treated as remote, loud)");

        List<String> malformed = capturer.recordsAtLevel(Level.WARNING).stream()
            .map(CapturingHandler::formatMessage)
            .filter(m -> m.contains("malformed base-url"))
            .toList();
        assertEquals(2, malformed.size(),
            "each distinct malformed base-url logs its parse-failure WARN exactly once per "
                + "scan, no matter how many keys inherit it; captured: " + capturer.formattedAll());
        assertTrue(malformed.stream().anyMatch(m -> m.contains("dead embed.invalid")),
            "the malformed embeddings URL gets its one WARN; captured: " + malformed);
        assertTrue(malformed.stream().anyMatch(m -> m.contains("broken.default")),
            "the malformed shared default gets its one WARN (not one per inheriting task); "
                + "captured: " + malformed);
    }

    private long absentLinesMentioning(String host) {
        return capturer.recordsAtLevel(Level.SEVERE).stream()
            .map(CapturingHandler::formatMessage)
            .filter(m -> m.contains(host) && m.contains("does not resolve"))
            .count();
    }

    static {
        // Mirror the sibling test's bootstrap pin: Quarkus can leave
        // WARNING-and-above on the class logger suppressed depending on the
        // LogManager bootstrap order, so pin ALL before the @BeforeEach attach.
        Logger.getLogger(LlmRouterStartupGuard.class.getName()).setLevel(Level.ALL);
    }
}
