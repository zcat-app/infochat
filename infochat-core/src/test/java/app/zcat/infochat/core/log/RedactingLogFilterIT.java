package app.zcat.infochat.core.log;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test: exercises the {@link Redactor} through
 * a real JUL handler pipeline and verifies the literal API key is
 * absent from the formatted output.
 */
class RedactingLogFilterIT {

    private static final String API_KEY = "sk-ant-test-redact-me-please-1234567890";

    @Test
    void apiKeyAbsentFromFormattedLogOutput() {
        var captured = new ByteArrayOutputStream();
        var formatter = new SimpleFormatter();
        var handler = new StreamHandler(captured, formatter);
        handler.setFilter(new Redactor());
        handler.setLevel(Level.ALL);

        var logger = java.util.logging.Logger.getLogger("test.redaction.it");
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);

        try {
            logger.severe("LLM call failed with key " + API_KEY);
            handler.flush();
        } finally {
            logger.removeHandler(handler);
            handler.close();
        }

        String output = captured.toString();
        assertFalse(output.isEmpty(), "handler must produce output");
        assertFalse(output.contains(API_KEY),
                "API key must not appear in formatted log output");
        assertTrue(output.contains(Redactor.REDACTED),
                "redaction marker must appear in formatted log output");
    }

    @Test
    void apiKeyInParameterAbsentFromFormattedOutput() {
        var captured = new ByteArrayOutputStream();
        var handler = new StreamHandler(captured, new SimpleFormatter());
        handler.setFilter(new Redactor());
        handler.setLevel(Level.ALL);

        var logger = java.util.logging.Logger.getLogger("test.redaction.param.it");
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);

        try {
            var record = new LogRecord(Level.SEVERE, "Request failed for key {0}");
            record.setParameters(new Object[]{API_KEY});
            logger.log(record);
            handler.flush();
        } finally {
            logger.removeHandler(handler);
            handler.close();
        }

        String output = captured.toString();
        assertFalse(output.isEmpty(), "handler must produce output");
        assertFalse(output.contains(API_KEY),
                "API key in parameter must not appear in formatted output");
    }
}
