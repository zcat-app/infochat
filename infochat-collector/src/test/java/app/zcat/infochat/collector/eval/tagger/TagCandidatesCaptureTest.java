package app.zcat.infochat.collector.eval.tagger;

import app.zcat.infochat.collector.eval.testing.StubLlmProvider;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.llm.LlmProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.logmanager.LogContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the Tier-2 candidate array (M1-868): resolution losers persist into post.tag_candidates by the SAME atomic cursor UPDATE as tags — same reply, zero extra calls; bounded and internal-only. */
@QuarkusTest
class TagCandidatesCaptureTest {

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    TaggerWorker taggerWorker;

    @Inject
    TagVocabulary tagVocabulary;

    @Inject
    LlmProvider llmProvider;

    private StubLlmProvider stub() {
        return (StubLlmProvider) llmProvider;
    }

    /** Tag names the tree fixtures insert or flip; snapshot-restored per test (shared table). */
    private static final List<String> TREE_TOUCHED = List.of("sport", "news", "football", "europe");

    /** Pre-fixture {@code [node_kind, parent_name]} per touched name; absent = the row did not exist. */
    private final Map<String, String[]> treeSnapshot = new HashMap<>();

    /** jboss LogContext capture for the cap-overflow drop-count log (NostrRelayConnectionTest pattern). */
    private org.jboss.logmanager.Logger relayLogger;
    private CapturingHandler capturer;

    @BeforeEach
    void reset() throws Exception {
        stub().reset();
        seedTreeFixture();
    }

    private void attachLogHandler() {
        // slf4j routes through jboss-logmanager: the level and the capturing
        // handler go on the jboss LogContext logger, not on
        // java.util.logging (disconnected tree — NostrRelayConnectionTest).
        relayLogger = LogContext.getLogContext().getLogger(
            TaggerWorker.class.getName());
        relayLogger.setLevel(org.jboss.logmanager.Level.ALL);
        capturer = new CapturingHandler();
        capturer.setLevel(Level.ALL);
        relayLogger.addHandler(capturer);
    }

    private void detachLogHandler() {
        relayLogger.removeHandler(capturer);
        relayLogger.setLevel(org.jboss.logmanager.Level.INFO);
    }

    /** Minimal JUL handler recording every {@link LogRecord}; same shape as the FetchSchedulerLogRedactionTest capturer. */
    private static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
            // no-op
        }

        @Override
        public void close() {
            // no-op
        }
    }

    @AfterEach
    void restoreTreeFixture() throws Exception {
        // No empty-snapshot guard: the DELETE below handles the
        // nothing-pre-existed case (removes every touched row), so an
        // empty snapshot must still run the restore, not skip it.
        try (Connection conn = dataSource.getConnection()) {
            String[] touched = TREE_TOUCHED.toArray(new String[0]);
            try (PreparedStatement detach = conn.prepareStatement(
                    "UPDATE tag SET parent_name = NULL WHERE name = ANY(?)")) {
                detach.setArray(1, conn.createArrayOf("text", touched));
                detach.executeUpdate();
            }
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM tag WHERE name = ANY(?) AND NOT (name = ANY(?))")) {
                del.setArray(1, conn.createArrayOf("text", touched));
                del.setArray(2, conn.createArrayOf("text", treeSnapshot.keySet().toArray(new String[0])));
                del.executeUpdate();
            }
            try (PreparedStatement upd = conn.prepareStatement(
                    "UPDATE tag SET node_kind = ?, parent_name = ? WHERE name = ?")) {
                for (Map.Entry<String, String[]> e : treeSnapshot.entrySet()) {
                    upd.setString(1, e.getValue()[0]);
                    upd.setString(2, e.getValue()[1]);
                    upd.setString(3, e.getKey());
                    upd.addBatch();
                }
                upd.executeBatch();
            }
        }
        treeSnapshot.clear();
        tagVocabulary.load();
    }

    @Test
    void resolutionLosersLandInTagCandidates() throws Exception {
        // The reproduction: a validated cross-top proposal (football+europe)
        // stores the resolved winner in post.tags AND the losing leaves in
        // post.tag_candidates — captured from the SAME reply (one LLM call).
        stub().setNextResponse("{\"tags\":[\"europe\",\"football\"]}");
        SeededPost post = seedPost("tier2", List.of("ai", "java"));

        taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

        assertPostState(post.id, true, false, Set.of("football"), List.of("europe"));
        assertEquals(1, stub().callCount(),
            "the losers already exist in the model output — zero additional LLM calls");
    }

    @Test
    void noTagsProposalWritesEmptyCandidates() throws Exception {
        // M1-726 empty proposal is an outcome: nothing was proposed, so the
        // array is '{}' and no retry/fallback fires (acceptance 5).
        stub().setNextResponse("{\"tags\":[]}");
        SeededPost post = seedPost("no-tags", List.of("ai", "java"));

        taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

        assertPostState(post.id, true, false, Set.of(), List.of());
        assertEquals(1, stub().callCount(), "an empty proposal is an answer — no retry");
    }

    @Test
    void bootstrapFallbackWritesEmptyCandidates() throws Exception {
        // Acceptance 5: BOOTSTRAP writes candidates from the failed attempts'
        // validated losers if any, else '{}'; a failed attempt produces none.
        stub().setNextResponses("{\"tags\":[\"INVALID1\"]}", "{\"tags\":[\"INVALID2\"]}");
        SeededPost post = seedPost("bootstrap-cand", List.of("ai", "java"));

        try {
            taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

            assertPostState(post.id, true, true, Set.of("ai", "java"), List.of());
        } finally {
            // This test FIRES the fallback notifier, whose state is DB-persistent
            // and shared; TaggerWorkerTest asserts the empty slate — restore it.
            clearNotifierState(TaggerWorker.ERROR_CLASS_TAGGER_FALLBACK);
        }
    }

    @Test
    void capOverflowKeepsFirstEmittedLosersAndLogsDropCount() throws Exception {
        // P15: the array's own bound, re-derived from M1-328. validate()
        // already caps the pre-resolution list, so the loser count cannot
        // exceed the bound end-to-end — the seam is driven directly.
        attachLogHandler();
        try {
            int over = TaggerWorker.MAX_TAGS_PER_POST + 2;
            List<String> losers = new ArrayList<>();
            for (int i = 0; i < over; i++) {
                losers.add("cand-" + i);
            }
            SeededPost post = seedPost("cap-overflow", List.of("ai"));

            List<String> kept = taggerWorker.cappedCandidates(post.id, losers);

            assertEquals(losers.subList(0, TaggerWorker.MAX_TAGS_PER_POST), kept,
                "exactly cap candidates kept in emission order");
            assertTrue(capturer.records.stream().anyMatch(record -> {
                String msg = record.getMessage();
                Object[] params = record.getParameters();
                if (msg == null || params == null) {
                    return false;
                }
                return msg.contains("tagger_candidates")
                    && msg.contains("dropped=")
                    && "2".equals(String.valueOf(params[params.length - 1]));
            }), "the drop count must be logged (observable, not silent); got: "
                + capturer.records);
        } finally {
            detachLogHandler();
        }
    }

    @Test
    void v83ColumnExistsWithEmptyArrayDefaultAndNoIndex() throws Exception {
        // Acceptance 2: column + DEFAULT + no-index stance (no reader —
        // §7, no machinery ahead of need).
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_nullable, column_default FROM information_schema.columns "
                         + "WHERE table_schema = 'public' AND table_name = 'post'"
                         + " AND column_name = 'tag_candidates'");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "post.tag_candidates must exist (V83)");
                assertEquals("NO", rs.getString(1), "tag_candidates must be NOT NULL");
                String def = rs.getString(2);
                assertTrue(def != null && def.contains("'{}'"),
                    "tag_candidates must DEFAULT '{}', got: " + def);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                     "SELECT indexname FROM pg_indexes WHERE tablename = 'post'"
                         + " AND indexdef ILIKE '%tag_candidates%'");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(!rs.next(), "no index may exist on tag_candidates (no reader)");
            }
        }
    }

    // ---------- helpers ----------

    private void seedTreeFixture() throws Exception {
        snapshotTreeRows();
        upsertTreeTag("sport", "top", null);
        upsertTreeTag("news", "top", null);
        upsertTreeTag("football", "leaf", "sport");
        upsertTreeTag("europe", "leaf", "news");
        tagVocabulary.load();
    }

    private void snapshotTreeRows() throws Exception {
        treeSnapshot.clear();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT name, node_kind, parent_name FROM tag WHERE name = ANY(?)")) {
            ps.setArray(1, conn.createArrayOf("text", TREE_TOUCHED.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    treeSnapshot.put(rs.getString(1), new String[]{rs.getString(2), rs.getString(3)});
                }
            }
        }
    }

    private void upsertTreeTag(String name, String nodeKind, String parent) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO tag (name, display, source_origin, node_kind, parent_name) "
                     + "VALUES (?, ?, 'bootstrap', ?, ?) "
                     + "ON CONFLICT (name) DO UPDATE SET node_kind = EXCLUDED.node_kind, "
                     + "parent_name = EXCLUDED.parent_name")) {
            ps.setString(1, name);
            ps.setString(2, name);
            ps.setString(3, nodeKind);
            ps.setString(4, parent);
            ps.executeUpdate();
        }
    }

    private void assertPostState(UUID postId, boolean taggerDone, boolean fallback,
                                 Set<String> expectedTags, List<String> expectedCandidates)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT tagger_done, tagger_fallback, tags, tag_candidates FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(taggerDone, rs.getBoolean("tagger_done"), "tagger_done");
                assertEquals(fallback, rs.getBoolean("tagger_fallback"), "tagger_fallback");
                String[] actualTags = (String[]) rs.getArray("tags").getArray();
                assertEquals(expectedTags, new HashSet<>(Arrays.asList(actualTags)), "post.tags");
                String[] actualCandidates = (String[]) rs.getArray("tag_candidates").getArray();
                assertEquals(expectedCandidates, Arrays.asList(actualCandidates),
                    "post.tag_candidates in emission order");
            }
        }
    }

    private void clearNotifierState(String key) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM admin_notification_state WHERE notification_key = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        }
    }

    private TaggerWorker.PostRow rowFor(SeededPost post, List<String> bootstrapTags) {
        return new TaggerWorker.PostRow(
            post.id, post.fetchedAt, "title", "body", bootstrapTags);
    }

    private SeededPost seedPost(String slug, List<String> bootstrapTags) throws Exception {
        UUID sourceId = seedSource(slug, bootstrapTags);
        Instant fetchedAt = Instant.parse("2026-05-20T14:00:00Z");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body,"
                     + "  fetched_at, status,"
                     + "  stage1_done, stage1_flagged, stage2_done, stage2_failed,"
                     + "  tagger_done, tagger_fallback, embedding_done, tags, re_eval_attempts"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, 'title', 'body',"
                     + "  ?, 'RAW',"
                     + "  TRUE, FALSE, FALSE, FALSE,"
                     + "  FALSE, FALSE, FALSE, '{}', 0"
                     + ") RETURNING id, fetched_at")) {
            ps.setString(1, "tagger-test-" + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "upstream-" + slug);
            ps.setTimestamp(4, Timestamp.from(fetchedAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new SeededPost((UUID) rs.getObject(1), rs.getTimestamp(2).toInstant());
            }
        }
    }

    private UUID seedSource(String slug, List<String> bootstrapTags) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('rss', ?, ?, 'news', ?) "
                     + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            ps.setString(1, "https://tagger-test.example/" + slug);
            ps.setString(2, "Tagger Test " + slug);
            ps.setArray(3, conn.createArrayOf("text", bootstrapTags.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    record SeededPost(UUID id, Instant fetchedAt) {
    }
}
