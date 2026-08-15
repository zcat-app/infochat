package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.ScopeRef;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

class SimpleXMessageCodecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void encodeSendCommand_buildsDmEnvelope() throws Exception {
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
    }

    @Test
    void encodeUpdateCommand_usesLiveOn() throws Exception {
        String updateFrame = SimpleXMessageCodec.encodeUpdateCommand(
                "corr-2",
                "chat-item-99",
                new ScopeRef.Dm("contact-abc"),
                "Partial");
        String updateCmd = MAPPER.readTree(updateFrame).get("cmd").asText();
        assertTrue(updateCmd.contains("/_update item @contact-abc chat-item-99 live=on"),
                "in-place update uses live=on: " + updateCmd);
    }

    @Test
    void encodeFinalizeCommand_usesLiveOff() throws Exception {
        String finalizeFrame = SimpleXMessageCodec.encodeFinalizeCommand(
                "corr-3",
                "chat-item-99",
                new ScopeRef.Dm("contact-abc"),
                "Done");
        String finalizeCmd = MAPPER.readTree(finalizeFrame).get("cmd").asText();
        assertTrue(finalizeCmd.contains("live=off"),
                "terminal edit uses live=off: " + finalizeCmd);
    }

    @Test
    void encodeEditCommandCarriesMentionsKey() throws Exception {
        // M1-839: the /_update UpdatedMessage json REQUIRES a mentions key —
        // a msgContent-only payload is rejected "Failed reading: empty" on
        // both pinned binaries (before/after frames in the ticket record).
        String updateFrame = SimpleXMessageCodec.encodeUpdateCommand(
                "corr-u", "9", new ScopeRef.Dm("contact-abc"), "Partial");
        String updateCmd = MAPPER.readTree(updateFrame).get("cmd").asText();
        assertTrue(updateCmd.endsWith(" json {\"msgContent\":{\"type\":\"text\",\"text\":\"Partial\"},\"mentions\":{}}"),
                "the edit payload must be an UpdatedMessage with an empty mentions map: " + updateCmd);
        String finalizeFrame = SimpleXMessageCodec.encodeFinalizeCommand(
                "corr-f", "9", new ScopeRef.Dm("contact-abc"), "Done");
        String finalizeCmd = MAPPER.readTree(finalizeFrame).get("cmd").asText();
        assertTrue(finalizeCmd.endsWith(" json {\"msgContent\":{\"type\":\"text\",\"text\":\"Done\"},\"mentions\":{}}"),
                "the finalize payload carries the same mentions map: " + finalizeCmd);
    }

    @Test
    void decode_directMessageYieldsInbound() {
        // An inbound direct-message newChatItem yields an Inbound carrying the
        // (contact_id, scope, body) tuple per acceptance item 7.
        String inboundJson = """
                {
                  "resp": {
                    "type": "newChatItem",
                    "chatItem": {
                      "chatInfo": {
                        "type": "direct",
                        "contact": {
                          "contactId": "contact-xyz",
                          "localDisplayName": "Test User"
                        }
                      },
                      "chatItem": {
                        "meta": {"itemId": "msg-77"},
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
    }

    @Test
    void decode_sendAckCarriesChatItemId() {
        // A send-ack carrying the chat-item id (acceptance item 8).
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
    }

    @Test
    void decode_unknownResponseTypeIsIgnored() {
        // An unknown response type is Ignored, not an error.
        var ignored = assertInstanceOf(
                SimpleXMessageCodec.Ignored.class,
                SimpleXMessageCodec.decode("{\"resp\": {\"type\": \"contactConnected\"}}"));
        assertNotNull(ignored.reason());
    }

    @Test
    void decode_malformedFrameThrows() {
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
        // The tag is the .type of the nested errorType object on the live
        // v6.5.4.1 wire (M1-510).
        String errorJson = """
                {
                  "corrId": "corr-9",
                  "resp": {
                    "type": "chatCmdError",
                    "chatError": {"type": "error", "errorType": {"type": "contactNotFound"}}
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
                    "chatError": {"type": "error", "errorType": {"type": "rcvRateLimit"}}
                  }
                }
                """;
        var transientError = assertInstanceOf(
                SimpleXMessageCodec.CommandError.class,
                SimpleXMessageCodec.decode(transientErrorJson));
        assertEquals(FailureCategory.TRANSIENT, transientError.category());
    }

    @Test
    void classifyErrorUsesExactIncludeListNotSubstringMatch() {
        // U-23: the classifier is an exact include-list, not substring
        // matching. An unknown tag that merely CONTAINS a transient-looking
        // fragment must classify PERMANENT — the spec's fail-closed default
        // (messaging.md §Failure handling) — instead of being promoted to
        // TRANSIENT and retried forever.
        assertEquals(FailureCategory.PERMANENT,
                SimpleXMessageCodec.classifyError("storeErrorTemporaryGlitch"),
                "an unknown tag containing 'temporary' must be PERMANENT, not"
                        + " promoted to TRANSIENT by substring matching");
        assertEquals(FailureCategory.PERMANENT,
                SimpleXMessageCodec.classifyError("temporary"),
                "the bare 'temporary' fragment is not a recognised tag");
        // Tags the old substring matchers caught only by fragment ("unavailable",
        // a bare "...timeout"-suffixed unknown) now fail closed too.
        assertEquals(FailureCategory.PERMANENT,
                SimpleXMessageCodec.classifyError("serviceUnavailable"));
        assertEquals(FailureCategory.PERMANENT,
                SimpleXMessageCodec.classifyError("storeTimeout"));

        // Each include-listed transient tag classifies TRANSIENT, folded
        // case-insensitively from the wire spelling.
        assertEquals(FailureCategory.TRANSIENT,
                SimpleXMessageCodec.classifyError("rcvRateLimit"));
        assertEquals(FailureCategory.TRANSIENT,
                SimpleXMessageCodec.classifyError("tryAgainLater"));
        assertEquals(FailureCategory.TRANSIENT,
                SimpleXMessageCodec.classifyError("networkError"));
        assertEquals(FailureCategory.TRANSIENT,
                SimpleXMessageCodec.classifyError("connectionTimeout"));
        // Case folding: an upper-cased include-listed tag is still TRANSIENT.
        assertEquals(FailureCategory.TRANSIENT,
                SimpleXMessageCodec.classifyError("RCVRATELIMIT"));
    }

    // --- M1-800: outbound attachment file-send encoding (D74) ---

    @Test
    void encodeSendFileCommandEmitsTheFileForm() throws Exception {
        // The file send rides the same id-addressed /_send verb as text, one composed
        // message whose filePath names the spool file (verified against the bundled
        // simplex-chat v6.5.4.1; docs/design/06-messaging.md §6.2.4).
        String frame = SimpleXMessageCodec.encodeSendFileCommand(
                "corr-file-1",
                new ScopeRef.Dm("contact-abc"),
                "/var/spool/infochat/out/img-123.png",
                "image/png",
                "img-123.png");
        JsonNode root = MAPPER.readTree(frame);
        assertEquals("corr-file-1", root.get("corrId").asText(),
                "corrId is the adapter-chosen pairing key");
        String cmd = root.get("cmd").asText();
        assertTrue(cmd.startsWith("/_send @contact-abc json "),
                "DM file send addresses the contact with @<id>: " + cmd);
        JsonNode composed = MAPPER.readTree(cmd.substring(cmd.indexOf(" json ") + 6)).get(0);
        assertEquals("/var/spool/infochat/out/img-123.png",
                composed.get("filePath").asText(),
                "the file-send form carries the spool file path");
        assertEquals("file", composed.get("msgContent").get("type").asText(),
                "msgContent is the file type");
        assertEquals("", composed.get("msgContent").get("text").asText(),
                "the attachment carries no text body");

        String groupFrame = SimpleXMessageCodec.encodeSendFileCommand(
                "corr-file-2",
                new ScopeRef.Group("group-1"),
                "/var/spool/infochat/out/img-123.png",
                "image/png",
                "img-123.png");
        String groupCmd = MAPPER.readTree(groupFrame).get("cmd").asText();
        assertTrue(groupCmd.startsWith("/_send #group-1 json "),
                "group file send addresses the group with #<id>: " + groupCmd);
    }

    @Test
    void encodeSendFileCommandRejectsInjectionInScopeIds() {
        // Same defense-in-depth as the text path (design §6.4.4): the
        // scope ids are pasted into the command verb, so the queue-address
        // validator re-asserts them at the file-send encode boundary.
        assertEquals(FailureCategory.PERMANENT, assertThrows(MessagingException.class,
                () -> SimpleXMessageCodec.encodeSendFileCommand(
                        "corr-1", new ScopeRef.Dm("abc /_send @victim json {}"),
                        "/tmp/f.png", "image/png", "f.png")).category());
        assertEquals(FailureCategory.PERMANENT, assertThrows(MessagingException.class,
                () -> SimpleXMessageCodec.encodeSendFileCommand(
                        "corr-1", new ScopeRef.Group("group\n/_send @v json {}"),
                        "/tmp/f.png", "image/png", "f.png")).category());
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
                            "type": "direct",
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
    void directNewChatItemWithProfileContactLinkStillResolvesConnectionContactId() {
        // Regression guard for M1-506 / decision D50: a SimpleX sender's
        // advertised profile address (contact.profile.contactLink) is
        // self-asserted and NOT verified (out of scope of the SMP
        // protocol), so it must never influence the resolved identity.
        // The discarded M1-505 approach mapped that advertised address to
        // the contact_id / authz key, which let any contact spoof the
        // admin. This asserts the codec still resolves both
        // Identity.contactId AND ScopeRef.Dm.contactId to the
        // connection-based contactId even when a contactLink is present and
        // differs from it — the mapping is not (re)introduced.
        String connectionContactId = "Bnv1l0BPLkXjA38n-bWvHQ==";
        String advertisedContactLink =
                "https://simplex.chat/contact#/?v=2-7&smp=smp%3A%2F%2FhQ%40smp.example.com";
        String frame = """
                {
                  "resp": {
                    "type": "newChatItem",
                    "chatItem": {
                      "chatInfo": {
                        "type": "direct",
                        "contact": {
                          "contactId": %s,
                          "displayName": "Peer",
                          "profile": {
                            "contactLink": %s
                          }
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
                """.formatted(jsonStringLiteral(connectionContactId),
                        jsonStringLiteral(advertisedContactLink));
        var decoded = SimpleXMessageCodec.decode(frame);
        var inbound = assertInstanceOf(SimpleXMessageCodec.Inbound.class, decoded,
                "a direct frame with a profile.contactLink must still decode as Inbound");
        assertEquals(connectionContactId, inbound.message().sender().contactId(),
                "identity is the connection contactId, never the advertised contactLink");
        assertEquals(new ScopeRef.Dm(connectionContactId), inbound.message().scope(),
                "scope ref carries the connection contactId, never the advertised contactLink");
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
                            "type": "direct",
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
        // throws MessagingException(PERMANENT) — the SPI's two-category
        // retry model — so a Provider bug that bypassed the decode-time
        // gate (e.g., synthesised ScopeRef from a non-codec source) still
        // cannot leak a forged verb to the wire, and the fault routes as a
        // non-retryable permanent failure rather than an unchecked escape.
        ScopeRef badDm = new ScopeRef.Dm("abc /_send @victim json {}");
        ScopeRef badGroup = new ScopeRef.Group("group\n/_set_contact_typing @v on");
        ScopeRef goodDm = new ScopeRef.Dm("contact-abc");

        assertEquals(FailureCategory.PERMANENT, assertThrows(MessagingException.class,
                () -> SimpleXMessageCodec.encodeSendCommand("corr-1", badDm, "hi")).category());
        assertEquals(FailureCategory.PERMANENT, assertThrows(MessagingException.class,
                () -> SimpleXMessageCodec.encodeSendCommand("corr-1", badGroup, "hi")).category());

        assertEquals(FailureCategory.PERMANENT, assertThrows(MessagingException.class,
                () -> SimpleXMessageCodec.encodeUpdateCommand("corr-1", "chat-item-1", badDm, "hi")).category());
        assertEquals(FailureCategory.PERMANENT, assertThrows(MessagingException.class,
                () -> SimpleXMessageCodec.encodeFinalizeCommand("corr-1", "chat-item-1", badDm, "hi")).category());

        // chatItemId is round-tripped from simplex-chat in a SendAck; an
        // attacker-poisoned ack would surface here. encodeUpdate / encodeFinalize
        // must reject a tainted chatItemId even with a clean ScopeRef.
        assertEquals(FailureCategory.PERMANENT, assertThrows(MessagingException.class,
                () -> SimpleXMessageCodec.encodeUpdateCommand(
                        "corr-1", "chat-item-1\n/_send @victim json {}", goodDm, "hi")).category());
        assertEquals(FailureCategory.PERMANENT, assertThrows(MessagingException.class,
                () -> SimpleXMessageCodec.encodeFinalizeCommand(
                        "corr-1", "chat-item-1 forged", goodDm, "hi")).category());
    }

    // --- M1-360: outbound cap decision routed through Utf8 ---

    @Test
    void encodeRejectsOutboundTextExceedingCapByByteLength() {
        // requireWithinCap's boolean decision now goes through
        // Utf8.exceedsByteLength (allocation-free, early-exit); the rejection
        // still names the exact UTF-8 byte count. A 2-byte-per-char string
        // proves the cap is measured in BYTES, not chars: (cap/2 + 1) chars
        // stay under the cap as a char count but exceed it as a byte count.
        int overByChar = SimpleXMessageCodec.MAX_OUTBOUND_TEXT_BYTES / 2 + 1;
        // U+00E9 (é) encodes to 2 UTF-8 bytes; built via codepoint so the
        // source stays pure-ASCII regardless of compiler encoding.
        String twoByteText = Character.toString(0x00E9).repeat(overByChar);
        int expectedBytes = twoByteText.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(expectedBytes > SimpleXMessageCodec.MAX_OUTBOUND_TEXT_BYTES,
                "fixture sanity: must exceed the byte cap");
        MessagingException ex = assertThrows(MessagingException.class,
                () -> SimpleXMessageCodec.encodeSendCommand(
                        "corr-1", new ScopeRef.Dm("contact-abc"), twoByteText));
        assertEquals(FailureCategory.PERMANENT, ex.category(),
                "over-cap outbound text is a permanent failure");
        assertTrue(ex.getMessage().contains(Integer.toString(expectedBytes)),
                "rejection must name the exact UTF-8 byte count: " + ex.getMessage());
    }

    @Test
    void encodeAcceptsOutboundTextAtExactlyCap() {
        // The cap is inclusive: a text whose UTF-8 length is exactly the cap
        // still encodes, since Utf8.exceedsByteLength is strictly greater-than.
        String atCap = "a".repeat(SimpleXMessageCodec.MAX_OUTBOUND_TEXT_BYTES);
        assertDoesNotThrow(() -> SimpleXMessageCodec.encodeSendCommand(
                        "corr-1", new ScopeRef.Dm("contact-abc"), atCap),
                "outbound text at exactly the cap must still encode");
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
                        "type": "direct",
                        "contact": {
                          "contactId": "contact-abc",
                          "displayName": "Peer"
                        }
                      },
                      "chatItem": {
                        "meta": {"itemId": "msg-big"},
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
        // The decode-time cap still drops the message (enforcement point
        // unchanged); it now surfaces as OversizeDropped carrying the scope +
        // sender + adapterMessageId the consumer needs for the §6.3.10 WARN.
        var dropped = assertInstanceOf(SimpleXMessageCodec.OversizeDropped.class, decoded,
                "oversize inbound text must decode as OversizeDropped");
        assertInstanceOf(ScopeRef.Dm.class, dropped.scope(),
                "a direct oversize drop must carry a DM scope for scope_kind");
        assertEquals("contact-abc", dropped.senderContactId(),
                "the oversize drop must carry the sender for the WARN");
        assertEquals("msg-big", dropped.adapterMessageId(),
                "the oversize drop must carry the adapterMessageId for the WARN");
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
                        "type": "direct",
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

    // --- M1-514 (was M1-453): group-mention decode keeps memberId/span independence ---

    @Test
    void groupMentionDecodeKeepsMemberIdsWhenSpanReconstructionSucceeds() {
        // A group newChatItem whose formattedText segments concatenate to the
        // full text exactly: span reconstruction SUCCEEDS, so the decode must
        // yield BOTH the mention memberId (from the top-level mentions{}) AND its
        // span (tagged with that memberId via the segment's memberName). The
        // mention segment "@alice" occupies [0, 6); " hello" is a plain
        // non-mention tail that completes the coverage.
        String frame = """
                {
                  "resp": {
                    "type": "newChatItem",
                    "chatItem": {
                      "chatInfo": {
                        "type": "group",
                        "groupInfo": {
                          "groupId": "group-1",
                          "membership": {"memberId": "bot-member-id"}
                        }
                      },
                      "chatItem": {
                        "itemId": "g-msg-1",
                        "chatDir": {
                          "groupMember": {
                            "memberContactId": "sender-qaddr",
                            "localDisplayName": "Sender"
                          }
                        },
                        "content": {
                          "msgContent": {
                            "type": "text",
                            "text": "@alice hello"
                          }
                        },
                        "mentions": {"alice": {"memberId": "alice-member-id"}},
                        "formattedText": [
                          {"text": "@alice",
                           "format": {"type": "mention", "memberName": "alice"}},
                          {"text": " hello"}
                        ]
                      }
                    }
                  }
                }
                """;
        var candidate = assertInstanceOf(
                SimpleXMessageCodec.GroupCandidate.class,
                SimpleXMessageCodec.decode(frame),
                "a group newChatItem decodes as GroupCandidate");
        assertEquals(List.of("alice-member-id"), candidate.mentionMemberIds(),
                "the mention memberId is collected from mentions{}");
        assertEquals("bot-member-id", candidate.botMemberId(),
                "the bot's own memberId is read from groupInfo.membership");
        assertEquals(1, candidate.mentionSpans().size(),
                "span reconstruction succeeded, so the mention span is present");
        SimpleXMessageCodec.MentionSpan span = candidate.mentionSpans().get(0);
        assertEquals("alice-member-id", span.memberId());
        assertEquals(0, span.start(), "@alice begins at offset 0");
        assertEquals(6, span.length(), "@alice is 6 chars long");
    }

    @Test
    void groupMentionDecodeKeepsMemberIdsWhenSpanReconstructionFails() {
        // Same mention, but the formattedText segments do NOT cover the full
        // text ("@alice hello" vs the longer "@alice hello world"): the coverage
        // guard fails, so the spans are voided. The decode must STILL return the
        // mention memberId — recognition reads the top-level mentions{}, which is
        // independent of formattedText, so it survives a failed span
        // reconstruction (design §6.4.4).
        String frame = """
                {
                  "resp": {
                    "type": "newChatItem",
                    "chatItem": {
                      "chatInfo": {
                        "type": "group",
                        "groupInfo": {
                          "groupId": "group-1",
                          "membership": {"memberId": "bot-member-id"}
                        }
                      },
                      "chatItem": {
                        "itemId": "g-msg-2",
                        "chatDir": {
                          "groupMember": {
                            "memberContactId": "sender-qaddr",
                            "localDisplayName": "Sender"
                          }
                        },
                        "content": {
                          "msgContent": {
                            "type": "text",
                            "text": "@alice hello world"
                          }
                        },
                        "mentions": {"alice": {"memberId": "alice-member-id"}},
                        "formattedText": [
                          {"text": "@alice",
                           "format": {"type": "mention", "memberName": "alice"}},
                          {"text": " hello"}
                        ]
                      }
                    }
                  }
                }
                """;
        var candidate = assertInstanceOf(
                SimpleXMessageCodec.GroupCandidate.class,
                SimpleXMessageCodec.decode(frame),
                "a group newChatItem decodes as GroupCandidate even with a failed span guard");
        assertEquals(List.of("alice-member-id"), candidate.mentionMemberIds(),
                "the mention memberId survives the failed span reconstruction");
        assertTrue(candidate.mentionSpans().isEmpty(),
                "the coverage guard failed, so no spans are surfaced");
    }

    /** Render a Java String as a JSON string literal (with surrounding quotes). */
    private static String jsonStringLiteral(String s) {
        try {
            return MAPPER.writeValueAsString(s);
        } catch (Exception e) {
            throw new AssertionError("jsonStringLiteral", e);
        }
    }

    // --- M1-119: log-hygiene sentinel tests (Finding 4) ---

    @Test
    void malformedFrameExceptionHasFixedMessage() {
        // M1-119 acceptance item 3 (security.md §User content in
        // exceptions): MalformedFrameException thrown from a non-JSON
        // frame must carry only a fixed message — Jackson's
        // getOriginalMessage() interpolates bytes from the offending
        // input, which the M1-103 audit (Finding 4) flagged as a leak.
        String sentinel = "REDTEAM-SENTINEL-XXXXX";
        SimpleXMessageCodec.MalformedFrameException ex = assertThrows(
                SimpleXMessageCodec.MalformedFrameException.class,
                () -> SimpleXMessageCodec.decode("not json " + sentinel));
        assertNotNull(ex.getMessage(), "exception must carry a non-null message");
        assertFalse(ex.getMessage().contains(sentinel),
                "exception message must not interpolate bytes from the offending frame; was: "
                        + ex.getMessage());
        assertEquals("frame is not JSON", ex.getMessage(),
                "fixed-message contract per security.md §User content in exceptions");
    }

    @Test
    void ignoredVariantReasonStringsAreFixed() {
        // M1-119 acceptance item 4: the Ignored variant returned for a
        // non-direct chatType must NOT interpolate the chatType value.
        // The attacker controls chatType through the inbound frame; the
        // Ignored.reason() value flows to the WS-client's DEBUG log
        // (security.md §User content in exceptions).
        String sentinel = "REDTEAM-SENTINEL-XXXXX";
        String frame = """
                {
                  "resp": {
                    "type": "newChatItem",
                    "chatItem": {
                      "chatInfo": {
                        "type": "%s"
                      }
                    }
                  }
                }
                """.formatted(sentinel);
        var decoded = SimpleXMessageCodec.decode(frame);
        var ignored = assertInstanceOf(SimpleXMessageCodec.Ignored.class, decoded);
        assertFalse(ignored.reason().contains(sentinel),
                "Ignored.reason() must not echo attacker-controlled chatType; was: "
                        + ignored.reason());
        assertEquals("newChatItem-non-direct", ignored.reason(),
                "fixed sentinel per security.md §User content in exceptions");
    }

    @Test
    void unknownRespTypeYieldsFixedIgnoredReason() {
        // Top-level resp.type is attacker-influenceable through the inbound
        // frame and Ignored.reason() flows into the WS-client's DEBUG log
        // (security.md §User content in exceptions / §User-content logging
        // at any log level). The default switch branch must drop the
        // attacker-chosen type string and emit only a fixed sentinel —
        // parallel to the chatType non-direct rule covered above.
        String sentinel = "REDTEAM-SENTINEL-XXXXX";
        String frame = """
                {
                  "resp": {
                    "type": "%s"
                  }
                }
                """.formatted(sentinel);
        var decoded = SimpleXMessageCodec.decode(frame);
        var ignored = assertInstanceOf(SimpleXMessageCodec.Ignored.class, decoded);
        assertFalse(ignored.reason().contains(sentinel),
                "Ignored.reason() must not echo attacker-controlled resp.type; was: "
                        + ignored.reason());
        assertEquals("unknown-resp-type", ignored.reason(),
                "fixed sentinel per security.md §User content in exceptions");
    }

    // --- M1-508: plural newChatItems (v6.5.4) inbound decode ---

    // The batched plural async event simplex-chat v6.5.4 delivers for a
    // RECEIVED direct message: resp.type == "newChatItems" (PLURAL), NO corrId,
    // a chatItems ARRAY whose entry is an AChatItem ({chatInfo, chatItem}) with
    // chatInfo.type == "direct" and a received chatItem. This is the real
    // v6.5.4 frame structure (chatItems is an ARRAY, not the hand-rolled
    // singular newChatItem object whose test-vs-reality gap hid the bug that
    // silently discarded 100% of v6.5.4 inbound). Field locations are the live
    // v6.5.4.1 shape (contact.localDisplayName, chatItem.meta.itemId; M1-510).
    // Built inline so no resource file is added (files_scope is the codec + this
    // test only).
    private static final String NEW_CHAT_ITEMS_DIRECT_RECEIVED = """
            {
              "resp": {
                "type": "newChatItems",
                "chatItems": [
                  {
                    "chatInfo": {
                      "type": "direct",
                      "contact": {
                        "contactId": "contact-xyz",
                        "localDisplayName": "Test User"
                      }
                    },
                    "chatItem": {
                      "meta": {"itemId": "msg-77"},
                      "content": {
                        "msgContent": {
                          "type": "text",
                          "text": "Inbound payload"
                        }
                      }
                    }
                  }
                ]
              }
            }
            """;

    @Test
    void decodesNewChatItemsPluralDirectReceivedAsInbound() {
        // Acceptance item 1: the v6.5.4 batched plural received-DM event decodes
        // to an Inbound whose sender().contactId() and ScopeRef.Dm equal the
        // connection contactId, carrying the message body. This is the fix —
        // before M1-508 the plural type routed unconditionally to decodeSendAck
        // and the message was dropped as send-ack-without-chatItemId.
        var decoded = SimpleXMessageCodec.decode(NEW_CHAT_ITEMS_DIRECT_RECEIVED);
        var inbound = assertInstanceOf(SimpleXMessageCodec.Inbound.class, decoded,
                "a plural newChatItems received DM must decode as Inbound, not be dropped");
        assertEquals("contact-xyz", inbound.message().sender().contactId(),
                "identity is the connection contactId (D10)");
        assertEquals("Test User", inbound.message().sender().displayName());
        assertEquals("Inbound payload", inbound.message().text());
        assertEquals(new ScopeRef.Dm("contact-xyz"), inbound.message().scope(),
                "scope is the DM keyed on the connection contactId");
        assertEquals("msg-77", inbound.message().adapterMessageId());
    }

    @Test
    void newChatItemsWithCorrIdStillDecodesAsSendAck() {
        // Acceptance item 2: the SAME plural shape but WITH a corrId is the
        // response to our own /_send, not an inbound message. It must decode as
        // a SendAck (extracting chatItems[0].chatItem.meta.itemId), never as an
        // Inbound — otherwise the bot would treat its own sent message as a
        // received one (self-echo loop). corrId is the discriminator: present →
        // SendAck; absent → inbound.
        String sendResult = """
                {
                  "corrId": "corr-send-1",
                  "resp": {
                    "type": "newChatItems",
                    "chatItems": [
                      {
                        "chatInfo": {
                          "type": "direct",
                          "contact": {"contactId": "contact-xyz", "localDisplayName": "Test User"}
                        },
                        "chatItem": {
                          "meta": {"itemId": "sent-99"},
                          "content": {"msgContent": {"type": "text", "text": "our reply"}}
                        }
                      }
                    ]
                  }
                }
                """;
        var decoded = SimpleXMessageCodec.decode(sendResult);
        var ack = assertInstanceOf(SimpleXMessageCodec.SendAck.class, decoded,
                "a plural newChatItems WITH corrId is our send result — a SendAck, not an Inbound");
        assertEquals("corr-send-1", ack.corrId());
        assertEquals("sent-99", ack.chatItemId(),
                "the chat-item id is read from chatItems[0].chatItem.meta.itemId on the v6.5.4.1 array shape");
    }

    @Test
    void decodesNewChatItemsPluralGroupReceivedAsGroupInbound() {
        // Acceptance item 3: a group received message in the plural shape
        // (chatInfo.type == "group") decodes to a group-scope GroupCandidate,
        // mirroring the existing singular group path — the identity rules are
        // shared (decodeChatItemEntry), so the plural path resolves the sender to
        // the connection-based memberContactId exactly as the singular path does.
        String groupFrame = """
                {
                  "resp": {
                    "type": "newChatItems",
                    "chatItems": [
                      {
                        "chatInfo": {
                          "type": "group",
                          "groupInfo": {
                            "groupId": "group-1",
                            "membership": {"memberId": "bot-member-id"}
                          }
                        },
                        "chatItem": {
                          "meta": {"itemId": "g-msg-1"},
                          "chatDir": {
                            "groupMember": {
                              "memberContactId": "sender-qaddr",
                              "localDisplayName": "Sender"
                            }
                          },
                          "content": {"msgContent": {"type": "text", "text": "group hello"}}
                        }
                      }
                    ]
                  }
                }
                """;
        var candidate = assertInstanceOf(
                SimpleXMessageCodec.GroupCandidate.class,
                SimpleXMessageCodec.decode(groupFrame),
                "a plural newChatItems group message must decode as GroupCandidate");
        assertEquals("group-1", candidate.adapterGroupId());
        assertEquals("sender-qaddr", candidate.senderContactId(),
                "sender is the connection-based memberContactId (D10)");
        assertEquals("group hello", candidate.text());
        assertEquals("g-msg-1", candidate.adapterMessageId());
    }

    @Test
    void newChatItemsPluralDirectAppliesConnectionContactIdNotAdvertisedLink() {
        // Acceptance item 4 (regression, security): the M1-506/D50 rule — a
        // sender's self-asserted profile.contactLink must never influence the
        // resolved identity — holds on the plural path too. Because the plural
        // and singular paths share decodeChatItemEntry, the connection contactId
        // is authoritative here exactly as in the singular regression test.
        String connectionContactId = "Bnv1l0BPLkXjA38n-bWvHQ==";
        String advertisedContactLink =
                "https://simplex.chat/contact#/?v=2-7&smp=smp%3A%2F%2FhQ%40smp.example.com";
        String frame = """
                {
                  "resp": {
                    "type": "newChatItems",
                    "chatItems": [
                      {
                        "chatInfo": {
                          "type": "direct",
                          "contact": {
                            "contactId": %s,
                            "displayName": "Peer",
                            "profile": {"contactLink": %s}
                          }
                        },
                        "chatItem": {
                          "itemId": "msg-1",
                          "content": {"msgContent": {"type": "text", "text": "hi"}}
                        }
                      }
                    ]
                  }
                }
                """.formatted(jsonStringLiteral(connectionContactId),
                        jsonStringLiteral(advertisedContactLink));
        var inbound = assertInstanceOf(SimpleXMessageCodec.Inbound.class,
                SimpleXMessageCodec.decode(frame),
                "a plural direct frame with a profile.contactLink still decodes as Inbound");
        assertEquals(connectionContactId, inbound.message().sender().contactId(),
                "identity is the connection contactId, never the advertised contactLink");
        assertEquals(new ScopeRef.Dm(connectionContactId), inbound.message().scope(),
                "scope carries the connection contactId, never the advertised contactLink");
    }

    @Test
    void newChatItemsPluralAsyncMultiItemDecodesFirstOnly() {
        // First-only (v1) behavior, called out explicitly: decode() returns a
        // single DecodedFrame and its consumer handles one frame per call, so a
        // received async newChatItems batch carrying >1 item decodes only the
        // first. v6.5.4 delivers exactly one received item per async event, so
        // this loses nothing in practice; the test pins the documented choice so
        // a future change is a deliberate one. The second item (a distinct
        // contactId) must NOT surface from this single decode call.
        String twoItems = """
                {
                  "resp": {
                    "type": "newChatItems",
                    "chatItems": [
                      {
                        "chatInfo": {
                          "type": "direct",
                          "contact": {"contactId": "first-contact", "displayName": "First"}
                        },
                        "chatItem": {
                          "itemId": "msg-a",
                          "content": {"msgContent": {"type": "text", "text": "first"}}
                        }
                      },
                      {
                        "chatInfo": {
                          "type": "direct",
                          "contact": {"contactId": "second-contact", "displayName": "Second"}
                        },
                        "chatItem": {
                          "itemId": "msg-b",
                          "content": {"msgContent": {"type": "text", "text": "second"}}
                        }
                      }
                    ]
                  }
                }
                """;
        var inbound = assertInstanceOf(SimpleXMessageCodec.Inbound.class,
                SimpleXMessageCodec.decode(twoItems),
                "the first item of a multi-item plural batch decodes as Inbound");
        assertEquals("first-contact", inbound.message().sender().contactId(),
                "first-only: the first array entry is the one decoded");
        assertEquals("first", inbound.message().text());
    }

    @Test
    void newChatItemsAsyncWithoutItemsArrayIsIgnored() {
        // A no-corrId plural event lacking a usable chatItems ARRAY is dropped
        // with a fixed sentinel (no attacker bytes in the reason — it flows to
        // the WS-client DEBUG log, security.md §User content in exceptions).
        var empty = SimpleXMessageCodec.decode(
                "{\"resp\": {\"type\": \"newChatItems\", \"chatItems\": []}}");
        var ignoredEmpty = assertInstanceOf(SimpleXMessageCodec.Ignored.class, empty,
                "an empty chatItems array on a received plural event is Ignored");
        assertEquals("newChatItems-without-items", ignoredEmpty.reason());

        var missing = SimpleXMessageCodec.decode(
                "{\"resp\": {\"type\": \"newChatItems\"}}");
        var ignoredMissing = assertInstanceOf(SimpleXMessageCodec.Ignored.class, missing,
                "a missing chatItems on a received plural event is Ignored");
        assertEquals("newChatItems-without-items", ignoredMissing.reason());
    }

    @Test
    void unrecognizedErrorEnvelopeYieldsFixedDetail() {
        // chatCmdError / chatItemUpdateError frames where no recognized
        // tag (chatError / errorType / error) is found previously fell
        // back to resp.toString() — the entire envelope, which simplex-chat
        // may populate with bytes echoed back from the offending inbound
        // (user message body fragments). That detail flowed into both
        // the WS-client DEBUG log at failPending() and the
        // MessagingException message text returned to the adapter caller
        // (security.md §User-content logging at any log level;
        // §User content in exceptions). The fix replaces the envelope dump
        // with a fixed sentinel so the leak channel is closed structurally.
        String sentinel = "REDTEAM-SENTINEL-XXXXX";
        String frame = """
                {
                  "corrId": "corr-x",
                  "resp": {
                    "type": "chatCmdError",
                    "echoedBody": "%s"
                  }
                }
                """.formatted(sentinel);
        var decoded = assertInstanceOf(
                SimpleXMessageCodec.CommandError.class,
                SimpleXMessageCodec.decode(frame));
        assertFalse(decoded.detail().contains(sentinel),
                "CommandError.detail() must not carry envelope bytes; was: "
                        + decoded.detail());
        assertEquals("unrecognized-error-envelope", decoded.detail(),
                "fixed sentinel per security.md §User content in exceptions");
        // FailureCategory still classifies the missing-tag case as PERMANENT
        // — the spec rule (unknown tags default to PERMANENT) is unchanged.
        assertEquals(FailureCategory.PERMANENT, decoded.category());
        assertEquals("corr-x", decoded.corrId());
    }

    // --- M1-510: live v6.5.4.1 wire-format alignment (real captured frames) ---

    // Direct inbound DM "/help" captured verbatim from a live v6.5.4.1
    // deployment (a throwaway loopback ws://127.0.0.1:5225 probe, M1-510). The
    // decoded fields are chatInfo.type, contact.localDisplayName,
    // chatItem.meta.itemId, content.msgContent.text; contactId is the numeric
    // connection id (5). contact.profile.displayName ("admin") is present but
    // MUST NOT be read — the surfaced display name is the local handle "admin_1".
    private static final String REAL_V654_DIRECT_INBOUND = """
            {"resp":{"type":"newChatItems","chatItems":[{
              "chatInfo":{"type":"direct","contact":{"contactId":5,"localDisplayName":"admin_1",
                          "profile":{"displayName":"admin"}}},
              "chatItem":{"chatDir":{"type":"directRcv"},
                          "meta":{"itemId":20,"itemText":"/help"},
                          "content":{"type":"rcvMsgContent","msgContent":{"type":"text","text":"/help"}}}}]}}
            """;

    @Test
    void decodesDirectInboundUsingRealV654Frame() {
        // Acceptance items 1-3: the live v6.5.4.1 direct inbound — no corrId,
        // chatInfo.type=="direct" — decodes to an Inbound instead of being
        // dropped as Ignored("newChatItem-without-chatType"). The id is the
        // numeric connection contactId (D10), the display name is the local
        // handle (contact.localDisplayName, NOT the self-asserted
        // profile.displayName), and the adapterMessageId is chatItem.meta.itemId.
        var inbound = assertInstanceOf(SimpleXMessageCodec.Inbound.class,
                SimpleXMessageCodec.decode(REAL_V654_DIRECT_INBOUND),
                "the real v6.5.4.1 direct inbound must decode as Inbound, not be dropped");
        assertEquals("5", inbound.message().sender().contactId(),
                "identity is the numeric connection contactId (D10)");
        assertEquals("admin_1", inbound.message().sender().displayName(),
                "display name is contact.localDisplayName, never the self-asserted profile.displayName");
        assertEquals("/help", inbound.message().text());
        assertEquals("20", inbound.message().adapterMessageId(),
                "adapterMessageId is chatItem.meta.itemId");
        assertEquals(new ScopeRef.Dm("5"), inbound.message().scope());
    }

    @Test
    void decodesSendAckUsingRealV654Frame() {
        // Acceptance item 5: the live v6.5.4.1 send result — resp.type ==
        // "newChatItems" WITH a corrId — decodes to a SendAck carrying
        // chatItems[0].chatItem.meta.itemId, not
        // Ignored("send-ack-without-chatItemId").
        String frame = """
                {"corrId":"probe","resp":{"type":"newChatItems","chatItems":[{
                  "chatInfo":{"type":"direct","contact":{"contactId":5}},
                  "chatItem":{"chatDir":{"type":"directSnd"},
                              "meta":{"itemId":21,"itemStatus":{"type":"sndNew"}},
                              "content":{"type":"sndMsgContent","msgContent":{"type":"text","text":"reply"}}}}]}}
                """;
        var ack = assertInstanceOf(SimpleXMessageCodec.SendAck.class,
                SimpleXMessageCodec.decode(frame),
                "a v6.5.4.1 send result with corrId decodes as SendAck");
        assertEquals("probe", ack.corrId());
        assertEquals("21", ack.chatItemId(),
                "chat-item id is chatItems[0].chatItem.meta.itemId");
    }

    @Test
    void decodesCommandErrorUsingRealV654Frame() {
        // Acceptance item 6: the live commandError shape — the tag is the .type
        // of the nested errorType object. The sibling "message" ("Failed
        // reading: empty") is echoed-prose-shaped and MUST NOT reach
        // CommandError.detail() (security.md §User content in exceptions).
        String frame = """
                {"resp":{"type":"chatCmdError","chatError":{"type":"error","errorType":{"type":"commandError","message":"Failed reading: empty"}}}}
                """;
        var error = assertInstanceOf(SimpleXMessageCodec.CommandError.class,
                SimpleXMessageCodec.decode(frame));
        assertEquals("commandError", error.detail(),
                "the tag is chatError.errorType.type");
        assertFalse(error.detail().contains("Failed reading"),
                "the free-form errorType.message must not leak into detail; was: " + error.detail());
        assertEquals(FailureCategory.PERMANENT, error.category(),
                "commandError is not a transient tag — fail-closed PERMANENT");
    }

    @Test
    void decodesStoreErrorUsingRealV654Frame() {
        // Acceptance item 6: the second live error shape — the tag is the .type
        // of the nested storeError object.
        String frame = """
                {"resp":{"type":"chatCmdError","chatError":{"type":"errorStore","storeError":{"type":"groupAlreadyJoined"}}}}
                """;
        var error = assertInstanceOf(SimpleXMessageCodec.CommandError.class,
                SimpleXMessageCodec.decode(frame));
        assertEquals("groupAlreadyJoined", error.detail(),
                "the tag is chatError.storeError.type");
        assertEquals(FailureCategory.PERMANENT, error.category());
    }

    @Test
    void encodeSendCommandEmitsMsgContentArray() throws Exception {
        // Acceptance item 4: /_send must emit the message content as a JSON
        // ARRAY [{"msgContent":…}]. v6.5.4.1 rejects the single-object form with
        // commandError "Failed reading: empty"; the array form is accepted.
        String frame = SimpleXMessageCodec.encodeSendCommand(
                "corr-1", new ScopeRef.Dm("5"), "Hello, world");
        String cmd = MAPPER.readTree(frame).get("cmd").asText();
        assertTrue(cmd.startsWith("/_send @5 json "),
                "the command targets the DM and carries a json payload: " + cmd);
        JsonNode payload = MAPPER.readTree(jsonPayload(cmd));
        assertTrue(payload.isArray(), "the /_send payload is a JSON array: " + cmd);
        assertEquals(1, payload.size(),
                "v1 sends one composed message per command");
        assertEquals("Hello, world",
                payload.get(0).get("msgContent").get("text").asText(),
                "the single array element wraps the msgContent");
    }

    @Test
    void encodeUpdateCommandEmitsSingleMsgContentObject() throws Exception {
        // Acceptance item 7: /_update item edits exactly ONE existing item, so
        // its content is a single UpdatedMessage OBJECT — msgContent plus the
        // required empty mentions map — not the /_send array (an edit targets
        // one item; a send composes several). Live-proven against the bundled
        // v7.0.0 and a v6.5.4.1 control (M1-839 ticket record).
        String frame = SimpleXMessageCodec.encodeUpdateCommand(
                "corr-2", "chat-item-9", new ScopeRef.Dm("5"), "Partial");
        String cmd = MAPPER.readTree(frame).get("cmd").asText();
        JsonNode payload = MAPPER.readTree(jsonPayload(cmd));
        assertTrue(payload.isObject(),
                "the /_update payload is a single JSON object, not an array: " + cmd);
        assertEquals("Partial", payload.get("msgContent").get("text").asText());
    }

    @Test
    void decodesReceivedGroupInvitationFromRealV654Frame() {
        // REAL captured receivedGroupInvitation async event from live
        // simplex-chat v6.5.4.1 (M1-515): the bot was invited to group
        // "invite test" (groupId 2) by contactId 5. The full wire frame is
        // embedded verbatim — group id at resp.groupInfo.groupId, inviter at
        // resp.groupInfo.membership.invitedBy.byContactId.
        String frame = """
                {"resp":{"type":"receivedGroupInvitation","user":{"userId":1,"agentUserId":"1","userContactId":1,"localDisplayName":"Admin-Reno","profile":{"profileId":1,"displayName":"Admin-Reno","fullName":"","preferences":{"files":{"allow":"no"}},"peerType":"bot","localAlias":""},"fullPreferences":{"timedMessages":{"allow":"yes"},"fullDelete":{"allow":"no"},"reactions":{"allow":"yes"},"voice":{"allow":"yes"},"files":{"allow":"no"},"calls":{"allow":"yes"},"sessions":{"allow":"no"},"commands":[]},"activeUser":true,"activeOrder":1,"showNtfs":true,"sendRcptsContacts":true,"sendRcptsSmallGroups":true,"autoAcceptMemberContacts":false,"userChatRelay":false},"groupInfo":{"groupId":2,"useRelays":false,"localDisplayName":"invite test","groupProfile":{"displayName":"invite test","fullName":"","groupPreferences":{"history":{"enable":"on"}}},"localAlias":"","fullGroupPreferences":{"timedMessages":{"enable":"off","ttl":86400},"directMessages":{"enable":"off"},"fullDelete":{"enable":"off"},"reactions":{"enable":"on"},"voice":{"enable":"on"},"files":{"enable":"on"},"simplexLinks":{"enable":"on"},"reports":{"enable":"on"},"history":{"enable":"on"},"support":{"enable":"on"},"sessions":{"enable":"off"},"comments":{"enable":"off"},"commands":[]},"membership":{"groupMemberId":4,"groupId":2,"indexInGroup":1,"memberId":"RUMxTE5IMWdmNEtDV2NGWg==","memberRole":"member","memberCategory":"user","memberStatus":"invited","memberSettings":{"showMessages":true},"blockedByAdmin":false,"invitedBy":{"type":"contact","byContactId":5},"invitedByGroupMemberId":3,"localDisplayName":"Admin-Reno","memberProfile":{"profileId":1,"displayName":"Admin-Reno","fullName":"","preferences":{"files":{"allow":"no"}},"peerType":"bot","localAlias":""},"memberContactId":1,"memberContactProfileId":1,"memberChatVRange":{"minVersion":1,"maxVersion":17},"createdAt":"2026-06-29T16:36:42.342814838Z","updatedAt":"2026-06-29T16:36:42.342814838Z"},"chatSettings":{"enableNtfs":"all","favorite":false},"createdAt":"2026-06-29T16:36:42.342814838Z","updatedAt":"2026-06-29T16:36:42.342814838Z","chatTs":"2026-06-29T16:36:42.342814838Z","userMemberProfileSentAt":"2026-06-29T16:36:42.342814838Z","chatTags":[],"groupSummary":{"currentMembers":2},"membersRequireAttention":0},"contact":{"contactId":5,"localDisplayName":"admin_1","profile":{"profileId":5,"displayName":"admin","fullName":"","contactLink":"https://smp17.simplex.im/a#yyPGSnLVqurNqAPSIor-H1FX-vGxh29fUctWzM-kX7g","preferences":{"timedMessages":{"allow":"yes"},"fullDelete":{"allow":"no"},"reactions":{"allow":"yes"},"voice":{"allow":"yes"},"files":{"allow":"always"},"calls":{"allow":"yes"},"sessions":{"allow":"no"},"commands":[]},"localAlias":""},"activeConn":{"connId":3,"agentConnId":"aE5HajNVS2hyOTJ6ZEFMSQ==","connChatVersion":17,"peerChatVRange":{"minVersion":1,"maxVersion":17},"connLevel":0,"viaUserContactLink":1,"viaGroupLink":false,"connType":"contact","connStatus":{"type":"ready"},"contactConnInitiated":false,"localAlias":"","entityId":5,"pqSupport":true,"pqEncryption":true,"pqSndEnabled":true,"pqRcvEnabled":true,"authErrCounter":0,"quotaErrCounter":0,"createdAt":"2026-06-28T11:21:20.27497632Z"},"contactUsed":true,"contactStatus":"active","chatSettings":{"enableNtfs":"all","favorite":false},"userPreferences":{},"mergedPreferences":{"timedMessages":{"enabled":{"forUser":true,"forContact":true},"userPreference":{"type":"user","preference":{"allow":"yes"}},"contactPreference":{"allow":"yes"}},"fullDelete":{"enabled":{"forUser":false,"forContact":false},"userPreference":{"type":"user","preference":{"allow":"no"}},"contactPreference":{"allow":"no"}},"reactions":{"enabled":{"forUser":true,"forContact":true},"userPreference":{"type":"user","preference":{"allow":"yes"}},"contactPreference":{"allow":"yes"}},"voice":{"enabled":{"forUser":true,"forContact":true},"userPreference":{"type":"user","preference":{"allow":"yes"}},"contactPreference":{"allow":"yes"}},"files":{"enabled":{"forUser":true,"forContact":false},"userPreference":{"type":"user","preference":{"allow":"no"}},"contactPreference":{"allow":"always"}},"calls":{"enabled":{"forUser":true,"forContact":true},"userPreference":{"type":"user","preference":{"allow":"yes"}},"contactPreference":{"allow":"yes"}},"sessions":{"enabled":{"forUser":false,"forContact":false},"userPreference":{"type":"user","preference":{"allow":"no"}},"contactPreference":{"allow":"no"}},"commands":[]},"createdAt":"2026-06-28T11:21:20.079045222Z","updatedAt":"2026-06-28T11:21:20.079045222Z","chatTs":"2026-06-28T16:46:44.758895679Z","contactRequestId":2,"contactGrpInvSent":false,"chatTags":[],"chatDeleted":false},"fromMemberRole":"owner","memberRole":"member"}}
                """;
        var invitation = assertInstanceOf(
                SimpleXMessageCodec.ReceivedGroupInvitation.class,
                SimpleXMessageCodec.decode(frame),
                "a receivedGroupInvitation async event decodes to ReceivedGroupInvitation");
        assertEquals("2", invitation.adapterGroupId(),
                "adapterGroupId comes from resp.groupInfo.groupId");
        assertEquals("5", invitation.inviterContactId(),
                "inviterContactId comes from membership.invitedBy.byContactId");
    }

    @Test
    void encodeJoinGroupCommandEmitsJoinByGroupId() throws Exception {
        // Live-confirmed accept mechanism (M1-515): /_join #<groupId>.
        String frame = SimpleXMessageCodec.encodeJoinGroupCommand("corr-join", "2");
        JsonNode root = MAPPER.readTree(frame);
        assertEquals("corr-join", root.get("corrId").asText());
        assertEquals("/_join #2", root.get("cmd").asText(),
                "the join command targets the group by numeric id");
    }

    @Test
    void encodeJoinGroupCommandRejectsAdapterGroupIdWithCommandInjectionChars() {
        // The refuse-leg of the join encode path. adapterGroupId is
        // concatenated straight into the verb ("/_join #" + id), so the
        // encode-time validator is the only thing standing between a forged
        // id and a second command on the wire. The happy-path test above
        // exercises the accept leg only: with
        // requireValidQueueAddressId(adapterGroupId, …) deleted it still
        // passes, which is how the 2026-07-27 mutation sweep found this call
        // site unpinned while its three siblings were killed (M1-712).
        // Inputs mirror encodeRejectsContactIdWithCommandInjectionChars:
        // newline-plus-forged-verb, space-separated forged verb, and the
        // empty id the validator rejects on length alone.
        assertEquals(FailureCategory.PERMANENT, assertThrows(MessagingException.class,
                () -> SimpleXMessageCodec.encodeJoinGroupCommand(
                        "corr-join", "group\n/_set_contact_typing @v on")).category());
        assertEquals(FailureCategory.PERMANENT, assertThrows(MessagingException.class,
                () -> SimpleXMessageCodec.encodeJoinGroupCommand(
                        "corr-join", "2 /_send @victim json {}")).category());
        assertEquals(FailureCategory.PERMANENT, assertThrows(MessagingException.class,
                () -> SimpleXMessageCodec.encodeJoinGroupCommand("corr-join", "")).category());
    }

    /** The JSON content of a SimpleX command string — everything after " json ". */
    private static String jsonPayload(String cmd) {
        int marker = cmd.indexOf(" json ");
        assertTrue(marker >= 0, "command carries a json payload: " + cmd);
        return cmd.substring(marker + " json ".length());
    }
}
