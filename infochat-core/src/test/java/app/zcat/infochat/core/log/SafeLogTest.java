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

    @Test
    void formatSafeIncludesSuppressedClassNames() {
        var ex = new RuntimeException("primary secret");
        ex.addSuppressed(new IllegalStateException("suppressed user content"));
        ex.addSuppressed(new java.io.IOException("api key=sk-ant-0123456789"));
        String result = SafeLog.formatSafe("parallel-op failed", ex);

        assertTrue(result.contains("[+java.lang.IllegalStateException,+java.io.IOException]"),
                "suppressed class names must be emitted in [+ClassName,+ClassName] form; got: " + result);
        assertFalse(result.contains("suppressed user content"),
                "suppressed exception message body must not leak");
        assertFalse(result.contains("sk-ant-0123456789"),
                "suppressed exception API key must not leak");
        assertFalse(result.contains("primary secret"),
                "primary exception message must not leak");
    }

    @Test
    void formatSafeWalksSuppressedOnCauseChainElements() {
        var rootCause = new IllegalArgumentException("root secret");
        rootCause.addSuppressed(new java.io.IOException("root suppressed body"));
        var top = new RuntimeException("top secret", rootCause);
        top.addSuppressed(new IllegalStateException("top suppressed body"));
        String result = SafeLog.formatSafe("nested op", top);

        assertTrue(result.contains("java.lang.RuntimeException[+java.lang.IllegalStateException]"),
                "top-level suppressed must be emitted attached to top exception; got: " + result);
        assertTrue(result.contains("java.lang.IllegalArgumentException[+java.io.IOException]"),
                "cause-chain suppressed must be emitted attached to cause exception; got: " + result);
        assertFalse(result.contains("root suppressed body"),
                "suppressed message body must not leak from cause-chain elements");
        assertFalse(result.contains("top suppressed body"),
                "suppressed message body must not leak from top exception");
    }

    @Test
    void formatSafeOmitsBracketsWhenNoSuppressed() {
        var ex = new RuntimeException("plain");
        String result = SafeLog.formatSafe("simple", ex);

        assertEquals("simple | exception=java.lang.RuntimeException", result,
                "exact format must not include empty [] when there are no suppressed exceptions");
    }
}
