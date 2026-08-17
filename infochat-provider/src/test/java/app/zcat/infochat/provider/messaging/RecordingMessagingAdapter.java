package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

import java.time.Duration;
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
    // Adapter-declared edit-interval floor surfaced through capabilities();
    // ZERO by default so the system floor governs (the pre-U-32 behaviour the
    // coalescing tests pin). A test that needs the adapter floor to win sets a
    // non-zero value via withMinEditInterval().
    private Duration minEditInterval = Duration.ZERO;
    // TRUE by default (every v1 adapter declares it); the M1-607 degraded-
    // path contract test flips it via withSupportsMessageEdit(false) to pin
    // the notifier's collapse-to-single-final-send behaviour.
    private boolean supportsMessageEdit = true;
    // FALSE by default (the unknown-flag rule); live-text tests flip it via
    // withSupportsLiveText(true).
    private boolean supportsLiveText = false;
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

    /** Set the adapter-declared minEditInterval surfaced through {@link #capabilities()}. */
    RecordingMessagingAdapter withMinEditInterval(Duration interval) {
        this.minEditInterval = interval;
        return this;
    }

    /** Set the supportsMessageEdit capability surfaced through {@link #capabilities()}. */
    RecordingMessagingAdapter withSupportsMessageEdit(boolean supported) {
        this.supportsMessageEdit = supported;
        return this;
    }

    /** Set the supportsLiveText capability surfaced through {@link #capabilities()}. */
    RecordingMessagingAdapter withSupportsLiveText(boolean supported) {
        this.supportsLiveText = supported;
        return this;
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
        // Only minEditInterval and supportsMessageEdit are read by
        // StageProgressNotifier; the rest are benign placeholders
        // sufficient for the notifier's coalescing path.
        return new CapabilityFlags(
                /* supportsMentionByContactId */ false,
                /* supportsMembershipEvents    */ false,
                /* supportsCodeFormatting      */ false,
                /* supportsMarkdownLinks       */ false,
                /* maxInboundMessageBytes      */ 65536,
                /* maxSendsPerSecond           */ 1,
                supportsMessageEdit,
                supportsLiveText,
                /* supportsTypingIndicator     */ true,
                /* minEditInterval             */ minEditInterval,
                /* supportsOutboundAttachments */ false,
                /* maxOutboundAttachmentBytes  */ 0);
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
