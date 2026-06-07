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
