package app.zcat.infochat.provider.chat;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

        public CancellationHandle(Thread workerThread) {
            this.workerThread = workerThread;
        }

        public Thread workerThread() { return workerThread; }

        public void registerPgBackendPid(int pid) { pgBackendPid.set(pid); }

        public int pgBackendPid() { return pgBackendPid.get(); }

        public boolean hasPgBackendPid() { return pgBackendPid.get() > 0; }
    }

    private final ConcurrentHashMap<ScopeKey, CancellationHandle> inFlight = new ConcurrentHashMap<>();

    /**
     * Attempt to acquire the in-flight slot for the given (user, scope).
     * Returns true if the slot was free and is now held; false if already occupied.
     * Automatically captures Thread.currentThread() as the worker thread.
     */
    public boolean tryAcquire(UUID userId, String scopeKind, UUID scopeId) {
        CancellationHandle handle = new CancellationHandle(Thread.currentThread());
        return inFlight.putIfAbsent(new ScopeKey(userId, scopeKind, scopeId), handle) == null;
    }

    /**
     * Release the in-flight slot. Safe to call even if no slot was acquired.
     */
    public void release(UUID userId, String scopeKind, UUID scopeId) {
        inFlight.remove(new ScopeKey(userId, scopeKind, scopeId));
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
