package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the M1-285 edit-failure fallback at the SimpleX adapter SPI surface:
 * an unrecoverable {@code update}/{@code finalizeMessage} (the encoder's
 * over-cap PERMANENT, or a {@code CEInvalidChatItemUpdate} edit rejection)
 * no longer freezes the placeholder — the adapter falls back to a fresh
 * {@code send} carrying the original body, chunked through
 * {@link SimpleXOutboundChunker} so an over-cap final body still reaches the
 * reader in full (design 06-messaging.md §6.3.8 / §6.4.5). Same harness
 * shape as {@link SimpleXAdapterChunkedSendTest}: the adapter is wired
 * through the package-private {@code rebuildWebSocket()} seam against a
 * {@link FakeSimpleXProcess}, and a responder thread answers every command
 * frame so the blocking {@code send}/{@code update}/{@code finalizeMessage}
 * calls complete.
 */
class SimpleXEditFallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Duration POLL = Duration.ofMillis(200);
    private static final int CAP = SimpleXMessageCodec.MAX_OUTBOUND_TEXT_BYTES;

    @TempDir
    Path tempDir;

    @Test
    void overCapFinalizeFallsBackToChunkedFreshSend() throws Exception {
        String finalBody = overCapText();
        assertTrue(utf8Length(finalBody) > CAP, "test input must exceed the outbound cap");

        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXAdapter adapter = newAdapter(fake);
            adapter.rebuildWebSocket();
            List<String> frames = Collections.synchronizedList(new ArrayList<>());
            AtomicBoolean done = new AtomicBoolean();
            Thread responder = startResponder(fake, frames, done, /* rejectUpdates */ false);
            try {
                fake.awaitClient(WAIT);
                MessageHandle handle = adapter.send(outbound("working…"));
                int base = frames.size();

                // The over-cap finalize rejects PERMANENT at the encoder; the
                // adapter must convert that into a chunked fresh send, not
                // surface the PERMANENT to the caller.
                assertDoesNotThrow(
                        () -> adapter.finalizeMessage(handle, finalBody),
                        "an over-cap finalize must fall back to a chunked fresh send,"
                                + " never surface a PERMANENT MessagingException");
                done.set(true);
                assertTrue(responder.join(WAIT), "responder did not drain within " + WAIT);

                List<String> fallback = new ArrayList<>(frames).subList(base, frames.size());
                assertTrue(fallback.size() >= 2,
                        "the over-cap final body must require multiple chunked sends");
                assertEquals(finalBody, reassembleSends(fallback),
                        "the full final body must reach the transport across the fallback sends");
            } finally {
                adapter.close();
            }
        }
    }

    @Test
    void fallbackHandleSkipsFurtherEditsAfterRejection() throws Exception {
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXAdapter adapter = newAdapter(fake);
            adapter.rebuildWebSocket();
            List<String> frames = Collections.synchronizedList(new ArrayList<>());
            AtomicBoolean done = new AtomicBoolean();
            // Every /_update edit is rejected with CEInvalidChatItemUpdate so the
            // first update hits the transport and is bounced into a fallback.
            Thread responder = startResponder(fake, frames, done, /* rejectUpdates */ true);
            try {
                fake.awaitClient(WAIT);
                MessageHandle handle = adapter.send(outbound("working…"));

                // First update: the in-place edit reaches the transport, is
                // rejected, and falls back to a fresh send.
                assertDoesNotThrow(() -> adapter.update(handle, "update one"));
                // Two further updates after the fallback: these must skip the
                // doomed edit entirely and fresh-send directly.
                assertDoesNotThrow(() -> adapter.update(handle, "update two"));
                assertDoesNotThrow(() -> adapter.update(handle, "update three"));
                done.set(true);
                assertTrue(responder.join(WAIT), "responder did not drain within " + WAIT);

                List<String> all = new ArrayList<>(frames);
                long editAttempts = all.stream().map(SimpleXEditFallbackTest::cmdOf)
                        .filter(cmd -> cmd.startsWith("/_update")).count();
                assertEquals(1, editAttempts,
                        "only the first update may attempt an in-place edit; after the"
                                + " fallback no further edit attempt may hit the transport");

                // The three update bodies each reach the transport as a fresh
                // /_send, in order, after the placeholder send.
                List<String> sendTexts = new ArrayList<>();
                for (String envelope : all) {
                    if (cmdOf(envelope).startsWith("/_send")) {
                        sendTexts.add(sentText(envelope));
                    }
                }
                assertEquals(
                        List.of("working…", "update one", "update two", "update three"),
                        sendTexts,
                        "every update after the rejection must be delivered as a fresh send");
            } finally {
                adapter.close();
            }
        }
    }

    @Test
    void progressLifecycleOverCapFinalizeDeliversFinalContent() throws Exception {
        // StageProgressNotifier-shaped sequence (the concrete notifier lives
        // Provider-side, out of this module): placeholder send, several
        // coalesced updates, then an over-cap terminal finalize. Before the
        // fallback this froze the placeholder and the summary was never
        // delivered (StageProgressNotifier.terminate only logs).
        String finalBody = overCapText();
        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXAdapter adapter = newAdapter(fake);
            adapter.rebuildWebSocket();
            List<String> frames = Collections.synchronizedList(new ArrayList<>());
            AtomicBoolean done = new AtomicBoolean();
            Thread responder = startResponder(fake, frames, done, /* rejectUpdates */ false);
            try {
                fake.awaitClient(WAIT);
                MessageHandle handle = adapter.send(outbound("starting summary…"));
                adapter.update(handle, "scanning posts…");
                adapter.update(handle, "summarising…");
                int base = frames.size();

                assertDoesNotThrow(() -> adapter.finalizeMessage(handle, finalBody),
                        "an over-cap terminal finalize must deliver the body, not freeze"
                                + " the placeholder");
                done.set(true);
                assertTrue(responder.join(WAIT), "responder did not drain within " + WAIT);

                List<String> fallback = new ArrayList<>(frames).subList(base, frames.size());
                assertEquals(finalBody, reassembleSends(fallback),
                        "the placeholder-freeze loss path is closed: the over-cap final"
                                + " body is delivered in full");
            } finally {
                adapter.close();
            }
        }
    }

    // -- harness -------------------------------------------------------------

    /**
     * A responder vthread that acks every {@code /_send} (and, when {@code
     * rejectUpdates} is false, every {@code /_update}) and — when {@code
     * rejectUpdates} is true — bounces each {@code /_update} with a
     * {@code CEInvalidChatItemUpdate} error. Every observed frame is recorded
     * into {@code frames}. It polls on a short timeout and exits once {@code
     * done} is set and no further frame arrives, so the test never waits a
     * full {@link #WAIT} at teardown.
     */
    private Thread startResponder(FakeSimpleXProcess fake, List<String> frames,
                                  AtomicBoolean done, boolean rejectUpdates) {
        AtomicInteger itemSeq = new AtomicInteger();
        return Thread.ofVirtual().name("fallback-responder").start(() -> {
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
                frames.add(envelope);
                try {
                    String corrId = MAPPER.readTree(envelope).get("corrId").asText();
                    if (rejectUpdates && cmdOf(envelope).startsWith("/_update")) {
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
                msg -> { /* admin notifications unused here */ });
    }

    /** Concatenate the user-visible text of a list of {@code /_send} envelopes. */
    private static String reassembleSends(List<String> envelopes) {
        StringBuilder reassembled = new StringBuilder();
        for (String envelope : envelopes) {
            assertTrue(cmdOf(envelope).startsWith("/_send"),
                    "the fallback must use fresh /_send commands, never /_update");
            String sent = sentText(envelope);
            assertTrue(utf8Length(sent) <= CAP,
                    "every fallback chunk must fit the cap; got " + utf8Length(sent) + " bytes");
            reassembled.append(sent);
        }
        return reassembled.toString();
    }

    /** Prose long enough for multiple chunks — a plausible over-cap summary. */
    private static String overCapText() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; utf8Length(text.toString()) <= 2 * CAP + 200; i++) {
            text.append("summary line ").append(i)
                    .append(": one post condensed to a sentence, with its tag list and a")
                    .append(" bare source URL https://example.org/post/").append(i)
                    .append('\n');
        }
        return text.toString();
    }

    private static String cmdOf(String envelope) {
        try {
            return MAPPER.readTree(envelope).get("cmd").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String sentText(String envelope) {
        try {
            String cmd = cmdOf(envelope);
            String json = cmd.substring(cmd.indexOf(" json ") + " json ".length());
            return MAPPER.readTree(json).get("msgContent").get("text").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static OutboundMessage outbound(String text) {
        return new OutboundMessage(
                new ScopeRef.Dm("alice-queue-addr"), text, Instant.now(), "corr-summary");
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

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }
}
