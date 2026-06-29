package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Determinism of the {@code adapterMessageId} fallback for {@code newChatItem}
 * frames that arrive without a wire {@code itemId} (M1-376 / deep-review F3).
 * adapterMessageId is the stable correlation key; the prior
 * {@code "simplex-" + System.nanoTime()} fallback produced a different id on
 * every decode of the same frame, defeating retry correlation and audit
 * cross-references for the itemId-less path. These tests pin that two decodes
 * of one itemId-less frame now yield the same id, on both the DM and the
 * group decode paths.
 */
class SimpleXCodecDeterministicIdTest {

    private static final String DM_FRAME_NO_ITEM_ID = """
            {
              "resp": {
                "type": "newChatItem",
                "chatItem": {
                  "chatInfo": {
                    "type": "direct",
                    "contact": {
                      "contactId": "contact-xyz",
                      "displayName": "Test User"
                    }
                  },
                  "chatItem": {
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

    private static final String GROUP_FRAME_NO_ITEM_ID = """
            {
              "resp": {
                "type": "newChatItem",
                "chatItem": {
                  "chatInfo": {
                    "type": "group",
                    "groupInfo": {
                      "groupId": "group-7",
                      "membership": {"memberId": "bot-member-id"}
                    }
                  },
                  "chatItem": {
                    "chatDir": {
                      "groupMember": {
                        "memberContactId": "member-1",
                        "localDisplayName": "Member One"
                      }
                    },
                    "content": {
                      "msgContent": {
                        "type": "text",
                        "text": "group payload"
                      }
                    }
                  }
                }
              }
            }
            """;

    @Test
    void dmFallbackIdIsDeterministicAcrossDecodes() {
        var first = assertInstanceOf(SimpleXMessageCodec.Inbound.class,
                SimpleXMessageCodec.decode(DM_FRAME_NO_ITEM_ID));
        var second = assertInstanceOf(SimpleXMessageCodec.Inbound.class,
                SimpleXMessageCodec.decode(DM_FRAME_NO_ITEM_ID));
        assertTrue(first.message().adapterMessageId().startsWith("simplex-"),
                "itemId-less DM frame falls back to a simplex-prefixed id");
        assertEquals(first.message().adapterMessageId(), second.message().adapterMessageId(),
                "two decodes of the same itemId-less DM frame must yield the same adapterMessageId");
    }

    @Test
    void groupFallbackIdIsDeterministicAcrossDecodes() {
        var first = assertInstanceOf(SimpleXMessageCodec.GroupCandidate.class,
                SimpleXMessageCodec.decode(GROUP_FRAME_NO_ITEM_ID));
        var second = assertInstanceOf(SimpleXMessageCodec.GroupCandidate.class,
                SimpleXMessageCodec.decode(GROUP_FRAME_NO_ITEM_ID));
        assertTrue(first.adapterMessageId().startsWith("simplex-"),
                "itemId-less group frame falls back to a simplex-prefixed id");
        assertEquals(first.adapterMessageId(), second.adapterMessageId(),
                "two decodes of the same itemId-less group frame must yield the same adapterMessageId");
    }
}
