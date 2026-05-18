package app.zcat.infochat.messaging;

import java.time.Instant;

/**
 * One outbound message Provider hands to a {@link MessagingAdapter} for
 * delivery. Per {@code docs/design/06-messaging.md} §6.2.
 *
 * <p>{@code correlationId} ties an outbound reply back to its inbound
 * trigger so adapters that deduplicate on retry
 * ({@code docs/design/06-messaging.md} §6.3.5) can do so deterministically.
 * When the outbound is not a reply (e.g., a scheduled digest) the
 * correlation id is adapter-defined; the SPI commitment is that the id
 * is non-null and stable across retries of the same logical outbound.</p>
 */
public record OutboundMessage(
        ScopeRef scope,
        String text,
        Instant requestedAt,
        String correlationId) {
}
