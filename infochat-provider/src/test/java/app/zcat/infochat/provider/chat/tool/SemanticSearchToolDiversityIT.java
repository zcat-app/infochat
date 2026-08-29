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
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral assertions for the fused-window selection in
 * {@link SemanticSearchTool}: the per-source diversity cap over the arm
 * pool, its inertness when the window fits, and the controls it must not
 * disturb (world isolation, byte budget, D19 determinism). Named {@code *IT}
 * (failsafe phase) because it boots the pgvector DevServices DB — same rig
 * as {@link SemanticSearchToolHybridIT}: direct construction with an
 * explicit limit of 16 (the value the wiring pin freezes as the shipped
 * default), stub embedder, PREFIX fixtures with controlled angles, cleanup
 * before AND after each test.
 *
 * <p>Fixture arithmetic for the skew case (the reproduction): 8 "a" posts
 * match BOTH arms (phrase title + near embedding) so their fused scores
 * (~2/(60+rank)) dominate; 8 "f" posts are lexical-only siblings scoring
 * 1/(61..68); 8 "b" posts on source B are semantic-only at angle 0.30
 * (distance ~0.045, inside the 0.5 threshold) scoring 1/(69..76). The three
 * score bands never overlap, so today's plain top-16 fused window is ALL
 * source-A — and under the cap it is exactly 8 A + 8 B.
 */
@QuarkusTest
class SemanticSearchToolDiversityIT {

    private static final String PREFIX = "diversity-search-test/";
    private static final Instant FETCHED_AT = Instant.parse("2026-05-22T12:00:00Z");
    private static final int DIMENSION = 768;
    private static final double THRESHOLD = 0.5;
    private static final int LIMIT = 16;
    private static final float[] QUERY_VECTOR = vectorAtAngle(0);
    private static final String QUERY = "quantum router exploit";
    private static final Pattern UID_PATTERN = Pattern.compile("\\\"uid\\\":\\\"([^\\\"]+)\\\"");

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    CancellationService cancellationService;

    private StubEmbedder stubEmbedder;
    private CannedTranslationProvider cannedProvider;
    private SemanticSearchTool tool;

    private static float[] vectorAtAngle(double theta) {
        float[] v = new float[DIMENSION];
        v[0] = (float) Math.cos(theta);
        v[1] = (float) Math.sin(theta);
        return v;
    }

    static class StubEmbedder implements EmbeddingProvider {
        @Override
        public List<EmbeddingResult> embed(List<String> texts) {
            return List.of(new EmbeddingResult(QUERY_VECTOR));
        }
    }

    /** Same canned-anchor rig as SemanticSearchToolHybridIT: real translator, stub LLM. */
    static class CannedTranslationProvider implements LlmProvider {
        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            return new LlmResponse(QUERY);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        stubEmbedder = new StubEmbedder();
        cannedProvider = new CannedTranslationProvider();
        QueryAnchorTranslator translator = new QueryAnchorTranslator(
                new LlmRouter(
                        List.of(new LlmRouter.Entry("stub", cannedProvider, Set.of())),
                        LlmRouter.ConfigReader.fromMap(Map.of())),
                new QueryTranslationCache(),
                new LlmCircuitBreakerRegistry(3, 30_000, Clock.systemUTC(),
                        LlmRouter.ConfigReader.fromMap(Map.of())),
                500);
        tool = new SemanticSearchTool(
                dataSource, cancellationService, stubEmbedder, translator, THRESHOLD, LIMIT);
        deleteFixtures();
    }

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

    // The reproduction: one dominant source fills today's fused window even
    // though in-world B candidates sit in the arm pool. Under the cap the
    // same pool yields at most half the window from A while B alternatives
    // exist — and every returned B row is a row today's outer LIMIT dropped.
    @Test
    void fusedWindowCapsSingleSourceDominance() throws Exception {
        UUID userId = seedUser("cap");
        UUID sourceA = seedSource("a-src", "Dominant source");
        UUID sourceB = seedSource("b-src", "Minority source");
        seedSubscription("dm", userId, sourceA);
        seedSubscription("dm", userId, sourceB);
        seedSkewPool(sourceA, sourceB);

        String json = tool.execute(userId, "dm", userId, Map.of("query", QUERY));

        List<String> uids = extractUids(json);
        assertEquals(LIMIT, uids.size(), "the window stays at the limit; got: " + json);
        long aCount = uids.stream().map(this::slugOf)
                .filter(slug -> slug.startsWith("a") || slug.startsWith("f")).count();
        long bCount = uids.stream().map(this::slugOf)
                .filter(slug -> slug.startsWith("b")).count();
        assertTrue(aCount <= 8,
                "no source may take more than half the capped window while other "
                        + "in-world candidates exist; got " + aCount + " A posts: " + json);
        assertTrue(bCount >= 8,
                "over-cap A rows must be replaced by B rows already in the arm "
                        + "pool, in fused order; got " + bCount + " B posts: " + json);
        int lastA = lastIndexOfPrefix(uids, "a");
        int firstB = firstIndexOfPrefix(uids, "b");
        assertTrue(lastA >= 0 && firstB > lastA,
                "the window keeps fused order: the whole A block precedes the "
                        + "lower-scored B block; got: " + json);
    }

    // Inertness pin: when the pool is not skewed past the cap, the window is
    // byte-identical to the pre-change fused ORDER BY output — golden JSON
    // asserted verbatim: the set and order are the pre-change fused ORDER
    // BY, and the entry shape carries the landed body_summary field.
    @Test
    void fittingWindowRendersThePreChangeFusedOrder() throws Exception {
        UUID userId = seedUser("fitting");
        UUID sourceG = seedSource("g-src", "Fitting G");
        UUID sourceH = seedSource("h-src", "Fitting H");
        UUID sourceJ = seedSource("j-src", "Fitting J");
        seedSubscription("dm", userId, sourceG);
        seedSubscription("dm", userId, sourceH);
        seedSubscription("dm", userId, sourceJ);
        double[] angles = {0.05, 0.10, 0.15, 0.06, 0.11, 0.16, 0.07, 0.12,
                0.17, 0.08, 0.13, 0.18, 0.09, 0.14, 0.19, 0.20};
        String[] sources = {"g", "h", "j"};
        for (int i = 0; i < angles.length; i++) {
            UUID postId = seedPost(sources[i % 3] + "-fit-" + i, sourceOf(sources[i % 3], sourceG, sourceH, sourceJ),
                    "Alpha beta gamma digest " + i, "Nothing the query mentions.", "READY");
            seedEmbedding(postId, vectorAtAngle(angles[i]));
        }

        String json = tool.execute(userId, "dm", userId, Map.of("query", QUERY));

        assertEquals(GOLDEN_FITTING_JSON, json,
                "an unskewed multi-source window must render exactly the set and "
                        + "order the pre-change fused ORDER BY produced");
    }

    // Starvation failure mode: a single-source world must still fill the
    // window to the limit — the cap re-admits over-cap rows in fused order
    // when no alternative exists.
    @Test
    void singleSourceWorldStillFillsTheWindow() throws Exception {
        UUID userId = seedUser("starve");
        UUID onlySource = seedSource("only-src", "Only source");
        seedSubscription("dm", userId, onlySource);
        for (int i = 1; i <= 20; i++) {
            UUID postId = seedPost("only-" + i, onlySource,
                    "Quantum router exploit brief " + i, "Body " + i + ".", "READY");
            seedEmbedding(postId, vectorAtAngle(0.02 * i));
        }

        String json = tool.execute(userId, "dm", userId, Map.of("query", QUERY));

        List<String> uids = extractUids(json);
        assertEquals(LIMIT, uids.size(),
                "the cap must never shrink a single-source window; got: " + json);
    }

    // Isolation failure mode: the diversity pass reselects ONLY rows the
    // world-filtered arms returned — an unsubscribed source whose posts
    // embed NEAREST the query and a RAW post from the subscribed dominant
    // source must never surface, even under active capping.
    @Test
    void cappedWindowNeverSurfacesOutOfWorldOrNonReadyPosts() throws Exception {
        UUID userId = seedUser("hostile");
        UUID sourceA = seedSource("ha-src", "Hostile dominant source");
        UUID sourceB = seedSource("hb-src", "Hostile minority source");
        UUID outsider = seedSource("hc-src", "Unsubscribed nearest source");
        seedSubscription("dm", userId, sourceA);
        seedSubscription("dm", userId, sourceB);
        seedSkewPool(sourceA, sourceB);
        for (int i = 1; i <= 3; i++) {
            UUID postId = seedPost("c-out-" + i, outsider,
                    "Quantum router exploit outsider " + i, "Outsider body.", "READY");
            seedEmbedding(postId, vectorAtAngle(0.001 * i));
        }
        seedPost("raw-leak", sourceA, "Quantum router exploit raw", "Raw body.", "RAW");

        String json = tool.execute(userId, "dm", userId, Map.of("query", QUERY));

        assertFalse(json.contains(PREFIX + "c-out-"),
                "an unsubscribed source must never surface through the capped "
                        + "window; got: " + json);
        assertFalse(json.contains(PREFIX + "raw-leak"),
                "a non-READY post must never surface through the capped window; "
                        + "got: " + json);
        long bCount = extractUids(json).stream().map(this::slugOf)
                .filter(slug -> slug.startsWith("b")).count();
        assertTrue(bCount >= 8,
                "the cap must still bind under hostile pressure; got " + bCount
                        + " B posts: " + json);
    }

    // Byte-budget failure mode: worst-case-size entries at the widened
    // default stay inside MAX_RESULT_BYTES via order-preserving tail
    // truncation.
    @Test
    void windowAtTheNewDefaultStaysUnderTheByteBudget() throws Exception {
        UUID userId = seedUser("budget");
        UUID source = seedSource("budget-src", "Budget source");
        seedSubscription("dm", userId, source);
        String fat = "x".repeat(1200);
        for (int i = 1; i <= 18; i++) {
            UUID postId = seedPost(fatPostId(i), "fat-" + i, source,
                    "Quantum router exploit entry " + fat, "Body.", "READY");
            seedEmbedding(postId, vectorAtAngle(0.01 * i));
        }

        String json = tool.execute(userId, "dm", userId, Map.of("query", QUERY));

        assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= SemanticSearchTool.MAX_RESULT_BYTES,
                "the aggregate result must stay within MAX_RESULT_BYTES");
        assertTrue(json.startsWith("[") && json.endsWith("]"),
                "tail truncation must leave a well-formed JSON array");
        int entries = extractUids(json).size();
        assertTrue(entries > 0 && entries < 18,
                "the budget loop must truncate before all seeded entries fit; got "
                        + entries + " entries");
        assertTrue(json.contains("\"uid\":\"" + PREFIX + "fat-1\""),
                "truncation preserves fused order from the head; got: " + json);
    }

    // D19 determinism under the cap: same DB state, two consecutive calls,
    // byte-identical output on a fixture that exercises the cap.
    @Test
    void fusedResultIsByteIdenticalAcrossConsecutiveCallsOnUnchangedDb() throws Exception {
        UUID userId = seedUser("determinism");
        UUID sourceA = seedSource("da-src", "Determinism dominant source");
        UUID sourceB = seedSource("db-src", "Determinism minority source");
        seedSubscription("dm", userId, sourceA);
        seedSubscription("dm", userId, sourceB);
        seedSkewPool(sourceA, sourceB);

        String first = tool.execute(userId, "dm", userId, Map.of("query", QUERY));
        String second = tool.execute(userId, "dm", userId, Map.of("query", QUERY));

        assertEquals(first, second,
                "the capped window must be byte-identical across two consecutive "
                        + "calls on unchanged DB state (D19)");
    }

    /**
     * The deterministic dominance pool: 8 doubly-matched A posts ("a",
     * phrase + near embedding), 8 lexical-only A siblings ("f", phrase, no
     * embedding), 8 semantic-only B posts ("b", angle 0.30 inside the
     * threshold, no phrase). Each arm holds exactly LIMIT candidates, so
     * membership is fully determined by the phrase/angle placement.
     */
    private void seedSkewPool(UUID sourceA, UUID sourceB) throws Exception {
        for (int i = 1; i <= 8; i++) {
            UUID postId = seedPost("a-dom-" + i, sourceA,
                    "Quantum router exploit advisory " + i, "Advisory body " + i + ".", "READY");
            seedEmbedding(postId, vectorAtAngle(0.01 * i));
        }
        for (int i = 1; i <= 8; i++) {
            seedPost("f-lex-" + i, sourceA,
                    "Quantum router exploit digest " + i, "Digest body " + i + ".", "READY");
        }
        for (int i = 1; i <= 8; i++) {
            UUID postId = seedPost("b-min-" + i, sourceB,
                    "Unrelated harvest report " + i, "Harvest body " + i + ".", "READY");
            seedEmbedding(postId, vectorAtAngle(0.30));
        }
    }

    private UUID sourceOf(String key, UUID g, UUID h, UUID j) {
        return switch (key) {
            case "g" -> g;
            case "h" -> h;
            case "j" -> j;
            default -> throw new IllegalArgumentException(key);
        };
    }

    // Every fitting-window post seeds READY at FETCHED_AT, so each golden
    // entry carries that instant between url and similarity — derived from
    // the seeding constant, never free-typed.
    private static final String GOLDEN_READY_AT = ",\"ready_at\":\"" + FETCHED_AT + "\"";

    // Every fitting-window post seeds the same body with NULL summary/en —
    // the anchored fallback surfaces it verbatim per entry (within the
    // per-entry cap); derived from seeding like GOLDEN_READY_AT.
    private static final String GOLDEN_BODY_SUMMARY =
            ",\"body_summary\":\"Nothing the query mentions.\"";

    private static final String GOLDEN_FITTING_JSON =
            "[{\"uid\":\"diversity-search-test/g-fit-0\",\"title\":\"Alpha beta gamma digest 0\","
            + "\"url\":\"https://example.com/g-fit-0\"" + GOLDEN_READY_AT + ",\"similarity\":0.99875027"
            + GOLDEN_BODY_SUMMARY + "},"
            + "{\"uid\":\"diversity-search-test/g-fit-3\",\"title\":\"Alpha beta gamma digest 3\","
            + "\"url\":\"https://example.com/g-fit-3\"" + GOLDEN_READY_AT + ",\"similarity\":0.99820054"
            + GOLDEN_BODY_SUMMARY + "},"
            + "{\"uid\":\"diversity-search-test/g-fit-6\",\"title\":\"Alpha beta gamma digest 6\","
            + "\"url\":\"https://example.com/g-fit-6\"" + GOLDEN_READY_AT + ",\"similarity\":0.99755096"
            + GOLDEN_BODY_SUMMARY + "},"
            + "{\"uid\":\"diversity-search-test/g-fit-9\",\"title\":\"Alpha beta gamma digest 9\","
            + "\"url\":\"https://example.com/g-fit-9\"" + GOLDEN_READY_AT + ",\"similarity\":0.9968017"
            + GOLDEN_BODY_SUMMARY + "},"
            + "{\"uid\":\"diversity-search-test/g-fit-12\",\"title\":\"Alpha beta gamma digest 12\","
            + "\"url\":\"https://example.com/g-fit-12\"" + GOLDEN_READY_AT + ",\"similarity\":0.9959527"
            + GOLDEN_BODY_SUMMARY + "},"
            + "{\"uid\":\"diversity-search-test/h-fit-1\",\"title\":\"Alpha beta gamma digest 1\","
            + "\"url\":\"https://example.com/h-fit-1\"" + GOLDEN_READY_AT + ",\"similarity\":0.9950042"
            + GOLDEN_BODY_SUMMARY + "},"
            + "{\"uid\":\"diversity-search-test/h-fit-4\",\"title\":\"Alpha beta gamma digest 4\","
            + "\"url\":\"https://example.com/h-fit-4\"" + GOLDEN_READY_AT + ",\"similarity\":0.9939561"
            + GOLDEN_BODY_SUMMARY + "},"
            + "{\"uid\":\"diversity-search-test/h-fit-7\",\"title\":\"Alpha beta gamma digest 7\","
            + "\"url\":\"https://example.com/h-fit-7\"" + GOLDEN_READY_AT + ",\"similarity\":0.99280864"
            + GOLDEN_BODY_SUMMARY + "},"
            + "{\"uid\":\"diversity-search-test/h-fit-10\",\"title\":\"Alpha beta gamma digest 10\","
            + "\"url\":\"https://example.com/h-fit-10\"" + GOLDEN_READY_AT + ",\"similarity\":0.9915619"
            + GOLDEN_BODY_SUMMARY + "},"
            + "{\"uid\":\"diversity-search-test/h-fit-13\",\"title\":\"Alpha beta gamma digest 13\","
            + "\"url\":\"https://example.com/h-fit-13\"" + GOLDEN_READY_AT + ",\"similarity\":0.990216"
            + GOLDEN_BODY_SUMMARY + "},"
            + "{\"uid\":\"diversity-search-test/j-fit-2\",\"title\":\"Alpha beta gamma digest 2\","
            + "\"url\":\"https://example.com/j-fit-2\"" + GOLDEN_READY_AT + ",\"similarity\":0.9887711"
            + GOLDEN_BODY_SUMMARY + "},"
            + "{\"uid\":\"diversity-search-test/j-fit-5\",\"title\":\"Alpha beta gamma digest 5\","
            + "\"url\":\"https://example.com/j-fit-5\"" + GOLDEN_READY_AT + ",\"similarity\":0.98722726"
            + GOLDEN_BODY_SUMMARY + "},"
            + "{\"uid\":\"diversity-search-test/j-fit-8\",\"title\":\"Alpha beta gamma digest 8\","
            + "\"url\":\"https://example.com/j-fit-8\"" + GOLDEN_READY_AT + ",\"similarity\":0.9855848"
            + GOLDEN_BODY_SUMMARY + "},"
            + "{\"uid\":\"diversity-search-test/j-fit-11\",\"title\":\"Alpha beta gamma digest 11\","
            + "\"url\":\"https://example.com/j-fit-11\"" + GOLDEN_READY_AT + ",\"similarity\":0.9838437"
            + GOLDEN_BODY_SUMMARY + "},"
            + "{\"uid\":\"diversity-search-test/j-fit-14\",\"title\":\"Alpha beta gamma digest 14\","
            + "\"url\":\"https://example.com/j-fit-14\"" + GOLDEN_READY_AT + ",\"similarity\":0.9820042"
            + GOLDEN_BODY_SUMMARY + "},"
            + "{\"uid\":\"diversity-search-test/g-fit-15\",\"title\":\"Alpha beta gamma digest 15\","
            + "\"url\":\"https://example.com/g-fit-15\"" + GOLDEN_READY_AT + ",\"similarity\":0.9800666"
            + GOLDEN_BODY_SUMMARY + "}]";

    private List<String> extractUids(String json) {
        List<String> uids = new ArrayList<>();
        Matcher matcher = UID_PATTERN.matcher(json);
        while (matcher.find()) {
            uids.add(matcher.group(1));
        }
        return uids;
    }

    private String slugOf(String uid) {
        return uid.substring(PREFIX.length());
    }

    private int firstIndexOfPrefix(List<String> uids, String prefix) {
        for (int i = 0; i < uids.size(); i++) {
            if (slugOf(uids.get(i)).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private int lastIndexOfPrefix(List<String> uids, String prefix) {
        int found = -1;
        for (int i = 0; i < uids.size(); i++) {
            if (slugOf(uids.get(i)).startsWith(prefix)) {
                found = i;
            }
        }
        return found;
    }

    // ---------- helpers (SemanticSearchToolHybridIT rig) ----------

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

    // Byte-ordered fat-fixture ids: the identical titles tie ts_rank, so
    // the lexical arm breaks on post_id ASC — both arms then rank by
    // angle, making the byte-budget truncation head fat-1 by construction
    // (D19) instead of by random-UUID luck.
    private static UUID fatPostId(int i) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-0000000000%02x", i));
    }

    private UUID seedPost(String slug, UUID sourceId, String title, String body,
                          String status) throws Exception {
        return seedPost(UUID.randomUUID(), slug, sourceId, title, body, status);
    }

    private UUID seedPost(UUID id, String slug, UUID sourceId, String title, String body,
                          String status) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post (id, uid, source_id, title, body, url, published_at, "
                     + "fetched_at, status, ready_at, tags, upstream_identifier) "
                     + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '{}', ?) RETURNING id")) {
            ps.setObject(1, id);
            ps.setString(2, PREFIX + slug);
            ps.setObject(3, sourceId);
            ps.setString(4, title);
            ps.setString(5, body);
            ps.setString(6, "https://example.com/" + slug);
            ps.setTimestamp(7, Timestamp.from(FETCHED_AT));
            ps.setTimestamp(8, Timestamp.from(FETCHED_AT));
            ps.setString(9, status);
            ps.setTimestamp(10, "READY".equals(status) ? Timestamp.from(FETCHED_AT) : null);
            ps.setString(11, PREFIX + slug);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void seedEmbedding(UUID postId, float[] embedding) throws Exception {
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
