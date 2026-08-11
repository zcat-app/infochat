package app.zcat.infochat.provider.help;

import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.llm.EmbeddingResult;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.health.HelpCorpusBuildState;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

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
 * outlive its source text. M1-660 adds the read-path hardening probes:
 * command recall under foreign-doc_kind crowding, and the
 * SET-LOCAL-without-transaction no-op trap.
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
    private HelpCorpusBuildState buildState;
    private CommandIntentIndexBuilder builder;

    @BeforeEach
    void setUp() throws Exception {
        deleteAllIntentRows();
        deleteForeignKindRows();
        stubEmbedder = new CountingEmbedder();
        buildState = new HelpCorpusBuildState();
        builder = new CommandIntentIndexBuilder(dao, stubEmbedder, bundleLoader, buildState, EMBEDDING_MODEL);
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteAllIntentRows();
        deleteForeignKindRows();
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
                new CommandIntentIndexBuilder(dao, stubEmbedder, bundleLoader, buildState, "nomic-embed-text-v2");
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

    /** Failure mode: a throwing embedder fed through onStart degrades the
     * holder for that corpus and never propagates — reaching these assertions IS the
     * no-propagation proof (an escaped observer failure refuses the service start). */
    @Test
    void failedEmbeddingBackendReportsDegradedAndContinuesStartup() {
        stubEmbedder.throwOnEmbed = true;

        builder.onStart(new StartupEvent());

        assertEquals(Boolean.FALSE, buildState.snapshot().get(CommandIntentIndex.DOC_KIND),
                "a failed build must degrade the holder for that corpus — the"
                        + " readiness entry carries what the ERROR log line carries,"
                        + " as a boolean, never the exception text");
    }

    /** Warm-restart semantics: the entry states corpus availability, never
     * backend liveness — a hash-matching corpus performs zero embedding calls, so a
     * dead backend and a built entry legitimately coexist. */
    @Test
    void unchangedCorpusWarmRestartReportsBuiltDespiteDeadBackend() {
        builder.onStart(new StartupEvent());
        int callsAfterFirst = stubEmbedder.embedCalls;

        stubEmbedder.throwOnEmbed = true;
        builder.onStart(new StartupEvent());

        assertEquals(callsAfterFirst, stubEmbedder.embedCalls,
                "a warm restart performs ZERO embedding calls — the content-hash"
                        + " skip short-circuits before the backend is touched");
        assertEquals(Boolean.TRUE, buildState.snapshot().get(CommandIntentIndex.DOC_KIND),
                "the content-hash-skipped build reports built even though the"
                        + " injected embedder now throws when called — a mutation"
                        + " reporting backend liveness instead of build outcome"
                        + " fails here");
    }

    // ---------- M1-660: filter-inside-ANN read-path hardening ----------

    /** A second doc_kind sharing idx_doc_embedding_hnsw (the M1-649 shape). */
    private static final String FOREIGN_KIND = "zz_m1660_foreign";
    /** Synthetic command row the crowded probes must still recall. */
    private static final String CROWDED_COMMAND = "zz-m1660-target-command";
    /**
     * Pinned to postgres' hnsw.ef_search default so the crowd size below
     * always exceeds the non-iterative candidate window, regardless of
     * server-config drift.
     */
    private static final int EF_SEARCH = 40;
    private static final int FOREIGN_CROWD_SIZE = 200;
    /** The crowd spans the whole gap below the target so the target keeps
     * close graph neighbors under ANY HNSW level draw — an outlier beyond
     * the cone loses its bridge edges to pruning and becomes unreachable. */
    private static final double CROWD_MIN_ANGLE = 0.001;
    private static final double CROWD_MAX_ANGLE = 0.29;
    /** Inside the crowd's span: more than EF_SEARCH rows stay closer than
     * the target (the trap), yet the target sits in the connected manifold. */
    private static final double TARGET_ANGLE = 0.15;
    private static final double SIMILARITY_THRESHOLD = 0.60;

    /**
     * M1-660 acceptance item 4 — the retrieval proof, not a SQL-string
     * assert. {@value #FOREIGN_CROWD_SIZE} rows of a second doc_kind span
     * the gap below the target — more than {@value #EF_SEARCH} of them
     * strictly CLOSER to the query — so a non-iterative HNSW probe's whole
     * candidate window fills with rows the {@code doc_kind} filter then
     * discards — under-recall to empty, exactly what production hits the
     * moment M1-649's topic corpus lands. With
     * {@code hnsw.iterative_scan = strict_order} armed inside the probe's
     * transaction the scan keeps walking until a filter-surviving row
     * emerges, so the command is recalled. This test FAILS on main (no
     * arming) and PASSES with the fix.
     */
    @Test
    void commandRecallSurvivesForeignKindInterleaving() throws Exception {
        seedDocRow(CROWDED_COMMAND, CommandIntentIndex.DOC_KIND, vectorAtAngle(TARGET_ANGLE));
        seedForeignCrowd();
        reindexHnswGraph();

        try (Connection conn = dataSource.getConnection()) {
            try {
                conn.setAutoCommit(false);
                forcePlannerToHnswProbe(conn, true);
                Optional<String> match = CommandIntentIndex.lookupCommand(
                        conn,
                        toVectorLiteral(vectorAtAngle(0)),
                        List.of(CROWDED_COMMAND),
                        SIMILARITY_THRESHOLD);
                assertEquals(Optional.of(CROWDED_COMMAND), match,
                        "a command probe must recall the nearest VISIBLE command-intent "
                                + "row even when a " + FOREIGN_CROWD_SIZE + "-row crowd of "
                                + "another doc_kind outnumbers the ef_search window with "
                                + "closer rows — without "
                                + "iterative_scan the probe stops at " + EF_SEARCH
                                + " candidates and silently under-recalls");
            } finally {
                conn.rollback();
                conn.setAutoCommit(true);
            }
        }
    }

    /**
     * M1-660 acceptance item 5 — the no-op-trap pin. Probe A hand-rolls
     * the naive pre-M1-660 read shape on an AUTOCOMMIT connection:
     * {@code SET LOCAL hnsw.iterative_scan} is issued exactly as the
     * fixed path issues it, but with no transaction open the GUC expires
     * with its own implicit single-statement transaction before the
     * query runs, so the probe under-recalls to empty. Probe B then
     * calls the REAL read path on the SAME still-autocommit connection
     * (ChatAgent.lookupIntentForDelivery's exact borrow shape) and
     * recalls the command — the only variable separating under-recall
     * from recall is {@code lookupCommand}'s own transaction + arming.
     * A refactor that drops {@code setAutoCommit(false)} or the
     * {@code SET LOCAL} from {@code lookupCommand} turns probe B into
     * probe A and reds this test rather than silently regressing recall.
     * Probe A doubles as the fixture's discrimination guard: if the
     * crowd ever stops defeating a non-iterative window (pgvector
     * default drift, fixture rot), its EMPTY assert fires and flags the
     * companion test above as vacuous instead of green-washing it.
     */
    @Test
    void armingOutsideTransactionIsSilentNoOpAndRealPathRecovers() throws Exception {
        seedDocRow(CROWDED_COMMAND, CommandIntentIndex.DOC_KIND, vectorAtAngle(TARGET_ANGLE));
        seedForeignCrowd();
        reindexHnswGraph();

        try (Connection conn = dataSource.getConnection()) {
            try {
                // Session-scoped (plain SET) so the plan pinning survives
                // autocommit's per-statement transaction boundaries for
                // probe A, and stays in effect inside probe B's transaction.
                forcePlannerToHnswProbe(conn, false);

                // Probe A — the trap: arming without a transaction.
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SET LOCAL hnsw.iterative_scan = strict_order");
                }
                assertTrue(naiveProbe(conn).isEmpty(),
                        "SET LOCAL on an autocommit connection must be a silent no-op "
                                + "— if this probe recalls the command, either the arming "
                                + "unexpectedly took effect outside a transaction or the "
                                + "crowd fixture no longer defeats a non-iterative "
                                + "ef_search window, and the companion recall test is "
                                + "vacuous");

                // Probe B — the real read path on the same bare autocommit
                // connection.
                Optional<String> match = CommandIntentIndex.lookupCommand(
                        conn,
                        toVectorLiteral(vectorAtAngle(0)),
                        List.of(CROWDED_COMMAND),
                        SIMILARITY_THRESHOLD);
                assertEquals(Optional.of(CROWDED_COMMAND), match,
                        "lookupCommand must open the transaction its own SET LOCAL "
                                + "arming needs — on a bare autocommit borrow (the "
                                + "ChatAgent.lookupIntentForDelivery shape) the arming "
                                + "must still be effective");
            } finally {
                if (!conn.getAutoCommit()) {
                    conn.rollback();
                    conn.setAutoCommit(true);
                }
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("RESET enable_seqscan");
                    stmt.execute("RESET enable_sort");
                    stmt.execute("RESET hnsw.ef_search");
                }
            }
        }
    }

    /**
     * Rebuild the HNSW graph from the live heap before probing. Sibling
     * tests in this class churn {@code doc_embedding} (insert + delete
     * of the whole CATALOGUE as IDENTICAL stub vectors per builder run);
     * HNSW deletes leave
     * dead graph nodes until VACUUM, and a fresh row inserted into that
     * degenerate duplicate-vector region can become unreachable from
     * the graph entry point — empirically, even an exhaustive iterative
     * scan (ef_search=1000, max_scan_tuples=20000) then exhausts after
     * the reachable component without ever surfacing the row (reproduced
     * standalone on pgvector/pgvector:pg16, 2026-07-20). REINDEX pins
     * the precondition these tests rely on — every LIVE row reachable —
     * so the non-iterative ef_search window is the ONLY recall variable
     * under test. Production sees no comparable state: the corpus is
     * rebuilt at most once per boot with diverse real embeddings, not
     * churned per-test with duplicates.
     */
    private void reindexHnswGraph() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            // HNSW level draws come from the backend RNG (pgvector seeds a
            // fixed one in debug builds only): pin them so the graph cannot
            // flip with the pooled backend's RNG history (catalog growth).
            stmt.execute("SELECT setseed(0.5)");
            stmt.execute("REINDEX INDEX idx_doc_embedding_hnsw");
        }
    }

    /**
     * Pin the probe to the HNSW index path. On a ~{@value #FOREIGN_CROWD_SIZE}-row
     * table the planner would otherwise seq-scan (exact — every row sees
     * the filters) or walk the (doc_kind, target_ref) btree plus an
     * explicit sort (also exact), and the ef_search window these tests
     * probe would never come into play. Disabling seq scans and explicit
     * sorts leaves the distance-ordered HNSW scan as the only plan — the
     * same plan a production-sized corpus reaches on cost alone.
     */
    private static void forcePlannerToHnswProbe(Connection conn, boolean transactionScoped)
            throws SQLException {
        String scope = transactionScoped ? "SET LOCAL " : "SET ";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(scope + "enable_seqscan = off");
            stmt.execute(scope + "enable_sort = off");
            stmt.execute(scope + "hnsw.ef_search = " + EF_SEARCH);
        }
    }

    /**
     * The pre-M1-660 probe shape: {@code lookupCommand}'s exact SQL with
     * no transaction and no arming beyond what the caller already issued.
     */
    private Optional<String> naiveProbe(Connection conn) throws SQLException {
        String sql = "SELECT target_ref FROM doc_embedding "
                + "WHERE doc_kind = ? AND target_ref = ANY(?) "
                + "AND (embedding <=> ?::vector) < ? "
                + "ORDER BY (embedding <=> ?::vector) ASC LIMIT 1";
        String vectorLiteral = toVectorLiteral(vectorAtAngle(0));
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, CommandIntentIndex.DOC_KIND);
            ps.setArray(2, conn.createArrayOf("text", new String[] {CROWDED_COMMAND}));
            ps.setString(3, vectorLiteral);
            ps.setDouble(4, 1.0 - SIMILARITY_THRESHOLD);
            ps.setString(5, vectorLiteral);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString("target_ref")) : Optional.empty();
            }
        }
    }

    /**
     * {@value #FOREIGN_CROWD_SIZE} rows of a second doc_kind, every one
     * strictly closer to the query vector (angle 0) than the command row
     * at angle 0.30 — so a non-iterative probe's candidate window holds
     * only rows the doc_kind filter discards.
     */
    private void seedForeignCrowd() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO doc_embedding "
                     + "(doc_id, doc_kind, target_ref, content_hash, embedding, embedding_model) "
                     + "VALUES (?, ?, ?, ?, ?::vector, ?)")) {
            for (int i = 0; i < FOREIGN_CROWD_SIZE; i++) {
                String docId = "zz-m1660-foreign-" + i;
                ps.setString(1, docId);
                ps.setString(2, FOREIGN_KIND);
                ps.setString(3, docId);
                ps.setString(4, "crowd-hash");
                ps.setString(5, toVectorLiteral(vectorAtAngle(
                        CROWD_MIN_ANGLE + i * (CROWD_MAX_ANGLE - CROWD_MIN_ANGLE) / (FOREIGN_CROWD_SIZE - 1))));
                ps.setString(6, EMBEDDING_MODEL);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void seedDocRow(String docId, String docKind, float[] embedding) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO doc_embedding "
                     + "(doc_id, doc_kind, target_ref, content_hash, embedding, embedding_model) "
                     + "VALUES (?, ?, ?, ?, ?::vector, ?)")) {
            ps.setString(1, docId);
            ps.setString(2, docKind);
            ps.setString(3, docId);
            ps.setString(4, "m1660-hash");
            ps.setString(5, toVectorLiteral(embedding));
            ps.setString(6, EMBEDDING_MODEL);
            ps.executeUpdate();
        }
    }

    private void deleteForeignKindRows() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM doc_embedding WHERE doc_kind = ?")) {
            ps.setString(1, FOREIGN_KIND);
            ps.executeUpdate();
        }
    }

    /**
     * Unit vector in the (dim0, dim1) plane at the given angle from the
     * dim0 axis — cosine similarity to {@code vectorAtAngle(0)} is
     * exactly {@code cos(radians)}. Same helper shape as
     * {@code HelpLookupToolIT}.
     */
    private static float[] vectorAtAngle(double radians) {
        float[] v = new float[DIMENSION];
        v[0] = (float) Math.cos(radians);
        v[1] = (float) Math.sin(radians);
        return v;
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
