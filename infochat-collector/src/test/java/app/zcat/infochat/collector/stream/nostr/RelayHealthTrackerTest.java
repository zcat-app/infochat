package app.zcat.infochat.collector.stream.nostr;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * State-machine unit tests for {@link RelayHealthTracker}. Pure JUnit
 * (no Quarkus container) with an injected {@link MutableClock} so the
 * cooldown / cycle assertions are deterministic without wall-clock waits.
 */
class RelayHealthTrackerTest {

    private static final URI RELAY_A = URI.create("ws://relay-a.example/");
    private static final URI RELAY_B = URI.create("ws://relay-b.example/");
    private static final URI RELAY_C = URI.create("ws://relay-c.example/");

    private static final int FAILURE_THRESHOLD = 3;
    private static final Duration COOLDOWN = Duration.ofMinutes(5);

    @Test
    void singleBadRelay_cooldownDoesNotBlockOthers() {
        MutableClock clock = new MutableClock(Instant.parse("2026-05-31T00:00:00Z"));
        List<RelayHealthTracker.Transition> transitions = new CopyOnWriteArrayList<>();
        RelayHealthTracker tracker = new RelayHealthTracker(
                List.of(RELAY_A, RELAY_B, RELAY_C),
                FAILURE_THRESHOLD, COOLDOWN, /*cycleCap=*/5, clock, transitions::add);

        // Relay A fails THRESHOLD times → enters cooldown. B and C remain healthy.
        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            assertEquals(RelayHealthTracker.Transition.NONE, tracker.recordFailure(RELAY_A));
        }

        Instant now = clock.instant();
        assertTrue(tracker.nextAttemptTime(RELAY_A).isAfter(now),
                "relay A parked until its cooldown expiry");
        assertEquals(now, tracker.nextAttemptTime(RELAY_B),
                "relay B is healthy — next attempt is now");
        assertEquals(now, tracker.nextAttemptTime(RELAY_C),
                "relay C is healthy — next attempt is now");

        // Other relays still freely report success (events flow through them).
        assertEquals(RelayHealthTracker.Transition.NONE, tracker.recordSuccess(RELAY_B));
        assertEquals(RelayHealthTracker.Transition.NONE, tracker.recordSuccess(RELAY_C));
        assertFalse(tracker.isTerminal());
        assertTrue(transitions.isEmpty(),
                "no source-level transition while only one relay is bad");
    }

    @Test
    void allRelaysBad_waitsForEarliestCooldown() {
        MutableClock clock = new MutableClock(Instant.parse("2026-05-31T00:00:00Z"));
        List<RelayHealthTracker.Transition> transitions = new CopyOnWriteArrayList<>();
        RelayHealthTracker tracker = new RelayHealthTracker(
                List.of(RELAY_A, RELAY_B, RELAY_C),
                FAILURE_THRESHOLD, COOLDOWN, /*cycleCap=*/5, clock, transitions::add);

        // Drive A to cooldown at T0.
        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            tracker.recordFailure(RELAY_A);
        }
        Instant expectedExpiryA = clock.instant().plus(COOLDOWN);

        // Advance time, then drive B to cooldown — B's cooldown expires AFTER A's.
        clock.advance(Duration.ofMinutes(1));
        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            tracker.recordFailure(RELAY_B);
        }
        Instant expectedExpiryB = clock.instant().plus(COOLDOWN);

        // Drive C to cooldown last; entry into all-bad fires here.
        clock.advance(Duration.ofMinutes(1));
        RelayHealthTracker.Transition last = RelayHealthTracker.Transition.NONE;
        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            last = tracker.recordFailure(RELAY_C);
        }
        Instant expectedExpiryC = clock.instant().plus(COOLDOWN);
        assertEquals(RelayHealthTracker.Transition.ALL_RELAYS_BAD, last,
                "last threshold-crossing call transitioned source into all-bad");
        assertEquals(List.of(RelayHealthTracker.Transition.ALL_RELAYS_BAD), transitions);

        // The earliest expiry across all relays is A's. The tracker, via its
        // per-relay nextAttemptTime API, signals wait-until = earliest expiry.
        Instant earliest = List.of(RELAY_A, RELAY_B, RELAY_C).stream()
                .map(tracker::nextAttemptTime)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        assertEquals(expectedExpiryA, earliest,
                "the earliest next-attempt time across the relay set is A's expiry");
        assertEquals(expectedExpiryA, tracker.nextAttemptTime(RELAY_A));
        assertEquals(expectedExpiryB, tracker.nextAttemptTime(RELAY_B));
        assertEquals(expectedExpiryC, tracker.nextAttemptTime(RELAY_C));
    }

    @Test
    void allRelaysBadCycleCap_terminalFailure() {
        MutableClock clock = new MutableClock(Instant.parse("2026-05-31T00:00:00Z"));
        List<RelayHealthTracker.Transition> transitions = new CopyOnWriteArrayList<>();
        int cycleCap = 3;
        RelayHealthTracker tracker = new RelayHealthTracker(
                List.of(RELAY_A, RELAY_B),
                FAILURE_THRESHOLD, COOLDOWN, cycleCap, clock, transitions::add);

        // Repeat (all-bad → cooldown expires → all-bad again) cycleCap times.
        // The entry-into-all-bad transition fires on the call that pushes the
        // LAST not-yet-in-cooldown relay over the threshold. After cycle 1,
        // consecutiveFailures persists across cooldown expiry (only
        // recordSuccess resets it), so in later cycles the FIRST recordFailure
        // on the last-out-of-cooldown relay is the trigger — not necessarily
        // the loop's final call. The test captures the non-NONE transition
        // wherever it lands inside the per-cycle burst.
        for (int cycle = 1; cycle <= cycleCap; cycle++) {
            RelayHealthTracker.Transition cycleTransition = driveBothIntoCooldownObserved(tracker);
            if (cycle < cycleCap) {
                assertEquals(RelayHealthTracker.Transition.ALL_RELAYS_BAD, cycleTransition,
                        "non-terminal cycle " + cycle + " fires ALL_RELAYS_BAD");
                // Skip past both cooldowns so the next failure burst opens a fresh cycle.
                clock.advance(COOLDOWN.plus(Duration.ofSeconds(1)));
            } else {
                assertEquals(RelayHealthTracker.Transition.TERMINAL, cycleTransition,
                        "cycle " + cycleCap + " hits the cap and fires TERMINAL");
                assertTrue(tracker.isTerminal());
            }
        }

        assertEquals(cycleCap - 1,
                transitions.stream()
                        .filter(t -> t == RelayHealthTracker.Transition.ALL_RELAYS_BAD).count(),
                "ALL_RELAYS_BAD fires once per non-terminal cycle");
        assertEquals(1L,
                transitions.stream()
                        .filter(t -> t == RelayHealthTracker.Transition.TERMINAL).count(),
                "TERMINAL fires exactly once");

        // Post-terminal: further outcomes are inert; the tracker stays in the terminal state.
        assertEquals(RelayHealthTracker.Transition.NONE, tracker.recordFailure(RELAY_A));
        assertEquals(RelayHealthTracker.Transition.NONE, tracker.recordSuccess(RELAY_B));
        assertTrue(tracker.isTerminal());
    }

    @Test
    void recoveryAfterAllRelaysBad_clearsCounter() {
        MutableClock clock = new MutableClock(Instant.parse("2026-05-31T00:00:00Z"));
        List<RelayHealthTracker.Transition> transitions = new CopyOnWriteArrayList<>();
        int cycleCap = 4;
        RelayHealthTracker tracker = new RelayHealthTracker(
                List.of(RELAY_A, RELAY_B),
                FAILURE_THRESHOLD, COOLDOWN, cycleCap, clock, transitions::add);

        // Drive two consecutive all-bad cycles (no recovery in between).
        driveBothIntoCooldown(tracker);
        clock.advance(COOLDOWN.plus(Duration.ofSeconds(1)));
        driveBothIntoCooldown(tracker);
        assertEquals(2L,
                transitions.stream()
                        .filter(t -> t == RelayHealthTracker.Transition.ALL_RELAYS_BAD).count(),
                "ALL_RELAYS_BAD fired twice");

        // Recovery on A clears the cycle counter and fires RECOVERED exactly once.
        assertEquals(RelayHealthTracker.Transition.RECOVERED, tracker.recordSuccess(RELAY_A));
        assertEquals(1L,
                transitions.stream()
                        .filter(t -> t == RelayHealthTracker.Transition.RECOVERED).count(),
                "RECOVERED fires exactly once on the first productive frame after all-bad");
        // A second success during the now-cleared state does not re-fire RECOVERED.
        assertEquals(RelayHealthTracker.Transition.NONE, tracker.recordSuccess(RELAY_B));

        // A new all-bad cycle counts as cycle 1, not cycle 3 — proves the counter reset.
        // With cycleCap=4, after recovery + one fresh cycle we are at cycle 1 — far
        // from terminal even though we previously hit 2 cycles before recovery.
        clock.advance(COOLDOWN.plus(Duration.ofSeconds(1)));
        driveBothIntoCooldown(tracker);
        assertFalse(tracker.isTerminal(),
                "post-recovery the counter restarted from 0; one fresh cycle is far from cap");
        assertEquals(3L,
                transitions.stream()
                        .filter(t -> t == RelayHealthTracker.Transition.ALL_RELAYS_BAD).count(),
                "the post-recovery all-bad entry adds one ALL_RELAYS_BAD notification");
    }

    @Test
    void untilNextAttempt_isRemainingCooldownAgainstInjectedClock() {
        MutableClock clock = new MutableClock(Instant.parse("2026-05-31T00:00:00Z"));
        RelayHealthTracker tracker = new RelayHealthTracker(
                List.of(RELAY_A, RELAY_B),
                FAILURE_THRESHOLD, COOLDOWN, /*cycleCap=*/5, clock, t -> { });

        // Healthy relay: nothing to park for.
        assertEquals(Duration.ZERO, tracker.untilNextAttempt(RELAY_A),
                "a relay that never failed has no cooldown to wait out");

        // Drive A into cooldown at T0 → remaining is the full cooldown window.
        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            tracker.recordFailure(RELAY_A);
        }
        assertEquals(COOLDOWN, tracker.untilNextAttempt(RELAY_A),
                "a fresh cooldown parks for the whole cooldown duration");

        // Advance the INJECTED clock partway; the remaining park shrinks by
        // exactly that much. Under the wall clock this 2026-05-31 cooldown is
        // already long expired (ZERO), so a non-ZERO remaining here can only come
        // from the injected Clock governing the subtraction (the §9 single-clock
        // property the old Duration.between(Instant.now(), ...) split lacked).
        clock.advance(Duration.ofMinutes(2));
        assertEquals(COOLDOWN.minus(Duration.ofMinutes(2)), tracker.untilNextAttempt(RELAY_A),
                "remaining park is measured against the injected Clock, not Instant.now()");

        // Advance past expiry → remaining floors at ZERO (the per-attempt backoff
        // curve then governs the actual park in the run loop).
        clock.advance(COOLDOWN);
        assertEquals(Duration.ZERO, tracker.untilNextAttempt(RELAY_A),
                "an expired cooldown yields ZERO; the backoff floor takes over");
        assertEquals(Duration.ZERO, tracker.untilNextAttempt(RELAY_B),
                "RELAY_B never failed → always ZERO");
    }

    private static void driveBothIntoCooldown(RelayHealthTracker tracker) {
        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            tracker.recordFailure(RELAY_A);
        }
        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            tracker.recordFailure(RELAY_B);
        }
    }

    /** Same as {@link #driveBothIntoCooldown} but returns the non-NONE transition fired inside the burst. */
    private static RelayHealthTracker.Transition driveBothIntoCooldownObserved(RelayHealthTracker tracker) {
        RelayHealthTracker.Transition observed = RelayHealthTracker.Transition.NONE;
        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            RelayHealthTracker.Transition t = tracker.recordFailure(RELAY_A);
            if (t != RelayHealthTracker.Transition.NONE) {
                observed = t;
            }
        }
        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            RelayHealthTracker.Transition t = tracker.recordFailure(RELAY_B);
            if (t != RelayHealthTracker.Transition.NONE) {
                observed = t;
            }
        }
        return observed;
    }

    /** Test-only clock — production gets {@code Clock.systemUTC()} from the Registrar. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant initial) {
            this.now = initial;
        }

        void advance(Duration delta) {
            this.now = this.now.plus(delta);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }
}
