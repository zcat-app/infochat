package app.zcat.infochat.messaging.impl.signal;

import static app.zcat.infochat.messaging.impl.signal.SignalTestJson.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

/**
 * Drives group-scope envelopes end-to-end through the adapter's
 * production receive wiring: FakeSignalCli wire bytes →
 * {@code SignalJsonRpcClient} reader → group-notification route →
 * {@code SignalAdapter.groupHandler()} → the handlers Provider
 * registered on the adapter. The wiring under test is exactly what
 * {@code SignalAdapter.start()} performs — the test enters at the
 * package-private {@code attachClient} seam because {@code start()}
 * requires a real signal-cli subprocess.
 */
class SignalGroupEndToEndTest {

    private static final String BOT_ACI = "11112222-3333-4444-5555-666677778888";
    private static final String GROUP_V2_ID = "Z3JvdXBJZEJhc2U2NEVuY29kZWQ=";
    private static final Duration TEST_RESPONSE_TIMEOUT = Duration.ofSeconds(5);
    private static final long QUEUE_WAIT_MS = 2_000;

    @Test
    void groupEnvelopesReachAdapterHandlers() throws Exception {
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalAdapter adapter = new SignalAdapter(
                    "/usr/bin/signal-cli",
                    "/tmp/signal-data",
                    "+15551111111",
                    fake.endpoint());
            adapter.adoptBotAci(BOT_ACI);
            LinkedBlockingQueue<InboundMessage> inbound = new LinkedBlockingQueue<>();
            adapter.setInboundHandler(inbound::add);
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            client.connect();
            try {
                adapter.attachClient(client);

                // Group mention in the real signal-cli 0.14.5 wire shape
                // (groupInfo{groupId}, F-live-10 — the exact frame class
                // that silently dropped live; shape-faithful
                // reconstruction with synthetic identifiers) →
                // InboundMessage with Group scope.
                fake.pushNotification("receive", parse("""
                        {
                          "envelope": {
                            "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                            "sourceName": "LiveUser",
                            "timestamp": 1700000001000,
                            "dataMessage": {
                              "timestamp": 1700000001000,
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
                InboundMessage msg = inbound.poll(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS);
                assertNotNull(msg,
                        "group mention must reach the adapter's InboundHandler within " + QUEUE_WAIT_MS + " ms");
                ScopeRef.Group scope = assertInstanceOf(ScopeRef.Group.class, msg.scope());
                assertEquals(GROUP_V2_ID, scope.adapterGroupId());
                assertEquals("aabbccdd-1111-2222-3333-444455556666", msg.sender().contactId(),
                        "sender ACI must be canonicalized to lowercase");
                assertEquals("summarise this", msg.text(),
                        "bot mention span must be stripped before delivery");

                // The groupV2 spelling still dispatches through the same
                // route (route symmetry with the DM exclusion guard).
                fake.pushNotification("receive", parse("""
                        {
                          "envelope": {
                            "sourceUuid": "AABBCCDD-1111-2222-3333-444455556666",
                            "timestamp": 1700000002000,
                            "dataMessage": {
                              "timestamp": 1700000002000,
                              "message": "@bot ping",
                              "groupV2": {"id": "Z3JvdXBJZEJhc2U2NEVuY29kZWQ="},
                              "mentions": [
                                {"uuid": "11112222-3333-4444-5555-666677778888", "start": 0, "length": 4}
                              ]
                            }
                          }
                        }
                        """));
                InboundMessage viaGroupV2 = inbound.poll(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS);
                assertNotNull(viaGroupV2,
                        "a groupV2-shaped mention must still reach the InboundHandler");
                assertEquals(GROUP_V2_ID,
                        assertInstanceOf(ScopeRef.Group.class, viaGroupV2.scope()).adapterGroupId());
                assertEquals("ping", viaGroupV2.text());
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void groupRouteHandlerExceptionDoesNotKillReader() throws Exception {
        // Reader-survival invariant for the group route, mirroring the
        // DM path's handlerExceptionDoesNotKillReader: a Provider-side
        // handler that throws on a group mention must not kill the
        // signal-jsonrpc-reader thread; subsequent group notifications
        // are still delivered.
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalAdapter adapter = new SignalAdapter(
                    "/usr/bin/signal-cli",
                    "/tmp/signal-data",
                    "+15551111111",
                    fake.endpoint());
            adapter.adoptBotAci(BOT_ACI);
            LinkedBlockingQueue<InboundMessage> delivered = new LinkedBlockingQueue<>();
            AtomicBoolean firstThrown = new AtomicBoolean(false);
            adapter.setInboundHandler(msg -> {
                if (firstThrown.compareAndSet(false, true)) {
                    throw new RuntimeException("boom from group handler");
                }
                delivered.add(msg);
            });
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            client.connect();
            try {
                adapter.attachClient(client);

                fake.pushNotification("receive", groupMention("first", 1700000003000L));
                fake.pushNotification("receive", groupMention("second", 1700000004000L));

                InboundMessage msg = delivered.poll(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS);
                assertNotNull(msg,
                        "reader must survive a group-route RuntimeException and deliver subsequent notifications");
                assertEquals("second", msg.text());
            } finally {
                client.disconnect();
            }
        }
    }

    private static JsonObject groupMention(String body, long timestamp) {
        // The mention span [0,4) covers the "@bot" prefix, so the
        // handler's strip delivers exactly the given body — keeping the
        // fixture self-consistent with the protocol span it declares.
        return Json.createObjectBuilder()
                .add("envelope", Json.createObjectBuilder()
                        .add("sourceUuid", "AABBCCDD-1111-2222-3333-444455556666")
                        .add("timestamp", timestamp)
                        .add("dataMessage", Json.createObjectBuilder()
                                .add("timestamp", timestamp)
                                .add("message", "@bot " + body)
                                .add("groupV2", Json.createObjectBuilder()
                                        .add("id", GROUP_V2_ID))
                                .add("mentions", Json.createArrayBuilder()
                                        .add(Json.createObjectBuilder()
                                                .add("uuid", BOT_ACI)
                                                .add("start", 0)
                                                .add("length", 4)))))
                .build();
    }

}
