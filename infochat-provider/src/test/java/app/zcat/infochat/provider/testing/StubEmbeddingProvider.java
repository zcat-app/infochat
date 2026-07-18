package app.zcat.infochat.provider.testing;

import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.llm.EmbeddingResult;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.List;

/**
 * Test-scope {@link EmbeddingProvider} that shadows the real HTTP embedding
 * client for the whole provider test classpath, exactly as {@link TestLlmProvider}
 * shadows the chat provider (M1-644).
 *
 * <p>Without this bean the provider suite is not hermetic: {@code %test} points
 * {@code infochat.llm.default.base-url} and {@code infochat.embeddings.base-url}
 * at the same string, {@code LlmCircuitBreakerRegistry} keys breakers by endpoint
 * URL, so chat and embeddings SHARE one breaker. The M1-589 semanticSearch
 * pre-fetch runs on every chat turn, so with nothing listening on 11434 three
 * consecutive transport failures trip that shared breaker OPEN — and because
 * {@code CircuitBreakingLlmProvider} is a {@code @Decorator} on {@code @Any
 * LlmProvider} it wraps the stub too, throwing without ever entering
 * {@code TestLlmProvider.generate()} where the router ITs' latch lives.
 *
 * <p>The lenient no-setup default is load-bearing and deliberately differs from
 * the collector's queue-driven stub of the same name, which throws when its queue
 * is empty: that pre-fetch embeds on EVERY chat turn without queueing anything,
 * so a strict port would throw on every turn.
 */
@Alternative
@Priority(Integer.MAX_VALUE)
@ApplicationScoped
public class StubEmbeddingProvider implements EmbeddingProvider {

    /** Must match post_embedding.embedding's declared vector(768) (V11__post_embedding.sql). */
    static final int DIMENSION = 768;

    /**
     * One canned vector per input text, in order — the SPI contract callers index
     * against.
     *
     * <p>The vector is the unit vector [1, 0, ..., 0], NOT all-zeroes: retrieval
     * ranks with pgvector's cosine operator {@code <=>}, which is undefined for a
     * zero vector, so an all-zero canned vector would poison every distance
     * comparison with NaN instead of merely returning no matches.
     */
    @Override
    public List<EmbeddingResult> embed(List<String> texts) {
        return texts.stream().map(text -> new EmbeddingResult(unitVector())).toList();
    }

    private static float[] unitVector() {
        float[] vector = new float[DIMENSION];
        vector[0] = 1.0f;
        return vector;
    }
}
