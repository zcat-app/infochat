package app.zcat.infochat.provider.chat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InFlightTrackerTest {

    private static final UUID USER_A = UUID.randomUUID();
    private static final UUID USER_B = UUID.randomUUID();
    private static final UUID SCOPE_A = UUID.randomUUID();
    private static final UUID SCOPE_B = UUID.randomUUID();

    private final InFlightTracker tracker = new InFlightTracker();

    @Test
    void rejectsConcurrentRequest() {
        assertTrue(tracker.tryAcquire(USER_A, "dm", SCOPE_A));
        assertFalse(tracker.tryAcquire(USER_A, "dm", SCOPE_A));

        tracker.release(USER_A, "dm", SCOPE_A);

        assertTrue(tracker.tryAcquire(USER_A, "dm", SCOPE_A));
    }

    @Test
    void independentScopesDoNotConflict() {
        assertTrue(tracker.tryAcquire(USER_A, "dm", SCOPE_A));
        assertTrue(tracker.tryAcquire(USER_A, "dm", SCOPE_B));
        assertTrue(tracker.tryAcquire(USER_B, "dm", SCOPE_A));

        tracker.release(USER_A, "dm", SCOPE_A);
        tracker.release(USER_A, "dm", SCOPE_B);
        tracker.release(USER_B, "dm", SCOPE_A);
    }

    @Test
    void releaseWithoutAcquireIsNoOp() {
        tracker.release(USER_A, "dm", SCOPE_A);
        assertTrue(tracker.tryAcquire(USER_A, "dm", SCOPE_A));
    }

    @Test
    void isInFlightReflectsState() {
        assertFalse(tracker.isInFlight(USER_A, "dm", SCOPE_A));
        tracker.tryAcquire(USER_A, "dm", SCOPE_A);
        assertTrue(tracker.isInFlight(USER_A, "dm", SCOPE_A));
        tracker.release(USER_A, "dm", SCOPE_A);
        assertFalse(tracker.isInFlight(USER_A, "dm", SCOPE_A));
    }
}
