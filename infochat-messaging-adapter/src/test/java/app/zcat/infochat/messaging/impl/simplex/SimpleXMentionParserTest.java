package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Trust-anchor tests for {@link SimpleXMentionParser}. The parser decides
 * whether one or more {@code mentions{}} memberIds refer to the bot, and the
 * comparison MUST be exact bytes of the memberId string (D51) — never display
 * names, and with no decoding or canonicalization (a canonicalizing compare is
 * non-injective and lets distinct ids collide).
 */
class SimpleXMentionParserTest {

    // Real-shape per-group memberIds: simplex emits them as base64 (with
    // padding), e.g. the captured "WE1sRTBSZlVvMS9WYXdFcQ==".
    private static final String BOT_MEMBER_ID = "WE1sRTBSZlVvMS9WYXdFcQ==";
    private static final String OTHER_MEMBER_ID = "SENEZlYxaVpZV3dPK2FGWQ==";

    /**
     * Acceptance: an exact byte match of the memberId string succeeds; a
     * near-miss (one differing trailing char) fails.
     */
    @Test
    void memberIdByteEquality() {
        String matching = BOT_MEMBER_ID;
        String nearMiss = BOT_MEMBER_ID.substring(0, BOT_MEMBER_ID.length() - 2) + "X=";

        assertTrue(
                SimpleXMentionParser.botMentioned(List.of(matching), BOT_MEMBER_ID),
                "byte-identical memberId matches");
        assertFalse(
                SimpleXMentionParser.botMentioned(List.of(nearMiss), BOT_MEMBER_ID),
                "a one-char difference must NOT match");
    }

    /**
     * Display-name strings are never memberIds and never byte-equal the bot's
     * memberId. The parser receives the memberIds the codec extracted from
     * {@code mentions{}} — a display-name-only fixture must resolve to false.
     */
    @Test
    void displayNameNeverMatchesMemberId() {
        List<String> mentions = List.of("InfoChatBot", "@InfoChatBot", "Admin-Reno");

        assertFalse(SimpleXMentionParser.botMentioned(mentions, BOT_MEMBER_ID),
                "display-name match never sufficient to mention the bot");
    }

    /**
     * Multi-mention list: any one matching entry triggers true. Tests the
     * iteration walks past non-matching entries (co-mentions of other members).
     */
    @Test
    void multipleMentions_anyMatchReturnsTrue() {
        List<String> mentions = List.of(OTHER_MEMBER_ID, "QW5vdGhlck1lbWJlcg==", BOT_MEMBER_ID);

        assertTrue(SimpleXMentionParser.botMentioned(mentions, BOT_MEMBER_ID),
                "any-match across the mention list returns true");
    }

    /**
     * The compare is exact bytes with no decoding. A canonicalizing compare
     * (base64-decode then compare bytes) would be non-injective: the base64
     * string {@code "MTIzNDU="} decodes to the bytes of {@code "12345"}, so a
     * decode-then-compare would read them equal — yet they are distinct
     * memberId strings. Exact-bytes string comparison keeps them distinct: the
     * colliding non-mention is NOT read as a mention, and a real (exact-string)
     * mention of the bot is NOT suppressed.
     */
    @Test
    void collidingDecodedPair_notReadAsMention() {
        String botMemberId = "12345";
        String collidingNonMention = "MTIzNDU=";

        assertFalse(
                SimpleXMentionParser.botMentioned(List.of(collidingNonMention), botMemberId),
                "distinct memberId strings a decode would collide must NOT mention the bot");
        assertTrue(
                SimpleXMentionParser.botMentioned(List.of(botMemberId), botMemberId),
                "an exact-string mention of the bot is still recognised, not suppressed");
    }

    /**
     * The comparison is literal UTF-8 bytes regardless of whether the operand
     * looks like base64. Two identical strings match; a one-char difference
     * does not.
     */
    @Test
    void literalByteComparison_noDecoding() {
        String id = "12345";
        String differentId = "12346";

        assertTrue(SimpleXMentionParser.botMentioned(List.of(id), id),
                "identical literal id matches");
        assertFalse(SimpleXMentionParser.botMentioned(List.of(differentId), id),
                "different literal ids must NOT match");

        byte[] expectedBytes = id.getBytes(StandardCharsets.UTF_8);
        assertTrue(expectedBytes.length > 0, "fixture sanity: id has bytes");
    }

    /** Empty mention list trivially returns false. */
    @Test
    void emptyMentionList_returnsFalse() {
        assertFalse(SimpleXMentionParser.botMentioned(List.of(), BOT_MEMBER_ID),
                "no mentions in the frame → bot not mentioned");
    }
}
