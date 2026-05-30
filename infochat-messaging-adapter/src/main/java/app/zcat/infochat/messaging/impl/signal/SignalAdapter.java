package app.zcat.infochat.messaging.impl.signal;

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
 * Signal production adapter — skeleton. Declares the adapter's
 * selection name, HIGH trust level, and static capability flags; the
 * subprocess, JSON-RPC connection, and live send/receive are M1-107.
 *
 * <p>Signal's contact id is the ACI (Account Credential Identifier),
 * the UUID signal-cli surfaces as {@code mentionUuid} — the D10 trust
 * anchor — so this adapter is {@link AdapterTrustLevel#HIGH}. The
 * unimplemented transport methods throw {@link UnsupportedOperationException}
 * rather than returning placeholder values; in particular
 * {@link #assertIdentity} throws, because a HIGH-trust adapter must
 * never return an unverified identity (real assertion is M1-107). No
 * CDI annotations here — the adapter bean's producer/discovery is
 * Provider-side wiring (M1-035b/M1-105), mirroring InMemoryAdapter.</p>
 */
public final class SignalAdapter implements MessagingAdapter {

    // maxInboundMessageBytes is the 16 KiB laptop default per
    // docs/design/06-messaging.md §6.2.2 (profile-tunable). maxMessageBytes,
    // maxSendsPerSecond, and minEditInterval are best-guess defaults not
    // fixed by spec and are expected to be tuned against a live signal-cli.
    private static final CapabilityFlags CAPABILITIES = new CapabilityFlags(
            /* supportsMentionByContactId */ true,
            /* supportsMembershipEvents   */ true,
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
        return "signal";
    }

    @Override
    public CapabilityFlags capabilities() {
        return CAPABILITIES;
    }

    @Override
    public AdapterTrustLevel trustLevel() {
        return AdapterTrustLevel.HIGH;
    }

    /** Start the signal-cli subprocess and connect — implemented in M1-107. */
    public void start() {
        // No-op skeleton; subprocess + JSON-RPC connection are M1-107.
    }

    /** Disconnect and stop the signal-cli subprocess — implemented in M1-107. */
    public void close() {
        // No-op skeleton; subprocess shutdown is M1-107.
    }

    @Override
    public Identity assertIdentity(@NonNull InboundMessage msg) {
        throw new UnsupportedOperationException(
                "Signal identity assertion is implemented in M1-107");
    }

    @Override
    public MessageHandle send(@NonNull OutboundMessage msg) throws MessagingException {
        throw new UnsupportedOperationException(
                "Signal send is implemented in M1-107");
    }

    @Override
    public void update(@NonNull MessageHandle handle, @NonNull String body) throws MessagingException {
        throw new UnsupportedOperationException(
                "Signal update is implemented in M1-107");
    }

    @Override
    public void finalize(@NonNull MessageHandle handle, @NonNull String body) throws MessagingException {
        throw new UnsupportedOperationException(
                "Signal finalize is implemented in M1-107");
    }

    @Override
    public void setTyping(@NonNull ScopeRef scope, boolean typing) {
        throw new UnsupportedOperationException(
                "Signal typing indicator is implemented in M1-107");
    }

    @Override
    public void setInboundHandler(@NonNull InboundHandler handler) {
        this.handler = handler;
    }
}
