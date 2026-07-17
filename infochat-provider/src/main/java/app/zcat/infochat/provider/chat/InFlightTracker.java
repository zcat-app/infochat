package app.zcat.infochat.provider.chat;

import app.zcat.infochat.provider.messaging.InboundContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

// The single interruptible-turn lifecycle registry (M1-638). One entry per
// interruptible dispatch, born at submit (registerQueued) and living to its
// terminal: QUEUED (pre-worker, no thread, no handle) → RUNNING (worker
// attached at tryAcquire, thread-bound CancellationHandle) → gone (released,
// consumed-after-cancel, or discarded). The pre-M1-634 in-flight guard is a
// DERIVED property of this lifecycle — at most one RUNNING turn per
// (user, scope), decided at the same tryAcquire call with the same reject —
// not a separate map that only exists once a thread does. That derivation is
// what lets /stop reach a turn still sitting in the InterruptibleDispatcher
// pool queue (cancelQueuedTurns), which a thread-keyed map structurally
// could not express. Consumed by ChatAgent (M1-063), /summary, /retry,
// InboundRouter (turn registration), and /stop + CancellationService (M1-065).
@ApplicationScoped
public class InFlightTracker {

    record ScopeKey(UUID userId, String scopeKind, UUID scopeId) {}

    /**
     * Handle stored alongside an in-flight slot. Captures the worker
     * thread (for interrupt) and an optional PG backend PID (for
     * pg_cancel_backend). The PID is registered lazily by tool-call
     * code after acquiring a DB connection; it may never be set if
     * the in-flight work is pure LLM (no DB query).
     */
    public static class CancellationHandle {
        private final Thread workerThread;
        private final AtomicInteger pgBackendPid = new AtomicInteger(-1);
        // Source of truth for "the user said /stop on this request". Set by
        // CancellationService.cancel() BEFORE the worker interrupt, and read
        // at the delivery boundaries (ChatAgent reply, summary progress
        // terminal). The interrupt status alone cannot distinguish a /stop
        // from any other interrupt, and a worker that finished between
        // interruptible points never observes the interrupt at all — so the
        // flag, not the interrupt, decides whether a result is discarded.
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        // Gate for cancellation side effects (M1-634 redteam, 2026-07-16):
        // closed by the worker at the end of its in-flight section. Guarded
        // by the handle monitor so interruptWorker()'s check-then-interrupt
        // and releaseWorker()'s close-then-clear are mutually atomic —
        // without the gate, a /stop descheduled between reading the handle
        // and interrupting could land the interrupt on the pool thread AFTER
        // it recycled to a different (user, scope)'s turn.
        private boolean workerReleased;

        public CancellationHandle(Thread workerThread) {
            this.workerThread = workerThread;
        }

        /**
         * Interrupt the captured worker thread iff it is still inside this
         * handle's in-flight section ({@link #releaseWorker()} has not run).
         * Returns whether the interrupt was issued, so the caller can
         * suppress companion cancellation side effects (pg_cancel_backend)
         * on the same staleness signal. Atomic with {@link #releaseWorker()}
         * via the handle monitor: once the gate is closed, no interrupt from
         * this handle can reach the (possibly recycled) thread.
         */
        public synchronized boolean interruptWorker() {
            if (workerReleased) {
                return false;
            }
            workerThread.interrupt();
            return true;
        }

        /**
         * Close the cancellation gate at the end of the in-flight section
         * and clear the calling thread's interrupt status in the same atomic
         * step, so a /stop interrupt that already landed — or is being
         * issued concurrently under the monitor — never leaks past this
         * section into the pool thread's next, possibly different-user,
         * task. MUST run on the captured worker thread as the last statement
         * of the in-flight {@code finally}.
         */
        public synchronized void releaseWorker() {
            workerReleased = true;
            Thread.interrupted();
        }

        /** Mark this in-flight request as cancelled by /stop. */
        public void markCancelled() { cancelled.set(true); }

        /** Whether /stop has marked this request cancelled. */
        public boolean isCancelled() { return cancelled.get(); }

        public void registerPgBackendPid(int pid) { pgBackendPid.set(pid); }

        public int pgBackendPid() { return pgBackendPid.get(); }

        public boolean hasPgBackendPid() { return pgBackendPid.get() > 0; }
    }

    /** A QUEUED turn's only mutable lifecycle fact: whether /stop marked it. */
    private static final class QueuedTurn {
        boolean cancelled;
    }

    /**
     * All non-terminal turns for one (user, scope): the QUEUED turns keyed
     * by turn id — per-turn keying, so two same-scope queued turns coexist
     * and a second submit can never clobber the first — plus the at-most-one
     * RUNNING turn's handle. State is encoded by location: an id in
     * {@code queued} is QUEUED (or CANCELLED when its flag is set); the
     * {@code running} handle is the RUNNING turn; absent is terminal.
     */
    private static final class ScopeEntry {
        final Map<String, QueuedTurn> queued = new LinkedHashMap<>();
        // volatile: written under the map's per-key compute lock, but read
        // lock-free by isInFlight/getCancellationHandle peeks.
        volatile @Nullable CancellationHandle running;

        boolean isEmpty() {
            return queued.isEmpty() && running == null;
        }
    }

    /**
     * One registry, indexed by scope with per-turn keying inside each entry
     * (the M1-638 "key by turn identity, index by scope" model). Every
     * lifecycle transition runs inside a {@code compute}-family call on this
     * map, which is what makes the derived admission check ("no other
     * RUNNING turn on this key") atomic with the QUEUED→RUNNING transition —
     * two workers dequeuing same-key turns cannot both be admitted. Entries
     * whose last turn leaves return null from their compute and vanish, so
     * the map never grows one entry per (user, scope) ever seen.
     */
    private final ConcurrentHashMap<ScopeKey, ScopeEntry> turns = new ConcurrentHashMap<>();

    /**
     * The submitting router seeds each interruptible stage's purpose-minted
     * turn id as the worker context's operationId (M1-635); reading it back
     * here is how {@link #tryAcquire} knows WHICH queued turn the calling
     * worker is serving without any signature change for the three handler
     * call sites. Plain-construction seam ({@code new InFlightTracker()} in
     * unit tests, the router's field-init default): the field stays null and
     * tryAcquire falls back to minting a fresh identity — reproducing the
     * pre-M1-638 putIfAbsent semantics for every direct caller. Same
     * test-seam pattern as {@code AdapterMetrics.noop()} /
     * {@code InterruptibleDispatcher.direct()}.
     */
    @Inject
    @Nullable
    InboundContext inboundContext;

    /**
     * Register an interruptible turn at submit time, before its stage
     * crosses the dispatcher hop. The turn enters the QUEUED state: visible
     * to {@link #cancelQueuedTurns} (so /stop can reach it before any worker
     * attaches) but holding no slot and no {@link CancellationHandle} — the
     * handle stays thread-bound and simply does not exist until
     * {@link #tryAcquire} attaches a worker. Exactly one removal owner per
     * turn: adoption ({@code tryAcquire}), preamble consumption
     * ({@link #consumeIfCancelled}), or the stage-final {@link #discard}.
     */
    public void registerQueued(UUID userId, String scopeKind, UUID scopeId, String turnId) {
        turns.compute(new ScopeKey(userId, scopeKind, scopeId), (key, entry) -> {
            ScopeEntry scopeEntry = entry == null ? new ScopeEntry() : entry;
            scopeEntry.queued.put(turnId, new QueuedTurn());
            return scopeEntry;
        });
    }

    /**
     * Stage-preamble probe: atomically remove-and-report the turn iff /stop
     * cancelled it while it was QUEUED. True at most once per turn — the
     * atomic remove is what makes the preamble's skip path and the
     * handlers' adopted-cancelled arms mutually exclusive, so exactly one
     * side publishes the D35 stopped terminal. An uncancelled turn is left
     * in place for adoption.
     */
    public boolean consumeIfCancelled(UUID userId, String scopeKind, UUID scopeId, String turnId) {
        boolean[] consumed = new boolean[1];
        turns.computeIfPresent(new ScopeKey(userId, scopeKind, scopeId), (key, entry) -> {
            QueuedTurn turn = entry.queued.get(turnId);
            if (turn != null && turn.cancelled) {
                entry.queued.remove(turnId);
                consumed[0] = true;
            }
            return entry.isEmpty() ? null : entry;
        });
        return consumed[0];
    }

    /**
     * The /stop sweep over the pre-worker phase: mark every QUEUED turn on
     * this exact (user, scope) key cancelled. Returns whether any turn was
     * NEWLY marked — a repeat /stop against already-swept turns reports
     * false, matching the running path's second-/stop-is-a-noop semantics
     * (the slot is freed on the first cancel). Marked turns stay registered
     * until their stage consumes them; cancellation never reorders the pool
     * queue (the skip happens in place, 06-messaging.md §6.3 deferral).
     */
    public boolean cancelQueuedTurns(UUID userId, String scopeKind, UUID scopeId) {
        boolean[] newlyMarked = new boolean[1];
        turns.computeIfPresent(new ScopeKey(userId, scopeKind, scopeId), (key, entry) -> {
            for (QueuedTurn turn : entry.queued.values()) {
                if (!turn.cancelled) {
                    turn.cancelled = true;
                    newlyMarked[0] = true;
                }
            }
            return entry;
        });
        return newlyMarked[0];
    }

    /**
     * Stage-final leak guard: remove a never-adopted turn (a pre-handler
     * Reply arm, an in-flight reject, an escaped exception ended the stage
     * before any {@code tryAcquire} consumed the entry). No-op for adopted
     * turns — adoption already removed the queued entry, and turn ids are
     * never reused. A missed removal here is M1-636's permanent-lockout
     * hazard, which is why the router calls this from a {@code finally}.
     */
    public void discard(UUID userId, String scopeKind, UUID scopeId, String turnId) {
        turns.computeIfPresent(new ScopeKey(userId, scopeKind, scopeId), (key, entry) -> {
            entry.queued.remove(turnId);
            return entry.isEmpty() ? null : entry;
        });
    }

    /**
     * Attempt to acquire the in-flight slot for the given (user, scope).
     * Returns the handle now holding the slot, or null if another turn is
     * already RUNNING on this key — the same reject, on the same worker, at
     * the same call the guard has fired on since M1-063. Automatically
     * captures Thread.currentThread() as the worker thread. The caller
     * retains the handle and passes it back to {@link #release} so only the
     * current holder can free the slot.
     *
     * <p>Adoption (M1-638): when the calling context carries a seeded turn
     * id with a QUEUED entry on this key, acquisition IS that turn's
     * QUEUED→RUNNING transition — one identity from submit to terminal. A
     * turn /stop cancelled between the stage preamble and this call is
     * adopted with its handle pre-marked cancelled (never rejected with
     * null: "request already in progress" would be a lie after /stop freed
     * everything), so the handlers' existing cancelled arms fire before any
     * LLM work. With no context or no matching entry, a fresh identity is
     * minted — the pre-M1-638 semantics, byte-for-byte.</p>
     */
    public @Nullable CancellationHandle tryAcquire(UUID userId, String scopeKind, UUID scopeId) {
        @Nullable String seededTurnId = seededTurnId();
        CancellationHandle[] acquired = new CancellationHandle[1];
        turns.compute(new ScopeKey(userId, scopeKind, scopeId), (key, entry) -> {
            ScopeEntry scopeEntry = entry == null ? new ScopeEntry() : entry;
            if (scopeEntry.running != null) {
                return scopeEntry;
            }
            CancellationHandle handle = new CancellationHandle(Thread.currentThread());
            if (seededTurnId != null) {
                QueuedTurn adopted = scopeEntry.queued.remove(seededTurnId);
                if (adopted != null && adopted.cancelled) {
                    handle.markCancelled();
                }
            }
            scopeEntry.running = handle;
            acquired[0] = handle;
            return scopeEntry;
        });
        return acquired[0];
    }

    /**
     * The turn id the submitting router seeded into the calling thread's
     * dispatch context, or null when no seeded identity exists: plain
     * construction (the field is null), or a caller outside any inbound
     * dispatch — a bare worker thread driving {@code tryAcquire} directly,
     * where the {@code @RequestScoped} client proxy throws the CDI-standard
     * inactive-context signal. The router's stage paths always run inside
     * an active, seeded request context, so production acquisition never
     * takes either fallback.
     */
    private @Nullable String seededTurnId() {
        if (inboundContext == null) {
            return null;
        }
        try {
            return inboundContext.operationId();
        } catch (ContextNotActiveException e) {
            return null;
        }
    }

    /**
     * Release the in-flight slot iff it is still held by the given handle
     * (identity comparison). A stale release — a worker's finally firing
     * after /stop already freed the slot and a newer request re-acquired
     * it — is a no-op, leaving the new holder's cancellation path intact.
     */
    public void release(UUID userId, String scopeKind, UUID scopeId, CancellationHandle handle) {
        turns.computeIfPresent(new ScopeKey(userId, scopeKind, scopeId), (key, entry) -> {
            if (entry.running == handle) {
                entry.running = null;
            }
            return entry.isEmpty() ? null : entry;
        });
    }

    /**
     * Check whether a RUNNING turn currently holds the slot. Read-only —
     * used by /stop to decide whether there is an in-flight request to
     * cancel. QUEUED turns are deliberately invisible here: they hold no
     * slot and block no admission.
     */
    public boolean isInFlight(UUID userId, String scopeKind, UUID scopeId) {
        ScopeEntry entry = turns.get(new ScopeKey(userId, scopeKind, scopeId));
        return entry != null && entry.running != null;
    }

    /**
     * A sender's non-terminal (QUEUED + RUNNING) turn count across ALL
     * scopes — the M1-636 per-user cross-scope concurrency cap is a query
     * over this registry, never a second counter that could drift: a turn
     * reaching a terminal state IS the release, so no decrement can be
     * missed or doubled. Each entry is read back through computeIfPresent
     * because {@code queued} is mutated only inside compute-family lambdas
     * on the owning key — a bare get() would read the LinkedHashMap with no
     * happens-before edge to those writes. Cross-key the sum is a
     * moment-in-time snapshot, which is sufficient for admission: same-user
     * increments are serialized on the sender's single transport dispatch
     * thread (the admission check's caller — a user's identity is
     * adapter-scoped, so all their submits arrive on one thread), and a
     * concurrent worker-side decrement can only make the sum conservatively
     * high for one check, self-correcting on the next message.
     */
    public int countNonTerminalTurns(UUID userId) {
        int[] count = new int[1];
        for (ScopeKey key : turns.keySet()) {
            if (key.userId().equals(userId)) {
                turns.computeIfPresent(key, (scopeKey, entry) -> {
                    count[0] += entry.queued.size() + (entry.running != null ? 1 : 0);
                    return entry;
                });
            }
        }
        return count[0];
    }

    /**
     * Retrieve the cancellation handle for the RUNNING turn, if one exists.
     * Used by CancellationService to interrupt the worker and issue
     * pg_cancel_backend. A QUEUED turn has no reachable handle — the handle
     * is thread-bound and does not exist until a worker attaches.
     */
    public Optional<CancellationHandle> getCancellationHandle(
            UUID userId, String scopeKind, UUID scopeId) {
        ScopeEntry entry = turns.get(new ScopeKey(userId, scopeKind, scopeId));
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.running);
    }
}
