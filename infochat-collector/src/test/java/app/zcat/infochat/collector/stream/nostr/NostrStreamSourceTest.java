package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.ssrf.IpBlocklist;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural tests for {@link NostrStreamSource} against the in-process
 * {@link FakeNostrRelay}. The worker is constructed directly (the role the
 * nested {@code Registrar} plays in production) with a fast backoff so
 * reconnects do not slow the suite; the end-to-end persist path is covered
 * by {@code NostrStreamSourceIT}.
 */
class NostrStreamSourceTest {

    private static final Duration FAST_BASE = Duration.ofMillis(20);
    private static final Duration FAST_MAX = Duration.ofMillis(100);
    private static final Duration AWAIT = Duration.ofSeconds(5);
    private static final String FILTER = "{\"kinds\":[1]}";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    // Loopback-permitting SSRF guard so FakeNostrRelay (bound to 127.0.0.1)
    // remains dialable; every other blocklist range still refuses. Same
    // pattern the Fetcher tests use against the in-process HttpServer
    // fixture (RssFetcherTest, NitterFetcherTest, ...). Production wires
    // a default-strict SsrfGuardedHttpClient through Registrar.
    private final SsrfGuardedHttpClient ssrfClient = new SsrfGuardedHttpClient(
            new LoopbackPermittingBlocklist(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            Duration.ofMinutes(2),
            10L * 1024 * 1024,
            3);
    private final NostrEventVerifier verifier = new NostrEventVerifier();
    private final NostrDedupFilter dedupFilter = new NostrDedupFilter();
    private final List<FakeNostrRelay> relays = new ArrayList<>();
    private NostrStreamSource source;

    @AfterEach
    void cleanup() {
        if (source != null) {
            source.stop();
        }
        for (FakeNostrRelay relay : relays) {
            relay.close();
        }
    }

    @Test
    void connectsToAllConfiguredRelays() {
        FakeNostrRelay r1 = newRelay();
        FakeNostrRelay r2 = newRelay();
        FakeNostrRelay r3 = newRelay();
        List<URI> relayUris = List.of(r1.uri(), r2.uri(), r3.uri());
        source = new NostrStreamSource(relayUris,
                OptionalLong::empty, FAST_BASE, FAST_MAX, httpClient, ssrfClient, verifier,
                noOpTracker(relayUris), dedupFilter);

        source.start(1L, FILTER, post -> { });

        assertTrue(r1.awaitFrameCount(1, AWAIT), "relay 1 received a REQ");
        assertTrue(r2.awaitFrameCount(1, AWAIT), "relay 2 received a REQ");
        assertTrue(r3.awaitFrameCount(1, AWAIT), "relay 3 received a REQ");
    }

    @Test
    void receivesAndDeliversEvents() {
        FakeNostrRelay relay = newRelay();
        List<NormalizedPost> delivered = new CopyOnWriteArrayList<>();
        List<URI> relayUris = List.of(relay.uri());
        source = new NostrStreamSource(relayUris, OptionalLong::empty,
                FAST_BASE, FAST_MAX, httpClient, ssrfClient, verifier, noOpTracker(relayUris), dedupFilter);

        source.start(7L, FILTER, delivered::add);
        assertTrue(relay.awaitFrameCount(1, AWAIT), "REQ received");
        relay.sendEvent(NostrSignedEventFixtures.VALID_KIND_1_EVENT);

        assertTrue(awaitSize(delivered, 1), "event delivered to the callback");
        NormalizedPost post = delivered.get(0);
        assertEquals(NostrSignedEventFixtures.KIND_1_ID, post.upstreamIdentifier(),
                "upstream id is the Nostr event id");
        assertEquals(NostrSignedEventFixtures.KIND_1_CONTENT, post.body());
        assertEquals(7L, post.sourceId());
        assertEquals(Instant.ofEpochSecond(NostrSignedEventFixtures.FIXED_CREATED_AT), post.publishedAt(),
                "published_at is created_at");
        assertNull(post.title());
        assertNull(post.url());
    }

    @Test
    void reconnectsWithSinceOnDisconnect() throws Exception {
        FakeNostrRelay relay = newRelay();
        AtomicReference<OptionalLong> since = new AtomicReference<>(OptionalLong.empty());
        List<URI> relayUris = List.of(relay.uri());
        source = new NostrStreamSource(relayUris, since::get, FAST_BASE, FAST_MAX,
                httpClient, ssrfClient, verifier, noOpTracker(relayUris), dedupFilter);

        source.start(1L, FILTER, post -> { });
        assertTrue(relay.awaitFrameCount(1, AWAIT), "initial REQ received");

        // Advance the persisted cursor, then drop the connection to force a reconnect.
        since.set(OptionalLong.of(1700000000L));
        relay.disconnectClients();
        assertTrue(relay.awaitFrameCount(2, AWAIT), "reconnect REQ received");

        List<String> frames = relay.receivedFrames();
        JsonNode firstReq = NostrMessage.MAPPER.readTree(frames.get(0));
        assertFalse(firstReq.get(2).has("since"), "the first subscribe has no since cursor");
        JsonNode reconnectReq = NostrMessage.MAPPER.readTree(frames.get(1));
        assertEquals(1700000000L, reconnectReq.get(2).get("since").asLong(),
                "the reconnect REQ carries since=last_persisted_event_at");
    }

    @Test
    void stopDrainsAndClosesConnections() {
        FakeNostrRelay relay = newRelay();
        List<NormalizedPost> delivered = new CopyOnWriteArrayList<>();
        // A deliberately slow consumer makes events back up in the queue so
        // the stop() drain path (not steady-state delivery) flushes them.
        Consumer<NormalizedPost> slowDeliver = post -> {
            sleepQuietly(50);
            delivered.add(post);
        };
        List<URI> relayUris = List.of(relay.uri());
        source = new NostrStreamSource(relayUris, OptionalLong::empty,
                FAST_BASE, FAST_MAX, httpClient, ssrfClient, verifier, noOpTracker(relayUris), dedupFilter);

        source.start(1L, FILTER, slowDeliver);
        assertTrue(relay.awaitFrameCount(1, AWAIT), "REQ received");
        relay.sendEvent(NostrSignedEventFixtures.VALID_KIND_1_DRAIN_A_EVENT);
        relay.sendEvent(NostrSignedEventFixtures.VALID_KIND_1_DRAIN_B_EVENT);
        relay.sendEvent(NostrSignedEventFixtures.VALID_KIND_1_DRAIN_C_EVENT);
        // Wait until the first delivery starts; the remaining events are now queued.
        assertTrue(awaitSize(delivered, 1), "delivery started while running");

        source.stop();
        source = null; // already stopped; skip the @AfterEach stop

        assertEquals(3, delivered.size(), "stop() flushed every buffered event");
        assertTrue(awaitCondition(() -> relay.liveConnectionCount() == 0),
                "stop() closed the WebSocket connection");

        // No callback may fire after stop() returns.
        relay.sendEvent(NostrSignedEventFixtures.VALID_KIND_1_EVENT);
        sleepQuietly(200);
        assertEquals(3, delivered.size(), "no deliver callback fires after stop() returns");
    }

    private FakeNostrRelay newRelay() {
        FakeNostrRelay relay = new FakeNostrRelay();
        relays.add(relay);
        return relay;
    }

    /**
     * A tracker that never enters cooldown or terminal state — every threshold
     * is set so high that recordFailure / recordSuccess are inert side-effect
     * wise. These tests assert on M1-096 reconnect / delivery behaviour, not
     * on M1-099 degradation behaviour; the tracker is wired only to satisfy
     * the constructor contract.
     */
    private static RelayHealthTracker noOpTracker(List<URI> relayUris) {
        return new RelayHealthTracker(relayUris, Integer.MAX_VALUE,
                Duration.ofHours(1), Integer.MAX_VALUE, Clock.systemUTC(), t -> { });
    }

    private static boolean awaitSize(List<?> list, int size) {
        return awaitCondition(() -> list.size() >= size);
    }

    private static boolean awaitCondition(BooleanSupplier condition) {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            sleepQuietly(10);
        }
        return condition.getAsBoolean();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Test-only {@link IpBlocklist} subclass that permits loopback
     * (127.0.0.0/8 + IPv6 ::1) so the in-process {@link FakeNostrRelay}
     * fixture remains dialable. Every other range (private, link-local,
     * CGNAT, cloud-metadata, multicast) is still refused via the strict
     * super-implementation. Subclass-override is the explicit seam the
     * Fetcher tests use against the same module.
     */
    private static final class LoopbackPermittingBlocklist extends IpBlocklist {

        @Override
        public boolean isBlocked(InetAddress addr) {
            if (addr.isLoopbackAddress()) {
                return false;
            }
            return super.isBlocked(addr);
        }
    }
}
