package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.collector.outbox.EvalQueueProducer;
import app.zcat.infochat.collector.outbox.PostPersister;
import app.zcat.infochat.collector.stream.StreamSourceSupervisor;
import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.ssrf.IpBlocklist;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test for M1-099 per-relay degradation: a
 * {@link NostrStreamSource} subscribed to TWO {@link FakeNostrRelay}
 * instances — one that drops every client connection (forcing repeated
 * reconnects + tracker cooldown) and one that flows events normally —
 * persists every event from the healthy relay despite the bad relay's
 * misbehaviour (architecture.md §Ingest SPIs: "a single misbehaving
 * relay MUST NOT block the StreamSource").
 *
 * <p>Unit-level state-machine coverage lives in
 * {@link RelayHealthTrackerTest}; this IT validates the integration
 * inside the live {@link StreamSourceSupervisor} + outbox pipeline.</p>
 */
@QuarkusTest
class NostrDegradationIT {

    private static final long DISPATCH_KEY = 990099L;
    private static final Duration FIVE_SECONDS = Duration.ofSeconds(5);

    @Inject
    DataSource dataSource;

    @Inject
    StreamSourceSupervisor supervisor;

    @Inject
    PostPersister postPersister;

    @Inject
    EvalQueueProducer evalQueueProducer;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    // Loopback-permitting SSRF guard so FakeNostrRelays (127.0.0.1) remain
    // dialable. Production Registrar wires a default-strict instance.
    private final SsrfGuardedHttpClient ssrfClient = new SsrfGuardedHttpClient(
            new LoopbackPermittingBlocklist(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            Duration.ofMinutes(2),
            10L * 1024 * 1024,
            3);
    private final NostrEventVerifier verifier = new NostrEventVerifier();

    private FakeNostrRelay badRelay;
    private FakeNostrRelay goodRelay;
    private ScheduledExecutorService disruptor;

    @BeforeEach
    void setup() {
        badRelay = new FakeNostrRelay();
        goodRelay = new FakeNostrRelay();
    }

    @AfterEach
    void teardown() {
        supervisor.stop(DISPATCH_KEY);
        if (disruptor != null) {
            disruptor.shutdownNow();
        }
        if (badRelay != null) {
            badRelay.close();
        }
        if (goodRelay != null) {
            goodRelay.close();
        }
    }

    @Test
    void relayDegradation_endToEnd() throws Exception {
        String filterSpec = "{\"kinds\":[1]}";
        UUID sourceUuid = seedNostrSource(filterSpec, "Nostr degradation IT");
        long preCount = countPostsForSource(sourceUuid);

        // Bad relay: every 50ms close any current client. The relay-worker
        // reconnects (eating backoff), hits the failure threshold, enters
        // cooldown, eventually retries. Meanwhile the good relay's worker
        // is untouched and delivers events normally.
        disruptor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nostr-degradation-disruptor");
            t.setDaemon(true);
            return t;
        });
        disruptor.scheduleAtFixedRate(badRelay::disconnectClients, 0, 50, TimeUnit.MILLISECONDS);

        // Tracker tuned tight so the bad relay enters cooldown within a
        // few hundred ms of test start (so the IT does not depend on the
        // application.properties values which are production-tuned). The
        // cycle cap is set high so the bad relay's repeated drops do not
        // terminate the source mid-test — this IT validates "the healthy
        // relay keeps flowing", not the terminal-failure path.
        List<URI> relays = List.of(badRelay.uri(), goodRelay.uri());
        RelayHealthTracker tracker = new RelayHealthTracker(relays,
                /*failureThreshold=*/2, Duration.ofMillis(200),
                /*allRelaysBadCycleCap=*/1_000, Clock.systemUTC(), t -> { });
        NostrStreamSource worker = new NostrStreamSource(relays,
                OptionalLong::empty, Duration.ofMillis(50), Duration.ofMillis(200),
                httpClient, ssrfClient, verifier, tracker, new NostrDedupFilter());
        Consumer<NormalizedPost> deliver =
                post -> postPersister.persist(sourceUuid, post).ifPresent(evalQueueProducer::emit);
        supervisor.register(DISPATCH_KEY, filterSpec, worker, deliver);

        assertTrue(goodRelay.awaitFrameCount(1, FIVE_SECONDS),
                "good relay received the subscriber's REQ");
        goodRelay.sendEvent(NostrSignedEventFixtures.VALID_KIND_1_EVENT);
        awaitPostCount(sourceUuid, preCount + 1);
        assertEquals(preCount + 1, countPostsForSource(sourceUuid),
                "the good relay's EVENT produced one post row despite the bad relay's drops");
        assertEquals(NostrSignedEventFixtures.KIND_1_ID, upstreamIdentifierFor(sourceUuid),
                "the persisted post is the good relay's kind-1 event");

        // A subsequent event still flows after additional disruption time.
        // The first EVENT proved one delivery; this proves the channel
        // stayed live (not just that one event raced through before the
        // tracker cooldown took hold).
        goodRelay.sendEvent(NostrSignedEventFixtures.VALID_KIND_1_DRAIN_A_EVENT);
        awaitPostCount(sourceUuid, preCount + 2);
        assertEquals(preCount + 2, countPostsForSource(sourceUuid),
                "subsequent EVENTs from the healthy relay continue persisting");

        // Confirm the bad relay was in fact being disrupted — multiple
        // (re)connect attempts landed there. Without this assertion the
        // test could pass with the bad relay simply never being dialed,
        // which would not exercise the degradation path. Wait-based so the
        // assertion is robust to JVM warm-up ordering (a prior IT in the
        // same surefire fork warms the WebSocket dial path and the entire
        // event-flow above can complete before the bad relay-worker gets
        // past its first iteration).
        assertTrue(badRelay.awaitFrameCount(2, FIVE_SECONDS),
                "bad relay observed multiple REQ frames from repeated reconnects, "
                        + "got " + badRelay.receivedFrames().size());
    }

    private UUID seedNostrSource(String identifier, String displayName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                             + "VALUES ('nostr', ?, ?, 'social', '{}') "
                             + "RETURNING id")) {
            ps.setString(1, identifier + ":" + UUID.randomUUID());
            ps.setString(2, displayName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private long countPostsForSource(UUID sourceUuid) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM post WHERE source_id = ?")) {
            ps.setObject(1, sourceUuid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private String upstreamIdentifierFor(UUID sourceUuid) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT upstream_identifier FROM post WHERE source_id = ?")) {
            ps.setObject(1, sourceUuid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private void awaitPostCount(UUID sourceUuid, long expected) throws Exception {
        long deadline = System.currentTimeMillis() + FIVE_SECONDS.toMillis();
        while (countPostsForSource(sourceUuid) < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
    }

    /** See {@code NostrStreamSourceTest.LoopbackPermittingBlocklist} — same pattern. */
    private static final class LoopbackPermittingBlocklist extends IpBlocklist {

        @Override
        public boolean isBlocked(@NonNull InetAddress addr) {
            if (addr.isLoopbackAddress()) {
                return false;
            }
            return super.isBlocked(addr);
        }
    }
}
