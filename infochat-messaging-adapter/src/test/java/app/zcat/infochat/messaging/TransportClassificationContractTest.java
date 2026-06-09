package app.zcat.infochat.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Shared transport-classification contract test: asserts each production
 * adapter against the cross-adapter rows of the {@link FailureCategory}
 * classification matrix — one rule, two adapters. A concrete subclass per
 * adapter drives its transport into each matrix state and returns the
 * resulting {@link MessagingException}; the assertions on the categories
 * live here so the two transports can never drift apart again.
 */
public abstract class TransportClassificationContractTest {

    /**
     * Drive a send to the point of awaiting its ack, interrupt the
     * sending thread, and return the resulting failure.
     */
    protected abstract MessagingException interruptedAwaitingAck() throws Exception;

    /**
     * Drive a send to the point of awaiting its ack, close the transport
     * locally with the ack still outstanding, and return the resulting
     * failure.
     */
    protected abstract MessagingException closedBeforeAck() throws Exception;

    @Test
    protected void interruptedAwaitingAckClassifiesTransient() throws Exception {
        assertEquals(FailureCategory.TRANSIENT, interruptedAwaitingAck().category(),
                "matrix row 'interrupted-awaiting-ack': the interrupt is a local"
                        + " thread-lifecycle event, not a verdict on the transport"
                        + " or the message");
    }

    @Test
    protected void closedBeforeAckClassifiesPermanent() throws Exception {
        assertEquals(FailureCategory.PERMANENT, closedBeforeAck().category(),
                "matrix row 'closed-before-ack': retrying against a closed"
                        + " connection cannot succeed until the transport is rebuilt");
    }
}
