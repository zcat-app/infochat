package app.zcat.infochat.collector.smoke;

import app.zcat.infochat.collector.eval.embedding.EmbeddingWorker;
import app.zcat.infochat.collector.eval.entity.EntityExtractorWorker;
import app.zcat.infochat.collector.eval.ready.ReadyPromoter;
import app.zcat.infochat.collector.eval.tagger.TaggerWorker;
import app.zcat.infochat.collector.eval.testing.StubLlmProvider;
import app.zcat.infochat.collector.fetch.FetchScheduler;
import app.zcat.infochat.collector.fetcher.rss.RssFeedParser;
import app.zcat.infochat.collector.fetcher.rss.RssFetcher;
import app.zcat.infochat.collector.fetch.FetcherKind;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.llm.LlmProvider;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

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
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end smoke IT for the Collector ingest pipeline (M1-416):
 * proves fetch &rarr; outbox &rarr; Stage 1 &rarr; Stage 2 BENIGN &rarr;
 * Tagger &rarr; Entity &rarr; Embedding &rarr; READY plus the
 * cross-service {@code new_post} NOTIFY all work together. The
 * provider-side testing tools (M1-413..M1-415) all stopped at the
 * {@code post} table; this closes the collector-side gap in
 * {@code docs/testing/USER_TEST_PLAN.md} deliverable #5.
 *
 * <h2>What is real vs stubbed</h2>
 * <p>Real: the production {@link FetchScheduler}, outbox emit, every
 * eval-stage worker, the {@code post} state machine, and the
 * {@link ReadyPromoter}'s {@code pg_notify('new_post', ...)} against the
 * Quarkus DevServices Postgres. Stubbed: the LLM (the module-global
 * {@link StubLlmProvider}). The RSS feed is a canned loopback fixture, not
 * live network egress.
 *
 * <h2>Embedding is exercised on its "embedding-optional" release path</h2>
 * <p>The module-global {@code EmbeddingProvider} stub is a nested type
 * inside a package-private IT — unreachable from this package, so this test
 * cannot queue a success vector into it. Per the acceptance path
 * ("...&rarr; embedding-optional"), the real {@link EmbeddingWorker} is
 * still run: with no queued vector it fails the embed twice and takes the
 * no-vector release branch, which advances {@code embedding_done=TRUE} (a
 * WARN log, no admin notification) so the post still reaches READY. The
 * dedicated {@link SmokeTestProfile} boots a fresh Quarkus instance so that
 * stub's FIFO queue is guaranteed empty — a sibling IT's left-over queued
 * vector cannot leak in and turn this into a success-or-dimension-mismatch
 * non-determinism.
 *
 * <h2>Driving the halted scheduler</h2>
 * <p>{@code %test.quarkus.scheduler.start-mode=halted} (collector
 * {@code application.properties}) means the {@code @Scheduled} pollers do
 * not fire on their clock, so this IT advances each stage by hand. Stage 1
 * (and the in-process Stage 2 hand-off) run automatically on the
 * {@code eval-queue} virtual-thread dispatch the fetch triggers; the
 * later DB-poller stages are driven via the workers' public entry points.
 * The Tagger and Entity workers expose only the global-scan {@code onTick()}
 * across packages, so {@link #neutralizeStrayInFlightPosts()} first marks
 * every pre-existing in-flight post as already-through-those-stages — this
 * keeps a sibling IT's left-over {@code RAW} posts from being swept into
 * this test's onTick scan and stealing the FIFO {@link StubLlmProvider}
 * responses queued for THIS post.
 *
 * <h2>Stage 2 BENIGN, not Stage-2-skipped</h2>
 * <p>The well-formed fixture item's body trips Stage 1 regex rule
 * {@code stage1.ignore_previous_instructions}, so Stage 1 flags the post
 * and hands it to the Stage 2 judge (the stub returns {@code BENIGN}),
 * which releases it {@code RAW} with {@code stage2_done=true}. A clean,
 * non-flagged post would skip Stage 2 entirely; flagging it is what makes
 * the smoke test exercise the literal "Stage 2 BENIGN" hop the acceptance
 * path names.
 */
@QuarkusTest
@TestProfile(IngestNotifySmokeIT.SmokeTestProfile.class)
class IngestNotifySmokeIT {

    private static final Path WELL_FORMED_FIXTURE =
        Paths.get("src/test/resources/fixtures/smoke/ingest-smoke-well-formed.xml");
    private static final Path MALFORMED_FIXTURE =
        Paths.get("src/test/resources/fixtures/smoke/ingest-smoke-malformed.xml");

    // 'security' is in the controlled vocabulary at startup: the bootstrap
    // fixture (bootstrap-sources-fixture.json) carries a "Security" tag, and
    // TagVocabulary loads the union of bootstrap tags at @Startup. The Tagger
    // therefore accepts it as a valid tag without this cross-package test
    // needing to reach the package-private TagVocabulary.load() to refresh
    // the cache after a late insert.
    private static final String VOCAB_TAG = "security";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    FetchScheduler fetchScheduler;

    @Inject
    LlmProvider llmProvider;

    @Inject
    TaggerWorker taggerWorker;

    @Inject
    EntityExtractorWorker entityExtractorWorker;

    @Inject
    EmbeddingWorker embeddingWorker;

    @Inject
    ReadyPromoter readyPromoter;

    private HttpServer server;
    private int port;
    private final List<UUID> seededSources = new ArrayList<>();

    @BeforeEach
    void startFixtureAndInstallMockFetcher() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/well-formed.xml", exchange -> serveFixture(exchange, WELL_FORMED_FIXTURE));
        server.createContext("/malformed.xml", exchange -> serveFixture(exchange, MALFORMED_FIXTURE));
        server.start();

        // Substitute the production RssFetcher with a loopback-permitting
        // one — the production SsrfGuardedHttpClient blocklist refuses
        // 127.0.0.1; the SSRF gate itself is covered by RssFetcherTest.
        QuarkusMock.installMockForType(new TestRssFetcher(), RssFetcher.class,
            new FetcherKind.Literal("rss"));

        stubLlm().reset();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (server != null) {
            server.stop(0);
        }
        // Remove this IT's own rows so repeated runs (each seeds a fresh
        // source row, hence fresh uids) do not accumulate. Children first —
        // post_embedding / post_entity / quarantine carry a post_id FK.
        try (Connection conn = dataSource.getConnection()) {
            for (String table : List.of("post_embedding", "post_entity", "quarantine")) {
                try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM " + table + " WHERE post_id IN "
                        + "(SELECT id FROM post WHERE source_id = ANY (?))")) {
                    ps.setArray(1, conn.createArrayOf("uuid", seededSources.toArray()));
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM post WHERE source_id = ANY (?)")) {
                ps.setArray(1, conn.createArrayOf("uuid", seededSources.toArray()));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM source WHERE id = ANY (?)")) {
                ps.setArray(1, conn.createArrayOf("uuid", seededSources.toArray()));
                ps.executeUpdate();
            }
        }
    }

    /**
     * The golden path: one well-formed feed item fetched, evaluated through
     * every stage, promoted to READY, and announced on {@code new_post}.
     */
    @Test
    void fetchEvaluatePromoteEmitsNewPostNotify() throws Exception {
        // The FIFO stub serves, in consumption order: Stage 2 judge (BENIGN),
        // Tagger (one in-vocabulary tag), Entity extractor (no entities).
        stubLlm().setNextResponses(
            "BENIGN",
            "{\"tags\":[\"" + VOCAB_TAG + "\"]}",
            "[]");

        // Isolate the global-scan tagger/entity onTick() calls below from any
        // in-flight posts a sibling IT left behind (see class javadoc).
        neutralizeStrayInFlightPosts();

        UUID sourceId = seedRssSource("http://127.0.0.1:" + port + "/well-formed.xml",
            "M1-416 smoke well-formed source");

        fetchScheduler.tickOnce(new FetchScheduler.SourceRow(
            sourceId, "http://127.0.0.1:" + port + "/well-formed.xml", 1L, "rss"));

        // The fixture has exactly one valid <item>: one RAW post persisted.
        assertEquals(1, countPostsForSource(sourceId),
            "the well-formed fixture's single item must persist exactly one post");
        PostKey postKey = solePostKey(sourceId);

        // Stage 1 + the in-process Stage 2 judge run on the eval-queue
        // virtual-thread dispatch; wait for the BENIGN release to land.
        awaitStage2Benign(postKey.id());
        PostFlags afterStage2 = readFlags(postKey.id());
        assertTrue(afterStage2.stage1Done(), "Stage 1 must complete");
        assertTrue(afterStage2.stage1Flagged(),
            "the fixture body must trip a Stage 1 regex so Stage 2 actually runs");
        assertTrue(afterStage2.stage2Done(), "Stage 2 must complete");
        assertEquals("BENIGN", afterStage2.stage2Verdict(),
            "Stage 2 must record the BENIGN verdict");
        assertEquals("RAW", afterStage2.status(),
            "a BENIGN post stays RAW until Stage 5 promotes it");

        // Stage 3 (Tagger) and the parallel Entity stage — driven via their
        // public onTick() now that this post is the only in-flight one.
        taggerWorker.onTick();
        PostFlags afterTagger = readFlags(postKey.id());
        assertTrue(afterTagger.taggerDone(), "Tagger must complete");

        entityExtractorWorker.onTick();
        assertTrue(readFlags(postKey.id()).entityDone(), "Entity extraction must complete");

        // Stage 4 (Embedding) — targeted public processBatch on this post. The
        // fresh-boot embedding stub has no queued vector, so the worker takes
        // its "embedding-optional" no-vector release path and still advances
        // embedding_done=TRUE (see class javadoc).
        embeddingWorker.processBatch(List.of(new EmbeddingWorker.PostRow(
            postKey.id(), postKey.fetchedAt(), "Routine security advisory", "body", null)));
        assertTrue(readFlags(postKey.id()).embeddingDone(), "Embedding stage must complete");

        // Stage 5 (ReadyPromoter): RAW -> READY + new_post NOTIFY, observed
        // over a real JDBC LISTEN — the load-bearing collector->provider seam
        // (docs/spec/architecture.md §Inter-service communication).
        try (Connection listenConn = dataSource.getConnection()) {
            listenConn.setAutoCommit(true);
            try (Statement s = listenConn.createStatement()) {
                s.execute("LISTEN " + ReadyPromoter.NEW_POST_CHANNEL);
            }
            PGConnection pg = listenConn.unwrap(PGConnection.class);

            readyPromoter.promoteOne(postKey.id(), postKey.fetchedAt());

            String payload = awaitNotificationForPost(pg, postKey.id());
            assertNotNull(payload,
                "promotion must emit a new_post NOTIFY carrying this post's id");
            assertTrue(payload.contains("\"post_id\":\"" + postKey.id() + "\""),
                "NOTIFY payload must name the promoted post_id: " + payload);
        }

        assertEquals("READY", readFlags(postKey.id()).status(),
            "the post must reach status='READY' after the full pipeline");
    }

    /**
     * Boundary rejection: a feed item lacking both {@code <guid>} and
     * {@code <link>} is rejected inside the Fetcher (RssFeedParser raises),
     * the whole tick fails closed, and NO post row is produced.
     */
    @Test
    void malformedItemWithoutIdentifierProducesNoPost() throws Exception {
        UUID sourceId = seedRssSource("http://127.0.0.1:" + port + "/malformed.xml",
            "M1-416 smoke malformed source");

        // tickOnce catches the Fetcher's RssFeedParseException, logs, and
        // returns — no crash, no rows.
        fetchScheduler.tickOnce(new FetchScheduler.SourceRow(
            sourceId, "http://127.0.0.1:" + port + "/malformed.xml", 1L, "rss"));

        assertEquals(0, countPostsForSource(sourceId),
            "an item with no usable upstream identifier must never produce a post row");
    }

    // ---------- helpers ----------

    private void serveFixture(com.sun.net.httpserver.HttpExchange exchange, Path fixture)
            throws IOException {
        byte[] body = Files.readAllBytes(fixture);
        exchange.getResponseHeaders().add("Content-Type", "application/rss+xml; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private StubLlmProvider stubLlm() {
        return (StubLlmProvider) llmProvider;
    }

    /**
     * Mark every currently in-flight post as already through the Tagger,
     * Entity, and Embedding stages so the global-scan {@code onTick()} calls
     * pick up ONLY this test's freshly-fetched post. status / stage flags are
     * untouched; ReadyPromoter is driven by targeted {@code promoteOne}, so
     * these neutralized rows are never promoted by this test.
     */
    private void neutralizeStrayInFlightPosts() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE post SET tagger_done = TRUE, entity_done = TRUE, embedding_done = TRUE "
                     + "WHERE tagger_done = FALSE OR entity_done = FALSE OR embedding_done = FALSE")) {
            ps.executeUpdate();
        }
    }

    private UUID seedRssSource(String identifier, String displayName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('rss', ?, ?, 'news', ?) RETURNING id")) {
            ps.setString(1, identifier);
            ps.setString(2, displayName);
            ps.setArray(3, conn.createArrayOf("text", new String[] {VOCAB_TAG}));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                UUID id = (UUID) rs.getObject(1);
                seededSources.add(id);
                return id;
            }
        }
    }

    private long countPostsForSource(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT count(*) FROM post WHERE source_id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private PostKey solePostKey(UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, fetched_at FROM post WHERE source_id = ?")) {
            ps.setObject(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new PostKey((UUID) rs.getObject(1), rs.getTimestamp(2).toInstant());
            }
        }
    }

    private PostFlags readFlags(UUID id) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status, stage1_done, stage1_flagged, stage2_done, stage2_verdict, "
                     + "tagger_done, entity_done, embedding_done FROM post WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new PostFlags(
                    rs.getString("status"),
                    rs.getBoolean("stage1_done"),
                    rs.getBoolean("stage1_flagged"),
                    rs.getBoolean("stage2_done"),
                    rs.getString("stage2_verdict"),
                    rs.getBoolean("tagger_done"),
                    rs.getBoolean("entity_done"),
                    rs.getBoolean("embedding_done"));
            }
        }
    }

    /** Poll until Stage 1 flags the post and Stage 2 records its BENIGN release. */
    private void awaitStage2Benign(UUID id) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            PostFlags flags = readFlags(id);
            if (flags.stage1Done() && flags.stage2Done()) {
                return;
            }
            Thread.sleep(25);
        }
        fail("timeout: post never reached stage2_done within 10s");
    }

    private String awaitNotificationForPost(PGConnection pg, UUID postId) throws Exception {
        long deadlineNanos = System.nanoTime() + 10_000_000_000L;
        String marker = "\"post_id\":\"" + postId + "\"";
        while (System.nanoTime() < deadlineNanos) {
            PGNotification[] batch = pg.getNotifications(500);
            if (batch != null) {
                for (PGNotification n : batch) {
                    if (n.getParameter().contains(marker)) {
                        return n.getParameter();
                    }
                }
            }
        }
        return null;
    }

    private record PostKey(UUID id, Instant fetchedAt) {
    }

    private record PostFlags(String status, boolean stage1Done, boolean stage1Flagged,
                             boolean stage2Done, String stage2Verdict, boolean taggerDone,
                             boolean entityDone, boolean embeddingDone) {
    }

    /**
     * Marker profile that boots this IT in its own Quarkus instance, so the
     * module-global LLM / embedding stub beans start with empty FIFO queues
     * — isolating this test from queue state a sibling IT might leave behind
     * (see class javadoc §Embedding).
     */
    public static final class SmokeTestProfile implements QuarkusTestProfile {
    }

    /**
     * Test-only {@link RssFetcher} that hits the loopback fixture with a
     * plain {@link HttpClient} (bypassing the SSRF gate) and re-uses
     * {@link RssFeedParser} so the parsed shape matches production. A
     * malformed feed's {@link RssFeedParser.RssFeedParseException} propagates
     * out of {@link #fetch} to FetchScheduler.tickOnce, which fails the tick
     * closed. Mirrors FetchSchedulerIT's TestRssFetcher.
     */
    private static final class TestRssFetcher extends RssFetcher {
        private final HttpClient httpClient = HttpClient.newHttpClient();

        @Override
        public List<NormalizedPost> fetch(long dispatchKey, String identifier) {
            Instant fetchedAt = Instant.now();
            try {
                HttpResponse<byte[]> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(identifier)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new RuntimeException(
                        "TestRssFetcher: HTTP " + response.statusCode() + " from " + identifier);
                }
                return RssFeedParser.parse(dispatchKey, response.body(), fetchedAt);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new RuntimeException("TestRssFetcher: I/O failure", e);
            }
        }
    }
}
