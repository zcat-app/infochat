package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
 * <p>The bot's queue address used throughout
 * ({@code "BOT-QUEUE-ADDR"}) is shorter than a real SimpleX
 * cryptographic identifier but still falls inside the queue-address
 * character set the codec accepts at the inbound trust boundary
 * (URL-safe base64 ∪ decimal — see
 * {@link SimpleXMessageCodec#isValidQueueAddressId}).</p>
 */
class SimpleXGroupHandlerTest {

    private static final String BOT_QUEUE_ADDR = "BOT-QUEUE-ADDR";
    private static final SimpleXIdentity BOT_IDENTITY = new SimpleXIdentity(BOT_QUEUE_ADDR);

    private final List<InboundMessage> delivered = new ArrayList<>();
    private final SimpleXGroupHandler handler =
            new SimpleXGroupHandler(BOT_IDENTITY, delivered::add);

    /**
     * The canonical attack-scenario test: a peer mentions the bot via
     * a queue-address-anchored formattedText entry, and the handler
     * delivers the resulting {@link InboundMessage} with the right
     * sender, body, group scope, and adapter message id.
     */
    @Test
    void mentionByQueueAddress_delivered() {
        var candidate = decodeGroupFrame(groupFrame(
                "group-7",
                "alice-queue-addr",
                "Alice",
                "hi @bot",
                mentions(BOT_QUEUE_ADDR),
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
     * D10 trust anchor enforcement: an impersonator whose displayName
     * matches the bot but whose formattedText mention points at the
     * impersonator's own queue address (not the bot's) MUST NOT trigger
     * delivery. This is the attack the queue-address byte-equality rule
     * defends against — display-name matching is never sufficient.
     */
    @Test
    void mentionByDisplayName_ignored() {
        var candidate = decodeGroupFrame(groupFrame(
                "group-7",
                "mallory-queue-addr",
                "InfoChatBot",
                "@InfoChatBot help",
                mentions("mallory-queue-addr"),
                "msg-spoof"));

        handler.onGroupCandidate(candidate);

        assertEquals(0, delivered.size(),
                "display-name match without bot queue-address mention MUST NOT deliver");
    }

    /** Group messages with no mentions at all are silently dropped. */
    @Test
    void noMention_ignored() {
        var candidate = decodeGroupFrame(groupFrame(
                "group-7",
                "alice-queue-addr",
                "Alice",
                "general chatter, not addressed to bot",
                mentions(),
                "msg-quiet"));

        handler.onGroupCandidate(candidate);

        assertEquals(0, delivered.size(),
                "no mention list → silent drop, no log spam");
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
                        "chatType": "direct",
                        "contact": {
                          "contactId": "alice-queue-addr",
                          "displayName": "Alice"
                        }
                      },
                      "chatItem": {
                        "itemId": "dm-1",
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
                mentions(BOT_QUEUE_ADDR),
                "msg-A"));
        var second = decodeGroupFrame(groupFrame(
                "group-42",
                "bob-queue-addr", "Bob",
                "second @bot",
                mentions(BOT_QUEUE_ADDR),
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
     * (a) let a globally-banned user evade Provider's ban check (the
     * per-group memberId does not match the banned row keyed on the
     * user's queue-address contactId) and (b) collide pre-contact
     * members across distinct groups (memberId is a per-group counter
     * — different real users in different groups routinely share the
     * same value).
     */
    @Test
    void memberIdOnlyGroupFrame_dropped() {
        String frame = """
                {
                  "resp": {
                    "type": "newChatItem",
                    "chatItem": {
                      "chatInfo": {
                        "chatType": "group",
                        "groupInfo": {
                          "groupId": "group-7"
                        }
                      },
                      "chatItem": {
                        "itemId": "msg-precontact",
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
                        "formattedText": %s
                      }
                    }
                  }
                }
                """.formatted(jsonEscape("hello @bot"), mentions(BOT_QUEUE_ADDR));

        var decoded = SimpleXMessageCodec.decode(frame);
        var ignored = assertInstanceOf(SimpleXMessageCodec.Ignored.class, decoded,
                "frame missing memberContactId MUST decode as Ignored, not GroupCandidate");
        assertEquals("newChatItem-group-without-sender", ignored.reason(),
                "rejection reason names the missing-sender path");
        assertEquals(0, delivered.size(),
                "handler is never invoked when decode drops the frame");
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
     * Synthesise a simplex-chat group {@code newChatItem} envelope
     * with the given group id, sender, display name, body, mentions
     * list, and item id. The shape mirrors what
     * {@link SimpleXMessageCodec#decodeNewChatItem} expects (see the
     * frame-path conventions documented on its group branch).
     */
    private static String groupFrame(String groupId,
                                     String senderContactId,
                                     String senderDisplayName,
                                     String text,
                                     String formattedTextJson,
                                     String itemId) {
        return """
                {
                  "resp": {
                    "type": "newChatItem",
                    "chatItem": {
                      "chatInfo": {
                        "chatType": "group",
                        "groupInfo": {
                          "groupId": "%s"
                        }
                      },
                      "chatItem": {
                        "itemId": "%s",
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
                        "formattedText": %s
                      }
                    }
                  }
                }
                """.formatted(
                groupId, itemId, senderContactId, senderDisplayName,
                jsonEscape(text), formattedTextJson);
    }

    /**
     * Build a {@code formattedText} JSON array carrying one
     * {@code format.type == "mention"} entry per queue address. An
     * empty argument list produces the empty array (no mentions).
     */
    private static String mentions(String... mentionQueueAddresses) {
        if (mentionQueueAddresses.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < mentionQueueAddresses.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("""
                    {"text":"@m","format":{"type":"mention","memberRef":"%s"}}"""
                    .formatted(mentionQueueAddresses[i]));
        }
        sb.append(']');
        return sb.toString();
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
