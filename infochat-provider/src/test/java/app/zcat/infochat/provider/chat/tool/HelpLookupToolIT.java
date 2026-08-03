package app.zcat.infochat.provider.chat.tool;

import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.llm.EmbeddingResult;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.help.CommandIntentIndex;
import app.zcat.infochat.provider.messaging.HelpCommandHandler;
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
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral assertions for {@link HelpLookupTool} against the real
 * pgvector DevServices DB. The tool is constructed directly with a
 * stub {@link EmbeddingProvider} returning a fixed query vector, so no
 * embedding backend is contacted; {@code doc_embedding} fixtures are
 * seeded via JDBC with vectors of known cosine distance to that query
 * vector — the same shape {@code SemanticSearchToolIT} uses, applied
 * here to the command-intent corpus.
 *
 * <p>Covers the four M1-664 acceptance items that live at the tool
 * level: tier-filter-before-return (admin commands never surface to
 * non-admin callers), match-not-assert (the returned description is
 * the runtime catalogue value, regardless of the indexed row's stored
 * text), below-threshold no-match, and free-text phrasings resolving
 * to the right command name.
 *
 * <p>Named {@code *IT} (failsafe phase) because it boots DevServices
 * Postgres — integration-shaped per design 08-verification §8.2,
 * enforced by the M1-495 naming-guard ratchet. {@code SearchPostsToolTest}
 * and the other ~89 DB-backed {@code *Test}-named classes are
 * "accepted debt" frozen in {@code integration-test-naming-baseline.txt};
 * the baseline header recommends {@code *IT} for new DB-backed tests.
 */
@QuarkusTest
class HelpLookupToolIT {

    private static final String PREFIX = "help-lookup-test/";
    private static final int DIMENSION = 768;
    private static final float[] QUERY_VECTOR = vectorAtAngle(0);

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    CancellationService cancellationService;

    @Inject
    HelpCommandHandler helpHandler;

    @Inject
    BundleLoader bundleLoader;

    private StubEmbedder stubEmbedder;
    private HelpLookupTool tool;

    @BeforeEach
    void setUp() throws Exception {
        stubEmbedder = new StubEmbedder();
        tool = new HelpLookupTool(
                dataSource, cancellationService, stubEmbedder, helpHandler, bundleLoader);
        deleteFixtures();
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteFixtures();
    }

    /**
     * Acceptance item 4 — adminOnlyCommandNeverSurfacesToNonAdmin.
     *
     * <p>The tier filter rides INSIDE the WHERE clause
     * ({@code target_ref = ANY(?)}). A non-admin caller's visible set
     * excludes {@code grant-admin} (BOT_ADMIN tier). The leak candidate
     * is the nearest row of all — well inside the threshold AND closer
     * than the legitimate non-admin match — so only the tier filter can
     * exclude it. A missing tier filter would surface it and fail this
     * test (the M1-647 existence-oracle defense, widened to free-text).
     */
    @Test
    void adminOnlyCommandNeverSurfacesToNonAdmin() throws Exception {
        UUID user = seedUser("user", false);
        // grant-admin seeded CLOSER to the query than unfollow-source.
        // Without the tier filter inside WHERE, pgvector's nearest-first
        // would surface grant-admin and leak an admin command's name to a
        // non-admin caller.
        seedIntentDoc("grant-admin", vectorAtAngle(0.10));   // distance ≈ 0.005
        seedIntentDoc("unfollow-source", vectorAtAngle(0.40)); // distance ≈ 0.079

        String json = tool.execute(user, "dm", user,
                Map.of("query", "how do I mute this feed"));

        assertTrue(json.contains("\"command\":\"unfollow-source\""),
                "a non-admin caller must get the nearest VISIBLE match — never an "
                        + "admin-only command, even one seeded closer to the query. "
                        + "Got: " + json);
        assertFalse(json.contains("grant-admin"),
                "an admin-only command's name must never enter the model context "
                        + "for a non-admin caller — the tier filter rides inside the "
                        + "WHERE, not as a post-filter. Got: " + json);
    }

    /**
     * Acceptance item 5 — toolOutputComesFromRuntimeCatalogueNotFromIndexedText.
     *
     * <p>The match-not-assert invariant: embedded text is used only for
     * MATCHING. The returned {@code description} is composed at call
     * time from the runtime {@link HelpCommandHandler#CATALOGUE}'s
     * bundle key, never from any value carried by the indexed row. The
     * mutation here proves the description is independent of the row's
     * stored {@code content_hash} (which represents the source text the
     * embedding was generated from) — changing it cannot shift the
     * returned description by one byte. A stale intent document can
     * degrade a match (a future mutation could move the embedding out
     * of the threshold; tested separately) but can never produce wrong
     * syntax, because the tool never reads the stored text.
     */
    @Test
    void toolOutputComesFromRuntimeCatalogueNotFromIndexedText() throws Exception {
        UUID user = seedUser("user", false);
        seedIntentDoc("save", vectorAtAngle(0.20));  // distance ≈ 0.020

        String firstJson = tool.execute(user, "dm", user,
                Map.of("query", "how do I bookmark a post"));
        String expectedDescription = bundleLoader.get(BundleKeys.HELP_CMD_SAVE_SHORT);
        assertTrue(firstJson.contains("\"description\":\"" + expectedDescription + "\""),
                "the returned description must be the runtime catalogue short-help "
                        + "value for the matched command. Got: " + firstJson);

        // Mutate the row: simulate a stale intent document by changing
        // content_hash (the source-text fingerprint) and re-embedding at
        // a different angle that is still inside the threshold. The
        // description returned the second time MUST equal the first.
        mutateRowHashAndEmbedding("save", "STALE_HASH_FROM_OLD_TEXT", vectorAtAngle(0.25));

        String secondJson = tool.execute(user, "dm", user,
                Map.of("query", "how do I bookmark a post"));

        assertEquals(extractDescription(firstJson), extractDescription(secondJson),
                "the returned description must be byte-identical before and after "
                        + "mutating the indexed row's content_hash and embedding — the "
                        + "description comes from the runtime catalogue, not from any "
                        + "value carried by the row. Before: " + firstJson
                        + ", after: " + secondJson);
        assertNotEquals("STALE_HASH_FROM_OLD_TEXT", extractDescription(secondJson),
                "no byte sequence sourced from the indexed row's text fingerprint may "
                        + "appear as the description — that is the match-not-assert "
                        + "regression class");
    }

    /**
     * Acceptance item 6 — belowThresholdReturnsNoCommand.
     *
     * <p>Below the calibrated similarity threshold the tool returns no
     * command; the agent is directed (by {@code ChatAgent.TOOL_INSTRUCTIONS})
     * to say it does not know and point at {@code /help} rather than
     * answering from general knowledge. The threshold is
     * {@link HelpLookupTool#SIMILARITY_THRESHOLD} = 0.52 similarity
     * (M1-748-measured; docs/measurement/retrieval-separability.md
     * §5.4).
     *
     * <p><b>Boundary fixture, not orthogonal-vector sanity.</b> The
     * seeded row sits at similarity 0.50 (distance 0.50) — JUST BELOW
     * the 0.52 cutoff and WELL ABOVE the original units-confused 0.40
     * cutoff. A realistic unrelated English query against
     * nomic-embed-text routinely scores in this band, so this fixture
     * exercises the boundary the production behaviour actually lives
     * at. The prior implementation seeded an orthogonal vector at
     * similarity ≈0.0, which passed vacuously under any plausible
     * threshold and pinned nothing — the M1-664 round-1 redteam
     * flagged this as the reason the loose 0.40 cutoff survived
     * review. Asserting {@code {"command":null}} here both verifies
     * the cutoff and pins the regression: if the constant drifts to
     * 0.50 or below, this fixture would match and the assertion would
     * fail.
     */
    @Test
    void belowThresholdReturnsNoCommand() throws Exception {
        UUID user = seedUser("user", false);
        // similarity 0.50 (cos(acos(0.50))) — would have WRONGLY matched
        // under the prior 0.40 cutoff; correctly returns no command under
        // the 0.52 cutoff.
        seedIntentDoc("save", vectorAtAngle(Math.acos(0.50)));

        String json = tool.execute(user, "dm", user,
                Map.of("query", "what color is the sky"));

        assertEquals("{\"command\":null}", json,
                "below the similarity threshold the tool must return no command — "
                        + "the agent then says it does not know and points at /help. "
                        + "Got: " + json);
    }

    /**
     * Acceptance item 7 — free-text phrasings resolve to the right
     * command. Three phrasings that share NO prefix with the target
     * command name ({@code unfollow-source}) all resolve to that
     * command at the tool level. Delivered usage/examples are
     * deliberately NOT asserted — that end-to-end surface is M1-665.
     */
    @Test
    void freeTextPhrasingsResolveToTargetCommand() throws Exception {
        UUID user = seedUser("user", false);
        // The stub embedder returns QUERY_VECTOR for every input text,
        // so any of the three phrasings resolves to the row whose
        // embedding is closest to QUERY_VECTOR. The phrasings are
        // deliberately prefix-disjoint from "unfollow-source" — the
        // resolution goes through the embedded intent document, not
        // through any string-prefix match.
        seedIntentDoc("unfollow-source", vectorAtAngle(0.05)); // distance ≈ 0.001

        List<String> phrasings = List.of(
                "how do I stop seeing posts from this source",
                "mute this feed",
                "silence posts from that rss");
        for (String phrasing : phrasings) {
            String json = tool.execute(user, "dm", user, Map.of("query", phrasing));
            assertTrue(json.contains("\"command\":\"unfollow-source\""),
                    "phrasing '" + phrasing + "' must resolve to unfollow-source "
                            + "(shares no prefix with it — the match goes through the "
                            + "intent embedding, not a string comparison). Got: " + json);
            assertFalse(phrasing.startsWith("unfollow"),
                    "the test phrasings must be prefix-disjoint from the target "
                            + "command name; this one (" + phrasing + ") is not");
        }
    }

    /**
     * Regression guard for the chat path's embedding-backend degradation
     * posture: if the embed call throws, the tool degrades to "no match"
     * rather than aborting the chat turn. The model then says it does
     * not know and points at /help — the same friendly-degradation
     * posture {@code SemanticSearchTool} carries.
     */
    @Test
    void embeddingBackendFailureDegradesToNoMatch() throws Exception {
        UUID user = seedUser("user", false);
        seedIntentDoc("save", vectorAtAngle(0.20));
        stubEmbedder.throwOnEmbed = true;

        String json = tool.execute(user, "dm", user,
                Map.of("query", "how do I bookmark"));

        assertEquals("{\"command\":null}", json,
                "an embedding-backend failure must degrade the lookup to no-match, "
                        + "not abort the chat turn. Got: " + json);
    }

    // ---------- helpers ----------

    /**
     * Unit vector {@code [cos θ, sin θ, 0, ..., 0]}: its cosine distance
     * to {@code QUERY_VECTOR} (θ=0) is exactly {@code 1 − cos θ}, so a
     * fixture gets a known distance without hardcoding a 768-float
     * literal. Mirrors {@code SemanticSearchToolIT.vectorAtAngle}.
     */
    private static float[] vectorAtAngle(double theta) {
        float[] v = new float[DIMENSION];
        v[0] = (float) Math.cos(theta);
        v[1] = (float) Math.sin(theta);
        return v;
    }

    private UUID seedUser(String suffix, boolean isAdmin) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO users (adapter, contact_id, is_admin, registration_state) "
                     + "VALUES ('inmemory', ?, ?, 'vouched') RETURNING id")) {
            ps.setString(1, PREFIX + suffix + "/" + UUID.randomUUID());
            ps.setBoolean(2, isAdmin);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void seedIntentDoc(String command, float[] embedding) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO doc_embedding "
                     + "(doc_id, doc_kind, target_ref, content_hash, embedding, embedding_model) "
                     + "VALUES (?, ?, ?, ?, ?::vector, ?)")) {
            ps.setString(1, command);
            ps.setString(2, CommandIntentIndex.DOC_KIND);
            ps.setString(3, command);
            ps.setString(4, "hash-" + command);
            ps.setString(5, toVectorLiteral(embedding));
            ps.setString(6, "nomic-embed-text");
            ps.executeUpdate();
        }
    }

    private void mutateRowHashAndEmbedding(String command, String newHash, float[] newEmbedding)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE doc_embedding SET content_hash = ?, embedding = ?::vector "
                     + "WHERE doc_kind = ? AND doc_id = ?")) {
            ps.setString(1, newHash);
            ps.setString(2, toVectorLiteral(newEmbedding));
            ps.setString(3, CommandIntentIndex.DOC_KIND);
            ps.setString(4, command);
            ps.executeUpdate();
        }
    }

    private void deleteFixtures() throws Exception {
        // Wipe ALL command_intent rows: the @QuarkusTest boot fires
        // CommandIntentIndexBuilder's @Observes StartupEvent, which
        // pre-populates doc_embedding with the full catalogue corpus
        // under SHA-256 content hashes. A filter like 'hash-%' would
        // leave those rows behind and the test's seedIntentDoc INSERTs
        // would collide on the primary key.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM doc_embedding WHERE doc_kind = ?")) {
            ps.setString(1, CommandIntentIndex.DOC_KIND);
            ps.executeUpdate();
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM users WHERE contact_id LIKE ?")) {
            ps.setString(1, PREFIX + "%");
            ps.executeUpdate();
        }
    }

    private static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    /** Extract the {@code description} value from a tool-result JSON. */
    private static String extractDescription(String json) {
        int idx = json.indexOf("\"description\":\"");
        if (idx < 0) {
            return "";
        }
        int start = idx + "\"description\":\"".length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : "";
    }

    static class StubEmbedder implements EmbeddingProvider {
        boolean throwOnEmbed;

        @Override
        public List<EmbeddingResult> embed(List<String> texts) {
            if (throwOnEmbed) {
                throw new RuntimeException("embedding backend down");
            }
            return texts.stream().map(t -> new EmbeddingResult(QUERY_VECTOR)).toList();
        }
    }
}
