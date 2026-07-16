package app.zcat.infochat.provider.chat;

import jakarta.enterprise.context.ApplicationScoped;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

// Enforces at most one in-flight interruptible request per (user, scope).
// Consumed by ChatAgent (M1-063) and by /stop + CancellationService (M1-065).
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

    private final ConcurrentHashMap<ScopeKey, CancellationHandle> inFlight = new ConcurrentHashMap<>();

    /**
     * Attempt to acquire the in-flight slot for the given (user, scope).
     * Returns the handle now holding the slot, or null if the slot is
     * already occupied. Automatically captures Thread.currentThread()
     * as the worker thread. The caller retains the handle and passes it
     * back to {@link #release} so only the current holder can free the slot.
     */
    public @Nullable CancellationHandle tryAcquire(UUID userId, String scopeKind, UUID scopeId) {
        CancellationHandle handle = new CancellationHandle(Thread.currentThread());
        boolean acquired =
                inFlight.putIfAbsent(new ScopeKey(userId, scopeKind, scopeId), handle) == null;
        return acquired ? handle : null;
    }

    /**
     * Release the in-flight slot iff it is still held by the given handle
     * (two-arg {@code ConcurrentHashMap.remove(key, value)}; handles
     * compare by identity). A stale release — a worker's finally firing
     * after /stop already freed the slot and a newer request re-acquired
     * it — is a no-op, leaving the new holder's cancellation path intact.
     */
    public void release(UUID userId, String scopeKind, UUID scopeId, CancellationHandle handle) {
        inFlight.remove(new ScopeKey(userId, scopeKind, scopeId), handle);
    }

    /**
     * Check whether a slot is currently held. Read-only — used by /stop to
     * decide whether there is an in-flight request to cancel.
     */
    public boolean isInFlight(UUID userId, String scopeKind, UUID scopeId) {
        return inFlight.containsKey(new ScopeKey(userId, scopeKind, scopeId));
    }

    /**
     * Retrieve the cancellation handle for an in-flight slot, if one exists.
     * Used by CancellationService to interrupt the worker and issue pg_cancel_backend.
     */
    public Optional<CancellationHandle> getCancellationHandle(
            UUID userId, String scopeKind, UUID scopeId) {
        return Optional.ofNullable(inFlight.get(new ScopeKey(userId, scopeKind, scopeId)));
    }
}
