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
 * the bot, and the comparison MUST be done on decoded queue-address
 * bytes — never display names, never base64-string equality.
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
     * Padded vs. unpadded base64 of the same bytes must compare equal
     * (the JDK URL decoder accepts both forms). The bot's address and
     * the mention payload may be encoded differently across simplex-chat
     * versions; byte equality on the decoded form is the only invariant.
     */
    @Test
    void base64PaddedVsUnpaddedEquivalence() {
        byte[] bytes = new byte[]{
                (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF, 1};
        String padded = Base64.getUrlEncoder().encodeToString(bytes);
        String unpadded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        assertTrue(SimpleXMentionParser.botMentioned(List.of(padded), unpadded),
                "padded vs unpadded base64 of identical bytes must match");
        assertTrue(SimpleXMentionParser.botMentioned(List.of(unpadded), padded),
                "comparison is symmetric across padding");
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
