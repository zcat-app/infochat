package app.zcat.infochat.messaging.impl.signal;


import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Decides whether one signal-cli group {@code dataMessage} mentions the
 * bot. Pure helper; no I/O, no state — every method is a function over
 * its arguments so the {@link SignalGroupHandler} (and tests) can call
 * into the parser concurrently without coordination.
 *
 * <p>Mention recognition follows {@code docs/spec/messaging.md} §Required
 * SPI surface — Receive: a group message counts as an {@code @mention}
 * of the bot <strong>only</strong> when an entry in the dataMessage's
 * {@code mentions} array carries a {@code uuid} that byte-equals the
 * bot's per-adapter ACI under canonical lowercase form. Display-name
 * matching is never sufficient — an attacker who can spoof the bot's
 * display name in a group must not be able to fake a mention, and
 * Signal's mention payload is the D10 trust anchor for group mode.</p>
 *
 * <p>ACI canonicalization mirrors {@link SignalMessageCodec#canonicalizeAci}:
 * both sides of the comparison are lower-cased so an upstream that
 * upper-cases the UUID cannot break {@code (adapter, contact_id)} byte
 * equality.</p>
 */
final class SignalMentionParser {

    private SignalMentionParser() {
        // utility class — instantiation would be a caller bug.
    }

    /**
     * Returns true when {@code dataMessage.mentions} contains an entry
     * whose {@code uuid} equals {@code botAci} under canonical lowercase
     * form. Missing or empty {@code mentions} array → false.
     *
     * <p>Both operands are lower-cased (preserving the case-insensitive
     * ACI match) and then compared as UTF-8 bytes in constant time
     * ({@link MessageDigest#isEqual}), mirroring the {@code SimpleXMentionParser}
     * sibling: the ACI is the D10 group-mode trust anchor and the number
     * of leading bytes a wire mention shares with it must not leak via
     * timing.</p>
     *
     * @param dataMessage the signal-cli envelope's {@code dataMessage}
     *                    object; never null.
     * @param botAci      the bot's per-adapter ACI (UUID string);
     *                    never null.
     * @return true iff the dataMessage carries an ACI-anchored mention
     *         of the bot.
     */
    static boolean botMentioned(JsonObject dataMessage, String botAci) {
        // instanceof doubles as the null-check and the type-check (the
        // codec's discipline): a present-but-wrong-typed mentions field
        // (untrusted wire data) collapses into the same 'not a mention ->
        // false' branch as an absent one rather than throwing CCE out of
        // the typed getJsonArray accessor.
        if (!(dataMessage.get("mentions") instanceof JsonArray mentions) || mentions.isEmpty()) {
            return false;
        }
        byte[] botBytes = botAci.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        for (JsonValue entry : mentions) {
            if (entry.getValueType() != JsonValue.ValueType.OBJECT) {
                continue;
            }
            JsonObject mention = (JsonObject) entry;
            String uuid = mention.getString("uuid", null);
            if (uuid == null) {
                continue;
            }
            byte[] uuidBytes = uuid.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(uuidBytes, botBytes)) {
                return true;
            }
        }
        return false;
    }
}
