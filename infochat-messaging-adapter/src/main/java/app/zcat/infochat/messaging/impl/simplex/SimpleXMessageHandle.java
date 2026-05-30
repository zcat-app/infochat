package app.zcat.infochat.messaging.impl.simplex;

import org.jspecify.annotations.NonNull;

import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.ScopeRef;

/**
 * Adapter-internal handle carried alongside the public {@link MessageHandle}
 * returned to callers by {@link SimpleXAdapter#send}. Keyed into
 * {@link SimpleXAdapter}'s in-memory handle table by the opaque value of the
 * public handle; never escapes the adapter. Per
 * {@code docs/spec/messaging.md} §Message handles: callers MUST NOT inspect
 * or rely on the public handle's contents, so the simplex-chat
 * {@code chatItemId} the adapter needs to issue subsequent
 * {@code /_update item} commands lives here, not on the public handle.
 *
 * <p>{@code chatItemId} is the simplex-chat message identifier captured from
 * the {@code apiSendMessages} response per {@code docs/design/06-messaging.md}
 * §6.4.5. {@code scope} is the recipient (DM or group) — needed because the
 * SimpleX update command syntax addresses the conversation by scope, not
 * just the chat-item id. {@code correlationId} ties the handle back to the
 * originating outbound for retry deduplication.</p>
 */
record SimpleXMessageHandle(
        @NonNull String chatItemId,
        @NonNull ScopeRef scope,
        @NonNull String correlationId) {
}
