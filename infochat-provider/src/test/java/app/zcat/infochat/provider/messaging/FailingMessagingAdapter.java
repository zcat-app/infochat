package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test {@link MessagingAdapter} whose {@code send} fails the first
 * {@code failCount} attempts with a configurable {@link FailureCategory}
 * before succeeding, so {@link OutboundDelivery}'s retry / abort / cleanup
 * paths can be driven without a real transport. Counts send attempts and
 * successes. Top-level package-private double (not an inner class) per the
 * project's test-double convention.
 */
final class FailingMessagingAdapter implements MessagingAdapter {

    private final String name;
    private final int failCount;
    private final FailureCategory failWith;
    final AtomicInteger sendAttempts = new AtomicInteger();
    final AtomicInteger successfulSends = new AtomicInteger();

    FailingMessagingAdapter(String name, int failCount, FailureCategory failWith) {
        this.name = name;
        this.failCount = failCount;
        this.failWith = failWith;
    }

    /** Every send fails with {@code failWith}. */
    static FailingMessagingAdapter alwaysFailing(String name, FailureCategory failWith) {
        return new FailingMessagingAdapter(name, Integer.MAX_VALUE, failWith);
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
    public MessageHandle send(OutboundMessage msg) throws MessagingException {
        int attempt = sendAttempts.incrementAndGet();
        if (attempt <= failCount) {
            throw new MessagingException(failWith, "simulated " + failWith + " send failure");
        }
        successfulSends.incrementAndGet();
        return new MessageHandle("fail-rec-" + attempt);
    }

    @Override
    public void update(MessageHandle handle, String body) {
        // Not exercised by these tests — the retry logic is driven through send.
    }

    @Override
    public void finalizeMessage(MessageHandle handle, String body) {
        // Not exercised by these tests — the retry logic is driven through send.
    }

    @Override
    public void setTyping(ScopeRef scope, boolean isTyping) {
        // no-op
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
}
