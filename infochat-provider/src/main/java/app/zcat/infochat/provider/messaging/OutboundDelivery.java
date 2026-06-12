package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.metrics.AdapterMetrics;
import app.zcat.infochat.provider.group.GroupRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The single Provider-side outbound-delivery chokepoint. Every send to
 * a messaging transport — chat replies ({@link InboundRouter}), progress
 * placeholders/finalizes ({@link StageProgressNotifier}), periodic group
 * digests ({@code DigestWorker}), and group command announcements
 * (approve/reject handlers) — routes through here rather than calling
 * {@link MessagingAdapter#send}/{@code update}/{@code finalizeMessage}
 * directly. This is the only retry layer between Provider and the
 * transport (spec {@code messaging.md} §Failure handling — "No silent
 * extension"): adapters classify each failure and never retry on their own.
 *
 * <p><b>Transient retry.</b> A {@link FailureCategory#TRANSIENT} failure
 * is retried up to {@link #maxAttempts} total attempts (the original send
 * plus two retries) with exponential back-off and full jitter — each
 * inter-attempt delay is sampled uniformly from
 * {@code [0, base * growth^k)} (the AWS "full jitter" form). The base
 * delay, growth factor, and attempt cap are profile-driven config.</p>
 *
 * <p><b>Cap exhaustion.</b> When the attempt budget is exhausted the
 * failure is escalated to permanent for the rest of this reply's
 * lifecycle: the reply is aborted, no further retry is enqueued, and the
 * throttled admin-notification path fires once per {@code (channel,
 * error_class)} window ({@code security.md} §Failure handling). The user
 * is never pinged about a failed delivery.</p>
 *
 * <p><b>Permanent abort.</b> A {@link FailureCategory#PERMANENT} failure
 * aborts the affected reply immediately and is never retried.</p>
 *
 * <p><b>Bot-removed cleanup.</b> {@link #deliverToGroup} attributes a
 * permanent group-send outcome (an immediate PERMANENT or a cap-exhausted
 * TRANSIENT) to the group's internal id and counts consecutive permanent
 * failures per group. When the count reaches the profile-driven
 * {@link #permanentFailureThreshold} (always &gt; 1, so a single
 * misclassified failure cannot trigger it) the group is soft-removed via
 * {@link GroupRepository#markRemovedAudited(UUID, String)} — which writes a
 * {@code BOT_REMOVED} system-actor audit row in the same transaction and is
 * the entire scheduler-cancel effect, since
 * {@code DigestScheduler.queryActiveGroups} filters {@code removed_at IS NULL}.
 * A successful group delivery resets the group's counter.</p>
 */
@ApplicationScoped
public class OutboundDelivery {

    private static final Logger log = LoggerFactory.getLogger(OutboundDelivery.class);

    /**
     * §6.12 adapter-metrics emission point for the outbound chokepoint
     * ({@code adapter.outbound.total} with its ok/retry/fail
     * classification — only this retry loop sees all three — plus
     * outbound {@code adapter.message.bytes}). Field-injected, unlike
     * the rest of this class, so the two constructors and their
     * plain-JUnit construction sites stay unchanged; the
     * throwaway-registry initializer covers unwired plain
     * constructions, and CDI replaces it with the produced
     * deployment-wide bean.
     */
    @Inject
    AdapterMetrics adapterMetrics = AdapterMetrics.noop();

    private final ThrottledAdminNotifier adminNotifier;
    private final GroupRepository groupRepository;
    private final int maxAttempts;
    private final long baseDelayMillis;
    private final double growthFactor;
    private final int permanentFailureThreshold;
    private final Sleeper sleeper;

    /**
     * Consecutive permanent-send-failure count per group, keyed by the
     * internal {@code groups.id}. In-process only: the threshold is a
     * runtime "is the bot still in this group" signal, not persisted state
     * — a restart legitimately re-starts the evidence-gathering. Adapter
     * isolation (D46) is preserved because the key is the group UUID, which
     * is itself scoped by the {@code (adapter, upstream_group_id)} natural key.
     */
    private final ConcurrentHashMap<UUID, Integer> consecutivePermanentByGroup =
            new ConcurrentHashMap<>();

    @Inject
    public OutboundDelivery(
            ThrottledAdminNotifier adminNotifier,
            GroupRepository groupRepository,
            @ConfigProperty(name = "infochat.messaging.retry.max-attempts") int maxAttempts,
            @ConfigProperty(name = "infochat.messaging.retry.base-delay-ms") long baseDelayMillis,
            @ConfigProperty(name = "infochat.messaging.retry.growth-factor") double growthFactor,
            @ConfigProperty(name = "infochat.messaging.permanent-failure-threshold")
            int permanentFailureThreshold) {
        this(adminNotifier, groupRepository, maxAttempts, baseDelayMillis, growthFactor,
                permanentFailureThreshold, Thread::sleep);
    }

    // Package-private seam constructor: lets plain-JUnit tests inject a
    // no-op sleeper so the retry loop runs without real back-off delays.
    OutboundDelivery(
            ThrottledAdminNotifier adminNotifier,
            GroupRepository groupRepository,
            int maxAttempts,
            long baseDelayMillis,
            double growthFactor,
            int permanentFailureThreshold,
            Sleeper sleeper) {
        this.adminNotifier = adminNotifier;
        this.groupRepository = groupRepository;
        this.maxAttempts = maxAttempts;
        this.baseDelayMillis = baseDelayMillis;
        this.growthFactor = growthFactor;
        this.permanentFailureThreshold = permanentFailureThreshold;
        this.sleeper = sleeper;
    }

    /**
     * Deliver a fresh (non-group-attributed) message. Returns the
     * {@link MessageHandle} on success, or {@code null} if the reply was
     * aborted (permanent failure or exhausted retry budget). Used for DM
     * replies and progress placeholders/finalizes.
     */
    public @Nullable MessageHandle deliver(MessagingAdapter adapter, OutboundMessage msg) {
        return execute(adapter.name(), null, msg, () -> adapter.send(msg)).handle();
    }

    /**
     * Deliver a group-scoped message, attributing permanent failures to
     * {@code groupId} for bot-removed cleanup. Returns the handle on
     * success or {@code null} on abort.
     */
    public @Nullable MessageHandle deliverToGroup(
            MessagingAdapter adapter, OutboundMessage msg, UUID groupId) {
        return execute(adapter.name(), groupId, msg, () -> adapter.send(msg)).handle();
    }

    /**
     * Edit an in-place message (coalesced progress update). Returns
     * {@code true} iff delivered. Named distinctly from the adapter's
     * {@code update} so the chokepoint grep (acceptance item 1) resolves
     * to this class alone.
     */
    public boolean updateInPlace(MessagingAdapter adapter, MessageHandle handle, String body) {
        return execute(adapter.name(), null, null, () -> {
            adapter.update(handle, body);
            return null;
        }).delivered();
    }

    /**
     * Finalize an in-place message. Returns {@code true} iff delivered.
     * Named distinctly from the adapter's {@code finalizeMessage} so the
     * chokepoint grep (acceptance item 1) resolves to this class alone.
     */
    public boolean finalizeInPlace(MessagingAdapter adapter, MessageHandle handle, String body) {
        return execute(adapter.name(), null, null, () -> {
            adapter.finalizeMessage(handle, body);
            return null;
        }).delivered();
    }

    /**
     * {@code sendMsg} carries the §6.12 {@code adapter.outbound.total}
     * emission for send ops — its scope supplies the {@code scope_kind}
     * label, its text the outbound byte count. Null for update/finalize
     * ops: those are counted by the progress notifier under
     * {@code adapter.outbound.update.total}, whose outcome domain has no
     * retry value, so this loop emits nothing for them.
     */
    private Outcome execute(String channel, @Nullable UUID groupId,
            @Nullable OutboundMessage sendMsg, DeliveryOp op) {
        long currentBound = baseDelayMillis;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                MessageHandle handle = op.perform();
                if (groupId != null) {
                    consecutivePermanentByGroup.remove(groupId);
                }
                if (sendMsg != null) {
                    adapterMetrics.outbound(channel, sendMsg.scope(),
                            AdapterMetrics.SendOutcome.OK);
                    adapterMetrics.messageBytes(channel,
                            AdapterMetrics.Direction.OUTBOUND, sendMsg.text());
                }
                return new Outcome(true, handle);
            } catch (MessagingException e) {
                switch (e.category()) {
                    case PERMANENT -> {
                        log.warn("Outbound delivery to channel={} failed permanently, aborting reply",
                                channel);
                        onPermanentGroupFailure(channel, groupId);
                        if (sendMsg != null) {
                            adapterMetrics.outbound(channel, sendMsg.scope(),
                                    AdapterMetrics.SendOutcome.FAIL);
                        }
                        return Outcome.ABORTED;
                    }
                    case TRANSIENT -> {
                        if (attempt == maxAttempts) {
                            // Budget exhausted — escalate to permanent for the
                            // rest of this reply's lifecycle and alert admins.
                            onCapExhausted(channel, e);
                            onPermanentGroupFailure(channel, groupId);
                            if (sendMsg != null) {
                                adapterMetrics.outbound(channel, sendMsg.scope(),
                                        AdapterMetrics.SendOutcome.FAIL);
                            }
                            return Outcome.ABORTED;
                        }
                        if (!backOff(currentBound)) {
                            return Outcome.ABORTED;
                        }
                        if (sendMsg != null) {
                            adapterMetrics.outbound(channel, sendMsg.scope(),
                                    AdapterMetrics.SendOutcome.RETRY);
                        }
                        currentBound = (long) (currentBound * growthFactor);
                    }
                }
            }
        }
        // Unreachable: the loop always returns on the maxAttempts-th attempt.
        return Outcome.ABORTED;
    }

    private void onCapExhausted(String channel, MessagingException last) {
        String errorClass = last.category().name();
        // Key encodes (channel, error_class) so the throttle window is
        // per-(channel, error_class) and low-cardinality — never per-message.
        String key = channel + "|" + errorClass;
        // The exception detail goes to the SafeLog.warn line below (class +
        // cause chain only). The admin-notify line deliberately omits
        // last.getMessage(): an adapter's exception message may quote
        // user-authored prose, which security.md §"User content in exceptions"
        // (D37) bars from non-audit logs — and the notifier sanitizes for
        // log-forging but does not drop the message body the way SafeLog does.
        adminNotifier.notifyOnce(key, errorClass,
                "Outbound delivery to channel " + channel
                        + " exhausted the retry budget (" + maxAttempts
                        + " attempts) and was escalated to permanent");
        SafeLog.warn(log,
                "Outbound delivery to channel=" + channel
                        + " exhausted retry budget; escalated to permanent", last);
    }

    private void onPermanentGroupFailure(String channel, @Nullable UUID groupId) {
        if (groupId == null) {
            return;
        }
        int count = consecutivePermanentByGroup.merge(groupId, 1, Integer::sum);
        if (count >= permanentFailureThreshold) {
            // The bot looks removed from the group: soft-remove it AND write the
            // BOT_REMOVED system-actor audit row atomically (which also stops the
            // digest scheduler, since it filters removed_at IS NULL), then reset
            // the counter so a later re-add starts clean. channel is recorded as
            // the audit actor_adapter.
            groupRepository.markRemovedAudited(groupId, channel);
            consecutivePermanentByGroup.remove(groupId);
            log.warn("Group {} soft-removed after {} consecutive permanent send failures",
                    groupId, count);
        }
    }

    // Sleep for a full-jitter delay sampled from [0, currentBound). Returns
    // false if the wait was interrupted (caller aborts rather than retrying).
    private boolean backOff(long currentBound) {
        long delay = currentBound <= 0 ? 0 : ThreadLocalRandom.current().nextLong(currentBound);
        try {
            sleeper.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** One delivery attempt against the transport. */
    @FunctionalInterface
    interface DeliveryOp {
        @Nullable MessageHandle perform() throws MessagingException;
    }

    /** Test seam for the inter-attempt back-off wait. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private record Outcome(boolean delivered, @Nullable MessageHandle handle) {
        static final Outcome ABORTED = new Outcome(false, null);
    }
}
