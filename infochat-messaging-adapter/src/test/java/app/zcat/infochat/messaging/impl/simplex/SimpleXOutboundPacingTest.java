package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.OutboundRateLimiter;
import app.zcat.infochat.messaging.ScopeRef;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the §6.3.6 "one token per wire frame" contract on the SimpleX
 * outbound path by counting {@link OutboundRateLimiter#acquiredCount()}
 * draws — the SimpleX half of the parity pair whose Signal half is
 * {@code SignalEditFallbackTest.fallenBackUpdateDrawsTwoTokens()} (M1-710).
 * The 2026-07-27 PIT sweep found every {@code outboundRate.acquire()} in
 * {@link SimpleXAdapter} SURVIVING deletion: seven tests executed
 * {@code transmitChunk} and none of them noticed when the pacing
 * disappeared, because the adapter built its own limiter and no test could
 * reach it. Each assertion below is verified to go red when its own
 * {@code acquire()} call is deleted.
 *
 * <p>Harness shape is {@link SimpleXEditFallbackTest}'s: the adapter is
 * wired through the package-private {@code rebuildWebSocket()} seam against
 * a {@link FakeSimpleXProcess}, and a responder thread acks every command
 * frame so the blocking {@code send} / {@code update} /
 * {@code finalizeMessage} calls complete.</p>
 */
class SimpleXOutboundPacingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Duration POLL = Duration.ofMillis(200);
    private static final int CAP = SimpleXMessageCodec.MAX_OUTBOUND_TEXT_BYTES;

    @TempDir
    Path tempDir;

    @Test
    void multiChunkSendDrawsOneTokenPerChunk() throws Exception {
        String text = overCapText();
        int expectedChunks = SimpleXOutboundChunker.chunk(text).size();
        assertTrue(expectedChunks >= 2, "test input must require multiple chunks");

        OutboundRateLimiter limiter = countingLimiter();
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXAdapter adapter = SimpleXTestHarness.newAdapter(fake, tempDir, limiter);
            adapter.rebuildWebSocket();
            AtomicBoolean done = new AtomicBoolean();
            Thread responder = startResponder(fake, done);
            try {
                fake.awaitClient(WAIT);
                adapter.send(outbound(text));
                done.set(true);
                assertTrue(responder.join(WAIT), "responder did not drain within " + WAIT);

                // One token per transmitted chunk, not one per send() call: a
                // chunked digest emits `expectedChunks` wire frames, so pacing
                // it per SPI call would transmit at expectedChunks-times the
                // declared cap. The SimpleX analogue of the Signal twin's
                // "a fallen-back update draws two tokens".
                assertEquals(expectedChunks, limiter.acquiredCount(),
                        "a multi-chunk send must draw one token per transmitted chunk");
            } finally {
                adapter.close();
            }
        }
    }

    @Test
    void inPlaceUpdateDrawsOneTokenOnTopOfTheSend() throws Exception {
        OutboundRateLimiter limiter = countingLimiter();
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXAdapter adapter = SimpleXTestHarness.newAdapter(fake, tempDir, limiter);
            adapter.rebuildWebSocket();
            AtomicBoolean done = new AtomicBoolean();
            Thread responder = startResponder(fake, done);
            try {
                fake.awaitClient(WAIT);
                MessageHandle handle = adapter.send(outbound("working…"));
                assertEquals(1L, limiter.acquiredCount(),
                        "the single-chunk placeholder send draws exactly one token");

                // The edit is acked, so this exercises the in-place /_update
                // frame — NOT the fresh-send fallback, whose token would come
                // from transmitChunk and mask a missing draw here.
                adapter.update(handle, "scanning posts…");
                done.set(true);
                assertTrue(responder.join(WAIT), "responder did not drain within " + WAIT);

                assertEquals(2L, limiter.acquiredCount(),
                        "an in-place update draws its own token on top of the send's");
            } finally {
                adapter.close();
            }
        }
    }

    @Test
    void finalizeMessageDrawsOneTokenOnTopOfTheSend() throws Exception {
        OutboundRateLimiter limiter = countingLimiter();
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXAdapter adapter = SimpleXTestHarness.newAdapter(fake, tempDir, limiter);
            adapter.rebuildWebSocket();
            AtomicBoolean done = new AtomicBoolean();
            Thread responder = startResponder(fake, done);
            try {
                fake.awaitClient(WAIT);
                MessageHandle handle = adapter.send(outbound("working…"));
                assertEquals(1L, limiter.acquiredCount(),
                        "the single-chunk placeholder send draws exactly one token");

                // Within-cap terminal edit: acked in place, so the draw counted
                // is finalizeMessage's own and not a fallback chunk's.
                adapter.finalizeMessage(handle, "done.");
                done.set(true);
                assertTrue(responder.join(WAIT), "responder did not drain within " + WAIT);

                assertEquals(2L, limiter.acquiredCount(),
                        "the terminal edit draws its own token on top of the send's");
            } finally {
                adapter.close();
            }
        }
    }

    // -- harness -------------------------------------------------------------

    /**
     * A cap high enough that the bucket's opening burst absorbs every draw:
     * this test counts tokens, it does not exercise real pacing sleeps (the
     * production cap of {@code CAPABILITIES.maxSendsPerSecond} would park the
     * multi-chunk send for whole seconds). Mirrors the Signal twin's limiter.
     */
    private static OutboundRateLimiter countingLimiter() {
        return new OutboundRateLimiter(1_000_000, Clock.systemUTC());
    }

    /**
     * A responder vthread that acks every command frame. It polls on a short
     * timeout and exits once {@code done} is set and no further frame arrives,
     * so the test never waits a full {@link #WAIT} at teardown.
     */
    private Thread startResponder(FakeSimpleXProcess fake, AtomicBoolean done) {
        AtomicInteger itemSeq = new AtomicInteger();
        return Thread.ofVirtual().name("pacing-responder").start(() -> {
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
                    fake.sendFrame(SimpleXTestHarness.ackFrame(corrId, itemSeq.getAndIncrement()));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    /** Prose long enough for multiple chunks — a plausible over-cap digest. */
    private static String overCapText() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; utf8Length(text.toString()) <= 2 * CAP + 200; i++) {
            text.append("digest item ").append(i)
                    .append(": a one-line summary of a post, with a tag list and a")
                    .append(" bare source URL https://example.org/post/").append(i)
                    .append('\n');
        }
        return text.toString();
    }

    private static OutboundMessage outbound(String text) {
        return new OutboundMessage(
                new ScopeRef.Dm("alice-queue-addr"), text, Instant.now(), "corr-pacing");
    }

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }
}
