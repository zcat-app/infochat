package app.zcat.infochat.collector.stream.nostr;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the NIP-01 frame codec ({@link NostrMessage}) and the
 * reconnect backoff curve ({@link NostrRelayConnection#backoffDelay}). Both
 * are pure functions — no socket, no container.
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
}
