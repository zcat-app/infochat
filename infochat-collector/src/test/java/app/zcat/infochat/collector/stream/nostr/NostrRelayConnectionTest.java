package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.ssrf.IpBlocklist;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.jboss.logmanager.LogContext;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the NIP-01 frame codec ({@link NostrMessage}), the
 * reconnect backoff curve ({@link NostrRelayConnection#backoffDelay} — both
 * pure functions), and the log-sanitization of relay NOTICE text in
 * {@link NostrRelayConnection#handleFrame}. The NOTICE case drives
 * {@code handleFrame} directly and captures the emitted log record — no
 * socket, no container.
 */
class NostrRelayConnectionTest {

    @Test
    void parsesNip01Messages() throws Exception {
        // EVENT frame parses into the seven canonical fields.
        String eventFrame = "[\"EVENT\",\"sub1\",{"
                + "\"id\":\"abc\",\"pubkey\":\"pk\",\"created_at\":1700000000,"
                + "\"kind\":1,\"tags\":[[\"e\",\"x\"]],\"content\":\"hello\",\"sig\":\"s\"}]";
        NostrMessage message = NostrMessage.parse(eventFrame);
        NostrMessage.Event event = assertInstanceOf(NostrMessage.Event.class, message);
        assertEquals("sub1", event.subscriptionId());
        NostrEvent parsed = event.event();
        assertEquals("abc", parsed.id());
        assertEquals("pk", parsed.pubkey());
        assertEquals(1700000000L, parsed.createdAt());
        assertEquals(1, parsed.kind());
        assertEquals(List.of(List.of("e", "x")), parsed.tags());
        assertEquals("hello", parsed.content());
        assertEquals("s", parsed.sig());

        // EOSE and NOTICE.
        assertEquals(new NostrMessage.Eose("sub1"), NostrMessage.parse("[\"EOSE\",\"sub1\"]"));
        assertEquals(new NostrMessage.Notice("rate limited"),
                NostrMessage.parse("[\"NOTICE\",\"rate limited\"]"));

        // REQ serialization without a since cursor: ["REQ", subId, filter], no since key.
        String req = NostrMessage.serializeReq("sub1", "{\"kinds\":[1]}", OptionalLong.empty());
        JsonNode reqNode = NostrMessage.MAPPER.readTree(req);
        assertEquals("REQ", reqNode.get(0).asText());
        assertEquals("sub1", reqNode.get(1).asText());
        assertTrue(reqNode.get(2).has("kinds"));
        assertFalse(reqNode.get(2).has("since"), "no since cursor on first subscribe");

        // REQ serialization with a since cursor merges it into the filter.
        String reqSince = NostrMessage.serializeReq("sub1", "{\"kinds\":[1]}", OptionalLong.of(1700000000L));
        JsonNode reqSinceNode = NostrMessage.MAPPER.readTree(reqSince);
        assertEquals(1700000000L, reqSinceNode.get(2).get("since").asLong());

        // Malformed frames raise MalformedFrameException rather than tearing down the read loop.
        assertThrows(NostrMessage.MalformedFrameException.class,
                () -> NostrMessage.parse("not-json-at-all"));
        assertThrows(NostrMessage.MalformedFrameException.class,
                () -> NostrMessage.parse("{}"));
        assertThrows(NostrMessage.MalformedFrameException.class,
                () -> NostrMessage.parse("[\"CLOSED\",\"sub1\"]"));
        assertThrows(NostrMessage.MalformedFrameException.class,
                () -> NostrMessage.parse("[\"EVENT\",\"sub1\"]"));
    }

    /**
     * Addresses the clarity pre-flight warning: acceptance item 6
     * ("exponential backoff with jitter — no tight-loop reconnect storm") now
     * has a runnable test. The deterministic floor doubles each attempt up to
     * the cap, and every sampled delay lands inside the equal-jitter window
     * {@code [exp/2, exp]}, so the lower bound always grows (no tight loop).
     */
    @Test
    void backoffDelayGrowsExponentiallyWithJitterAndCaps() {
        Duration base = Duration.ofSeconds(1);
        Duration max = Duration.ofSeconds(60);
        Random random = new Random(7);
        // Sample repeatedly: jitter varies the exact value but never escapes
        // the per-attempt window, and the windows grow then cap.
        for (int i = 0; i < 50; i++) {
            long d1 = NostrRelayConnection.backoffDelay(1, base, max, random).toMillis();
            assertTrue(d1 >= 500 && d1 <= 1000, "attempt 1 in [500,1000], got " + d1);
            long d2 = NostrRelayConnection.backoffDelay(2, base, max, random).toMillis();
            assertTrue(d2 >= 1000 && d2 <= 2000, "attempt 2 in [1000,2000], got " + d2);
            long d3 = NostrRelayConnection.backoffDelay(3, base, max, random).toMillis();
            assertTrue(d3 >= 2000 && d3 <= 4000, "attempt 3 in [2000,4000], got " + d3);
            long capped = NostrRelayConnection.backoffDelay(20, base, max, random).toMillis();
            assertTrue(capped >= 30000 && capped <= 60000, "attempt 20 capped in [30000,60000], got " + capped);
        }
    }

    /**
     * Acceptance item 3 (M1-491): a relay NOTICE carrying control / bidi
     * codepoints must reach the log sanitized. {@code handleFrame} logs the
     * NOTICE at DEBUG, so the logger is forced to {@code ALL} and a handler
     * captures the emitted record. slf4j routes through jboss-logmanager
     * (slf4j-jboss-logmanager), whose {@link LogContext} is a separate logger
     * tree from {@code java.util.logging} — the DEBUG-enabling level and the
     * capturing handler must therefore be set on the jboss {@code LogContext}
     * logger, not on {@code java.util.logging.Logger.getLogger} (which targets
     * a different, disconnected tree, so debug stays gated and nothing is
     * captured). The frame embeds the hostile codepoints as JSON unicode
     * escapes so this source stays ASCII; Jackson decodes them into the real
     * chars before logging, and {@link SafeLog#stripControls} must replace
     * every one with a space.
     */
    @Test
    void relayNoticeIsLoggedControlAndBidiStripped() {
        org.jboss.logmanager.Logger relayLogger = LogContext.getLogContext().getLogger(
                "app.zcat.infochat.collector.stream.nostr.NostrRelayConnection");
        Level priorLevel = relayLogger.getLevel();
        CapturingHandler capturer = new CapturingHandler();
        capturer.setLevel(Level.ALL);
        relayLogger.setLevel(Level.ALL);
        relayLogger.addHandler(capturer);
        try {
            // danger<ESC>[31m<U+202E><U+2028><U+2029>end — a C0 control plus the
            // bidi override and the line / paragraph separators, embedded as JSON
            // unicode escapes (valid JSON, ASCII source).
            String frame = "[\"NOTICE\",\"danger\\u001b[31m\\u202e\\u2028\\u2029end\"]";

            newConnection().handleFrame(frame);

            String noticeLine = null;
            for (LogRecord record : capturer.records) {
                String message = record.getMessage();
                if (message != null && message.contains("NOTICE")) {
                    noticeLine = message;
                }
            }
            assertNotNull(noticeLine,
                    "expected a captured NOTICE debug record; captured "
                            + capturer.records.size() + " records");
            assertFalse(noticeLine.indexOf('\u202E') >= 0,
                    "U+202E (bidi override) must be stripped from the logged NOTICE: " + noticeLine);
            assertFalse(noticeLine.indexOf('\u2028') >= 0,
                    "U+2028 (line separator) must be stripped from the logged NOTICE: " + noticeLine);
            assertFalse(noticeLine.indexOf('\u2029') >= 0,
                    "U+2029 (paragraph separator) must be stripped from the logged NOTICE: " + noticeLine);
            assertFalse(noticeLine.indexOf('\u001B') >= 0,
                    "the C0 ESC control must be stripped from the logged NOTICE: " + noticeLine);
            assertTrue(noticeLine.contains("danger") && noticeLine.contains("end"),
                    "printable NOTICE text must survive the strip: " + noticeLine);
        } finally {
            relayLogger.removeHandler(capturer);
            relayLogger.setLevel(priorLevel);
        }
    }

    // handleFrame's NOTICE arm touches neither the event sink, the health
    // tracker, nor the SSRF client; these stand-ins satisfy the non-null
    // constructor contract only (mirrors NostrProductivityAfterVerifyTest).
    private static NostrRelayConnection newConnection() {
        URI relayUri = URI.create("wss://nostr-notice-fixture.invalid:39753/relay");
        Predicate<NostrEvent> unusedSink = event -> false;
        RelayHealthTracker tracker = new RelayHealthTracker(
                List.of(relayUri), Integer.MAX_VALUE, Duration.ofHours(1),
                Integer.MAX_VALUE, Clock.systemUTC(), t -> { });
        SsrfGuardedHttpClient unusedSsrfClient = new SsrfGuardedHttpClient(
                new IpBlocklist(), Duration.ofSeconds(2), Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofMinutes(2), 10L * 1024 * 1024, 3,
                (String host) -> List.<InetAddress>of());
        return new NostrRelayConnection(
                relayUri, "{\"kinds\":[1]}",
                OptionalLong::empty, unusedSink,
                Duration.ofMillis(50), Duration.ofMillis(200),
                HttpClient.newHttpClient(), unusedSsrfClient,
                NostrRelayConnection.DEFAULT_PEER_IP_CHECK_INTERVAL,
                tracker);
    }

    /**
     * Minimal JUL handler recording every emitted {@link LogRecord}; same
     * shape as the CapturingHandler in {@code FetchSchedulerLogRedactionTest}.
     */
    private static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
            // no-op
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
