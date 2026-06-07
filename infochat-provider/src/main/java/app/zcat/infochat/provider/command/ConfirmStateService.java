package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.ScopeRef;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory pending-confirm store for destructive admin commands.
 * Keyed by {@code (UUID actorUserId, ScopeRef scope)}; one pending
 * entry per key (a second {@link #remember} for the same key
 * overwrites the first, per acceptance item 7 — the spec models the
 * second remember as a fresh intent, not a multi-pending merge).
 *
 * <p>Per {@code docs/spec/commands.md} §Surface conventions:
 * confirmation state is in-memory only (no DB persistence), expires
 * lazily against {@code infochat.confirm.timeout}, and is cleared on
 * Provider restart. The cancellation side-effect ("any other input
 * cancels") is enforced by {@link app.zcat.infochat.provider.messaging.InboundRouter}'s
 * step 4.5 sweep, NOT by this service — handlers consume the
 * {@link #takeMatching} / {@link #takeAny} / {@link #peek} surface
 * without probing for cancellation themselves.</p>
 *
 * <p><b>Clock seam.</b> {@link Clock} is consumed via CDI; the
 * production {@link Clock#systemUTC()} producer lives in
 * {@code ThrottledAdminNotifier} (infochat-core).
 * Plain-JUnit tests instantiate the service directly with the
 * package-private test constructor; {@code @QuarkusTest} callers
 * advance time via the package-private {@link #setClock} setter.</p>
 */
@ApplicationScoped
public class ConfirmStateService {

    @ConfigProperty(name = "infochat.confirm.timeout", defaultValue = "60s")
    Duration timeout;

    @Inject
    Clock clock;

    private final ConcurrentHashMap<ConfirmKey, Stored> pending = new ConcurrentHashMap<>();

    public ConfirmStateService() {
        // CDI no-arg constructor; @ConfigProperty + @Inject fields populated post-construction.
    }

    /**
     * Test seam: plain-JUnit tests bypass CDI and instantiate the
     * service with a controllable {@link Clock} + explicit timeout.
     * Package-private — the rest of the provider tree consumes the
     * bean via CDI.
     */
    ConfirmStateService(Clock clock, Duration timeout) {
        this.clock = clock;
        this.timeout = timeout;
    }

    /**
     * Test seam: {@code @QuarkusTest} callers swap the clock in
     * {@code @BeforeEach} to drive deadline-boundary assertions; the
     * @Inject default is restored by setting it back to
     * {@link Clock#systemUTC()} on @AfterEach.
     */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Test seam: report the current pending-map size so service-tier
     * tests can assert overwrite / removal invariants without
     * exposing the map directly. Package-private.
     */
    int size() {
        return pending.size();
    }

    /**
     * Configured timeout in whole seconds, used by handlers to
     * interpolate the prompt bundle's timeout token. The service
     * reads the same {@code infochat.confirm.timeout} the deadline
     * arithmetic uses, so the prompt and the lazy-expiry stay in
     * lock-step.
     */
    public long timeoutSeconds() {
        return timeout.getSeconds();
    }

    /**
     * Store {@code pendingConfirm} under {@code (actorUserId, scope)}
     * with deadline {@code clock.instant() + timeout}. A prior
     * pending under the same key is replaced (acceptance item 7).
     */
    public void remember(UUID actorUserId,
                         ScopeRef scope,
                         PendingConfirm pendingConfirm) {
        Instant deadline = clock.instant().plus(timeout);
        pending.put(new ConfirmKey(actorUserId, scope), new Stored(pendingConfirm, deadline));
    }

    /**
     * Pop the pending iff the stored entry's {@code commandName}
     * equals {@code commandName} AND the deadline has not passed.
     * Lazy expiry: a past-deadline entry is removed and an empty
     * Optional returned.
     */
    public Optional<PendingConfirm> takeMatching(UUID actorUserId,
                                                 ScopeRef scope,
                                                 String commandName) {
        AtomicReference<PendingConfirm> result = new AtomicReference<>();
        pending.compute(new ConfirmKey(actorUserId, scope), (k, stored) -> {
            if (stored == null) {
                return null;
            }
            if (!clock.instant().isBefore(stored.deadline)) {
                // expired — remove
                return null;
            }
            if (!stored.pending.commandName().equals(commandName)) {
                // mismatch — keep
                return stored;
            }
            result.set(stored.pending);
            return null;
        });
        return Optional.ofNullable(result.get());
    }

    /**
     * Pop the pending regardless of {@code commandName}, deadline-checked.
     * The router's step 4.5 sweep uses this to drain a pending entry
     * when any non-confirm-shape input arrives.
     */
    public Optional<PendingConfirm> takeAny(UUID actorUserId,
                                            ScopeRef scope) {
        AtomicReference<PendingConfirm> result = new AtomicReference<>();
        pending.compute(new ConfirmKey(actorUserId, scope), (k, stored) -> {
            if (stored == null) {
                return null;
            }
            if (!clock.instant().isBefore(stored.deadline)) {
                return null;
            }
            result.set(stored.pending);
            return null;
        });
        return Optional.ofNullable(result.get());
    }

    /**
     * Read the pending without removing it. Deadline-checked: a
     * past-deadline entry is removed and an empty Optional returned.
     */
    public Optional<PendingConfirm> peek(UUID actorUserId,
                                         ScopeRef scope) {
        ConfirmKey key = new ConfirmKey(actorUserId, scope);
        Stored stored = pending.get(key);
        if (stored == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(stored.deadline)) {
            // Lazy expiry: drop the past-deadline entry. The CAS-style
            // remove(key, value) avoids racing with a concurrent
            // remember() that put a fresh entry under the same key
            // between the get above and this remove.
            pending.remove(key, stored);
            return Optional.empty();
        }
        return Optional.of(stored.pending);
    }

    /** Composite key per spec §Surface conventions: per-(user, scope) isolation. */
    private record ConfirmKey(UUID actorUserId, ScopeRef scope) {}

    /** Map value: the pending payload plus its absolute deadline. */
    private record Stored(PendingConfirm pending, Instant deadline) {}

    /**
     * Typed pending-confirm payload. Open-extension contract: each
     * confirmable command provides its own top-level record implementing
     * this interface, located alongside its command handler. The
     * confirmable-command catalogue is still a design-doc invariant
     * enumerated in {@code docs/design/03-commands.md} §Confirmation
     * for destructive commands; this interface no longer mechanically
     * enforces the closure via a {@code sealed} / {@code permits}
     * clause, because every new variant ships with the matching
     * handler-side {@code remember} / {@code takeMatching} wiring in
     * the same ticket — the spec coupling lives in the design doc, not
     * in this file's permits list.
     *
     * <p>Each implementing record MUST satisfy two contracts:</p>
     * <ul>
     *   <li>{@link #commandName()} MUST equal the {@code takeMatching}
     *       key the matching handler passes — and MUST be unique across
     *       the catalogue (a colon-namespaced form is used where one
     *       handler exposes multiple confirmable subcommands, e.g.
     *       {@code "invite:create:open"} vs {@code "invite:revoke"}).
     *       The router's step 4.5 sweep does NOT consume this.</li>
     *   <li>{@link #sweepPrefix()} MUST equal the slash-stripped user-
     *       visible prefix the router's step 4.5 sweep matches against
     *       to recognize the confirm-shape body. The canonical confirm
     *       form is {@code "/" + sweepPrefix() + " confirm"}; the
     *       "args retyped" relaxation is any body starting with
     *       {@code "/" + sweepPrefix() + " "} that ends in
     *       {@code " confirm"}.</li>
     * </ul>
     */
    public interface PendingConfirm {

        /** Colon-namespaced takeMatching key (see interface javadoc). */
        String commandName();

        /**
         * Slash-stripped user-visible prefix the router's step 4.5
         * sweep matches against to recognize the confirm-shape body.
         */
        String sweepPrefix();
    }
}
