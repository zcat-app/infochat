package app.zcat.infochat.provider.chat;

import app.zcat.infochat.provider.chat.InFlightTracker.CancellationHandle;
import app.zcat.infochat.provider.messaging.InboundContext;
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

    // ----- M1-638 turn-lifecycle coverage (additions only) ------------------

    /**
     * Acceptance 6 pin: the thread-bound CancellationHandle is reachable
     * only from a turn that has attached a worker thread. A QUEUED turn is
     * cancellable (via the sweep) but holds no slot and exposes no handle.
     */
    @Test
    void queuedTurnExposesNoHandleAndHoldsNoSlot() {
        tracker.registerQueued(USER_A, "dm", SCOPE_A, "turn-1");

        assertFalse(tracker.isInFlight(USER_A, "dm", SCOPE_A),
                "a QUEUED turn holds no in-flight slot");
        assertTrue(tracker.getCancellationHandle(USER_A, "dm", SCOPE_A).isEmpty(),
                "a QUEUED turn has no reachable CancellationHandle before a worker attaches");
    }

    /**
     * Per-turn keying (the ticket's anti-clobber constraint): two same-key
     * queued turns coexist — the second submit must not evict the first,
     * or the first becomes uncancellable and the second's terminal is
     * removed by the wrong finally.
     */
    @Test
    void twoQueuedTurnsOnOneScopeCoexistWithoutClobbering() {
        tracker.registerQueued(USER_A, "dm", SCOPE_A, "turn-1");
        tracker.registerQueued(USER_A, "dm", SCOPE_A, "turn-2");

        assertTrue(tracker.cancelQueuedTurns(USER_A, "dm", SCOPE_A));

        assertTrue(tracker.consumeIfCancelled(USER_A, "dm", SCOPE_A, "turn-1"),
                "the first queued turn must survive the second's registration");
        assertTrue(tracker.consumeIfCancelled(USER_A, "dm", SCOPE_A, "turn-2"),
                "the second queued turn must be independently cancellable");
    }

    /** Cancellation stays keyed per-(user, scope) at every lifecycle state. */
    @Test
    void cancelQueuedTurnsNeverReachesAnotherScopesKey() {
        tracker.registerQueued(USER_A, "dm", SCOPE_A, "turn-a");
        tracker.registerQueued(USER_B, "dm", SCOPE_B, "turn-b");

        assertTrue(tracker.cancelQueuedTurns(USER_A, "dm", SCOPE_A));

        assertFalse(tracker.consumeIfCancelled(USER_B, "dm", SCOPE_B, "turn-b"),
                "user A's sweep must never mark user B's queued turn");
    }

    @Test
    void cancelQueuedTurnsReturnsFalseWhenNothingQueued() {
        assertFalse(tracker.cancelQueuedTurns(USER_A, "dm", SCOPE_A));
    }

    /**
     * Newly-marked-only semantics: a repeat /stop against an already-swept
     * queued turn reports false, matching the running path's second-/stop
     * no-op (the slot is freed on the first cancel there).
     */
    @Test
    void repeatSweepAgainstAlreadyCancelledTurnsReportsFalse() {
        tracker.registerQueued(USER_A, "dm", SCOPE_A, "turn-1");

        assertTrue(tracker.cancelQueuedTurns(USER_A, "dm", SCOPE_A));
        assertFalse(tracker.cancelQueuedTurns(USER_A, "dm", SCOPE_A),
                "a second sweep with nothing newly marked must report false");
    }

    /**
     * Single-consume: the atomic remove-and-report is what makes the stage
     * preamble and the adopted-cancelled handler arms mutually exclusive
     * publishers of the stopped terminal (acceptance 4's one-terminal pin).
     */
    @Test
    void consumeIfCancelledReportsTrueExactlyOnce() {
        tracker.registerQueued(USER_A, "dm", SCOPE_A, "turn-1");
        tracker.cancelQueuedTurns(USER_A, "dm", SCOPE_A);

        assertTrue(tracker.consumeIfCancelled(USER_A, "dm", SCOPE_A, "turn-1"));
        assertFalse(tracker.consumeIfCancelled(USER_A, "dm", SCOPE_A, "turn-1"),
                "a consumed cancellation must never be reported twice");
    }

    /** The preamble probe must leave an uncancelled turn in place for adoption. */
    @Test
    void consumeIfCancelledLeavesUncancelledTurnQueued() {
        tracker.registerQueued(USER_A, "dm", SCOPE_A, "turn-1");

        assertFalse(tracker.consumeIfCancelled(USER_A, "dm", SCOPE_A, "turn-1"),
                "an uncancelled turn reports false");

        tracker.cancelQueuedTurns(USER_A, "dm", SCOPE_A);
        assertTrue(tracker.consumeIfCancelled(USER_A, "dm", SCOPE_A, "turn-1"),
                "the probe must not have consumed the still-queued turn");
    }

    /**
     * Adoption: with the submitting router's turn id seeded as the context
     * operationId, tryAcquire is the QUEUED→RUNNING transition of that
     * exact turn. A turn cancelled between the stage preamble and the
     * acquire is adopted with a pre-marked handle — never rejected with
     * null, which would render the "request already in progress" reject
     * for a user whose /stop just freed everything.
     */
    @Test
    void adoptionOfCancelledTurnYieldsPreMarkedHandleNotNull() {
        InboundContext context = new InboundContext();
        context.setOperationId("turn-1");
        tracker.inboundContext = context;

        tracker.registerQueued(USER_A, "dm", SCOPE_A, "turn-1");
        tracker.cancelQueuedTurns(USER_A, "dm", SCOPE_A);

        CancellationHandle adopted = tracker.tryAcquire(USER_A, "dm", SCOPE_A);
        assertNotNull(adopted, "a cancelled queued turn is adopted, never null-rejected");
        assertTrue(adopted.isCancelled(),
                "the adopted handle must carry the queued-phase cancellation");
        assertFalse(tracker.consumeIfCancelled(USER_A, "dm", SCOPE_A, "turn-1"),
                "adoption consumes the queued entry — no second publisher can claim it");
    }

    /** Admission is derived from RUNNING alone: a QUEUED turn blocks nothing. */
    @Test
    void queuedTurnDoesNotBlockAdmission() {
        tracker.registerQueued(USER_A, "dm", SCOPE_A, "turn-1");

        assertNotNull(tracker.tryAcquire(USER_A, "dm", SCOPE_A),
                "only a RUNNING turn occupies the (user, scope) slot");
    }

    /**
     * Fresh-identity fallback: a context operationId with no matching
     * queued entry (every direct test caller, and production paths that
     * never registered) reproduces the pre-M1-638 acquire semantics.
     */
    @Test
    void tryAcquireWithUnknownContextIdMintsFreshIdentity() {
        InboundContext context = new InboundContext();
        context.setOperationId("never-registered");
        tracker.inboundContext = context;

        CancellationHandle handle = tracker.tryAcquire(USER_A, "dm", SCOPE_A);
        assertNotNull(handle);
        assertFalse(handle.isCancelled());
        assertNull(tracker.tryAcquire(USER_A, "dm", SCOPE_A),
                "the fallback identity still enforces one RUNNING per (user, scope)");
    }

    /** The stage-final leak guard removes a never-adopted turn. */
    @Test
    void discardRemovesNeverAdoptedTurn() {
        tracker.registerQueued(USER_A, "dm", SCOPE_A, "turn-1");
        tracker.discard(USER_A, "dm", SCOPE_A, "turn-1");

        assertFalse(tracker.cancelQueuedTurns(USER_A, "dm", SCOPE_A),
                "a discarded turn must leave nothing for the sweep to mark");
    }
}
