package app.zcat.infochat.core.notifier;

import java.time.Instant;

/**
 * Immutable snapshot of one {@code admin_notification_state} row.
 * Returned by {@link ThrottledAdminNotifier} read accessors so tests
 * (and future admin commands surfacing the notifier's state to bot
 * admins) can observe the DB-side counters without re-reading the
 * table themselves.
 *
 * <p>The record's components mirror the V16 schema 1:1:
 * <ul>
 *   <li>{@code key} — the caller-supplied notification key (PK).</li>
 *   <li>{@code errorClass} — error_class from the most recent emitting
 *       notifyOnce call for the key; an emit overwrites it, a suppressed
 *       call leaves it unchanged.</li>
 *   <li>{@code lastNotifiedAt} — wall-clock of the most recent emit.</li>
 *   <li>{@code notificationCount} — total emits since {@code firstSeenAt}.</li>
 *   <li>{@code suppressedCount} — total within-window suppressions
 *       since {@code firstSeenAt}.</li>
 *   <li>{@code firstSeenAt} — when this key was first observed.</li>
 * </ul>
 */
public record AdminNotificationRecord(
    String key,
    String errorClass,
    Instant lastNotifiedAt,
    long notificationCount,
    long suppressedCount,
    Instant firstSeenAt
) {}
