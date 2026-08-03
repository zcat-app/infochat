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
import io.quarkus.test.junit.QuarkusMock;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-tool IT for the D59 world predicate (M1-621, acceptance item 2):
 * every RAG retrieval surface — {@link SearchPostsTool},
 * {@link SemanticSearchTool} (both fused arms), {@link GetPostTool},
 * {@link GetReferencesTool} — must see exactly "live, non-excluded
 * bootstrap sources OR the scope's subscriptions", and nothing else.
 * The uid-resolution half proves no post is search-visible but
 * unfetchable. The {@code /summary}-side (EligiblePostQuery,
 * DigestPostCollector) halves live in their own ITs.
 *
 * <p>Clock discipline: {@link SearchPostsTool}'s window cutoff reads the
 * injected Clock, pinned here via {@code QuarkusMock} to
 * {@link #PINNED_NOW}; every fixture instant is absolute (no
 * wall-clock-relative seeds — the ScanWindowFixtureGuardTest contract
 * for new files).
 *
 * <p>Cleanup runs before AND after each test: a bootstrap-origin fixture
 * source is visible to EVERY scope under the world predicate, so one
 * left behind would pollute other classes' scope-isolated assertions.
 */
@QuarkusTest
class RetrievalWorldPredicateIT {

    private static final String PREFIX = "m1-621-world/";
    /** All fixtures share one fetched_at so they land in the May 2026 partition. */
    private static final Instant FETCHED_AT = Instant.parse("2026-05-22T12:00:00Z");
    private static final Instant PINNED_NOW = Instant.parse("2026-05-22T12:00:00Z");
    private static final Instant PUBLISHED_AT = PINNED_NOW.minusSeconds(3600);

    /** Must match the test DB's post_embedding column type, vector(768) (V11). */
    private static final int DIMENSION = 768;
    private static final float[] QUERY_VECTOR = unitVector();

    @Inject @SeedDataSource DataSource dataSource;

    @Inject SearchPostsTool searchPostsTool;
    @Inject GetPostTool getPostTool;
    @Inject GetReferencesTool getReferencesTool;
    @Inject CancellationService cancellationService;

    private SemanticSearchTool semanticSearchTool;

    private static float[] unitVector() {
        float[] v = new float[DIMENSION];
        v[0] = 1.0f;
        return v;
    }

    @BeforeEach
    void setUp() throws Exception {
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
        // Direct construction with a stub embedder (the SemanticSearchToolIT
        // pattern): no embedding backend is contacted; fixture embeddings
        // are seeded at distance 0 to the stub's query vector, well inside
        // the 0.5 threshold.
        semanticSearchTool = new SemanticSearchTool(
                dataSource, cancellationService,
                texts -> List.of(new EmbeddingResult(QUERY_VECTOR)), noOpAnchorTranslator(),
                0.5, 8);
        deleteFixtures();
    }

    // No-op anchor translator: this class seeds no scope_preferences
    // rows, so the tool's language lookup defaults to 'en' and
    // translate() short-circuits. The provider stub THROWS on any call,
    // so an unexpected translation attempt fails the test loudly.
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

    @AfterEach
    void tearDown() throws Exception {
        deleteFixtures();
    }

    private void deleteFixtures() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            exec(conn, "DELETE FROM post_embedding WHERE post_id IN "
                    + "(SELECT id FROM post WHERE uid LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM post_reference WHERE from_post IN "
                    + "(SELECT id FROM post WHERE uid LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM source_exclusion WHERE source_id IN "
                    + "(SELECT id FROM source WHERE identifier LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM source_subscription WHERE source_id IN "
                    + "(SELECT id FROM source WHERE identifier LIKE '" + PREFIX + "%')");
            exec(conn, "DELETE FROM post WHERE uid LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM source WHERE identifier LIKE '" + PREFIX + "%'");
            exec(conn, "DELETE FROM users WHERE contact_id LIKE '" + PREFIX + "%'");
        }
    }

    @Test
    void bootstrapPostReachesSubscriptionlessScopeThroughEveryTool() throws Exception {
        UUID userId = seedUser("boot-actor");
        UUID sourceId = seedSource("boot-src", "bootstrap");
        UUID postA = seedPost("boot-a", sourceId, "Bootstrap quantum breakthrough");
        UUID postB = seedPost("boot-b", sourceId, "Bootstrap follow-up analysis");
        seedReference(postA, postB);
        seedEmbedding(postA);

        // searchPosts: visible with zero subscriptions.
        String search = searchPostsTool.execute(userId, "dm", userId, Map.of());
        assertTrue(search.contains(PREFIX + "boot-a"),
                "bootstrap post surfaces in searchPosts for a subscription-less scope");

        // getPost resolves the uid search returned (acceptance (c): never
        // search-visible but unfetchable).
        String post = getPostTool.execute(userId, "dm", userId, Map.of("uid", PREFIX + "boot-a"));
        assertFalse("null".equals(post), "getPost must resolve a searchable bootstrap uid");
        assertTrue(post.contains("Bootstrap quantum breakthrough"));

        // getReferences traverses edges between bootstrap posts.
        String refs = getReferencesTool.execute(userId, "dm", userId,
                Map.of("uid", PREFIX + "boot-a"));
        assertTrue(refs.contains(PREFIX + "boot-b"),
                "reference edges between bootstrap posts are visible");

        // semanticSearch: the semantic arm (embedded fixture) and lexical
        // arm (tsv keyword) both carry the world predicate.
        String semantic = semanticSearchTool.execute(userId, "dm", userId,
                Map.of("query", "quantum breakthrough"));
        assertTrue(semantic.contains(PREFIX + "boot-a"),
                "the fused hybrid result surfaces the bootstrap post");
    }

    @Test
    void customSourcePostVisibleOnlyToItsSubscriber() throws Exception {
        UUID subscriber = seedUser("cust-subscriber");
        UUID stranger = seedUser("cust-stranger");
        UUID sourceId = seedSource("cust-src", "user");
        UUID postId = seedPost("cust-a", sourceId, "Custom niche newsletter piece");
        seedEmbedding(postId);
        seedSubscription("dm", subscriber, sourceId);

        assertTrue(searchPostsTool.execute(subscriber, "dm", subscriber, Map.of())
                        .contains(PREFIX + "cust-a"),
                "the subscriber sees their custom source's post");
        assertFalse("null".equals(getPostTool.execute(subscriber, "dm", subscriber,
                        Map.of("uid", PREFIX + "cust-a"))),
                "the subscriber can fetch it");

        // Privacy: a different scope never sees a 'user'-origin source.
        assertEquals("[]", searchPostsTool.execute(stranger, "dm", stranger, Map.of()),
                "another scope gets nothing from a custom source via searchPosts");
        assertEquals("null", getPostTool.execute(stranger, "dm", stranger,
                        Map.of("uid", PREFIX + "cust-a")),
                "another scope cannot fetch it (same reply as nonexistent)");
        assertEquals("[]", getReferencesTool.execute(stranger, "dm", stranger,
                        Map.of("uid", PREFIX + "cust-a")),
                "another scope sees no references out of it");
        assertEquals("[]", semanticSearchTool.execute(stranger, "dm", stranger,
                        Map.of("query", "niche newsletter")),
                "neither hybrid arm leaks it to another scope");
    }

    @Test
    void exclusionHidesBootstrapSourceForThatScopeOnly() throws Exception {
        UUID excluder = seedUser("excl-actor");
        UUID other = seedUser("excl-other");
        UUID sourceId = seedSource("excl-src", "bootstrap");
        seedPost("excl-a", sourceId, "Excludable bootstrap story");
        seedExclusion("dm", excluder, sourceId);

        assertEquals("[]", searchPostsTool.execute(excluder, "dm", excluder, Map.of()),
                "the excluding scope no longer sees the bootstrap source's posts");
        assertEquals("null", getPostTool.execute(excluder, "dm", excluder,
                        Map.of("uid", PREFIX + "excl-a")),
                "uid resolution honours the exclusion too");
        assertTrue(searchPostsTool.execute(other, "dm", other, Map.of())
                        .contains(PREFIX + "excl-a"),
                "a different scope still sees it (exclusion is per-scope)");
    }

    // ----- fixtures -------------------------------------------------------

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

    private UUID seedSource(String suffix, String origin) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source (kind, identifier, display_name, category, "
                             + "bootstrap_tags, status, source_origin) "
                             + "VALUES ('rss', ?, ?, 'news', '{}', 'active', ?) RETURNING id")) {
            ps.setString(1, PREFIX + suffix);
            ps.setString(2, suffix);
            ps.setString(3, origin);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID seedPost(String uidSuffix, UUID sourceId, String title) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post (uid, source_id, title, body, published_at, ready_at, "
                             + "fetched_at, status, tags, upstream_identifier) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, 'READY', ?, ?) RETURNING id")) {
            ps.setString(1, PREFIX + uidSuffix);
            ps.setObject(2, sourceId);
            ps.setString(3, title);
            ps.setString(4, "Body: " + title);
            ps.setTimestamp(5, Timestamp.from(PUBLISHED_AT));
            ps.setTimestamp(6, Timestamp.from(PUBLISHED_AT));
            ps.setTimestamp(7, Timestamp.from(FETCHED_AT));
            ps.setArray(8, conn.createArrayOf("TEXT", new String[0]));
            ps.setString(9, PREFIX + uidSuffix);
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

    private void seedExclusion(String scopeKind, UUID scopeId, UUID sourceId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO source_exclusion (scope_kind, scope_id, source_id) "
                             + "VALUES (?, ?, ?)")) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            ps.setObject(3, sourceId);
            ps.executeUpdate();
        }
    }

    private void seedReference(UUID fromPost, UUID toPost) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     // link_type is CHECK-constrained to the V29 closed set
                     // ('entity','semantic','repost').
                     "INSERT INTO post_reference (from_post, to_post, link_type, score, created_at) "
                             + "VALUES (?, ?, 'entity', 0.9, ?)")) {
            ps.setObject(1, fromPost);
            ps.setObject(2, toPost);
            ps.setTimestamp(3, Timestamp.from(FETCHED_AT));
            ps.executeUpdate();
        }
    }

    private void seedEmbedding(UUID postId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO post_embedding (post_id, embedding, embedding_model, fetched_at) "
                             + "VALUES (?, ?::vector, 'nomic-embed-text', ?)")) {
            ps.setObject(1, postId);
            ps.setString(2, SemanticSearchTool.toVectorLiteral(QUERY_VECTOR));
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
