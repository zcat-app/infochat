package app.zcat.infochat.collector.eval.reeval;

import app.zcat.infochat.collector.eval.stage2.Stage2VerdictHandler;
import app.zcat.infochat.collector.eval.stage2.Stage2Worker;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test double for {@link Stage2Worker} that records how many times
 * {@link #judgeBody} is invoked and returns a fixed verdict — the seam the
 * M1-342 per-tick infra-failure fan-out bound is measured against. Counting
 * judgeBody (not the underlying provider {@code generate} calls) is what the
 * acceptance pins: the real worker retries once internally, so a generate
 * count would double per judge.
 *
 * <p>Constructed directly rather than as a CDI bean — the override never calls
 * {@code super.judgeBody}, so the real worker's injected collaborators are
 * never touched and may stay null.
 */
final class CountingStage2Worker extends Stage2Worker {

    private final AtomicInteger judgeBodyCalls = new AtomicInteger();
    private volatile Stage2VerdictHandler.Verdict verdict = Stage2VerdictHandler.Verdict.INFRA_FAILURE;

    void setVerdict(Stage2VerdictHandler.Verdict verdict) {
        this.verdict = verdict;
    }

    int judgeBodyCallCount() {
        return judgeBodyCalls.get();
    }

    @Override
    public Stage2VerdictHandler.Verdict judgeBody(UUID postId, String originalBody) {
        judgeBodyCalls.incrementAndGet();
        return verdict;
    }
}
