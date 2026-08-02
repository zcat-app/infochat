package app.zcat.infochat.collector.eval.embedding;

import app.zcat.infochat.collector.eval.PartitionScan;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.llm.EmbeddingResult;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.annotation.Priority;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.UserTransaction;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test for {@link EmbeddingWorker} covering
 * the five scenarios enumerated in M1-034b acceptance item
 * "EmbeddingWorkerIT.java is a @QuarkusTest IT".
 *
 * <h2>Stub provider</h2>
 *
 * <p>{@link StubEmbeddingProvider} is the test-scoped
 * {@code @Alternative @Priority(Integer.MAX_VALUE) @ApplicationScoped}
 * bean Quarkus ArC selects over {@code OpenAiCompatibleEmbeddingProvider}
 * for the test profile. Nested static class because this ticket's
 * {@code files_scope} permits only one new file for the EmbeddingWorker
 * IT; ArC discovers nested @ApplicationScoped beans the same way it
 * discovers top-level ones.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmbeddingWorkerIT {

    /**
     * The active embedding dimension under the test profile. V11's
     * seed row in {@code embedding_metadata} commits to 768 for the
     * laptop / vps default model, and the test profile inherits the
     * base {@code infochat.embeddings.dimension=768}.
     */
    private static final int EXPECTED_DIMENSION = 768;

    /**
     * The expected {@code post_embedding.embedding_model} value —
     * matches V11's seed and the laptop default
     * {@code infochat.embeddings.model=nomic-embed-text}.
     */
    private static final String EXPECTED_MODEL = "nomic-embed-text";

    /**
     * fetched_at must fall inside V11's bootstrap partition
     * (post_embedding_202605 covers May 2026).
     */
    private static final Instant FETCHED_AT = Instant.parse("2026-05-16T10:00:00Z");

    /**
     * A FIXED instant the scan-window pickup reads via the injected Clock
     * (pinned in {@link #reset()}). The @Order(5) boundary seeds are computed
     * relative to this constant and the configured scan window, so the
     * pickup-floor boundary is exercised deterministically regardless of the
     * wall-clock run date — replacing the {@code Instant.now()}-relative
     * fixture that ages out below the floor (the M1-398 time-bomb). (M1-448)
     */
    private static final Instant PINNED_NOW = Instant.parse("2026-06-20T12:00:00Z");

    // M1-715 gate fixtures: base infochat.summarizer.threshold-chars is
    // 1200; LONG_BODY clears it, the short fixture body stays well under.
    private static final String LONG_BODY =
        ("The riverside district was placed under mandatory evacuation as the "
            + "crest reached the old town and volunteers sandbagged the "
            + "brewery quarter through the night. ").repeat(10);
    private static final String ABSTRACT =
        "Oberloiben evacuated 1,200 residents as the Danube crested at 5.8 metres.";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    EmbeddingWorker embeddingWorker;

    @Inject
    EmbeddingProvider embeddingProvider;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @Inject
    UserTransaction userTransaction;

    // The post retention horizon driving the scan window
    // (retention + PARTITION_SCAN_SLACK); read so the @Order(5) boundary
    // straddle is computed exactly as the production floor is.
    @ConfigProperty(name = "infochat.partitions.retention-days.post")
    int postRetentionDays;

    private StubEmbeddingProvider stub() {
        return (StubEmbeddingProvider) embeddingProvider;
    }

    @BeforeEach
    void reset() throws Exception {
        // Pin the injected Clock the scan-window pickup reads so the @Order(5)
        // boundary is deterministic; the QuarkusMock.installMockForType seam is
        // the same one ThrottledAdminNotifier's Clock producer documents
        // (M1-444). Inert for the processBatch scenarios, which read no clock.
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
        stub().reset();
        clearItPosts();
    }

    // ---------- 1. happy path ----------

    @Test
    @Order(1)
    void happyPathInsertsRowsAndAdvancesEmbeddingDone() throws Exception {
        SeededPost a = seedPickupReadyPost("embed-it-happy-a");
        SeededPost b = seedPickupReadyPost("embed-it-happy-b");

        stub().queueSuccess(List.of(zeroVector(EXPECTED_DIMENSION), oneVector(EXPECTED_DIMENSION)));

        embeddingWorker.processBatch(List.of(rowFor(a), rowFor(b)));

        assertEmbeddingDone(a.id, true);
        assertEmbeddingDone(b.id, true);
        assertEquals(1, postEmbeddingCount(a.id),
            "exactly one post_embedding row must exist for post A on success");
        assertEquals(1, postEmbeddingCount(b.id),
            "exactly one post_embedding row must exist for post B on success");
        assertEquals(EXPECTED_MODEL, readEmbeddingModel(a.id),
            "embedding_model must equal the active embedding_metadata identifier");
        assertEquals(EXPECTED_MODEL, readEmbeddingModel(b.id));
    }

    // ---------- 2. batch failure (exception, exception) → no-vector release ----------

    @Test
    @Order(2)
    void batchFailureTwiceReleasesAllPostsWithoutVectors() throws Exception {
        SeededPost a = seedPickupReadyPost("embed-it-fail-a");
        SeededPost b = seedPickupReadyPost("embed-it-fail-b");

        // First call AND retry both throw — second-failure path
        // triggers the no-vector release.
        stub().queueException();
        stub().queueException();

        embeddingWorker.processBatch(List.of(rowFor(a), rowFor(b)));

        assertEquals(2, stub().callCount(),
            "the one-failure-fails-batch retry must invoke embed twice before release");
        assertEmbeddingDone(a.id, true);
        assertEmbeddingDone(b.id, true);
        assertEquals(0, postEmbeddingCount(a.id),
            "no post_embedding row on the no-vector release path");
        assertEquals(0, postEmbeddingCount(b.id));
    }

    // ---------- 3. wrong-shape (N=1 result for N=2 inputs, twice) → no-vector release ----------

    @Test
    @Order(3)
    void wrongShapeTwiceReleasesAllPostsWithoutVectors() throws Exception {
        SeededPost a = seedPickupReadyPost("embed-it-shape-a");
        SeededPost b = seedPickupReadyPost("embed-it-shape-b");

        // The provider returns ONE result for a TWO-input call,
        // twice in a row. The worker cannot map results back to
        // posts so the whole batch retries; the retry also returns
        // wrong shape; the no-vector release fires.
        stub().queueSuccess(List.of(zeroVector(EXPECTED_DIMENSION)));
        stub().queueSuccess(List.of(zeroVector(EXPECTED_DIMENSION)));

        embeddingWorker.processBatch(List.of(rowFor(a), rowFor(b)));

        assertEquals(2, stub().callCount(),
            "wrong-shape failure must retry with the SAME batch");
        assertEmbeddingDone(a.id, true);
        assertEmbeddingDone(b.id, true);
        assertEquals(0, postEmbeddingCount(a.id));
        assertEquals(0, postEmbeddingCount(b.id));
    }

    // ---------- 4. dimensionality mismatch → coalesced operator alert + skip, no throw ----------

    @Test
    @Order(4)
    void dimensionMismatchAlertsOperatorAndSkipsWithoutThrowing() throws Exception {
        // Clear any prior row for the mismatch key so the isPresent()
        // assertion below reflects THIS tick's alert, not a stale row.
        clearNotification(EmbeddingWorker.ERROR_CLASS_EMBEDDING_DIMENSION_MISMATCH);
        SeededPost a = seedPickupReadyPost("embed-it-dim-a");
        SeededPost b = seedPickupReadyPost("embed-it-dim-b");

        // Provider returns the correct COUNT (2 of 2) but each vector
        // has the WRONG dimension (384 instead of 768). The worker
        // treats this as an operator-action-required metadata-invariant
        // violation, not a batch-failure-retry case: it fires one
        // coalesced operator alert and skips the batch WITHOUT throwing.
        stub().queueSuccess(List.of(zeroVector(384), oneVector(384)));

        assertDoesNotThrow(
            () -> embeddingWorker.processBatch(List.of(rowFor(a), rowFor(b))),
            "dim mismatch must skip the batch via a coalesced alert, not throw");

        assertEquals(1, stub().callCount(),
            "dim mismatch must NOT retry (it is not a batch-failure case)");
        // The skip returns before the narrow transaction starts: no DB
        // writes executed, so embedding_done stays FALSE and the posts
        // stay in-flight for the operator's re-embed procedure.
        assertEmbeddingDone(a.id, false);
        assertEmbeddingDone(b.id, false);
        assertEquals(0, postEmbeddingCount(a.id));
        assertEquals(0, postEmbeddingCount(b.id));
        // The coalesced operator alert fired on the canonical error class.
        assertTrue(
            throttledAdminNotifier.getState(
                EmbeddingWorker.ERROR_CLASS_EMBEDDING_DIMENSION_MISMATCH).isPresent(),
            "a throttled admin alert must fire on the dimension-mismatch skip");
    }

    // ---------- 5. pre-promotion boundary — already-embedded post NOT picked up ----------

    @Test
    @Order(5)
    void postAlreadyEmbeddedIsNotPickedUpByEnumeratePending() throws Exception {
        // A post that has cleared the Embedding boundary (status='RAW'
        // AND tagger_done=true AND embedding_done=true) is downstream
        // of EmbeddingWorker's responsibility — the ReadyPromoter is
        // the next stage. EmbeddingWorker.enumeratePending must not
        // return it.
        //
        // enumeratePending applies a rolling floor: fetched_at >=
        // scanWindowFloor(clock.instant()) (= PINNED_NOW − (retention + 2d
        // slack)). The Clock is pinned (reset()), so the boundary is fixed and
        // the seeds straddle it deterministically — no longer a wall-clock-
        // relative fixture that ages out below the floor (the M1-398 time-bomb,
        // now killed by the injected Clock). @Order(5) writes no post_embedding
        // row, so it is NOT bound by V11's May-2026 post_embedding partition. (M1-448)
        Instant floor = PINNED_NOW.minus(
            Duration.ofDays(postRetentionDays + PartitionScan.PARTITION_SCAN_SLACK.toDays()));
        Instant inWindow = floor.plus(Duration.ofDays(1));
        Instant belowFloor = floor.minus(Duration.ofDays(1));

        SeededPost already = seedAlreadyEmbeddedPost("embed-it-already", inWindow);
        // Sanity: a fresh in-window pickup-ready post IS returned, so the
        // negative assertions are meaningful (not a coincidence of an
        // empty pending list).
        SeededPost fresh = seedPickupReadyPost("embed-it-fresh", inWindow);
        // A pickup-ready post one day BELOW the floor must be excluded by the
        // scan window — the deterministic boundary assertion against the
        // injected instant.
        SeededPost belowFloorPost = seedPickupReadyPost("embed-it-below", belowFloor);

        // Enumerate the FULL in-window pending set rather than a fixed LIMIT 10
        // top slice: other collector ITs (e.g. EmbeddingWorkerPickupFloorIT)
        // leave their own in-window pickup-ready posts uncleaned in the shared
        // DevServices DB, which could order-dependently crowd `fresh` out of a
        // small limit. Against the whole set, membership reflects the WHERE
        // filter alone. (M1-398)
        List<EmbeddingWorker.PostRow> pending = embeddingWorker.enumeratePending(Integer.MAX_VALUE);

        boolean foundAlready = pending.stream().anyMatch(r -> r.id().equals(already.id));
        assertFalse(foundAlready,
            "post with embedding_done=true must NOT appear in EmbeddingWorker pickup");
        boolean foundFresh = pending.stream().anyMatch(r -> r.id().equals(fresh.id));
        assertTrue(foundFresh,
            "fresh tagger_done=true / embedding_done=false post MUST appear in pickup");
        boolean foundBelowFloor = pending.stream().anyMatch(r -> r.id().equals(belowFloorPost.id));
        assertFalse(foundBelowFloor,
            "post fetched below PINNED_NOW − (retention + slack) must NOT appear in pickup — "
                + "the scan-window floor reads the injected Clock, so a fixed clock makes the "
                + "boundary deterministic");
        assertEquals(0, stub().callCount(),
            "enumeratePending must not invoke the provider");
    }

    // ---------- 6. transaction does not span the embed HTTP call ----------

    @Test
    @Order(6)
    void transactionDoesNotSpanHttpCall() throws Exception {
        SeededPost a = seedPickupReadyPost("embed-it-txn-a");

        AtomicInteger jtaStatusDuringEmbed = new AtomicInteger(-1);
        stub().setEmbedCallback(() -> {
            try {
                jtaStatusDuringEmbed.set(userTransaction.getStatus());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        stub().queueSuccess(List.of(zeroVector(EXPECTED_DIMENSION)));

        embeddingWorker.processBatch(List.of(rowFor(a)));

        assertEquals(Status.STATUS_NO_TRANSACTION, jtaStatusDuringEmbed.get(),
            "no JTA transaction should be active during the embedding HTTP call");
        assertEmbeddingDone(a.id, true);
        assertEquals(1, postEmbeddingCount(a.id));
    }

    // ---------- 7. interrupt during permit acquire → restore flag, skip embed ----------

    @Test
    @Order(7)
    void processBatchInterruptedDuringAcquireRestoresFlagAndSkipsEmbed() {
        EmbeddingWorker.PostRow row = new EmbeddingWorker.PostRow(
            UUID.randomUUID(), FETCHED_AT, "interrupt-probe", "body", null);

        // An already-interrupted caller makes Semaphore.acquire() throw on
        // entry; processBatch must restore the interrupt flag (acquire clears
        // it when it throws) and return WITHOUT invoking the provider — the
        // C-ACQUIRE-INT graceful-shutdown contract. No post is seeded and no
        // result is queued: a correct short-circuit never reaches the DB or
        // the provider.
        Thread.currentThread().interrupt();
        embeddingWorker.processBatch(List.of(row));
        // Read-and-clear in one step so the flag cannot leak onto the shared
        // JUnit worker thread for sibling tests.
        boolean interruptRestored = Thread.interrupted();

        assertTrue(interruptRestored,
            "the interrupt flag must be restored after acquire() is interrupted");
        assertEquals(0, stub().callCount(),
            "no embed call may be issued when the permit acquire is interrupted");
    }

    // ---------- 8. summary_done gate (M1-715) ----------

    @Test
    @Order(8)
    void summaryGateHoldsOverThresholdPostUntilSummaryDone() throws Exception {
        // Pin the injected Clock so FETCHED_AT sits inside the pickup
        // scan window (same seam as the ReadyPromoterIT gate scenarios).
        QuarkusMock.installMockForType(
            Clock.fixed(Instant.parse("2026-05-17T00:00:00Z"), ZoneOffset.UTC), Clock.class);
        SeededPost longPost = seedPostWithSummaryDone("gate-long", LONG_BODY, false);
        SeededPost shortPost = seedPostWithSummaryDone("gate-short", "short body", false);

        List<EmbeddingWorker.PostRow> pendingBefore = embeddingWorker.enumeratePending(64);
        assertFalse(pendingBefore.stream().anyMatch(r -> r.id().equals(longPost.id())),
            "over-threshold post with summary_done=FALSE must NOT be picked for embedding");
        assertTrue(pendingBefore.stream().anyMatch(r -> r.id().equals(shortPost.id())),
            "under-threshold post escapes the summary gate with summary_done=FALSE");

        // The BodySummaryWorker writes its abstract and advances the
        // cursor → the gate opens, and the embed input is the abstract
        // (title + "\n\n" + body_summary), not the first-800 fallback.
        setSummary(longPost.id(), ABSTRACT);
        List<EmbeddingWorker.PostRow> pendingAfter = embeddingWorker.enumeratePending(64);
        EmbeddingWorker.PostRow row = pendingAfter.stream()
            .filter(r -> r.id().equals(longPost.id()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("summary_done=TRUE post must be picked"));
        assertEquals("Embed IT title gate-long\n\n" + ABSTRACT,
            EmbeddingWorker.buildInputText(row),
            "the embed input is the abstract once body_summary is populated");

        stub().queueSuccess(List.of(zeroVector(EXPECTED_DIMENSION)));
        embeddingWorker.processBatch(List.of(row));
        assertEmbeddingDone(longPost.id(), true);
    }

    // ---------- 9. translation_done gate + English-anchor projection (M1-749) ----------

    @Test
    @Order(9)
    void translationGateHoldsPostAndProjectionReadsEnglishAnchor() throws Exception {
        // Pin the injected Clock so FETCHED_AT sits inside the pickup
        // scan window (same seam as the @Order(8) summary-gate scenario).
        QuarkusMock.installMockForType(
            Clock.fixed(Instant.parse("2026-05-17T00:00:00Z"), ZoneOffset.UTC), Clock.class);
        // Three seeds differing ONLY in the M1-749 columns:
        //   gated-out — translation_done=FALSE: the IngestTranslationWorker
        //     has not run, so embedding must not see the post (without the
        //     gate a non-English post would be permanently embedded from
        //     non-English text — embedding_done never re-fires).
        //   anchored — translation_done=TRUE with title_en/body_en written:
        //     the pickup projection must read the English anchor through
        //     coalesce(title_en, title) / coalesce(body_en, body).
        //   fallback — translation_done=TRUE with NULL *_en (an
        //     English-source or translation-released post): the projection
        //     must fall back to the original text.
        UUID sourceId = seedRssSource("translation-gate");
        SeededPost gatedOut = seedPostWithTranslationState(
            "gate-closed", sourceId, false, null, null);
        SeededPost anchored = seedPostWithTranslationState(
            "gate-anchored", sourceId, true, "English anchor title", "English anchor body");
        SeededPost fallback = seedPostWithTranslationState(
            "gate-fallback", sourceId, true, null, null);

        List<EmbeddingWorker.PostRow> pending = embeddingWorker.enumeratePending(Integer.MAX_VALUE);

        assertFalse(pending.stream().anyMatch(r -> r.id().equals(gatedOut.id())),
            "translation_done=FALSE must hold the post out of embedding pickup (M1-749)");
        EmbeddingWorker.PostRow anchoredRow = pending.stream()
            .filter(r -> r.id().equals(anchored.id()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("translation_done=TRUE post must be picked"));
        assertEquals("English anchor title", anchoredRow.title(),
            "the projection reads coalesce(title_en, title)");
        assertEquals("English anchor body", anchoredRow.body(),
            "the projection reads coalesce(body_en, body)");
        EmbeddingWorker.PostRow fallbackRow = pending.stream()
            .filter(r -> r.id().equals(fallback.id()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("a released post must still be picked"));
        assertEquals("Embed IT title gate-fallback", fallbackRow.title(),
            "NULL title_en falls back to the original title");
        assertEquals("Embed IT body for slug gate-fallback", fallbackRow.body(),
            "NULL body_en falls back to the original body");
    }

    // ---------- helpers ----------

    private SeededPost seedPickupReadyPost(String slug) throws Exception {
        return seedPost(slug, /* taggerDone */ true, /* embeddingDone */ false, "RAW");
    }

    private SeededPost seedPickupReadyPost(String slug, Instant fetchedAt) throws Exception {
        return seedPost(slug, /* taggerDone */ true, /* embeddingDone */ false, "RAW", fetchedAt);
    }

    private SeededPost seedAlreadyEmbeddedPost(String slug, Instant fetchedAt) throws Exception {
        return seedPost(slug, /* taggerDone */ true, /* embeddingDone */ true, "RAW", fetchedAt);
    }

    private SeededPost seedPost(String slug, boolean taggerDone, boolean embeddingDone, String status)
            throws Exception {
        return seedPost(slug, taggerDone, embeddingDone, status, FETCHED_AT);
    }

    private SeededPost seedPost(String slug, boolean taggerDone, boolean embeddingDone, String status,
            Instant fetchedAt) throws Exception {
        UUID sourceId = seedRssSource(slug);
        String uid = "embed-it/" + slug;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body, "
                     + "  fetched_at, status, "
                     + "  stage1_done, stage2_done, tagger_done, embedding_done, "
                     + "  stage1_flagged, stage2_failed, tagger_fallback, tags, translation_done"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, "
                     + "  TRUE, FALSE, ?, ?, FALSE, FALSE, FALSE, '{}', TRUE"
                     + ") RETURNING id, fetched_at")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, "embed-it-upstream-" + slug);
            ps.setString(4, "Embed IT title " + slug);
            ps.setString(5, "Embed IT body for slug " + slug);
            ps.setTimestamp(6, Timestamp.from(fetchedAt));
            ps.setString(7, status);
            ps.setBoolean(8, taggerDone);
            ps.setBoolean(9, embeddingDone);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "INSERT INTO post must yield an id");
                UUID id = (UUID) rs.getObject(1);
                Instant storedFetchedAt = rs.getTimestamp(2).toInstant();
                return new SeededPost(id, uid, storedFetchedAt);
            }
        }
    }

    /**
     * M1-749 fixture: a pickup-ready post (RAW, tagger_done=TRUE,
     * embedding_done=FALSE, short body so the summary gate escapes) with
     * explicit {@code translation_done} and English-anchor fields.
     */
    private SeededPost seedPostWithTranslationState(String slug, UUID sourceId,
            boolean translationDone, @Nullable String titleEn, @Nullable String bodyEn)
            throws Exception {
        String uid = "embed-it/" + slug;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body, "
                     + "  fetched_at, status, "
                     + "  stage1_done, stage2_done, tagger_done, embedding_done, "
                     + "  stage1_flagged, stage2_failed, tagger_fallback, tags, summary_done,"
                     + "  entity_done, classifier_done, translation_done, title_en, body_en"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, "
                     + "  TRUE, FALSE, TRUE, FALSE, FALSE, FALSE, FALSE, '{}', TRUE,"
                     + "  TRUE, TRUE, ?, ?, ?"
                     + ") RETURNING id, fetched_at")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, "embed-it-upstream-" + slug);
            ps.setString(4, "Embed IT title " + slug);
            ps.setString(5, "Embed IT body for slug " + slug);
            ps.setTimestamp(6, Timestamp.from(FETCHED_AT));
            ps.setString(7, "RAW");
            ps.setBoolean(8, translationDone);
            ps.setString(9, titleEn);
            ps.setString(10, bodyEn);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "INSERT INTO post must yield an id");
                UUID id = (UUID) rs.getObject(1);
                Instant storedFetchedAt = rs.getTimestamp(2).toInstant();
                return new SeededPost(id, uid, storedFetchedAt);
            }
        }
    }

    private UUID seedRssSource(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('rss', ?, ?, 'news', '{ai}') "
                     + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            ps.setString(1, "https://embed-it.example.test/" + slug + "/feed.xml");
            ps.setString(2, "Embed IT source " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return (UUID) rs.getObject(1);
            }
        }
    }

    private SeededPost seedPostWithSummaryDone(String slug, String body, boolean summaryDone)
            throws Exception {
        UUID sourceId = seedRssSource(slug);
        String uid = "embed-it/" + slug;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body, "
                     + "  fetched_at, status, "
                     + "  stage1_done, stage2_done, tagger_done, embedding_done, "
                     + "  stage1_flagged, stage2_failed, tagger_fallback, tags, summary_done,"
                     + "  entity_done, classifier_done, translation_done"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, "
                     + "  TRUE, FALSE, TRUE, FALSE, FALSE, FALSE, FALSE, '{}', ?,"
                     + "  TRUE, TRUE, TRUE"
                     + ") RETURNING id, fetched_at")) {
            ps.setString(1, uid);
            ps.setObject(2, sourceId);
            ps.setString(3, "embed-it-upstream-" + slug);
            ps.setString(4, "Embed IT title " + slug);
            ps.setString(5, body);
            ps.setTimestamp(6, Timestamp.from(FETCHED_AT));
            ps.setString(7, "RAW");
            ps.setBoolean(8, summaryDone);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "INSERT INTO post must yield an id");
                UUID id = (UUID) rs.getObject(1);
                Instant storedFetchedAt = rs.getTimestamp(2).toInstant();
                return new SeededPost(id, uid, storedFetchedAt);
            }
        }
    }

    private void setSummary(UUID postId, String summary) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE post SET body_summary = ?, summary_done = TRUE WHERE id = ?")) {
            ps.setString(1, summary);
            ps.setObject(2, postId);
            ps.executeUpdate();
        }
    }

    private void clearItPosts() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM post_embedding WHERE post_id IN "
                    + "(SELECT id FROM post WHERE uid LIKE 'embed-it/%')")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM post WHERE uid LIKE 'embed-it/%'")) {
                ps.executeUpdate();
            }
        }
    }

    private void clearNotification(String notificationKey) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM admin_notification_state WHERE notification_key = ?")) {
            ps.setString(1, notificationKey);
            ps.executeUpdate();
        }
    }

    private EmbeddingWorker.PostRow rowFor(SeededPost post) {
        return new EmbeddingWorker.PostRow(
            post.id, post.fetchedAt,
            "Embed IT title " + post.uid,
            "Embed IT body for " + post.uid,
            null);
    }

    private void assertEmbeddingDone(UUID postId, boolean expected) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT embedding_done FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "post row must exist");
                assertEquals(expected, rs.getBoolean("embedding_done"), "embedding_done");
            }
        }
    }

    private int postEmbeddingCount(UUID postId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(*) FROM post_embedding WHERE post_id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private String readEmbeddingModel(UUID postId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT embedding_model FROM post_embedding WHERE post_id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "post_embedding row must exist");
                return rs.getString(1);
            }
        }
    }

    private static float[] zeroVector(int dimension) {
        return new float[dimension];
    }

    private static float[] oneVector(int dimension) {
        float[] v = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            v[i] = 1.0f / (i + 1);
        }
        return v;
    }

    private record SeededPost(UUID id, String uid, Instant fetchedAt) {
    }

    /**
     * Test-scoped {@link EmbeddingProvider} replacing the production
     * {@code OpenAiCompatibleEmbeddingProvider} for every
     * {@code @QuarkusTest} in this module that needs deterministic
     * embed results. FIFO queue of either canned vector lists or
     * pre-recorded exceptions; the IT's {@code @BeforeEach} must
     * call {@link #reset()} so per-test state is isolated.
     */
    @Alternative
    @Priority(Integer.MAX_VALUE)
    @ApplicationScoped
    public static class StubEmbeddingProvider implements EmbeddingProvider {

        private final Deque<Response> queue = new ArrayDeque<>();
        private int callCount = 0;
        private Runnable embedCallback;

        public void reset() {
            queue.clear();
            callCount = 0;
            embedCallback = null;
        }

        public void setEmbedCallback(@Nullable Runnable callback) {
            this.embedCallback = callback;
        }

        public int callCount() {
            return callCount;
        }

        public void queueSuccess(List<float[]> vectors) {
            queue.add(new SuccessResponse(vectors));
        }

        public void queueException() {
            queue.add(new ExceptionResponse());
        }

        @Override
        public List<EmbeddingResult> embed(List<String> texts) {
            callCount++;
            if (embedCallback != null) {
                embedCallback.run();
            }
            Response r = queue.pollFirst();
            if (r == null) {
                throw new RuntimeException(
                    "StubEmbeddingProvider: no queued response for call #" + callCount);
            }
            return r.materialize();
        }

        private sealed interface Response {
            List<EmbeddingResult> materialize();
        }

        private record SuccessResponse(List<float[]> vectors) implements Response {
            @Override
            public List<EmbeddingResult> materialize() {
                return vectors.stream().map(EmbeddingResult::new).toList();
            }
        }

        private record ExceptionResponse() implements Response {
            @Override
            public List<EmbeddingResult> materialize() {
                throw new RuntimeException("StubEmbeddingProvider: simulated embed failure");
            }
        }
    }
}
