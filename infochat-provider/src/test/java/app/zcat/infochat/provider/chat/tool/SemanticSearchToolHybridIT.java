package app.zcat.infochat.provider.chat.tool;

import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.llm.EmbeddingResult;
import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral assertions for the M1-617 hybrid (semantic + lexical, RRF)
 * retrieval in {@link SemanticSearchTool} against the real pgvector
 * DevServices DB — the lexical arm's tsvector/GIN behaviour (V58), the
 * FULL OUTER JOIN fusion, and the isolation predicates inside the new arm
 * are SQL behaviours a fake DataSource cannot exercise. Named {@code *IT}
 * (failsafe phase) because it boots DevServices Postgres — integration-
 * shaped per design 08-verification §8.2, enforced by the M1-495
 * naming-guard ratchet. The rig mirrors {@link SemanticSearchToolIT}:
 * direct construction, stub {@link EmbeddingProvider} returning a fixed
 * query vector, PREFIX fixtures with known-angle embeddings, cleanup
 * before AND after each test (a leftover READY fixture poisons the
 * NewPostReconciler*IT exact row counts).
 */
@QuarkusTest
class SemanticSearchToolHybridIT {

    private static final String PREFIX = "hybrid-search-test/";
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

    @BeforeEach
    void setUp() throws Exception {
        tool = new SemanticSearchTool(
                dataSource, cancellationService, new StubEmbedder(), THRESHOLD, 8);
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

    // Acceptance (isolation on the NEW arm): a keyword-exact post can leak
    // ONLY through the lexical/fused path (its embedding is absent or
    // beyond the threshold, so the semantic arm can never return it) — if
    // the lexical arm carried the READY + subscription predicates outside
    // its LIMIT, or not at all, these two leak candidates would surface.
    // The subscribed READY control post proves the lexical match itself
    // works (an empty result here would just mean a broken arm, not
    // isolation).
    @Test
    void lexicalAndFusedPathNeverSurfaceUnsubscribedOrNonReadyPosts() throws Exception {
        UUID userId = seedUser("lex-scope");
        UUID subscribedSource = seedSource("lex-scope-sub-src", "Subscribed source");
        UUID otherSource = seedSource("lex-scope-other-src", "Unsubscribed source");
        seedSubscription("dm", userId, subscribedSource);
        seedPost("control", subscribedSource,
                "Tenda router backdoor advisory", "Details of the Tenda backdoor.", "READY");
        seedPost("unsub-leak", otherSource,
                "Tenda router backdoor advisory", "Details of the Tenda backdoor.", "READY");
        seedPost("raw-leak", subscribedSource,
                "Tenda router backdoor advisory", "Details of the Tenda backdoor.", "RAW");

        String json = tool.execute(userId, "dm", userId, Map.of("query", "Tenda backdoor"));

        assertTrue(json.contains(PREFIX + "control"),
                "the subscribed READY keyword-exact post must be returned via the "
                        + "lexical arm; got: " + json);
        assertFalse(json.contains(PREFIX + "unsub-leak"),
                "a post outside the caller's subscribed sources must NEVER surface "
                        + "through the lexical/fused path; got: " + json);
        assertFalse(json.contains(PREFIX + "raw-leak"),
                "a non-READY post must NEVER surface through the lexical/fused "
                        + "path; got: " + json);
    }

    // Acceptance (recall win — the "Tenda backdoor" class): the post's
    // embedding sits at 1 − cos(1.4) ≈ 0.830, beyond the 0.5 threshold, so
    // the semantic arm excludes it BY CONSTRUCTION (SemanticSearchToolIT
    // candidatesBeyondDistanceThresholdAreExcluded pins that exclusion) —
    // pre-M1-617 this query returned []. The lexical arm must now recover
    // it, with similarity emitted as JSON null (no semantic distance for a
    // row the semantic arm did not return).
    @Test
    void keywordExactPostBeyondSemanticThresholdIsRetrievedViaLexicalArm() throws Exception {
        UUID userId = seedUser("recall");
        UUID sourceId = seedSource("recall-src", "Recall source");
        seedSubscription("dm", userId, sourceId);
        UUID postId = seedPost("tenda", sourceId,
                "Tenda AC7 backdoor found in firmware", "Vendor ships a hard-coded telnet backdoor.",
                "READY");
        seedEmbedding(postId, vectorAtAngle(1.4));

        String json = tool.execute(userId, "dm", userId, Map.of("query", "Tenda backdoor"));

        assertTrue(json.contains(PREFIX + "tenda"),
                "a keyword-exact post the semantic-only path misses (distance "
                        + "0.83 > threshold 0.5) must be retrieved via the lexical arm; "
                        + "got: " + json);
        assertTrue(json.contains("\"similarity\":null"),
                "a row the semantic arm did not return carries similarity null, "
                        + "never a fabricated number; got: " + json);
    }

    // Acceptance (D19 determinism): same DB state -> byte-identical fused
    // set AND order across two consecutive calls. The seed mixes all three
    // membership shapes (semantic-only, lexical-only with NO embedding row
    // — the embedding-failure release path — and both-arms) so every
    // branch of the FULL OUTER JOIN is exercised under the assertion.
    @Test
    void fusedResultIsByteIdenticalAcrossConsecutiveCallsOnUnchangedDb() throws Exception {
        UUID userId = seedUser("determinism");
        UUID sourceId = seedSource("determinism-src", "Determinism source");
        seedSubscription("dm", userId, sourceId);
        UUID semanticOnly = seedPost("sem-only", sourceId,
                "Alpha beta gamma", "Nothing the query mentions.", "READY");
        seedEmbedding(semanticOnly, vectorAtAngle(0.2));
        seedPost("lex-only", sourceId,
                "Quantum router exploit disclosed", "A quantum router exploit writeup.", "READY");
        UUID bothArms = seedPost("both-arms", sourceId,
                "Quantum router exploit roundup", "More on the quantum router exploit.", "READY");
        seedEmbedding(bothArms, vectorAtAngle(0.3));

        String first = tool.execute(userId, "dm", userId, Map.of("query", "quantum router exploit"));
        String second = tool.execute(userId, "dm", userId, Map.of("query", "quantum router exploit"));

        assertTrue(first.contains(PREFIX + "sem-only")
                        && first.contains(PREFIX + "lex-only")
                        && first.contains(PREFIX + "both-arms"),
                "all three membership shapes must be present in the fused result; "
                        + "got: " + first);
        assertEquals(first, second,
                "the fused set and its order must be byte-identical across two "
                        + "consecutive calls on unchanged DB state (D19)");
    }

    // Fused-rank sanity: RRF's additive 1/(k+rank) means a post found by
    // BOTH arms (semantic rank 2 of 2, lexical rank 1 or 2) always sums
    // higher than either single-arm post (at best 1/(k+1)), so it must be
    // emitted first regardless of ts_rank's exact scoring.
    @Test
    void postMatchedByBothArmsOutranksSingleArmPosts() throws Exception {
        UUID userId = seedUser("rank");
        UUID sourceId = seedSource("rank-src", "Rank source");
        seedSubscription("dm", userId, sourceId);
        UUID semanticOnly = seedPost("rank-sem-only", sourceId,
                "Alpha beta gamma", "Nothing the query mentions.", "READY");
        seedEmbedding(semanticOnly, vectorAtAngle(0.2));
        seedPost("rank-lex-only", sourceId,
                "Quantum router exploit disclosed", "A quantum router exploit writeup.", "READY");
        UUID bothArms = seedPost("rank-both-arms", sourceId,
                "Quantum router exploit roundup", "More on the quantum router exploit.", "READY");
        seedEmbedding(bothArms, vectorAtAngle(0.3));

        String json = tool.execute(userId, "dm", userId, Map.of("query", "quantum router exploit"));

        int bothIdx = json.indexOf(PREFIX + "rank-both-arms");
        int semIdx = json.indexOf(PREFIX + "rank-sem-only");
        int lexIdx = json.indexOf(PREFIX + "rank-lex-only");
        assertTrue(bothIdx >= 0 && semIdx >= 0 && lexIdx >= 0,
                "all three posts must be in the fused result; got: " + json);
        assertTrue(bothIdx < semIdx && bothIdx < lexIdx,
                "the both-arms post must outrank every single-arm post under "
                        + "RRF; got: " + json);
    }

    // ---------- helpers (SemanticSearchToolIT rig, plus status/no-embedding variants) ----------

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

    /**
     * Post WITHOUT an embedding row — title/body drive the V58 generated
     * search_tsv, status is caller-chosen so RAW leak candidates can be
     * seeded. ready_at is only set for READY rows (RAW rows have none yet).
     */
    private UUID seedPost(String slug, UUID sourceId, String title, String body,
                          String status) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post (uid, source_id, title, body, url, published_at, "
                     + "fetched_at, status, ready_at, tags, upstream_identifier) "
                     + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '{}', ?) RETURNING id")) {
            ps.setString(1, PREFIX + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, title);
            ps.setString(4, body);
            ps.setString(5, "https://example.com/" + slug);
            ps.setTimestamp(6, Timestamp.from(FETCHED_AT));
            ps.setTimestamp(7, Timestamp.from(FETCHED_AT));
            ps.setString(8, status);
            ps.setTimestamp(9, "READY".equals(status) ? Timestamp.from(FETCHED_AT) : null);
            ps.setString(10, PREFIX + slug);
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
