package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import app.zcat.infochat.messaging.InboundMessage;

import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link SignalAdapter#start()}'s attach-before-connect ordering:
 * signal-cli can flush queued envelopes the moment the JSON-RPC
 * connection opens, and an envelope delivered immediately on connect
 * must reach the registered inbound handler — never the client's
 * "no InboundHandler set" drop path. Driven through the
 * package-private attachClient/connectClient seams in the exact order
 * {@code start()} runs them ({@code start()} itself requires a real
 * signal-cli binary).
 */
class SignalStartRaceTest {

    private static final Duration TEST_RESPONSE_TIMEOUT = Duration.ofSeconds(5);
    private static final long WAIT_MS = 2_000;

    @Test
    void envelopeFlushedAtConnectTimeIsDelivered() throws Exception {
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalAdapter adapter = new SignalAdapter(
                    "/bin/true", "/tmp", "+15551111111",
                    "11112222-3333-4444-5555-666677778888", fake.endpoint());
            LinkedBlockingQueue<InboundMessage> delivered = new LinkedBlockingQueue<>();
            // Registry wiring order: the handler is registered on the
            // adapter before the transport starts.
            adapter.setInboundHandler(delivered::add);
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(),
                    TEST_RESPONSE_TIMEOUT);
            SignalSubprocess subprocess = new SignalSubprocess(
                    new ProcessBuilder("/bin/sh", "-c", "sleep 30"),
                    fake.endpoint(),
                    SignalSubprocess.BackoffPolicy.laptopDefault(),
                    1);
            // The fake flushes a DM envelope the instant it has an accepted
            // connection (pushNotification blocks until then) — simulating
            // signal-cli's queued-envelope flush at connect time.
            Thread flusher = new Thread(() -> {
                try {
                    fake.pushNotification("receive", receiveParams());
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }, "connect-time-flusher");
            flusher.start();
            try {
                // Production order from start(): attach BEFORE connect.
                adapter.attachClient(client);
                adapter.connectClient(client, subprocess);
                InboundMessage msg = delivered.poll(WAIT_MS, TimeUnit.MILLISECONDS);
                assertNotNull(msg,
                        "an envelope flushed at connect time must reach the handler,"
                                + " not the 'no InboundHandler set' drop path");
                assertEquals("flushed at connect", msg.text());
            } finally {
                flusher.join(WAIT_MS);
                client.disconnect();
                subprocess.stop();
            }
        }
    }

    private static JsonObject receiveParams() {
        return Json.createObjectBuilder()
                .add("envelope", Json.createObjectBuilder()
                        .add("source", "+15557654321")
                        .add("sourceUuid", "aabbccdd-1111-2222-3333-444455556666")
                        .add("sourceName", "Alice")
                        .add("sourceDevice", 1)
                        .add("timestamp", 1700000001000L)
                        .add("dataMessage", Json.createObjectBuilder()
                                .add("timestamp", 1700000001000L)
                                .add("message", "flushed at connect")))
                .build();
    }
}
