package app.zcat.infochat.provider.digest;

import app.zcat.infochat.core.notifier.NotifyOutcome;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.llm.LlmCallBudget;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-call draw's two properties beyond plain accounting (M1-769):
 * the reserved tail that stops one group starving the deployment —
 * including the exact share it delivers and the fact that admission and
 * the draw decide one predicate (acceptance item 4) — and the bound the
 * atomic check-and-charge holds under concurrent renders (acceptance
 * item 6). Asserted against
 * {@link SystemLlmBudget} directly — neither property is about the
 * render.
 *
 * <p>{@link SystemLlmBudgetTest} keeps the M1-767 window/admission
 * mechanics; this file adds only what the per-call draw introduces.</p>
 */
class SystemLlmBudgetFairnessTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-08-05T08:00:00Z"), ZoneOffset.UTC);

    private static final UUID EARLY_GROUP = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID LATE_GROUP = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SECOND_LATE_GROUP =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static SystemLlmBudget budget(int ceiling, int groupReserve) {
        SystemLlmBudget budget = new SystemLlmBudget();
        budget.window = Duration.ofHours(24);
        budget.ceiling = ceiling;
        budget.groupReserve = groupReserve;
        budget.clock = FIXED;
        return budget;
    }

    @Test
    void lateGroupStillDrawsAfterAnEarlyGroupBurnedThePool() {
        // DigestScheduler.staggerOffset is a deterministic groupId hash, so
        // the groups that fire late are the SAME ones every day. Under a
        // purely global ceiling they would be starved permanently, which is
        // the failure this reserve exists to prevent — not the total spend.
        SystemLlmBudget budget = budget(20, 5);
        int drawn = 0;
        while (budget.tryDraw(EARLY_GROUP)) {
            drawn++;
        }

        assertEquals(15, drawn,
                "an early group draws freely until the reserved tail, then is held out "
                        + "of it — it has long since had its own reserve");
        assertTrue(budget.tryDraw(LATE_GROUP),
                "a group that has drawn nothing must still render at an all-but-exhausted "
                        + "window; that is the whole anti-starvation property");
    }

    @Test
    void reservedTailFundsOneLatecomerPerWindowAndNoMore() {
        // The reserve is a shared BAND, not a per-group allocation: the
        // first starved group to reach it takes the whole thing and the
        // ones behind it still degrade. Pinning that here is the point —
        // the operator-facing comment in application.properties must
        // promise one latecomer, not a floor for every latecomer, and an
        // over-claimed rate-limiting control is itself the defect.
        SystemLlmBudget budget = budget(20, 5);
        RecordingAdminNotifier notifier = new RecordingAdminNotifier();
        budget.adminNotifier = notifier;
        while (budget.tryDraw(EARLY_GROUP)) {
            // Burn down to the reserved tail.
        }

        int lateDraws = 0;
        while (budget.tryDraw(LATE_GROUP)) {
            lateDraws++;
        }

        assertEquals(5, lateDraws,
                "one latecomer takes the whole band — a floor for it, not for each "
                        + "starved group");
        assertEquals(20, budget.callsInWindow(),
                "and the aggregate ceiling still binds everyone");
        assertFalse(budget.tryDraw(LATE_GROUP), "the window is now full for every group");
        assertFalse(budget.canStartRender(SECOND_LATE_GROUP),
                "and the NEXT starved group is refused at admission, not admitted into a "
                        + "render that would issue nothing — the delivered property is one "
                        + "latecomer per window, stated exactly where the operator reads it");
        assertEquals(1, notifier.notifyCount,
                "that refusal is what tells the operator the budget bound the deployment");
    }

    @Test
    void admissionRefusesExactlyWhenTheDrawWouldAndSignalsTheBreach() {
        // Both altitudes must decide ONE predicate. While the reserve lived
        // only in the draw, a group-blind admission gate let through a
        // render whose every call the draw then refused: it issued nothing,
        // shipped prose-less, burned the circuit breaker's recovery probe on
        // each refused call, and raised no breach signal because the gate
        // itself never went false.
        SystemLlmBudget budget = budget(20, 5);
        RecordingAdminNotifier notifier = new RecordingAdminNotifier();
        budget.adminNotifier = notifier;
        while (budget.tryDraw(EARLY_GROUP)) {
            // Burn down to the tail: EARLY_GROUP is now over its own
            // reserve, LATE_GROUP has drawn nothing.
        }

        assertTrue(budget.canStartRender(LATE_GROUP),
                "a group still under its reserve must be ADMITTED into the tail — the "
                        + "share has to be reachable, not merely declared");
        assertEquals(0, notifier.notifyCount, "an admitted render signals no breach");

        assertFalse(budget.canStartRender(EARLY_GROUP),
                "a group the draw would refuse must be refused at ADMISSION, where the "
                        + "digest degrades to its non-generative path and the operator is "
                        + "told — not inside a render that then issues nothing");
        assertFalse(budget.tryDraw(EARLY_GROUP),
                "non-vacuity: admission refused exactly what the draw refuses");
        assertEquals(1, notifier.notifyCount,
                "and the refusal the group-blind gate never made is the operator signal");
    }

    @Test
    void belowTheReservedTailNoGroupIsHeldBack() {
        // Normal operation is untouched: fairness engages only once the
        // window is nearly exhausted (out_of_scope item 4 — the render's
        // shape in normal operation does not change).
        SystemLlmBudget budget = budget(20, 5);
        for (int i = 0; i < 15; i++) {
            assertTrue(budget.tryDraw(i % 2 == 0 ? EARLY_GROUP : LATE_GROUP),
                    "every draw below the reserved tail is admitted regardless of group");
        }
    }

    @Test
    void concurrentRendersCanNeverPushTheWindowPastTheCeiling() {
        // The intended bound, stated: admission (canStartRender) is
        // check-then-act across slot dispatches on virtual threads bounded
        // by infochat.summary.workers, so N renders CAN all be admitted
        // under the ceiling. Their combined SPEND cannot exceed it, because
        // the check and the charge share one monitor here.
        int ceiling = 500;
        int threads = 8;
        SystemLlmBudget budget = budget(ceiling, 0);
        AtomicInteger admitted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            UUID groupId = UUID.randomUUID();
            LlmCallBudget sink = budget.forRender(groupId);
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < ceiling; i++) {
                        if (sink.tryDraw()) {
                            admitted.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();

        assertTrue(awaitQuietly(done), "all drawing threads must finish");
        assertEquals(ceiling, admitted.get(),
                "exactly the ceiling's worth of calls may be admitted — no more from a "
                        + "check that raced another thread's charge, and no fewer");
        assertEquals(ceiling, budget.callsInWindow(),
                "and every admitted call is charged exactly once");
    }

    /**
     * Hand-rolled notifier stub, mirroring {@link SystemLlmBudgetTest}'s
     * (private) twin: {@code notifyOnce} is public and non-final, so a
     * recording subclass is the Mockito-free double this suite's style
     * calls for.
     */
    private static final class RecordingAdminNotifier extends ThrottledAdminNotifier {
        private int notifyCount;

        @Override
        public NotifyOutcome notifyOnce(String key, String errorClass, String message) {
            notifyCount++;
            return NotifyOutcome.EMITTED;
        }
    }

    private static boolean awaitQuietly(CountDownLatch latch) {
        try {
            return latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
