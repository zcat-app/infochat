package app.zcat.infochat.messaging.impl.signal;

import static app.zcat.infochat.messaging.impl.signal.SignalTestJson.parse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;


import org.junit.jupiter.api.Test;

import app.zcat.infochat.messaging.MembershipEvent;
import app.zcat.infochat.messaging.metrics.AdapterMetrics;

/**
 * M1-331: robustness of the Signal group inbound path against untrusted
 * Signal-peer wire data at the adapter-inbound trust boundary
 * ({@code docs/spec/security.md} §Trust boundaries). Two hardening
 * guarantees, both fixing a way a hostile peer could weaponize a
 * present-but-malformed field that the per-span / per-field individual
 * guards do not cover:
 *
 * <ul>
 *   <li>{@code SignalGroupHandler.stripBotMentions} coalesces
 *       overlapping/adjacent bot-mention spans before deleting, so two
 *       overlapping bot-uuid spans cannot make {@code StringBuilder.delete}
 *       clamp against the already-shortened buffer and silently mutilate
 *       the body — the strip is a single contiguous, well-defined,
 *       idempotent operation.</li>
 *   <li>{@code handleReceive} and {@code SignalMentionParser.botMentioned}
 *       guard their JSON accessors with {@code instanceof}, so a
 *       present-but-wrong-typed field drops as cleanly as an absent one
 *       rather than throwing {@code ClassCastException} out of the handler.</li>
 * </ul>
 */
class SignalGroupInboundRobustnessTest {

    private static final String BOT_ACI = "11112222-3333-4444-5555-666677778888";
    private static final String SENDER = "AABBCCDD-1111-2222-3333-444455556666";
    private static final String GROUP_V2_ID = "Z3JvdXBJZEJhc2U2NEVuY29kZWQ=";

    // ---- overlap coalescing (acceptance items 1 + 2) ----

    @Test
    void overlappingBotSpansStripToSameResultAsSingleMergedSpan() {
        // The acceptance example: an untrusted peer authors two
        // overlapping bot-uuid spans {start=5,length=10} and
        // {start=8,length=10} over a 21-char body. Both cover the region
        // [5,18); a single span {start=5,length=13} covers the identical
        // region. Coalescing must yield the same well-defined result, not
        // the order-dependent StringBuilder.delete clamping that would
        // otherwise mutilate the body.
        String body = "abcde0123456789012XYZ";

        String twoOverlapping = deliveredText(body, span(5, 10), span(8, 10));
        String singleMerged = deliveredText(body, span(5, 13));

        assertEquals("abcdeXYZ", singleMerged,
                "the merged region [5,18) is removed, leaving the surrounding text");
        assertEquals(singleMerged, twoOverlapping,
                "two overlapping spans coalesce to the same strip as one merged span");
    }

    @Test
    void overlapStripIsIdempotentAcrossOverlapShapes() {
        // Any overlap shape covering [5,18) — reversed order, full
        // containment, exactly-adjacent — yields the identical body. The
        // merge is order- and shape-independent.
        String body = "abcde0123456789012XYZ";
        String expected = "abcdeXYZ";

        assertEquals(expected, deliveredText(body, span(8, 10), span(5, 10)),
                "reversed span order");
        assertEquals(expected, deliveredText(body, span(5, 13), span(8, 4)),
                "a span fully contained inside a larger one");
        assertEquals(expected, deliveredText(body, span(5, 8), span(13, 5)),
                "exactly-adjacent spans (end of one == start of the next)");
    }

    @Test
    void nonOverlappingSpansAndSingleMentionUnchanged() {
        // A gap between spans keeps them as separate intervals, so the
        // prior single-mention / non-overlapping strip behavior is
        // unchanged: each disjoint span is removed independently.
        assertEquals("summarise this", deliveredText("@bot summarise this", span(0, 4)),
                "single mention span stripped exactly as before");
        assertEquals("foobarbaz", deliveredText("foo@@bar@@baz", span(3, 2), span(8, 2)),
                "two non-overlapping spans each removed; the gap text preserved");
    }

    // ---- instanceof-guarded accessors (acceptance items 3 + 4) ----

    @Test
    void wrongTypedFieldsDropCleanlyWithoutException() {
        // Each of envelope / dataMessage / groupV2 / mentions /
        // memberJoined / memberLeft, present but wrong-typed, must drop
        // into the same branch as an absent field — no ClassCastException
        // escapes handleReceive or botMentioned.
        RecordingInbound inbound = new RecordingInbound();
        RecordingMembership membership = new RecordingMembership();
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, inbound, membership, AdapterMetrics.noop());

        assertDoesNotThrow(() -> handler.handleReceive(parse(
                "{\"envelope\": \"x\"}")),
                "envelope present but a string");
        assertDoesNotThrow(() -> handler.handleReceive(parse(
                "{\"envelope\": {\"dataMessage\": \"x\"}}")),
                "dataMessage present but a string");
        assertDoesNotThrow(() -> handler.handleReceive(parse(
                "{\"envelope\": {\"dataMessage\": {\"groupV2\": 5}}}")),
                "groupV2 present but a number");
        assertDoesNotThrow(() -> handler.handleReceive(parse("""
                {
                  "envelope": {
                    "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                    "timestamp": 1700000001000,
                    "dataMessage": {
                      "timestamp": 1700000001000,
                      "message": "@bot hi",
                      "groupV2": {"id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ="},
                      "mentions": "x"
                    }
                  }
                }
                """)),
                "mentions present but a string (reached via botMentioned)");
        assertDoesNotThrow(() -> handler.handleReceive(parse(
                "{\"envelope\": {\"dataMessage\": {\"groupV2\": {\"id\": \""
                        + GROUP_V2_ID + "\", \"memberJoined\": \"x\"}}}}")),
                "memberJoined present but a string");
        assertDoesNotThrow(() -> handler.handleReceive(parse(
                "{\"envelope\": {\"dataMessage\": {\"groupV2\": {\"id\": \""
                        + GROUP_V2_ID + "\", \"memberLeft\": 5}}}}")),
                "memberLeft present but a number");

        assertEquals(0, inbound.messages.size(),
                "no wrong-typed frame produces an inbound dispatch");
        assertEquals(0, membership.events.size(),
                "no wrong-typed frame produces a membership event");
    }

    @Test
    void wellFormedFramesBehaveUnchanged() {
        RecordingInbound inbound = new RecordingInbound();
        RecordingMembership membership = new RecordingMembership();
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, inbound, membership, AdapterMetrics.noop());

        handler.handleReceive(parse("""
                {
                  "envelope": {
                    "dataMessage": {
                      "groupV2": {
                        "id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ=",
                        "memberJoined": ["AABBCCDD-1111-2222-3333-444455556666"]
                      }
                    }
                  }
                }
                """));
        assertEquals(1, membership.events.size(),
                "a well-formed memberJoined array still dispatches one event");
        assertInstanceOf(MembershipEvent.UserJoined.class, membership.events.get(0));

        handler.handleReceive(parse("""
                {
                  "envelope": {
                    "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                    "timestamp": 1700000001000,
                    "dataMessage": {
                      "timestamp": 1700000001000,
                      "message": "@bot hello",
                      "groupV2": {"id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ="},
                      "mentions": [
                        {"uuid": "11112222-3333-4444-5555-666677778888", "start": 0, "length": 4}
                      ]
                    }
                  }
                }
                """));
        assertEquals(1, inbound.messages.size(),
                "a well-formed bot mention is still delivered");
        assertEquals("hello", inbound.messages.get(0).text(),
                "the mention span is stripped as before");
    }

    // ---- helpers ----

    /**
     * Build a group frame whose dataMessage carries {@code body} and the
     * given bot-uuid mention spans, feed it to a fresh handler, and return
     * the single delivered message's text. Asserts exactly one delivery —
     * every span carries the bot ACI, so the bot is always mentioned.
     */
    private String deliveredText(String body, JsonObject... mentionSpans) {
        RecordingInbound inbound = new RecordingInbound();
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, inbound, null, AdapterMetrics.noop());
        JsonArrayBuilder mentions = Json.createArrayBuilder();
        for (JsonObject span : mentionSpans) {
            mentions.add(span);
        }
        JsonObject frame = Json.createObjectBuilder()
                .add("envelope", Json.createObjectBuilder()
                        .add("sourceUuid", SENDER)
                        .add("timestamp", 1700000001000L)
                        .add("dataMessage", Json.createObjectBuilder()
                                .add("timestamp", 1700000001000L)
                                .add("message", body)
                                .add("groupV2", Json.createObjectBuilder().add("id", GROUP_V2_ID))
                                .add("mentions", mentions)))
                .build();
        handler.handleReceive(frame);
        assertEquals(1, inbound.messages.size(),
                "the bot is ACI-mentioned, so exactly one message is delivered");
        return inbound.messages.get(0).text();
    }

    private static JsonObject span(int start, int length) {
        return Json.createObjectBuilder()
                .add("uuid", BOT_ACI)
                .add("start", start)
                .add("length", length)
                .build();
    }

}
