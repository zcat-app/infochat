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
 * frame's mention list carries a {@code memberId} that is
 * <strong>byte-equal to the bot's own per-group {@code memberId}</strong>
 * (decision D51, superseding the queue-address anchor that v6.5.4.1's
 * mention payload no longer carries; D10's identity anchor is unchanged for
 * sender identity). The bot's memberId is read from the same frame at
 * {@code chatInfo.groupInfo.membership.memberId}. Display-name matching is
 * never sufficient — simplex resolves each {@code @mention} to a member's
 * cryptographic group {@code memberId} (the v6.3 mention model), so a peer
 * who types the bot's display name as plain prose creates no mention entry
 * and cannot fake or suppress a mention.</p>
 *
 * <p>Comparison is on the <em>exact bytes of the memberId string</em>, with
 * no decoding or canonicalization. The mention entries are simplex-chat's
 * own {@code mentions{}} memberId values and the bot id is its own
 * {@code membership.memberId}; both are the stable canonical identifier
 * simplex-chat emits for a member. Treating the canonical identifier as the
 * opaque value it is keeps distinct memberId strings distinct mentions: the
 * only thing that mentions the bot is the bot's exact memberId string.</p>
 */
final class SimpleXMentionParser {

    private SimpleXMentionParser() {
        // utility class — instantiation would be a caller bug.
    }

    /**
     * Returns true when any entry in {@code mentionMemberIds} is byte-equal
     * to {@code botMemberId}. An empty list returns false (no mentions →
     * cannot mention the bot).
     *
     * <p>The per-entry comparison uses {@link MessageDigest#isEqual},
     * which is constant-time only across operands of equal length and
     * short-circuits on a length mismatch. That short-circuit leaks
     * nothing usable here: a SimpleX memberId has a protocol-fixed length
     * determined by its key size, not a secret, so an attacker already
     * knows the bot memberId's length. Within equal-length operands the
     * comparison does not short-circuit, so the number of leading bytes a
     * same-length mention shares with the bot's memberId is not observable
     * via timing — the memberId is the group-mode authorization trust
     * anchor (D51) and must not leak byte-by-byte.</p>
     *
     * @param mentionMemberIds memberIds extracted from the frame's
     *                         {@code mentions{}} object; never null, entries
     *                         never null.
     * @param botMemberId      the bot's own per-group memberId
     *                         ({@code chatInfo.groupInfo.membership.memberId});
     *                         never null.
     * @return true iff some mention entry is byte-equal to the bot's
     *         memberId string.
     */
    static boolean botMentioned(List<String> mentionMemberIds,
                                String botMemberId) {
        if (mentionMemberIds.isEmpty()) {
            return false;
        }
        byte[] botBytes = botMemberId.getBytes(StandardCharsets.UTF_8);
        for (String mention : mentionMemberIds) {
            byte[] mentionBytes = mention.getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(mentionBytes, botBytes)) {
                return true;
            }
        }
        return false;
    }
}
