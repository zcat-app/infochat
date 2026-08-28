package app.zcat.infochat.collector.eval.tagger;

import app.zcat.infochat.collector.eval.testing.StubLlmProvider;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.llm.LlmProvider;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the M1-736 re-evaluation sweep: posts whose first
 * tagger pass left arrays empty ({@code tags='{}'} and/or, post-V87,
 * {@code search_tags='{}'}; {@code tagger_done=TRUE},
 * {@code tagger_fallback=FALSE}) are re-evaluated
 * once per input generation, with the per-post attempt cap, the per-sweep
 * batch cap, and the live-pickup-first ordering the ticket pins.
 *
 * <h2>Driving the sweep</h2>
 *
 * <p>The test profile sets {@code infochat.llm.tagger.sweep.batch-size=0}
 * (test {@code application.properties}) so the background 5s tagger tick
 * never sweeps the standing sweep-eligible rows other ITs seed — that would
 * consume {@link StubLlmProvider} FIFO responses and inflate
 * {@code callCount} at random tick boundaries. Each test opts the shared
 * bean back in immediately before driving {@link TaggerWorker#onTick()}
 * synchronously, via {@link ClientProxy#unwrap}: a field write through the
 * injected CDI client proxy hits the PROXY's field slot, not the contextual
 * instance's (the same trap {@code PriceSnapshotStoreTest} and
 * {@code ReadyPromoterIT} unwrap around), which would leave the real bean
 * at 0 and silently skip the sweep.
 *
 * <h2>Leftover hygiene</h2>
 *
 * <p>Two kinds of foreign rows in the shared failsafe boot would otherwise
 * race these tests, and both are neutralized in {@link #reset()}:
 * <ul>
 *   <li><b>Sweep-eligible leftovers</b> (any {@code tags='{}'} /
 *       {@code tagger_done=TRUE} row another IT left behind) — squelched to
 *       the max generation so they are never candidates.</li>
 *   <li><b>Live-pickup leftovers</b> (rows awaiting their first tagger
 *       pass, e.g. Stage2WorkerIT's completed posts) — temporarily flipped
 *       to {@code tagger_done=TRUE} for the duration of each test and
 *       restored in {@link #teardown()}, so {@code onTick()}'s live phase
 *       finds nothing but the row the test itself seeded. Without this the
 *       live phase (correctly) wins the tick, eats the queued stub
 *       responses, and starves the sweep slots the test is trying to
 *       observe.</li>
 * </ul>
 * The marker is reset to generation 1 with a stale fingerprint, so each
 * test's first sweep bumps to generation 2 — exercising the
 * fingerprint-mismatch bump path on every test.
 */
@QuarkusTest
class TaggerWorkerSweepIT {

    /** Same pin as TaggerWorkerIT: keeps the 2026-05-15 seeds inside the
     *  now-32d pickup window on every calendar date. */
    private static final Instant PINNED_NOW = Instant.parse("2026-05-15T14:30:00Z");
    private static final Instant FETCHED_BASE = Instant.parse("2026-05-15T13:00:00Z");

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    TaggerWorker taggerWorker;

    @Inject
    TagVocabulary tagVocabulary;

    @Inject
    LlmProvider llmProvider;

    /** The test-profile value (0), so {@link #teardown()} restores the
     *  configured state rather than a hardcoded one. */
    @ConfigProperty(name = "infochat.llm.tagger.sweep.batch-size")
    int configuredSweepBatchSize;

    /** Live-pickup rows this test hid and must restore. */
    private List<UUID> neutralizedLiveRows = List.of();

    private StubLlmProvider stub() {
        return (StubLlmProvider) llmProvider;
    }

    @BeforeEach
    void reset() throws Exception {
        QuarkusMock.installMockForType(
            Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
        stub().reset();
        seedVocabularyTag("tagger-fixture-news");
        seedVocabularyTag("tagger-fixture-security");
        tagVocabulary.load();
        squelchAllRows();
        resetSweepMarker();
        neutralizedLiveRows = neutralizeLiveBacklog();
    }

    @AfterEach
    void teardown() throws Exception {
        // Leave the shared bean at the test-profile value so a background
        // tick in a LATER test class never sweeps standing rows.
        setSweepBatchSize(configuredSweepBatchSize);
        restoreNeutralizedLiveRows();
    }

    /** Field write on the CONTEXTUAL instance — see the class javadoc for
     *  why the injected proxy cannot be used for this. */
    private void setSweepBatchSize(int value) {
        ClientProxy.unwrap(taggerWorker).sweepBatchSize = value;
    }

    // ---------- vocabulary growth ----------

    @Test
    void sweptPostGainsNewlyFittingVocabularyTag() throws Exception {
        SeededPost post = seedSweptPost("gain", FETCHED_BASE, false, List.of("ai"), List.of());
        // The vocabulary gains a tag that fits the post; the refresh path
        // (load(), what the scheduled refresh calls) changes the
        // fingerprint, which bumps the generation on the next sweep.
        seedVocabularyTag("quantum");
        tagVocabulary.load();
        stub().setNextResponse("{\"tags\":[\"quantum\"]}");

        setSweepBatchSize(4);
        taggerWorker.onTick();

        assertEquals(1, stub().callCount());
        PostState state = readPost(post.id);
        assertEquals(Set.of("quantum"), state.tags,
            "a swept post whose vocabulary gained a fitting tag gains the tag");
        assertFalse(state.fallback);
        assertEquals(2, state.sweptGeneration, "swept at the bumped generation");
        assertEquals(1, state.sweepAttempts);
    }

    @Test
    void alreadySweptPostIsNotRetriedAtSameGeneration() throws Exception {
        SeededPost post = seedSweptPost("retried", FETCHED_BASE, false, List.of("ai"), List.of());
        stub().setNextResponse("{\"tags\":[]}");

        setSweepBatchSize(4);
        taggerWorker.onTick();
        assertEquals(1, stub().callCount());
        PostState first = readPost(post.id);
        assertEquals(2, first.sweptGeneration);
        assertEquals(1, first.sweepAttempts);
        assertTrue(first.tags.isEmpty(), "still nothing -> stays tags='{}'");

        stub().reset();
        taggerWorker.onTick();

        assertEquals(0, stub().callCount(),
            "a post already swept at the current generation must not be re-tried");
        assertEquals(1, readPost(post.id).sweepAttempts,
            "the attempt counter does not move when no attempt happened");
    }

    // ---------- spend caps ----------

    @Test
    void postAtAttemptCapIsSkippedEvenWhenGenerationBumps() throws Exception {
        // Read through ClientProxy.unwrap too — the proxy's field slot is 0.
        int cap = ClientProxy.unwrap(taggerWorker).sweepMaxAttempts;
        SeededPost post = seedSweptPost("capped", FETCHED_BASE, false, List.of("ai"), List.of());
        setSweepBookkeeping(post.id, 0, cap);

        setSweepBatchSize(4);
        taggerWorker.onTick();   // bumps generation 1 -> 2
        taggerWorker.onTick();   // stable inputs

        assertEquals(0, stub().callCount(), "a post at the attempt cap must be skipped");
        PostState state = readPost(post.id);
        assertEquals(0, state.sweptGeneration, "a skipped post's generation must not advance");
        assertEquals(cap, state.sweepAttempts);

        // Even a further generation bump must not resurrect it.
        stampStaleFingerprint();
        taggerWorker.onTick();
        assertEquals(0, stub().callCount(), "the attempt cap holds across generation bumps");
        assertEquals(0, readPost(post.id).sweptGeneration);
    }

    @Test
    void perSweepBatchCapLeavesRemainderForNextTick() throws Exception {
        // Seeded while the bean is still at the test-profile 0, so no
        // background tick can touch them before the batch is armed.
        SeededPost p1 = seedSweptPost("batch-1", FETCHED_BASE, false, List.of("ai"), List.of());
        SeededPost p2 = seedSweptPost("batch-2", FETCHED_BASE.plusSeconds(60), false,
            List.of("ai"), List.of());
        SeededPost p3 = seedSweptPost("batch-3", FETCHED_BASE.plusSeconds(120), false,
            List.of("ai"), List.of());
        stub().setNextResponses("{\"tags\":[]}", "{\"tags\":[]}");

        setSweepBatchSize(2);
        taggerWorker.onTick();
        // Deny a background tick the remainder before asserting.
        setSweepBatchSize(0);

        assertEquals(2, stub().callCount(), "the per-sweep batch cap bounds one tick's LLM spend");
        assertEquals(2, readPost(p1.id).sweptGeneration);
        assertEquals(2, readPost(p2.id).sweptGeneration);
        assertEquals(0, readPost(p3.id).sweptGeneration,
            "the row past the batch cap waits for a later sweep");
        squelchAllRows();
    }

    // ---------- eligibility ----------

    @Test
    void fallbackRowsAreNeverSwept() throws Exception {
        SeededPost post = seedSweptPost("fallback", FETCHED_BASE, true,
            List.of("ai", "java"), List.of("ai", "java"));

        setSweepBatchSize(4);
        taggerWorker.onTick();

        assertEquals(0, stub().callCount(), "tagger_fallback=TRUE rows are never swept");
        PostState state = readPost(post.id);
        assertTrue(state.fallback);
        assertEquals(Set.of("ai", "java"), state.tags, "the bootstrap tags stay untouched");
        assertEquals(0, state.sweptGeneration);
        assertEquals(0, state.sweepAttempts);
    }

    @Test
    void searchTagsEmptyDonePostIsBackfilledByThePromptGenerationBump() throws Exception {
        // The prompt edit is a fingerprint leg: the generation bump makes
        // the done search_tags='{}' post eligible (V87's bounded backfill).
        SeededPost post = seedSweptPost("backfill", FETCHED_BASE, false,
            List.of("ai"), List.of("ai", "java"), List.of());
        stub().setNextResponse(
            "{\"tags\":[\"tagger-fixture-news\"],\"search_tags\":[\"czechia\"]}");

        setSweepBatchSize(4);
        taggerWorker.onTick();

        assertEquals(1, stub().callCount(),
            "the OR-arm makes the search_tags='{}' post eligible despite non-empty tags");
        PostState state = readPost(post.id);
        assertEquals(Set.of("tagger-fixture-news"), state.tags,
            "the re-drive re-derives the categories through the normal chain");
        assertEquals(2, state.sweptGeneration, "swept at the bumped generation");
        assertEquals(1, state.sweepAttempts);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT search_tags FROM post WHERE id = ?")) {
            ps.setObject(1, post.id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(List.of("czechia"),
                    Arrays.asList((String[]) rs.getArray("search_tags").getArray()),
                    "the re-drive writes the free tags through the same cursor UPDATE");
            }
        }
    }

    // ---------- ordering ----------

    @Test
    void firstPassPickupIsProcessedBeforeSweepWork() throws Exception {
        SeededPost live = seedPickupReadyPost("live", List.of("ai"));
        SeededPost swept = seedSweptPost("swept", FETCHED_BASE, false, List.of("ai"), List.of());
        // The stub's FIFO order is the observation: the first queued reply
        // answers the FIRST LLM call, so whichever row carries
        // "tagger-fixture-news" was processed first. The live reply carries
        // a search_tags field so the freshly-tagged row does not itself
        // become sweep-eligible mid-tick under the OR-arm.
        stub().setNextResponses(
            "{\"tags\":[\"tagger-fixture-news\"],\"search_tags\":[\"live-done\"]}",
            "{\"tags\":[\"tagger-fixture-security\"]}");

        setSweepBatchSize(4);
        taggerWorker.onTick();

        assertEquals(2, stub().callCount());
        assertEquals(Set.of("tagger-fixture-news"), readPost(live.id).tags,
            "first-pass pickup must be processed before sweep work");
        assertEquals(Set.of("tagger-fixture-security"), readPost(swept.id).tags);
        assertEquals(2, readPost(swept.id).sweptGeneration);
    }

    // ---------- failure path ----------

    @Test
    void sweepFailureTakesTheNormalBootstrapFallbackPath() throws Exception {
        SeededPost post = seedSweptPost("failure", FETCHED_BASE, false,
            List.of("ai", "java"), List.of());
        stub().failAll();

        setSweepBatchSize(4);
        taggerWorker.onTick();

        assertEquals(2, stub().callCount(),
            "the unreachable path retries once before the bootstrap fallback");
        PostState state = readPost(post.id);
        assertEquals(Set.of("ai", "java"), state.tags,
            "failures take the normal failure path: bootstrap tags, fallback marked");
        assertTrue(state.fallback);
        assertEquals(2, state.sweptGeneration);
        assertEquals(1, state.sweepAttempts);
    }

    // ---------- fingerprint ----------

    @Test
    void sweepFingerprintChangesOnModelOrVocabularyOrPromptChange() {
        Set<String> vocab = Set.of("news", "security");
        String base = TaggerWorker.sweepFingerprint(vocab, "llama3.1:8b", "primary", "fallback");
        assertEquals(base, TaggerWorker.sweepFingerprint(vocab, "llama3.1:8b", "primary", "fallback"),
            "the fingerprint is stable for unchanged inputs");
        assertNotEquals(base, TaggerWorker.sweepFingerprint(vocab, "llama3.2:1b", "primary", "fallback"),
            "a model change bumps the generation via the fingerprint");
        assertNotEquals(base,
            TaggerWorker.sweepFingerprint(Set.of("news", "quantum", "security"), "llama3.1:8b",
                "primary", "fallback"),
            "a vocabulary change bumps the generation via the fingerprint");
        assertNotEquals(base, TaggerWorker.sweepFingerprint(vocab, "llama3.1:8b", "primary-v2", "fallback"),
            "a primary prompt change bumps the generation via the fingerprint");
        assertNotEquals(base, TaggerWorker.sweepFingerprint(vocab, "llama3.1:8b", "primary", "fallback-v2"),
            "a fallback prompt change bumps the generation via the fingerprint");
        assertNotEquals(
            TaggerWorker.sweepFingerprint(vocab, "llama3.1:8b", "alpha", "beta"),
            TaggerWorker.sweepFingerprint(vocab, "llama3.1:8b", "beta", "alpha"),
            "the template legs are positional slots, not load-order dependent");
    }

    // ---------- helpers ----------

    /** A post whose first pass already ended: {@code tagger_done=TRUE},
     *  sweep bookkeeping at the never-swept baseline (generation 0, 0
     *  attempts). */
    private SeededPost seedSweptPost(String slug, Instant fetchedAt, boolean fallback,
                                     List<String> bootstrapTags, List<String> tags)
            throws Exception {
        return seedSweptPost(slug, fetchedAt, fallback, bootstrapTags, tags, List.of());
    }

    /** Same, with an explicit {@code search_tags} seed (the V87 backfill
     *  leg needs a done post whose free-tag array is still empty). */
    private SeededPost seedSweptPost(String slug, Instant fetchedAt, boolean fallback,
                                     List<String> bootstrapTags, List<String> tags,
                                     List<String> searchTags)
            throws Exception {
        UUID sourceId = seedRssSource(
            "https://sweep-it.example.test/" + slug + "/feed.xml",
            "Sweep IT " + slug, bootstrapTags);
        String uid = "sweep-it-" + slug + "-uid";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body,"
                     + "  fetched_at, status,"
                     + "  stage1_done, stage2_done, tagger_done, embedding_done,"
                     + "  stage1_flagged, stage2_failed, tagger_fallback, tags, search_tags"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, ?, ?, ?, 'RAW',"
                     + "  TRUE, FALSE, TRUE, FALSE, FALSE, FALSE, ?, ?, ?"
                     + ") RETURNING id")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, "sweep-it-" + slug + "-upstream");
            ps.setString(4, "Sweep IT post " + slug);
            ps.setString(5, "Sweep IT body " + slug);
            ps.setTimestamp(6, Timestamp.from(fetchedAt));
            ps.setBoolean(7, fallback);
            ps.setArray(8, conn.createArrayOf("text", tags.toArray(new String[0])));
            ps.setArray(9, conn.createArrayOf("text", searchTags.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new SeededPost((UUID) rs.getObject(1), uid);
            }
        }
    }

    /** A first-pass row: every pickup predicate satisfied,
     *  {@code tagger_done=FALSE} (mirrors TaggerWorkerIT's seed). */
    private SeededPost seedPickupReadyPost(String slug, List<String> bootstrapTags)
            throws Exception {
        UUID sourceId = seedRssSource(
            "https://sweep-it.example.test/" + slug + "/feed.xml",
            "Sweep IT " + slug, bootstrapTags);
        String uid = "sweep-it-" + slug + "-uid";

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
                     + ") RETURNING id")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, "sweep-it-" + slug + "-upstream");
            ps.setString(4, "Sweep IT post " + slug);
            ps.setString(5, "Sweep IT body " + slug);
            ps.setTimestamp(6, Timestamp.from(FETCHED_BASE));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new SeededPost((UUID) rs.getObject(1), uid);
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

    /** Marks every existing row swept at the max generation, so leftovers
     *  of other IT classes (or of earlier tests in this class) can never be
     *  sweep candidates. */
    private void squelchAllRows() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE post SET tagger_swept_generation = 2147483647")) {
            ps.executeUpdate();
        }
    }

    /**
     * Hides every standing live-pickup row (the enumeratePending predicate
     * minus the scan window) behind {@code tagger_done=TRUE} and returns
     * their ids so {@link #restoreNeutralizedLiveRows()} can undo it. The
     * flip is what lets a test observe onTick()'s live-then-sweep order
     * without foreign rows eating the queued stub responses first; the
     * restore leaves the shared boot byte-identical for later classes.
     */
    private List<UUID> neutralizeLiveBacklog() throws Exception {
        List<UUID> ids = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM post WHERE status = 'RAW' AND stage1_done = TRUE "
                         + "AND (stage1_flagged = FALSE OR stage2_done = TRUE) "
                         + "AND tagger_done = FALSE");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add((UUID) rs.getObject(1));
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                     "UPDATE post SET tagger_done = TRUE WHERE id = ?")) {
                for (UUID id : ids) {
                    ps.setObject(1, id);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        return ids;
    }

    private void restoreNeutralizedLiveRows() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE post SET tagger_done = FALSE WHERE id = ?")) {
            for (UUID id : neutralizedLiveRows) {
                ps.setObject(1, id);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        neutralizedLiveRows = List.of();
    }

    /** Resets the singleton marker to generation 1 with a fingerprint that
     *  never matches the live one, so each test's first sweep bumps to
     *  generation 2 (the fingerprint-mismatch bump path). */
    private void resetSweepMarker() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO tagger_sweep_state (id, generation, input_fingerprint) "
                     + "VALUES (1, 1, 'sweep-it-stale') "
                     + "ON CONFLICT (id) DO UPDATE SET generation = 1,"
                     + " input_fingerprint = 'sweep-it-stale'")) {
            ps.executeUpdate();
        }
    }

    private void stampStaleFingerprint() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE tagger_sweep_state SET input_fingerprint = 'sweep-it-stale-again'")) {
            ps.executeUpdate();
        }
    }

    private void setSweepBookkeeping(UUID postId, int sweptGeneration, int attempts)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE post SET tagger_swept_generation = ?, tagger_sweep_attempts = ? "
                     + "WHERE id = ?")) {
            ps.setInt(1, sweptGeneration);
            ps.setInt(2, attempts);
            ps.setObject(3, postId);
            ps.executeUpdate();
        }
    }

    private PostState readPost(UUID postId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT tagger_done, tagger_fallback, tags,"
                     + " tagger_swept_generation, tagger_sweep_attempts "
                     + "FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "post row must exist");
                Set<String> tags = new HashSet<>(Arrays.asList(
                    (String[]) rs.getArray("tags").getArray()));
                return new PostState(
                    rs.getBoolean("tagger_done"),
                    rs.getBoolean("tagger_fallback"),
                    tags,
                    rs.getInt("tagger_swept_generation"),
                    rs.getInt("tagger_sweep_attempts"));
            }
        }
    }

    private record SeededPost(UUID id, String uid) {
    }

    private record PostState(boolean taggerDone, boolean fallback, Set<String> tags,
                             int sweptGeneration, int sweepAttempts) {
    }
}
