package app.zcat.infochat.llm.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Plain JUnit5 tests for the shared {@link LlmHttpSupport} request
 * helpers used by all three provider impls.
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
}
