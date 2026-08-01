package app.zcat.infochat.collector.eval.summary;

import app.zcat.infochat.collector.eval.PartitionScan;
import app.zcat.infochat.collector.eval.testing.StubLlmProvider;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.llm.LlmProvider;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test for {@link BodySummaryWorker} (M1-715): a
 * RAW post that has passed the Tagger with an over-threshold body
 * ({@code tagger_done=TRUE, summary_done=FALSE, length(body) >
 * infochat.summarizer.threshold-chars}) is picked up by
 * {@link BodySummaryWorker#enumeratePending}, processed against the
 * shared {@link StubLlmProvider}, and its {@code body_summary} +
 * {@code summary_done} cursor land in {@code post}. Exercises the real
 * pickup SQL + persistence against Postgres, mirroring
 * {@code ClassifierWorkerIT}, and proves the under-threshold exclusion
 * costs no LLM call and the double-failure path releases NULL + notifies.
 */
@QuarkusTest
class BodySummaryWorkerIT {

    // A FIXED instant the scan-window pickup reads via the injected Clock
    // (pinned in reset()), so a fixed in-window fetched_at cannot age out
    // below the floor (engineering-rules §9).
    private static final Instant PINNED_NOW = Instant.parse("2026-06-20T12:00:00Z");
    // In-window fetched_at: above the PINNED_NOW − (retention + slack) floor
    // and inside the June 2026 post partition.
    private static final Instant FETCHED_AT = Instant.parse("2026-06-19T10:00:00Z");
    private static final String UID_PREFIX = "body-summary-it/";

    // Base infochat.summarizer.threshold-chars is 1200; this fixture body
    // clears it, and the short fixture body stays well under it.
    private static final String LONG_BODY =
        ("The Danube crest reached the riverside district overnight and the "
            + "crisis staff ordered a mandatory evacuation while volunteers "
            + "sandbagged the brewery quarter. ").repeat(10);
    private static final String SHORT_BODY = "Short body, well under the threshold.";
    private static final String ABSTRACT =
        "Oberloiben evacuated 1,200 residents as the Danube crested at 5.8 metres.";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    BodySummaryWorker bodySummaryWorker;

    @Inject
    LlmProvider llmProvider;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    // The post retention horizon driving the scan window
    // (retention + PARTITION_SCAN_SLACK); read so the below-floor seed is
    // computed exactly as the production floor is.
    @ConfigProperty(name = "infochat.partitions.retention-days.post")
    int postRetentionDays;

    private StubLlmProvider stub() {
        return (StubLlmProvider) llmProvider;
    }

    @BeforeEach
    void reset() throws Exception {
        // Pin the injected Clock the scan-window pickup reads so the boundary
        // is deterministic (M1-444 seam).
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
        stub().reset();
        clearItData();
    }

    @Test
    void endToEndSummaryWritesAbstractAndAdvancesCursor() throws Exception {
        stub().setNextResponse("{\"summary\":\"" + ABSTRACT + "\"}");
        UUID postId = seedPost("e2e", LONG_BODY, FETCHED_AT);
        // A pickup-ready post one day BELOW the scan-window floor must be
        // excluded — the deterministic boundary assertion against the
        // injected instant.
        Instant floor = PINNED_NOW.minus(
            Duration.ofDays(postRetentionDays + PartitionScan.PARTITION_SCAN_SLACK.toDays()));
        UUID belowFloorId = seedPost("below-floor", LONG_BODY, floor.minus(Duration.ofDays(1)));

        // Exercise the real pickup filter: the in-window over-threshold
        // post must be enumerated (status='RAW' AND tagger_done=TRUE AND
        // summary_done=FALSE AND length(body) > threshold) and the
        // below-floor post excluded.
        List<BodySummaryWorker.PostRow> pending = bodySummaryWorker.enumeratePending(64);
        assertFalse(pending.stream().anyMatch(r -> r.id().equals(belowFloorId)),
            "post fetched below PINNED_NOW − (retention + slack) must NOT be picked up");
        BodySummaryWorker.PostRow row = pending.stream()
            .filter(r -> r.id().equals(postId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("seeded in-window post must be picked up by enumeratePending"));

        bodySummaryWorker.processOne(row);

        assertSummary(postId, ABSTRACT, true);
        assertEquals(1, stub().callCount(), "one post, one LLM call");
    }

    @Test
    void underThresholdPostIsNeverPickedAndCostsNoLlmCall() throws Exception {
        // The pickup's length(body) > threshold predicate is the only
        // thing standing between an under-threshold post and an LLM call:
        // the post must never be enumerated, and a full tick must spend
        // zero calls and leave the row untouched (summary_done stays
        // FALSE, body_summary stays NULL) — the EmbeddingWorker /
        // ReadyPromoter gates escape under-threshold posts on the same
        // predicate, so nothing downstream waits either.
        UUID postId = seedPost("under-threshold", SHORT_BODY, FETCHED_AT);

        List<BodySummaryWorker.PostRow> pending = bodySummaryWorker.enumeratePending(64);
        assertFalse(pending.stream().anyMatch(r -> r.id().equals(postId)),
            "an under-threshold post must NOT be picked up for summarization");

        bodySummaryWorker.onTick();

        assertEquals(0, stub().callCount(), "an under-threshold post must cost zero LLM calls");
        assertSummary(postId, null, false);
    }

    @Test
    void schemaViolatingTwice_releasesNullCursorAndNotifies() throws Exception {
        // Both attempts unparseable → summary_done=TRUE with body_summary
        // NULL (the post is then embedded from the first-800-chars
        // fallback, asserted on the EmbeddingWorker side) + notification.
        stub().setNextResponses("not json", "still not json");
        UUID postId = seedPost("schema", LONG_BODY, FETCHED_AT);

        BodySummaryWorker.PostRow row = bodySummaryWorker.enumeratePending(64).stream()
            .filter(r -> r.id().equals(postId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("seeded post must be picked up"));

        bodySummaryWorker.processOne(row);

        assertSummary(postId, null, true);
        assertEquals(2, stub().callCount(), "exactly one retry after the initial schema violation");
        assertTrue(throttledAdminNotifier
                .getState(BodySummaryWorker.ERROR_CLASS_SUMMARY_FAILURE).isPresent(),
            "throttled admin notification must fire on the NULL release");
    }

    @Test
    void llmUnreachable_releasesNullCursorAndNotifies() throws Exception {
        // Every generate call throws → initial attempt + one retry both
        // fail → release NULL + throttled admin notification.
        stub().failAll();
        UUID postId = seedPost("unreachable", LONG_BODY, FETCHED_AT);

        BodySummaryWorker.PostRow row = bodySummaryWorker.enumeratePending(64).stream()
            .filter(r -> r.id().equals(postId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("seeded post must be picked up"));

        bodySummaryWorker.processOne(row);

        assertSummary(postId, null, true);
        assertEquals(2, stub().callCount(), "exactly one retry after the initial unreachable failure");
        assertTrue(throttledAdminNotifier
                .getState(BodySummaryWorker.ERROR_CLASS_SUMMARY_FAILURE).isPresent(),
            "throttled admin notification must fire on the NULL release");
    }

    @Test
    void modelRefusal_releasesNullAndNotifiesRefusalClass() throws Exception {
        // The model answers with the structured refusal marker (an
        // in-wrapper action request per the prompt's refusal rule):
        // persist NULL + summary_done=TRUE, notify under the refusal
        // error class, and do NOT burn the retry (a PARSED refusal is a
        // final answer, not a failure).
        stub().setNextResponse("{\"summary\":\"[refused-action]\"}");
        UUID postId = seedPost("refusal", LONG_BODY, FETCHED_AT);

        BodySummaryWorker.PostRow row = bodySummaryWorker.enumeratePending(64).stream()
            .filter(r -> r.id().equals(postId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("seeded post must be picked up"));

        bodySummaryWorker.processOne(row);

        assertSummary(postId, null, true);
        assertEquals(1, stub().callCount(), "a refusal is final — no retry");
        assertTrue(throttledAdminNotifier
                .getState(BodySummaryWorker.ERROR_CLASS_SUMMARY_REFUSAL).isPresent(),
            "throttled admin notification must fire under the refusal error class");
    }

    // ---------- helpers ----------

    private UUID seedPost(String slug, String body, Instant fetchedAt) throws Exception {
        UUID sourceId = seedSource(slug);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body,"
                     + "  fetched_at, status,"
                     + "  stage1_done, stage1_flagged, stage2_done, stage2_failed,"
                     + "  tagger_done, tagger_fallback, entity_done, embedding_done, classifier_done, tags, re_eval_attempts"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, 'Body summary IT title', ?,"
                     + "  ?, 'RAW',"
                     + "  TRUE, FALSE, FALSE, FALSE,"
                     + "  TRUE, FALSE, TRUE, FALSE, TRUE, '{}', 0"
                     + ") RETURNING id")) {
            ps.setString(1, UID_PREFIX + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "body-summary-it-upstream-" + slug);
            ps.setString(4, body);
            ps.setTimestamp(5, Timestamp.from(fetchedAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private UUID seedSource(String slug) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO source (kind, identifier, display_name, category, bootstrap_tags) "
                     + "VALUES ('rss', ?, ?, 'news', '{ai}') "
                     + "ON CONFLICT (kind, identifier) DO UPDATE SET display_name = EXCLUDED.display_name "
                     + "RETURNING id")) {
            ps.setString(1, "https://body-summary-it.example/" + slug);
            ps.setString(2, "Body summary IT source " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void assertSummary(UUID postId, String expectedSummary, boolean expectedDone)
            throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT body_summary, summary_done FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "post row must exist");
                assertEquals(expectedSummary, rs.getString("body_summary"), "body_summary");
                assertEquals(expectedDone, rs.getBoolean("summary_done"), "summary_done");
            }
        }
    }

    private void clearItData() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM post WHERE uid LIKE ?")) {
            ps.setString(1, UID_PREFIX + "%");
            ps.executeUpdate();
        }
    }
}
