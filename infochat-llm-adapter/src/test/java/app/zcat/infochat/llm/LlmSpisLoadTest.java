package app.zcat.infochat.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Smoke test: verifies the five LLM SPI types compile and are loadable
 * on the infochat-llm-adapter classpath with the kinds the spec commits
 * to (interface for LlmProvider / EmbeddingProvider, enum for ModelTask,
 * record for LlmResponse / EmbeddingResult). No behavior is exercised
 * here — that lives with the impl-side tickets that will land concrete
 * providers. The cross-module load test (Fetcher + LLM SPIs + Messaging
 * SPIs all visible from the same classpath) is the M1-007 umbrella
 * commit's verification surface, not this one.
 */
class LlmSpisLoadTest {

    @Test
    void llmProviderIsLoadableInterface() throws ClassNotFoundException {
        Class<?> type = Class.forName("app.zcat.infochat.llm.LlmProvider");
        assertNotNull(type);
        assertTrue(type.isInterface(), "LlmProvider must be an interface");
    }

    @Test
    void embeddingProviderIsLoadableInterface() throws ClassNotFoundException {
        Class<?> type = Class.forName("app.zcat.infochat.llm.EmbeddingProvider");
        assertNotNull(type);
        assertTrue(type.isInterface(), "EmbeddingProvider must be an interface");
    }

    @Test
    void modelTaskIsLoadableEnumWithSpecMandatedValues() throws ClassNotFoundException {
        Class<?> type = Class.forName("app.zcat.infochat.llm.ModelTask");
        assertNotNull(type);
        assertTrue(type.isEnum(), "ModelTask must be an enum");
        // The exact six values are spec-mandated (docs/spec/llm.md §SPI
        // shape). Asserting the count guards against a future drive-by
        // addition (most likely an EMBEDDER value, which the spec
        // explicitly excludes under "Scope of the enum").
        assertEquals(6, type.getEnumConstants().length,
                "ModelTask must have exactly six spec-mandated values");
    }

    @Test
    void llmResponseIsLoadableRecord() throws ClassNotFoundException {
        Class<?> type = Class.forName("app.zcat.infochat.llm.LlmResponse");
        assertNotNull(type);
        assertTrue(type.isRecord(), "LlmResponse must be a record");
    }

    @Test
    void embeddingResultIsLoadableRecord() throws ClassNotFoundException {
        Class<?> type = Class.forName("app.zcat.infochat.llm.EmbeddingResult");
        assertNotNull(type);
        assertTrue(type.isRecord(), "EmbeddingResult must be a record");
    }
}
