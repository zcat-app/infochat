package app.zcat.infochat.collector.fetch;

import com.sun.net.httpserver.HttpServer;
import app.zcat.infochat.collector.fetcher.rss.RssFeedParser;
import app.zcat.infochat.collector.fetcher.rss.RssFetcher;
import app.zcat.infochat.collector.outbox.PostPersister;
import app.zcat.infochat.collector.outbox.TestEvalQueueConsumer;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.ingest.NormalizedPost;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for {@link FetchScheduler} against the Quarkus
 * DevServices Postgres + an in-process {@link HttpServer} fixture.
 *
 * <p>The test seeds a {@code source} row pointing at the loopback
 * fixture, substitutes a loopback-permitting {@link RssFetcher} into
 * the scheduler, manually triggers one tick via
 * {@link FetchScheduler#tickOnce}, and asserts:
 * <ul>
 *   <li>N {@code post(status='RAW')} rows appear in the DB matching
 *       the fixture's {@code <item>} count;</li>
 *   <li>N corresponding post keys land on the {@code eval-queue}
 *       channel via {@link TestEvalQueueConsumer};</li>
 *   <li>The keys' {@code id} values match the inserted rows' ids.</li>
 * </ul>
 *
 * <p>The IT does NOT wait on the scheduler's clock — calling
 * {@link FetchScheduler#tickOnce} directly is the recommended
 * deterministic-trigger shape per
 * {@code docs/plan/m1/tickets/M1-028-collector-outbox-fetch.md}
 * §Implementation notes.
 */
@QuarkusTest
class FetchSchedulerIT {

    private static final Path FIXTURE =
        Paths.get("src/test/resources/fixtures/outbox/feed-fixture.xml");

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    FetchScheduler fetchScheduler;

    @Inject
    TestEvalQueueConsumer consumer;

    private HttpServer server;
    private int port;

    @BeforeEach
    void startFixtureAndInstallMockFetcher() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/feed.xml", exchange -> {
            byte[] body = Files.readAllBytes(FIXTURE);
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        // Substitute the production RssFetcher via QuarkusMock — the
        // Quarkus-idiomatic CDI bean replacement. The substitute hits
        // the loopback fixture with a plain HttpClient because the
        // production SsrfGuardedHttpClient's blocklist refuses
        // 127.0.0.1; the SSRF gate itself is covered by RssFetcherTest.
        // QuarkusMock.installMockForType wires the substitute through
        // the same CDI client proxy that FetchScheduler holds, so
        // fetchScheduler.rssFetcher.fetch(...) dispatches here.
        QuarkusMock.installMockForType(new TestRssFetcher(), RssFetcher.class,
            new FetcherKind.Literal("rss"));

        // Drain anything other ITs left on the channel.
        consumer.drain();
    }

    @AfterEach
    void stopFixture() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void tickPersistsAndEnqueuesEveryFixtureItem() throws Exception {
        UUID sourceUuid = seedRssSource(
            "http://127.0.0.1:" + port + "/feed.xml",
            "FetchScheduler IT fixture source");

        // Pre-count post rows for this source so the test is robust
        // against rows that other tests (PostPersisterIT,
        // OutboxRehydratorIT) inserted earlier in the suite.
        long preCount = countPostsForSource(sourceUuid);

        FetchScheduler.SourceRow row = new FetchScheduler.SourceRow(
            sourceUuid,
            "http://127.0.0.1:" + port + "/feed.xml",
            42L, // dispatch key — opaque to the Fetcher
            "rss");
        fetchScheduler.tickOnce(row);

        // The fixture has 2 <item> elements; expect 2 new RAW rows.
        long postCount = countPostsForSource(sourceUuid);
        assertEquals(preCount + 2, postCount,
            "FetchScheduler tick must persist one RAW post per fixture <item>");

        // All persisted rows for this source are at status='RAW'.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status FROM post WHERE source_id = ?")) {
            ps.setObject(1, sourceUuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    assertEquals("RAW", rs.getString(1),
                        "every newly persisted post is at status='RAW'");
                }
            }
        }

        // Drain the test consumer's queue and assert 2 keys arrived.
        awaitConsumerSize(2);
        List<PostPersister.PersistedPostKey> received = consumer.drain();
        assertEquals(2, received.size(),
            "the eval-queue channel must receive one key per persisted post");

        // The drained keys' ids must match the inserted post ids.
        Set<UUID> receivedIds = received.stream()
            .map(PostPersister.PersistedPostKey::id)
            .collect(Collectors.toSet());
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id FROM post WHERE source_id = ?")) {
            ps.setObject(1, sourceUuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = (UUID) rs.getObject(1);
                    assertTrue(receivedIds.contains(id),
                        "each inserted post id must appear on the eval-queue");
                }
            }
        }
    }

    @Test
    void tickDispatchesSourceToFetcherMatchingKind() throws Exception {
        UUID sourceUuid = seedRssSource(
            "http://127.0.0.1:" + port + "/feed.xml",
            "kind-dispatch IT source");

        long preCount = countPostsForSource(sourceUuid);

        FetchScheduler.SourceRow row = new FetchScheduler.SourceRow(
            sourceUuid,
            "http://127.0.0.1:" + port + "/feed.xml",
            99L,
            "rss");
        fetchScheduler.tickOnce(row);

        long postCount = countPostsForSource(sourceUuid);
        assertEquals(preCount + 2, postCount,
            "tickOnce must dispatch an rss-kind source to the registered RssFetcher");

        awaitConsumerSize(2);
        List<PostPersister.PersistedPostKey> received = consumer.drain();
        assertEquals(2, received.size(),
            "eval-queue must receive one key per persisted post from kind-dispatched tick");
    }

    @Test
    void tickSkipsSourceWithUnregisteredKind() throws Exception {
        UUID sourceUuid = seedSourceWithKind(
            "fake",
            "fake://no-fetcher-registered",
            "unregistered kind IT source");

        FetchScheduler.SourceRow row = new FetchScheduler.SourceRow(
            sourceUuid,
            "fake://no-fetcher-registered",
            100L,
            "fake");
        fetchScheduler.tickOnce(row);

        long postCount = countPostsForSource(sourceUuid);
        assertEquals(0, postCount,
            "tickOnce must skip a source whose kind has no registered Fetcher");
        assertEquals(0, consumer.size(),
            "eval-queue must not receive keys when Fetcher is missing for kind");
    }

    private UUID seedRssSource(String identifier, String displayName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                 + "VALUES ('rss', ?, ?, 'news', '{}') "
                 + "RETURNING id")) {
            ps.setString(1, identifier);
            ps.setString(2, displayName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID seedSourceWithKind(String kind, String identifier, String displayName)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                 + "VALUES (?, ?, ?, 'news', '{}') "
                 + "RETURNING id")) {
            ps.setString(1, kind);
            ps.setString(2, identifier);
            ps.setString(3, displayName);
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

    private void awaitConsumerSize(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (consumer.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        if (consumer.size() < expected) {
            throw new AssertionError(
                "timeout: expected consumer size >= " + expected
                + " but got " + consumer.size());
        }
    }

    /**
     * Test-only {@link RssFetcher} subclass that overrides
     * {@link #fetch} with a plain {@link HttpClient} call to bypass
     * the SSRF gate (the production blocklist refuses 127.0.0.1).
     * The override re-uses {@link RssFeedParser} for the actual
     * RSS-to-NormalizedPost mapping so the parsed shape matches
     * what the production path would produce.
     */
    private static final class TestRssFetcher extends RssFetcher {
        private final HttpClient httpClient = HttpClient.newHttpClient();

        @Override
        public List<NormalizedPost> fetch(long sourceId, String identifier) {
            Instant fetchedAt = Instant.now();
            try {
                HttpResponse<byte[]> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(identifier)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new RuntimeException(
                        "TestRssFetcher: HTTP " + response.statusCode() + " from " + identifier);
                }
                return RssFeedParser.parse(sourceId, response.body(), fetchedAt);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new RuntimeException("TestRssFetcher: I/O failure", e);
            }
        }
    }
}
