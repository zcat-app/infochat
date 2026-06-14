package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import app.zcat.infochat.messaging.metrics.AdapterMetrics;

/**
 * Pins the M1-224 DoS bound for the Signal transport: the inbound
 * dispatch executor's work queue is bounded, so a flood that arrives
 * faster than the single dispatch thread can drain it cannot grow the
 * queue without bound. Once the queue is full the newest notification is
 * dropped and counted rather than retained — the queue depth never
 * exceeds the cap, and the dropped notifications never reach the handler.
 */
class SignalInboundQueueBoundTest {

    private static final Duration TEST_RESPONSE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration WAIT = Duration.ofSeconds(2);

    @Test
    void floodIsBoundedAndOverflowDropsAreCounted() throws Exception {
        int capacity = 4;
        int fed = 30;
        // One notification occupies the (blocked) worker thread, `capacity`
        // fill the queue, the rest overflow and are dropped.
        int expectedDropped = fed - capacity - 1;
        int expectedDelivered = capacity + 1;

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(),
                    TEST_RESPONSE_TIMEOUT, () -> { }, capacity);
            client.bindMetrics(new AdapterMetrics(registry));
            CountDownLatch release = new CountDownLatch(1);
            LinkedBlockingQueue<String> delivered = new LinkedBlockingQueue<>();
            client.setInboundHandler(msg -> {
                // Block the single dispatch thread so the queue fills and
                // the flood overflows deterministically.
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                delivered.add(msg.text());
            });
            client.connect();
            try {
                for (int i = 0; i < fed; i++) {
                    fake.pushNotification("receive",
                            receiveParams("flood-" + i, 1700000000000L + i));
                }

                awaitDropCount(client, expectedDropped);
                assertEquals(expectedDropped, client.droppedInboundCount(),
                        "every inbound past (worker + queue capacity) must be dropped and counted");
                assertTrue(client.dispatchQueueDepth() <= capacity,
                        "the dispatch queue must never grow past its cap under a flood; was "
                                + client.dispatchQueueDepth());

                // Releasing the worker drains exactly the retained
                // notifications (worker + queue), proving the drops were not
                // buffered.
                release.countDown();
                for (int i = 0; i < expectedDelivered; i++) {
                    assertNotNull(delivered.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS),
                            "retained notification " + i + " must be delivered");
                }
                assertNull(delivered.poll(200, TimeUnit.MILLISECONDS),
                        "dropped notifications must never reach the handler");
                assertEquals(expectedDropped, client.droppedInboundCount(),
                        "the drop count must be stable after the flood drains");
                // §6.3.7 overflow drops route through the shared inbound-drop
                // counter with reason=queue_full and scope_kind=unknown (the
                // drop fires before the notification is decoded into a scope).
                assertEquals((double) expectedDropped, registry.get("adapter.inbound.dropped")
                                .tags("adapter", "signal", "scope_kind", "unknown", "reason", "queue_full")
                                .counter().count(),
                        "every queue-overflow drop must increment adapter.inbound.dropped{reason=queue_full}");
            } finally {
                release.countDown();
                client.disconnect();
            }
        }
    }

    private static void awaitDropCount(SignalJsonRpcClient client, int target)
            throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (client.droppedInboundCount() < target && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static JsonObject receiveParams(String body, long timestamp) {
        return Json.createObjectBuilder()
                .add("envelope", Json.createObjectBuilder()
                        .add("source", "+15557654321")
                        .add("sourceUuid", "AABBCCDD-1111-2222-3333-444455556666")
                        .add("sourceName", "Alice")
                        .add("sourceDevice", 1)
                        .add("timestamp", timestamp)
                        .add("dataMessage", Json.createObjectBuilder()
                                .add("timestamp", timestamp)
                                .add("message", body)))
                .build();
    }
}
