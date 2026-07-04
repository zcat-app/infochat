package app.zcat.infochat.messaging.impl.signal;

import static app.zcat.infochat.messaging.impl.signal.SignalTestJson.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.json.JsonObject;


import org.junit.jupiter.api.Test;

import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.metrics.AdapterMetrics;

class SignalGroupHandlerTest {

    private static final String BOT_ACI = "11112222-3333-4444-5555-666677778888";
    private static final String GROUP_V2_ID = "Z3JvdXBJZEJhc2U2NEVuY29kZWQ=";

    @Test
    void mentionByAci_delivered() {
        RecordingInbound inbound = new RecordingInbound();
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, inbound, AdapterMetrics.noop());

        JsonObject params = parse("""
                {
                  "envelope": {
                    "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                    "timestamp": 1700000001000,
                    "dataMessage": {
                      "timestamp": 1700000001000,
                      "message": "@bot summarise this",
                      "groupV2": {"id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ="},
                      "mentions": [
                        {"uuid": "11112222-3333-4444-5555-666677778888", "start": 0, "length": 4}
                      ]
                    }
                  }
                }
                """);

        handler.handleReceive(params);

        assertEquals(1, inbound.messages.size(), "exactly one inbound dispatch expected");
        InboundMessage msg = inbound.messages.get(0);
        assertInstanceOf(ScopeRef.Group.class, msg.scope(),
                "group-mention message must be dispatched with group scope");
        assertEquals(GROUP_V2_ID, ((ScopeRef.Group) msg.scope()).adapterGroupId(),
                "group id MUST be the signal-cli groupV2.id (base64)");
        assertEquals("aabbccdd-1111-2222-3333-444455556666", msg.sender().contactId(),
                "sender ACI must be canonicalized to lowercase");
        assertEquals("summarise this", msg.text(),
                "bot mention span must be stripped before delivery");
    }

    @Test
    void groupInfoWireShape_delivered() {
        // F-live-10: the shape real signal-cli 0.14.5 emits — the group
        // stanza is groupInfo{groupId, groupName, revision, type}, NOT
        // groupV2{id}. The pre-fix handler gated on groupV2 and silently
        // dropped every live group message. Shape-faithful reconstruction
        // of the live-captured envelope (synthetic identifiers — the real
        // capture holds private phone numbers and never enters the repo).
        RecordingInbound inbound = new RecordingInbound();
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, inbound, AdapterMetrics.noop());

        handler.handleReceive(parse("""
                {
                  "envelope": {
                    "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                    "sourceName": "LiveUser",
                    "timestamp": 1700000030000,
                    "dataMessage": {
                      "timestamp": 1700000030000,
                      "message": "@bot summarise this",
                      "mentions": [
                        {"name": "+15550000000", "number": "+15550000000",
                         "uuid": "11112222-3333-4444-5555-666677778888",
                         "start": 0, "length": 4}
                      ],
                      "groupInfo": {
                        "groupId": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ=",
                        "groupName": "live-signal-group",
                        "revision": 10,
                        "type": "DELIVER"
                      }
                    }
                  }
                }
                """));

        assertEquals(1, inbound.messages.size(),
                "the real 0.14.5 groupInfo shape MUST dispatch (F-live-10 regression)");
        InboundMessage msg = inbound.messages.get(0);
        assertInstanceOf(ScopeRef.Group.class, msg.scope());
        assertEquals(GROUP_V2_ID, ((ScopeRef.Group) msg.scope()).adapterGroupId(),
                "group id MUST be the signal-cli groupInfo.groupId (base64)");
        assertEquals("summarise this", msg.text(),
                "bot mention span must be stripped before delivery");
    }

    @Test
    void mentionSpanStripIsAnchoredToProtocolEntry_plainTextBotNameKept() {
        // The body contains the bot's display name as plain text AFTER
        // the real mention. Only the protocol mention span ([0,4) per
        // the mentions entry) may be removed — a display-name text
        // search would also eat the trailing "bot".
        RecordingInbound inbound = new RecordingInbound();
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, inbound, AdapterMetrics.noop());

        handler.handleReceive(parse("""
                {
                  "envelope": {
                    "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                    "timestamp": 1700000009000,
                    "dataMessage": {
                      "timestamp": 1700000009000,
                      "message": "@bot say hello to bot",
                      "groupV2": {"id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ="},
                      "mentions": [
                        {"uuid": "11112222-3333-4444-5555-666677778888", "start": 0, "length": 4}
                      ]
                    }
                  }
                }
                """));

        assertEquals(1, inbound.messages.size());
        assertEquals("say hello to bot", inbound.messages.get(0).text(),
                "only the actual mention span is removed; plain-text 'bot' stays");
    }

    @Test
    void groupSlashCommandParseableAfterStrip() {
        RecordingInbound inbound = new RecordingInbound();
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, inbound, AdapterMetrics.noop());

        handler.handleReceive(parse("""
                {
                  "envelope": {
                    "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                    "timestamp": 1700000010000,
                    "dataMessage": {
                      "timestamp": 1700000010000,
                      "message": "@bot /summary",
                      "groupV2": {"id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ="},
                      "mentions": [
                        {"uuid": "11112222-3333-4444-5555-666677778888", "start": 0, "length": 4}
                      ]
                    }
                  }
                }
                """));

        assertEquals(1, inbound.messages.size());
        String delivered = inbound.messages.get(0).text();
        assertTrue(delivered.startsWith("/"),
                "delivered text must start with the slash so the command parser sees it");
        assertEquals("/summary", delivered);
    }

    @Test
    void midTextMentionStripNormalizesWhitespace() {
        // Mention in the middle of the body: removing the span must not
        // leave the surrounding spaces doubled.
        RecordingInbound inbound = new RecordingInbound();
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, inbound, AdapterMetrics.noop());

        handler.handleReceive(parse("""
                {
                  "envelope": {
                    "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                    "timestamp": 1700000011000,
                    "dataMessage": {
                      "timestamp": 1700000011000,
                      "message": "hey @bot summarise",
                      "groupV2": {"id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ="},
                      "mentions": [
                        {"uuid": "11112222-3333-4444-5555-666677778888", "start": 4, "length": 4}
                      ]
                    }
                  }
                }
                """));

        assertEquals(1, inbound.messages.size());
        assertEquals("hey summarise", inbound.messages.get(0).text(),
                "junction whitespace collapses to a single space");
    }

    @Test
    void mentionByDisplayName_ignored() {
        // The dataMessage's mentions array points at a NON-bot ACI even
        // though the body text says "@TheBot". Spec rule: display-name
        // matching is never sufficient — only ACI-anchored mentions
        // cross the adapter boundary. The message must be silently
        // dropped.
        RecordingInbound inbound = new RecordingInbound();
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, inbound, AdapterMetrics.noop());

        JsonObject params = parse("""
                {
                  "envelope": {
                    "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                    "timestamp": 1700000002000,
                    "dataMessage": {
                      "timestamp": 1700000002000,
                      "message": "@TheBot summarise this",
                      "groupV2": {"id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ="},
                      "mentions": [
                        {"uuid": "99998888-7777-6666-5555-444433332222", "start": 0, "length": 7}
                      ]
                    }
                  }
                }
                """);

        handler.handleReceive(params);

        assertEquals(0, inbound.messages.size(),
                "display-name mention without ACI mention MUST be silently dropped");
    }

    @Test
    void noMention_ignored() {
        // A group message with no mentions array at all — bot is not
        // mentioned. Silent drop per spec ("Group messages arrive only
        // when the bot is @mentioned").
        RecordingInbound inbound = new RecordingInbound();
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, inbound, AdapterMetrics.noop());

        JsonObject params = parse("""
                {
                  "envelope": {
                    "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                    "timestamp": 1700000003000,
                    "dataMessage": {
                      "timestamp": 1700000003000,
                      "message": "just chatting amongst ourselves",
                      "groupV2": {"id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ="}
                    }
                  }
                }
                """);

        handler.handleReceive(params);

        assertEquals(0, inbound.messages.size(),
                "group message without a bot mention MUST be silently dropped");
    }

    @Test
    void adapterGroupHandlerWiresBotAciAndCallbacks() {
        // The SignalAdapter.groupHandler() factory must propagate the
        // bot ACI and the currently-registered inbound callback so the
        // group surface delivered through Provider's wiring is anchored
        // against the right identity.
        java.net.InetSocketAddress endpoint =
                new java.net.InetSocketAddress("127.0.0.1", 0);
        SignalAdapter adapter = new SignalAdapter(
                "/usr/bin/signal-cli",
                "/tmp/signal-data",
                "+15551234567",
                endpoint);
        adapter.adoptBotAci(BOT_ACI);
        RecordingInbound inbound = new RecordingInbound();
        adapter.setInboundHandler(inbound);

        SignalGroupHandler wired = adapter.groupHandler();
        assertNotNull(wired);

        // Drive a group mention through the wired handler — confirms
        // the callback reaches the adapter-built group handler.
        wired.handleReceive(parse("""
                {
                  "envelope": {
                    "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                    "timestamp": 1700000007000,
                    "dataMessage": {
                      "timestamp": 1700000007000,
                      "message": "@bot ping",
                      "groupV2": {"id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ="},
                      "mentions": [
                        {"uuid": "11112222-3333-4444-5555-666677778888", "start": 0, "length": 4}
                      ]
                    }
                  }
                }
                """));
        assertEquals(1, inbound.messages.size(),
                "adapter-built group handler must dispatch inbound through the wired callback");
        assertTrue(inbound.messages.get(0).scope() instanceof ScopeRef.Group);

        // F-live-10: signal-cli 0.14.5 exposes no native per-user
        // membership signal in its receive stream, so the capability
        // MUST be false (spec messaging.md §Required SPI surface —
        // Membership events) and Provider uses the delivery-failure
        // fallback, the SimpleX posture.
        assertFalse(adapter.capabilities().supportsMembershipEvents(),
                "supportsMembershipEvents MUST be false — no native signal on the 0.14.5 wire");
    }

    @Test
    void overflowMentionSpanSkipped_messageStillDispatched() {
        // U-10: a hostile bot-mention entry with start=Integer.MAX_VALUE,
        // length=1 rides alongside a legitimate bot mention. The 32-bit
        // start+length sum wraps negative and (pre-fix) slipped the
        // "> body.length()" guard, so StringBuilder.delete threw and the
        // dispatch-thread catch silently dropped the whole message — a
        // per-message DoS of group functionality. The (long)-widened guard
        // must skip the overflow span and still dispatch the message.
        RecordingInbound inbound = new RecordingInbound();
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, inbound, AdapterMetrics.noop());

        handler.handleReceive(parse("""
                {
                  "envelope": {
                    "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                    "timestamp": 1700000012000,
                    "dataMessage": {
                      "timestamp": 1700000012000,
                      "message": "@bot summarise this",
                      "groupV2": {"id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ="},
                      "mentions": [
                        {"uuid": "11112222-3333-4444-5555-666677778888", "start": 0, "length": 4},
                        {"uuid": "11112222-3333-4444-5555-666677778888", "start": 2147483647, "length": 1}
                      ]
                    }
                  }
                }
                """));

        assertEquals(1, inbound.messages.size(),
                "a hostile overflow mention span MUST NOT drop the whole message");
        assertEquals("summarise this", inbound.messages.get(0).text(),
                "only the valid bot-mention span is stripped; the overflow span is skipped");
    }

    @Test
    void groupSenderDisplayNamePopulatedFromSourceName() {
        // U-21: the inbound Identity's displayName (informational only,
        // D10) is taken from the envelope's sourceName on the group path.
        RecordingInbound inbound = new RecordingInbound();
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, inbound, AdapterMetrics.noop());

        handler.handleReceive(parse("""
                {
                  "envelope": {
                    "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                    "sourceName": "Alice",
                    "timestamp": 1700000013000,
                    "dataMessage": {
                      "timestamp": 1700000013000,
                      "message": "@bot ping",
                      "groupV2": {"id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ="},
                      "mentions": [
                        {"uuid": "11112222-3333-4444-5555-666677778888", "start": 0, "length": 4}
                      ]
                    }
                  }
                }
                """));

        assertEquals(1, inbound.messages.size());
        assertEquals("Alice", inbound.messages.get(0).sender().displayName(),
                "group sender displayName must come from the envelope sourceName");
    }

    @Test
    void malformedGroupIdDroppedWithObservableWarn() {
        // M1-565: a group stanza whose groupId is present as a JSON string
        // but is not valid base64 fails the SignalMessageCodec shape gate.
        // The frame drops (no dispatch) — but observably, unlike an
        // F-live-10-style silent drop: a WARN carrying ONLY the encoded
        // id's LENGTH and the adapterMessageId timestamp token, never the
        // id value itself (D37 — the id names a private group).
        String malformedId = "!!!!not-base64-group-id!!!!";
        RecordingInbound inbound = new RecordingInbound();
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, inbound, AdapterMetrics.noop());
        CapturingLogHandler log = CapturingLogHandler.attach(SignalGroupHandler.class);
        try {
            handler.handleReceive(parse("""
                    {
                      "envelope": {
                        "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                        "timestamp": 1700000009000,
                        "dataMessage": {
                          "timestamp": 1700000009000,
                          "message": "@bot summarise this",
                          "groupInfo": {"groupId": "!!!!not-base64-group-id!!!!"},
                          "mentions": [
                            {"uuid": "11112222-3333-4444-5555-666677778888", "start": 0, "length": 4}
                          ]
                        }
                      }
                    }
                    """));
        } finally {
            log.detach();
        }

        assertEquals(0, inbound.messages.size(),
                "a group id that fails the base64 shape gate must drop, not dispatch");
        String logged = log.formatted();
        assertTrue(logged.contains("WARN"),
                "the shape-gate rejection must be observable at WARN");
        assertTrue(logged.contains("signal-1700000009000"),
                "the WARN must carry the adapterMessageId timestamp token");
        assertTrue(logged.contains(String.valueOf(malformedId.length())),
                "the WARN must carry the encoded id's length");
        assertFalse(logged.contains(malformedId),
                "the WARN must NOT carry the group id value (D37 — it names a private group)");
    }

}
