package app.zcat.infochat.messaging.impl.simplex;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
 * frame's mention list carries a queue address that is
 * <strong>byte-equal to the bot's per-adapter queue address</strong>
 * (decision D10). Display-name matching is never sufficient — an
 * attacker who can spoof the bot's display name in a group must not be
 * able to fake or suppress a mention, and SimpleX's queue address is the
 * D10 trust anchor for this adapter.</p>
 *
 * <p>Comparison is on the <em>exact bytes of the queue-address string</em>,
 * with no decoding or canonicalization. The mention entries are
 * simplex-chat's own {@code format.memberRef} strings and the bot address
 * is {@link SimpleXIdentity#queueAddress()}; both are the stable canonical
 * identifier simplex-chat emits for a member, and both are constrained to
 * {@link SimpleXMessageCodec#isValidQueueAddressId}'s character set before
 * they reach this parser. A prior implementation base64-decoded each
 * operand (with a UTF-8 literal fallback) before comparing the decoded
 * bytes; that canonicalization was <strong>non-injective</strong> — e.g.
 * the base64 string {@code "MTIzNDU="} and the literal {@code "12345"}
 * both reduce to the bytes {@code [0x31..0x35]} and both pass the queue
 * address validator, so a non-mention could be read as a bot mention (or
 * a real mention suppressed). Treating the canonical identifier as the
 * opaque value it is removes that collision: distinct queue-address
 * strings are distinct mentions, and the only thing that mentions the bot
 * is the bot's exact queue-address string.</p>
 */
final class SimpleXMentionParser {

    private SimpleXMentionParser() {
        // utility class — instantiation would be a caller bug.
    }

    /**
     * Returns true when any entry in {@code mentionQueueAddresses} is
     * byte-equal to {@code botQueueAddress}. An empty list returns false
     * (no mentions → cannot mention the bot).
     *
     * <p>The per-entry comparison uses {@link MessageDigest#isEqual},
     * which is constant-time only across operands of equal length and
     * short-circuits on a length mismatch. That short-circuit leaks
     * nothing usable here: a SimpleX queue address has a protocol-fixed
     * length determined by its key size, not a secret, so an attacker
     * already knows the bot address's length. Within equal-length
     * operands the comparison does not short-circuit, so the number of
     * leading bytes a same-length mention shares with the bot's queue
     * address is not observable via timing — the queue address is the
     * group-mode authorization trust anchor (D10) and must not leak
     * byte-by-byte.</p>
     *
     * @param mentionQueueAddresses queue addresses extracted from the
     *                              frame's mention metadata; never null,
     *                              entries never null.
     * @param botQueueAddress       the bot's per-adapter queue address
     *                              ({@link SimpleXIdentity#queueAddress()});
     *                              never null.
     * @return true iff some mention entry is byte-equal to the bot's
     *         queue-address string.
     */
    static boolean botMentioned(List<String> mentionQueueAddresses,
                                String botQueueAddress) {
        if (mentionQueueAddresses.isEmpty()) {
            return false;
        }
        byte[] botBytes = botQueueAddress.getBytes(StandardCharsets.UTF_8);
        for (String mention : mentionQueueAddresses) {
            byte[] mentionBytes = mention.getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(mentionBytes, botBytes)) {
                return true;
            }
        }
        return false;
    }
}
