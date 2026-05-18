package app.zcat.infochat.collector.eval.testing;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Shared test stub for the LLM evaluation pipeline. Replaces the
 * production {@code OpenAiCompatibleProvider} for every
 * {@code @QuarkusTest} that @Injects {@link LlmProvider} by way of
 * the standard ArC {@code @Alternative @Priority(Integer.MAX_VALUE)}
 * machinery.
 *
 * <p>Consumers (M1-034a):
 * <ul>
 *   <li>{@code Stage2WorkerIT} — drives the M1-033 nine-scenario
 *       judge contract (BENIGN / INJECTION / MALWARE / UNKNOWN /
 *       schema-violating / empty / unreachable + the
 *       release-on-stage2-failure flag toggling at items 28h/28i).</li>
 *   <li>{@code TaggerWorkerIT} — drives M1-034a's seven-scenario
 *       Tagger contract (happy / partial-valid / zero-valid /
 *       schema-violating-then-fallback / total-fail /
 *       LLM-unreachable / quarantined-exclusion).</li>
 * </ul>
 *
 * <p>Forward consumers (later tickets): M1-034b's {@code
 * EmbeddingWorkerIT} (if it @Injects {@code LlmProvider} as opposed
 * to {@code EmbeddingProvider}) and T2's chat-agent IT consume this
 * same stub without re-declaring an @Alternative bean.
 *
 * <p>Public top-level class so the {@code @Alternative} is visible
 * from every test-source package in the module. A nested stub
 * inside any one IT can only be reused cross-package if the IT's
 * outer class is itself public, and a second @Alternative
 * @Priority(MAX) bean in the same module triggers ArC
 * {@code AmbiguousResolutionException} at deployment — top-level
 * placement avoids both problems.
 *
 * <h2>Per-test usage</h2>
 *
 * <p>FIFO queue of canned replies; {@link #failAll} overrides every
 * call regardless of queue state. The IT's {@code @BeforeEach} must
 * call {@link #reset()} so per-test state is isolated.
 */
@Alternative
@Priority(Integer.MAX_VALUE)
@ApplicationScoped
public final class StubLlmProvider implements LlmProvider {

    private final Deque<String> queuedResponses = new ArrayDeque<>();
    private final List<ModelTask> calls = new ArrayList<>();
    private boolean failAll = false;

    public void reset() {
        queuedResponses.clear();
        calls.clear();
        failAll = false;
    }

    public void setNextResponse(String reply) {
        queuedResponses.add(reply);
    }

    public void setNextResponses(String... replies) {
        for (String r : replies) {
            queuedResponses.add(r);
        }
    }

    public void failAll() {
        this.failAll = true;
    }

    public int callCount() {
        return calls.size();
    }

    @Override
    public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
        calls.add(task);
        if (failAll) {
            throw new RuntimeException(
                "StubLlmProvider: simulated LLM unreachable (call #" + calls.size() + ")");
        }
        if (queuedResponses.isEmpty()) {
            throw new RuntimeException(
                "StubLlmProvider: no queued response for call #" + calls.size());
        }
        return new LlmResponse(queuedResponses.pollFirst());
    }
}
