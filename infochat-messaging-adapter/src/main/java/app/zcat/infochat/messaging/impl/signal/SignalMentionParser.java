package app.zcat.infochat.messaging.impl.signal;


import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

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
     * @param dataMessage the signal-cli envelope's {@code dataMessage}
     *                    object; never null.
     * @param botAci      the bot's per-adapter ACI (UUID string);
     *                    never null.
     * @return true iff the dataMessage carries an ACI-anchored mention
     *         of the bot.
     */
    static boolean botMentioned(JsonObject dataMessage, String botAci) {
        JsonArray mentions = dataMessage.getJsonArray("mentions");
        if (mentions == null || mentions.isEmpty()) {
            return false;
        }
        String botAciLower = botAci.toLowerCase(Locale.ROOT);
        for (JsonValue entry : mentions) {
            if (entry.getValueType() != JsonValue.ValueType.OBJECT) {
                continue;
            }
            JsonObject mention = (JsonObject) entry;
            String uuid = mention.getString("uuid", null);
            if (uuid != null && uuid.toLowerCase(Locale.ROOT).equals(botAciLower)) {
                return true;
            }
        }
        return false;
    }
}
