package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import app.zcat.infochat.messaging.FailureCategory;
import org.junit.jupiter.api.Test;

/**
 * Pins the known-field decode rule: the send-ack chatItemId and the
 * command-error tag are read from the known response fields only
 * (direct fields, or the {@code chatItems} / {@code chatError}
 * containers). A textual node with a matching name nested inside an
 * UNRELATED container — which simplex-chat may populate with bytes
 * echoed back from the offending inbound — must NOT be picked up;
 * the pre-M1-148 breadth-first key search over every child object
 * allowed exactly that.
 */
class SimpleXMessageCodecKnownFieldTest {

    @Test
    void forgedItemIdInUnrelatedContainerIsNotASendAck() throws Exception {
        String frame = """
                {
                  "corrId": "corr-1",
                  "resp": {
                    "type": "newChatItems",
                    "echoedBody": {"itemId": "forged-id"}
                  }
                }
                """;
        var decoded = SimpleXMessageCodec.decode(frame);
        var ignored = assertInstanceOf(SimpleXMessageCodec.Ignored.class, decoded);
        assertEquals("send-ack-without-chatItemId", ignored.reason(),
                "an itemId nested in an unrelated container must not be read as the ack id");
    }

    @Test
    void knownChatItemsContainerStillYieldsSendAck() throws Exception {
        String frame = """
                {
                  "corrId": "corr-2",
                  "resp": {
                    "type": "newChatItems",
                    "chatItems": {"itemId": "msg-1"}
                  }
                }
                """;
        var ack = assertInstanceOf(
                SimpleXMessageCodec.SendAck.class,
                SimpleXMessageCodec.decode(frame));
        assertEquals("msg-1", ack.chatItemId());
    }

    @Test
    void forgedErrorTagInUnrelatedContainerYieldsFixedSentinel() throws Exception {
        // Pre-M1-148 the breadth-first search would have found the
        // forged rcvRateLimit tag and classified the error TRANSIENT
        // (retry). With known-field reads the tag is unrecognized:
        // fixed sentinel detail + the spec's default-to-PERMANENT.
        String frame = """
                {
                  "corrId": "corr-3",
                  "resp": {
                    "type": "chatCmdError",
                    "echoedBody": {"errorType": "rcvRateLimit"}
                  }
                }
                """;
        var error = assertInstanceOf(
                SimpleXMessageCodec.CommandError.class,
                SimpleXMessageCodec.decode(frame));
        assertEquals("unrecognized-error-envelope", error.detail());
        assertEquals(FailureCategory.PERMANENT, error.category(),
                "a forged transient tag in an unrelated container must not flip the category");
    }

    @Test
    void knownChatErrorContainerStillClassifies() throws Exception {
        String frame = """
                {
                  "corrId": "corr-4",
                  "resp": {
                    "type": "chatCmdError",
                    "chatError": {"type": "error", "errorType": {"type": "rcvRateLimit"}}
                  }
                }
                """;
        var error = assertInstanceOf(
                SimpleXMessageCodec.CommandError.class,
                SimpleXMessageCodec.decode(frame));
        assertEquals(FailureCategory.TRANSIENT, error.category());
    }
}
