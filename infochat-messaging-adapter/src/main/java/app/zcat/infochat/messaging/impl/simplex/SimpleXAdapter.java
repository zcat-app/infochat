package app.zcat.infochat.messaging.impl.simplex;

import org.jspecify.annotations.NonNull;

import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

import java.time.Duration;

/**
 * SimpleX production adapter — skeleton. Declares the adapter's
 * selection name, HIGH trust level, and static capability flags; the
 * simplex-chat subprocess, WebSocket connection, and live send/receive
 * are M1-103.
 *
 * <p>SimpleX's contact id is the queue address — a cryptographically
 * anchored identifier (decision D32, {@code docs/spec/messaging.md}
 * §Per-adapter trust level and identity) — so this adapter is
 * {@link AdapterTrustLevel#HIGH}. The unimplemented transport methods
 * throw {@link UnsupportedOperationException} rather than returning
 * placeholder values; in particular {@link #assertIdentity} throws,
 * because a HIGH-trust adapter must never return an unverified
 * identity (real assertion is M1-103). No CDI annotations here — the
 * adapter bean's producer / discovery is Provider-side wiring
 * (M1-035b/M1-105), mirroring InMemoryAdapter and SignalAdapter.</p>
 *
 * <p>{@code supportsMembershipEvents} is declared {@code false} as the
 * safe skeleton default; M1-104 (group support) flips this to the
 * researched value when it adds group-membership event handling. The
 * other unenumerated flags (code formatting, attachments, threading,
 * size and rate caps, edit interval) carry conservative defaults that
 * will be tuned against a live simplex-chat in M1-103.</p>
 */
public final class SimpleXAdapter implements MessagingAdapter {

    // maxInboundMessageBytes is the 16 KiB laptop default per
    // docs/design/06-messaging.md §6.2.2 (profile-tunable). maxMessageBytes,
    // maxSendsPerSecond, and minEditInterval are best-guess defaults not
    // fixed by spec and are expected to be tuned against a live simplex-chat
    // in M1-103.
    private static final CapabilityFlags CAPABILITIES = new CapabilityFlags(
            /* supportsMentionByContactId */ true,
            /* supportsMembershipEvents   */ false,
            /* supportsCodeFormatting     */ false,
            /* supportsMarkdownLinks      */ false,
            /* supportsMultilineCode      */ false,
            /* supportsAttachments        */ false,
            /* supportsThreading          */ false,
            /* maxMessageBytes            */ 2_000,
            /* maxInboundMessageBytes     */ 16_384,
            /* maxInflightSends           */ 4,
            /* maxSendsPerSecond          */ 8,
            /* supportsMessageEdit        */ true,
            /* supportsTypingIndicator    */ true,
            /* minEditInterval            */ Duration.ZERO);

    private volatile InboundHandler handler;

    @Override
    public String name() {
        return "simplex";
    }

    @Override
    public CapabilityFlags capabilities() {
        return CAPABILITIES;
    }

    @Override
    public AdapterTrustLevel trustLevel() {
        return AdapterTrustLevel.HIGH;
    }

    /** Start the simplex-chat subprocess and connect — implemented in M1-103. */
    public void start() {
        // No-op skeleton; subprocess + WebSocket connection are M1-103.
    }

    /** Disconnect and stop the simplex-chat subprocess — implemented in M1-103. */
    public void close() {
        // No-op skeleton; subprocess shutdown is M1-103.
    }

    @Override
    public Identity assertIdentity(@NonNull InboundMessage msg) {
        throw new UnsupportedOperationException(
                "SimpleX identity assertion is implemented in M1-103");
    }

    @Override
    public MessageHandle send(@NonNull OutboundMessage msg) throws MessagingException {
        throw new UnsupportedOperationException(
                "SimpleX send is implemented in M1-103");
    }

    @Override
    public void update(@NonNull MessageHandle handle, @NonNull String body) throws MessagingException {
        throw new UnsupportedOperationException(
                "SimpleX update is implemented in M1-103");
    }

    @Override
    public void finalize(@NonNull MessageHandle handle, @NonNull String body) throws MessagingException {
        throw new UnsupportedOperationException(
                "SimpleX finalize is implemented in M1-103");
    }

    @Override
    public void setTyping(@NonNull ScopeRef scope, boolean typing) {
        throw new UnsupportedOperationException(
                "SimpleX typing indicator is implemented in M1-103");
    }

    @Override
    public void setInboundHandler(@NonNull InboundHandler handler) {
        this.handler = handler;
    }
}
