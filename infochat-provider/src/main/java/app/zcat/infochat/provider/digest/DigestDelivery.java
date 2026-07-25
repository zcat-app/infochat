package app.zcat.infochat.provider.digest;

import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.digest.DigestRenderer.RenderedSection;
import app.zcat.infochat.provider.messaging.OutboundDelivery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Delivers a per-category digest — one {@link OutboundMessage} per
 * rendered section, sent SEQUENTIALLY in section order through
 * {@link OutboundDelivery#deliverSequenceToGroup}. The per-message
 * TRANSIENT-retry / PERMANENT-abort ladder still applies to each category
 * independently; the per-group permanent-failure counter receives at most
 * ONE aggregate outcome per slot (any success resets, all-permanent
 * increments once), so a single transport blip during the sequential loop
 * cannot soft-remove a healthy group.
 *
 * <p>Section order is inherited as-is from {@link DigestRenderer#renderSections}
 * — D62 order (assigned-cluster count descending, alphabetical ties, Other
 * last). Sequential order is what makes the closing affordance (folded
 * into the LAST section's text inside {@code renderSections}) land on the
 * final message deterministically, and preserves the digest's narrative
 * order. A parallel implementation would pass every other delivery
 * acceptance check, which is why the property is pinned explicitly.
 *
 * <p>Each category message carries a per-(slot, category) correlationId
 * {@code digest-<groupId>-<windowStart>-<categorySlug>}, where
 * {@code categorySlug} is the section's tag string as-is and the literal
 * {@code "other"} for the null (Other) bucket. Tags are a controlled
 * vocabulary, so no further normalization is applied; M1-652's
 * {@code (group_id, window_start, category_slug)} delivery-state key
 * inherits this mapping. The id is NOT stable across regenerations and
 * nothing dedups on it (D64) — a {@code /retry --digest} re-posts every
 * category, including ones already delivered (M1-652 owns gap-filling
 * redelivery).
 *
 * <p><b>Delivery recording (M1-652, D65).</b> A category message the adapter
 * ACCEPTS records a {@code digest_category_delivery} row — on scheduled
 * delivery AND on replay delivery alike (both reach the adapter through
 * this same {@code deliver()} method, so the recording seam is shared). A
 * failed send records nothing, so the existing per-category
 * TRANSIENT/PERMANENT ladder is unchanged. Recording happens inside a
 * delegating wrapper around the adapter so the chokepoint
 * ({@link OutboundDelivery#deliverSequenceToGroup}) is untouched
 * (OutboundDelivery is frozen, M1-652 out-of-scope). A record-write failure
 * is caught and logged: a missing record degrades to the spec-sanctioned
 * duplicate on later replay (D64 at-least-once), while propagation would
 * abort the remaining categories — the very defect this ticket fixes.
 *
 * <p>This class sends whatever ordered section list it is handed; it
 * appends nothing, mutates nothing, and joins nothing.
 * {@link DigestRenderer#renderSections}'s output is the exact delivery
 * bytes (M1-652 fork closed, arm (b), 2026-07-20). Replay filters the
 * section list BEFORE calling {@code deliver()} ({@link DigestRetryService}),
 * never inside it — {@code deliver()} itself never filters.
 */
@ApplicationScoped
public class DigestDelivery {

    private static final Logger LOG = LoggerFactory.getLogger(DigestDelivery.class);

    @Inject
    OutboundDelivery outboundDelivery;

    @Inject
    DigestCategoryDeliveryRepository deliveryRepository;

    /**
     * Send one {@link OutboundMessage} per section sequentially in section
     * order. Single-message paths (zero-posts fixed reply, degraded
     * headlines-only) never enter this class — they keep today's
     * {@link OutboundDelivery#deliverToGroup} call.
     *
     * <p>Both the scheduled path ({@link DigestWorker}) and the replay path
     * ({@link DigestRetryService}) call this method, so delivery recording
     * runs through the same seam for both — satisfying "on scheduled
     * delivery AND on replay delivery alike" (acceptance item 3).
     *
     * @param adapter            the activated messaging adapter for the group
     * @param upstreamGroupId    the adapter's group id (becomes the
     *                           {@link ScopeRef.Group} target)
     * @param internalGroupId    the internal {@code groups.id} (drives the
     *                           per-group permanent-failure counter, the
     *                           correlationId, and the delivery-record key)
     * @param windowStart        the digest slot's {@code window_start}
     *                           (correlationId component; M1-652's
     *                           delivery-state key inherits the tuple)
     * @param sections           the ordered per-category rendered sections
     *                           from {@link DigestRenderer#renderSections}
     *                           (or the filtered subset for replay)
     */
    public void deliver(MessagingAdapter adapter,
                        String upstreamGroupId,
                        UUID internalGroupId,
                        Instant windowStart,
                        List<RenderedSection> sections) {
        List<OutboundMessage> messages = new ArrayList<>(sections.size());
        // Keyed on correlationId, NOT on the message instance and not on the
        // record's structural equality. The chokepoint may hand the adapter a
        // DIFFERENT OutboundMessage than the one built here — it rewrites the
        // body to break "](" adjacency (OutboundDelivery.neutralizeLinkSyntax,
        // M1-691) — so both identity and equals miss on exactly the messages
        // an attacker controls, silently skipping their delivery row and
        // degrading M1-652 replay to a duplicate. correlationId is unique per
        // section, already carries the slug, and no body transform touches it.
        Map<String, String> messageSlugs = new HashMap<>();
        for (RenderedSection section : sections) {
            String categorySlug = DigestSectionRepository.slugOf(section);
            String correlationId = "digest-" + internalGroupId + "-" + windowStart + "-" + categorySlug;
            OutboundMessage msg = new OutboundMessage(
                    new ScopeRef.Group(upstreamGroupId),
                    section.text(),
                    Instant.now(),
                    correlationId);
            messages.add(msg);
            messageSlugs.put(correlationId, categorySlug);
        }
        MessagingAdapter recording = new RecordingAdapter(
                adapter, deliveryRepository, internalGroupId, windowStart, messageSlugs);
        outboundDelivery.deliverSequenceToGroup(recording, messages, internalGroupId);
    }

    /**
     * Delegating {@link MessagingAdapter} that records a delivery row on the
     * adapter's normal return from {@code send()}. The chokepoint's retry
     * ladder calls {@code send()} directly; a failed attempt throws (no
     * recording), and a retried-then-successful attempt throws on the failed
     * attempts then returns once — so {@code recordDelivery} fires exactly
     * once per delivered message. Every other method is pure delegation.
     */
    private static final class RecordingAdapter implements MessagingAdapter {
        private final MessagingAdapter delegate;
        private final DigestCategoryDeliveryRepository deliveryRepository;
        private final UUID internalGroupId;
        private final Instant windowStart;
        private final Map<String, String> messageSlugs;

        RecordingAdapter(MessagingAdapter delegate,
                         DigestCategoryDeliveryRepository deliveryRepository,
                         UUID internalGroupId,
                         Instant windowStart,
                         Map<String, String> messageSlugs) {
            this.delegate = delegate;
            this.deliveryRepository = deliveryRepository;
            this.internalGroupId = internalGroupId;
            this.windowStart = windowStart;
            this.messageSlugs = messageSlugs;
        }

        @Override
        public MessageHandle send(OutboundMessage msg) throws MessagingException {
            // Delegate first — a throw means the adapter did NOT accept, so
            // no delivery is recorded and the existing ladder runs unchanged.
            MessageHandle handle = delegate.send(msg);
            String slug = messageSlugs.get(msg.correlationId());
            if (slug != null) {
                try {
                    deliveryRepository.recordDelivery(internalGroupId, windowStart, slug);
                } catch (SQLException e) {
                    // Catch-and-log, never propagate: a missing record
                    // degrades to the spec-sanctioned duplicate on the next
                    // replay (D64 at-least-once permits it), while throwing
                    // here would abort the remaining categories — the very
                    // defect M1-652 exists to fix. Routed through SafeLog
                    // because the guarded call's bind parameters are
                    // category slugs (controlled vocabulary, not user prose),
                    // but the spec commitment is unconditional (§Secrets
                    // handling — "The original Throwable is never passed
                    // to the underlying SLF4J logger").
                    SafeLog.warn(LOG,
                            "Delivery record write failed for group " + internalGroupId
                                    + " category " + slug + " — degrading to duplicate-on-replay",
                            e);
                }
            }
            return handle;
        }

        @Override public String name() { return delegate.name(); }
        @Override public CapabilityFlags capabilities() { return delegate.capabilities(); }
        @Override public AdapterTrustLevel trustLevel() { return delegate.trustLevel(); }
        @Override public boolean isWellFormedContactId(String contactId) {
            return delegate.isWellFormedContactId(contactId);
        }
        @Override public void update(MessageHandle h, String b) throws MessagingException {
            delegate.update(h, b);
        }
        @Override public void finalizeMessage(MessageHandle h, String b) throws MessagingException {
            delegate.finalizeMessage(h, b);
        }
        @Override public void setTyping(ScopeRef s, boolean t) { delegate.setTyping(s, t); }
        @Override public void setInboundHandler(InboundHandler h) {
            delegate.setInboundHandler(h);
        }
    }
}

