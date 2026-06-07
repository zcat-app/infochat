package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.time.Duration;
import java.time.Instant;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

import org.junit.jupiter.api.Test;

/**
 * Pins the LRU bound on {@link SignalJsonRpcClient}'s open-handle map
 * (M6): fire-once sends — replies that are never finalized, the common
 * case — previously accumulated for the life of the connection because
 * entries were removed only by finalizeHandle or the wholesale clear on
 * reconnect. The cap mirrors SimpleXAdapter's MAX_TRACKED_HANDLES
 * trade-off: an evicted handle behaves exactly like an unknown one
 * (PERMANENT), the same outcome a Provider restart produces.
 */
class SignalHandleMapBoundTest {

    private static final Duration TEST_RESPONSE_TIMEOUT = Duration.ofSeconds(5);
    private static final long QUEUE_WAIT_MS = 2_000;

    @Test
    void handleMapStaysBoundedUnderFireOnceSends() throws Exception {
        // Acceptance item 3: more fire-once (never-finalized) sends than the
        // cap; openHandleCount() must never exceed it.
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(),
                    TEST_RESPONSE_TIMEOUT);
            client.connect();
            try {
                int overCap = SignalJsonRpcClient.MAX_TRACKED_HANDLES + 1;
                sendFireOnce(client, fake, overCap);
                assertEquals(SignalJsonRpcClient.MAX_TRACKED_HANDLES,
                        client.openHandleCount(),
                        "after cap+1 fire-once sends the map saturates at the cap");
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void evictedHandleBehavesLikeUnknownHandle() throws Exception {
        // Acceptance item 4: the LRU-evicted handle (the first send — no
        // access ever bumped its recency) must produce the documented
        // unknown-handle outcome, PERMANENT, on both update and finalize.
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(),
                    TEST_RESPONSE_TIMEOUT);
            client.connect();
            try {
                int overCap = SignalJsonRpcClient.MAX_TRACKED_HANDLES + 1;
                MessageHandle evicted = sendFireOnce(client, fake, overCap);

                MessagingException onUpdate = assertThrows(MessagingException.class,
                        () -> client.update(evicted, "post-eviction update"),
                        "update on an evicted handle must throw");
                assertEquals(FailureCategory.PERMANENT, onUpdate.category(),
                        "evicted handle must be PERMANENT on update, like an unknown handle");

                MessagingException onFinalize = assertThrows(MessagingException.class,
                        () -> client.finalizeHandle(evicted, "post-eviction finalize"),
                        "finalize on an evicted handle must throw");
                assertEquals(FailureCategory.PERMANENT, onFinalize.category(),
                        "evicted handle must be PERMANENT on finalize, like an unknown handle");
            } finally {
                client.disconnect();
            }
        }
    }

    /**
     * Drive {@code count} sequential fire-once sends (no update, no
     * finalize) with a responder thread answering each JSON-RPC send
     * request, asserting after every send that the open-handle count
     * never exceeds the cap. Returns the FIRST send's handle — with
     * pure inserts and no recency-bumping accesses it is the eldest
     * entry, i.e. the one the LRU evicts when the cap is crossed.
     */
    private static MessageHandle sendFireOnce(SignalJsonRpcClient client,
                                              FakeSignalCli fake,
                                              int count) throws Exception {
        Thread responder = new Thread(() -> {
            try {
                for (int i = 0; i < count; i++) {
                    JsonObject request = fake.nextOutbound(QUEUE_WAIT_MS);
                    fake.respondSuccess(request.getString("id"),
                            Json.createObjectBuilder()
                                    .add("timestamp", 1_700_000_000_000L + i)
                                    .add("results", Json.createArrayBuilder())
                                    .build());
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "fire-once-responder");
        responder.setDaemon(true);
        responder.start();

        MessageHandle firstHandle = null;
        for (int i = 0; i < count; i++) {
            OutboundMessage out = new OutboundMessage(
                    new ScopeRef.Dm("aabbccdd-1111-2222-3333-444455556666"),
                    "fire-once " + i,
                    Instant.now(),
                    "corr-" + i);
            MessageHandle handle = client.send(out);
            if (firstHandle == null) {
                firstHandle = handle;
            }
            assertTrue(client.openHandleCount() <= SignalJsonRpcClient.MAX_TRACKED_HANDLES,
                    "openHandleCount must never exceed MAX_TRACKED_HANDLES; send #" + i);
        }
        responder.join(QUEUE_WAIT_MS);
        assertNotNull(firstHandle, "at least one send must have completed");
        return firstHandle;
    }
}
