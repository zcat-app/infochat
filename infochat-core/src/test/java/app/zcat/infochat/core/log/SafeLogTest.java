package app.zcat.infochat.core.log;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeLogTest {

    @Test
    void formatSafeDropsExceptionMessage() {
        var ex = new RuntimeException("SECRET user chat body");
        String result = SafeLog.formatSafe("dispatch failed", ex);

        assertTrue(result.startsWith("dispatch failed | exception="));
        assertTrue(result.contains("java.lang.RuntimeException"));
        assertFalse(result.contains("SECRET user chat body"));
    }

    @Test
    void formatSafeNoCauseProducesExactFormat() {
        var ex = new RuntimeException("secret");
        String result = SafeLog.formatSafe("op failed", ex);

        assertEquals("op failed | exception=java.lang.RuntimeException", result);
    }

    @Test
    void formatSafeIncludesCauseChainClassNames() {
        var root = new IllegalArgumentException("root secret");
        var mid = new RuntimeException("mid secret", root);
        var top = new Exception("top secret", mid);
        String result = SafeLog.formatSafe("chain test", top);

        assertEquals(
                "chain test | exception=java.lang.Exception"
                        + " > java.lang.RuntimeException"
                        + " > java.lang.IllegalArgumentException",
                result);
        assertFalse(result.contains("root secret"));
        assertFalse(result.contains("mid secret"));
        assertFalse(result.contains("top secret"));
    }

    @Test
    void formatSafeCauseChainCappedAtMaxDepth() {
        Throwable current = new Exception("leaf");
        for (int i = 0; i < 7; i++) {
            current = new RuntimeException("level-" + i, current);
        }
        String result = SafeLog.formatSafe("deep chain", current);

        int separatorCount = 0;
        int idx = 0;
        while ((idx = result.indexOf(" > ", idx)) != -1) {
            separatorCount++;
            idx += 3;
        }
        assertEquals(SafeLog.MAX_CAUSE_DEPTH, separatorCount,
                "cause chain must be capped at " + SafeLog.MAX_CAUSE_DEPTH);
    }

    @Test
    void formatSafeRedactsApiKeyInMsg() {
        var ex = new RuntimeException("connection reset");
        String result = SafeLog.formatSafe(
                "fetch failed for key=sk-ant-api0123456789abcdef01234567890", ex);

        assertFalse(result.contains("sk-ant-api0123456789abcdef01234567890"));
        assertTrue(result.contains(Redactor.REDACTED));
    }

    @Test
    void formatSafeDoesNotLeakExceptionMessageWithApiKey() {
        var ex = new RuntimeException(
                "Auth failed: key=sk-ant-api0123456789abcdef01234567890");
        String result = SafeLog.formatSafe("LLM call failed", ex);

        assertFalse(result.contains("sk-ant-api0123456789abcdef01234567890"),
                "API key in exception message must not leak");
        assertFalse(result.contains("Auth failed"),
                "exception message text must not leak");
    }
}
