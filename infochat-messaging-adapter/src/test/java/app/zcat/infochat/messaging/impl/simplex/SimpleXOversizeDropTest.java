package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.metrics.AdapterMetrics;

/**
 * Pins the §6.3.10 SimpleX oversize-drop observability (M1-358 acceptance
 * items 1, 2, 4): an inbound text over the transport size cap is dropped
 * at the adapter (never delivered), increments
 * {@code adapter.inbound.dropped{scope_kind=dm, reason=oversize}}, and is
 * logged at WARN with the redacted sender and the adapterMessageId — but
 * never the message body (D37). The drop stays silent at the boundary (no
 * reply), matching the queue-overflow shed.
 */
class SimpleXOversizeDropTest {

    private static final Duration WAIT = Duration.ofSeconds(2);
    private static final String OVERSIZE_TEXT =
            "a".repeat(SimpleXMessageCodec.MAX_INBOUND_TEXT_BYTES + 1);

    @Test
    void oversizeInboundIsCountedAndWarnedNotDelivered() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LinkedBlockingQueue<InboundMessage> delivered = new LinkedBlockingQueue<>();
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXWebSocketClient client = new SimpleXWebSocketClient(
                    fake.wsUri(), HttpClient.newHttpClient(), delivered::add, gc -> { });
            client.bindMetrics(new AdapterMetrics(registry));
            CapturingLogHandler log = CapturingLogHandler.attach(SimpleXWebSocketClient.class);
            client.start();
            try {
                fake.awaitClient(WAIT);
                fake.sendFrame(oversizeFrame());
                awaitOversizeCount(registry);

                assertEquals(1.0, registry.get("adapter.inbound.dropped")
                                .tags("adapter", "simplex", "scope_kind", "dm", "reason", "oversize")
                                .counter().count(),
                        "an oversize inbound must increment adapter.inbound.dropped{reason=oversize}");
                assertNull(delivered.poll(200, TimeUnit.MILLISECONDS),
                        "an oversize inbound must never reach the consumer");
                String logged = log.formatted();
                assertTrue(logged.contains("WARN"), "the oversize drop must log at WARN");
                assertTrue(logged.contains("contact#"), "the WARN must carry the redacted sender");
                assertTrue(logged.contains("msg-big"), "the WARN must carry the adapterMessageId");
                assertFalse(logged.contains(OVERSIZE_TEXT),
                        "the WARN must NOT carry the message body (D37)");
            } finally {
                log.detach();
                client.close();
            }
        }
    }

    private static void awaitOversizeCount(SimpleMeterRegistry registry) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            var counter = registry.find("adapter.inbound.dropped")
                    .tags("adapter", "simplex", "scope_kind", "dm", "reason", "oversize").counter();
            if (counter != null && counter.count() >= 1.0) {
                return;
            }
            Thread.sleep(10);
        }
    }

    private static String oversizeFrame() {
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
                        "meta": {"itemId": "msg-big"},
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
                """.formatted(OVERSIZE_TEXT);
    }
}
