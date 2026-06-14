package app.zcat.infochat.core.notifier;

/**
 * Result of a {@link ThrottledAdminNotifier#notifyOnce} call. Lets
 * callers (and tests) branch on whether the call actually emitted a
 * WARN log line without inspecting the logger output. Enum (not
 * boolean) because self-documenting at call sites:
 * {@code if (notifier.notifyOnce(...) == NotifyOutcome.EMITTED)}
 * reads better than {@code if (notifier.notifyOnce(...))} and lets
 * future additions (e.g. a {@code DELIVERED} state) extend the
 * contract without breaking callers.
 */
public enum NotifyOutcome {
    /** The call refreshed the row and emitted a WARN log line. */
    EMITTED,
    /** The call was within the throttle window; suppressed_count was incremented and no log emitted. */
    SUPPRESSED,
    /**
     * The notifier's own persistence path failed. A degraded-DB fallback WARN may
     * have been emitted on the canonical {@code admin-notifier-persistence-failed}
     * key (throttled one per window); {@code suppressed_count} was NOT incremented
     * (the DB is unreachable, so the counter cannot be bumped). Callers should not
     * retry on this outcome — the DB is down, not merely throttling this caller.
     * Distinct from {@link #SUPPRESSED}, whose contract (count bumped, no log) both
     * halves fail on this path.
     */
    PERSISTENCE_FAILED
}
