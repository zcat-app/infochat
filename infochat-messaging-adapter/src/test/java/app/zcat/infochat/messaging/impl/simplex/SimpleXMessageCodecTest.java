package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.ScopeRef;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

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

    // --- M1-118: queue-address character-set validation (Finding 2, INJECTION) ---

    @Test
    void decodeRejectsContactIdWithCommandInjectionChars() {
        // A peer-controlled contactId carrying a space, newline, or
        // simplex-chat command terminator must be dropped at the codec
        // before any InboundMessage is constructed. Otherwise the value
        // could be echoed into a /_send / /_update item / /_set_contact_typing
        // command and execute a forged verb under the bot's identity
        // (red-team Finding 2, design §6.4.4).
        String[] injectionContactIds = {
                "abc xyz",                       // bare space — splits the command
                "abc\nxyz",                      // newline — terminates the command
                "abc\r\n/_send @victim json {}", // CRLF + forged verb
                "abc /_set_contact_typing @v on",// space + forged verb
                "abc#group-id",                  // group terminator inside DM id
                "abc@other-contact",             // contact terminator
                "",                              // empty — refuses degenerate case
        };
        for (String badId : injectionContactIds) {
            String frame = """
                    {
                      "resp": {
                        "type": "newChatItem",
                        "chatItem": {
                          "chatInfo": {
                            "chatType": "direct",
                            "contact": {
                              "contactId": %s,
                              "displayName": "Peer"
                            }
                          },
                          "chatItem": {
                            "itemId": "msg-1",
                            "content": {
                              "msgContent": {
                                "type": "text",
                                "text": "hi"
                              }
                            }
                          }
                        }
                      }
                    }
                    """.formatted(jsonStringLiteral(badId));
            var decoded = SimpleXMessageCodec.decode(frame);
            assertInstanceOf(SimpleXMessageCodec.Ignored.class, decoded,
                    "contactId with injection chars must decode as Ignored: <" + badId + ">");
            // Defense-in-depth predicate check: the validator agrees.
            assertFalse(SimpleXMessageCodec.isValidQueueAddressId(badId),
                    "validator must reject id: <" + badId + ">");
        }
    }

    @Test
    void decodeAcceptsValidQueueAddressShapedContactId() {
        // A contactId in the documented queue-address character set
        // (URL-safe base64 ∪ decimal — A-Z, a-z, 0-9, _ = . -)
        // decodes to an Inbound with the contactId preserved verbatim.
        String[] validContactIds = {
                "12345",                              // simplex-chat decimal DB row id
                "abc-DEF_ghi=jkl.mno",                // URL-safe base64 alphabet
                "Bnv1l0BPLkXjA38n-bWvHQ==",           // realistic base64-shape sample
                "a",                                  // single-char minimum
        };
        for (String validId : validContactIds) {
            assertTrue(SimpleXMessageCodec.isValidQueueAddressId(validId),
                    "validator must accept id: <" + validId + ">");
            String frame = """
                    {
                      "resp": {
                        "type": "newChatItem",
                        "chatItem": {
                          "chatInfo": {
                            "chatType": "direct",
                            "contact": {
                              "contactId": %s,
                              "displayName": "Peer"
                            }
                          },
                          "chatItem": {
                            "itemId": "msg-1",
                            "content": {
                              "msgContent": {
                                "type": "text",
                                "text": "hi"
                              }
                            }
                          }
                        }
                      }
                    }
                    """.formatted(jsonStringLiteral(validId));
            var decoded = SimpleXMessageCodec.decode(frame);
            var inbound = assertInstanceOf(SimpleXMessageCodec.Inbound.class, decoded,
                    "valid contactId must decode as Inbound: <" + validId + ">");
            assertEquals(validId, inbound.message().sender().contactId(),
                    "contactId preserved verbatim through decode");
            assertEquals(new ScopeRef.Dm(validId), inbound.message().scope(),
                    "scope ref carries the same contactId");
        }
    }

    @Test
    void encodeRejectsContactIdWithCommandInjectionChars() {
        // Defense-in-depth (design §6.4.4): every encode entry point
        // re-asserts the validator on the ScopeRef's contactId /
        // adapterGroupId, and on chatItemId for the edit paths. A failure
        // throws IllegalStateException so a Provider bug that bypassed the
        // decode-time gate (e.g., synthesised ScopeRef from a non-codec
        // source) still cannot leak a forged verb to the wire.
        ScopeRef badDm = new ScopeRef.Dm("abc /_send @victim json {}");
        ScopeRef badGroup = new ScopeRef.Group("group\n/_set_contact_typing @v on");
        ScopeRef goodDm = new ScopeRef.Dm("contact-abc");

        assertThrows(IllegalStateException.class,
                () -> SimpleXMessageCodec.encodeSendCommand("corr-1", badDm, "hi"));
        assertThrows(IllegalStateException.class,
                () -> SimpleXMessageCodec.encodeSendCommand("corr-1", badGroup, "hi"));

        assertThrows(IllegalStateException.class,
                () -> SimpleXMessageCodec.encodeUpdateCommand("corr-1", "chat-item-1", badDm, "hi"));
        assertThrows(IllegalStateException.class,
                () -> SimpleXMessageCodec.encodeFinalizeCommand("corr-1", "chat-item-1", badDm, "hi"));
        assertThrows(IllegalStateException.class,
                () -> SimpleXMessageCodec.encodeTypingCommand("corr-1", badDm, true));

        // chatItemId is round-tripped from simplex-chat in a SendAck; an
        // attacker-poisoned ack would surface here. encodeUpdate / encodeFinalize
        // must reject a tainted chatItemId even with a clean ScopeRef.
        assertThrows(IllegalStateException.class,
                () -> SimpleXMessageCodec.encodeUpdateCommand(
                        "corr-1", "chat-item-1\n/_send @victim json {}", goodDm, "hi"));
        assertThrows(IllegalStateException.class,
                () -> SimpleXMessageCodec.encodeFinalizeCommand(
                        "corr-1", "chat-item-1 forged", goodDm, "hi"));
    }

    // --- M1-118: inbound text-size cap (Finding 3, DOS) ---

    @Test
    void decodeRejectsTextExceedingInboundCap() {
        // A peer-controlled text payload exceeding the SPI-declared inbound
        // cap (16 KiB on the laptop profile per design §6.2.2 / §6.4.4)
        // must be dropped at decode, BEFORE InboundMessage construction,
        // so the Provider's downstream budgets (LLM tokens, Stage 1 watchdog)
        // can plan against a real ceiling rather than the 1 MiB WS frame cap
        // (red-team Finding 3).
        int oversize = SimpleXMessageCodec.MAX_INBOUND_TEXT_BYTES + 1;
        String oversizeText = "a".repeat(oversize); // 1 ASCII byte per char
        assertEquals(oversize, oversizeText.getBytes(StandardCharsets.UTF_8).length,
                "fixture sanity: ASCII = one UTF-8 byte per char");
        String frame = """
                {
                  "resp": {
                    "type": "newChatItem",
                    "chatItem": {
                      "chatInfo": {
                        "chatType": "direct",
                        "contact": {
                          "contactId": "contact-abc",
                          "displayName": "Peer"
                        }
                      },
                      "chatItem": {
                        "itemId": "msg-big",
                        "content": {
                          "msgContent": {
                            "type": "text",
                            "text": %s
                          }
                        }
                      }
                    }
                  }
                }
                """.formatted(jsonStringLiteral(oversizeText));
        var decoded = SimpleXMessageCodec.decode(frame);
        var ignored = assertInstanceOf(SimpleXMessageCodec.Ignored.class, decoded,
                "oversize inbound text must decode as Ignored");
        assertTrue(ignored.reason().contains("cap"),
                "Ignored reason should name the cap: " + ignored.reason());
    }

    @Test
    void decodeAcceptsTextAtExactlyInboundCap() {
        // Boundary condition: exactly MAX_INBOUND_TEXT_BYTES is still
        // acceptable (the cap is inclusive). Drop-policy fires at cap+1.
        int exact = SimpleXMessageCodec.MAX_INBOUND_TEXT_BYTES;
        String exactText = "a".repeat(exact);
        assertEquals(exact, exactText.getBytes(StandardCharsets.UTF_8).length);
        String frame = """
                {
                  "resp": {
                    "type": "newChatItem",
                    "chatItem": {
                      "chatInfo": {
                        "chatType": "direct",
                        "contact": {
                          "contactId": "contact-abc",
                          "displayName": "Peer"
                        }
                      },
                      "chatItem": {
                        "itemId": "msg-cap",
                        "content": {
                          "msgContent": {
                            "type": "text",
                            "text": %s
                          }
                        }
                      }
                    }
                  }
                }
                """.formatted(jsonStringLiteral(exactText));
        var inbound = assertInstanceOf(
                SimpleXMessageCodec.Inbound.class,
                SimpleXMessageCodec.decode(frame),
                "inbound text at exactly the cap must still be accepted");
        assertEquals(exact, inbound.message().text().getBytes(StandardCharsets.UTF_8).length);
    }

    /** Render a Java String as a JSON string literal (with surrounding quotes). */
    private static String jsonStringLiteral(String s) {
        try {
            return MAPPER.writeValueAsString(s);
        } catch (Exception e) {
            throw new AssertionError("jsonStringLiteral", e);
        }
    }
}
