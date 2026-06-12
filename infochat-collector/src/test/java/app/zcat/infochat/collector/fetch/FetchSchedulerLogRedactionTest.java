package app.zcat.infochat.collector.fetch;

import app.zcat.infochat.collector.fetcher.rss.RssFetcher.RssFetchException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-042 item 3: verifies {@link FetchScheduler#logFetchFailure}
 * runs the exception chain through {@code UrlRedactor} before emitting
 * the WARN, so a wrapped {@link RssFetchException} whose root-cause
 * {@link IOException} message contains a URL with embedded userinfo
 * (e.g. {@code https://user:secret@example.com/feed}) emerges with
 * the {@code user:secret@} segment stripped.
 *
 * <p>Three layers of redaction tested:
 * <ol>
 *   <li><b>End-to-end log capture.</b> The acceptance criterion forces
 *       a fetcher failure with the credential URL in the root cause,
 *       drives the {@code logFetchFailure} path, captures the SLF4J
 *       output through a JUL {@link Handler}, and asserts that the
 *       literal {@code "secret"} appears in NO emitted line. This is
 *       the load-bearing test for the M1-042 INFO-LEAK fix.</li>
 *   <li><b>Helper-level redaction.</b>
 *       {@link FetchScheduler#redactUrlsInText} replaces only the
 *       URL substring; non-URL text is preserved verbatim. The test
 *       exercises both branches of the matcher (URL present + URL
 *       absent) to pin the helper's contract.</li>
 *   <li><b>Cycle-safe chain walk.</b>
 *       {@link FetchScheduler#exceptionChainMessage} terminates on a
 *       self-referential cause cycle (an exception whose
 *       {@code getCause()} closure includes itself or an ancestor)
 *       without an infinite loop — the {@code IdentityHashMap}-based
 *       visited set is the load-bearing guard.</li>
 * </ol>
 *
 * <p>Drives {@link FetchScheduler#logFetchFailure} directly rather
 * than the full {@link FetchScheduler#tickOnce} dependency graph
 * (Fetcher + PostPersister + EvalQueueProducer + SourceRepository +
 * ThrottledAdminNotifier): the redaction contract is local to the
 * helper, and the package-private seam lets us exercise it without
 * wiring six stubs.
 */
class FetchSchedulerLogRedactionTest {

    private static final String CREDENTIAL_URL =
        "https://user:secret@example.com/feed";

    private Logger jul;
    private Logger jbossJul;
    private CapturingHandler capturer;

    @BeforeEach
    void attachLogHandler() {
        capturer = new CapturingHandler();
        capturer.setLevel(Level.ALL);
        // Mirror InboundRouterContactIdRedactionTest's dual-attach:
        // depending on whether jboss-log-manager has bootstrapped
        // first, records flow through the class JUL logger and / or
        // the "org.jboss.logging" tree. Attaching to both guarantees
        // the capture works regardless of the LogManager ordering
        // the surefire JVM happened to pick.
        jul = Logger.getLogger(FetchScheduler.class.getName());
        jul.setLevel(Level.ALL);
        jul.addHandler(capturer);
        jbossJul = Logger.getLogger("org.jboss.logging");
        jbossJul.setLevel(Level.ALL);
        jbossJul.addHandler(capturer);
    }

    @AfterEach
    void detachLogHandler() {
        jul.removeHandler(capturer);
        jbossJul.removeHandler(capturer);
    }

    @Test
    void logFetchFailureRedactsUserinfoFromRootCauseUrl() {
        FetchScheduler scheduler = new FetchScheduler();
        FetchScheduler.SourceRow row = new FetchScheduler.SourceRow(
            UUID.fromString("00000000-0000-0000-0000-0000000abcde"),
            "ignored-identifier",
            42L,
            "rss");
        IOException rootCause = new IOException(
            "Connection refused: " + CREDENTIAL_URL);
        RssFetchException wrapper = new RssFetchException(
            "RSS fetch failed", rootCause);

        scheduler.logFetchFailure(row, wrapper);

        assertFalse(capturer.records.isEmpty(),
            "logFetchFailure must emit at least one record; got none");
        for (LogRecord record : capturer.records) {
            String formatted = renderRecord(record);
            assertFalse(formatted.contains("secret"),
                "captured log line leaked credential: " + formatted);
            assertFalse(formatted.contains("user:"),
                "captured log line leaked userinfo segment: " + formatted);
        }
    }

    @Test
    void logFetchFailurePreservesDiagnosticContextOutsideTheRedaction() {
        FetchScheduler scheduler = new FetchScheduler();
        UUID sourceId = UUID.fromString("00000000-0000-0000-0000-0000000fedcb");
        FetchScheduler.SourceRow row = new FetchScheduler.SourceRow(
            sourceId, "ignored-identifier", 7L, "rss");
        RssFetchException wrapper = new RssFetchException(
            "RSS fetch failed",
            new IOException("Connection refused: " + CREDENTIAL_URL));

        scheduler.logFetchFailure(row, wrapper);

        StringBuilder combined = new StringBuilder();
        for (LogRecord r : capturer.records) {
            combined.append(renderRecord(r)).append('\n');
        }
        String all = combined.toString();
        assertTrue(all.contains(sourceId.toString()),
            "redacted log must still name the source uuid: " + all);
        assertTrue(all.contains("dispatch=7"),
            "redacted log must still name the dispatch key: " + all);
        assertTrue(all.contains("RssFetchException"),
            "redacted log must still name the outer exception class: " + all);
        assertTrue(all.contains("IOException"),
            "redacted log must still name the root-cause exception class: " + all);
        assertTrue(all.contains("example.com/[REDACTED]"),
            "redacted URL must still carry the non-credential host (path collapsed to "
                + "/[REDACTED] per U-11): " + all);
    }

    @Test
    void redactUrlsInTextLeavesNonUrlSubstringsUntouched() {
        String input = "no URL here, just words.";
        String redacted = FetchScheduler.redactUrlsInText(input);
        assertEquals(input, redacted,
            "redactUrlsInText must be a no-op on URL-free input");

        assertEquals("", FetchScheduler.redactUrlsInText(""),
            "empty string must round-trip unchanged");
        assertEquals(null, FetchScheduler.redactUrlsInText(null),
            "null must round-trip unchanged (no NPE)");
    }

    @Test
    void redactUrlsInTextRedactsMultipleUrlsInOneMessage() {
        String input = "Failed " + CREDENTIAL_URL
            + " then retried https://other:hunter2@host.example/p?token=abc and quit.";

        String redacted = FetchScheduler.redactUrlsInText(input);

        assertFalse(redacted.contains("secret"), "first URL not redacted: " + redacted);
        assertFalse(redacted.contains("hunter2"), "second URL not redacted: " + redacted);
        assertFalse(redacted.contains("token=abc"), "query token not redacted: " + redacted);
        assertTrue(redacted.contains("Failed"),
            "non-URL prefix must be preserved: " + redacted);
        assertTrue(redacted.contains("then retried"),
            "non-URL infix must be preserved: " + redacted);
        assertTrue(redacted.contains("and quit."),
            "non-URL suffix must be preserved: " + redacted);
    }

    @Test
    void exceptionChainMessageTerminatesOnSelfReferentialCauseCycle() {
        // Reflectively-installed self-referential cause is a known
        // edge case (a custom Throwable subclass can call initCause(this)
        // outside of the JDK's loop guard); the helper must not hang.
        SelfCausingException self = new SelfCausingException("self loop");
        String digest = FetchScheduler.exceptionChainMessage(self);
        assertTrue(digest.contains("SelfCausingException"),
            "digest must name the throwable class: " + digest);
        assertTrue(digest.contains("self loop"),
            "digest must include the throwable message: " + digest);
    }

    private static String renderRecord(LogRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.getLevel()).append(": ");
        String raw = r.getMessage();
        if (raw != null) {
            Object[] params = r.getParameters();
            if (params == null || params.length == 0) {
                sb.append(raw);
            } else {
                try {
                    sb.append(String.format(raw, params));
                } catch (Exception fmtEx) {
                    sb.append(raw);
                }
            }
        }
        Throwable thrown = r.getThrown();
        if (thrown != null) {
            // If the helper accidentally re-introduces the throwable
            // as a logger parameter, the thrown's message would appear
            // here. The test's redaction assertion picks it up via the
            // sb append.
            sb.append(" [thrown=").append(thrown.getClass().getSimpleName());
            if (thrown.getMessage() != null) {
                sb.append(": ").append(thrown.getMessage());
            }
            sb.append("]");
        }
        return sb.toString();
    }

    /**
     * Throws-its-own-cause exception used to verify the chain walker
     * terminates on a self-referential cycle.
     */
    private static final class SelfCausingException extends RuntimeException {
        SelfCausingException(String message) {
            super(message);
        }

        @Override
        public Throwable getCause() {
            return this;
        }
    }

    /**
     * Minimal JUL handler that records every {@link LogRecord} the
     * configured loggers emit. Same shape as the CapturingHandler in
     * {@code InboundRouterContactIdRedactionTest} / {@code InstanceLockGuardIT}.
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
    }
}
