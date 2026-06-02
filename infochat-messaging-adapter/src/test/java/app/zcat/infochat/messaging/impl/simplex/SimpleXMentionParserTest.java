package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * D10 trust-anchor tests for {@link SimpleXMentionParser}. The parser
 * decides whether one or more {@code formattedText} mentions refer to
 * the bot, and the comparison MUST be exact bytes of the queue-address
 * string — never display names, and with no decoding or canonicalization
 * (a canonicalizing compare is non-injective and lets distinct addresses
 * collide).
 */
class SimpleXMentionParserTest {

    /**
     * Acceptance item: exact byte match of decoded queue addresses
     * succeeds; a near-miss (one differing byte) fails. The base64
     * fixtures used here are the URL-safe encoding of randomly chosen
     * 16-byte arrays — the same shape SimpleX assigns real queue
     * addresses.
     */
    @Test
    void queueAddressByteEquality() {
        byte[] botBytes = new byte[]{
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        byte[] nearMissBytes = botBytes.clone();
        nearMissBytes[15] = (byte) 0xFF;

        String botAddress = base64Url(botBytes);
        String matchingAddress = base64Url(botBytes);
        String nearMissAddress = base64Url(nearMissBytes);

        assertTrue(
                SimpleXMentionParser.botMentioned(List.of(matchingAddress), botAddress),
                "byte-identical decoded queue address matches");
        assertFalse(
                SimpleXMentionParser.botMentioned(List.of(nearMissAddress), botAddress),
                "one-byte difference must NOT match");
    }

    /**
     * Display-name strings are never queue addresses, never match the
     * bot's address byte-equality. The parser receives the mention
     * list the codec extracted from {@code formattedText.format.memberRef}
     * — a display-name-only fixture (no queue-address mention) must
     * still resolve to false.
     */
    @Test
    void displayNameOnlyFixture_returnsFalse() {
        String botAddress = "BOT-QUEUE-ADDR";
        List<String> mentions = List.of("InfoChatBot", "@InfoChatBot");

        assertFalse(SimpleXMentionParser.botMentioned(mentions, botAddress),
                "display-name match never sufficient to mention the bot");
    }

    /**
     * Multi-mention list: any one matching entry triggers true. Tests
     * the iteration walks past the first non-matching entry.
     */
    @Test
    void multipleMentions_anyMatchReturnsTrue() {
        String botAddress = "BOT-QUEUE-ADDR";
        List<String> mentions = List.of("alice-queue-addr", "bob-queue-addr", botAddress);

        assertTrue(SimpleXMentionParser.botMentioned(mentions, botAddress),
                "any-match across the mention list returns true");
    }

    /**
     * Regression for the removed non-injective canonicalization. The old
     * implementation base64-decoded each operand before comparing, so the
     * base64 string {@code "MTIzNDU="} (which decodes to the bytes of
     * {@code "12345"}) and the literal {@code "12345"} (UTF-8 fallback)
     * collapsed to the same bytes {@code [0x31..0x35]} and compared equal
     * — yet they are distinct queue-address strings, both of which pass
     * {@link SimpleXMessageCodec#isValidQueueAddressId} and therefore both
     * reach the parser. Exact-bytes string comparison must keep them
     * distinct: the colliding non-mention is NOT read as a mention, and a
     * real (exact-string) mention of the bot is NOT suppressed.
     */
    @Test
    void collidingDecodedPair_notReadAsMention() {
        String botAddress = "12345";
        String collidingNonMention = "MTIzNDU=";

        assertFalse(
                SimpleXMentionParser.botMentioned(List.of(collidingNonMention), botAddress),
                "distinct queue-address strings the old decode collided must NOT mention the bot");
        assertTrue(
                SimpleXMentionParser.botMentioned(List.of(botAddress), botAddress),
                "an exact-string mention of the bot is still recognised, not suppressed");
    }

    /**
     * Non-base64 inputs (e.g. simplex-chat decimal DB row ids, which
     * also live in the queue-address character set) fall back to UTF-8
     * literal-string byte comparison. Two row ids "12345" still match;
     * "12345" vs "12346" does not.
     */
    @Test
    void nonBase64LiteralFallback() {
        String rowId = "12345";
        String differentRowId = "12346";

        assertTrue(SimpleXMentionParser.botMentioned(List.of(rowId), rowId),
                "identical non-base64 row id matches via literal fallback");
        assertFalse(SimpleXMentionParser.botMentioned(List.of(differentRowId), rowId),
                "different non-base64 row ids must NOT match");

        byte[] expectedBytes = rowId.getBytes(StandardCharsets.UTF_8);
        assertTrue(expectedBytes.length > 0, "fixture sanity: row id has bytes");
    }

    /** Empty mention list trivially returns false. */
    @Test
    void emptyMentionList_returnsFalse() {
        assertFalse(SimpleXMentionParser.botMentioned(List.of(), "BOT-QUEUE-ADDR"),
                "no mentions in the frame → bot not mentioned");
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
