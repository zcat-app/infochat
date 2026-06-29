package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import app.zcat.infochat.messaging.metrics.AdapterMetrics;

/**
 * Pins the M1-224 DoS bound for the SimpleX transport: the inbound
 * dispatch executor's work queue is bounded, so a flood that arrives
 * faster than the single dispatch thread can drain it cannot grow the
 * queue without bound. Once the queue is full the newest delivery is
 * dropped and counted rather than retained — the queue depth never
 * exceeds the cap, and the dropped messages never reach the consumer.
 */
class SimpleXInboundQueueBoundTest {

    private static final Duration WAIT = Duration.ofSeconds(2);

    @Test
    void floodIsBoundedAndOverflowDropsAreCounted() throws Exception {
        int capacity = 4;
        int fed = 30;
        // One delivery occupies the (blocked) worker thread, `capacity`
        // fill the queue, the rest overflow and are dropped.
        int expectedDropped = fed - capacity - 1;
        int expectedDelivered = capacity + 1;

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            CountDownLatch release = new CountDownLatch(1);
            LinkedBlockingQueue<String> delivered = new LinkedBlockingQueue<>();
            SimpleXWebSocketClient client = new SimpleXWebSocketClient(
                    fake.wsUri(),
                    HttpClient.newHttpClient(),
                    msg -> {
                        // Block the single dispatch thread so the queue
                        // fills and the flood overflows deterministically.
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        delivered.add(msg.text());
                    },
                    gc -> { /* no group candidate in this test */ },
                    capacity);
            client.bindMetrics(new AdapterMetrics(registry));
            client.start();
            try {
                fake.awaitClient(WAIT);
                for (int i = 0; i < fed; i++) {
                    fake.sendFrame(inboundFrame("flood-" + i, "inbound-" + i));
                }

                awaitDropCount(client, expectedDropped);
                assertEquals(expectedDropped, client.droppedInboundCount(),
                        "every inbound past (worker + queue capacity) must be dropped and counted");
                assertTrue(client.dispatchQueueDepth() <= capacity,
                        "the dispatch queue must never grow past its cap under a flood; was "
                                + client.dispatchQueueDepth());

                // Releasing the worker drains exactly the retained messages
                // (worker + queue), proving the drops were not buffered.
                release.countDown();
                for (int i = 0; i < expectedDelivered; i++) {
                    assertNotNullPoll(delivered, "retained message " + i + " must be delivered");
                }
                assertNull(delivered.poll(200, TimeUnit.MILLISECONDS),
                        "dropped messages must never reach the consumer");
                assertEquals(expectedDropped, client.droppedInboundCount(),
                        "the drop count must be stable after the flood drains");
                // §6.3.7 overflow drops route through the shared inbound-drop
                // counter with reason=queue_full and scope_kind=unknown (the
                // drop fires before the frame is decoded into a scope).
                assertEquals((double) expectedDropped, registry.get("adapter.inbound.dropped")
                                .tags("adapter", "simplex", "scope_kind", "unknown", "reason", "queue_full")
                                .counter().count(),
                        "every queue-overflow drop must increment adapter.inbound.dropped{reason=queue_full}");
            } finally {
                release.countDown();
                client.close();
            }
        }
    }

    private static void awaitDropCount(SimpleXWebSocketClient client, int target)
            throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (client.droppedInboundCount() < target && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static void assertNotNullPoll(LinkedBlockingQueue<String> queue, String message)
            throws InterruptedException {
        assertTrue(queue.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS) != null, message);
    }

    private static String inboundFrame(String text, String itemId) {
        return """
                {
                  "resp": {
                    "type": "newChatItem",
                    "chatItem": {
                      "chatInfo": {
                        "type": "direct",
                        "contact": {
                          "contactId": "alice-queue-addr",
                          "localDisplayName": "Alice"
                        }
                      },
                      "chatItem": {
                        "meta": {"itemId": "%s"},
                        "content": {
                          "msgContent": {
                            "type": "text",
                            "text": "%s"
                          }
                        }
                      }
                    }
                  }
                }
                """.formatted(itemId, text);
    }
}
