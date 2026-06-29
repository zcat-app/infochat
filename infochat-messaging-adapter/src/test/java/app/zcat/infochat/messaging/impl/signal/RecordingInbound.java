package app.zcat.infochat.messaging.impl.signal;

import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessagingAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Recording {@link MessagingAdapter.InboundHandler} double shared across the
 * Signal adapter tests: captures every delivered {@link InboundMessage} in
 * arrival order for assertion.
 */
final class RecordingInbound implements MessagingAdapter.InboundHandler {
    final List<InboundMessage> messages = new ArrayList<>();

    @Override
    public void onMessage(InboundMessage msg) {
        messages.add(msg);
    }
}
