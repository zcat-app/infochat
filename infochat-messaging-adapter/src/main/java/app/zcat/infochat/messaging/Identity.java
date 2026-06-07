package app.zcat.infochat.messaging;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Per-adapter sender identity carried by every {@link InboundMessage}.
 * Per {@code docs/design/06-messaging.md} §6.2 and
 * {@code docs/spec/messaging.md} §Per-adapter trust level and identity.
 *
 * <p>Authorization decisions MUST be made against {@link #contactId}
 * alone (decision D10). {@code displayName} is informational and may
 * change between messages without invalidating the identity; relying on
 * it for permission checks would let an attacker who can rename
 * themselves impersonate another user. {@code lastSeen} is informational
 * only — Provider may or may not surface it.</p>
 */
public record Identity(String contactId, @Nullable String displayName, Instant lastSeen) {
}
