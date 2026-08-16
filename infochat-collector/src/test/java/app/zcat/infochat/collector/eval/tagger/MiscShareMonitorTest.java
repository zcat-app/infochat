package app.zcat.infochat.collector.eval.tagger;

import app.zcat.infochat.collector.eval.testing.StubLlmProvider;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.llm.LlmProvider;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the M1-865 misc-share monitor (decision 5, analysis P16): sustained all-misc runs fire the DISTINCT tagger.sustained_misc_share class (no-tags and fallback stay silent), a share at the threshold and a cold start stay silent. The end-to-end case drives the CDI-wired worker + monitor at production config values, pinning the processOne wiring too. */
@QuarkusTest
class MiscShareMonitorTest {

    /** Tag names the misc fixtures insert or flip; snapshot-restored per test. */
    private static final List<String> TOUCHED = List.of("others", "misc");

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    TaggerWorker taggerWorker;

    @Inject
    TagVocabulary tagVocabulary;

    @Inject
    MiscShareMonitor miscShareMonitor;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @Inject
    LlmProvider llmProvider;

    /** Pre-test {@code [node_kind, parent_name]} per touched name; absent = the row did not exist. */
    private final Map<String, String[]> snapshot = new HashMap<>();

    private StubLlmProvider stub() {
        return (StubLlmProvider) llmProvider;
    }

    @BeforeEach
    void reset() throws Exception {
        stub().reset();
        // The CDI monitor's window is in-memory and shared across the
        // whole Quarkus test instance — same per-test slate role as
        // stub().reset().
        miscShareMonitor.reset();
        clearNotifierState(MiscShareMonitor.ERROR_CLASS_SUSTAINED_MISC_SHARE);
        snapshotTouchedRows();
    }

    @AfterEach
    void restoreTouchedRows() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            String[] touched = TOUCHED.toArray(new String[0]);
            // Detach parent links first: deleting or re-flipping a top while
            // a touched leaf still references it violates the self-FK.
            try (PreparedStatement detach = conn.prepareStatement(
                    "UPDATE tag SET parent_name = NULL WHERE name = ANY(?)")) {
                detach.setArray(1, conn.createArrayOf("text", touched));
                detach.executeUpdate();
            }
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM tag WHERE name = ANY(?) AND NOT (name = ANY(?))")) {
                del.setArray(1, conn.createArrayOf("text", touched));
                del.setArray(2, conn.createArrayOf("text", snapshot.keySet().toArray(new String[0])));
                del.executeUpdate();
            }
            try (PreparedStatement upd = conn.prepareStatement(
                    "UPDATE tag SET node_kind = ?, parent_name = ? WHERE name = ?")) {
                for (Map.Entry<String, String[]> e : snapshot.entrySet()) {
                    upd.setString(1, e.getValue()[0]);
                    upd.setString(2, e.getValue()[1]);
                    upd.setString(3, e.getKey());
                    upd.addBatch();
                }
                upd.executeBatch();
            }
        }
        // Re-sync the shared bean so no tree shape lingers for later classes.
        tagVocabulary.load();
    }

    @Test
    void sustainedMiscRun_firesDistinctErrorClass() throws Exception {
        // 20/20 completions resolve to misc through the real worker chain:
        // share 1.0 over min-sample 20 / threshold 0.10 fires the DISTINCT
        // misc class; no-tags and fallback stay silent (all posts tagged).
        seedMiscTree();
        clearNotifierState(NoTagsRateMonitor.ERROR_CLASS_SUSTAINED_NO_TAGS);
        clearNotifierState(TaggerWorker.ERROR_CLASS_TAGGER_FALLBACK);
        try {
            List<UUID> postIds = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                stub().setNextResponse("{\"tags\":[\"misc\"]}");
                SeededPost post = seedPost("misc-run-" + i, List.of("ai", "java"));
                taggerWorker.processOne(rowFor(post, List.of("ai", "java")));
                postIds.add(post.id);
            }

            assertEquals(20, stub().callCount(), "every misc proposal is a first-attempt answer");
            assertPostState(postIds.get(0), true, false, Set.of("misc"));
            var misc = throttledAdminNotifier.getState(MiscShareMonitor.ERROR_CLASS_SUSTAINED_MISC_SHARE);
            assertTrue(misc.isPresent(),
                "a sustained all-misc run must raise the vocabulary-growth alert");
            var noTags = throttledAdminNotifier.getState(NoTagsRateMonitor.ERROR_CLASS_SUSTAINED_NO_TAGS);
            assertTrue(noTags.isEmpty(), "a fully tagged run must never fire the no-tags class");
            var fallback = throttledAdminNotifier.getState(TaggerWorker.ERROR_CLASS_TAGGER_FALLBACK);
            assertTrue(fallback.isEmpty(), "successful resolutions must never fire the fallback class");
        } finally {
            // The notifier's state is DB-persistent across the Quarkus test
            // instance; leave the empty slate other classes start from.
            clearNotifierState(MiscShareMonitor.ERROR_CLASS_SUSTAINED_MISC_SHARE);
        }
    }

    @Test
    void miscShareAtThreshold_firesNothing() {
        // Strictly-exceeds semantics: 1 misc in 10 completions is exactly
        // the 0.10 threshold, not over it — a normal trickle stays silent.
        // Hand-wired small window so the test does not need 50 records.
        MiscShareMonitor monitor = smallMonitor(10, 5, 0.10);
        // The misc record comes LAST: at min-sample 5 the window must hold
        // zero misc, and at 10 the share is exactly 0.10 — never over.
        for (int i = 0; i < 10; i++) {
            monitor.record(i == 9);
        }

        var state = throttledAdminNotifier.getState(MiscShareMonitor.ERROR_CLASS_SUSTAINED_MISC_SHARE);
        assertTrue(state.isEmpty(), "a misc share at (not over) the threshold must never alert");
    }

    @Test
    void coldStart_belowMinSample_firesNothingEvenAtAllMisc() {
        // Below the minimum sample the window is silent even at 100% misc:
        // a fresh collector tagging its first handful of posts cannot
        // false-alarm. 4 all-misc completions against a minimum sample of 5.
        MiscShareMonitor monitor = smallMonitor(10, 5, 0.10);
        for (int i = 0; i < 4; i++) {
            monitor.record(true);
        }

        var state = throttledAdminNotifier.getState(MiscShareMonitor.ERROR_CLASS_SUSTAINED_MISC_SHARE);
        assertTrue(state.isEmpty(), "below the minimum sample the window must stay silent");
    }

    // ---------- helpers ----------

    private MiscShareMonitor smallMonitor(int windowSize, int minSample, double threshold) {
        MiscShareMonitor monitor = new MiscShareMonitor();
        monitor.throttledAdminNotifier = throttledAdminNotifier;
        monitor.windowSize = windowSize;
        monitor.minSample = minSample;
        monitor.threshold = threshold;
        monitor.init();
        return monitor;
    }

    private void seedMiscTree() throws Exception {
        upsertTag("others", "top", null);
        upsertTag("misc", "leaf", "others");
        tagVocabulary.load();
    }

    private void snapshotTouchedRows() throws Exception {
        snapshot.clear();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT name, node_kind, parent_name FROM tag WHERE name = ANY(?)")) {
            ps.setArray(1, conn.createArrayOf("text", TOUCHED.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    snapshot.put(rs.getString(1), new String[]{rs.getString(2), rs.getString(3)});
                }
            }
        }
    }

    private void upsertTag(String name, String nodeKind, String parent) throws Exception {
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

    /** The notifier's state is DB-persistent and shared; absence assertions need a known-empty slate. */
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
            ps.setString(1, "misc-test-" + slug);
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
            ps.setString(1, "https://misc-test.example/" + slug);
            ps.setString(2, "Misc Test " + slug);
            ps.setArray(3, conn.createArrayOf("text", bootstrapTags.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void assertPostState(UUID postId, boolean taggerDone, boolean fallback,
                                 Set<String> expectedTags) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT tagger_done, tagger_fallback, tags FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(taggerDone, rs.getBoolean("tagger_done"), "tagger_done");
                assertEquals(fallback, rs.getBoolean("tagger_fallback"), "tagger_fallback");
                String[] actual = (String[]) rs.getArray("tags").getArray();
                Set<String> actualSet = new HashSet<>(Arrays.asList(actual));
                assertEquals(expectedTags, actualSet, "post.tags");
            }
        }
    }

    record SeededPost(UUID id, Instant fetchedAt) {
    }
}
