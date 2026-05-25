package app.zcat.infochat.messaging.impl.inmemory;

import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.OutboundMessage;
import org.jspecify.annotations.NonNull;

/**
 * {@link InMemoryAdapter}-internal per-handle state record. Per
 * {@code docs/design/06-messaging.md} §6.1 each concrete adapter owns
 * its handle carrier under its own {@code impl/}/{@code inmemory/}
 * package — the M1-007c {@link MessageHandle} record stays the opaque
 * SPI return type, and this record holds the InMemory-only state
 * (issued id and the original outbound) the adapter needs to apply
 * subsequent {@code update} / {@code finalize} calls deterministically.
 *
 * <p>The opacity invariant from {@code docs/spec/messaging.md}
 * §Message handles holds end-to-end: callers see only the opaque
 * {@link MessageHandle} returned by {@link InMemoryAdapter#send}; this
 * record never escapes the adapter (it is wholly internal). Reads from
 * the adapter's update / typing history are accessed through the
 * concrete-class test helpers, not by inspecting this record.</p>
 */
public record InMemoryMessageHandle(long id, @NonNull OutboundMessage original) {
}
