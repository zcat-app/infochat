package app.zcat.infochat.provider.testing;

import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmRouter;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Guards the provider suite's hermeticity at the CDI layer (M1-644): both LLM
 * SPIs must resolve to test doubles, so no integration test can reach a real
 * HTTP backend.
 *
 * <p>This is the executable form of a commitment that was previously spec-only —
 * {@code docs/spec/verification.md} §Test layers defines layer 3 as running
 * against "a fake LLM" — and therefore invisible to every machine check. Chat was
 * stubbed; embeddings were not, and the suite silently reached whatever ollama
 * happened to be listening on 11434 until the operator stopped one.
 */
@QuarkusTest
class TestDoubleWiringIT {

    @Inject EmbeddingProvider embeddingProvider;

    @Inject LlmRouter llmRouter;

    @Test
    void cdiResolvesEmbeddingProviderToTheTestStub() {
        assertInstanceOf(StubEmbeddingProvider.class, embeddingProvider,
                "the real HTTP embedding client holds the CDI slot — provider ITs "
                        + "would embed against a live endpoint");
    }

    /**
     * Guards a live landmine: {@link LlmRouter} reaches the stub only by MISSING
     * the configured name "openai-compatible" and falling through to its silent
     * priority-3 {@code entries.get(0)} branch. If the real provider ever
     * re-entered the bean set, the name lookup would hit it exactly AND the
     * case-insensitive sort orders "openai-compatible" before "TestLlmProvider" —
     * both mechanisms fail toward real HTTP, with no log line to notice it by.
     */
    @Test
    void chatAgentTaskRoutesToTheTestLlmProvider() {
        LlmProvider resolved = llmRouter.forTask(ModelTask.CHAT_AGENT, null);

        assertInstanceOf(TestLlmProvider.class, resolved,
                "chat routing escaped the test double — resolved " + resolved.providerName());
    }
}
