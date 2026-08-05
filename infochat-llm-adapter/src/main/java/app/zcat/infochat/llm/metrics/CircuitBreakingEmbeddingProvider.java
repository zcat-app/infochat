package app.zcat.infochat.llm.metrics;


import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.llm.EmbeddingResult;
import app.zcat.infochat.llm.impl.OpenAiCompatibleEmbeddingProvider.EmbeddingCallFailedException;
import app.zcat.infochat.llm.impl.OpenAiCompatibleEmbeddingProvider.EmbeddingProviderUnreachableException;
import app.zcat.infochat.llm.routing.LlmCircuitBreakerRegistry;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

import java.util.List;

/**
 * CDI decorator wrapping every {@link EmbeddingProvider} bean with the
 * per-endpoint circuit breaker (M1-606) — the embedding-side counterpart
 * of {@link CircuitBreakingLlmProvider}, keyed by the single
 * per-deployment {@code infochat.embeddings.base-url} (the embedding SPI
 * has no task axis). An OPEN breaker short-circuits {@link #embed} with
 * the typed {@link EmbeddingProviderUnreachableException} WITHOUT
 * attempting the HTTP call; the EmbeddingWorker's existing
 * one-failure-fails-batch handling and ChatAgent's pre-fetch degrade
 * catch treat it exactly like today's failures. Same priority placement
 * and outcome-attribution rules as the LLM-side decorator (short-circuits
 * never advance the counter; application errors record as reachable).
 */
@Decorator
@Priority(Interceptor.Priority.APPLICATION + 100)
public class CircuitBreakingEmbeddingProvider implements EmbeddingProvider {

    private final EmbeddingProvider delegate;
    private final LlmCircuitBreakerRegistry breakerRegistry;

    @Inject
    public CircuitBreakingEmbeddingProvider(@Delegate @Any EmbeddingProvider delegate,
                                            LlmCircuitBreakerRegistry breakerRegistry) {
        this.delegate = delegate;
        this.breakerRegistry = breakerRegistry;
    }

    @Override
    public List<EmbeddingResult> embed(List<String> texts) {
        if (!breakerRegistry.tryAcquireForEmbeddings()) {
            throw new EmbeddingProviderUnreachableException(
                delegate.providerName() + ": circuit breaker OPEN for the embedding "
                    + "endpoint; call short-circuited without an HTTP attempt");
        }
        List<EmbeddingResult> results;
        try {
            results = delegate.embed(texts);
        } catch (EmbeddingProviderUnreachableException e) {
            if (!releasedAsCancelled()) {
                breakerRegistry.recordUnreachableForEmbeddings();
            }
            throw e;
        } catch (EmbeddingCallFailedException e) {
            if (!releasedAsCancelled()) {
                breakerRegistry.recordReachableForEmbeddings();
            }
            throw e;
        }
        breakerRegistry.recordReachableForEmbeddings();
        return results;
    }

    /**
     * Embedding-side twin of {@code
     * CircuitBreakingLlmProvider.releasedAsCancelled} — see that method
     * for the full reasoning (M1-769). Reachable here through a different
     * door than the digest's: a chat turn cancelled by {@code /stop}
     * interrupts a thread that embeds ({@code SemanticSearchTool},
     * {@code HelpLookupTool}), and the embedding providers share {@code
     * LlmHttpSupport.sendForBody}, so the interrupt arrives as a plain
     * {@link EmbeddingCallFailedException} — the arm that closes the
     * breaker.
     */
    private boolean releasedAsCancelled() {
        if (!Thread.currentThread().isInterrupted()) {
            return false;
        }
        breakerRegistry.releaseProbeForEmbeddings();
        return true;
    }

    /**
     * Must forward: the interface default walks {@code getClass()},
     * which on this CDI decorator subclass would yield the decorator's
     * own simple name rather than the delegate's stable constant (same
     * trap {@link MeteredEmbeddingProvider#providerName()} documents).
     */
    @Override
    public String providerName() {
        return delegate.providerName();
    }
}
