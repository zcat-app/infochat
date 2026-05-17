package io.infochat.collector.eval.tagger;

import io.infochat.collector.eval.testing.StubLlmProvider;
import io.infochat.llm.LlmProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

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
 * End-to-end integration test for the Tagger pipeline. Covers the
 * seven scenarios enumerated in M1-034a's acceptance item 27 with
 * one @Test method per scenario.
 *
 * <h2>Stub provider</h2>
 *
 * <p>{@link StubLlmProvider} (under
 * {@code io.infochat.collector.eval.testing}) is the shared
 * {@code @Alternative @Priority(Integer.MAX_VALUE) @ApplicationScoped}
 * bean Quarkus ArC selects over the real
 * {@code OpenAiCompatibleProvider} for the test profile. It is the
 * same bean Stage2WorkerIT @Injects — the shared placement was
 * extracted during M1-034a's budget-breach refine so two ITs (and
 * later M1-034b's EmbeddingWorker IT, T2's chat-agent IT) can drive
 * canned LLM responses without re-declaring an @Alternative bean.
 *
 * <h2>Vocabulary seeding</h2>
 *
 * <p>The bootstrap fixture seeds {@code security} (among others)
 * into the {@code tag} table at startup. This IT additionally
 * seeds {@code news} in @BeforeEach (idempotent ON CONFLICT DO
 * NOTHING) and reloads the {@link TagVocabulary} cache so both
 * vocabulary members are visible to the {@link TaggerWorker}
 * regardless of test execution order.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TaggerWorkerIT {

    @Inject
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

    @BeforeEach
    void reset() throws Exception {
        stub().reset();
        seedVocabularyTag("news");
        seedVocabularyTag("security");
        // Refresh the cached vocabulary so the seeded names are
        // visible even if they were absent at first-startup.
        tagVocabulary.load();
    }

    // ---------- 27.1 happy path ----------

    @Test
    @Order(1)
    void happyPathPersistsValidVocabularyTagsAndAdvancesTaggerDone() throws Exception {
        stub().setNextResponse("{\"tags\":[\"security\",\"news\"]}");
        SeededPost post = seedPickupReadyPost(
            "tagger-it-happy", "Original body", List.of("ai", "java"));

        taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

        assertPostState(post.id, /* taggerDone */ true, /* fallback */ false,
            List.of("security", "news"));
    }

    // ---------- 27.2 partial-valid ----------

    @Test
    @Order(2)
    void partialValidDropsInvalidTagsAndKeepsValidWithoutFallback() throws Exception {
        stub().setNextResponse("{\"tags\":[\"security\",\"news\",\"NOTAVALIDTAG\"]}");
        SeededPost post = seedPickupReadyPost(
            "tagger-it-partial", "Body", List.of("ai", "java"));

        taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

        assertPostState(post.id, true, /* fallback */ false,
            List.of("security", "news"));
    }

    // ---------- 27.3 zero-valid → bootstrap ----------

    @Test
    @Order(3)
    void zeroValidTagsFallsBackToBootstrapAfterRetryWithSamePrompt() throws Exception {
        // Primary call: no valid tags. Retry call (SAME primary
        // prompt): also no valid tags. After 2 attempts, bootstrap
        // fallback fires.
        stub().setNextResponses(
            "{\"tags\":[\"NOTAVALIDTAG\"]}",
            "{\"tags\":[\"NOTAVALIDTAG\"]}");
        SeededPost post = seedPickupReadyPost(
            "tagger-it-zero-valid", "Body", List.of("ai", "java"));

        taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

        assertEquals(2, stub().callCount(),
            "zero-valid path must retry once with the SAME prompt before bootstrap fallback");
        assertPostState(post.id, true, /* fallback */ true,
            List.of("ai", "java"));
    }

    // ---------- 27.4 schema-violating → fallback prompt → success ----------

    @Test
    @Order(4)
    void schemaViolatingRetriesWithFallbackPromptAndUsesItsOutput() throws Exception {
        // Primary call: garbage. Retry call (DIFFERENT, line-
        // oriented fallback prompt): valid line-oriented reply.
        stub().setNextResponses(
            "this is not json",
            "TAGS: security, news");
        SeededPost post = seedPickupReadyPost(
            "tagger-it-schema-violating", "Body", List.of("ai", "java"));

        taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

        assertEquals(2, stub().callCount(),
            "schema-violating path must retry once with the fallback prompt");
        assertPostState(post.id, true, /* fallback */ false,
            List.of("security", "news"));
    }

    // ---------- 27.5 total-fail → bootstrap ----------

    @Test
    @Order(5)
    void totalFailureOnBothPromptsFallsBackToBootstrap() throws Exception {
        // Primary call: garbage (schema-violating).
        // Retry call (fallback prompt): also garbage.
        stub().setNextResponses(
            "this is not json",
            "still not json");
        SeededPost post = seedPickupReadyPost(
            "tagger-it-total-fail", "Body", List.of("ai", "java"));

        taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

        assertEquals(2, stub().callCount());
        assertPostState(post.id, true, /* fallback */ true,
            List.of("ai", "java"));
    }

    // ---------- 27.6 LLM unreachable → bootstrap ----------

    @Test
    @Order(6)
    void llmUnreachableRetriesOnceThenFallsBackToBootstrap() throws Exception {
        stub().failAll();
        SeededPost post = seedPickupReadyPost(
            "tagger-it-unreachable", "Body", List.of("ai", "java"));

        taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

        assertEquals(2, stub().callCount(),
            "unreachable path must retry once with the SAME prompt before bootstrap fallback");
        assertPostState(post.id, true, /* fallback */ true,
            List.of("ai", "java"));
    }

    // ---------- 27.7 quarantined post NOT picked up ----------

    @Test
    @Order(7)
    void quarantinedPostIsNotPickedUpAndTaggerDoneStaysFalse() throws Exception {
        // Seed a post whose every other column matches the pickup
        // predicate but status='QUARANTINED'. enumeratePending()
        // must not return it.
        SeededPost post = seedQuarantinedPost("tagger-it-quarantined", "Body");

        List<TaggerWorker.PostRow> pending = taggerWorker.enumeratePending(10);

        boolean foundQuarantined = pending.stream()
            .anyMatch(r -> r.id().equals(post.id));
        assertFalse(foundQuarantined,
            "QUARANTINED post must be excluded from pickup; pending: " + pending);
        assertEquals(0, stub().callCount(),
            "stub LLM must not be invoked when no eligible post exists");
        // Sanity: cursor flag stays unchanged (false).
        assertTaggerDone(post.id, false);
    }

    // ---------- helpers ----------

    private SeededPost seedPickupReadyPost(String slug, String body, List<String> bootstrapTags)
            throws Exception {
        UUID sourceId = seedRssSource(
            "https://tagger-it.example.test/" + slug + "/feed.xml",
            "Tagger IT " + slug,
            bootstrapTags);
        Instant fetchedAt = Instant.parse("2026-05-15T13:00:00Z");
        String uid = "tagger-it-" + slug + "-uid";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body,"
                     + "  fetched_at, status,"
                     + "  stage1_done, stage2_done, tagger_done, embedding_done,"
                     + "  stage1_flagged, stage2_failed, tagger_fallback, tags"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, ?, ?, ?, 'RAW',"
                     + "  TRUE, FALSE, FALSE, FALSE, FALSE, FALSE, FALSE, '{}'"
                     + ") RETURNING id, fetched_at")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, "tagger-it-" + slug + "-upstream");
            ps.setString(4, "Tagger IT post " + slug);
            ps.setString(5, body);
            ps.setTimestamp(6, Timestamp.from(fetchedAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                UUID postId = (UUID) rs.getObject(1);
                Instant returnedFetchedAt = rs.getTimestamp(2).toInstant();
                return new SeededPost(postId, uid, returnedFetchedAt);
            }
        }
    }

    private SeededPost seedQuarantinedPost(String slug, String body) throws Exception {
        UUID sourceId = seedRssSource(
            "https://tagger-it.example.test/" + slug + "/feed.xml",
            "Tagger IT " + slug,
            List.of("ai"));
        Instant fetchedAt = Instant.parse("2026-05-15T13:30:00Z");
        String uid = "tagger-it-" + slug + "-uid";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body,"
                     + "  fetched_at, status,"
                     + "  stage1_done, stage2_done, tagger_done, embedding_done,"
                     + "  stage1_flagged, stage2_failed, tagger_fallback, tags"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, ?, ?, ?, 'QUARANTINED',"
                     + "  TRUE, FALSE, FALSE, FALSE, TRUE, FALSE, FALSE, '{}'"
                     + ") RETURNING id, fetched_at")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, "tagger-it-" + slug + "-upstream");
            ps.setString(4, "Tagger IT quarantined " + slug);
            ps.setString(5, body);
            ps.setTimestamp(6, Timestamp.from(fetchedAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                UUID postId = (UUID) rs.getObject(1);
                Instant returnedFetchedAt = rs.getTimestamp(2).toInstant();
                return new SeededPost(postId, uid, returnedFetchedAt);
            }
        }
    }

    private UUID seedRssSource(String identifier, String displayName, List<String> bootstrapTags)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('rss', ?, ?, 'news', ?) "
                     + "RETURNING id")) {
            ps.setString(1, identifier);
            ps.setString(2, displayName);
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
                                  List<String> expectedTags) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT tagger_done, tagger_fallback, tags FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "post row must exist after Tagger");
                assertEquals(taggerDone, rs.getBoolean("tagger_done"), "tagger_done");
                assertEquals(fallback, rs.getBoolean("tagger_fallback"), "tagger_fallback");
                String[] actual = (String[]) rs.getArray("tags").getArray();
                Set<String> actualSet = new HashSet<>(Arrays.asList(actual));
                Set<String> expectedSet = new HashSet<>(expectedTags);
                assertEquals(expectedSet, actualSet, "post.tags");
            }
        }
    }

    private void assertTaggerDone(UUID postId, boolean expected) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT tagger_done FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expected, rs.getBoolean("tagger_done"));
            }
        }
    }

    /**
     * Build a {@link TaggerWorker.PostRow} from a seeded post +
     * known bootstrap tags. The IT bypasses {@code enumeratePending}
     * for the per-scenario calls so each test asserts the
     * three-surface chain in isolation; the pickup query itself is
     * covered by the quarantined-exclusion test.
     */
    private TaggerWorker.PostRow rowFor(SeededPost post, List<String> bootstrapTags) {
        return new TaggerWorker.PostRow(
            post.id, post.fetchedAt,
            "Tagger IT title for " + post.uid,
            "Tagger IT body",
            bootstrapTags);
    }

    private record SeededPost(UUID id, String uid, Instant fetchedAt) {
    }
}
