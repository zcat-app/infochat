package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Recording {@link MessagingAdapter} for {@link StageProgressNotifier}
 * unit tests. Captures the four outbound primitives the notifier
 * exercises — {@code send} (placeholder), {@code update} (coalesced
 * stage edits), {@code finalizeMessage} (terminal), and {@code setTyping}
 * — with simple accessors so a test can pin the spec steps 1–4
 * sequence and the edit-coalescing cadence without reaching past the
 * {@link MessageHandle} opacity invariant. Methods the notifier never
 * calls throw {@link UnsupportedOperationException} so a future change
 * that starts using them fails loudly (mirrors {@link CapturingAdapter}).
 */
final class RecordingMessagingAdapter implements MessagingAdapter {

    private final String name;
    private final AtomicInteger handleIds = new AtomicInteger();
    final List<String> sends = new ArrayList<>();
    final List<String> updates = new ArrayList<>();
    final List<String> finalizes = new ArrayList<>();
    final List<TypingRecord> typing = new ArrayList<>();

    RecordingMessagingAdapter() {
        this("inmemory");
    }

    RecordingMessagingAdapter(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isWellFormedContactId(String contactId) {
        return true;
    }

    @Override
    public MessageHandle send(OutboundMessage msg) {
        sends.add(msg.text());
        return new MessageHandle("rec-" + handleIds.incrementAndGet());
    }

    @Override
    public void update(MessageHandle handle, String body) {
        updates.add(body);
    }

    @Override
    public void finalizeMessage(MessageHandle handle, String body) {
        finalizes.add(body);
    }

    @Override
    public void setTyping(ScopeRef scope, boolean isTyping) {
        typing.add(new TypingRecord(scope, isTyping));
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
    public void setInboundHandler(InboundHandler handler) {
        throw new UnsupportedOperationException();
    }

    int updateCount() {
        return updates.size();
    }

    record TypingRecord(ScopeRef scope, boolean typing) {}
}
