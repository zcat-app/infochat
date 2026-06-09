package app.zcat.infochat.core.log;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@link Redactor} suppressed-throwable coverage: messages of
 * suppressed throwables on every node of the thrown graph — including
 * suppressed nodes' own causes and nested suppressed — pass through
 * the same catalogue scan as the cause chain before reaching the
 * console, and survive the rebuild as redacted {@code Suppressed:}
 * frames instead of being dropped.
 */
class RedactorSuppressedChainTest {

    private static final String API_KEY = "sk-ant-test-redact-me-please-1234567890";

    private static String logThroughConsoleHandler(Throwable thrown) {
        var captured = new ByteArrayOutputStream();
        var handler = new StreamHandler(captured, new SimpleFormatter());
        handler.setFilter(new Redactor());
        handler.setLevel(Level.ALL);

        var logger = java.util.logging.Logger.getLogger("test.redaction.suppressed");
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);

        try {
            var record = new LogRecord(Level.SEVERE, "outer operation failed");
            record.setThrown(thrown);
            logger.log(record);
            handler.flush();
        } finally {
            logger.removeHandler(handler);
            handler.close();
        }

        String output = captured.toString();
        assertFalse(output.isEmpty(), "handler must produce output");
        return output;
    }

    /**
     * The 2026-06-10 redteam REPRO shape: the try body's throwable
     * (clean message, clean cause chain) becomes the primary and the
     * secret-bearing {@code close()} failure is recorded on it as
     * suppressed by the try-with-resources machinery.
     */
    private static Throwable failingTryWithResources() {
        try (AutoCloseable resource = () -> {
            throw new IllegalStateException("close failed; key " + API_KEY + " rejected");
        }) {
            throw new RuntimeException("primary failure", new IllegalStateException("clean cause"));
        } catch (Exception e) {
            return e;
        }
    }

    @Test
    void apiKeyInSuppressedCloseFailureNeverReachesConsoleOutput() {
        String output = logThroughConsoleHandler(failingTryWithResources());

        assertFalse(output.contains(API_KEY),
                "API key inside a suppressed throwable's message must not reach console output");
        assertTrue(output.contains(Redactor.REDACTED),
                "redaction marker must appear in the rendered thrown graph");
    }

    @Test
    void rebuiltGraphRendersSuppressedFrameWithRedactedText() {
        var primary = new RuntimeException("primary failure");
        primary.addSuppressed(new IllegalStateException("close failed; key " + API_KEY + " rejected"));

        String output = logThroughConsoleHandler(primary);

        assertTrue(output.contains("Suppressed:"),
                "the rebuilt graph must still render the Suppressed: frame");
        assertTrue(output.contains(IllegalStateException.class.getName()
                        + ": close failed; key " + Redactor.REDACTED + " rejected"),
                "the suppressed frame must carry the original class name and the redacted message");
        assertFalse(output.contains(API_KEY),
                "the raw secret must not survive into the suppressed frame");
    }

    @Test
    void secretsInSuppressedCauseAndNestedSuppressedAreRedacted() {
        var primary = new RuntimeException("primary failure");
        var suppressed = new IllegalStateException("close failed",
                new IllegalArgumentException("rejected key " + API_KEY));
        suppressed.addSuppressed(new IllegalStateException("nested cleanup; token=" + "b".repeat(40)));
        primary.addSuppressed(suppressed);

        String output = logThroughConsoleHandler(primary);

        assertFalse(output.contains(API_KEY),
                "secret in a suppressed node's own cause must be redacted");
        assertFalse(output.contains("b".repeat(40)),
                "secret in a nested suppressed throwable must be redacted");
        assertTrue(output.contains(Redactor.REDACTED));
    }

    @Test
    void cleanGraphWithSuppressedPassesThroughAsSameObject() {
        var filter = new Redactor();
        var thrown = new RuntimeException("plain failure",
                new IllegalStateException("no secrets here"));
        var suppressed = new IllegalStateException("clean close failure",
                new IllegalArgumentException("clean suppressed cause"));
        suppressed.addSuppressed(new IllegalStateException("clean nested suppressed"));
        thrown.addSuppressed(suppressed);
        var record = new LogRecord(Level.SEVERE, "operation failed");
        record.setThrown(thrown);

        assertTrue(filter.isLoggable(record));
        assertSame(thrown, record.getThrown(),
                "a graph whose causes and suppressed entries are all catalogue-clean"
                        + " must keep the original throwable");
    }

    @Test
    void mutuallySuppressingPairTerminatesWithoutRawLeak() {
        var first = new RuntimeException("first; key " + API_KEY);
        var second = new IllegalStateException("second cleanup failure");
        first.addSuppressed(second);
        second.addSuppressed(first);

        // The log call returning at all is the termination assertion: a
        // per-branch depth bound would recurse forever on this cycle.
        String output = logThroughConsoleHandler(first);

        assertFalse(output.contains(API_KEY),
                "no node of the truncated suppressed graph may carry the raw key");
        assertTrue(output.contains(Redactor.REDACTED));
    }
}
