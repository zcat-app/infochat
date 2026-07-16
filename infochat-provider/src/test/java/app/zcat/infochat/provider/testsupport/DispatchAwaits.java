package app.zcat.infochat.provider.testsupport;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Shared deadline-poll await for the M1-634 async interruptible
 * dispatch: {@code InboundRouter.onMessage} returns before an
 * interruptible turn's reply exists, so router/command ITs await the
 * expected terminal (finalized body, sent count, DB row) instead of
 * asserting synchronously after the drive. One shared helper (same
 * fixture-sharing precedent as {@code OutboxItFixtures}) keeps the
 * await discipline uniform across the reworked ITs; before a NEGATIVE
 * assert (no-double-send, exact counts), additionally await
 * {@code InterruptibleDispatcher.inFlightTaskCount() == 0} so "no
 * further send can arrive" is a happens-before fact, not a sleep.
 */
public final class DispatchAwaits {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final long POLL_MILLIS = 20;

    private DispatchAwaits() {
    }

    /**
     * Poll {@code condition} until it holds, failing with
     * {@code description} after the shared 15s deadline. The deadline is
     * generous relative to any awaited terminal (all test LLM calls are
     * canned) — it exists to convert a hang into a diagnosable failure,
     * never as a tuning knob.
     */
    public static void await(BooleanSupplier condition, String description) {
        long deadlineNanos = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadlineNanos > 0) {
                throw new AssertionError(
                        "Timed out after " + TIMEOUT.toSeconds() + "s awaiting: " + description);
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while awaiting: " + description, e);
            }
        }
    }
}
