package app.zcat.infochat.provider.chat.tool;

import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.llm.EmbeddingResult;
import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmCircuitBreakerRegistry;
import app.zcat.infochat.llm.routing.LlmRouter;
import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import app.zcat.infochat.provider.translation.QueryTranslationCache;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral assertions for {@link SemanticSearchTool} against the real
 * pgvector DevServices DB (cosine ordering, the distance threshold, and
 * subscription scoping are pgvector SQL behaviours a fake DataSource
 * cannot exercise). Named {@code *IT} (failsafe phase) because it boots
 * DevServices Postgres — integration-shaped per design 08-verification
 * §8.2, enforced by the M1-495 naming-guard ratchet. The tool under test
 * is constructed directly with a stub {@link EmbeddingProvider} returning
 * a fixed query vector, so no embedding backend is contacted; fixtures
 * are seeded via JDBC with vectors of known cosine distance to that
 * query vector.
 */
@QuarkusTest
class SemanticSearchToolIT {

    private static final String PREFIX = "semantic-search-test/";
    /** All fixtures share one fetched_at so they land in the V11/V28/V29 May 2026 partition. */
    private static final Instant FETCHED_AT = Instant.parse("2026-05-22T12:00:00Z");
    /** Must match the test DB's post_embedding column type, vector(768) (V11). */
    private static final int DIMENSION = 768;
    private static final double THRESHOLD = 0.5;
    private static final float[] QUERY_VECTOR = vectorAtAngle(0);

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    CancellationService cancellationService;

    private final CountingStubEmbedder stubEmbedder = new CountingStubEmbedder();
    private SemanticSearchTool tool;

    /**
     * Unit vector [cos θ, sin θ, 0, ..., 0]: its cosine distance to
     * {@code QUERY_VECTOR} (θ=0) is exactly 1 − cos θ, so each fixture
     * gets a known distance without hardcoding a 768-float literal.
     */
    private static float[] vectorAtAngle(double theta) {
        float[] v = new float[DIMENSION];
        v[0] = (float) Math.cos(theta);
        v[1] = (float) Math.sin(theta);
        return v;
    }

    static class CountingStubEmbedder implements EmbeddingProvider {
        int embedCalls;

        @Override
        public List<EmbeddingResult> embed(List<String> texts) {
            embedCalls++;
            return List.of(new EmbeddingResult(QUERY_VECTOR));
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        tool = new SemanticSearchTool(
                dataSource, cancellationService, stubEmbedder,
                noOpAnchorTranslator(), THRESHOLD, 8);
        deleteFixtures();
    }

    // No-op anchor translator for the en-default tests: this class seeds
    // no scope_preferences rows, so the tool's language lookup defaults
    // to 'en' and translate() short-circuits before touching any
    // dependency. The provider stub THROWS on any call, so an unexpected
    // translation attempt fails the test loudly instead of silently
    // changing the query the arms receive.
    private static QueryAnchorTranslator noOpAnchorTranslator() {
        return new QueryAnchorTranslator(
                new LlmRouter(
                        List.of(new LlmRouter.Entry("stub", new NeverCalledLlmProvider(), Set.of())),
                        LlmRouter.ConfigReader.fromMap(Map.of())),
                new QueryTranslationCache(),
                new LlmCircuitBreakerRegistry(3, 30_000, Clock.systemUTC(),
                        LlmRouter.ConfigReader.fromMap(Map.of())),
                500);
    }

    private static final class NeverCalledLlmProvider implements LlmProvider {
        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            throw new AssertionError("no translation may be issued on the en-default test path");
        }
    }

    // Failsafe ITs share one Quarkus JVM and one DevServices DB, and the
    // outbox reconciler ITs (NewPostReconciler*IT) count EVERY READY post
    // row — a leftover READY fixture from this class poisons their exact
    // row-count assertions (+3 rows observed on the M1-589 r1 verify). So
    // unlike the surefire-phase SearchPostsToolTest mirror (whose
    // DevServices DB dies with its own JVM), this class must clean AFTER
    // each test as well as before.
    @AfterEach
    void tearDown() throws Exception {
        deleteFixtures();
    }

    private void deleteFixtures() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn,
                "DELETE FROM post_embedding WHERE post_id IN "
                    + "(SELECT id FROM post WHERE uid LIKE '" + PREFIX + "%')");
            exec(conn,
                "DELETE FROM source_subscription WHERE source_id IN "
                    + "(SELECT id FROM source WHERE identifier LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM post WHERE uid LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM source WHERE identifier LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE '" + PREFIX + "%'");
        }
    }

    // Acceptance (a): the k nearest subscribed posts come back in cosine
    // order. Seeded out of distance order so the result order can only
    // come from the SQL distance sort (D19), never insertion order.
    @Test
    void nearestSubscribedPostsReturnedInCosineOrder() throws Exception {
        UUID userId = seedUser("order");
        UUID sourceId = seedSource("order-src", "Order source");
        seedSubscription("dm", userId, sourceId);
        seedEmbeddedPost("mid", sourceId, vectorAtAngle(0.6));   // distance ≈ 0.175
        seedEmbeddedPost("near", sourceId, vectorAtAngle(0.2));  // distance ≈ 0.020
        seedEmbeddedPost("far", sourceId, vectorAtAngle(0.9));   // distance ≈ 0.378

        String json = tool.execute(userId, "dm", userId, Map.of("query", "anything"));

        int nearIdx = json.indexOf(PREFIX + "near");
        int midIdx = json.indexOf(PREFIX + "mid");
        int farIdx = json.indexOf(PREFIX + "far");
        assertTrue(nearIdx >= 0 && midIdx >= 0 && farIdx >= 0,
                "all three subscribed posts sit under the threshold and must be "
                        + "returned; got: " + json);
        assertTrue(nearIdx < midIdx && midIdx < farIdx,
                "results must be ordered by cosine distance ascending "
                        + "(nearest first); got: " + json);
    }

    // Acceptance (b): a post outside the caller's subscription is NEVER
    // returned. The leak candidate is the nearest post of all — well
    // inside the inner probe's top-k AND under the distance threshold —
    // so only the subscription predicate can exclude it (a missing scope
    // filter would surface it and fail this test).
    @Test
    void nearNeighbourOutsideSubscriptionIsNeverReturned() throws Exception {
        UUID userId = seedUser("scope");
        UUID subscribedSource = seedSource("scope-sub-src", "Subscribed source");
        UUID otherSource = seedSource("scope-other-src", "Unsubscribed source");
        seedSubscription("dm", userId, subscribedSource);
        seedEmbeddedPost("in-scope-post", subscribedSource, vectorAtAngle(0.6));
        seedEmbeddedPost("leak-candidate", otherSource, vectorAtAngle(0.2));

        String json = tool.execute(userId, "dm", userId, Map.of("query", "anything"));

        assertTrue(json.contains(PREFIX + "in-scope-post"),
                "the subscribed post under the threshold must be returned; got: " + json);
        assertFalse(json.contains(PREFIX + "leak-candidate"),
                "a post outside the caller's subscribed sources must NEVER surface, "
                        + "even as the nearest neighbour; got: " + json);
    }

    // Acceptance (c): candidates beyond the distance threshold are
    // excluded — an empty result is the general-knowledge path.
    @Test
    void candidatesBeyondDistanceThresholdAreExcluded() throws Exception {
        UUID userId = seedUser("threshold");
        UUID sourceId = seedSource("threshold-src", "Threshold source");
        seedSubscription("dm", userId, sourceId);
        // 1 − cos(1.4) ≈ 0.830 > THRESHOLD (0.5): subscribed, in the inner
        // top-k, excluded only by the relevance threshold.
        seedEmbeddedPost("too-far", sourceId, vectorAtAngle(1.4));

        String json = tool.execute(userId, "dm", userId, Map.of("query", "anything"));

        assertEquals("[]", json,
                "nothing under the distance threshold must yield an empty result "
                        + "(the general-knowledge path); got: " + json);
    }

    // Acceptance (e): crowding recall — a subscribed post under the
    // threshold must remain retrievable even when MORE than limit×4
    // semantically-nearer posts sit in UNSUBSCRIBED sources. Under the
    // superseded global-top-k over-fetch shape the 40 nearer unsubscribed
    // posts fill the entire inner probe (8×4=32) and the subscribed post
    // is starved out (red); the iterative filtered index scan walks the
    // index until LIMIT rows survive the subscription filter, so retrieval
    // is exact over the caller-visible corpus (green). This is also what
    // makes observed recall carry no signal about unsubscribed-content
    // density (redteam 2026-07-11 out-of-model item, eliminated).
    @Test
    void subscribedPostSurvivesCrowdingByNearerUnsubscribedNeighbours() throws Exception {
        UUID userId = seedUser("crowd");
        UUID subscribedSource = seedSource("crowd-sub-src", "Subscribed source");
        UUID otherSource = seedSource("crowd-other-src", "Unsubscribed source");
        seedSubscription("dm", userId, subscribedSource);
        // 40 > limit(8) × PROBE_OVERFETCH(4) nearer posts, all unsubscribed.
        for (int i = 0; i < 40; i++) {
            seedEmbeddedPost("crowd-" + i, otherSource, vectorAtAngle(0.2));
        }
        // The only subscribed post: farther than the crowd, still under the
        // 0.5 threshold (1 − cos(0.6) ≈ 0.175).
        seedEmbeddedPost("wanted", subscribedSource, vectorAtAngle(0.6));

        String json = tool.execute(userId, "dm", userId, Map.of("query", "anything"));

        assertTrue(json.contains(PREFIX + "wanted"),
                "the subscribed under-threshold post must be retrievable even when "
                        + "the global top-(limit×4) is filled by nearer unsubscribed "
                        + "posts; got: " + json);
        assertFalse(json.contains(PREFIX + "crowd-"),
                "no unsubscribed post may surface; got: " + json);
    }

    // Acceptance (d): the JSON carries displayable fields only — the raw
    // embedding vector is never emitted (D5: embeddings are internal).
    @Test
    void resultCarriesDisplayFieldsAndNeverTheRawVector() throws Exception {
        UUID userId = seedUser("shape");
        UUID sourceId = seedSource("shape-src", "Shape source");
        seedSubscription("dm", userId, sourceId);
        seedEmbeddedPost("shape-post", sourceId, vectorAtAngle(0.2));

        String json = tool.execute(userId, "dm", userId, Map.of("query", "anything"));

        assertTrue(json.matches(
                "\\[\\{\"uid\":\"[^\"]*\",\"title\":\"[^\"]*\",\"url\":\"[^\"]*\","
                        + "\"ready_at\":\"[^\"]*\",\"similarity\":[0-9.]+\\}\\]"),
                "each entry must carry exactly uid/title/url/ready_at/similarity; got: " + json);
        assertFalse(json.contains("embedding"),
                "the raw embedding vector must never be emitted (D5); got: " + json);
    }

    // Reproduction (M1-927): every emitted entry must carry its post's
    // ready_at between url and similarity — per-entry equality against
    // DISTINCT seeded instants catches a dropped or misdated field.
    @Test
    void entriesCarryReadyAtSoTheModelCanDateWhatItServes() throws Exception {
        UUID userId = seedUser("dated");
        UUID sourceId = seedSource("dated-src", "Dated source");
        seedSubscription("dm", userId, sourceId);
        Instant olderReady = Instant.parse("2026-05-01T09:15:00Z");
        Instant newerReady = Instant.parse("2026-05-20T18:45:30Z");
        seedEmbeddedPostReadyAt("dated-old", sourceId, vectorAtAngle(0.2), olderReady);
        seedEmbeddedPostReadyAt("dated-new", sourceId, vectorAtAngle(0.9), newerReady);

        String json = tool.execute(userId, "dm", userId, Map.of("query", "anything"));

        String oldEntry = entryContaining(json, PREFIX + "dated-old");
        assertTrue(oldEntry.matches(
                        "\\{\"uid\":\"[^\"]*\",\"title\":\"[^\"]*\",\"url\":\"[^\"]*\","
                                + "\"ready_at\":\"" + olderReady + "\","
                                + "\"similarity\":[0-9.]+\\}"),
                "the dated-old entry must carry exactly its seeded ready_at "
                        + "between url and similarity; got: " + oldEntry);
        String newEntry = entryContaining(json, PREFIX + "dated-new");
        assertTrue(newEntry.matches(
                        "\\{\"uid\":\"[^\"]*\",\"title\":\"[^\"]*\",\"url\":\"[^\"]*\","
                                + "\"ready_at\":\"" + newerReady + "\","
                                + "\"similarity\":[0-9.]+\\}"),
                "the dated-new entry must carry exactly its seeded ready_at "
                        + "between url and similarity; got: " + newEntry);
    }

    // Reproduction (M1-938 tool half): the windowed fused search bounds
    // BOTH arms to ready_at at the cutoff — PT2H returns EXACTLY the
    // in-window post; the SAME fixture WITHOUT _window keeps both.
    @Test
    void windowedFusedSearchBoundsBothArmsToReadyAtCutoff() throws Exception {
        UUID userId = seedUser("windowed");
        UUID sourceId = seedSource("windowed-src", "Windowed source");
        seedSubscription("dm", userId, sourceId);
        Instant freshReady = Instant.now().minus(Duration.ofMinutes(30));
        Instant oldReady = Instant.now().minus(Duration.ofHours(3));
        seedEmbeddedPostReadyAt("windowed-fresh", sourceId, vectorAtAngle(0.2), freshReady);
        seedEmbeddedPostReadyAt("windowed-old", sourceId, vectorAtAngle(0.3), oldReady);

        String windowed = tool.execute(userId, "dm", userId,
                Map.of("query", "anything", "_window", "PT2H"));

        assertTrue(windowed.contains(PREFIX + "windowed-fresh"),
                "the in-window post must survive the windowed fused search; got: " + windowed);
        assertFalse(windowed.contains(PREFIX + "windowed-old"),
                "a post whose ready_at sits OUTSIDE the dispatched window must never "
                        + "surface in the windowed fused search; got: " + windowed);

        String unwindowed = tool.execute(userId, "dm", userId, Map.of("query", "anything"));

        assertTrue(unwindowed.contains(PREFIX + "windowed-fresh")
                        && unwindowed.contains(PREFIX + "windowed-old"),
                "the SAME fixture without _window must keep the unwindowed behavior "
                        + "(both posts) — the predicate is conditional, never "
                        + "always-on; got: " + unwindowed);
    }

    // FAILURE-MODE isolation (M1-938, the M1-589 leak class): an
    // UNSUBSCRIBED source's fresh near-match and a non-READY fresh post,
    // both INSIDE the window, never surface (AND-composed in-arm).
    @Test
    void windowedArmsNeverSurfaceOutOfWorldOrNonReadyPosts() throws Exception {
        UUID userId = seedUser("wiso");
        UUID subscribedSource = seedSource("wiso-sub-src", "Subscribed source");
        UUID otherSource = seedSource("wiso-other-src", "Unsubscribed source");
        seedSubscription("dm", userId, subscribedSource);
        Instant fresh = Instant.now().minus(Duration.ofMinutes(30));
        seedEmbeddedPostReadyAt("wiso-leak", otherSource, vectorAtAngle(0.2), fresh);
        seedEmbeddedPost("wiso-notready", subscribedSource, vectorAtAngle(0.3),
                fresh, "RAW");
        seedEmbeddedPostReadyAt("wiso-wanted", subscribedSource, vectorAtAngle(0.6),
                fresh);

        String json = tool.execute(userId, "dm", userId,
                Map.of("query", "anything", "_window", "PT2H"));

        assertTrue(json.contains(PREFIX + "wiso-wanted"),
                "the subscribed READY in-window post must be returned; got: " + json);
        assertFalse(json.contains(PREFIX + "wiso-leak"),
                "an UNSUBSCRIBED source's post must never surface, windowed or not; "
                        + "got: " + json);
        assertFalse(json.contains(PREFIX + "wiso-notready"),
                "a non-READY post must never surface even inside the window; "
                        + "got: " + json);
    }

    // D19 windowed determinism (M1-938): two identical windowed executions
    // on an unchanged DB return byte-identical JSON under a pinned clock —
    // the window adds no nondeterminism to the fused set/order.
    @Test
    void windowedFusedResultIsByteIdenticalAcrossConsecutiveCallsOnUnchangedDb()
            throws Exception {
        UUID userId = seedUser("wdet");
        UUID sourceId = seedSource("wdet-src", "Determinism source");
        seedSubscription("dm", userId, sourceId);
        Instant fixedNow = Instant.parse("2026-08-29T09:00:00Z");
        tool.clock = Clock.fixed(fixedNow, java.time.ZoneOffset.UTC);
        Instant freshReady = fixedNow.minus(Duration.ofMinutes(30));
        Instant oldReady = fixedNow.minus(Duration.ofHours(3));
        seedEmbeddedPostReadyAt("wdet-fresh", sourceId, vectorAtAngle(0.2), freshReady);
        seedEmbeddedPostReadyAt("wdet-old", sourceId, vectorAtAngle(0.3), oldReady);

        String first = tool.execute(userId, "dm", userId,
                Map.of("query", "anything", "_window", "PT2H"));
        String second = tool.execute(userId, "dm", userId,
                Map.of("query", "anything", "_window", "PT2H"));

        assertEquals(first, second,
                "identical windowed calls on an unchanged DB must be byte-identical "
                        + "(D19); first: " + first + " second: " + second);
    }

    // The '{'-to-'}' span around a uid occurrence — entries are flat JSON
    // objects, so the nearest braces bound the whole entry.
    private static String entryContaining(String json, String uid) {
        int at = json.indexOf(uid);
        assertTrue(at >= 0, "no entry for " + uid + " in: " + json);
        return json.substring(json.lastIndexOf('{', at), json.indexOf('}', at) + 1);
    }

    // Boundary validation: a missing/blank query rejects before any embed
    // call or SQL runs (the dispatcher surfaces this as a typed
    // ValidationError the model can self-correct on).
    @Test
    void missingQueryRejectsBeforeAnyEmbedCall() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(UUID.randomUUID(), "dm", UUID.randomUUID(), Map.of()));
        assertEquals(0, stubEmbedder.embedCalls,
                "a rejected call must not reach the embedding backend");
    }

    // ---------- helpers ----------

    private UUID seedUser(String suffix) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                     + "VALUES ('inmemory', ?, FALSE, 'vouched') RETURNING id")) {
            ps.setString(1, PREFIX + suffix);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID seedSource(String suffix, String displayName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, "
                     + "bootstrap_tags, status) "
                     + "VALUES ('rss', ?, ?, 'news', '{}', 'active') RETURNING id")) {
            ps.setString(1, PREFIX + suffix);
            ps.setString(2, displayName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void seedSubscription(String scopeKind, UUID scopeId, UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source_subscription (scope_kind, scope_id, source_id) "
                     + "VALUES (?, ?, ?)")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setObject(3, sourceId);
            ps.executeUpdate();
        }
    }

    /** READY post + its embedding row, sharing FETCHED_AT so both land in the May 2026 partitions. */
    private void seedEmbeddedPost(String slug, UUID sourceId, float[] embedding) throws Exception {
        seedEmbeddedPostReadyAt(slug, sourceId, embedding, FETCHED_AT);
    }

    /** Same, with the post's own READY-transition instant (M1-927 dating fixture). */
    private void seedEmbeddedPostReadyAt(String slug, UUID sourceId, float[] embedding,
                                         Instant readyAt) throws Exception {
        seedEmbeddedPost(slug, sourceId, embedding, readyAt, "READY");
    }

    /** Same, with an explicit status — the isolation fixtures seed non-READY posts (M1-938). */
    private void seedEmbeddedPost(String slug, UUID sourceId, float[] embedding,
                                  Instant readyAt, String status) throws Exception {
        UUID postId;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post (uid, source_id, title, body, url, published_at, "
                     + "fetched_at, status, ready_at, tags, upstream_identifier) "
                     + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '{}', ?) RETURNING id")) {
            ps.setString(1, PREFIX + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "Title " + slug);
            ps.setString(4, "Body " + slug);
            ps.setString(5, "https://example.com/" + slug);
            ps.setTimestamp(6, Timestamp.from(FETCHED_AT));
            ps.setTimestamp(7, Timestamp.from(FETCHED_AT));
            ps.setString(8, status);
            ps.setTimestamp(9, Timestamp.from(readyAt));
            ps.setString(10, PREFIX + slug);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                postId = (UUID) rs.getObject(1);
            }
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post_embedding (post_id, embedding, embedding_model, fetched_at) "
                     + "VALUES (?, ?::vector, 'nomic-embed-text', ?)")) {
            ps.setObject(1, postId);
            ps.setString(2, SemanticSearchTool.toVectorLiteral(embedding));
            ps.setTimestamp(3, Timestamp.from(FETCHED_AT));
            ps.executeUpdate();
        }
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
