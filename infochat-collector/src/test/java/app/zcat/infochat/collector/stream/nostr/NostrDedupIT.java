package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.collector.outbox.EvalQueueProducer;
import app.zcat.infochat.collector.outbox.PostPersister;
import app.zcat.infochat.collector.outbox.TestEvalQueueConsumer;
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
import java.net.http.HttpClient;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-relay dedup integration test: a {@link NostrStreamSource}
 * subscribed to two {@link FakeNostrRelay}s receives the same event id
 * from both relays, and only one {@code post} row ends up in the outbox.
 *
 * <p>The post table's UNIQUE constraint is
 * {@code (source_id, upstream_identifier, fetched_at)} — it does not
 * absorb two writes for the same event id with millisecond-apart
 * fetched_at timestamps. The in-memory {@link NostrDedupFilter} is
 * therefore the actual dedup gate, and that's what this IT proves: the
 * deliver callback fires exactly once, before the outbox write.</p>
 */
@QuarkusTest
class NostrDedupIT {

    private static final long DISPATCH_KEY = 990198L;
    private static final Duration FIVE_SECONDS = Duration.ofSeconds(5);

    @Inject
    DataSource dataSource;

    @Inject
    StreamSourceSupervisor supervisor;

    @Inject
    PostPersister postPersister;

    @Inject
    EvalQueueProducer evalQueueProducer;

    @Inject
    TestEvalQueueConsumer consumer;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    // Loopback-permitting SSRF guard so the two FakeNostrRelays (127.0.0.1)
    // remain dialable. Production Registrar wires a default-strict instance.
    private final SsrfGuardedHttpClient ssrfClient = new SsrfGuardedHttpClient(
            new LoopbackPermittingBlocklist(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            Duration.ofMinutes(2),
            10L * 1024 * 1024,
            3);
    private final NostrEventVerifier verifier = new NostrEventVerifier();
    private FakeNostrRelay relayA;
    private FakeNostrRelay relayB;

    @BeforeEach
    void setup() {
        relayA = new FakeNostrRelay();
        relayB = new FakeNostrRelay();
        consumer.drain();
    }

    @AfterEach
    void teardown() {
        supervisor.stop(DISPATCH_KEY);
        if (relayA != null) {
            relayA.close();
        }
        if (relayB != null) {
            relayB.close();
        }
    }

    @Test
    void multiRelayDedup() throws Exception {
        // Per-run UUID suffix on the filter-spec identifier: the (kind,
        // identifier) UNIQUE constraint is enforced across the shared
        // QuarkusTest container, so a plain {"kinds":[1]} would collide
        // with NostrStreamSourceIT's seed in the same run. The fake relay
        // ignores filter content, so adding a #test tag is harmless.
        String filterSpec = "{\"kinds\":[1],\"#test\":\"" + UUID.randomUUID() + "\"}";
        UUID sourceUuid = seedNostrSource(filterSpec, "Nostr dedup IT source");
        long preCount = countPostsForSource(sourceUuid);

        NostrDedupFilter dedupFilter = new NostrDedupFilter();
        List<java.net.URI> relays = List.of(relayA.uri(), relayB.uri());
        NostrStreamSource worker = new NostrStreamSource(
                relays,
                OptionalLong::empty, Duration.ofMillis(50), Duration.ofMillis(200),
                httpClient, ssrfClient, verifier, noOpTracker(relays), dedupFilter);
        AtomicInteger deliveryCount = new AtomicInteger();
        Consumer<NormalizedPost> deliver = post -> {
            deliveryCount.incrementAndGet();
            postPersister.persist(sourceUuid, post).ifPresent(evalQueueProducer::emit);
        };
        supervisor.register(DISPATCH_KEY, filterSpec, worker, deliver);

        assertTrue(relayA.awaitFrameCount(1, FIVE_SECONDS), "relay A received the subscriber's REQ");
        assertTrue(relayB.awaitFrameCount(1, FIVE_SECONDS), "relay B received the subscriber's REQ");

        // Same event id from both relays — the cross-relay duplication
        // case the in-memory filter must suppress.
        relayA.sendEvent(NostrSignedEventFixtures.VALID_KIND_1_EVENT);
        relayB.sendEvent(NostrSignedEventFixtures.VALID_KIND_1_EVENT);

        awaitPostCount(sourceUuid, preCount + 1);
        // Give the second relay's delivery time to fire if dedup were broken.
        // Without the filter, two deliver callbacks would fire, producing
        // two post rows (the UNIQUE constraint includes fetched_at and so
        // does NOT absorb the duplicate).
        Thread.sleep(300);

        assertEquals(preCount + 1, countPostsForSource(sourceUuid),
                "same event id from two relays produces exactly one post row");
        assertEquals(1, deliveryCount.get(),
                "in-memory dedup short-circuited the second arrival before deliver");
        assertEquals(NostrSignedEventFixtures.KIND_1_ID, upstreamIdentifierFor(sourceUuid),
                "the single post row is the deduplicated event");
    }

    private UUID seedNostrSource(String identifier, String displayName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                             + "VALUES ('nostr', ?, ?, 'social', '{}') "
                             + "RETURNING id")) {
            ps.setString(1, identifier);
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

    /** See {@code NostrStreamSourceTest.noOpTracker} — same intent: satisfy the constructor only. */
    private static RelayHealthTracker noOpTracker(List<java.net.URI> relayUris) {
        return new RelayHealthTracker(relayUris, Integer.MAX_VALUE,
                Duration.ofHours(1), Integer.MAX_VALUE, java.time.Clock.systemUTC(), t -> { });
    }

    /**
     * Loopback-permitting {@link IpBlocklist} so the in-process
     * {@link FakeNostrRelay} fixtures (127.0.0.1) remain dialable
     * while every other range stays refused.
     */
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
