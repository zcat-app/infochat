package app.zcat.infochat.messaging.impl.signal;

import static app.zcat.infochat.messaging.impl.signal.SignalTestJson.parse;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.json.JsonObject;


import org.junit.jupiter.api.Test;

class SignalMentionParserTest {

    private static final String BOT_ACI = "11112222-3333-4444-5555-666677778888";

    @Test
    void aciComparison() {
        // Exact ACI match — bot is mentioned.
        JsonObject matching = parse("""
                {
                  "message": "hey @bot",
                  "mentions": [
                    {"uuid": "11112222-3333-4444-5555-666677778888", "start": 4, "length": 4}
                  ]
                }
                """);
        assertTrue(SignalMentionParser.botMentioned(matching, BOT_ACI),
                "exact ACI UUID match must recognize the bot mention");

        // Different ACI — bot is NOT mentioned.
        JsonObject differentUuid = parse("""
                {
                  "message": "hey @alice",
                  "mentions": [
                    {"uuid": "99998888-7777-6666-5555-444433332222", "start": 4, "length": 6}
                  ]
                }
                """);
        assertFalse(SignalMentionParser.botMentioned(differentUuid, BOT_ACI),
                "ACI UUID mismatch must NOT recognize a bot mention");

        // Case-insensitive — upstream upper-case ACI must still match
        // a lower-case bot ACI under the spec's byte-equality rule
        // (both sides canonicalized to lower-case before comparison so
        // an upstream case-folding cannot break recognition).
        JsonObject upperCaseUuid = parse("""
                {
                  "message": "hey @bot",
                  "mentions": [
                    {"uuid": "AABBCCDD-1111-2222-3333-444455556666", "start": 4, "length": 4}
                  ]
                }
                """);
        assertTrue(
                SignalMentionParser.botMentioned(
                        upperCaseUuid, "aabbccdd-1111-2222-3333-444455556666"),
                "ACI comparison must be case-insensitive — upper-case mention vs lower-case bot ACI must match");
    }

    @Test
    void noMentionsArray() {
        // Missing mentions array — no bot mention.
        JsonObject noMentions = parse("""
                {"message": "hey nobody"}
                """);
        assertFalse(SignalMentionParser.botMentioned(noMentions, BOT_ACI));

        // Empty mentions array — no bot mention.
        JsonObject emptyMentions = parse("""
                {"message": "hey nobody", "mentions": []}
                """);
        assertFalse(SignalMentionParser.botMentioned(emptyMentions, BOT_ACI));
    }

    @Test
    void displayNameOnlyMentionIsNotEnough() {
        // A mentions array whose only entry points at a different ACI
        // even though the body text contains the bot's display name MUST
        // NOT count as a bot mention (spec rule: ACI-only). This
        // strengthens the mentionByDisplayName_ignored case at the
        // parser layer — display-name strings in the body are invisible
        // to the parser by design.
        JsonObject displayNameOnly = parse("""
                {
                  "message": "hey @TheBot",
                  "mentions": [
                    {"uuid": "99998888-7777-6666-5555-444433332222", "start": 4, "length": 7}
                  ]
                }
                """);
        assertFalse(SignalMentionParser.botMentioned(displayNameOnly, BOT_ACI),
                "Display-name in body must NOT register as a bot mention when ACI does not match");
    }

}
