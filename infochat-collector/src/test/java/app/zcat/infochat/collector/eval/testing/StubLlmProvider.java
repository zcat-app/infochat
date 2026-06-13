package app.zcat.infochat.collector.eval.testing;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import org.jspecify.annotations.Nullable;

import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

    // All mutable state is thread-safe: the eval queue dispatches generate()
    // on virtual threads, so the queued responses, the call log, the fail
    // flag, and the captured-call list / gate are all read and written across
    // threads. Concurrent collections + volatile flags keep a flaky test from
    // masking a real concurrency bug.
    private final Deque<String> queuedResponses = new ConcurrentLinkedDeque<>();
    private final List<ModelTask> calls = new CopyOnWriteArrayList<>();
    private volatile boolean failAll = false;
    private final List<CapturedCall> capturedCalls = new CopyOnWriteArrayList<>();
    private volatile @Nullable CountDownLatch gate;

    /** One {@link #generate} invocation: which task, on which thread. */
    public record CapturedCall(ModelTask task, String threadName) {
    }

    public void reset() {
        queuedResponses.clear();
        calls.clear();
        capturedCalls.clear();
        failAll = false;
        releaseHeldCalls();
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

    /**
     * Park every subsequent {@link #generate} call (after its
     * thread name is recorded) until {@link #releaseHeldCalls()}.
     * Lets a test prove a caller thread is NOT the thread executing
     * the LLM call — e.g. the eval-queue emitter-thread-hop
     * assertion. The hold is bounded (10 s) so a test that forgets
     * to release fails loudly instead of hanging the suite.
     */
    public void holdCallsUntilReleased() {
        this.gate = new CountDownLatch(1);
    }

    public void releaseHeldCalls() {
        CountDownLatch held = this.gate;
        if (held != null) {
            held.countDown();
            this.gate = null;
        }
    }

    /**
     * Thread names observed by {@link #generate} for one task, in
     * call order. Filtering by task keeps a concurrently-polling
     * worker (e.g. the 5s Tagger scheduler) from polluting another
     * worker's capture.
     */
    public List<String> callThreadNames(ModelTask task) {
        return capturedCalls.stream()
            .filter(call -> call.task() == task)
            .map(CapturedCall::threadName)
            .toList();
    }

    @Override
    public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
        calls.add(task);
        capturedCalls.add(new CapturedCall(task, Thread.currentThread().getName()));
        awaitGateIfHeld();
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

    private void awaitGateIfHeld() {
        CountDownLatch held = gate;
        if (held == null) {
            return;
        }
        try {
            if (!held.await(10, TimeUnit.SECONDS)) {
                throw new RuntimeException(
                    "StubLlmProvider: held call was never released within 10s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                "StubLlmProvider: interrupted while held at the gate", e);
        }
    }
}
