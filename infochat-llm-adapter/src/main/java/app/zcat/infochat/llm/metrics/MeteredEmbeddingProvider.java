package app.zcat.infochat.llm.metrics;


import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.llm.EmbeddingResult;
import app.zcat.infochat.llm.LlmCallContext;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;

/**
 * CDI decorator wrapping every {@link EmbeddingProvider} bean with the
 * per-call observability surface (M1-321) — the embedding-side
 * counterpart of {@link MeteredLlmProvider}. Binds the per-call
 * {@link LlmCallContext} (task stays null: the embedder is not a
 * {@code ModelTask}) around the delegate call and emits
 * {@code embedding.calls.total} / {@code embedding.dimension}.
 *
 * <p>Label sources differ from the LLM side because the embedding SPI
 * carries no per-call provider/model reporting: the {@code provider}
 * label reads {@link EmbeddingProvider#providerName()} (the stable,
 * operator-visible name, symmetric with the LLM side), and the
 * {@code model} label reads the single-model-per-deployment property
 * {@code infochat.embeddings.model} — defaulted to {@code unknown} so
 * the decorator never fails a boot the undecorated bean would survive.</p>
 */
@Decorator
@Priority(Interceptor.Priority.APPLICATION)
public class MeteredEmbeddingProvider implements EmbeddingProvider {

    private static final Logger LOG = Logger.getLogger(MeteredEmbeddingProvider.class);

    private final EmbeddingProvider delegate;
    private final LlmMetrics metrics;
    private final String model;

    @Inject
    public MeteredEmbeddingProvider(@Delegate @Any EmbeddingProvider delegate, LlmMetrics metrics,
                                    @ConfigProperty(name = "infochat.embeddings.model",
                                                    defaultValue = "unknown") String model) {
        this.delegate = delegate;
        this.metrics = metrics;
        this.model = model;
    }

    @Override
    public List<EmbeddingResult> embed(List<String> texts) {
        LlmCallContext context = LlmCallContext.currentOrFresh().withTask(null);
        String provider = delegate.providerName();
        long startNanos = System.nanoTime();
        try {
            List<EmbeddingResult> results =
                LlmCallContext.callWith(context, () -> delegate.embed(texts));
            metrics.recordEmbeddingCall(provider, model, LlmMetrics.Outcome.OK);
            if (!results.isEmpty()) {
                metrics.recordEmbeddingDimension(provider, model, results.get(0).dimension());
            }
            LOG.debugf("embed call ok: trace=%s provider=%s model=%s batch=%d latencyMs=%d",
                context.traceId(), provider, model, texts.size(),
                Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
            return results;
        } catch (RuntimeException e) {
            metrics.recordEmbeddingCall(provider, model, LlmMetrics.Outcome.FAIL);
            LOG.debugf("embed call fail: trace=%s provider=%s model=%s batch=%d",
                context.traceId(), provider, model, texts.size());
            throw e;
        }
    }
}
