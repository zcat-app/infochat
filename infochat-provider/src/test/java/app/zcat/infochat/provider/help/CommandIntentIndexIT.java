package app.zcat.infochat.provider.help;

import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.llm.EmbeddingResult;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.testsupport.SeedDataSource;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral assertions for {@link CommandIntentIndexBuilder} against
 * the real pgvector DevServices DB. The builder is constructed directly
 * with a counting stub {@link EmbeddingProvider}, so embed-call counts
 * are precisely observable; {@code doc_embedding} state is seeded and
 * verified via direct JDBC.
 *
 * <p>Covers M1-664 acceptance items 2 (warm-restart content-hash skip)
 * and 3 (changed intent text or embedding model forces a re-embed).
 * Together these pin the invariant that a stale vector can never
 * outlive its source text.
 *
 * <p>Named {@code *IT} (failsafe phase) because it boots DevServices
 * Postgres — integration-shaped per design 08-verification §8.2,
 * enforced by the M1-495 naming-guard ratchet. {@code SearchPostsToolTest}
 * and the other ~89 DB-backed {@code *Test}-named classes are
 * "accepted debt" frozen in {@code integration-test-naming-baseline.txt}
 * (their bulk-rename was explicitly out of M1-495's scope); the
 * baseline header recommends {@code *IT} for new DB-backed tests, so
 * this class follows that recommendation rather than extending the
 * baseline.
 */
@QuarkusTest
class CommandIntentIndexIT {

    private static final String EMBEDDING_MODEL = "nomic-embed-text";
    private static final int DIMENSION = 768;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    DocEmbeddingDao dao;

    @Inject
    BundleLoader bundleLoader;

    private CountingEmbedder stubEmbedder;
    private CommandIntentIndexBuilder builder;

    @BeforeEach
    void setUp() throws Exception {
        deleteAllIntentRows();
        stubEmbedder = new CountingEmbedder();
        builder = new CommandIntentIndexBuilder(dao, stubEmbedder, bundleLoader, EMBEDDING_MODEL);
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteAllIntentRows();
    }

    /**
     * Acceptance item 2 — restartWithUnchangedCorpusPerformsNoEmbeddingCall.
     *
     * <p>The startup preflight diffs the in-memory source set against the
     * stored rows by content_hash + embedding_model. A row whose hash
     * AND model both match is skipped, so a second invocation with
     * identical inputs performs zero embedding calls — a warm restart
     * costs one SELECT (the preflight read) and nothing else.
     */
    @Test
    void restartWithUnchangedCorpusPerformsNoEmbeddingCall() {
        // First invocation: populates the table from an empty starting
        // state. Every catalogue command is a delta against the empty
        // stored set, so this embeds the full corpus once.
        builder.onStart(new StartupEvent());
        int callsAfterFirst = stubEmbedder.embedCalls;
        assertTrue(callsAfterFirst >= 1,
                "a cold start must embed the corpus at least once; got "
                        + callsAfterFirst + " embed calls");

        // Second invocation: identical inputs. Every row's content_hash
        // and embedding_model now match what the builder would produce,
        // so the content-hash skip fires for every row and no embed
        // call is made.
        builder.onStart(new StartupEvent());
        assertEquals(callsAfterFirst, stubEmbedder.embedCalls,
                "a restart with an unchanged corpus must perform ZERO embedding "
                        + "calls — the content-hash + model-identity skip is the "
                        + "warm-restart cost boundary");
    }

    /**
     * Acceptance item 3 — changedIntentTextIsReEmbedded.
     *
     * <p>A row whose source text has changed since the last embed (here
     * simulated by mutating its content_hash to a stale value) is
     * re-embedded on the next startup. The complement of item 2: a
     * stale vector can never outlive its source text.
     */
    @Test
    void changedIntentTextIsReEmbedded() throws Exception {
        builder.onStart(new StartupEvent());
        int callsAfterFirst = stubEmbedder.embedCalls;

        // Simulate a stale row: the source text changed since the last
        // embed, so the stored content_hash no longer matches what the
        // builder would compute. The builder cannot tell whether the
        // stored hash or the source text is the newer one — it just sees
        // a mismatch and re-embeds.
        assertTrue(rowExists("save"),
                "save must be a catalogue command present in the index");
        mutateRowHash("save", "STALE_HASH_FROM_OLD_TEXT");

        builder.onStart(new StartupEvent());
        int callsAfterSecond = stubEmbedder.embedCalls;
        assertTrue(callsAfterSecond > callsAfterFirst,
                "a row whose content_hash no longer matches its source text must "
                        + "trigger a re-embed on the next startup");
        assertFalse(rowHasHash("save", "STALE_HASH_FROM_OLD_TEXT"),
                "the stale row must have been replaced with the freshly-embedded "
                        + "current hash");
    }

    /**
     * Companion to item 3: a change to the active embedding model also
     * forces a re-embed of every row, even when the source text is
     * unchanged. A model switch invalidates every stored vector because
     * two models' vector spaces are incomparable (pgvector cosine
     * distance across models is meaningless — the same hazard
     * {@code EmbeddingMetadataStartupGuard} exists to refuse at startup
     * for the whole deployment).
     */
    @Test
    void embeddingModelChangeForcesFullReEmbed() throws Exception {
        builder.onStart(new StartupEvent());
        int callsAfterFirst = stubEmbedder.embedCalls;

        // Re-construct the builder with a different model name — every
        // stored row's embedding_model field now mismatches, so every
        // row is a delta regardless of content_hash.
        CommandIntentIndexBuilder rotatedBuilder =
                new CommandIntentIndexBuilder(dao, stubEmbedder, bundleLoader, "nomic-embed-text-v2");
        rotatedBuilder.onStart(new StartupEvent());

        assertTrue(stubEmbedder.embedCalls > callsAfterFirst,
                "an embedding-model rotation must re-embed every row — a vector "
                        + "from one model is incomparable with a vector from another, "
                        + "so the model-identity mismatch is a forced re-embed");
    }

    /**
     * A catalogue command removed since the last boot is pruned from the
     * table — the index never serves a target_ref the runtime catalogue
     * no longer recognizes. (Not a named acceptance item; the build
     * order's "prune disappeared rows" step.)
     */
    @Test
    void removedCatalogueCommandIsPruned() throws Exception {
        builder.onStart(new StartupEvent());
        // Manually insert a phantom row whose doc_id is not in CATALOGUE.
        seedPhantomRow("__never_a_real_command__");
        assertTrue(rowExists("__never_a_real_command__"),
                "phantom row seeded");

        builder.onStart(new StartupEvent());

        assertFalse(rowExists("__never_a_real_command__"),
                "a row whose doc_id is no longer in the runtime catalogue must be "
                        + "pruned on the next startup — the index must never serve a "
                        + "phantom command");
    }

    /**
     * Regression for the M1-664 round-1 redteam DOS finding (claude-only,
     * high severity, surfaced via the multi-auditor cross-exam). An
     * embedding-backend failure at boot must NOT abort Provider startup;
     * the builder catches the failure, logs ERROR, and leaves the
     * corpus empty (cold start) or at its prior state (warm restart).
     * The chat-time {@code HelpLookupTool} returns
     * {@code {"command":null}} on an empty corpus — quality degraded,
     * not safety impacted ({@code docs/spec/security.md} §Failure
     * handling: "A complete LLM outage degrades quality, not safety").
     *
     * <p>The prior implementation rethrew as {@code IllegalStateException}
     * from {@code onStart}, which aborted Quarkus startup and converted
     * a chat-tier convenience outage into a total Provider outage
     * (every adapter, ban intake, admin auth, invite gate, digests —
     * every security control) until the embedding backend recovered.
     * The fix bounds the blast radius to helpLookup itself.
     */
    @Test
    void embeddingBackendFailureAtStartupDoesNotAbort() throws Exception {
        stubEmbedder.throwOnEmbed = true;
        // Cold-start path: doc_embedding is empty (@BeforeEach wiped it),
        // so the builder computes the full catalogue as the delta and
        // tries to embed it. The throwing embedder simulates the
        // embedding backend being down at boot.
        builder.onStart(new StartupEvent());

        // If we got here, onStart did NOT throw — the catch + log +
        // degrade posture held. Assert the corpus is empty too: the
        // embed call failed before any row was written, so at chat time
        // HelpLookupTool would return {"command":null} for every query.
        assertFalse(rowExists("save"),
                "an embedding-backend failure must leave the corpus empty (cold "
                        + "start) or at its prior state (warm restart) — NOT abort "
                        + "Provider startup. helpLookup degrades to no-match at chat "
                        + "time per docs/spec/security.md §Failure handling.");
    }

    // ---------- helpers ----------

    private void mutateRowHash(String command, String newHash) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE doc_embedding SET content_hash = ? "
                     + "WHERE doc_kind = ? AND doc_id = ?")) {
            ps.setString(1, newHash);
            ps.setString(2, CommandIntentIndex.DOC_KIND);
            ps.setString(3, command);
            ps.executeUpdate();
        }
    }

    private void seedPhantomRow(String command) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO doc_embedding "
                     + "(doc_id, doc_kind, target_ref, content_hash, embedding, embedding_model) "
                     + "VALUES (?, ?, ?, ?, ?::vector, ?)")) {
            float[] v = new float[DIMENSION];
            v[0] = 1.0f;
            ps.setString(1, command);
            ps.setString(2, CommandIntentIndex.DOC_KIND);
            ps.setString(3, command);
            ps.setString(4, "phantom-hash");
            ps.setString(5, toVectorLiteral(v));
            ps.setString(6, EMBEDDING_MODEL);
            ps.executeUpdate();
        }
    }

    private boolean rowExists(String docId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT 1 FROM doc_embedding WHERE doc_kind = ? AND doc_id = ?")) {
            ps.setString(1, CommandIntentIndex.DOC_KIND);
            ps.setString(2, docId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean rowHasHash(String docId, String hash) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT 1 FROM doc_embedding WHERE doc_kind = ? AND doc_id = ? AND content_hash = ?")) {
            ps.setString(1, CommandIntentIndex.DOC_KIND);
            ps.setString(2, docId);
            ps.setString(3, hash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void deleteAllIntentRows() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM doc_embedding WHERE doc_kind = ?")) {
            ps.setString(1, CommandIntentIndex.DOC_KIND);
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

    static class CountingEmbedder implements EmbeddingProvider {
        int embedCalls;
        boolean throwOnEmbed;

        @Override
        public List<EmbeddingResult> embed(List<String> texts) {
            embedCalls++;
            if (throwOnEmbed) {
                // Mirrors the production failure mode the M1-664 round-1
                // redteam DOS finding flagged: the local embedding backend
                // (Ollama nomic-embed-text) is down, still loading, or
                // timed out at Provider boot.
                throw new RuntimeException("embedding backend down");
            }
            // Return one unit vector per input. pgvector's cosine
            // operator is undefined for a zero vector, so unit is the
            // safe canned shape (mirrors StubEmbeddingProvider).
            float[] v = new float[DIMENSION];
            v[0] = 1.0f;
            return texts.stream().map(t -> new EmbeddingResult(v)).toList();
        }
    }
}
