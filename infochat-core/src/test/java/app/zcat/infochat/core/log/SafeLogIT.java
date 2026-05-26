package app.zcat.infochat.core.log;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests that exercise SafeLog's sanitization through a
 * real JUL handler pipeline and verify sentinel strings are absent
 * from formatted log output. Each test simulates an exception-path
 * scenario where user-authored content or secrets could leak via the
 * exception message.
 *
 * <p>Uses the same JUL-handler capture pattern as
 * {@link RedactingLogFilterIT}: SafeLog formats the safe message,
 * then the formatted string is piped through a JUL logger with a
 * capturing handler to prove it survives the handler pipeline.</p>
 */
class SafeLogIT {

    @Test
    void jsonParseFailureDoesNotLeakChatBody() {
        String sentinel = "CONFIDENTIAL_MESSAGE_BODY_42";
        var ex = new RuntimeException(
                "Unexpected token in JSON body: " + sentinel);

        String safeMessage = SafeLog.formatSafe(
                "Inbound JSON parse failed", ex);
        String output = emitAndCapture("test.safelog.json", safeMessage);

        assertFalse(output.isEmpty(), "handler must produce output");
        assertFalse(output.contains(sentinel),
                "chat body sentinel must not appear in log output");
        assertTrue(output.contains("java.lang.RuntimeException"),
                "exception class name must appear");
    }

    @Test
    void jdbcBindParameterDoesNotLeakMemoryContent() {
        String sentinel = "CONFIDENTIAL_MEMORY_99";
        var ex = new SQLException(
                "Value too long for column chat_memory.content = '"
                        + sentinel + "'");

        String safeMessage = SafeLog.formatSafe("JDBC bind failed", ex);
        String output = emitAndCapture("test.safelog.jdbc", safeMessage);

        assertFalse(output.isEmpty(), "handler must produce output");
        assertFalse(output.contains(sentinel),
                "memory content sentinel must not appear in log output");
        assertTrue(output.contains("java.sql.SQLException"),
                "exception class name must appear");
    }

    @Test
    void httpFetchFailureDoesNotLeakApiKey() {
        String sentinel = "sk-ant-test-key-leak-555";
        var ex = new RuntimeException(
                "Connection failed: https://api.example.com?key=" + sentinel);

        String safeMessage = SafeLog.formatSafe(
                "HTTP fetch failed", ex);
        String output = emitAndCapture("test.safelog.http", safeMessage);

        assertFalse(output.isEmpty(), "handler must produce output");
        assertFalse(output.contains(sentinel),
                "API key sentinel must not appear in log output");
    }

    /**
     * Emit a pre-formatted SafeLog message through a JUL logger with
     * a capturing handler and return the formatted output. Same
     * capture pattern as {@link RedactingLogFilterIT}.
     */
    private static String emitAndCapture(String loggerName, String message) {
        var captured = new ByteArrayOutputStream();
        var handler = new StreamHandler(captured, new SimpleFormatter());
        handler.setLevel(Level.ALL);

        var logger = java.util.logging.Logger.getLogger(loggerName);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);

        try {
            logger.severe(message);
            handler.flush();
        } finally {
            logger.removeHandler(handler);
            handler.close();
        }
        return captured.toString();
    }
}
