package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundAttachment;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Test {@link MessagingAdapter} whose {@code sendAttachment} fails the
 * first {@code failCount} attempts before succeeding — drives
 * {@link OutboundDelivery#deliverAttachment}'s retry / abort / gates. */
final class AttachmentFailingMessagingAdapter implements MessagingAdapter {

    private final String name;
    private final int failCount;
    private final FailureCategory failWith;
    private final boolean supportsAttachments;
    private final int maxBytes;
    final AtomicInteger attachmentAttempts = new AtomicInteger();
    final AtomicInteger successfulAttachments = new AtomicInteger();
    final List<OutboundAttachment> received = new ArrayList<>();

    AttachmentFailingMessagingAdapter(String name, int failCount,
            FailureCategory failWith, boolean supportsAttachments, int maxBytes) {
        this.name = name;
        this.failCount = failCount;
        this.failWith = failWith;
        this.supportsAttachments = supportsAttachments;
        this.maxBytes = maxBytes;
    }

    /** Every attachment send fails with {@code failWith}. */
    static AttachmentFailingMessagingAdapter alwaysFailing(
            String name, FailureCategory failWith, int maxBytes) {
        return new AttachmentFailingMessagingAdapter(
                name, Integer.MAX_VALUE, failWith, true, maxBytes);
    }

    /** Attachment-capable, no failures. */
    static AttachmentFailingMessagingAdapter succeeding(String name, int maxBytes) {
        return new AttachmentFailingMessagingAdapter(name, 0, FailureCategory.PERMANENT,
                true, maxBytes);
    }

    /** Declares {@code supportsOutboundAttachments=false}. */
    static AttachmentFailingMessagingAdapter withoutAttachmentSupport(String name) {
        return new AttachmentFailingMessagingAdapter(name, 0, FailureCategory.PERMANENT,
                false, 0);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void sendAttachment(OutboundAttachment attachment) throws MessagingException {
        int attempt = attachmentAttempts.incrementAndGet();
        if (attempt <= failCount) {
            throw new MessagingException(failWith,
                    "simulated " + failWith + " attachment failure");
        }
        successfulAttachments.incrementAndGet();
        received.add(attachment);
    }

    @Override
    public CapabilityFlags capabilities() {
        // Positions follow CapabilityFlags' declaration order (the P1
        // cascade): mention, membership, formatting, links, inbound-cap,
        // send-rate, edit, typing, min-edit-interval, attachments, ceiling.
        return new CapabilityFlags(false, false, false, false, 65536, 1,
                false, false, Duration.ZERO, supportsAttachments, maxBytes);
    }

    @Override
    public boolean isWellFormedContactId(String contactId) {
        return true;
    }

    @Override
    public MessageHandle send(OutboundMessage msg) {
        throw new UnsupportedOperationException("not exercised by attachment tests");
    }

    @Override
    public void update(MessageHandle handle, String body) {
        throw new UnsupportedOperationException("not exercised by attachment tests");
    }

    @Override
    public void finalizeMessage(MessageHandle handle, String body) {
        throw new UnsupportedOperationException("not exercised by attachment tests");
    }

    @Override
    public void setTyping(ScopeRef scope, boolean isTyping) {
        // no-op
    }

    @Override
    public AdapterTrustLevel trustLevel() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setInboundHandler(InboundHandler handler) {
        throw new UnsupportedOperationException();
    }
}
