package app.zcat.infochat.provider.digest;

import app.zcat.infochat.core.notifier.NotifyOutcome;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The system-wide LLM call budget's own mechanics (M1-767): rolling-window
 * accounting, ceiling refusal without consuming budget, and the
 * once-per-window breach signal. The scheduled-route integration lives in
 * {@link DigestWorkerTest} (gate) and {@link DigestRendererTest} (draws).
 */
class SystemLlmBudgetTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-08-04T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void recordCalls_countsWithinWindow() {
        SystemLlmBudget budget = new SystemLlmBudget();
        budget.window = Duration.ofHours(24);
        budget.ceiling = 1000;
        budget.clock = FIXED;

        budget.recordCalls(3);
        budget.recordCalls(2);

        assertEquals(5, budget.callsInWindow());
    }

    @Test
    void windowRolls_callsOlderThanWindowExpire() {
        SystemLlmBudget budget = new SystemLlmBudget();
        budget.window = Duration.ofHours(24);
        budget.ceiling = 1000;
        budget.clock = FIXED;
        budget.recordCalls(5);

        budget.clock = Clock.fixed(
                Instant.parse("2026-08-05T09:00:00Z"), ZoneOffset.UTC);
        budget.recordCalls(1);

        assertEquals(1, budget.callsInWindow(),
                "calls older than the window must expire on the next draw");
    }

    @Test
    void canStartRender_refusesAtCeilingAndRecordsNothing() {
        SystemLlmBudget budget = new SystemLlmBudget();
        budget.window = Duration.ofHours(24);
        budget.ceiling = 2;
        budget.clock = FIXED;
        budget.adminNotifier = new RecordingAdminNotifier();
        budget.recordCalls(2);

        assertFalse(budget.canStartRender(),
                "a window at the ceiling must refuse admission");
        assertEquals(2, budget.callsInWindow(),
                "a refused render records nothing — rejection never consumes budget");
    }

    @Test
    void canStartRender_admitsUnderCeilingWithoutSignalling() {
        SystemLlmBudget budget = new SystemLlmBudget();
        budget.window = Duration.ofHours(24);
        budget.ceiling = 3;
        budget.clock = FIXED;
        budget.adminNotifier = new RecordingAdminNotifier();
        budget.recordCalls(2);

        assertTrue(budget.canStartRender(), "under the ceiling admission is granted");
        assertEquals(0, ((RecordingAdminNotifier) budget.adminNotifier).notifyCount,
                "admission must not signal the breach");
    }

    @Test
    void refusalSignalsBreachThroughNotifier() {
        SystemLlmBudget budget = new SystemLlmBudget();
        budget.window = Duration.ofHours(24);
        budget.ceiling = 1;
        budget.clock = FIXED;
        RecordingAdminNotifier notifier = new RecordingAdminNotifier();
        budget.adminNotifier = notifier;
        budget.recordCalls(1);

        assertFalse(budget.canStartRender());
        assertEquals(1, notifier.notifyCount,
                "a refused render signals the breach through the notifier");
        assertTrue(notifier.lastKey.contains("llm-budget"),
                "the signal uses a stable, low-cardinality key");
    }

    @Test
    void refusalSignalDoesNotHoldTheBudgetMonitor() {
        SystemLlmBudget budget = new SystemLlmBudget();
        budget.window = Duration.ofHours(24);
        budget.ceiling = 1;
        budget.clock = FIXED;
        MonitorProbingNotifier notifier = new MonitorProbingNotifier(budget);
        budget.adminNotifier = notifier;
        budget.recordCalls(1);

        assertFalse(budget.canStartRender());
        assertTrue(notifier.concurrentDrawCompleted,
                "notifyOnce must not run while holding the budget monitor: it opens a "
                        + "JDBC connection and UPSERTs on EVERY refusal (its coalescing "
                        + "suppresses the emission, not the round-trip), so holding the "
                        + "monitor across it would queue every concurrent render's draw "
                        + "behind a DB write on the small provider pool");
    }

    /**
     * Hand-rolled notifier stub — ThrottledAdminNotifier's notifyOnce is
     * public and non-final, so a recording subclass is the Mockito-free
     * test double this suite's style calls for.
     */
    private static final class RecordingAdminNotifier extends ThrottledAdminNotifier {
        private int notifyCount;
        private String lastKey;

        @Override
        public NotifyOutcome notifyOnce(String key, String errorClass, String message) {
            notifyCount++;
            lastKey = key;
            return NotifyOutcome.EMITTED;
        }
    }

    /**
     * Stands in for the real notifier's JDBC round-trip: while it runs, a
     * second thread tries to draw. If the breach signal were emitted under
     * the budget's monitor, that draw would block and the flag would stay
     * false. Bounded join so the assertion fails rather than hanging.
     */
    private static final class MonitorProbingNotifier extends ThrottledAdminNotifier {
        private final SystemLlmBudget budget;
        private volatile boolean concurrentDrawCompleted;

        private MonitorProbingNotifier(SystemLlmBudget budget) {
            this.budget = budget;
        }

        @Override
        public NotifyOutcome notifyOnce(String key, String errorClass, String message) {
            Thread drawer = new Thread(() -> {
                budget.recordCalls(1);
                concurrentDrawCompleted = true;
            });
            drawer.start();
            try {
                drawer.join(Duration.ofSeconds(5).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return NotifyOutcome.EMITTED;
        }
    }
}
