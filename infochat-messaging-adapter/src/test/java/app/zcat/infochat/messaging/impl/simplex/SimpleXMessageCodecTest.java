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
                        "chatType": "direct",
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

    // --- M1-453: single-pass group-mention decode preserves address/span independence ---

    @Test
    void groupMentionDecodeKeepsAddressesWhenSpanReconstructionSucceeds() {
        // A group newChatItem whose formattedText segments concatenate to the
        // full text exactly: span reconstruction SUCCEEDS, so the single-pass
        // decode must yield BOTH the mention queue address AND its span. The
        // mention segment "@alice" occupies [0, 6); " hello" is a plain
        // non-mention tail that completes the coverage.
        String frame = """
                {
                  "resp": {
                    "type": "newChatItem",
                    "chatItem": {
                      "chatInfo": {
                        "chatType": "group",
                        "groupInfo": {"groupId": "group-1"}
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
                        "formattedText": [
                          {"text": "@alice",
                           "format": {"type": "mention", "memberRef": "alice-qaddr"}},
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
        assertEquals(List.of("alice-qaddr"), candidate.mentionQueueAddresses(),
                "the mention queue address is collected");
        assertEquals(1, candidate.mentionSpans().size(),
                "span reconstruction succeeded, so the mention span is present");
        SimpleXMessageCodec.MentionSpan span = candidate.mentionSpans().get(0);
        assertEquals("alice-qaddr", span.queueAddress());
        assertEquals(0, span.start(), "@alice begins at offset 0");
        assertEquals(6, span.length(), "@alice is 6 chars long");
    }

    @Test
    void groupMentionDecodeKeepsAddressesWhenSpanReconstructionFails() {
        // Same mention element, but the formattedText segments do NOT cover the
        // full text ("@alice hello" vs the longer "@alice hello world"): the
        // coverage guard fails, so the spans are voided. The single-pass merge
        // must STILL return the mention queue address — addresses and spans
        // have independent fates (the address-survives-failed-span invariant,
        // design §6.4.4). A pre-merge regression would have coupled the address
        // list to the span guard and dropped the address here.
        String frame = """
                {
                  "resp": {
                    "type": "newChatItem",
                    "chatItem": {
                      "chatInfo": {
                        "chatType": "group",
                        "groupInfo": {"groupId": "group-1"}
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
                        "formattedText": [
                          {"text": "@alice",
                           "format": {"type": "mention", "memberRef": "alice-qaddr"}},
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
        assertEquals(List.of("alice-qaddr"), candidate.mentionQueueAddresses(),
                "the mention queue address survives the failed span reconstruction");
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
                        "chatType": "%s"
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
}
