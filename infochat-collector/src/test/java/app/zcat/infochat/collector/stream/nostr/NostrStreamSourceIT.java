package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.collector.outbox.EvalQueueProducer;
import app.zcat.infochat.collector.outbox.PostPersister;
import app.zcat.infochat.collector.outbox.TestEvalQueueConsumer;
import app.zcat.infochat.collector.stream.StreamSourceSupervisor;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test: a {@link NostrStreamSource} subscribed to an
 * in-process {@link FakeNostrRelay}, registered with the live
 * {@link StreamSourceSupervisor}, drives a received EVENT through the real
 * {@link PostPersister} onto a {@code post} row and the {@code eval-queue}
 * channel (observed via {@link TestEvalQueueConsumer}).
 *
 * <p>The worker is constructed and registered directly here rather than via
 * the startup {@code Registrar}: the relay's port is only known once the test
 * starts the fake, which is after the {@code @Startup} registrar has already
 * run. This mirrors {@code StreamSourceSupervisorIT}'s in-container
 * {@code register()} call.</p>
 */
@QuarkusTest
class NostrStreamSourceIT {

    private static final long DISPATCH_KEY = 990096L;
    private static final Duration FIVE_SECONDS = Duration.ofSeconds(5);

    @Inject
    @SeedDataSource
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
    // Loopback-permitting SSRF guard so FakeNostrRelay (127.0.0.1) remains
    // dialable; every other blocklist range still refuses. Production
    // Registrar wires a default-strict instance.
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
    private FakeNostrRelay relay;

    @BeforeEach
    void setup() {
        relay = new FakeNostrRelay();
        consumer.drain();
    }

    @AfterEach
    void teardown() {
        supervisor.stop(DISPATCH_KEY);
        if (relay != null) {
            relay.close();
        }
    }

    @Test
    void endToEndWithFakeRelay() throws Exception {
        String filterSpec = "{\"kinds\":[1]}";
        UUID sourceUuid = seedNostrSource(filterSpec, "Nostr IT source");
        long preCount = countPostsForSource(sourceUuid);

        List<URI> relayUris = List.of(relay.uri());
        NostrStreamSource worker = new NostrStreamSource(relayUris,
                OptionalLong::empty, Duration.ofMillis(50), Duration.ofMillis(200),
                httpClient, ssrfClient, verifier, noOpTracker(relayUris), dedupFilter);
        Consumer<NormalizedPost> deliver =
                post -> postPersister.persist(sourceUuid, post).ifPresent(evalQueueProducer::emit);
        supervisor.register(DISPATCH_KEY, filterSpec, worker, deliver);

        assertTrue(relay.awaitFrameCount(1, FIVE_SECONDS), "relay received the subscriber's REQ");
        relay.sendEvent(NostrSignedEventFixtures.VALID_KIND_1_EVENT);

        awaitPostCount(sourceUuid, preCount + 1);
        assertEquals(preCount + 1, countPostsForSource(sourceUuid),
                "the received EVENT produced one post row");
        assertEquals(NostrSignedEventFixtures.KIND_1_ID, upstreamIdentifierFor(sourceUuid),
                "the post carries the Nostr event id as upstream_identifier");

        awaitConsumerSize(1);
        assertEquals(1, consumer.drain().size(),
                "the persisted post key reached the eval-queue channel");
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
    private static RelayHealthTracker noOpTracker(List<URI> relayUris) {
        return new RelayHealthTracker(relayUris, Integer.MAX_VALUE,
                Duration.ofHours(1), Integer.MAX_VALUE, Clock.systemUTC(), t -> { });
    }

    private void awaitConsumerSize(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + FIVE_SECONDS.toMillis();
        while (consumer.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        if (consumer.size() < expected) {
            throw new AssertionError("timeout: expected consumer size >= " + expected
                    + " but got " + consumer.size());
        }
    }

    /**
     * Loopback-permitting {@link IpBlocklist} so the in-process
     * {@link FakeNostrRelay} (127.0.0.1) remains dialable while every
     * other range stays refused. Same shape used by the Fetcher tests
     * (RssFetcherTest, NitterFetcherTest, ...).
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
