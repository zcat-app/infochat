package app.zcat.infochat.provider.health;

import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

/**
 * Configurable {@link MessagingAdapter} double for
 * {@link AdapterReadinessCheck#evaluate} tests: only {@link #name()},
 * {@link #supervisorTerminallyFailed()}, {@link #connected()}, and
 * {@link #droppedInboundCount()} are consulted by readiness evaluation —
 * every other SPI method throws so an accidental call surfaces loudly
 * rather than returning a misleading default.
 */
final class FakeReadinessAdapter implements MessagingAdapter {

    private final String name;
    private final boolean terminallyFailed;
    private final boolean connected;
    private final long droppedInbound;

    FakeReadinessAdapter(String name, boolean terminallyFailed, long droppedInbound) {
        this(name, terminallyFailed, /* connected */ true, droppedInbound);
    }

    FakeReadinessAdapter(String name, boolean terminallyFailed, boolean connected,
                         long droppedInbound) {
        this.name = name;
        this.terminallyFailed = terminallyFailed;
        this.connected = connected;
        this.droppedInbound = droppedInbound;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean supervisorTerminallyFailed() {
        return terminallyFailed;
    }

    @Override
    public boolean connected() {
        return connected;
    }

    @Override
    public long droppedInboundCount() {
        return droppedInbound;
    }

    @Override
    public CapabilityFlags capabilities() {
        throw new UnsupportedOperationException();
    }

    @Override
    public AdapterTrustLevel trustLevel() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isWellFormedContactId(String contactId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public MessageHandle send(OutboundMessage msg) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void update(MessageHandle handle, String body) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void finalizeMessage(MessageHandle handle, String body) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setTyping(ScopeRef scope, boolean typing) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setInboundHandler(InboundHandler handler) {
        throw new UnsupportedOperationException();
    }
}
