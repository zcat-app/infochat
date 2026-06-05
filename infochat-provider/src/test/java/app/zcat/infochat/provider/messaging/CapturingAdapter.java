package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

import java.util.ArrayList;
import java.util.List;

/** Captures outbound messages the router sends. */
final class CapturingAdapter implements MessagingAdapter {
    final List<OutboundMessage> captured = new ArrayList<>();
    private final String name;

    // Default name "inmemory" matches the inbound adapterName the
    // router tests deliver, so the router's name-keyed reply
    // resolution finds this fake. Multi-adapter routing tests
    // construct a second instance under a different name.
    CapturingAdapter() {
        this("inmemory");
    }

    CapturingAdapter(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
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
        captured.add(msg);
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
