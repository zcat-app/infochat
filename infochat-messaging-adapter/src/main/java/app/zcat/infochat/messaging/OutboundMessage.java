package app.zcat.infochat.messaging;


import java.time.Instant;

/**
 * One outbound message Provider hands to a {@link MessagingAdapter} for
 * delivery. Per {@code docs/design/06-messaging.md} §6.2.
 *
 * <p>{@code correlationId} ties an outbound reply back to its inbound
 * trigger for logging and handle bookkeeping. The only SPI commitment is
 * that the id is <strong>non-null</strong>.
 *
 * <p><strong>It is not stable across retries.</strong> Whether two
 * constructions of the same logical outbound share an id is a property of
 * the individual call site, not of this SPI: all but three call sites mint
 * a fresh {@code UUID.randomUUID()} per construction. A consumer therefore
 * MUST NOT use this id as a deduplication or idempotency key — v1 outbound
 * delivery is at-least-once and no component suppresses duplicates
 * ({@code docs/design/06-messaging.md} §6.3.5, decision D64).</p>
 */
public record OutboundMessage(
        ScopeRef scope,
        String text,
        Instant requestedAt,
        String correlationId) {
}
