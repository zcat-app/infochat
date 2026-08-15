package app.zcat.infochat.messaging;

import org.jspecify.annotations.Nullable;


/**
 * One outbound binary attachment Provider hands to a
 * {@link MessagingAdapter} for delivery as a native file message. Per
 * {@code docs/spec/messaging.md} §Required SPI surface — Send
 * attachment (decision D74) and {@code docs/design/06-messaging.md}
 * §6.2.4.
 *
 * <p>The payload is a {@code filePath}, never bytes: signal-cli
 * attaches by path and SimpleX file transfer completes asynchronously
 * past {@code send()}'s return, so the file MUST remain readable by
 * the adapter for the whole transmit, and the adapter MUST NOT retain
 * or copy the payload beyond delivery.</p>
 *
 * <p>{@code imagePreview} is image-derived data under the same
 * no-retention posture: memory and this record only (§6.2.4).</p>
 *
 * <p>{@code correlationId} carries the same non-null-only contract as
 * {@link OutboundMessage#correlationId()}: it ties the send back to
 * its trigger for logging and handle bookkeeping, and it is not stable
 * across retries — a consumer MUST NOT use it as a deduplication or
 * idempotency key ({@code docs/design/06-messaging.md} §6.3.5,
 * decision D64).</p>
 */
public record OutboundAttachment(
        ScopeRef scope,
        String filePath,
        String mimeType,
        String displayFileName,
        String correlationId,
        @Nullable String imagePreview) {
}
