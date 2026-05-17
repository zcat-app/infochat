package io.infochat.messaging;

import java.time.Instant;

/**
 * One inbound message delivered from a {@link MessagingAdapter} to the
 * Provider's {@link MessagingAdapter.InboundHandler}. Per
 * {@code docs/design/06-messaging.md} §6.2.
 *
 * <p>The record is the load-bearing carrier for {@link Identity},
 * {@link ScopeRef}, the message text, the receipt timestamp, and the
 * adapter-local message id. {@code adapterMessageId} is opaque to
 * Provider — Provider may use it as a correlation key for retries and
 * audit cross-references but MUST NOT parse or persist it across
 * service instances.</p>
 *
 * <p>The text is already mention-stripped per
 * {@code docs/spec/messaging.md} §Required SPI surface — the adapter
 * (or Provider, depending on impl) removes the bot's {@code @mention}
 * token before delivery.</p>
 */
public record InboundMessage(
        Identity sender,
        ScopeRef scope,
        String text,
        Instant receivedAt,
        String adapterMessageId) {
}
