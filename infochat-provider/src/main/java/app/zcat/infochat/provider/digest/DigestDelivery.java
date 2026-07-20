package app.zcat.infochat.provider.digest;

import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.digest.DigestRenderer.RenderedSection;
import app.zcat.infochat.provider.messaging.OutboundDelivery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * <p>This class sends whatever ordered section list it is handed; it
 * appends nothing, mutates nothing, and joins nothing.
 * {@link DigestRenderer#renderSections}'s output is the exact delivery
 * bytes (M1-652 fork closed, arm (b), 2026-07-20).
 */
@ApplicationScoped
public class DigestDelivery {

    @Inject
    OutboundDelivery outboundDelivery;

    /**
     * Send one {@link OutboundMessage} per section sequentially in section
     * order. Single-message paths (zero-posts fixed reply, degraded
     * headlines-only) never enter this class — they keep today's
     * {@link OutboundDelivery#deliverToGroup} call.
     *
     * @param adapter            the activated messaging adapter for the group
     * @param upstreamGroupId    the adapter's group id (becomes the
     *                           {@link ScopeRef.Group} target)
     * @param internalGroupId    the internal {@code groups.id} (drives the
     *                           per-group permanent-failure counter and the
     *                           correlationId)
     * @param windowStart        the digest slot's {@code window_start}
     *                           (correlationId component; M1-652's
     *                           delivery-state key inherits the tuple)
     * @param sections           the ordered per-category rendered sections
     *                           from {@link DigestRenderer#renderSections}
     */
    public void deliver(MessagingAdapter adapter,
                        String upstreamGroupId,
                        UUID internalGroupId,
                        Instant windowStart,
                        List<RenderedSection> sections) {
        List<OutboundMessage> messages = new ArrayList<>(sections.size());
        for (RenderedSection section : sections) {
            String categorySlug = section.tag() != null ? section.tag() : "other";
            String correlationId = "digest-" + internalGroupId + "-" + windowStart + "-" + categorySlug;
            messages.add(new OutboundMessage(
                    new ScopeRef.Group(upstreamGroupId),
                    section.text(),
                    Instant.now(),
                    correlationId));
        }
        outboundDelivery.deliverSequenceToGroup(adapter, messages, internalGroupId);
    }
}
