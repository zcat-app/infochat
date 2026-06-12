package app.zcat.infochat.provider.chat;

import jakarta.enterprise.context.ApplicationScoped;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

        public CancellationHandle(Thread workerThread) {
            this.workerThread = workerThread;
        }

        public Thread workerThread() { return workerThread; }

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
