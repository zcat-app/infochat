package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

/** Adapter that swallows {@link #send} without throwing — isolates dispatch-side behavior from delivery. */
final class NoopAdapter implements MessagingAdapter {
    // Reports the inbound adapterName ("inmemory") the router tests
    // deliver so the router's name-keyed reply resolution binds this
    // fake as the reply target.
    @Override
    public String name() {
        return "inmemory";
    }

    @Override
    public CapabilityFlags capabilities() {
        throw new UnsupportedOperationException();
    }

    @Override
    public app.zcat.infochat.messaging.AdapterTrustLevel trustLevel() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Identity assertIdentity(InboundMessage msg) {
        throw new UnsupportedOperationException();
    }

    @Override
    public MessageHandle send(OutboundMessage msg) {
        return null;
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
