package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.metrics.AdapterMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the §6.3.8/§6.4.5 edit-failure fallback <em>metrics</em> the
 * behavioral twin {@link SimpleXEditFallbackTest} (M1-285) deferred: a
 * simulated {@code CEInvalidChatItemUpdate} edit rejection must
 * increment {@code adapter.outbound.update.total{outcome=fallback_send}}
 * and {@code adapter.outbound.update.fail{reason=unknown}} —
 * {@code unknown} because the single SimpleX rejection tag covers "item
 * too old, deleted, or not the bot's own message" (§6.4.5) without
 * discriminating. Same harness shape as the twin: the adapter is wired
 * through the package-private {@code rebuildWebSocket()} seam against a
 * {@link FakeSimpleXProcess}, with a responder thread bouncing every
 * {@code /_update} with the rejection frame.
 */
class SimpleXEditFallbackMetricsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Duration POLL = Duration.ofMillis(200);

    @TempDir
    Path tempDir;

    @Test
    void rejectedUpdateIncrementsFallbackSendAndPerReasonFailCounters() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXAdapter adapter = newAdapter(fake);
            adapter.bindMetrics(new AdapterMetrics(registry));
            adapter.rebuildWebSocket();
            AtomicBoolean done = new AtomicBoolean();
            Thread responder = startRejectingResponder(fake, done);
            try {
                fake.awaitClient(WAIT);
                MessageHandle handle = adapter.send(outbound("working…"));

                // The edit reaches the transport, is rejected with
                // CEInvalidChatItemUpdate, and falls back to a fresh send.
                assertDoesNotThrow(() -> adapter.update(handle, "update one"));

                assertEquals(1.0, fallbackSendCount(registry),
                        "the rejected edit must count one fallback_send outcome");
                assertEquals(1.0, unknownFailCount(registry),
                        "the rejected edit must count one update.fail{reason=unknown}");

                // Subsequent updates on the fallen-back handle fresh-send
                // without re-attempting the edit: each is another
                // fallback_send outcome, but no new failing edit occurred,
                // so the per-reason fail counter must not move.
                assertDoesNotThrow(() -> adapter.update(handle, "update two"));
                assertDoesNotThrow(() -> adapter.update(handle, "update three"));

                assertEquals(3.0, fallbackSendCount(registry),
                        "every fallback fresh-send counts an outcome=fallback_send");
                assertEquals(1.0, unknownFailCount(registry),
                        "update.fail counts failing edits, not the short-circuited repeats");

                done.set(true);
                assertTrue(responder.join(WAIT), "responder did not drain within " + WAIT);
            } finally {
                adapter.close();
            }
        }
    }

    private static double fallbackSendCount(SimpleMeterRegistry registry) {
        return registry.get("adapter.outbound.update.total")
                .tags("adapter", "simplex", "scope_kind", "dm", "outcome", "fallback_send")
                .counter().count();
    }

    private static double unknownFailCount(SimpleMeterRegistry registry) {
        return registry.get("adapter.outbound.update.fail")
                .tags("adapter", "simplex", "reason", "unknown")
                .counter().count();
    }

    // -- harness (mirrors SimpleXEditFallbackTest) -----------------------------

    private Thread startRejectingResponder(FakeSimpleXProcess fake, AtomicBoolean done) {
        AtomicInteger itemSeq = new AtomicInteger();
        return Thread.ofVirtual().name("fallback-metrics-responder").start(() -> {
            while (true) {
                String envelope;
                try {
                    envelope = fake.awaitFrame(POLL);
                } catch (IllegalStateException timeout) {
                    if (done.get()) {
                        return;
                    }
                    continue;
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    String corrId = MAPPER.readTree(envelope).get("corrId").asText();
                    String cmd = MAPPER.readTree(envelope).get("cmd").asText();
                    if (cmd.startsWith("/_update")) {
                        fake.sendFrame(updateRejectFrame(corrId));
                    } else {
                        fake.sendFrame(ackFrame(corrId, itemSeq.getAndIncrement()));
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    private SimpleXAdapter newAdapter(FakeSimpleXProcess fake) {
        // binary/dataDir are never exercised: start() (where cfg.validate()
        // lives) is not called; only wsPort() is read by rebuildWebSocket().
        SimpleXConfig cfg = new SimpleXConfig(
                "/usr/bin/simplex-chat", tempDir.toString(), fake.port());
        return new SimpleXAdapter(
                cfg,
                HttpClient.newHttpClient(),
                msg -> { /* admin notifications unused here */ },
                new SimpleXIdentity("bot-queue-addr"));
    }

    private static OutboundMessage outbound(String text) {
        return new OutboundMessage(
                new ScopeRef.Dm("alice-queue-addr"), text, Instant.now(), "corr-progress");
    }

    private static String ackFrame(String corrId, int i) {
        return """
                {
                  "corrId": "%s",
                  "resp": {
                    "type": "newChatItems",
                    "chatItems": {"itemId": "item-%d"}
                  }
                }
                """.formatted(corrId, i);
    }

    /** A CEInvalidChatItemUpdate rejection — PERMANENT per the codec's classifier. */
    private static String updateRejectFrame(String corrId) {
        return """
                {
                  "corrId": "%s",
                  "resp": {
                    "type": "chatItemUpdateError",
                    "chatError": {"errorType": "CEInvalidChatItemUpdate"}
                  }
                }
                """.formatted(corrId);
    }
}
