package app.zcat.infochat.provider.chat;

import jakarta.enterprise.context.ApplicationScoped;
import org.jspecify.annotations.NonNull;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Enforces at most one in-flight interruptible request per (user, scope).
// Consumed by ChatAgent (this ticket) and by /stop (M1-065).
@ApplicationScoped
public class InFlightTracker {

    record ScopeKey(@NonNull UUID userId, @NonNull String scopeKind, @NonNull UUID scopeId) {}

    private final ConcurrentHashMap<ScopeKey, Boolean> inFlight = new ConcurrentHashMap<>();

    /**
     * Attempt to acquire the in-flight slot for the given (user, scope).
     * Returns true if the slot was free and is now held; false if already occupied.
     */
    public boolean tryAcquire(@NonNull UUID userId, @NonNull String scopeKind, @NonNull UUID scopeId) {
        return inFlight.putIfAbsent(new ScopeKey(userId, scopeKind, scopeId), Boolean.TRUE) == null;
    }

    /**
     * Release the in-flight slot. Safe to call even if no slot was acquired.
     */
    public void release(@NonNull UUID userId, @NonNull String scopeKind, @NonNull UUID scopeId) {
        inFlight.remove(new ScopeKey(userId, scopeKind, scopeId));
    }

    /**
     * Check whether a slot is currently held. Read-only — used by /stop to
     * decide whether there is an in-flight request to cancel.
     */
    public boolean isInFlight(@NonNull UUID userId, @NonNull String scopeKind, @NonNull UUID scopeId) {
        return inFlight.containsKey(new ScopeKey(userId, scopeKind, scopeId));
    }
}
