package app.zcat.infochat.collector.eval.tagger;

import app.zcat.infochat.collector.eval.testing.StubLlmProvider;
import app.zcat.infochat.collector.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.llm.LlmProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-level assertions for TaggerWorker's partial-valid handling and
 * bootstrap-fallback path. Complements TaggerWorkerIT with focus on the
 * M1-081a acceptance items: partial-valid counter and notifier wiring.
 */
@QuarkusTest
class TaggerWorkerTest {

    @Inject
    DataSource dataSource;

    @Inject
    TaggerWorker taggerWorker;

    @Inject
    TagVocabulary tagVocabulary;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @Inject
    LlmProvider llmProvider;

    private StubLlmProvider stub() {
        return (StubLlmProvider) llmProvider;
    }

    @BeforeEach
    void reset() throws Exception {
        stub().reset();
        seedVocabularyTag("security");
        seedVocabularyTag("news");
        seedVocabularyTag("finance");
        tagVocabulary.load();
    }

    @Test
    void partialValidTags_keepsValidDropsInvalid_noFallback() throws Exception {
        // LLM emits 3 valid + 1 invalid: only valid tags are kept,
        // bootstrap fallback does NOT fire.
        stub().setNextResponse("{\"tags\":[\"security\",\"news\",\"finance\",\"INVALIDTAG\"]}");
        SeededPost post = seedPost("partial-valid", List.of("ai", "java"));

        taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

        assertPostState(post.id, true, false, Set.of("security", "news", "finance"));
        // bootstrap fallback must NOT have fired — no notification
        var state = throttledAdminNotifier.getState(TaggerWorker.ERROR_CLASS_TAGGER_FALLBACK);
        assertTrue(state.isEmpty(),
            "ThrottledAdminNotifier should NOT fire for partial-valid (some tags passed)");
    }

    @Test
    void zeroValidTags_fallsBackToBootstrapTags() throws Exception {
        // Both attempts yield zero valid tags → bootstrap fallback fires.
        stub().setNextResponses(
            "{\"tags\":[\"INVALID1\",\"INVALID2\"]}",
            "{\"tags\":[\"INVALID3\"]}");
        SeededPost post = seedPost("zero-valid", List.of("ai", "java"));

        taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

        assertPostState(post.id, true, true, Set.of("ai", "java"));
        var state = throttledAdminNotifier.getState(TaggerWorker.ERROR_CLASS_TAGGER_FALLBACK);
        assertTrue(state.isPresent(),
            "ThrottledAdminNotifier should fire on bootstrap fallback");
    }

    // ---------- helpers ----------

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

    private void seedVocabularyTag(String name) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO tag (name, display, source_origin) "
                     + "VALUES (?, ?, 'bootstrap') "
                     + "ON CONFLICT (name) DO NOTHING")) {
            ps.setString(1, name);
            ps.setString(2, name);
            ps.executeUpdate();
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
