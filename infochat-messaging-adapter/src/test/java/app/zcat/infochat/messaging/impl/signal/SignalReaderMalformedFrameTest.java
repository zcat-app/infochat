package app.zcat.infochat.messaging.impl.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import app.zcat.infochat.messaging.InboundMessage;

import org.junit.jupiter.api.Test;

/**
 * Pins the M1-184 reader-survival contract: no structurally-malformed
 * inbound frame may kill the {@code signal-jsonrpc-reader} thread —
 * signal-cli stays alive when its stream carries garbage, so a dead
 * reader is a permanently deaf adapter that no restart machinery
 * notices. Also pins the D37 class-name-only discipline of the
 * malformed-frame WARN log.
 */
class SignalReaderMalformedFrameTest {

    private static final Duration TEST_RESPONSE_TIMEOUT = Duration.ofSeconds(2);
    private static final long QUEUE_WAIT_MS = 2_000;
    private static final Duration LOG_WAIT = Duration.ofSeconds(2);
    private static final String SENTINEL = "REDTEAM-SENTINEL-FRAME-BYTES";
    private static final String SOURCE_ACI = "AABBCCDD-1111-2222-3333-444455556666";

    @Test
    void readerSurvivesMalformedFramesAndDeliversSubsequentValidFrame() throws Exception {
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            LinkedBlockingQueue<InboundMessage> delivered = new LinkedBlockingQueue<>();
            client.setInboundHandler(delivered::add);
            client.connect();
            try {
                // Not JSON at all.
                fake.pushRawLine("totally not json {{{ " + SENTINEL);
                // Notification with wrong-typed params — formerly a CCE
                // past handleLine's IllegalArgumentException-only catch,
                // killing the reader.
                fake.pushRawLine(
                        "{\"jsonrpc\":\"2.0\",\"method\":\"receive\",\"params\":\"junk\"}");
                // Wrong-typed envelope inside well-typed params.
                fake.pushRawLine(
                        "{\"jsonrpc\":\"2.0\",\"method\":\"receive\","
                                + "\"params\":{\"envelope\":\"junk\"}}");
                // DM-shaped receive with timestamp absent in both envelope
                // and dataMessage — formerly an NPE.
                fake.pushRawLine(
                        "{\"jsonrpc\":\"2.0\",\"method\":\"receive\",\"params\":{\"envelope\":{"
                                + "\"sourceUuid\":\"" + SOURCE_ACI + "\","
                                + "\"dataMessage\":{\"message\":\"no timestamp\"}}}}");
                // Wrong-typed timestamp in both fields.
                fake.pushRawLine(
                        "{\"jsonrpc\":\"2.0\",\"method\":\"receive\",\"params\":{\"envelope\":{"
                                + "\"sourceUuid\":\"" + SOURCE_ACI + "\",\"timestamp\":\"soon\","
                                + "\"dataMessage\":{\"message\":\"bad ts\",\"timestamp\":\"soon\"}}}}");
                // Well-formed JSON with neither method nor id.
                fake.pushRawLine("{\"jsonrpc\":\"2.0\"}");

                // A valid frame after the garbage MUST still deliver. The
                // reader processes lines in arrival order, so delivery
                // proves every malformed frame above was dropped without
                // killing the reader.
                fake.pushNotification("receive",
                        validReceiveParams("post-garbage", 1700000111000L));
                InboundMessage msg = delivered.poll(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS);
                assertNotNull(msg,
                        "reader loop must survive malformed frames and deliver the next valid frame");
                assertEquals("post-garbage", msg.text());
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void malformedFrameLogCarriesNeitherFrameBytesNorExceptionMessage() throws Exception {
        CapturingLogHandler logCapture = CapturingLogHandler.attach(SignalJsonRpcClient.class);
        try (FakeSignalCli fake = new FakeSignalCli()) {
            SignalJsonRpcClient client = new SignalJsonRpcClient(
                    fake.endpoint(), "+15551111111", new SignalMessageCodec(), TEST_RESPONSE_TIMEOUT);
            client.connect();
            try {
                fake.pushRawLine("totally not json {{{ " + SENTINEL);
                awaitLogContains(logCapture, "ignoring malformed inbound JSON-RPC line", LOG_WAIT);
            } finally {
                client.disconnect();
            }
            String captured = logCapture.formatted();
            assertTrue(captured.contains("IllegalArgumentException"),
                    "D37 log must carry the exception class name; captured: " + captured);
            assertFalse(captured.contains(SENTINEL),
                    "log must not carry the frame bytes; captured: " + captured);
            assertFalse(captured.contains("Malformed JSON-RPC envelope"),
                    "log must not carry the exception's message text; captured: " + captured);
        } finally {
            logCapture.detach();
        }
    }

    private static void awaitLogContains(CapturingLogHandler capture,
                                         String needle,
                                         Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (capture.formatted().contains(needle)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        throw new AssertionError(
                "expected captured log to contain `" + needle + "` within "
                        + timeout + "; captured: " + capture.formatted());
    }

    private static JsonObject validReceiveParams(String body, long timestamp) {
        return Json.createObjectBuilder()
                .add("envelope", Json.createObjectBuilder()
                        .add("source", "+15557654321")
                        .add("sourceUuid", SOURCE_ACI)
                        .add("sourceName", "Alice")
                        .add("sourceDevice", 1)
                        .add("timestamp", timestamp)
                        .add("dataMessage", Json.createObjectBuilder()
                                .add("timestamp", timestamp)
                                .add("message", body)))
                .build();
    }
}
