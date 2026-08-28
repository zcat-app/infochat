package app.zcat.infochat.collector.eval.tagger;

import app.zcat.infochat.collector.eval.testing.StubLlmProvider;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.llm.LlmProvider;
import io.quarkus.test.junit.QuarkusMock;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

/** Pins the free-tags emission (M1-934): the tagger's EXISTING single LLM
 * call returns a two-field reply whose second field persists into
 * post.search_tags — same call, canonical form, never steering the chain. */
@QuarkusTest
class SearchTagsCaptureTest {

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

    /** Pinned 1h after the seed's fetched_at (ScanWindowFixtureGuard, M1-444 pattern). */
    private static final Instant PINNED_NOW = Instant.parse("2026-05-20T15:00:00Z");

    /** Pre-fixture {@code [node_kind, parent_name]} per touched name; absent = the row did not exist. */
    private final Map<String, String[]> treeSnapshot = new HashMap<>();

    /** jboss LogContext capture for the drop-count log (TagCandidatesCaptureTest pattern). */
    private org.jboss.logmanager.Logger workerLogger;
    private CapturingHandler capturer;

    @BeforeEach
    void reset() throws Exception {
        QuarkusMock.installMockForType(
            Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
        stub().reset();
        seedTreeFixture();
    }

    private void attachLogHandler() {
        workerLogger = LogContext.getLogContext().getLogger(
            TaggerWorker.class.getName());
        workerLogger.setLevel(org.jboss.logmanager.Level.ALL);
        capturer = new CapturingHandler();
        capturer.setLevel(Level.ALL);
        workerLogger.addHandler(capturer);
    }

    private void detachLogHandler() {
        workerLogger.removeHandler(capturer);
        workerLogger.setLevel(org.jboss.logmanager.Level.INFO);
    }

    /** Minimal JUL handler recording every {@link LogRecord} (TagCandidatesCaptureTest pattern). */
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
    void freeTagsRideTheSameCallIntoSearchTags() throws Exception {
        // The reproduction: one two-field reply — tags tree-resolve into
        // post.tags, the free tags persist into post.search_tags from the
        // SAME reply with exactly one provider.generate call.
        stub().setNextResponse(
            "{\"tags\":[\"europe\"],\"search_tags\":[\"czechia\",\"prague-eu-summit\"]}");
        SeededPost post = seedPost("free-tags", List.of("ai", "java"));

        taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

        assertPostState(post.id, true, false,
            Set.of("europe"), List.of(), List.of("czechia", "prague-eu-summit"));
        assertEquals(1, stub().callCount(),
            "the free tags ride the existing call — zero additional LLM calls");
    }

    @Test
    void nonNormalizableFreeTagsAreDroppedAndCounted() throws Exception {
        // Write-side canonicalization is mandatory: class failures are
        // dropped+counted+logged, duplicates collapse, survivors persist.
        attachLogHandler();
        try {
            stub().setNextResponse("{\"tags\":[\"europe\"],\"search_tags\":["
                + "\"czechia\",\"Czech Republic\",\"prague-eu-summit\",\"Česko\",\"czechia\"]}");
            SeededPost post = seedPost("non-normalizable", List.of("ai", "java"));

            taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

            assertPostState(post.id, true, false,
                Set.of("europe"), List.of(), List.of("czechia", "prague-eu-summit"));
            assertTrue(capturer.records.stream().anyMatch(record -> {
                String msg = record.getMessage();
                Object[] params = record.getParameters();
                if (msg == null || params == null) {
                    return false;
                }
                return msg.contains("search_tags")
                    && msg.contains("dropped=")
                    && "2".equals(String.valueOf(params[params.length - 2]));
            }), "the class-reject drop count must be logged; got: " + capturer.records);
        } finally {
            detachLogHandler();
        }
    }

    @Test
    void capOverflowKeepsFirstEmittedFreeTagsAndLogsDropCount() throws Exception {
        // At most MAX_SEARCH_TAGS_PER_POST distinct valid free tags, in
        // emission order; distinct overflow past the cap is counted and
        // logged, never silent.
        attachLogHandler();
        try {
            int over = TaggerWorker.MAX_SEARCH_TAGS_PER_POST + 2;
            StringBuilder tags = new StringBuilder();
            for (int i = 0; i < over; i++) {
                if (i > 0) {
                    tags.append(',');
                }
                tags.append("\"free-tag-").append(i).append('"');
            }
            stub().setNextResponse(
                "{\"tags\":[\"europe\"],\"search_tags\":[" + tags + "]}");
            SeededPost post = seedPost("cap-overflow", List.of("ai", "java"));

            taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

            List<String> expected = new ArrayList<>();
            for (int i = 0; i < TaggerWorker.MAX_SEARCH_TAGS_PER_POST; i++) {
                expected.add("free-tag-" + i);
            }
            assertPostState(post.id, true, false,
                Set.of("europe"), List.of(), expected);
            assertTrue(capturer.records.stream().anyMatch(record -> {
                String msg = record.getMessage();
                Object[] params = record.getParameters();
                if (msg == null || params == null) {
                    return false;
                }
                return msg.contains("search_tags")
                    && msg.contains("capped=")
                    && "2".equals(String.valueOf(params[params.length - 1]));
            }), "the cap-overflow count must be logged; got: " + capturer.records);
        } finally {
            detachLogHandler();
        }
    }

    @Test
    void missingOrNullSearchTagsStoresEmpty() throws Exception {
        // Best-effort passenger: a missing or JSON-null second field means
        // '{}' — never an error, never a retry.
        stub().setNextResponse("{\"tags\":[\"europe\"]}");
        SeededPost missing = seedPost("field-missing", List.of("ai", "java"));
        stub().setNextResponse("{\"tags\":[\"europe\"],\"search_tags\":null}");
        SeededPost jsonNull = seedPost("field-null", List.of("ai", "java"));

        taggerWorker.processOne(rowFor(missing, List.of("ai", "java")));
        taggerWorker.processOne(rowFor(jsonNull, List.of("ai", "java")));

        assertPostState(missing.id, true, false, Set.of("europe"), List.of(), List.of());
        assertPostState(jsonNull.id, true, false, Set.of("europe"), List.of(), List.of());
        assertEquals(2, stub().callCount(), "each post answers on its first attempt");
    }

    @Test
    void noTagsProposalWithFreeTagsStoresThemWithoutRetry() throws Exception {
        // The M1-726 empty-proposal outcome is keyed on the tags array
        // alone: a clean-empty categories proposal with free tags present
        // stores the free tags with tags='{}' and fires no retry.
        stub().setNextResponse("{\"tags\":[],\"search_tags\":[\"zcash\",\"qwen\"]}");
        SeededPost post = seedPost("no-tags-free", List.of("ai", "java"));

        taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

        assertPostState(post.id, true, false, Set.of(), List.of(),
            List.of("zcash", "qwen"));
        assertEquals(1, stub().callCount(),
            "an empty categories proposal is an answer — the free tags ride it, no retry");
    }

    @Test
    void lineOrientedReplyProducesNoSearchTags() throws Exception {
        // The line-oriented fallback shape is tags-only by design; its
        // replies produce search_tags='{}' (documented degradation).
        stub().setNextResponse("TAGS: europe");
        SeededPost post = seedPost("line-reply", List.of("ai", "java"));

        taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

        assertPostState(post.id, true, false, Set.of("europe"), List.of(), List.of());
        assertEquals(1, stub().callCount());
    }

    @Test
    void bootstrapFallbackWritesNoSearchTags() throws Exception {
        // The bootstrap path never saw an answered reply — free tags are
        // empty alongside the Tier-2 candidates.
        stub().setNextResponses("{\"tags\":[\"INVALID1\"]}", "{\"tags\":[\"INVALID2\"]}");
        SeededPost post = seedPost("bootstrap-free", List.of("ai", "java"));

        try {
            taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

            assertPostState(post.id, true, true, Set.of("ai", "java"), List.of(), List.of());
            assertEquals(2, stub().callCount(),
                "the zero-valid chain retries once before the bootstrap fallback");
        } finally {
            // This test FIRES the fallback notifier, whose state is
            // DB-persistent and shared — restore it (TagCandidatesCaptureTest).
            clearNotifierState(TaggerWorker.ERROR_CLASS_TAGGER_FALLBACK);
        }
    }

    @Test
    void v87ColumnExistsWithEmptyArrayDefaultAndNoIndex() throws Exception {
        // The column lands BARE: NOT NULL, DEFAULT '{}', and no index
        // whose definition references it (§7 — no reader exists yet).
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_nullable, column_default FROM information_schema.columns "
                         + "WHERE table_schema = 'public' AND table_name = 'post'"
                         + " AND column_name = 'search_tags'");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "post.search_tags must exist (V87)");
                assertEquals("NO", rs.getString(1), "search_tags must be NOT NULL");
                String def = rs.getString(2);
                assertTrue(def != null && def.contains("'{}'"),
                    "search_tags must DEFAULT '{}', got: " + def);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                     "SELECT indexname FROM pg_indexes WHERE tablename = 'post'"
                         + " AND indexdef ILIKE '%search_tags%'");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(!rs.next(),
                    "no index may exist on search_tags (no index-servable reader)");
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

    /** The five-field co-write pin: one answered call moves tags,
     *  tag_candidates, search_tags, tagger_done and tagger_fallback
     *  together (Invariant 5 — one atomic cursor UPDATE). */
    private void assertPostState(UUID postId, boolean taggerDone, boolean fallback,
                                 Set<String> expectedTags, List<String> expectedCandidates,
                                 List<String> expectedSearchTags)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT tagger_done, tagger_fallback, tags, tag_candidates, search_tags"
                     + " FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(taggerDone, rs.getBoolean("tagger_done"), "tagger_done");
                assertEquals(fallback, rs.getBoolean("tagger_fallback"), "tagger_fallback");
                String[] actualTags = (String[]) rs.getArray("tags").getArray();
                assertEquals(expectedTags, new HashSet<>(Arrays.asList(actualTags)), "post.tags");
                String[] actualCandidates = (String[]) rs.getArray("tag_candidates").getArray();
                assertEquals(expectedCandidates, Arrays.asList(actualCandidates),
                    "post.tag_candidates");
                String[] actualSearchTags = (String[]) rs.getArray("search_tags").getArray();
                assertEquals(expectedSearchTags, Arrays.asList(actualSearchTags),
                    "post.search_tags in emission order");
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
            ps.setString(1, "search-tags-test-" + slug);
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
            ps.setString(1, "https://search-tags-test.example/" + slug);
            ps.setString(2, "Search Tags Test " + slug);
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
