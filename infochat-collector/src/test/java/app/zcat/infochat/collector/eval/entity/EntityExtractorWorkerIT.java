package app.zcat.infochat.collector.eval.entity;

import app.zcat.infochat.collector.eval.PartitionScan;
import app.zcat.infochat.collector.eval.testing.StubLlmProvider;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test for {@link EntityExtractorWorker}: a RAW
 * post that has passed the Tagger ({@code tagger_done=TRUE,
 * entity_done=FALSE}) is picked up by {@link EntityExtractorWorker#enumeratePending},
 * processed against the shared {@link StubLlmProvider}, and the
 * extracted entities land in {@code post_entity} with the cursor
 * advanced. Exercises the real pickup SQL + transactional persistence
 * against Postgres, mirroring {@code TaggerWorkerIT}.
 */
@QuarkusTest
class EntityExtractorWorkerIT {

    // A FIXED instant the scan-window pickup reads via the injected Clock
    // (pinned in reset()). Replaces the former Instant.now() pin (M1-400): the
    // injected Clock now makes the pickup-floor boundary deterministic, so a
    // fixed in-window fetched_at can no longer age out below the floor.
    private static final Instant PINNED_NOW = Instant.parse("2026-06-20T12:00:00Z");
    // In-window fetched_at: above the PINNED_NOW − (retention + slack) floor and
    // inside the June 2026 post partition.
    private static final Instant FETCHED_AT = Instant.parse("2026-06-19T10:00:00Z");
    private static final String UID_PREFIX = "entity-it/";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    EntityExtractorWorker entityExtractorWorker;

    @Inject
    LlmProvider llmProvider;

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
        // Pin the injected Clock the scan-window pickup reads so the boundary is
        // deterministic; same QuarkusMock seam ThrottledAdminNotifier's Clock
        // producer documents (M1-444).
        QuarkusMock.installMockForType(Clock.fixed(PINNED_NOW, ZoneOffset.UTC), Clock.class);
        stub().reset();
        clearItData();
    }

    @Test
    void endToEndEntityExtraction() throws Exception {
        stub().setNextResponse(
            "[{\"text\":\"Log4Shell\",\"type\":\"product\"},"
                + "{\"text\":\"CVE-2021-44228\",\"type\":\"cve\"}]");
        UUID postId = seedPickupReadyPost("e2e", FETCHED_AT);
        // A pickup-ready post one day BELOW the scan-window floor
        // (PINNED_NOW − (retention + slack)) must be excluded — the
        // deterministic boundary assertion against the injected instant.
        Instant floor = PINNED_NOW.minus(
            Duration.ofDays(postRetentionDays + PartitionScan.PARTITION_SCAN_SLACK.toDays()));
        UUID belowFloorId = seedPickupReadyPost("below-floor", floor.minus(Duration.ofDays(1)));

        // Exercise the real pickup filter: the in-window post must be
        // enumerated (status='RAW' AND tagger_done=TRUE AND
        // entity_done=FALSE) and the below-floor post excluded, then the
        // in-window post processed end-to-end.
        List<EntityExtractorWorker.PostRow> pending = entityExtractorWorker.enumeratePending(64);
        assertFalse(pending.stream().anyMatch(r -> r.id().equals(belowFloorId)),
            "post fetched below PINNED_NOW − (retention + slack) must NOT be picked up — the "
                + "scan-window floor reads the injected Clock, so a fixed clock makes the boundary "
                + "deterministic");
        EntityExtractorWorker.PostRow row = pending.stream()
            .filter(r -> r.id().equals(postId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("seeded in-window post must be picked up by enumeratePending"));

        entityExtractorWorker.processOne(row);

        List<EntityPair> entities = queryEntities(postId);
        assertEquals(2, entities.size(), "both extracted entities must be persisted");
        assertTrue(entities.contains(new EntityPair("log4shell", "product")));
        assertTrue(entities.contains(new EntityPair("cve-2021-44228", "cve")));

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT entity_done FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getBoolean("entity_done"), "entity_done must be TRUE after the tick");
            }
        }
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
                     + "  gen_random_uuid(), ?, ?, ?, 'Entity IT title', 'Entity IT body',"
                     + "  ?, 'RAW',"
                     + "  TRUE, FALSE, FALSE, FALSE,"
                     + "  TRUE, FALSE, FALSE, FALSE, '{}', 0"
                     + ") RETURNING id")) {
            ps.setString(1, UID_PREFIX + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "entity-it-upstream-" + slug);
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
            ps.setString(1, "https://entity-it.example/" + slug);
            ps.setString(2, "Entity IT source " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private List<EntityPair> queryEntities(UUID postId) throws Exception {
        List<EntityPair> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT entity_text, entity_type FROM post_entity WHERE post_id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new EntityPair(rs.getString("entity_text"), rs.getString("entity_type")));
                }
            }
        }
        return out;
    }

    private void clearItData() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM post_entity WHERE post_id IN (SELECT id FROM post WHERE uid LIKE ?)")) {
                ps.setString(1, UID_PREFIX + "%");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM post WHERE uid LIKE ?")) {
                ps.setString(1, UID_PREFIX + "%");
                ps.executeUpdate();
            }
        }
    }

    private record EntityPair(String text, String type) {
    }
}
