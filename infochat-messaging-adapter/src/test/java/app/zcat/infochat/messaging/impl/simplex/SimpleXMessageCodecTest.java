package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.ScopeRef;

import org.junit.jupiter.api.Test;

class SimpleXMessageCodecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void encodesAndDecodesMessages() throws Exception {
        // --- Encode: a fresh /_send to a DM, and verify the envelope shape.
        String sendFrame = SimpleXMessageCodec.encodeSendCommand(
                "corr-1",
                new ScopeRef.Dm("contact-abc"),
                "Hello, world");
        JsonNode sendRoot = MAPPER.readTree(sendFrame);
        assertEquals("corr-1", sendRoot.get("corrId").asText(),
                "corrId is the adapter-chosen pairing key");
        String cmd = sendRoot.get("cmd").asText();
        assertTrue(cmd.startsWith("/_send @contact-abc "),
                "DM send addresses the contact with @<id>: " + cmd);
        assertTrue(cmd.contains("\"text\":\"Hello, world\""),
                "text-content msgContent payload appears in the cmd string: " + cmd);

        // --- Encode: an in-place edit (live=on) and the terminal edit (live=off).
        String updateFrame = SimpleXMessageCodec.encodeUpdateCommand(
                "corr-2",
                "chat-item-99",
                new ScopeRef.Dm("contact-abc"),
                "Partial");
        String updateCmd = MAPPER.readTree(updateFrame).get("cmd").asText();
        assertTrue(updateCmd.contains("/_update item @contact-abc chat-item-99 live=on"),
                "in-place update uses live=on: " + updateCmd);

        String finalizeFrame = SimpleXMessageCodec.encodeFinalizeCommand(
                "corr-3",
                "chat-item-99",
                new ScopeRef.Dm("contact-abc"),
                "Done");
        String finalizeCmd = MAPPER.readTree(finalizeFrame).get("cmd").asText();
        assertTrue(finalizeCmd.contains("live=off"),
                "terminal edit uses live=off: " + finalizeCmd);

        // --- Encode: typing on/off (acceptance item 11).
        String typingFrame = SimpleXMessageCodec.encodeTypingCommand(
                "corr-4",
                new ScopeRef.Dm("contact-abc"),
                true);
        String typingCmd = MAPPER.readTree(typingFrame).get("cmd").asText();
        assertTrue(typingCmd.startsWith("/_set_contact_typing @contact-abc on"),
                "typing-on emits the apiSetContactTyping form: " + typingCmd);

        // --- Decode: an inbound direct-message newChatItem yields an Inbound
        //     carrying the (contact_id, scope, body) tuple per acceptance item 7.
        String inboundJson = """
                {
                  "resp": {
                    "type": "newChatItem",
                    "chatItem": {
                      "chatInfo": {
                        "chatType": "direct",
                        "contact": {
                          "contactId": "contact-xyz",
                          "displayName": "Test User"
                        }
                      },
                      "chatItem": {
                        "itemId": "msg-77",
                        "content": {
                          "msgContent": {
                            "type": "text",
                            "text": "Inbound payload"
                          }
                        }
                      }
                    }
                  }
                }
                """;
        SimpleXMessageCodec.DecodedFrame decoded = SimpleXMessageCodec.decode(inboundJson);
        var inbound = assertInstanceOf(SimpleXMessageCodec.Inbound.class, decoded);
        assertEquals("contact-xyz", inbound.message().sender().contactId(),
                "contactId is the cryptographic, stable D10 identifier");
        assertEquals("Test User", inbound.message().sender().displayName());
        assertEquals("Inbound payload", inbound.message().text());
        assertInstanceOf(ScopeRef.Dm.class, inbound.message().scope());
        assertEquals("msg-77", inbound.message().adapterMessageId());

        // --- Decode: a send-ack carrying the chat-item id (acceptance item 8).
        String ackJson = """
                {
                  "corrId": "corr-1",
                  "resp": {
                    "type": "newChatItems",
                    "chatItems": {"itemId": "msg-12345"}
                  }
                }
                """;
        var ack = assertInstanceOf(
                SimpleXMessageCodec.SendAck.class,
                SimpleXMessageCodec.decode(ackJson));
        assertEquals("corr-1", ack.corrId());
        assertEquals("msg-12345", ack.chatItemId());

        // --- Decode: an unknown response type is Ignored, not an error.
        var ignored = assertInstanceOf(
                SimpleXMessageCodec.Ignored.class,
                SimpleXMessageCodec.decode("{\"resp\": {\"type\": \"contactConnected\"}}"));
        assertNotNull(ignored.reason());

        // --- Decode: malformed frames throw MalformedFrameException.
        assertThrows(SimpleXMessageCodec.MalformedFrameException.class,
                () -> SimpleXMessageCodec.decode("not json"));
        assertThrows(SimpleXMessageCodec.MalformedFrameException.class,
                () -> SimpleXMessageCodec.decode("{\"corrId\":\"x\"}"),
                "missing resp envelope is malformed");
    }

    @Test
    void classifiesFailureCategory() {
        // Spec rule (messaging.md §Failure handling): network/timeout/rate
        // limit → TRANSIENT; user-blocked, group-gone, oversize, policy
        // violation, anything unknown → PERMANENT.
        assertEquals(FailureCategory.TRANSIENT,
                SimpleXMessageCodec.classifyError("rcvRateLimit"));
        assertEquals(FailureCategory.TRANSIENT,
                SimpleXMessageCodec.classifyError("tryAgainLater"));
        assertEquals(FailureCategory.TRANSIENT,
                SimpleXMessageCodec.classifyError("networkError"));
        assertEquals(FailureCategory.TRANSIENT,
                SimpleXMessageCodec.classifyError("connectionTimeout"));

        assertEquals(FailureCategory.PERMANENT,
                SimpleXMessageCodec.classifyError("contactNotFound"));
        assertEquals(FailureCategory.PERMANENT,
                SimpleXMessageCodec.classifyError("groupNotFound"));
        assertEquals(FailureCategory.PERMANENT,
                SimpleXMessageCodec.classifyError("userBlocked"));
        assertEquals(FailureCategory.PERMANENT,
                SimpleXMessageCodec.classifyError("messageTooLarge"));
        assertEquals(FailureCategory.PERMANENT,
                SimpleXMessageCodec.classifyError("invalidChatItemUpdate"));
        // "Cannot tell → PERMANENT" — the spec's default.
        assertEquals(FailureCategory.PERMANENT,
                SimpleXMessageCodec.classifyError(""));
        assertEquals(FailureCategory.PERMANENT,
                SimpleXMessageCodec.classifyError("CRChatCmdError"));

        // End-to-end: decoding an error envelope buckets via the same rule.
        String errorJson = """
                {
                  "corrId": "corr-9",
                  "resp": {
                    "type": "chatCmdError",
                    "chatError": {"errorType": "contactNotFound"}
                  }
                }
                """;
        var decodedError = assertInstanceOf(
                SimpleXMessageCodec.CommandError.class,
                SimpleXMessageCodec.decode(errorJson));
        assertEquals(FailureCategory.PERMANENT, decodedError.category());
        assertEquals("corr-9", decodedError.corrId());

        String transientErrorJson = """
                {
                  "corrId": "corr-10",
                  "resp": {
                    "type": "chatCmdError",
                    "chatError": {"errorType": "rcvRateLimit"}
                  }
                }
                """;
        var transientError = assertInstanceOf(
                SimpleXMessageCodec.CommandError.class,
                SimpleXMessageCodec.decode(transientErrorJson));
        assertEquals(FailureCategory.TRANSIENT, transientError.category());
    }
}
