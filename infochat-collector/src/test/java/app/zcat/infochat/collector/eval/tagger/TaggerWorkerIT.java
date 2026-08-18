package app.zcat.infochat.collector.eval.tagger;

import app.zcat.infochat.collector.bootstrap.BootstrapLoader;
import app.zcat.infochat.collector.eval.testing.StubLlmProvider;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.llm.LlmProvider;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * {@code app.zcat.infochat.collector.eval.testing}) is the shared
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
 * <p>The bootstrap fixture seeds {@code ai}/{@code software-development}
 * into the {@code tag} table at startup. This IT additionally
 * seeds the unique parentless fixture names
 * {@code tagger-fixture-security}/{@code tagger-fixture-news} in
 * @BeforeEach (idempotent ON CONFLICT DO
 * NOTHING) and reloads the {@link TagVocabulary} cache so both
 * vocabulary members are visible to the {@link TaggerWorker}
 * regardless of test execution order. The names are deliberately
 * unique: the M1-866 seed owns the flat-era names these cases used
 * before, so they pin identity-mode semantics on fresh leaves.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TaggerWorkerIT {

    /**
     * One hour after the latest seeded fetched_at (2026-05-15T13:30Z, the
     * quarantined post) so every seed is inside the now-32d pickup window
     * on every calendar date.
     */
    private static final Instant PINNED_NOW = Instant.parse("2026-05-15T14:30:00Z");

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    TaggerWorker taggerWorker;

    @Inject
    BootstrapLoader bootstrapLoader;

    @Inject
    TagVocabulary tagVocabulary;

    @Inject
    LlmProvider llmProvider;

    private StubLlmProvider stub() {
        return (StubLlmProvider) llmProvider;
    }

    @BeforeEach
    void reset() throws Exception {
        // Pin the injected Clock so the seeded posts stay inside the
        // tagger's now-32d pickup window on every calendar date. Unpinned,
        // scenario 27.7's quarantine-exclusion assertion went silently
        // vacuous on 2026-06-16 (seed+32d): the post was excluded by the
        // fetched_at floor instead of the status filter (engineering-rules
        // §9, M1-444/M1-601 pattern; M1-602).
        QuarkusMock.installMockForType(
            Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
        stub().reset();
        seedVocabularyTag("tagger-fixture-news");
        seedVocabularyTag("tagger-fixture-security");
        // Refresh the cached vocabulary so the seeded names are
        // visible even if they were absent at first-startup.
        tagVocabulary.load();
    }

    // ---------- 27.1 happy path ----------

    @Test
    @Order(1)
    void happyPathPersistsValidVocabularyTagsAndAdvancesTaggerDone() throws Exception {
        stub().setNextResponse("{\"tags\":[\"tagger-fixture-security\",\"tagger-fixture-news\"]}");
        SeededPost post = seedPickupReadyPost(
            "tagger-it-happy", "Original body", List.of("ai", "java"));

        taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

        assertPostState(post.id, /* taggerDone */ true, /* fallback */ false,
            List.of("tagger-fixture-security", "tagger-fixture-news"));
    }

    // ---------- 27.2 partial-valid ----------

    @Test
    @Order(2)
    void partialValidDropsInvalidTagsAndKeepsValidWithoutFallback() throws Exception {
        stub().setNextResponse("{\"tags\":[\"tagger-fixture-security\",\"tagger-fixture-news\",\"NOTAVALIDTAG\"]}");
        SeededPost post = seedPickupReadyPost(
            "tagger-it-partial", "Body", List.of("ai", "java"));

        taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

        assertPostState(post.id, true, /* fallback */ false,
            List.of("tagger-fixture-security", "tagger-fixture-news"));
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
            "TAGS: tagger-fixture-security, tagger-fixture-news");
        SeededPost post = seedPickupReadyPost(
            "tagger-it-schema-violating", "Body", List.of("ai", "java"));

        taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

        assertEquals(2, stub().callCount(),
            "schema-violating path must retry once with the fallback prompt");
        assertPostState(post.id, true, /* fallback */ false,
            List.of("tagger-fixture-security", "tagger-fixture-news"));
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

    // ---------- 27.8 clean empty tag list → no tags, no fallback ----------

    @Test
    @Order(8)
    void cleanEmptyTagListPersistsNoTagsAndLeavesBootstrapTagsOff() throws Exception {
        // M1-726, end to end through the real pipeline write: the model
        // complied with prompts/tagger.md's "if none fit well, output
        // {"tags": []}", so the cursor advances with an EMPTY tags array and
        // tagger_fallback=false. The source's bootstrap tags must be nowhere
        // on the row — filing an off-topic post under its source's topic is
        // exactly the defect this scenario pins closed.
        stub().setNextResponse("{\"tags\":[]}");
        SeededPost post = seedPickupReadyPost(
            "tagger-it-clean-empty", "Body", List.of("ai", "java"));

        taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

        assertEquals(1, stub().callCount(),
            "a deliberate empty tag list is an answer — no retry");
        assertPostState(post.id, /* taggerDone */ true, /* fallback */ false,
            List.of());
        assertPostTagsExclude(post.id, List.of("ai", "java"));
    }

    // ---------- leaf-only bootstrap fallback ----------

    @Test
    @Order(9)
    void bootstrapFallbackStoresOnlyLeafAfterRejectedTopInputs(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws Exception {
        // Exercise the collector admission boundary first: the same loader
        // rejects a top before writes, then admits the leaf-valued source used
        // by the fallback assertion below.
        String topIdentifier = "https://tagger-it.example.test/leaf-gate-top/feed.xml";
        String leafIdentifier = "https://tagger-it.example.test/leaf-gate-leaf/feed.xml";
        Path topFixture = tempDir.resolve("top-tags.json");
        Files.writeString(topFixture, """
            [{"kind":"rss","identifier":"%s","name":"Top gate","category":"news","tags":["tech"]}]
            """.formatted(topIdentifier));
        Path leafFixture = tempDir.resolve("leaf-tags.json");
        Files.writeString(leafFixture, """
            [{"kind":"rss","identifier":"%s","name":"Leaf gate","category":"news","tags":["ai","software-development"]}]
            """.formatted(leafIdentifier));

        BootstrapLoader unwrapped = ClientProxy.unwrap(bootstrapLoader);
        Field sourcesFilePath = BootstrapLoader.class.getDeclaredField("sourcesFilePath");
        sourcesFilePath.trySetAccessible();
        String originalPath = (String) sourcesFilePath.get(unwrapped);
        try {
            sourcesFilePath.set(unwrapped, topFixture.toString());
            assertTrue(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> bootstrapLoader.runLoad())
                .getMessage().contains("tech"));
            assertEquals(0L, countSources(topIdentifier));

            sourcesFilePath.set(unwrapped, leafFixture.toString());
            bootstrapLoader.runLoad();
        } finally {
            sourcesFilePath.set(unwrapped, originalPath);
        }

        stub().failAll();
        List<String> bootstrapLeaves = List.of("ai", "software-development");
        SeededPost post = seedPickupReadyPostForExistingSource(
            "tagger-it-leaf-bootstrap-fallback", "Body", leafIdentifier, bootstrapLeaves);

        taggerWorker.processOne(rowFor(post, bootstrapLeaves));

        assertEquals(2, stub().callCount(),
            "bootstrap fallback must follow two failed tagger attempts");
        assertPostState(post.id, true, /* fallback */ true, bootstrapLeaves);
        assertPostTagsExclude(post.id, List.of("tech"));
    }

    // ---------- helpers ----------

    private SeededPost seedPickupReadyPostForExistingSource(
            String slug, String body, String identifier, List<String> bootstrapTags)
            throws Exception {
        UUID sourceId;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id FROM source WHERE identifier = ?")) {
            ps.setString(1, identifier);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "loader must admit the leaf-valued source");
                sourceId = (UUID) rs.getObject(1);
            }
        }
        return insertPickupReadyPost(slug, body, sourceId);
    }

    private SeededPost seedPickupReadyPost(String slug, String body, List<String> bootstrapTags)
            throws Exception {
        UUID sourceId = seedRssSource(
            "https://tagger-it.example.test/" + slug + "/feed.xml",
            "Tagger IT " + slug,
            bootstrapTags);
        return insertPickupReadyPost(slug, body, sourceId);
    }

    private SeededPost insertPickupReadyPost(String slug, String body, UUID sourceId)
            throws Exception {
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

    private long countSources(String identifier) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT count(*) FROM source WHERE identifier = ?")) {
            ps.setString(1, identifier);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
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

    /**
     * Asserts none of the given tags reached {@code post.tags}. Stated
     * separately from {@link #assertPostState} because the interesting claim
     * is about a specific set the row must NOT have acquired (the source's
     * bootstrap tags), not about the row's exact contents.
     */
    private void assertPostTagsExclude(UUID postId, List<String> forbidden) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT tags FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "post row must exist after Tagger");
                Set<String> actual = new HashSet<>(
                    Arrays.asList((String[]) rs.getArray("tags").getArray()));
                for (String tag : forbidden) {
                    assertFalse(actual.contains(tag),
                        "the source's bootstrap tag '" + tag + "' must not be applied to a post "
                            + "the tagger judged to have no topic; got " + actual);
                }
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
