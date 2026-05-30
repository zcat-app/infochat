package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MembershipEvent;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.ScopeRef;

class SignalGroupHandlerTest {

    private static final String BOT_ACI = "11112222-3333-4444-5555-666677778888";
    private static final String GROUP_V2_ID = "Z3JvdXBJZEJhc2U2NEVuY29kZWQ=";

    @Test
    void mentionByAci_delivered() {
        RecordingInbound inbound = new RecordingInbound();
        RecordingMembership membership = new RecordingMembership();
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, inbound, membership);

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
        assertEquals("@bot summarise this", msg.text());
        assertEquals(0, membership.events.size(), "no membership events for a regular message");
    }

    @Test
    void mentionByDisplayName_ignored() {
        // The dataMessage's mentions array points at a NON-bot ACI even
        // though the body text says "@TheBot". Spec rule: display-name
        // matching is never sufficient — only ACI-anchored mentions
        // cross the adapter boundary. The message must be silently
        // dropped.
        RecordingInbound inbound = new RecordingInbound();
        RecordingMembership membership = new RecordingMembership();
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, inbound, membership);

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
        assertEquals(0, membership.events.size());
    }

    @Test
    void noMention_ignored() {
        // A group message with no mentions array at all — bot is not
        // mentioned. Silent drop per spec ("Group messages arrive only
        // when the bot is @mentioned").
        RecordingInbound inbound = new RecordingInbound();
        RecordingMembership membership = new RecordingMembership();
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, inbound, membership);

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
        assertEquals(0, membership.events.size());
    }

    @Test
    void memberJoinedEvent_surfaced() {
        // signal-cli group update notification carrying memberJoined —
        // mapped to MembershipEvent.UserJoined for each ACI.
        RecordingInbound inbound = new RecordingInbound();
        RecordingMembership membership = new RecordingMembership();
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, inbound, membership);

        JsonObject params = parse("""
                {
                  "envelope": {
                    "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                    "timestamp": 1700000004000,
                    "dataMessage": {
                      "timestamp": 1700000004000,
                      "groupV2": {
                        "id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ=",
                        "memberJoined": ["BBCCDDEE-2222-3333-4444-555566667777"]
                      }
                    }
                  }
                }
                """);

        handler.handleReceive(params);

        assertEquals(0, inbound.messages.size(),
                "group update is not an inbound message");
        assertEquals(1, membership.events.size(), "exactly one membership event expected");
        MembershipEvent event = membership.events.get(0);
        MembershipEvent.UserJoined joined = assertInstanceOf(
                MembershipEvent.UserJoined.class, event,
                "memberJoined ACI maps to MembershipEvent.UserJoined");
        assertEquals(GROUP_V2_ID, joined.adapterGroupId(),
                "membership event carries the signal-cli groupV2.id");
        assertEquals("bbccddee-2222-3333-4444-555566667777", joined.contactId(),
                "joined ACI must be canonicalized to lowercase");
    }

    @Test
    void memberLeftEvent_surfaced() {
        // signal-cli group update notification carrying memberLeft —
        // mapped to MembershipEvent.UserLeft for each ACI.
        RecordingInbound inbound = new RecordingInbound();
        RecordingMembership membership = new RecordingMembership();
        SignalGroupHandler handler = new SignalGroupHandler(BOT_ACI, inbound, membership);

        JsonObject params = parse("""
                {
                  "envelope": {
                    "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                    "timestamp": 1700000005000,
                    "dataMessage": {
                      "timestamp": 1700000005000,
                      "groupV2": {
                        "id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ=",
                        "memberLeft": [
                          {"uuid": "CCDDEEFF-3333-4444-5555-666677778888"}
                        ]
                      }
                    }
                  }
                }
                """);

        handler.handleReceive(params);

        assertEquals(0, inbound.messages.size());
        assertEquals(1, membership.events.size());
        MembershipEvent event = membership.events.get(0);
        MembershipEvent.UserLeft left = assertInstanceOf(
                MembershipEvent.UserLeft.class, event,
                "memberLeft ACI maps to MembershipEvent.UserLeft");
        assertEquals(GROUP_V2_ID, left.adapterGroupId());
        assertEquals("ccddeeff-3333-4444-5555-666677778888", left.contactId(),
                "left ACI must be canonicalized to lowercase");
    }

    @Test
    void adapterGroupHandlerWiresBotAciAndCallbacks() {
        // The SignalAdapter.groupHandler() factory must propagate the
        // bot ACI and the currently-registered inbound/membership
        // callbacks so the group surface delivered through Provider's
        // wiring is anchored against the right identity. Acceptance
        // items 3 + 4: SignalAdapter surfaces the group id and the
        // membership events through this factory.
        java.net.InetSocketAddress endpoint =
                new java.net.InetSocketAddress("127.0.0.1", 0);
        SignalAdapter adapter = new SignalAdapter(
                "/usr/bin/signal-cli",
                "/tmp/signal-data",
                "+15551234567",
                BOT_ACI,
                endpoint);
        RecordingInbound inbound = new RecordingInbound();
        RecordingMembership membership = new RecordingMembership();
        adapter.setInboundHandler(inbound);
        adapter.setMembershipEventHandler(membership);

        SignalGroupHandler wired = adapter.groupHandler();
        assertNotNull(wired);

        // Drive a group mention through the wired handler — confirms
        // both callbacks reach the adapter-built group handler.
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

        // Drive a member-left event through the wired handler too.
        wired.handleReceive(parse("""
                {
                  "envelope": {
                    "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                    "timestamp": 1700000008000,
                    "dataMessage": {
                      "timestamp": 1700000008000,
                      "groupV2": {
                        "id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ=",
                        "memberLeft": ["DDEEFF00-4444-5555-6666-777788889999"]
                      }
                    }
                  }
                }
                """));
        assertEquals(1, membership.events.size(),
                "adapter-built group handler must dispatch membership through the wired callback");
        // Item-5 anchor: the capability flag must stay true after the
        // group wiring (it was true on the skeleton; group support
        // delivery does not regress it).
        assertTrue(adapter.capabilities().supportsMembershipEvents(),
                "supportsMembershipEvents MUST remain true per spec");
    }

    private static JsonObject parse(String json) {
        try (JsonReader r = Json.createReader(new StringReader(json))) {
            return r.readObject();
        }
    }

    private static final class RecordingInbound implements MessagingAdapter.InboundHandler {
        final List<InboundMessage> messages = new ArrayList<>();

        @Override
        public void onMessage(InboundMessage msg) {
            messages.add(msg);
        }
    }

    private static final class RecordingMembership implements MessagingAdapter.MembershipHandler {
        final List<MembershipEvent> events = new ArrayList<>();

        @Override
        public void onEvent(MembershipEvent event) {
            events.add(event);
        }
    }
}
