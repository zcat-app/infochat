package app.zcat.infochat.llm.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit5 tests for the shared {@link LlmHttpSupport} request/log
 * helpers used by all three provider impls. The preview-cap tests pin
 * the log-leak bound for error-path body previews — a provider response
 * body reaches the log only through {@code preview}, so the cap is what
 * keeps a hostile or runaway reply from flooding it.
 */
class LlmHttpSupportTest {

    @Test
    void joinPathInsertsSingleSlashWhenBaseHasNoTrailingSlash() {
        assertEquals("http://localhost:11434/v1/messages",
            LlmHttpSupport.joinPath("http://localhost:11434/v1", "/messages"));
    }

    @Test
    void joinPathCollapsesTrailingSlashOnBase() {
        assertEquals("http://localhost:11434/v1/messages",
            LlmHttpSupport.joinPath("http://localhost:11434/v1/", "/messages"));
    }

    @Test
    void previewReturnsBodyAtCapUnchanged() {
        String body = "x".repeat(LlmHttpSupport.PREVIEW_MAX_CHARS);
        assertEquals(body, LlmHttpSupport.preview(body));
    }

    @Test
    void previewTruncatesBodyBeyondCapAndNamesFullLength() {
        String body = "y".repeat(LlmHttpSupport.PREVIEW_MAX_CHARS + 300);
        String preview = LlmHttpSupport.preview(body);

        assertTrue(preview.startsWith("y".repeat(LlmHttpSupport.PREVIEW_MAX_CHARS)),
            "preview must retain exactly the capped prefix");
        String expectedSuffix = "…(" + body.length() + " bytes)";
        assertEquals(LlmHttpSupport.PREVIEW_MAX_CHARS + expectedSuffix.length(), preview.length(),
            "preview length must be the cap plus the truncation marker");
        assertTrue(preview.endsWith(expectedSuffix),
            "preview must name the full body length; got: " + preview);
    }
}
