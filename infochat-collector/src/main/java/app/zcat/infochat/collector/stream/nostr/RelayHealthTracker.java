package app.zcat.infochat.collector.stream.nostr;

import org.jspecify.annotations.NonNull;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Per-source relay health state machine driving the
 * "single misbehaving relay must not block the StreamSource" guarantee
 * from architecture.md §Ingest SPIs. One instance per {@link NostrStreamSource};
 * the per-relay reconnect loops in {@link NostrRelayConnection} consult it
 * to decide whether a relay is in cooldown, and report each productive or
 * unproductive (re)connect outcome.
 *
 * <h2>Two layers of state</h2>
 * <ul>
 *   <li><b>Per relay</b>: {@code consecutiveFailures} and {@code cooldownUntil}.
 *       After {@code failureThreshold} consecutive failures the relay enters a
 *       {@code cooldownDuration} window during which its worker parks
 *       ({@link #nextAttemptTime} returns the future expiry) instead of
 *       hot-looping reconnects.</li>
 *   <li><b>Source-level</b>: {@code consecutiveBadCycles}, {@code inAllBadCycle},
 *       {@code terminal}. A "cycle" is one transition into all-relays-bad;
 *       {@code consecutiveBadCycles} counts those entries and is the basis for
 *       the cycle-cap terminal-failure decision. Only a {@link #recordSuccess}
 *       resets the counter — passive cooldown expiry followed by another
 *       failure burst is, by design, a new cycle.</li>
 * </ul>
 *
 * <h2>Transition emission</h2>
 * <p>{@link #recordFailure} and {@link #recordSuccess} compute {@code allBad}
 * before and after applying the mutation. {@code !allBefore && allAfter} is
 * the entry-into-all-bad edge: {@code consecutiveBadCycles++}, then either
 * {@link Transition#TERMINAL} (cycle cap hit) or
 * {@link Transition#ALL_RELAYS_BAD}. A {@code recordSuccess} while
 * {@code inAllBadCycle} fires {@link Transition#RECOVERED} and resets the
 * cycle counter. The notifier callback runs OUTSIDE the synchronized
 * region so JDBC inside the callback (the ThrottledAdminNotifier persists
 * to {@code admin_notification_state}) does not block parallel
 * {@code recordFailure}/{@code recordSuccess} calls from other relay
 * workers.</p>
 */
final class RelayHealthTracker {

    /**
     * Source-level transition emitted by {@link #recordFailure} / {@link #recordSuccess}.
     * The notifier callback the constructor receives sees these and decides side effects;
     * {@link #NONE} suppresses the callback.
     */
    enum Transition {
        NONE,
        ALL_RELAYS_BAD,
        RECOVERED,
        TERMINAL
    }

    private final int failureThreshold;
    private final Duration cooldownDuration;
    private final int allRelaysBadCycleCap;
    private final Clock clock;
    private final Consumer<Transition> notifier;

    private final Map<URI, RelayState> states = new HashMap<>();

    private int consecutiveBadCycles;
    private boolean inAllBadCycle;
    private boolean terminal;

    RelayHealthTracker(@NonNull List<URI> relayUris, int failureThreshold,
                       @NonNull Duration cooldownDuration, int allRelaysBadCycleCap,
                       @NonNull Clock clock, @NonNull Consumer<Transition> notifier) {
        this.failureThreshold = failureThreshold;
        this.cooldownDuration = cooldownDuration;
        this.allRelaysBadCycleCap = allRelaysBadCycleCap;
        this.clock = clock;
        this.notifier = notifier;
        for (URI relayUri : relayUris) {
            states.put(relayUri, new RelayState());
        }
    }

    /**
     * Record one unproductive (re)connect outcome for {@code relay} — a relay
     * that closed without an EOSE / EVENT, or a connect attempt that never
     * established. After {@code failureThreshold} consecutive failures the
     * relay enters cooldown until {@code now + cooldownDuration}. Returns the
     * source-level transition the call caused, if any.
     */
    @NonNull
    Transition recordFailure(@NonNull URI relay) {
        Transition transition;
        synchronized (this) {
            if (terminal) {
                return Transition.NONE;
            }
            boolean allBefore = computeAllBad();
            RelayState state = stateOf(relay);
            state.consecutiveFailures++;
            if (state.consecutiveFailures >= failureThreshold) {
                state.cooldownUntil = clock.instant().plus(cooldownDuration);
            }
            boolean allAfter = computeAllBad();
            transition = decideTransition(allBefore, allAfter, false);
        }
        if (transition != Transition.NONE) {
            notifier.accept(transition);
        }
        return transition;
    }

    /**
     * Record one productive (re)connect outcome for {@code relay} — the relay
     * has sent EOSE or an EVENT, proving the subscription is live. Clears the
     * relay's failure count and cooldown. If the source was in an all-relays-bad
     * cycle, the recovery fires {@link Transition#RECOVERED} and resets the
     * cycle counter.
     */
    @NonNull
    Transition recordSuccess(@NonNull URI relay) {
        Transition transition;
        synchronized (this) {
            if (terminal) {
                return Transition.NONE;
            }
            boolean allBefore = computeAllBad();
            RelayState state = stateOf(relay);
            state.consecutiveFailures = 0;
            state.cooldownUntil = Instant.MIN;
            boolean allAfter = computeAllBad();
            transition = decideTransition(allBefore, allAfter, true);
        }
        if (transition != Transition.NONE) {
            notifier.accept(transition);
        }
        return transition;
    }

    /**
     * Time the {@code relay} worker should park until before its next connect
     * attempt: the future {@code cooldownUntil} when active, otherwise the
     * current clock instant (the runLoop then takes {@code max(this, backoff)}
     * so the per-attempt backoff floor still applies).
     */
    @NonNull
    synchronized Instant nextAttemptTime(@NonNull URI relay) {
        Instant now = clock.instant();
        Instant cooldownUntil = stateOf(relay).cooldownUntil;
        return cooldownUntil.isAfter(now) ? cooldownUntil : now;
    }

    /** True once the cycle cap has been hit; the runLoop exits and the source is permanently failed. */
    synchronized boolean isTerminal() {
        return terminal;
    }

    // Every relay passed to record*/nextAttemptTime was seeded into `states`
    // by the constructor, so the lookup never misses; assert that closed-set
    // invariant so NullAway sees a non-null RelayState at the deref sites.
    // (Private helper — inherits the package's non-null-by-default contract.)
    private RelayState stateOf(URI relay) {
        RelayState state = states.get(relay);
        if (state == null) {
            throw new IllegalStateException("RelayHealthTracker: no state for relay " + relay);
        }
        return state;
    }

    private boolean computeAllBad() {
        Instant now = clock.instant();
        for (RelayState state : states.values()) {
            if (!state.cooldownUntil.isAfter(now)) {
                return false;
            }
        }
        return true;
    }

    private Transition decideTransition(boolean allBefore, boolean allAfter, boolean wasSuccess) {
        // Entry into all-bad — increments the cycle counter. Passive cooldown
        // expiry followed by another failure burst that re-cooldowns every
        // relay is a NEW cycle (allBefore is false because the prior
        // cooldownUntil expired against the clock); only recordSuccess resets
        // the counter.
        if (!allBefore && allAfter) {
            consecutiveBadCycles++;
            inAllBadCycle = true;
            if (consecutiveBadCycles >= allRelaysBadCycleCap) {
                terminal = true;
                return Transition.TERMINAL;
            }
            return Transition.ALL_RELAYS_BAD;
        }
        // Recovery — the first productive frame on any relay during an
        // ongoing all-bad cycle clears the cycle counter and notifies. Fires
        // exactly once per cycle because inAllBadCycle is reset here.
        if (wasSuccess && inAllBadCycle) {
            consecutiveBadCycles = 0;
            inAllBadCycle = false;
            return Transition.RECOVERED;
        }
        return Transition.NONE;
    }

    private static final class RelayState {
        int consecutiveFailures;
        // Instant.MIN means "never in cooldown"; any value strictly after
        // clock.instant() means the relay is currently in cooldown.
        Instant cooldownUntil = Instant.MIN;
    }
}
