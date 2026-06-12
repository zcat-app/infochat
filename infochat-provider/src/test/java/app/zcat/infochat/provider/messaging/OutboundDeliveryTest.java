package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.group.GroupRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit (no {@code @QuarkusTest}) — constructs {@link OutboundDelivery}
 * directly with a no-op back-off sleeper and recording doubles, so the retry,
 * cap-exhaustion, abort, and bot-removed-counter logic is pinned without a
 * transport, a database, or real back-off delays. Profile-driven DB effects
 * (the actual {@code removed_at} write and digest-scheduler exclusion, plus
 * the user-left membership soft-clear) are covered by
 * {@link OutboundDeliveryCleanupIT}.
 *
 * <p>Configuration mirrors the {@code laptop}/base profile: 3 attempts
 * (original + two retries), permanent-failure threshold 3.</p>
 */
class OutboundDeliveryTest {

    private static OutboundDelivery delivery(
            RecordingAdminNotifier notifier, GroupRepository groupRepository) {
        // base-delay 0 + no-op sleeper → the retry loop runs without waiting.
        return new OutboundDelivery(notifier, groupRepository, 3, 0L, 2.0, 3, millis -> { });
    }

    private static OutboundMessage dmMessage() {
        return new OutboundMessage(
                new ScopeRef.Dm("contact-1"), "hello", Instant.now(), UUID.randomUUID().toString());
    }

    private static OutboundMessage groupMessage() {
        return new OutboundMessage(
                new ScopeRef.Group("group-upstream-1"), "digest",
                Instant.now(), UUID.randomUUID().toString());
    }

    @Test
    void deliversOnSecondAttemptAfterOneTransientFailure() {
        FailingMessagingAdapter adapter =
                new FailingMessagingAdapter("chanA", 1, FailureCategory.TRANSIENT);
        OutboundDelivery delivery = delivery(new RecordingAdminNotifier(), new RecordingGroupRepository());

        MessageHandle handle = delivery.deliver(adapter, dmMessage());

        assertNotNull(handle, "delivery should succeed on the retry");
        assertEquals(2, adapter.sendAttempts.get(), "one failure then one successful retry");
    }

    @Test
    void thirdConsecutiveTransientFailureStopsRetrying() {
        FailingMessagingAdapter adapter =
                FailingMessagingAdapter.alwaysFailing("chanA", FailureCategory.TRANSIENT);
        OutboundDelivery delivery = delivery(new RecordingAdminNotifier(), new RecordingGroupRepository());

        MessageHandle handle = delivery.deliver(adapter, dmMessage());

        assertNull(handle, "exhausted retry budget aborts the reply");
        assertEquals(3, adapter.sendAttempts.get(), "original + two retries, no fourth attempt");
    }

    @Test
    void permanentFailureIsNotRetriedAndReplyIsAborted() {
        FailingMessagingAdapter adapter =
                FailingMessagingAdapter.alwaysFailing("chanA", FailureCategory.PERMANENT);
        OutboundDelivery delivery = delivery(new RecordingAdminNotifier(), new RecordingGroupRepository());

        MessageHandle handle = delivery.deliver(adapter, dmMessage());

        assertNull(handle, "permanent failure aborts the reply");
        assertEquals(1, adapter.sendAttempts.get(), "permanent failures are never retried");
    }

    @Test
    void capExhaustionNotifiesAdminPerChannelErrorClassAndDoesNotPingUser() {
        RecordingAdminNotifier notifier = new RecordingAdminNotifier();
        FailingMessagingAdapter adapter =
                FailingMessagingAdapter.alwaysFailing("chanA", FailureCategory.TRANSIENT);
        OutboundDelivery delivery = delivery(notifier, new RecordingGroupRepository());

        assertNull(delivery.deliver(adapter, dmMessage()));

        assertEquals(1, notifier.notifications.size(), "one admin notification on exhaustion");
        RecordingAdminNotifier.Notification n = notifier.notifications.get(0);
        assertEquals("chanA|TRANSIENT", n.key(), "throttle key encodes (channel, error_class)");
        assertEquals("TRANSIENT", n.errorClass());
        // The user is never pinged: the only sends were the failed attempts, and
        // there was no successful delivery (OutboundDelivery has no user-facing
        // failure-notice path).
        assertEquals(0, adapter.successfulSends.get(), "user is not pinged about the failure");
        assertEquals(3, adapter.sendAttempts.get());

        // A second exhaustion on the same (channel, error_class) reuses the same
        // throttle key, so the real ThrottledAdminNotifier's window dedupes it to
        // a single emission (that window dedup is the notifier's own contract).
        FailingMessagingAdapter adapter2 =
                FailingMessagingAdapter.alwaysFailing("chanA", FailureCategory.TRANSIENT);
        assertNull(delivery.deliver(adapter2, dmMessage()));
        assertEquals(2, notifier.notifications.size());
        assertEquals("chanA|TRANSIENT", notifier.notifications.get(1).key(), "same throttle key");
    }

    @Test
    void capExhaustionAdminNotificationOmitsTransportExceptionBody() {
        RecordingAdminNotifier notifier = new RecordingAdminNotifier();
        FailingMessagingAdapter adapter =
                FailingMessagingAdapter.alwaysFailing("chanA", FailureCategory.TRANSIENT);
        OutboundDelivery delivery = delivery(notifier, new RecordingGroupRepository());

        assertNull(delivery.deliver(adapter, dmMessage()));

        assertEquals(1, notifier.notifications.size());
        String message = notifier.notifications.get(0).message();
        // security.md §"User content in exceptions" (D37): a transport exception
        // message may quote user-authored prose, so the admin-notify line must
        // carry only channel + attempt-count, never the raw MessagingException
        // body. FailingMessagingAdapter throws "simulated TRANSIENT send
        // failure"; that body must not reach the ADMIN-NOTIFY log line.
        assertFalse(message.contains("simulated"),
                "admin-notify message must not interpolate the raw transport exception body");
        assertTrue(message.contains("chanA") && message.contains("exhausted the retry budget"),
                "admin-notify message retains the safe channel + attempt-count detail");
    }

    @Test
    void singlePermanentGroupFailureDoesNotTriggerCleanup() {
        RecordingGroupRepository repo = new RecordingGroupRepository();
        FailingMessagingAdapter adapter =
                FailingMessagingAdapter.alwaysFailing("chanA", FailureCategory.PERMANENT);
        OutboundDelivery delivery = delivery(new RecordingAdminNotifier(), repo);
        UUID groupId = UUID.randomUUID();

        assertNull(delivery.deliverToGroup(adapter, groupMessage(), groupId));

        assertTrue(repo.removed.isEmpty(),
                "threshold is 3 (> 1): a single permanent failure must not remove the group");
    }

    @Test
    void groupCleanupTriggersWhenConsecutivePermanentFailuresReachThreshold() {
        RecordingGroupRepository repo = new RecordingGroupRepository();
        OutboundDelivery delivery = delivery(new RecordingAdminNotifier(), repo);
        UUID groupId = UUID.randomUUID();

        for (int i = 0; i < 3; i++) {
            FailingMessagingAdapter adapter =
                    FailingMessagingAdapter.alwaysFailing("chanA", FailureCategory.PERMANENT);
            assertNull(delivery.deliverToGroup(adapter, groupMessage(), groupId));
        }

        assertEquals(List.of(groupId), repo.removed,
                "the group is soft-removed exactly once when the threshold is reached");
    }

    @Test
    void successfulGroupDeliveryResetsTheConsecutiveFailureCounter() {
        RecordingGroupRepository repo = new RecordingGroupRepository();
        OutboundDelivery delivery = delivery(new RecordingAdminNotifier(), repo);
        UUID groupId = UUID.randomUUID();

        // Two permanent failures, a success (resets the counter), then two more:
        // the streak never reaches three consecutive, so no cleanup fires.
        assertNull(delivery.deliverToGroup(
                FailingMessagingAdapter.alwaysFailing("chanA", FailureCategory.PERMANENT),
                groupMessage(), groupId));
        assertNull(delivery.deliverToGroup(
                FailingMessagingAdapter.alwaysFailing("chanA", FailureCategory.PERMANENT),
                groupMessage(), groupId));
        assertNotNull(delivery.deliverToGroup(
                new FailingMessagingAdapter("chanA", 0, FailureCategory.PERMANENT),
                groupMessage(), groupId));
        assertNull(delivery.deliverToGroup(
                FailingMessagingAdapter.alwaysFailing("chanA", FailureCategory.PERMANENT),
                groupMessage(), groupId));
        assertNull(delivery.deliverToGroup(
                FailingMessagingAdapter.alwaysFailing("chanA", FailureCategory.PERMANENT),
                groupMessage(), groupId));

        assertTrue(repo.removed.isEmpty(),
                "a successful delivery resets the consecutive-failure counter");
    }
}
