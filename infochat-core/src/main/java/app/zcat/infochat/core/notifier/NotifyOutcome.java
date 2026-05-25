package app.zcat.infochat.core.notifier;

/**
 * Result of a {@link ThrottledAdminNotifier#notifyOnce} call. Lets
 * callers (and tests) branch on whether the call actually emitted a
 * WARN log line without inspecting the logger output. Enum (not
 * boolean) because self-documenting at call sites:
 * {@code if (notifier.notifyOnce(...) == NotifyOutcome.EMITTED)}
 * reads better than {@code if (notifier.notifyOnce(...))} and lets
 * future additions (e.g. {@code DELIVERED} once the
 * AdminNotificationDelivery SPI lands) extend the contract without
 * breaking callers.
 */
public enum NotifyOutcome {
    /** The call refreshed the row and emitted a WARN log line. */
    EMITTED,
    /** The call was within the throttle window; suppressed_count was incremented and no log emitted. */
    SUPPRESSED
}
