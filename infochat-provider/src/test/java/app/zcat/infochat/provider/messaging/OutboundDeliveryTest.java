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

    private static OutboundMessage dmMessage(String text) {
        return new OutboundMessage(
                new ScopeRef.Dm("contact-1"), text, Instant.now(), UUID.randomUUID().toString());
    }

    private static OutboundMessage groupMessage() {
        return new OutboundMessage(
                new ScopeRef.Group("group-upstream-1"), "digest",
                Instant.now(), UUID.randomUUID().toString());
    }

    private static OutboundMessage groupMessage(String text) {
        return new OutboundMessage(
                new ScopeRef.Group("group-upstream-1"), text, Instant.now(), UUID.randomUUID().toString());
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

    @Test
    void sequenceAttributesAtMostOneCounterOutcome() {
        // A digest slot produces N category messages through
        // deliverSequenceToGroup. The per-group permanent-failure counter
        // receives at most ONE aggregate outcome per call, regardless of N —
        // collapsing the slot into one increment when every message fails
        // permanently, and resetting on any success. The threshold is 3; a
        // single sequence of >= threshold all-permanent messages must NOT
        // soft-remove the group (naive per-message attribution would soft-
        // remove it on the third message of the same slot).
        RecordingGroupRepository repo = new RecordingGroupRepository();
        OutboundDelivery delivery = delivery(new RecordingAdminNotifier(), repo);
        UUID groupId = UUID.randomUUID();

        // One sequence of 5 all-permanent messages → exactly ONE counter
        // increment. Threshold is 3, so the group is NOT removed.
        delivery.deliverSequenceToGroup(
                FailingMessagingAdapter.alwaysFailing("chanA", FailureCategory.PERMANENT),
                List.of(groupMessage(), groupMessage(), groupMessage(),
                        groupMessage(), groupMessage()),
                groupId);
        assertTrue(repo.removed.isEmpty(),
                "one sequence of all-permanent messages increments the counter once — "
                        + "threshold 3 not reached (naive per-message attribution would "
                        + "soft-remove the group on the third message of the same slot)");

        // A sequence with any success resets the counter to 0. failCount=0
        // means the adapter never fails, so both messages succeed.
        delivery.deliverSequenceToGroup(
                new FailingMessagingAdapter("chanA", 0, FailureCategory.PERMANENT),
                List.of(groupMessage(), groupMessage()),
                groupId);
        assertTrue(repo.removed.isEmpty(),
                "a successful sequence resets the counter; threshold still not reached");

        // Three subsequent all-permanent sequences bring the counter from 0
        // to 3 via three one-increment aggregates — threshold reached, group
        // removed. The slot-level attribution is the load-bearing invariant:
        // each slot contributes exactly one outcome, never one per message.
        for (int i = 0; i < 3; i++) {
            delivery.deliverSequenceToGroup(
                    FailingMessagingAdapter.alwaysFailing("chanA", FailureCategory.PERMANENT),
                    List.of(groupMessage(), groupMessage()),
                    groupId);
        }
        assertEquals(List.of(groupId), repo.removed,
                "three one-increment aggregates reach threshold 3 — the slot-level "
                        + "attribution is the load-bearing invariant");
    }

    @Test
    void interruptedSequenceStopsWithoutCounterAttribution() throws InterruptedException {
        // An InterruptedException during a back-off aborts the in-flight
        // message and stops the sequence; remaining messages never reach the
        // adapter, and NO aggregate counter outcome is applied. The
        // invariant is provable deterministically by following with two
        // all-permanent sequences and asserting the group is NOT yet removed
        // at threshold 3 — the interrupt "consumed" the slot's would-be
        // increment.
        RecordingGroupRepository repo = new RecordingGroupRepository();
        // First TRANSIENT failure triggers a back-off; the sleeper throws,
        // backOff re-interrupts the thread and returns false, execute
        // returns ABORTED with no onPermanentGroupFailure call (groupId is
        // null in the sequence loop), and deliverSequenceToGroup observes
        // the interrupt flag and returns without aggregating.
        OutboundDelivery delivery = new OutboundDelivery(
                new RecordingAdminNotifier(), repo,
                3, 0L, 2.0, 3, millis -> { throw new InterruptedException("interrupted back-off"); });
        UUID groupId = UUID.randomUUID();
        FailingMessagingAdapter transientThenGiveUp =
                FailingMessagingAdapter.alwaysFailing("chanA", FailureCategory.TRANSIENT);

        delivery.deliverSequenceToGroup(transientThenGiveUp,
                List.of(groupMessage(), groupMessage(), groupMessage()),
                groupId);
        // The test thread's interrupt flag is now set (backOff re-set it).
        // Clear it so subsequent Thread.currentThread().isInterrupted() checks
        // in the same thread do not see a stale flag.
        assertTrue(Thread.interrupted(),
                "backOff re-sets the interrupt flag after an InterruptedException");

        assertTrue(repo.removed.isEmpty(),
                "interrupted sequence attributes nothing to the counter");

        // Two follow-up all-permanent sequences bring the counter to 2 —
        // threshold 3 not reached, because the interrupted slot contributed
        // no increment.
        delivery = delivery(new RecordingAdminNotifier(), repo);
        for (int i = 0; i < 2; i++) {
            delivery.deliverSequenceToGroup(
                    FailingMessagingAdapter.alwaysFailing("chanA", FailureCategory.PERMANENT),
                    List.of(groupMessage(), groupMessage()),
                    groupId);
        }
        assertTrue(repo.removed.isEmpty(),
                "two post-interrupt permanent sequences leave the counter at 2 — not removed");
    }

    // M1-691: the no-link guarantee is a property of the DELIVERED message,
    // carried once at OutboundDelivery — not of any one sanitized field. A
    // render path that assembled `](` from operands the sanitizer never saw
    // must still reach the adapter with the adjacency broken; a body with no
    // `](` must pass byte-identical. One test per public entry point, each
    // catching the mutation that drops the neutralizeLinkSyntax call from
    // that entry point (a `](` body would then reach the transport unchanged).

    @Test
    void deliverBreaksLinkAdjacencyBeforeTheAdapter() {
        RecordingMessagingAdapter adapter = new RecordingMessagingAdapter();
        OutboundDelivery delivery = delivery(new RecordingAdminNotifier(), new RecordingGroupRepository());

        delivery.deliver(adapter, dmMessage("click ](here"));
        delivery.deliver(adapter, dmMessage("plain body"));

        assertEquals(List.of("click ] (here", "plain body"), adapter.sends,
                "deliver breaks ]( adjacency before the adapter and passes ](-free "
                        + "bodies byte-identical");
    }

    @Test
    void deliverToGroupBreaksLinkAdjacencyBeforeTheAdapter() {
        RecordingMessagingAdapter adapter = new RecordingMessagingAdapter();
        OutboundDelivery delivery = delivery(new RecordingAdminNotifier(), new RecordingGroupRepository());
        UUID groupId = UUID.randomUUID();

        delivery.deliverToGroup(adapter, groupMessage("click ](here"), groupId);
        delivery.deliverToGroup(adapter, groupMessage("plain body"), groupId);

        assertEquals(List.of("click ] (here", "plain body"), adapter.sends,
                "deliverToGroup breaks ]( adjacency before the adapter and passes ](-free "
                        + "bodies byte-identical");
    }

    @Test
    void deliverSequenceToGroupBreaksLinkAdjacencyBeforeTheAdapter() {
        RecordingMessagingAdapter adapter = new RecordingMessagingAdapter();
        OutboundDelivery delivery = delivery(new RecordingAdminNotifier(), new RecordingGroupRepository());
        UUID groupId = UUID.randomUUID();

        delivery.deliverSequenceToGroup(adapter, List.of(
                groupMessage("first ](link"),
                groupMessage("second plain")), groupId);

        assertEquals(List.of("first ] (link", "second plain"), adapter.sends,
                "deliverSequenceToGroup breaks ]( adjacency per message before the adapter "
                        + "and passes ](-free bodies byte-identical");
    }

    @Test
    void updateInPlaceBreaksLinkAdjacencyBeforeTheAdapter() {
        RecordingMessagingAdapter adapter = new RecordingMessagingAdapter();
        OutboundDelivery delivery = delivery(new RecordingAdminNotifier(), new RecordingGroupRepository());
        MessageHandle handle = new MessageHandle("h-1");

        delivery.updateInPlace(adapter, handle, "edit ](here");
        delivery.updateInPlace(adapter, handle, "plain edit");

        assertEquals(List.of("edit ] (here", "plain edit"), adapter.updates,
                "updateInPlace breaks ]( adjacency before the adapter and passes ](-free "
                        + "bodies byte-identical");
    }

    @Test
    void finalizeInPlaceBreaksLinkAdjacencyBeforeTheAdapter() {
        RecordingMessagingAdapter adapter = new RecordingMessagingAdapter();
        OutboundDelivery delivery = delivery(new RecordingAdminNotifier(), new RecordingGroupRepository());
        MessageHandle handle = new MessageHandle("h-1");

        delivery.finalizeInPlace(adapter, handle, "final ](here");
        delivery.finalizeInPlace(adapter, handle, "plain final");

        assertEquals(List.of("final ] (here", "plain final"), adapter.finalizes,
                "finalizeInPlace breaks ]( adjacency before the adapter and passes ](-free "
                        + "bodies byte-identical");
    }
}
