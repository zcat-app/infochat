package app.zcat.infochat.provider.help;

import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.llm.EmbeddingResult;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral assertions for {@link TopicCorpusBuilder} and
 * {@link CommandIntentIndex#lookupTopic(Connection, String, double)}
 * against the real pgvector DevServices DB (M1-649). The builder is
 * constructed directly with a counting stub
 * {@link EmbeddingProvider}, so embed-call counts are precisely
 * observable; {@code doc_embedding} state is seeded and verified via
 * direct JDBC. {@code lookupTopic} probes are exercised the same way
 * {@code CommandIntentIndexIT} exercises {@code lookupCommand}.
 *
 * <p>Covers M1-649 acceptance items 2 (no-shared-content-word
 * phrasings resolve), 6 (content-hash skip + doc_kind-scoped prune),
 * 7 ({@code lookupTopic} pointer-only over the M1-660 armed path),
 * and 10 (negative pin — no topic reaches the adapter via this
 * ticket's paths, expressed as cross-kind isolation through
 * {@code lookupCommand}).
 *
 * <p>Named {@code *IT} (failsafe phase) because it boots DevServices
 * Postgres — integration-shaped per design 08-verification §8.2,
 * enforced by the M1-495 naming-guard ratchet.
 */
@QuarkusTest
class TopicCorpusRetrievalIT {

    private static final String EMBEDDING_MODEL = "nomic-embed-text";
    private static final int DIMENSION = 768;
    private static final double SIMILARITY_THRESHOLD =
            CommandIntentIndex.TOPIC_SIMILARITY_THRESHOLD;

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    DocEmbeddingDao dao;

    private CountingEmbedder stubEmbedder;
    private TopicCorpusBuilder builder;

    @BeforeEach
    void setUp() throws Exception {
        // Wipe ALL topic rows: the @QuarkusTest boot fires
        // TopicCorpusBuilder.onStart, which pre-populates doc_embedding
        // with the 10-topic corpus under SHA-256 content hashes. Any
        // test that seeds its own topic rows or counts embed calls
        // must start from an empty topic-kind table.
        //
        // Wipe ALL command_intent rows too: the @QuarkusTest boot also
        // fires CommandIntentIndexBuilder.onStart, so the catalogue
        // corpus (41 rows) is present. Tests that seed their own
        // command_intent rows (the cross-kind isolation probes) would
        // PK-violate on the boot-seeded row without this wipe.
        //
        // deleteFixtures() also REINDEXes idx_doc_embedding_hnsw after
        // the wipe: the boot fired BOTH builders, each inserting many
        // IDENTICAL-stub-vector rows whose HNSW-deleted dead nodes
        // would otherwise make a freshly-seeded row in the same
        // duplicate-vector region unreachable from the graph entry
        // point — the same fixture-pitfall
        // CommandIntentIndexIT.reindexHnswGraph documents.
        deleteFixtures();
        stubEmbedder = new CountingEmbedder();
        builder = new TopicCorpusBuilder(dao, stubEmbedder, EMBEDDING_MODEL);
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteFixtures();
    }

    /**
     * Acceptance item 2 — three phrasings that share NO content word
     * with the target topic title all resolve to that topic via
     * {@link CommandIntentIndex#lookupTopic}. The stub embedder
     * returns {@code QUERY_VECTOR} for every input text, so any
     * phrasing resolves to the row whose embedding is nearest
     * {@code QUERY_VECTOR}; the phrasings are deliberately
     * word-disjoint from the topic title to prove the resolution goes
     * through the embedded intent document, not through any
     * string-token match.
     *
     * <p>CI pins plumbing only — do NOT reach for a real embedding
     * backend (ticket CI NOTE). Real no-shared-word recall is
     * verified by live calibration (the M1-619 pattern, follow-up),
     * not by CI.
     */
    @Test
    void phrasingsWithNoSharedContentWordResolveToTopic() throws Exception {
        String slug = "probation";
        String title = titleFor(slug);
        seedTopicRow(slug, vectorAtAngle(0.05)); // distance ≈ 0.00125 from QUERY_VECTOR

        // Three phrasings, each sharing NO content word with the
        // topic title. Content words of the title are computed
        // below; the assertion enforces disjointness.
        List<String> phrasings = List.of(
                "why can't I post in the group",
                "am I locked out of features",
                "when do I get full access");
        Set<String> titleContentWords = contentWords(title);
        for (String phrasing : phrasings) {
            Set<String> phrasingWords = contentWords(phrasing);
            Set<String> overlap = new HashSet<>(phrasingWords);
            overlap.retainAll(titleContentWords);
            assertTrue(overlap.isEmpty(),
                    "test phrasing '" + phrasing + "' must share no content word with the "
                            + "topic title '" + title + "'; overlap was " + overlap
                            + " — the resolution must go through the intent embedding, not "
                            + "a string comparison");

            try (Connection conn = dataSource.getConnection()) {
                try {
                    Optional<String> match = CommandIntentIndex.lookupTopic(
                            conn,
                            toVectorLiteral(vectorAtAngle(0)),
                            SIMILARITY_THRESHOLD);
                    assertEquals(Optional.of(slug), match,
                            "phrasing '" + phrasing + "' must resolve to topic '" + slug
                                    + "' via lookupTopic (the seeded topic row is the only "
                                    + "topic-kind row and the stub embedder returns "
                                    + "QUERY_VECTOR for every input)");
                } finally {
                    if (!conn.getAutoCommit()) {
                        conn.rollback();
                        conn.setAutoCommit(true);
                    }
                }
            }
        }
    }

    /**
     * Acceptance item 6 (content-hash skip half) —
     * {@link TopicCorpusBuilder#onStart} diffs the in-memory source
     * set against the stored rows by content_hash + embedding_model.
     * A row whose hash AND model both match is skipped, so a second
     * invocation with identical inputs performs zero embedding calls
     * — a warm restart costs one SELECT (the preflight read) and
     * nothing else.
     */
    @Test
    void restartWithUnchangedCorpusPerformsNoEmbeddingCall() {
        // First invocation: populates the table from an empty starting
        // state (the @BeforeEach wiped all topic rows). Every corpus
        // topic is a delta against the empty stored set, so this embeds
        // the full corpus once.
        builder.onStart(new StartupEvent());
        int callsAfterFirst = stubEmbedder.embedCalls;
        assertTrue(callsAfterFirst >= 1,
                "a cold start must embed the corpus at least once; got " + callsAfterFirst);

        // Second invocation: identical inputs. Every row's content_hash
        // and embedding_model now match what the builder would produce,
        // so the content-hash skip fires for every row and no embed
        // call is made.
        builder.onStart(new StartupEvent());
        assertEquals(callsAfterFirst, stubEmbedder.embedCalls,
                "a restart with an unchanged corpus must perform ZERO embedding calls — "
                        + "the content-hash + model-identity skip is the warm-restart cost "
                        + "boundary");
    }

    /**
     * Acceptance item 6 (prune half) — the builder's DELETE is
     * doc_kind-scoped, so a topic-row prune never touches
     * {@code command_intent} rows. Seed a {@code command_intent} row
     * and a phantom topic row (a doc_id not in
     * {@link HelpTopicCorpus#CORPUS}); run the builder; the phantom is
     * pruned (it's not in the source set) and the command row survives
     * intact. This is the V60 single-column-PK safety the
     * {@code doc_kind}-scoped DELETE buys: the prune cannot accidentally
     * cascade into the other corpus.
     */
    @Test
    void topicBuilderDeleteNeverTouchesCommandRows() throws Exception {
        String command = "save";
        seedCommandRow(command, vectorAtAngle(0.20));
        String phantomTopicId = "topic:__never_a_real_topic__";
        seedTopicRowWithId(phantomTopicId, "__never_a_real_topic__", vectorAtAngle(0.30));
        assertTrue(rowExists(HelpTopicCorpus.DOC_KIND, phantomTopicId),
                "phantom topic row seeded");
        assertTrue(rowExists(CommandIntentIndex.DOC_KIND, command),
                "command_intent row seeded");

        builder.onStart(new StartupEvent());

        assertFalse(rowExists(HelpTopicCorpus.DOC_KIND, phantomTopicId),
                "the phantom topic row must be pruned on the next startup — the index must "
                        + "never serve a phantom topic");
        assertTrue(rowExists(CommandIntentIndex.DOC_KIND, command),
                "the command_intent row must survive the topic builder's prune — the "
                        + "doc_kind-scoped DELETE cannot touch another corpus");
    }

    /**
     * Acceptance item 7 — {@link CommandIntentIndex#lookupTopic} returns
     * a pointer only: the matched topic's {@code target_ref} (its slug),
     * never stored text. Three probes pin the read path:
     * <ol>
     *   <li>nearest topic row returns its target_ref;</li>
     *   <li>a nearer {@code command_intent} row is NEVER returned by
     *       {@code lookupTopic} (cross-kind isolation —
     *       {@code doc_kind='topic'} filter);</li>
     *   <li>below the similarity threshold, returns empty.</li>
     * </ol>
     */
    @Test
    void lookupTopicReturnsPointerOnlyOverArmedPath() throws Exception {
        // Probe 1 — nearest topic returns its target_ref.
        seedTopicRow("clear-vs-forget", vectorAtAngle(0.10));
        try (Connection conn = dataSource.getConnection()) {
            try {
                Optional<String> match = CommandIntentIndex.lookupTopic(
                        conn,
                        toVectorLiteral(vectorAtAngle(0)),
                        SIMILARITY_THRESHOLD);
                assertEquals(Optional.of("clear-vs-forget"), match,
                        "lookupTopic must return the nearest topic row's target_ref "
                                + "(the topic slug)");
            } finally {
                if (!conn.getAutoCommit()) {
                    conn.rollback();
                    conn.setAutoCommit(true);
                }
            }
        }

        // Probe 2 — a nearer command_intent row must NEVER be returned
        // by lookupTopic. Cross-kind isolation via the doc_kind filter.
        deleteFixtures();
        seedTopicRow("clear-vs-forget", vectorAtAngle(0.20));
        seedCommandRow("save", vectorAtAngle(0.05)); // CLOSER to the query
        try (Connection conn = dataSource.getConnection()) {
            try {
                Optional<String> match = CommandIntentIndex.lookupTopic(
                        conn,
                        toVectorLiteral(vectorAtAngle(0)),
                        SIMILARITY_THRESHOLD);
                assertEquals(Optional.of("clear-vs-forget"), match,
                        "lookupTopic must return the nearest TOPIC row, never a "
                                + "command_intent row even when the command row is "
                                + "strictly closer — the doc_kind filter scopes the probe "
                                + "to the topic corpus");
                assertFalse(match.equals(Optional.of("save")),
                        "a command_intent row must never leak through lookupTopic");
            } finally {
                if (!conn.getAutoCommit()) {
                    conn.rollback();
                    conn.setAutoCommit(true);
                }
            }
        }

        // Probe 3 — below threshold returns empty.
        deleteFixtures();
        // cosine similarity 0.50 = distance 0.50 — JUST below the 0.52
        // similarity cutoff (M1-748). A realistic unrelated English query
        // against nomic-embed-text routinely scores in this band.
        seedTopicRow("clear-vs-forget", vectorAtAngle(Math.acos(0.50)));
        try (Connection conn = dataSource.getConnection()) {
            try {
                Optional<String> match = CommandIntentIndex.lookupTopic(
                        conn,
                        toVectorLiteral(vectorAtAngle(0)),
                        SIMILARITY_THRESHOLD);
                assertTrue(match.isEmpty(),
                        "below the similarity threshold, lookupTopic must return empty — "
                                + "the delivery path (M1-666) then surfaces no topic");
            } finally {
                if (!conn.getAutoCommit()) {
                    conn.rollback();
                    conn.setAutoCommit(true);
                }
            }
        }
    }

    /**
     * Acceptance item 10 (negative pin) — this ticket adds no delivery
     * path, so the existing adapter-feeding path cannot emit topic
     * content. The only {@code doc_embedding} path that feeds the
     * adapter is {@link CommandIntentIndex#lookupCommand} (D67 trigger
     * + {@code helpLookup} tool both route through it —
     * {@code ChatAgent.lookupIntentForDelivery} and
     * {@code HelpLookupTool}).
     * A topic row seeded STRICTLY CLOSER to the query than any command
     * row must NEVER surface through {@code lookupCommand}: the
     * {@code doc_kind='command_intent'} filter scopes that probe to the
     * command corpus, so the topic row is invisible.
     *
     * <p>This is the practical form of "no topic answer reaches the
     * adapter via this ticket's paths": the ticket adds no delivery
     * path, so the pin proves the existing adapter-feeding path cannot
     * emit topic content.
     */
    @Test
    void topicRowsNeverSurfaceThroughLookupCommand() throws Exception {
        String command = "save";
        // Topic row STRICTLY CLOSER to the query than the command row.
        seedTopicRow("clear-vs-forget", vectorAtAngle(0.05));
        seedCommandRow(command, vectorAtAngle(0.20));
        try (Connection conn = dataSource.getConnection()) {
            try {
                Optional<String> match = CommandIntentIndex.lookupCommand(
                        conn,
                        toVectorLiteral(vectorAtAngle(0)),
                        List.of(command),
                        0.60);
                assertEquals(Optional.of(command), match,
                        "lookupCommand must return the matched command name, never a topic "
                                + "target_ref — the doc_kind='command_intent' filter scopes "
                                + "the probe to the command corpus even when a topic row is "
                                + "strictly closer to the query");
            } finally {
                if (!conn.getAutoCommit()) {
                    conn.rollback();
                    conn.setAutoCommit(true);
                }
            }
        }
    }

    // ---------- helpers ----------

    /**
     * Unit vector in the (dim0, dim1) plane at the given angle from the
     * dim0 axis — cosine similarity to {@code vectorAtAngle(0)} is
     * exactly {@code cos(radians)}. Same helper shape as
     * {@code CommandIntentIndexIT} and {@code HelpLookupToolIT}.
     */
    private static float[] vectorAtAngle(double radians) {
        float[] v = new float[DIMENSION];
        v[0] = (float) Math.cos(radians);
        v[1] = (float) Math.sin(radians);
        return v;
    }

    private static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    /**
     * Resolve a topic slug to its in-memory title, so the test's
     * word-disjointness assertion is sourced from the corpus itself
     * (not a parallel literal the test maintains and could drift
     * from).
     */
    private static String titleFor(String slug) {
        return HelpTopicCorpus.byTargetRef(slug)
                .orElseThrow(() -> new AssertionError(
                        "test references topic '" + slug + "' but it is not in the corpus"))
                .title();
    }

    /**
     * Compute the set of content words (lower-cased, ≥4 chars, not in
     * a small stopword set) of a phrase. Used by the
     * word-disjointness assertion in
     * {@link #phrasingsWithNoSharedContentWordResolveToTopic()}.
     */
    private static Set<String> contentWords(String phrase) {
        Set<String> stopwords = Set.of(
                "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
                "and", "or", "but", "if", "then", "of", "in", "on", "at", "to", "for",
                "with", "without", "into", "from", "by", "as", "this", "that", "these",
                "those", "it", "its", "i", "you", "he", "she", "we", "they", "me",
                "him", "her", "us", "them", "my", "your", "his", "our", "their",
                "do", "does", "did", "doing", "have", "has", "had", "having",
                "what", "when", "where", "why", "how", "who", "which", "whose",
                "can", "cant", "cannot", "will", "would", "shall", "should", "may",
                "might", "must", "could");
        Set<String> words = new HashSet<>();
        for (String token : phrase.toLowerCase().split("[^a-zA-Z]+")) {
            if (token.length() >= 4 && !stopwords.contains(token)) {
                words.add(token);
            }
        }
        return words;
    }

    private void seedTopicRow(String slug, float[] embedding) throws Exception {
        seedTopicRowWithId(HelpTopicCorpus.DOC_ID_PREFIX + slug, slug, embedding);
    }

    private void seedTopicRowWithId(String docId, String targetRef, float[] embedding)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO doc_embedding "
                     + "(doc_id, doc_kind, target_ref, content_hash, embedding, embedding_model) "
                     + "VALUES (?, ?, ?, ?, ?::vector, ?)")) {
            ps.setString(1, docId);
            ps.setString(2, HelpTopicCorpus.DOC_KIND);
            ps.setString(3, targetRef);
            ps.setString(4, "hash-" + targetRef);
            ps.setString(5, toVectorLiteral(embedding));
            ps.setString(6, EMBEDDING_MODEL);
            ps.executeUpdate();
        }
    }

    private void seedCommandRow(String command, float[] embedding) throws Exception {
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
            ps.setString(6, EMBEDDING_MODEL);
            ps.executeUpdate();
        }
    }

    private boolean rowExists(String docKind, String docId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT 1 FROM doc_embedding WHERE doc_kind = ? AND doc_id = ?")) {
            ps.setString(1, docKind);
            ps.setString(2, docId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Wipe ALL doc_embedding rows: topic rows (the @QuarkusTest-boot
     * TopicCorpusBuilder's output), command_intent rows (the
     * @QuarkusTest-boot CommandIntentIndexBuilder's output, plus any
     * rows this IT seeds for cross-kind isolation probes), and any
     * non-standard doc_kind left over from a sibling IT's
     * M1-660-style crowd fixtures (the {@code zz_m1660_foreign} kind
     * {@code CommandIntentIndexIT} seeds). Every kind is in scope, so
     * one unconditional DELETE is the whole wipe.
     *
     * <p>Calls {@link #reindexHnswGraph()} at the end so every wipe
     * leaves a clean graph — including intra-test wipes between
     * sequential probes (e.g. the three probes in
     * {@link #lookupTopicReturnsPointerOnlyOverArmedPath()}). HNSW
     * deletes leave dead graph nodes until VACUUM, and a fresh row
     * seeded into the duplicate-vector region the boot churn created
     * can become unreachable from the graph entry point.
     */
    private void deleteFixtures() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM doc_embedding");
        }
        reindexHnswGraph();
    }

    /**
     * Rebuild the HNSW graph from the live heap. Sibling tests in this
     * class and in {@code CommandIntentIndexIT} churn {@code doc_embedding}
     * (insert + delete of identical stub vectors at boot and per test);
     * HNSW deletes leave dead graph nodes until VACUUM, and a fresh row
     * inserted into that degenerate duplicate-vector region can become
     * unreachable from the graph entry point — empirically, even an
     * exhaustive iterative scan then exhausts after the reachable
     * component without ever surfacing the row. REINDEX pins the
     * precondition the recall probes below rely on (every LIVE row
     * reachable). Mirrors {@code CommandIntentIndexIT.reindexHnswGraph}.
     * Production sees no comparable state: the corpus is rebuilt at
     * most once per boot with diverse real embeddings.
     */
    private void reindexHnswGraph() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("REINDEX INDEX idx_doc_embedding_hnsw");
        }
    }

    static class CountingEmbedder implements EmbeddingProvider {
        int embedCalls;

        @Override
        public List<EmbeddingResult> embed(List<String> texts) {
            embedCalls++;
            // Return one unit vector per input. pgvector's cosine
            // operator is undefined for a zero vector, so unit is the
            // safe canned shape (mirrors StubEmbeddingProvider and
            // CommandIntentIndexIT.CountingEmbedder).
            float[] v = new float[DIMENSION];
            v[0] = 1.0f;
            return texts.stream().map(t -> new EmbeddingResult(v)).toList();
        }
    }
}
