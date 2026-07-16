package app.zcat.infochat.provider.chat;

import app.zcat.infochat.provider.chat.InFlightTracker.CancellationHandle;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InFlightTrackerTest {

    private static final UUID USER_A = UUID.randomUUID();
    private static final UUID USER_B = UUID.randomUUID();
    private static final UUID SCOPE_A = UUID.randomUUID();
    private static final UUID SCOPE_B = UUID.randomUUID();

    private final InFlightTracker tracker = new InFlightTracker();

    @Test
    void rejectsConcurrentRequest() {
        CancellationHandle held = tracker.tryAcquire(USER_A, "dm", SCOPE_A);
        assertNotNull(held);
        assertNull(tracker.tryAcquire(USER_A, "dm", SCOPE_A));

        tracker.release(USER_A, "dm", SCOPE_A, held);

        assertNotNull(tracker.tryAcquire(USER_A, "dm", SCOPE_A));
    }

    @Test
    void independentScopesDoNotConflict() {
        CancellationHandle aa = tracker.tryAcquire(USER_A, "dm", SCOPE_A);
        CancellationHandle ab = tracker.tryAcquire(USER_A, "dm", SCOPE_B);
        CancellationHandle ba = tracker.tryAcquire(USER_B, "dm", SCOPE_A);
        assertNotNull(aa);
        assertNotNull(ab);
        assertNotNull(ba);

        tracker.release(USER_A, "dm", SCOPE_A, aa);
        tracker.release(USER_A, "dm", SCOPE_B, ab);
        tracker.release(USER_B, "dm", SCOPE_A, ba);
    }

    @Test
    void releaseWithoutAcquireIsNoOp() {
        tracker.release(USER_A, "dm", SCOPE_A,
                new CancellationHandle(Thread.currentThread()));
        assertNotNull(tracker.tryAcquire(USER_A, "dm", SCOPE_A));
    }

    @Test
    void isInFlightReflectsState() {
        assertFalse(tracker.isInFlight(USER_A, "dm", SCOPE_A));
        CancellationHandle held = tracker.tryAcquire(USER_A, "dm", SCOPE_A);
        assertNotNull(held);
        assertTrue(tracker.isInFlight(USER_A, "dm", SCOPE_A));
        tracker.release(USER_A, "dm", SCOPE_A, held);
        assertFalse(tracker.isInFlight(USER_A, "dm", SCOPE_A));
    }

    @Test
    void cancellationHandleStartsUncancelledAndMarksCancelled() {
        CancellationHandle held = tracker.tryAcquire(USER_A, "dm", SCOPE_A);
        assertNotNull(held);
        assertFalse(held.isCancelled(), "a freshly acquired handle is not cancelled");
        held.markCancelled();
        assertTrue(held.isCancelled(), "markCancelled() flips the flag to true");
    }

    /**
     * The open-gate half of the M1-634 stale-interrupt confinement: while
     * the worker is inside its in-flight section, interruptWorker() must
     * reach the captured thread — this is the D35 cancellation actually
     * landing.
     */
    @Test
    void interruptWorkerBeforeReleaseInterruptsCapturedThread() {
        CancellationHandle held = tracker.tryAcquire(USER_A, "dm", SCOPE_A);
        assertNotNull(held);
        try {
            assertTrue(held.interruptWorker(),
                    "an open gate must report the interrupt as issued");
            assertTrue(Thread.currentThread().isInterrupted(),
                    "the captured worker thread must receive the interrupt");
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * M1-634 redteam remediation pin (stale-interrupt window): after the
     * worker closes the gate at the end of its in-flight section, a delayed
     * interruptWorker() — the /stop thread descheduled between reading the
     * handle and interrupting — must be a no-op, because the pool thread
     * may already be running a different (user, scope)'s turn.
     */
    @Test
    void interruptWorkerAfterReleaseWorkerIsNoOp() {
        CancellationHandle held = tracker.tryAcquire(USER_A, "dm", SCOPE_A);
        assertNotNull(held);
        held.releaseWorker();

        assertFalse(held.interruptWorker(),
                "a closed gate must report the interrupt as suppressed");
        assertFalse(Thread.currentThread().isInterrupted(),
                "no interrupt may reach the (recycled) thread after the gate closed");
    }

    /**
     * The already-landed half of the confinement: an interrupt that fired
     * while the gate was open but was never consumed by a blocking call
     * must not survive the section — releaseWorker() clears it in the same
     * atomic step that closes the gate.
     */
    @Test
    void releaseWorkerClearsPendingInterruptStatus() {
        CancellationHandle held = tracker.tryAcquire(USER_A, "dm", SCOPE_A);
        assertNotNull(held);
        assertTrue(held.interruptWorker());

        held.releaseWorker();

        assertFalse(Thread.currentThread().isInterrupted(),
                "releaseWorker must clear an already-landed stale interrupt");
    }

    @Test
    void staleReleaseByPreviousWorkerDoesNotEvictNewHoldersSlot() {
        CancellationHandle first = tracker.tryAcquire(USER_A, "dm", SCOPE_A);
        assertNotNull(first);

        // /stop cancels and frees the slot, then a new request re-acquires it.
        tracker.release(USER_A, "dm", SCOPE_A, first);
        CancellationHandle second = tracker.tryAcquire(USER_A, "dm", SCOPE_A);
        assertNotNull(second);

        // The first worker's late finally fires after the re-acquire — it
        // must not evict the new holder's slot or its cancellation handle.
        tracker.release(USER_A, "dm", SCOPE_A, first);

        assertTrue(tracker.isInFlight(USER_A, "dm", SCOPE_A),
                "stale release must leave the new holder's slot intact");
        assertSame(second,
                tracker.getCancellationHandle(USER_A, "dm", SCOPE_A).orElseThrow(),
                "the new holder's cancellation handle must remain registered");
    }
}
