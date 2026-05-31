package app.zcat.infochat.messaging.impl.simplex;

import org.jspecify.annotations.NonNull;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Decides whether a SimpleX group {@code newChatItem} mentions the bot.
 * Pure helper; no I/O, no state — every method is a function over its
 * arguments so {@link SimpleXGroupHandler} (and tests) can invoke it
 * concurrently without coordination.
 *
 * <p>Mention recognition follows {@code docs/spec/messaging.md}
 * §Required SPI surface — Receive: a group message counts as an
 * {@code @mention} of the bot <strong>only</strong> when an entry in the
 * frame's mention list carries a queue address whose decoded bytes equal
 * the bot's per-adapter queue address (decision D10). Display-name
 * matching is never sufficient — an attacker who can spoof the bot's
 * display name in a group must not be able to fake or suppress a
 * mention, and SimpleX's queue address is the D10 trust anchor for
 * this adapter.</p>
 *
 * <p>Comparison is on the decoded bytes, not the base64 string. SimpleX
 * queue addresses are URL-safe base64; different encodings of the same
 * key (padded vs. unpadded, occasional standard-alphabet variants) MUST
 * compare equal under D10. When either operand fails to decode as
 * base64 (e.g. the simplex-chat WebSocket bot API also surfaces
 * decimal DB row ids that are legitimate values under
 * {@link SimpleXMessageCodec#isValidQueueAddressId}), the comparison
 * falls back to UTF-8 byte equality on the literal string — both sides
 * undergo the same decode attempt, so the fallback is symmetric.</p>
 */
final class SimpleXMentionParser {

    private SimpleXMentionParser() {
        // utility class — instantiation would be a caller bug.
    }

    /**
     * Returns true when any entry in {@code mentionQueueAddresses}
     * decodes to the same bytes as {@code botQueueAddress}. An empty
     * list returns false (no mentions → cannot mention the bot).
     *
     * @param mentionQueueAddresses queue addresses extracted from the
     *                              frame's mention metadata; never null,
     *                              entries never null.
     * @param botQueueAddress       the bot's per-adapter queue address
     *                              ({@link SimpleXIdentity#queueAddress()});
     *                              never null.
     * @return true iff some mention entry byte-equals the bot's queue
     *         address under base64-decoded (with literal-string
     *         fallback) comparison.
     */
    static boolean botMentioned(@NonNull List<String> mentionQueueAddresses,
                                @NonNull String botQueueAddress) {
        if (mentionQueueAddresses.isEmpty()) {
            return false;
        }
        byte[] botBytes = decodeQueueAddress(botQueueAddress);
        for (String mention : mentionQueueAddresses) {
            byte[] mentionBytes = decodeQueueAddress(mention);
            if (Arrays.equals(botBytes, mentionBytes)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Decode a queue address into its raw bytes, accepting either the
     * URL-safe base64 cryptographic form (with or without padding) or a
     * non-base64 literal (e.g. a simplex-chat DB row id). The literal
     * fallback is the UTF-8 bytes of the original string; both sides of
     * the comparison apply the same routine so the fallback is
     * symmetric — two row ids "12345" still byte-equal each other, and
     * a row id never accidentally collides with a real queue address
     * because their character sets do not overlap meaningfully (a
     * 5-digit decimal is not a valid base64-decoded queue address).
     */
    private static byte[] decodeQueueAddress(String value) {
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException urlFailed) {
            try {
                return Base64.getDecoder().decode(value);
            } catch (IllegalArgumentException stdFailed) {
                return value.getBytes(StandardCharsets.UTF_8);
            }
        }
    }
}
