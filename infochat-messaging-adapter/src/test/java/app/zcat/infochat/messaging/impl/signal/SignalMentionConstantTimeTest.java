package app.zcat.infochat.messaging.impl.signal;

import static app.zcat.infochat.messaging.impl.signal.SignalTestJson.parse;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.json.JsonObject;


import org.junit.jupiter.api.Test;

/**
 * Pins the mention-gate behavior across the switch from {@code String.equals}
 * to the constant-time {@link java.security.MessageDigest#isEqual} compare
 * (D10 trust anchor — the comparison must not short-circuit on the first
 * differing ACI byte). Timing is not directly observable in a unit test, so
 * these assertions guard that the byte-array compare preserves the exact
 * match semantics the previous string compare had: case-insensitive match
 * of the bot ACI, rejection of any other uuid.
 */
class SignalMentionConstantTimeTest {

    private static final String BOT_ACI = "aabbccdd-1111-2222-3333-444455556666";

    @Test
    void matchesBotAciCaseInsensitively() {
        // Upper-case wire uuid vs lower-case bot ACI — the constant-time
        // compare lower-cases both operands first, so recognition holds.
        JsonObject upperWire = parse("""
                {
                  "message": "hey @bot",
                  "mentions": [
                    {"uuid": "AABBCCDD-1111-2222-3333-444455556666", "start": 4, "length": 4}
                  ]
                }
                """);
        assertTrue(SignalMentionParser.botMentioned(upperWire, BOT_ACI),
                "upper-case wire ACI must still match the lower-case bot ACI (case-insensitive)");

        // Lower-case wire uuid vs upper-case bot ACI — symmetric.
        JsonObject lowerWire = parse("""
                {
                  "message": "hey @bot",
                  "mentions": [
                    {"uuid": "aabbccdd-1111-2222-3333-444455556666", "start": 4, "length": 4}
                  ]
                }
                """);
        assertTrue(
                SignalMentionParser.botMentioned(
                        lowerWire, "AABBCCDD-1111-2222-3333-444455556666"),
                "lower-case wire ACI must still match an upper-case bot ACI (case-insensitive)");
    }

    @Test
    void rejectsNonMatchingUuid() {
        // A uuid sharing a long common prefix with the bot ACI but
        // differing in the final group: the constant-time compare must
        // still reject it (and, by construction, not short-circuit).
        JsonObject nearMiss = parse("""
                {
                  "message": "hey @someone",
                  "mentions": [
                    {"uuid": "aabbccdd-1111-2222-3333-444455556667", "start": 4, "length": 8}
                  ]
                }
                """);
        assertFalse(SignalMentionParser.botMentioned(nearMiss, BOT_ACI),
                "a uuid differing only in the final byte must NOT be recognized as the bot");

        // A wholly different uuid — rejected.
        JsonObject different = parse("""
                {
                  "message": "hey @alice",
                  "mentions": [
                    {"uuid": "99998888-7777-6666-5555-444433332222", "start": 4, "length": 6}
                  ]
                }
                """);
        assertFalse(SignalMentionParser.botMentioned(different, BOT_ACI),
                "an unrelated uuid must NOT be recognized as the bot");
    }

}
