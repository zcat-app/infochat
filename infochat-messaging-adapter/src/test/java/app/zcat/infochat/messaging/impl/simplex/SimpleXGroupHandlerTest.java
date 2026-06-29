package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Behavior tests for {@link SimpleXGroupHandler}: mention recognition,
 * group-scope delivery, and the codec / mention-parser handoff. Frames
 * are decoded through the real {@link SimpleXMessageCodec} (higher
 * fidelity than hand-built {@code GroupCandidate} records — the
 * codec's group-frame parser is itself security_relevant) and the
 * resulting candidate is fed into the handler with a capturing
 * inbound handler.
 *
 * <p>Recognition is by the per-group {@code memberId} (D51): a bot @mention is
 * a {@code mentions{}} entry whose {@code memberId} byte-equals the bot's own
 * {@code chatInfo.groupInfo.membership.memberId}. The bot's memberId
 * ({@link #BOT_MEMBER_ID}) is baked into every group frame's
 * {@code groupInfo.membership}; the bot's display name in the group is
 * {@link #BOT_DISPLAY}.</p>
 */
class SimpleXGroupHandlerTest {

    // Bot's own per-group memberId (the recognition anchor) and its display
    // name in the group. Real simplex memberIds are base64 with padding.
    private static final String BOT_MEMBER_ID = "WE1sRTBSZlVvMS9WYXdFcQ==";
    private static final String BOT_DISPLAY = "bot";
    private static final String ALICE_MEMBER_ID = "QWxpY2VNZW1iZXIxMjM0NTY=";
    private static final String MALLORY_MEMBER_ID = "TWFsbG9yeU1lbWJlcjEyMzQ=";

    private final List<InboundMessage> delivered = new ArrayList<>();
    private final SimpleXGroupHandler handler = new SimpleXGroupHandler(delivered::add);

    /**
     * The canonical scenario: a peer mentions the bot (a {@code mentions{}}
     * entry whose memberId is the bot's own), and the handler delivers the
     * resulting {@link InboundMessage} with the right sender, body, group
     * scope, and adapter message id.
     */
    @Test
    void mentionByMemberId_delivered() {
        var candidate = decodeGroupFrame(groupFrame(
                "group-7",
                "alice-queue-addr",
                "Alice",
                "hi @bot",
                mentionsObject(BOT_DISPLAY, BOT_MEMBER_ID),
                nonReconstructingFt(BOT_DISPLAY),
                "msg-101"));

        handler.onGroupCandidate(candidate);

        assertEquals(1, delivered.size(), "mention-anchored group msg is delivered");
        InboundMessage msg = delivered.get(0);
        assertEquals("alice-queue-addr", msg.sender().contactId());
        assertEquals("Alice", msg.sender().displayName());
        assertEquals("hi @bot", msg.text());
        assertEquals(new ScopeRef.Group("group-7"), msg.scope());
        assertEquals("msg-101", msg.adapterMessageId());
    }

    /**
     * Trust anchor enforcement: an impersonator whose displayName matches the
     * bot but whose mention points at the impersonator's own memberId (not the
     * bot's) MUST NOT trigger delivery. This is the attack the memberId
     * byte-equality rule defends against — display-name matching is never
     * sufficient.
     */
    @Test
    void mentionByDisplayName_ignored() {
        var candidate = decodeGroupFrame(groupFrame(
                "group-7",
                "mallory-queue-addr",
                "InfoChatBot",
                "@InfoChatBot help",
                mentionsObject("InfoChatBot", MALLORY_MEMBER_ID),
                nonReconstructingFt("InfoChatBot"),
                "msg-spoof"));

        handler.onGroupCandidate(candidate);

        assertEquals(0, delivered.size(),
                "display-name match without the bot's memberId MUST NOT deliver");
    }

    /** Group messages with no mentions at all are silently dropped. */
    @Test
    void noMention_ignored() {
        var candidate = decodeGroupFrame(groupFrame(
                "group-7",
                "alice-queue-addr",
                "Alice",
                "general chatter, not addressed to bot",
                mentionsObject(),
                "[]",
                "msg-quiet"));

        handler.onGroupCandidate(candidate);

        assertEquals(0, delivered.size(),
                "no mention of the bot → silent drop, no log spam");
    }

    /**
     * Reply-to-bot is NOT delivered (D51). simplex sets {@code meta.userMention}
     * on a quote-reply to a bot message even when it carries no @mention; the
     * memberId rule deliberately ignores that — there is no {@code mentions{}}
     * entry for the bot, so the message is dropped. Recognising on
     * {@code userMention} would (incorrectly) deliver it.
     */
    @Test
    void replyToBotWithoutMention_ignored() {
        // userMention:true present on meta, but mentions{} is empty (a reply,
        // not an @mention). The codec does not read userMention.
        String frame = """
                {
                  "resp": {
                    "type": "newChatItem",
                    "chatItem": {
                      "chatInfo": {
                        "type": "group",
                        "groupInfo": {
                          "groupId": "group-7",
                          "membership": {"memberId": "%s"}
                        }
                      },
                      "chatItem": {
                        "meta": {"itemId": "msg-reply", "userMention": true},
                        "chatDir": {
                          "groupMember": {
                            "memberContactId": "alice-queue-addr",
                            "localDisplayName": "Alice"
                          }
                        },
                        "content": {"msgContent": {"type": "text", "text": "thanks!"}},
                        "mentions": {},
                        "formattedText": [{"text": "thanks!"}]
                      }
                    }
                  }
                }
                """.formatted(BOT_MEMBER_ID);

        handler.onGroupCandidate(decodeGroupFrame(frame));

        assertEquals(0, delivered.size(),
                "a reply-to-bot with no mention payload is NOT delivered (userMention ignored)");
    }

    /**
     * DM frames are decoded by the codec into a different variant
     * ({@link SimpleXMessageCodec.Inbound}, not
     * {@link SimpleXMessageCodec.GroupCandidate}) — confirming that
     * the group handler is never invoked for DMs and the DM scope
     * stays {@link ScopeRef.Dm} regardless of any mention metadata.
     */
    @Test
    void dmMessage_deliveredAsDmScope() {
        String dmFrame = """
                {
                  "resp": {
                    "type": "newChatItem",
                    "chatItem": {
                      "chatInfo": {
                        "type": "direct",
                        "contact": {
                          "contactId": "alice-queue-addr",
                          "localDisplayName": "Alice"
                        }
                      },
                      "chatItem": {
                        "meta": {"itemId": "dm-1"},
                        "content": {
                          "msgContent": {
                            "type": "text",
                            "text": "DM body"
                          }
                        }
                      }
                    }
                  }
                }
                """;
        var decoded = SimpleXMessageCodec.decode(dmFrame);
        var inbound = assertInstanceOf(SimpleXMessageCodec.Inbound.class, decoded,
                "DM newChatItem decodes as Inbound, NOT GroupCandidate");
        assertInstanceOf(ScopeRef.Dm.class, inbound.message().scope(),
                "DM scope is preserved through decode (no group routing)");
        assertEquals("alice-queue-addr", inbound.message().sender().contactId());
        assertEquals("DM body", inbound.message().text());
    }

    /**
     * Two group messages from the same SimpleX group surface the same
     * {@code adapterGroupId} on the delivered {@link InboundMessage}
     * — the codec's group-id extraction is stable across messages, so
     * Provider's {@code (adapter, upstream_group_id)} join key is
     * sound.
     */
    @Test
    void groupIdIsStableAcrossMessages() {
        var first = decodeGroupFrame(groupFrame(
                "group-42",
                "alice-queue-addr", "Alice",
                "first @bot",
                mentionsObject(BOT_DISPLAY, BOT_MEMBER_ID),
                nonReconstructingFt(BOT_DISPLAY),
                "msg-A"));
        var second = decodeGroupFrame(groupFrame(
                "group-42",
                "bob-queue-addr", "Bob",
                "second @bot",
                mentionsObject(BOT_DISPLAY, BOT_MEMBER_ID),
                nonReconstructingFt(BOT_DISPLAY),
                "msg-B"));

        handler.onGroupCandidate(first);
        handler.onGroupCandidate(second);

        assertEquals(2, delivered.size(), "both mentioned messages delivered");
        assertEquals(new ScopeRef.Group("group-42"), delivered.get(0).scope());
        assertEquals(new ScopeRef.Group("group-42"), delivered.get(1).scope(),
                "second message from same SimpleX group surfaces the same adapterGroupId");
    }

    /**
     * v1 declaration: the simplex-chat WebSocket bot API surface
     * inspected during M1-103 does not expose a native
     * {@code user_left_group} / {@code member_removed} event, so
     * {@link SimpleXAdapter#capabilities()} declares
     * {@code supportsMembershipEvents = false}. Provider falls back to
     * permanent-delivery-failure cleanup per
     * {@code docs/spec/messaging.md} §Failure handling. If a future
     * simplex-chat release exposes such a signal, this assertion is
     * the forcing function for re-evaluation: the flag must flip to
     * true AND the handler must grow a parallel membership-dispatch
     * path before the test is updated.
     */
    @Test
    void supportsMembershipEventsFalseWhenNoNativeSignal() {
        CapabilityFlags caps = new SimpleXAdapter().capabilities();
        assertFalse(caps.supportsMembershipEvents(),
                "SimpleX has no native left-group signal in v1");
    }

    /**
     * D10 trust-boundary regression: a group frame whose
     * {@code groupMember} carries only the per-group {@code memberId}
     * counter — without a {@code memberContactId} (the
     * cryptographically-anchored account id) — MUST be dropped at
     * decode time rather than surfaced as a {@link
     * SimpleXMessageCodec.GroupCandidate} whose senderContactId is
     * the non-cryptographic counter. Delivering such a frame would
     * (a) let a globally-banned user evade Provider's ban check and
     * (b) collide pre-contact members across distinct groups. The
     * frame carries a valid {@code groupInfo.membership} so the drop is
     * attributed to the missing sender, not the missing membership.
     */
    @Test
    void memberIdOnlyGroupFrame_dropped() {
        String frame = """
                {
                  "resp": {
                    "type": "newChatItem",
                    "chatItem": {
                      "chatInfo": {
                        "type": "group",
                        "groupInfo": {
                          "groupId": "group-7",
                          "membership": {"memberId": "%s"}
                        }
                      },
                      "chatItem": {
                        "meta": {"itemId": "msg-precontact"},
                        "chatDir": {
                          "groupMember": {
                            "memberId": "m-1",
                            "localDisplayName": "Charlie"
                          }
                        },
                        "content": {
                          "msgContent": {
                            "type": "text",
                            "text": "%s"
                          }
                        },
                        "mentions": %s,
                        "formattedText": %s
                      }
                    }
                  }
                }
                """.formatted(
                BOT_MEMBER_ID,
                jsonEscape("hello @bot"),
                mentionsObject(BOT_DISPLAY, BOT_MEMBER_ID),
                nonReconstructingFt(BOT_DISPLAY));

        var decoded = SimpleXMessageCodec.decode(frame);
        var ignored = assertInstanceOf(SimpleXMessageCodec.Ignored.class, decoded,
                "frame missing memberContactId MUST decode as Ignored, not GroupCandidate");
        assertEquals("newChatItem-group-without-sender", ignored.reason(),
                "rejection reason names the missing-sender path");
        assertEquals(0, delivered.size(),
                "handler is never invoked when decode drops the frame");
    }

    /**
     * Spec promise (docs/spec/messaging.md §Required SPI surface): "the
     * mention is stripped before delivery". A realistic frame whose
     * formattedText decomposes the message text — the mention segment
     * "@bot" followed by the rest — delivers with the mention span
     * removed and surrounding whitespace normalized.
     */
    @Test
    void mentionSpanStrippedBeforeDelivery() {
        var candidate = decodeGroupFrame(groupFrame(
                "group-7",
                "alice-queue-addr",
                "Alice",
                "@bot summarise this",
                mentionsObject(BOT_DISPLAY, BOT_MEMBER_ID),
                decomposingBotMention("@bot", " summarise this"),
                "msg-strip-1"));

        handler.onGroupCandidate(candidate);

        assertEquals(1, delivered.size());
        assertEquals("summarise this", delivered.get(0).text(),
                "bot mention span must be stripped before delivery");
    }

    @Test
    void mentionSpanStripIsAnchoredToProtocolEntry_plainTextBotNameKept() {
        // The body contains the bot's name as plain text after the real
        // mention segment. Only the protocol mention span may be removed
        // — a display-name text search would also eat the trailing "bot".
        var candidate = decodeGroupFrame(groupFrame(
                "group-7",
                "alice-queue-addr",
                "Alice",
                "@bot say hello to bot",
                mentionsObject(BOT_DISPLAY, BOT_MEMBER_ID),
                decomposingBotMention("@bot", " say hello to bot"),
                "msg-strip-2"));

        handler.onGroupCandidate(candidate);

        assertEquals(1, delivered.size());
        assertEquals("say hello to bot", delivered.get(0).text(),
                "only the actual mention span is removed; plain-text 'bot' stays");
    }

    @Test
    void groupSlashCommandParseableAfterStrip() {
        var candidate = decodeGroupFrame(groupFrame(
                "group-7",
                "alice-queue-addr",
                "Alice",
                "@bot /summary",
                mentionsObject(BOT_DISPLAY, BOT_MEMBER_ID),
                decomposingBotMention("@bot", " /summary"),
                "msg-strip-3"));

        handler.onGroupCandidate(candidate);

        assertEquals(1, delivered.size());
        String text = delivered.get(0).text();
        assertTrue(text.startsWith("/"),
                "delivered text must start with the slash so the command parser sees it");
        assertEquals("/summary", text);
    }

    /**
     * Multi-mention precision (D51): when the bot is co-mentioned alongside
     * another member, ONLY the bot's span is stripped — the other member's
     * mention survives in the delivered text.
     */
    @Test
    void coMentionOfOtherMemberNotStripped() {
        String formattedText = """
                [{"text":"@bot","format":{"type":"mention","memberName":"bot"}},\
                {"text":" "},\
                {"text":"@Alice","format":{"type":"mention","memberName":"Alice"}},\
                {"text":" hi"}]""";
        var candidate = decodeGroupFrame(groupFrame(
                "group-7",
                "carol-queue-addr",
                "Carol",
                "@bot @Alice hi",
                mentionsObject(BOT_DISPLAY, BOT_MEMBER_ID, "Alice", ALICE_MEMBER_ID),
                formattedText,
                "msg-comention"));

        handler.onGroupCandidate(candidate);

        assertEquals(1, delivered.size());
        assertEquals("@Alice hi", delivered.get(0).text(),
                "only the bot's mention is stripped; the co-mention of Alice stays");
    }

    /**
     * Reconstruction guard: when the formattedText segments do NOT
     * reconstruct the message text (the mention segment "@m" never
     * appears in the body), no protocol span can be located inside the
     * text — recognition still fires (it reads {@code mentions{}}, not
     * the spans) but the text is delivered unstripped rather than
     * guessed at by display-name search.
     */
    @Test
    void nonReconstructingFormattedText_deliveredUnstripped() {
        var candidate = decodeGroupFrame(groupFrame(
                "group-7",
                "alice-queue-addr",
                "Alice",
                "hi @bot",
                mentionsObject(BOT_DISPLAY, BOT_MEMBER_ID),
                nonReconstructingFt(BOT_DISPLAY),
                "msg-strip-4"));

        handler.onGroupCandidate(candidate);

        assertEquals(1, delivered.size());
        assertEquals("hi @bot", delivered.get(0).text(),
                "no locatable span → delivered as-is, never text-searched");
    }

    // -- helpers -------------------------------------------------------------

    private static SimpleXMessageCodec.GroupCandidate decodeGroupFrame(String frame) {
        var decoded = SimpleXMessageCodec.decode(frame);
        var gc = assertInstanceOf(SimpleXMessageCodec.GroupCandidate.class, decoded,
                "group newChatItem decodes as GroupCandidate");
        assertNotNull(gc, "fixture must produce a GroupCandidate");
        return gc;
    }

    /**
     * Synthesise a simplex-chat group {@code newChatItem} envelope with the
     * given group id, sender, display name, body, top-level {@code mentions{}}
     * object, {@code formattedText}, and item id. The bot's own per-group
     * memberId ({@link #BOT_MEMBER_ID}) is baked into
     * {@code groupInfo.membership} as the recognition anchor.
     */
    private static String groupFrame(String groupId,
                                     String senderContactId,
                                     String senderDisplayName,
                                     String text,
                                     String mentionsJson,
                                     String formattedTextJson,
                                     String itemId) {
        return """
                {
                  "resp": {
                    "type": "newChatItem",
                    "chatItem": {
                      "chatInfo": {
                        "type": "group",
                        "groupInfo": {
                          "groupId": "%s",
                          "membership": {"memberId": "%s"}
                        }
                      },
                      "chatItem": {
                        "meta": {"itemId": "%s"},
                        "chatDir": {
                          "groupMember": {
                            "memberContactId": "%s",
                            "localDisplayName": "%s"
                          }
                        },
                        "content": {
                          "msgContent": {
                            "type": "text",
                            "text": "%s"
                          }
                        },
                        "mentions": %s,
                        "formattedText": %s
                      }
                    }
                  }
                }
                """.formatted(
                groupId, BOT_MEMBER_ID, itemId, senderContactId, senderDisplayName,
                jsonEscape(text), mentionsJson, formattedTextJson);
    }

    /**
     * Build a top-level {@code mentions{}} JSON object from
     * (displayName, memberId) pairs. {@code mentionsObject()} (no pairs)
     * produces the empty object (no mentions).
     */
    private static String mentionsObject(String... displayNameMemberIdPairs) {
        if (displayNameMemberIdPairs.length == 0) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < displayNameMemberIdPairs.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\"%s\":{\"memberId\":\"%s\"}"
                    .formatted(displayNameMemberIdPairs[i], displayNameMemberIdPairs[i + 1]));
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * A {@code formattedText} array with one mention segment per memberName,
     * each carrying the placeholder text {@code "@m"} that does NOT reconstruct
     * a realistic body — so the codec's reconstruction guard voids the spans
     * and the message is delivered unstripped (recognition still fires from
     * {@code mentions{}}).
     */
    private static String nonReconstructingFt(String... memberNames) {
        if (memberNames.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < memberNames.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"text\":\"@m\",\"format\":{\"type\":\"mention\",\"memberName\":\"%s\"}}"
                    .formatted(memberNames[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Build a {@code formattedText} array that DECOMPOSES the message text the
     * way real simplex-chat frames do: the bot-mention segment first, then one
     * plain segment carrying the rest. The frame's {@code msgContent.text} must
     * equal {@code mentionText + rest} or the codec's reconstruction guard
     * discards the spans. The mention segment's {@code memberName} is
     * {@link #BOT_DISPLAY}, which the frame's {@code mentions{}} maps to the
     * bot's memberId.
     */
    private static String decomposingBotMention(String mentionText, String rest) {
        return """
                [{"text":"%s","format":{"type":"mention","memberName":"%s"}},{"text":"%s"}]"""
                .formatted(jsonEscape(mentionText), BOT_DISPLAY, jsonEscape(rest));
    }

    /**
     * Minimal JSON-string escape for the fixtures. The fixtures don't
     * carry control characters; the escape only handles the
     * backslash and double-quote cases so a body like {@code @"}
     * cannot break the envelope.
     */
    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
