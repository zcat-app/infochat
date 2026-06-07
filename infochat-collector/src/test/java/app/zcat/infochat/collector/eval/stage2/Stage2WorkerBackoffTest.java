package app.zcat.infochat.collector.eval.stage2;

import app.zcat.infochat.collector.eval.testing.StubLlmProvider;
import app.zcat.infochat.llm.LlmProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the sleep-before-retry backoff on {@link Stage2Worker}'s single
 * LLM retry: an exception-shaped first attempt (the rate-limited
 * 429/503 case — the {@link StubLlmProvider}'s {@code failAll}
 * RuntimeException is the same shape a real 429/503's
 * {@code LlmCallFailedException} presents to the worker's catch) must
 * wait at least the configured {@code infochat.llm.retry-backoff-ms}
 * before attempt 2, while keeping the retry count at exactly one.
 *
 * <p>No DB rows are seeded: {@link Stage2Worker#judgeBody} performs no
 * persistence, so the verdict + stub call count + elapsed time are the
 * complete observable surface.
 */
@QuarkusTest
class Stage2WorkerBackoffTest {

    @Inject
    Stage2Worker stage2Worker;

    @Inject
    LlmProvider llmProvider;

    @ConfigProperty(name = "infochat.llm.retry-backoff-ms")
    long backoffMs;

    private StubLlmProvider stub() {
        return (StubLlmProvider) llmProvider;
    }

    @BeforeEach
    void reset() {
        stub().reset();
    }

    @Test
    void rateLimitedFailureSleepsConfiguredBackoffBeforeSingleRetry() {
        stub().failAll();

        long startNanos = System.nanoTime();
        Stage2VerdictHandler.Verdict verdict =
            stage2Worker.judgeBody(UUID.randomUUID(), "backoff-test body");
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertEquals(Stage2VerdictHandler.Verdict.INFRA_FAILURE, verdict,
            "two exhausted attempts must yield INFRA_FAILURE");
        assertEquals(2, stub().callCount(),
            "the retry count must stay at exactly one retry");
        assertTrue(elapsedMs >= backoffMs,
            "expected >= " + backoffMs + " ms backoff between attempt 1 and attempt 2; elapsed "
                + elapsedMs + " ms");
    }
}
