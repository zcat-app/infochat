package app.zcat.infochat.collector.eval.reeval;

import io.quarkus.scheduler.Scheduled;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@code concurrentExecution = SKIP} on {@link ReEvaluationJob#onTick}'s
 * {@code @Scheduled} annotation. The re-eval tick runs Stage-2 judge calls
 * synchronously per candidate and can overrun its poll interval during an LLM
 * outage; two overlapping ticks would re-enumerate the same rows and
 * double-increment {@code re_eval_attempts}, burning the per-post attempt
 * budget at up to 2x. SKIP makes that overlap impossible within the single
 * Collector instance and matches every sibling candidate-processing poller in
 * the module — this reflective assertion keeps a future edit from silently
 * dropping the convention (the {@code @Scheduled} default is PROCEED).
 */
class ReEvaluationJobConcurrentExecutionTest {

    @Test
    void onTickSkipsConcurrentExecution() throws NoSuchMethodException {
        Method onTick = ReEvaluationJob.class.getDeclaredMethod("onTick");
        Scheduled scheduled = onTick.getAnnotation(Scheduled.class);
        assertEquals(Scheduled.ConcurrentExecution.SKIP, scheduled.concurrentExecution(),
            "ReEvaluationJob.onTick must declare concurrentExecution = SKIP so same-instance "
                + "ticks cannot overlap and double-burn the re-eval attempt budget");
    }
}
