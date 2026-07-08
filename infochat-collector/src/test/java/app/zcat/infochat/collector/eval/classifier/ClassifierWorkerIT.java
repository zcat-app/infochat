package app.zcat.infochat.collector.eval.classifier;

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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test for {@link ClassifierWorker}: a RAW post
 * that has passed the Tagger ({@code tagger_done=TRUE,
 * classifier_done=FALSE}) is picked up by
 * {@link ClassifierWorker#enumeratePending}, processed against the shared
 * {@link StubLlmProvider}, and its {@code classification} +
 * {@code classifier_done} cursor land in {@code post}. Exercises the real
 * pickup SQL + persistence against Postgres, mirroring
 * {@code EntityExtractorWorkerIT}, and proves the schema-violating reply
 * falls back to {@code {unknown}}.
 */
@QuarkusTest
class ClassifierWorkerIT {

    // A FIXED instant the scan-window pickup reads via the injected Clock
    // (pinned in reset()), so a fixed in-window fetched_at cannot age out
    // below the floor (engineering-rules §9).
    private static final Instant PINNED_NOW = Instant.parse("2026-06-20T12:00:00Z");
    // In-window fetched_at: above the PINNED_NOW − (retention + slack) floor
    // and inside the June 2026 post partition.
    private static final Instant FETCHED_AT = Instant.parse("2026-06-19T10:00:00Z");
    private static final String UID_PREFIX = "classifier-it/";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    ClassifierWorker classifierWorker;

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
    void endToEndClassification() throws Exception {
        stub().setNextResponse("{\"classification\":[\"factual\",\"technical\"]}");
        UUID postId = seedPickupReadyPost("e2e", FETCHED_AT);
        // A pickup-ready post one day BELOW the scan-window floor must be
        // excluded — the deterministic boundary assertion against the
        // injected instant.
        Instant floor = PINNED_NOW.minus(
            Duration.ofDays(postRetentionDays + PartitionScan.PARTITION_SCAN_SLACK.toDays()));
        UUID belowFloorId = seedPickupReadyPost("below-floor", floor.minus(Duration.ofDays(1)));

        // Exercise the real pickup filter: the in-window post must be
        // enumerated (status='RAW' AND tagger_done=TRUE AND
        // classifier_done=FALSE) and the below-floor post excluded.
        List<ClassifierWorker.PostRow> pending = classifierWorker.enumeratePending(64);
        assertFalse(pending.stream().anyMatch(r -> r.id().equals(belowFloorId)),
            "post fetched below PINNED_NOW − (retention + slack) must NOT be picked up");
        ClassifierWorker.PostRow row = pending.stream()
            .filter(r -> r.id().equals(postId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("seeded in-window post must be picked up by enumeratePending"));

        classifierWorker.processOne(row);

        assertClassification(postId, List.of("factual", "technical"), true);
    }

    @Test
    void schemaViolatingReply_fallsBackToUnknown() throws Exception {
        // Both attempts unparseable → classification={unknown},
        // classifier_done=TRUE (the post still reaches READY).
        stub().setNextResponses("not json", "still not json");
        UUID postId = seedPickupReadyPost("schema", FETCHED_AT);

        ClassifierWorker.PostRow row = classifierWorker.enumeratePending(64).stream()
            .filter(r -> r.id().equals(postId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("seeded post must be picked up"));

        classifierWorker.processOne(row);

        assertClassification(postId, List.of("unknown"), true);
    }

    @Test
    void llmUnreachableReply_fallsBackToUnknownAndNotifies() throws Exception {
        // Every generate call throws → initial attempt + one retry both fail →
        // release as {unknown} + throttled admin notification.
        stub().failAll();
        UUID postId = seedPickupReadyPost("unreachable", FETCHED_AT);

        ClassifierWorker.PostRow row = classifierWorker.enumeratePending(64).stream()
            .filter(r -> r.id().equals(postId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("seeded post must be picked up"));

        classifierWorker.processOne(row);

        assertClassification(postId, List.of("unknown"), true);
        assertEquals(2, stub().callCount(), "exactly one retry after the initial unreachable failure");
        assertTrue(throttledAdminNotifier
                .getState(ClassifierWorker.ERROR_CLASS_CLASSIFICATION_FAILURE).isPresent(),
            "throttled admin notification must fire on release-as-unknown");
    }

    // ---------- helpers ----------

    private UUID seedPickupReadyPost(String slug, Instant fetchedAt) throws Exception {
        UUID sourceId = seedSource(slug);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body,"
                     + "  fetched_at, status,"
                     + "  stage1_done, stage1_flagged, stage2_done, stage2_failed,"
                     + "  tagger_done, tagger_fallback, entity_done, embedding_done, tags, re_eval_attempts"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, 'Classifier IT title', 'Classifier IT body',"
                     + "  ?, 'RAW',"
                     + "  TRUE, FALSE, FALSE, FALSE,"
                     + "  TRUE, FALSE, FALSE, FALSE, '{}', 0"
                     + ") RETURNING id")) {
            ps.setString(1, UID_PREFIX + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "classifier-it-upstream-" + slug);
            ps.setTimestamp(4, Timestamp.from(fetchedAt));
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
            ps.setString(1, "https://classifier-it.example/" + slug);
            ps.setString(2, "Classifier IT source " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void assertClassification(UUID postId, List<String> expected, boolean done) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT classification, classifier_done FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                String[] actual = (String[]) rs.getArray("classification").getArray();
                assertEquals(expected, Arrays.asList(actual), "post.classification");
                assertEquals(done, rs.getBoolean("classifier_done"), "classifier_done");
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
