package app.zcat.infochat.messaging.impl.simplex;

import app.zcat.infochat.messaging.ScopeRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic pins of the TWO harness-only wire shapes {@link LiveSimpleXClient}
 * adds beside the production codec (M1-546): the {@code chatItemUpdated} body
 * extraction and the D51 mention-envelope composition. Both are best-guess
 * shapes declared live-discovery items for the 4b-3 host run — this test is
 * their only CI truth, so a live correction lands here first.
 */
class LiveSimpleXHarnessFrameTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Mirrors the codec's singular newChatItem shape (docs/design/06-messaging.md
    // §6.4) with the item-edit resp type the codec deliberately does not model.
    private static final String CHAT_ITEM_UPDATED_FRAME = """
            {"resp":{"type":"chatItemUpdated","chatItem":{
              "chatInfo":{"type":"direct","contact":{"contactId":7,"localDisplayName":"Admin-Reno"}},
              "chatItem":{"content":{"msgContent":{"type":"text","text":"final summary body"}},
                          "meta":{"itemId":42}}}}}
            """;

    @Test
    void chatItemUpdatedFrameYieldsFinalizedBody() {
        assertEquals(Optional.of("final summary body"),
                LiveSimpleXClient.extractFinalizedBody(CHAT_ITEM_UPDATED_FRAME));
    }

    @Test
    void chatItemUpdatedFrameMissingFieldsIsDroppedNotThrown() {
        assertEquals(Optional.empty(),
                LiveSimpleXClient.extractFinalizedBody("{\"resp\":{\"type\":\"chatItemUpdated\"}}"));
        assertEquals(Optional.empty(),
                LiveSimpleXClient.extractFinalizedBody("not json at all"));
    }

    @Test
    void nonChatItemUpdatedFramesYieldNoFinalizedBody() {
        assertEquals(Optional.empty(), LiveSimpleXClient.extractFinalizedBody(
                "{\"corrId\":\"live-admin-1\",\"resp\":{\"type\":\"contactsList\",\"contacts\":[]}}"));
    }

    @Test
    void mentionEnvelopeCarriesNumericLocalGroupMemberId() throws Exception {
        String envelope = LiveSimpleXClient.encodeMentionSendCommand(
                "live-user-3", "5", "@Admin-Reno /help",
                new LiveSimpleXClient.GroupMember(3, "Admin-Reno"));

        JsonNode root = MAPPER.readTree(envelope);
        assertEquals("live-user-3", root.get("corrId").asText());
        String cmd = root.get("cmd").asText();
        assertTrue(cmd.startsWith("/_send #5 json "),
                "mention cmd must use the production group-scope form, got: " + cmd);

        JsonNode payload = MAPPER.readTree(cmd.substring("/_send #5 json ".length()));
        assertEquals(1, payload.size(), "one composed message per send");
        JsonNode composed = payload.get(0);
        assertEquals("text", composed.path("msgContent").path("type").asText());
        assertEquals("@Admin-Reno /help", composed.path("msgContent").path("text").asText());
        // Live-corrected 2026-07-03 (4b-3 run): ComposedMessage.mentions maps
        // display name -> the sender-local NUMERIC groupMemberId; simplex-chat
        // translates it to the wire memberId the bot byte-compares (D51). The
        // original {memberId} object value is rejected by v6.5.4.1 with
        // "bad chat command: Failed reading: empty".
        JsonNode mentionValue = composed.path("mentions").path("Admin-Reno");
        assertTrue(mentionValue.isIntegralNumber(),
                "mention value must be the numeric local groupMemberId, got: " + mentionValue);
        assertEquals(3L, mentionValue.asLong());
    }

    @Test
    void mentionEnvelopeMatchesProductionGroupEncodingPlusMentions() throws Exception {
        String production = SimpleXMessageCodec.encodeSendCommand(
                "c1", new ScopeRef.Group("5"), "hello group");
        String mention = LiveSimpleXClient.encodeMentionSendCommand(
                "c1", "5", "hello group",
                new LiveSimpleXClient.GroupMember(3, "Admin-Reno"));

        JsonNode productionRoot = MAPPER.readTree(production);
        JsonNode mentionRoot = MAPPER.readTree(mention);
        assertEquals(productionRoot.get("corrId"), mentionRoot.get("corrId"));

        String prefix = "/_send #5 json ";
        String productionCmd = productionRoot.get("cmd").asText();
        String mentionCmd = mentionRoot.get("cmd").asText();
        assertTrue(productionCmd.startsWith(prefix));
        assertTrue(mentionCmd.startsWith(prefix));

        // The harness composed message is the production composed message plus
        // EXACTLY the mentions{} object — no other divergence from the one
        // wire-shape source of truth (D-live-9).
        JsonNode productionComposed = MAPPER.readTree(productionCmd.substring(prefix.length())).get(0);
        JsonNode mentionComposed = MAPPER.readTree(mentionCmd.substring(prefix.length())).get(0);
        assertEquals(productionComposed.get("msgContent"), mentionComposed.get("msgContent"));
        assertEquals(1, productionComposed.size(), "production composed message carries msgContent only");
        assertEquals(2, mentionComposed.size(), "harness adds exactly the mentions object");
        assertTrue(mentionComposed.has("mentions"));
    }
}
