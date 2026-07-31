package app.zcat.infochat.provider.digest;

import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.digest.DigestRenderer.DigestMode;
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
 * Delivers a rendered digest to its group. The framing is mode-dependent
 * ({@code groups.digest_mode}, V67, M1-734 narrowing D63):
 *
 * <p><b>{@code full}</b> — one {@link OutboundMessage} per rendered section,
 * sent SEQUENTIALLY in section order through
 * {@link OutboundDelivery#deliverSequenceToGroup}. The per-category split
 * exists to stop SimpleX's 4 000-byte line-based chunker splitting inside a
 * cluster — a live concern while a category is prose-sized. The per-message
 * TRANSIENT-retry / PERMANENT-abort ladder still applies to each category
 * independently; the per-group permanent-failure counter receives at most
 * ONE aggregate outcome per slot (any success resets, all-permanent
 * increments once), so a single transport blip during the sequential loop
 * cannot soft-remove a healthy group.
 *
 * <p><b>{@code normal} / {@code brief}</b> — ONE {@link OutboundMessage}
 * for the whole digest: the section texts joined on {@code "\n\n"} (the
 * same join {@link DigestWorker} performs for the {@code summary_cache}
 * content, so the delivered bytes equal the cached bytes). A normal-mode
 * category is five lines and a brief one four — far under the chunker
 * threshold — and N notifications for one digest is worse than one. The
 * ladder and the counter semantics are unchanged (trivially: there is one
 * message, and the threshold was calibrated for one message per slot).
 *
 * <p>Section order is inherited as-is from {@link DigestRenderer#renderSections}
 * — D62 order (assigned-cluster count descending, alphabetical ties, Other
 * last). In {@code full} mode, sequential order is what makes the closing
 * affordance (folded into the LAST section's text inside
 * {@code renderSections}) land on the final message deterministically, and
 * preserves the digest's narrative order; in the batched modes the
 * {@code "\n\n"} join preserves the same order within the single body.
 * A parallel implementation would pass every other delivery acceptance
 * check, which is why the property is pinned explicitly.
 *
 * <p>In {@code full} mode each category message carries a per-(slot,
 * category) correlationId {@code digest-<groupId>-<windowStart>-<categorySlug>},
 * where {@code categorySlug} is the section's tag string as-is and the literal
 * {@code "other"} for the null (Other) bucket. Tags are a controlled
 * vocabulary, so no further normalization is applied; M1-652's
 * {@code (group_id, window_start, category_slug)} delivery-state key
 * inherits this mapping. The batched modes use
 * {@code digest-<groupId>-<windowStart>-batch} — the literal {@code -batch}
 * suffix where the slug would be, which stays distinct from every per-category
 * id (slugs never contain {@code -batch}: tags are single words and the Other
 * bucket maps to {@code other}) and from the zero-posts id
 * ({@code digest-<groupId>-<windowStart>}). Either way the id is NOT stable
 * across regenerations and nothing dedups on it (D64) — a
 * {@code /retry --digest} re-posts every missing category (M1-652 owns
 * gap-filling redelivery).
 *
 * <p><b>Delivery recording (M1-652, D65; batched in M1-734).</b> A message
 * the adapter ACCEPTS records a {@code digest_category_delivery} row for
 * EVERY section slug it carries — one row per category in {@code full} mode,
 * one row per batched slug in {@code normal}/{@code brief} — on scheduled
 * delivery AND on replay delivery alike (both reach the adapter through
 * this same {@code deliver()} method, so the recording seam is shared).
 * All-slugs-or-none is what keeps {@link DigestRetryService}'s slug filter
 * correct on the batched modes: a delivered batch leaves nothing missing
 * (the no-op-retry branch), a failed batch leaves every slug missing and
 * the whole batch re-sends. Recording happens inside a delegating wrapper
 * around the adapter so the chokepoint
 * ({@link OutboundDelivery#deliverSequenceToGroup}) is untouched
 * (OutboundDelivery is frozen, M1-652 out-of-scope). A record-write failure
 * is caught and logged per slug: a missing record degrades to the
 * spec-sanctioned duplicate on later replay (D64 at-least-once), while
 * propagation would abort the remaining categories — the very defect
 * M1-652 fixed.
 *
 * <p>This class sends whatever ordered section list it is handed; it
 * appends nothing and mutates nothing — in the batched modes it joins the
 * section texts and nothing more.
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
     * Send the sections in the framing the group's mode selects:
     * {@code normal}/{@code brief} join all section texts on {@code "\n\n"}
     * into ONE {@link OutboundMessage}; {@code full} sends one
     * {@link OutboundMessage} per section sequentially in section order.
     * Both framings route through {@code deliverSequenceToGroup}, so the
     * TRANSIENT-retry / PERMANENT-abort ladder and the one-aggregate-
     * counter-outcome-per-slot rule apply unchanged. Single-message paths
     * (zero-posts fixed reply, degraded headlines-only) never enter this
     * class — they keep today's {@link OutboundDelivery#deliverToGroup} call.
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
     * @param mode               the group's {@code digest_mode} — on the
     *                           replay path this is the CURRENT mode, which
     *                           equals the render-time mode unless the group
     *                           flipped modes mid-window (M1-734: framing
     *                           follows the current preference, the bytes
     *                           stay byte-faithful per D65)
     */
    public void deliver(MessagingAdapter adapter,
                        String upstreamGroupId,
                        UUID internalGroupId,
                        Instant windowStart,
                        List<RenderedSection> sections,
                        DigestMode mode) {
        List<OutboundMessage> messages;
        // Keyed on correlationId, NOT on the message instance and not on the
        // record's structural equality. The chokepoint may hand the adapter a
        // DIFFERENT OutboundMessage than the one built here — it rewrites the
        // body to break "](" adjacency (OutboundDelivery.neutralizeLinkSyntax,
        // M1-691) — so both identity and equals miss on exactly the messages
        // an attacker controls, silently skipping their delivery rows and
        // degrading M1-652 replay to a duplicate. correlationId is unique per
        // message, already identifies the (batched) slug set, and no body
        // transform touches it. The value is the slug LIST the message
        // carries: one slug per category message in full mode, every section
        // slug for the single batched message.
        Map<String, List<String>> messageSlugs = new HashMap<>();
        if (mode == DigestMode.FULL) {
            messages = new ArrayList<>(sections.size());
            for (RenderedSection section : sections) {
                String categorySlug = DigestSectionRepository.slugOf(section);
                String correlationId =
                        "digest-" + internalGroupId + "-" + windowStart + "-" + categorySlug;
                OutboundMessage msg = new OutboundMessage(
                        new ScopeRef.Group(upstreamGroupId),
                        section.text(),
                        Instant.now(),
                        correlationId);
                messages.add(msg);
                messageSlugs.put(correlationId, List.of(categorySlug));
            }
        } else {
            // Batched delivery (M1-734): one message whose body is the same
            // "\n\n" join DigestWorker stores in summary_cache, so delivered
            // bytes == cached bytes. The correlationId carries the literal
            // "-batch" where the slug would be: no slug identifies the whole
            // batch, nothing dedups on the id (D64), and the suffix cannot
            // collide with a per-category id (tags are single words; the
            // Other bucket is "other").
            String correlationId = "digest-" + internalGroupId + "-" + windowStart + "-batch";
            OutboundMessage msg = new OutboundMessage(
                    new ScopeRef.Group(upstreamGroupId),
                    sections.stream().map(RenderedSection::text)
                            .collect(java.util.stream.Collectors.joining("\n\n")),
                    Instant.now(),
                    correlationId);
            messages = List.of(msg);
            messageSlugs.put(correlationId, sections.stream()
                    .map(DigestSectionRepository::slugOf).toList());
        }
        MessagingAdapter recording = new RecordingAdapter(
                adapter, deliveryRepository, internalGroupId, windowStart, messageSlugs);
        outboundDelivery.deliverSequenceToGroup(recording, messages, internalGroupId);
    }

    /**
     * Delegating {@link MessagingAdapter} that records a delivery row for
     * every slug a message carries on the adapter's normal return from
     * {@code send()}. The chokepoint's retry ladder calls {@code send()}
     * directly; a failed attempt throws (no recording), and a
     * retried-then-successful attempt throws on the failed attempts then
     * returns once — so recording fires exactly once per delivered message.
     * Every other method is pure delegation.
     */
    private static final class RecordingAdapter implements MessagingAdapter {
        private final MessagingAdapter delegate;
        private final DigestCategoryDeliveryRepository deliveryRepository;
        private final UUID internalGroupId;
        private final Instant windowStart;
        private final Map<String, List<String>> messageSlugs;

        RecordingAdapter(MessagingAdapter delegate,
                         DigestCategoryDeliveryRepository deliveryRepository,
                         UUID internalGroupId,
                         Instant windowStart,
                         Map<String, List<String>> messageSlugs) {
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
            List<String> slugs = messageSlugs.get(msg.correlationId());
            if (slugs != null) {
                // One slug per category message (full mode), every section
                // slug for the batched message. The per-slug catch keeps the
                // remaining slugs recording after one write fails.
                for (String slug : slugs) {
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

