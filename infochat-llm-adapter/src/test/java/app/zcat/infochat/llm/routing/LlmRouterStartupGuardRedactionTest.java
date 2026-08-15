package app.zcat.infochat.llm.routing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-423: the guard reads base-urls raw via {@code snapshotConfig} and echoes
 * them into its FATAL conflict message, its remote-embedding confirmation WARN,
 * and its per-task disclosure WARN. A credential-bearing base-url
 * ({@code https://user:pass@host/v1}) must never reach any of those surfaces
 * verbatim — the FATAL path is the sharpest because the guard throws to abort
 * boot before any provider's {@code requireHttpBaseUrl} (M1-401) gets a chance
 * to redact. Each base-url echo is routed through
 * {@code LlmHttpSupport.redactUserInfo}, so the credential span is masked.
 *
 * <p>Plain JUnit5 (no Quarkus boot), same direct
 * {@link LlmRouterStartupGuard#validateLocalOnlyConfiguration(Map)} seam and
 * {@link CapturingHandler} the sibling {@code LlmRouterStartupGuardLocalOnlyTest}
 * uses. The host {@code api.openai.com} is stubbed to a public address via the
 * resolver seam, so the assertions are stable offline.
 */
class LlmRouterStartupGuardRedactionTest {

    private static final String CREDENTIAL_BASE_URL = "https://user:pass@api.openai.com/v1";
    private static final String CREDENTIAL_SUBSTRING = "user:pass";
    private static final String REDACTED_HOST = "api.openai.com";

    // Every host resolves to one public address — see the sibling test.
    private static final LlmRouterStartupGuard.HostResolver REMOTE_RESOLVER = host ->
        new InetAddress[]{ InetAddress.getByAddress(new byte[]{8, 8, 8, 8}) };

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
    void localOnlyConflictMessageDoesNotLeakCredential() {
        Map<String, String> conflict = new LinkedHashMap<>();
        conflict.put(LlmRouterStartupGuard.CONFIG_KEY_LOCAL_ONLY, "true");
        conflict.put(LlmRouterStartupGuard.CONFIG_KEY_EMBEDDINGS_BASE_URL, CREDENTIAL_BASE_URL);

        LlmRouterStartupGuard.LocalOnlyConflictException ex = assertThrows(
            LlmRouterStartupGuard.LocalOnlyConflictException.class,
            () -> LlmRouterStartupGuard.validateLocalOnlyConfiguration(conflict),
            "a credential-bearing embedding base-url under local-only=true must throw");

        String msg = ex.getMessage();
        assertFalse(msg.contains(CREDENTIAL_SUBSTRING),
            "the conflict exception message must NOT contain the credential; got: " + msg);
        assertTrue(msg.contains(REDACTED_HOST),
            "the conflict message must still name the offending host; got: " + msg);
        assertTrue(msg.contains("***"),
            "the conflict message must carry the redaction marker; got: " + msg);
        // The FATAL line is logged as well as thrown — it must not leak either.
        assertNoCapturedRecordLeaksCredential();
    }

    @Test
    void remoteEmbeddingWarnDoesNotLeakCredential() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put(LlmRouterStartupGuard.CONFIG_KEY_LOCAL_ONLY, "false");
        snapshot.put(LlmRouterStartupGuard.CONFIG_KEY_EMBEDDINGS_BASE_URL, CREDENTIAL_BASE_URL);

        assertDoesNotThrow(
            () -> LlmRouterStartupGuard.validateLocalOnlyConfiguration(snapshot, REMOTE_RESOLVER),
            "a credential-bearing remote embedding base-url without local-only is allowed");

        List<LogRecord> warns = capturer.recordsAtLevel(Level.WARNING);
        assertTrue(warns.stream().anyMatch(r -> {
            String m = CapturingHandler.formatMessage(r);
            return m.contains(REDACTED_HOST) && m.contains("***") && m.contains("leave the host");
        }), "the remote-embedding WARN must name the redacted base-url and that post bodies "
            + "leave the host; captured: " + capturer.formattedAll());
        assertNoCapturedRecordLeaksCredential();
    }

    @Test
    void remoteTaskDisclosureWarnDoesNotLeakCredential() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put(LlmRouterStartupGuard.CONFIG_KEY_LOCAL_ONLY, "false");
        snapshot.put("infochat.llm.summarizer.base-url", CREDENTIAL_BASE_URL);

        assertDoesNotThrow(
            () -> LlmRouterStartupGuard.validateLocalOnlyConfiguration(snapshot, REMOTE_RESOLVER),
            "a credential-bearing per-task base-url without local-only is allowed");

        List<LogRecord> warns = capturer.recordsAtLevel(Level.WARNING);
        assertTrue(warns.stream().anyMatch(r -> {
            String m = CapturingHandler.formatMessage(r);
            return m.contains("SUMMARIZER") && m.contains(REDACTED_HOST)
                && m.contains("***") && m.contains("leave the host");
        }), "the per-task disclosure WARN must name the task and the redacted base-url; "
            + "captured: " + capturer.formattedAll());
        assertNoCapturedRecordLeaksCredential();
    }

    private void assertNoCapturedRecordLeaksCredential() {
        for (LogRecord record : capturer.records) {
            String m = CapturingHandler.formatMessage(record);
            assertFalse(m.contains(CREDENTIAL_SUBSTRING),
                "no log record may contain the credential; leaking record: " + m);
        }
    }

    static {
        // Mirror the sibling test's bootstrap pin: Quarkus can leave
        // WARNING-and-above on the class logger suppressed depending on the
        // LogManager bootstrap order, so pin ALL before the @BeforeEach attach.
        Logger.getLogger(LlmRouterStartupGuard.class.getName()).setLevel(Level.ALL);
    }
}
