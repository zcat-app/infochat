package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the M1-283 delivery guarantee at the adapter SPI surface: an
 * outbound text past {@link SimpleXMessageCodec#MAX_OUTBOUND_TEXT_BYTES}
 * no longer fails PERMANENT (pre-chunking, the whole send was dropped and
 * the recipient received nothing) — it is delivered in full across
 * multiple ordered sends, each within the cap. Same harness shape as
 * {@link SimpleXReconnectTest}: the adapter is wired through the
 * package-private {@code rebuildWebSocket()} seam against a
 * {@link FakeSimpleXProcess}, and an acker thread answers every command
 * frame so {@code send()} can complete.
 */
class SimpleXAdapterChunkedSendTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final int CAP = SimpleXMessageCodec.MAX_OUTBOUND_TEXT_BYTES;

    @TempDir
    Path tempDir;

    @Test
    void overCapSendDeliversFullTextAcrossOrderedChunks() throws Exception {
        String text = digestLikeText();
        assertTrue(utf8Length(text) > CAP, "test input must exceed the outbound cap");
        int expectedChunks = SimpleXOutboundChunker.chunk(text).size();
        assertTrue(expectedChunks >= 2, "test input must require multiple sends");

        try (FakeSimpleXProcess fake = new FakeSimpleXProcess()) {
            fake.start();
            SimpleXAdapter adapter = newAdapter(fake);
            adapter.rebuildWebSocket();
            try {
                fake.awaitClient(WAIT);
                // Collect-then-ack for every transmitted frame: send()
                // blocks on each chunk's ack, so by the time it returns,
                // every envelope is in the list.
                List<String> envelopes = Collections.synchronizedList(new ArrayList<>());
                Thread acker = Thread.ofVirtual().start(() -> {
                    try {
                        for (int i = 0; i < expectedChunks; i++) {
                            String envelope = fake.awaitFrame(WAIT);
                            envelopes.add(envelope);
                            String corrId = MAPPER.readTree(envelope).get("corrId").asText();
                            fake.sendFrame(ackFrame(corrId, i));
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                MessageHandle handle = assertDoesNotThrow(
                        () -> adapter.send(outbound(text)),
                        "an over-cap outbound send must not raise a (PERMANENT)"
                                + " MessagingException");
                assertNotNull(handle);
                assertTrue(acker.join(WAIT), "acker did not see all chunks within " + WAIT);

                assertEquals(expectedChunks, envelopes.size());
                StringBuilder reassembled = new StringBuilder();
                for (String envelope : envelopes) {
                    String sent = extractSentText(envelope);
                    assertFalse(sent.isEmpty(), "every transmitted chunk must be non-empty");
                    assertTrue(utf8Length(sent) <= CAP,
                            "every transmitted chunk must fit the cap; got "
                                    + utf8Length(sent) + " bytes");
                    reassembled.append(sent);
                }
                // Concatenation equality proves both completeness and order:
                // the frames arrived as chunk 1, 2, ... with nothing lost.
                assertEquals(text, reassembled.toString(),
                        "the full text must reach the transport across the ordered sends");
            } finally {
                adapter.close();
            }
        }
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

    /** Prose long enough for three chunks — a plausible 200-post group digest. */
    private static String digestLikeText() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; utf8Length(text.toString()) <= 2 * CAP + 200; i++) {
            text.append("digest item ").append(i)
                    .append(": a one-line summary of a post, with a tag list and a")
                    .append(" bare source URL https://example.org/post/").append(i)
                    .append('\n');
        }
        return text.toString();
    }

    /** Pull the user-visible text back out of a {@code /_send} command envelope. */
    private static String extractSentText(String envelope) throws Exception {
        String cmd = MAPPER.readTree(envelope).get("cmd").asText();
        assertTrue(cmd.startsWith("/_send @alice-queue-addr json "),
                "every chunk must be a /_send to the same scope; got: "
                        + cmd.substring(0, Math.min(cmd.length(), 40)));
        String json = cmd.substring(cmd.indexOf(" json ") + " json ".length());
        return MAPPER.readTree(json).get("msgContent").get("text").asText();
    }

    private static OutboundMessage outbound(String text) {
        return new OutboundMessage(
                new ScopeRef.Dm("alice-queue-addr"), text, Instant.now(), "corr-digest");
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

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }
}
