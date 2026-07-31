package app.zcat.infochat.collector.eval.tagger;

import app.zcat.infochat.collector.eval.PartitionScan;
import app.zcat.infochat.collector.eval.RetryBackoff;
import app.zcat.infochat.collector.eval.testing.StubLlmProvider;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.routing.LlmRouter;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-level assertions for TaggerWorker's partial-valid handling and
 * bootstrap-fallback path. Complements TaggerWorkerIT with focus on the
 * M1-081a acceptance items: partial-valid counter and notifier wiring.
 *
 * <p>It also holds both halves of the M1-726 distinction — a clean
 * {@code {"tags":[]}} versus a proposal whose every tag missed the
 * vocabulary — side by side, so a future change cannot collapse them back
 * into one branch without a visibly contradictory pair of tests.
 *
 * <p>And it pins the M1-735 aggregate detector: the per-post no-tags
 * outcome stays silent, but a sustained all-empty run fires
 * {@link NoTagsRateMonitor#ERROR_CLASS_SUSTAINED_NO_TAGS} — a distinct
 * error class from {@code tagger.fallback_to_bootstrap} — while a
 * normal trickle and a cold start fire nothing.
 */
@QuarkusTest
class TaggerWorkerTest {

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    TaggerWorker taggerWorker;

    @Inject
    TagVocabulary tagVocabulary;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @Inject
    LlmProvider llmProvider;

    @Inject
    RetryBackoff retryBackoff;

    @Inject
    PartitionScan partitionScan;

    @Inject
    NoTagsRateMonitor noTagsRateMonitor;

    private StubLlmProvider stub() {
        return (StubLlmProvider) llmProvider;
    }

    @BeforeEach
    void reset() throws Exception {
        stub().reset();
        // The CDI monitor's window is in-memory and shared across the
        // whole Quarkus test instance — same per-test slate role as
        // stub().reset().
        noTagsRateMonitor.reset();
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

    @Test
    void cleanEmptyTagList_persistsNoTags_withoutRetryOrFallback() throws Exception {
        // The counterpart of zeroValidTags_fallsBackToBootstrapTags above, and
        // deliberately adjacent to it: both replies leave ZERO valid tags, and
        // only invalidCount tells them apart. `{"tags":[]}` is what
        // prompts/tagger.md tells the model to emit when nothing fits, so it
        // is an outcome — no retry, no bootstrap tags, no admin notification
        // (M1-726).
        clearFallbackNotifierState();
        stub().setNextResponse("{\"tags\":[]}");
        SeededPost post = seedPost("clean-empty", List.of("ai", "java"));

        taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

        assertPostState(post.id, true, false, Set.of());
        assertEquals(1, stub().callCount(),
            "a deliberate empty tag list is an answer — it must not be retried");
        var state = throttledAdminNotifier.getState(TaggerWorker.ERROR_CLASS_TAGGER_FALLBACK);
        assertTrue(state.isEmpty(),
            "no admin notification may fire for a post the tagger correctly judged to have no topic");
    }

    @Test
    void nonStringTagsArray_isSchemaViolating_notNoTags() throws Exception {
        // M1-726 round-1 red-team finding: {"tags":[{"name":"ai"}]},
        // {"tags":[1,2]}, {"tags":[null]} are wrong-shape replies, not the
        // deliberate "nothing fits" empty list. parseTags used to drop the
        // non-textual elements and hand validate() an empty list, so the
        // invalidCount discriminator read them as NO_TAGS — no retry, no
        // fallback, no notification. They now take the schema-violating
        // path: retry once, then bootstrap fallback + throttled notify.
        clearFallbackNotifierState();
        stub().setNextResponses("{\"tags\":[{\"name\":\"ai\"}]}", "{\"tags\":[1,2]}");
        SeededPost post = seedPost("nonstring-array", List.of("ai", "java"));

        try {
            taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

            assertEquals(2, stub().callCount(),
                "a wrong-shape tags array is schema-violating — it must retry once");
            assertPostState(post.id, true, true, Set.of("ai", "java"));
            var state = throttledAdminNotifier.getState(TaggerWorker.ERROR_CLASS_TAGGER_FALLBACK);
            assertTrue(state.isPresent(),
                "a reply that stays wrong-shaped on retry falls back to bootstrap tags and notifies");
        } finally {
            // This test FIRES the notifier, and the notifier's state is
            // DB-persistent across the whole Quarkus test instance — leaving
            // it set would make partialValid's must-NOT-fire assertion above
            // fail on test order. Restore the empty slate we started from.
            clearFallbackNotifierState();
        }
    }

    @Test
    void schemaViolatingThenCleanEmptyList_resolvesToNoTagsNotBootstrap() throws Exception {
        // The distinction has to survive the SECOND attempt too: a garbage
        // first reply retries with the line-oriented fallback prompt, whose
        // own "nothing fits" shape is a bare `TAGS:`. That is still an answer,
        // so the chain must terminate there rather than falling through to
        // the source's bootstrap tags.
        stub().setNextResponses("this is not json", "TAGS:");
        SeededPost post = seedPost("schema-then-empty", List.of("ai", "java"));

        taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

        assertEquals(2, stub().callCount(),
            "the schema-violating first attempt still retries once");
        assertPostState(post.id, true, false, Set.of());
    }

    @Test
    void unreachableThenCleanEmptyList_resolvesToNoTagsNotBootstrap() throws Exception {
        // Same second-attempt property on the UNREACHABLE arm. The shared
        // StubLlmProvider fails either every call or none, so this leg wires
        // its own TaggerWorker over a two-behaviour provider — the only way to
        // make attempt 1 throw and attempt 2 answer.
        AtomicInteger calls = new AtomicInteger();
        TaggerWorker worker = workerOver((task, systemPrompt, userPrompt) -> {
            if (calls.incrementAndGet() == 1) {
                throw new RuntimeException("simulated LLM unreachable");
            }
            return new LlmResponse("{\"tags\":[]}");
        });
        SeededPost post = seedPost("unreachable-then-empty", List.of("ai", "java"));

        worker.processOne(rowFor(post, List.of("ai", "java")));

        assertEquals(2, calls.get(), "the unreachable arm retries exactly once");
        assertPostState(post.id, true, false, Set.of());
    }

    @Test
    void capsValidTagsAtMax_keepsFirstByEmissionOrder_reportsCappedCount() throws Exception {
        // Seed MAX+2 distinct vocabulary tags so the cap actually bites.
        int over = TaggerWorker.MAX_TAGS_PER_POST + 2;
        List<String> emitted = new ArrayList<>();
        for (int i = 0; i < over; i++) {
            String name = "captag" + i;
            seedVocabularyTag(name);
            emitted.add(name);
        }
        tagVocabulary.load();

        // Direct assertion: validate truncates to the cap in emission
        // order and counts the distinct tags dropped purely by the cap.
        TaggerWorker.ValidationResult result = taggerWorker.validate(emitted);
        assertEquals(emitted.subList(0, TaggerWorker.MAX_TAGS_PER_POST), result.valid(),
            "first MAX_TAGS_PER_POST tags kept in emission order");
        assertEquals(over - TaggerWorker.MAX_TAGS_PER_POST, result.cappedCount(),
            "tags past the cap reported as capped");

        // End-to-end: persisted post.tags holds exactly the first MAX,
        // bootstrap fallback does NOT fire (the LLM succeeded).
        List<String> quoted = new ArrayList<>();
        for (String t : emitted) {
            quoted.add("\"" + t + "\"");
        }
        stub().setNextResponse("{\"tags\":[" + String.join(",", quoted) + "]}");
        SeededPost post = seedPost("tag-cap", List.of("ai", "java"));

        taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

        assertPostState(post.id, true, false,
            new HashSet<>(emitted.subList(0, TaggerWorker.MAX_TAGS_PER_POST)));
    }

    @Test
    void normalTagCount_belowCap_keepsAllAndReportsZeroCapped() {
        // A normal 1–4 tag response is unchanged by the cap.
        List<String> emitted = List.of("security", "news", "finance");

        TaggerWorker.ValidationResult result = taggerWorker.validate(emitted);

        assertEquals(emitted, result.valid(), "all valid tags kept below the cap");
        assertEquals(0, result.cappedCount(), "nothing capped below the cap");
    }

    @Test
    void fencedJsonObject_recoversTagsInsteadOfBootstrapFallback() throws Exception {
        // A valid {"tags":[...]} object wrapped in a ```json markdown code
        // fence (the DeepSeek shape from M1-586). Before the fence-strip the
        // strict readTree rejected the fence → SCHEMA_VIOLATING → retry →
        // bootstrap fallback; now it is recovered on the first attempt
        // (callCount==1, tagger_fallback=false, LLM tags persisted).
        stub().setNextResponse("```json\n{\"tags\":[\"security\",\"news\"]}\n```");
        SeededPost post = seedPost("fenced", List.of("ai", "java"));

        taggerWorker.processOne(rowFor(post, List.of("ai", "java")));

        // tagger_fallback=false + the LLM tags (not the {ai,java} bootstrap
        // set) prove the fenced object was recovered rather than degrading to
        // the bootstrap fallback. (Asserting on the shared ThrottledAdminNotifier
        // state would be order-dependent — zeroValidTags... leaves the same
        // error-class present — so the per-post state is the reliable proof.)
        assertPostState(post.id, true, false, Set.of("security", "news"));
        assertEquals(1, stub().callCount(),
            "fenced-but-valid reply parses on the first attempt — no schema-violating retry");
    }

    @Test
    void renderPrompt_wrapsTitleInsideDelimiter() {
        // D21 remediation (redteam follow-up from M1-597): the untrusted post
        // title must sit INSIDE the per-call {{id}} delimiter, not before the
        // opener, so a feed-controlled title cannot reach the model as
        // un-delimited instructions (mirrors the classifier.md fix).
        String template = TaggerWorker.loadResource(TaggerWorker.PRIMARY_PROMPT_RESOURCE);
        TaggerWorker.PostRow row = new TaggerWorker.PostRow(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            Instant.EPOCH, "EVIL TITLE INJECTION", "post body text", List.of("ai"));

        String rendered = taggerWorker.renderPrompt(template, "DELIM-TOKEN-1", row);

        // The prompt PREAMBLE also names the delimiter tokens when it explains
        // the wrapper format, so target the ACTUAL content wrapper (the last
        // occurrence) rather than the preamble's explanatory mention.
        int opener = rendered.lastIndexOf("<<<UNTRUSTED_CONTENT id=\"DELIM-TOKEN-1\">>>");
        int closer = rendered.lastIndexOf("<<<END id=\"DELIM-TOKEN-1\">>>");
        int title = rendered.indexOf("EVIL TITLE INJECTION");
        assertTrue(opener >= 0, "the delimiter opener must be present");
        assertTrue(closer > opener, "the delimiter closer must follow the opener");
        assertTrue(title > opener && title < closer,
            "the untrusted title must sit INSIDE the {{id}} delimiter block (D21), "
                + "not before the opener where it would read as instructions");
    }

    // ---------- M1-735: aggregate no-tags rate detector ----------

    @Test
    void sustainedAllEmptyRun_firesDistinctErrorClass_notFallback() throws Exception {
        // The M1-726 round-1 LOW red-team finding's repro, closed: a tagger
        // answering {"tags":[]} to EVERY post drives the whole corpus to
        // tags='{}'. The per-post path stays silent (no retry, no fallback,
        // no per-post notification), but the AGGREGATE rate past the minimum
        // sample must fire tagger.sustained_no_tags — and must NOT fire
        // tagger.fallback_to_bootstrap (distinct classes, distinct runbooks).
        // Uses the CDI-wired worker and monitor with their real config
        // defaults (window 50 / min-sample 20 / threshold 0.9), so this test
        // also pins the production wiring and the configured values. One
        // tagged post in the middle (19/20 = 0.95 > 0.9) proves a single
        // healthy answer does not silence a dead tagger.
        clearNotifierState(NoTagsRateMonitor.ERROR_CLASS_SUSTAINED_NO_TAGS);
        clearFallbackNotifierState();
        try {
            for (int i = 0; i < 20; i++) {
                stub().setNextResponse(i == 6 ? "{\"tags\":[\"security\"]}" : "{\"tags\":[]}");
            }
            List<SeededPost> posts = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                posts.add(seedPost("all-empty-" + i, List.of("ai", "java")));
            }
            for (SeededPost post : posts) {
                taggerWorker.processOne(rowFor(post, List.of("ai", "java")));
            }

            assertEquals(20, stub().callCount(),
                "every empty reply is an answer — no post is retried");
            assertPostState(posts.get(0).id, true, false, Set.of());
            assertPostState(posts.get(6).id, true, false, Set.of("security"));
            var state = throttledAdminNotifier.getState(NoTagsRateMonitor.ERROR_CLASS_SUSTAINED_NO_TAGS);
            assertTrue(state.isPresent(),
                "a sustained all-empty tagger output must raise the aggregate alert");
            var fallbackState = throttledAdminNotifier.getState(TaggerWorker.ERROR_CLASS_TAGGER_FALLBACK);
            assertTrue(fallbackState.isEmpty(),
                "no-tags outcomes must never fire the bootstrap-fallback error class");
        } finally {
            // The notifier's state is DB-persistent across the Quarkus test
            // instance; leave the empty slate the absence-asserting tests
            // below start from.
            clearNotifierState(NoTagsRateMonitor.ERROR_CLASS_SUSTAINED_NO_TAGS);
        }
    }

    @Test
    void noTagsTrickle_belowThreshold_firesNothing() throws Exception {
        // A normal trickle of untaggable posts is M1-726's intended behavior
        // and must never alarm: 2 no-tags out of 10 completions (share 0.2,
        // well under the 0.9 threshold) past the minimum sample of 5.
        // Hand-wired small window so the test does not need 50 posts; the
        // CDI-default values are pinned by the all-empty test above.
        clearNotifierState(NoTagsRateMonitor.ERROR_CLASS_SUSTAINED_NO_TAGS);
        TaggerWorker worker = workerOver(stub(), smallMonitor(10, 5, 0.9));
        List<SeededPost> posts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            // 2 no-tags out of 10, interleaved with tagged answers.
            stub().setNextResponse(i == 3 || i == 7 ? "{\"tags\":[]}" : "{\"tags\":[\"security\"]}");
            posts.add(seedPost("trickle-" + i, List.of("ai", "java")));
        }
        for (SeededPost post : posts) {
            worker.processOne(rowFor(post, List.of("ai", "java")));
        }

        // The per-post outcomes are untouched: tagged posts keep their LLM
        // tags, untaggable posts persist tags='{}' with no fallback.
        assertPostState(posts.get(0).id, true, false, Set.of("security"));
        assertPostState(posts.get(3).id, true, false, Set.of());
        var state = throttledAdminNotifier.getState(NoTagsRateMonitor.ERROR_CLASS_SUSTAINED_NO_TAGS);
        assertTrue(state.isEmpty(),
            "a no-tags share below threshold must never fire the aggregate alert");
    }

    @Test
    void coldStart_belowMinSample_firesNothingEvenAtAllEmpty() throws Exception {
        // Below the minimum sample the window is silent even at 100%
        // no-tags: a fresh collector tagging its first handful of posts
        // cannot false-alarm. 4 all-empty completions against a minimum
        // sample of 5.
        clearNotifierState(NoTagsRateMonitor.ERROR_CLASS_SUSTAINED_NO_TAGS);
        TaggerWorker worker = workerOver(stub(), smallMonitor(10, 5, 0.9));
        for (int i = 0; i < 4; i++) {
            stub().setNextResponse("{\"tags\":[]}");
            SeededPost post = seedPost("cold-start-" + i, List.of("ai", "java"));
            worker.processOne(rowFor(post, List.of("ai", "java")));
        }

        var state = throttledAdminNotifier.getState(NoTagsRateMonitor.ERROR_CLASS_SUSTAINED_NO_TAGS);
        assertTrue(state.isEmpty(),
            "below the minimum sample the window must stay silent even at 100% no-tags");
    }

    // ---------- helpers ----------

    /**
     * A hand-wired {@link NoTagsRateMonitor} with explicit window
     * parameters, so window-semantics tests do not need to drive the
     * production-sized default window (50) to reach the sample floor.
     */
    private NoTagsRateMonitor smallMonitor(int windowSize, int minSample, double threshold) {
        NoTagsRateMonitor monitor = new NoTagsRateMonitor();
        monitor.throttledAdminNotifier = throttledAdminNotifier;
        monitor.windowSize = windowSize;
        monitor.minSample = minSample;
        monitor.threshold = threshold;
        monitor.init();
        return monitor;
    }

    /**
     * A TaggerWorker wired to a caller-supplied provider instead of the
     * shared stub, so a test can vary behaviour BETWEEN the two attempts of
     * the fallback chain. Every other collaborator is the real injected bean,
     * so the DB write and the notifier path stay identical to production.
     * The monitor is a fresh hand-wired instance with the production
     * parameters — the two-post scenarios this helper exists for never
     * reach its sample floor, so it stays silent.
     */
    private TaggerWorker workerOver(LlmProvider provider) {
        return workerOver(provider, smallMonitor(50, 20, 0.9));
    }

    /**
     * {@link #workerOver(LlmProvider)} with a caller-supplied
     * {@link NoTagsRateMonitor}, so window-semantics tests can shrink
     * the window and sample floor.
     */
    private TaggerWorker workerOver(LlmProvider provider, NoTagsRateMonitor monitor) {
        TaggerWorker worker = new TaggerWorker();
        worker.dataSource = dataSource;
        worker.llmRouter = new LlmRouter(
            List.of(new LlmRouter.Entry("test-stub", provider, Set.of("en"))),
            LlmRouter.ConfigReader.fromMap(
                Map.of(LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER, "test-stub")));
        worker.tagVocabulary = tagVocabulary;
        worker.throttledAdminNotifier = throttledAdminNotifier;
        worker.retryBackoff = retryBackoff;
        worker.noTagsRateMonitor = monitor;
        worker.partitionScan = partitionScan;
        worker.maxConcurrency = 1;
        worker.init();
        return worker;
    }

    /**
     * The notifier's state is DB-persistent and shared across every test in
     * the Quarkus instance, so a "no notification fired" assertion is only
     * meaningful from a known-empty slate — the same per-key cleanup
     * TaggerWorkerBackoffTest performs for this key.
     */
    private void clearFallbackNotifierState() throws Exception {
        clearNotifierState(TaggerWorker.ERROR_CLASS_TAGGER_FALLBACK);
    }

    /** {@link #clearFallbackNotifierState()} for an arbitrary notification key. */
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
