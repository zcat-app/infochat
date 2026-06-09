package app.zcat.infochat.core.log;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@link Redactor} thrown-chain coverage: messages of
 * {@link LogRecord#getThrown()} and its cause chain pass through the
 * same catalogue scan as the message and parameters before reaching
 * the console.
 */
class RedactorThrownChainTest {

    private static final String API_KEY = "sk-ant-test-redact-me-please-1234567890";

    @Test
    void apiKeyInNestedCauseMessageIsRedactedInConsoleOutput() {
        var captured = new ByteArrayOutputStream();
        var handler = new StreamHandler(captured, new SimpleFormatter());
        handler.setFilter(new Redactor());
        handler.setLevel(Level.ALL);

        var logger = java.util.logging.Logger.getLogger("test.redaction.thrown.it");
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);

        try {
            var record = new LogRecord(Level.SEVERE, "outer operation failed");
            record.setThrown(new RuntimeException("wrapper failure",
                    new IllegalStateException("auth rejected for key " + API_KEY)));
            logger.log(record);
            handler.flush();
        } finally {
            logger.removeHandler(handler);
            handler.close();
        }

        String output = captured.toString();
        assertFalse(output.isEmpty(), "handler must produce output");
        assertFalse(output.contains(API_KEY),
                "API key inside a nested cause's message must not reach console output");
        assertTrue(output.contains(Redactor.REDACTED),
                "redaction marker must appear in the rendered thrown chain");
        assertTrue(output.contains(IllegalStateException.class.getName()),
                "the replaced cause must still name the original exception class");
    }

    @Test
    void cleanThrownChainPassesThroughUntouched() {
        var filter = new Redactor();
        var thrown = new RuntimeException("plain failure",
                new IllegalStateException("no secrets here"));
        var record = new LogRecord(Level.SEVERE, "operation failed");
        record.setThrown(thrown);

        assertTrue(filter.isLoggable(record));
        assertSame(thrown, record.getThrown(),
                "a chain without catalogue matches must keep the original throwable");
    }

    @Test
    void apiKeyInTopLevelThrownMessageIsReplacedAtFilterLevel() {
        var filter = new Redactor();
        var record = new LogRecord(Level.SEVERE, "operation failed");
        record.setThrown(new RuntimeException("key " + API_KEY + " rejected"));

        assertTrue(filter.isLoggable(record));

        Throwable replaced = record.getThrown();
        assertNotNull(replaced, "thrown must be replaced, not dropped");
        String message = replaced.getMessage();
        assertNotNull(message);
        assertFalse(message.contains(API_KEY), "raw key must not survive in the replacement");
        assertTrue(message.contains(Redactor.REDACTED));
        assertTrue(message.contains(RuntimeException.class.getName()),
                "replacement message must carry the original class name");
    }

    @Test
    void replacementPreservesStackFramesAndCauseStructure() {
        var filter = new Redactor();
        var cause = new IllegalStateException("token=" + "a".repeat(40));
        var top = new RuntimeException("wrapper", cause);
        var record = new LogRecord(Level.SEVERE, "operation failed");
        record.setThrown(top);

        filter.isLoggable(record);

        Throwable replaced = record.getThrown();
        assertNotNull(replaced);
        assertEquals(top.getStackTrace().length, replaced.getStackTrace().length,
                "replacement must carry the original stack frames");
        Throwable replacedCause = replaced.getCause();
        assertNotNull(replacedCause, "cause structure must survive the rebuild");
        String causeMessage = replacedCause.getMessage();
        assertNotNull(causeMessage);
        assertFalse(causeMessage.contains("a".repeat(40)),
                "secret in the cause message must be redacted");
    }

    @Test
    void cyclicCauseChainIsTruncatedNotLoopedForever() {
        var filter = new Redactor();
        var first = new RuntimeException("first; key " + API_KEY);
        var second = new RuntimeException("second", first);
        first.initCause(second);
        var record = new LogRecord(Level.SEVERE, "operation failed");
        record.setThrown(first);

        assertTrue(filter.isLoggable(record), "a cyclic chain must not hang the filter");

        // Count the rebuilt chain: the cycle is cut at the depth cap.
        int depth = 0;
        for (Throwable t = record.getThrown(); t != null; t = t.getCause()) {
            depth++;
            String message = t.getMessage();
            if (message != null) {
                assertFalse(message.contains(API_KEY),
                        "no node of the rebuilt chain may carry the raw key");
            }
        }
        assertTrue(depth <= Redactor.MAX_THROWN_CHAIN_DEPTH + 1,
                "rebuilt chain must be capped (cap + truncation marker)");
    }
}
