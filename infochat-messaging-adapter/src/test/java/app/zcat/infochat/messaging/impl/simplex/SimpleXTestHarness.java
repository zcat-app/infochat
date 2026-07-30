package app.zcat.infochat.messaging.impl.simplex;

import app.zcat.infochat.messaging.OutboundRateLimiter;

import java.net.http.HttpClient;
import java.nio.file.Path;

/**
 * Shared SimpleX adapter-over-fake harness for the edit-fallback / chunked-send
 * tests: builds a {@link SimpleXAdapter} pointed at a {@link FakeSimpleXProcess}'s
 * websocket port, and renders a {@code newChatItems} ack frame. Package-private
 * in the simplex test package per the module's fixture convention (cf.
 * {@link FakeSimpleXProcess}).
 */
final class SimpleXTestHarness {

    private SimpleXTestHarness() {
    }

    static SimpleXAdapter newAdapter(FakeSimpleXProcess fake, Path tempDir) {
        // binary/dataDir are never exercised: start() (where cfg.validate()
        // lives) is not called; only wsPort() is read by rebuildWebSocket().
        SimpleXConfig cfg = new SimpleXConfig(
                "/usr/bin/simplex-chat", tempDir.toString(), fake.port());
        return new SimpleXAdapter(
                cfg,
                HttpClient.newHttpClient(),
                msg -> { /* admin notifications unused here */ });
    }

    /**
     * Same adapter, wired to a caller-supplied {@link OutboundRateLimiter} so a
     * test can count {@code acquiredCount()} draws (M1-710). Takes the
     * production reconnect ladder — a pacing test never reconnects.
     */
    static SimpleXAdapter newAdapter(FakeSimpleXProcess fake, Path tempDir,
                                     OutboundRateLimiter outboundRate) {
        SimpleXConfig cfg = new SimpleXConfig(
                "/usr/bin/simplex-chat", tempDir.toString(), fake.port());
        return new SimpleXAdapter(
                cfg,
                HttpClient.newHttpClient(),
                msg -> { /* admin notifications unused here */ },
                SimpleXAdapter.WS_RECONNECT_BACKOFF,
                outboundRate);
    }

    static String ackFrame(String corrId, int i) {
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
}
