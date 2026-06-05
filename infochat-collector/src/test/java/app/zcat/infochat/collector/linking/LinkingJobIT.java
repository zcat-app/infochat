package app.zcat.infochat.collector.linking;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test for {@link LinkingJob}: seeds a pair of
 * READY posts with {@code post_entity} + {@code post_embedding} rows,
 * drives one tick via {@link LinkingJob#onTick}, and asserts the V29
 * {@code post_reference} rows materialise plus {@code last_linked_at}
 * advances on both endpoints. Mirrors {@code EntityExtractorWorkerIT}
 * for the seed-and-drive shape.
 */
@QuarkusTest
@TestProfile(LinkingJobTest.WideLookbackProfile.class)
class LinkingJobIT {

    private static final Instant FETCHED_AT = Instant.parse("2026-05-22T11:00:00Z");
    private static final String UID_PREFIX = "linking-it/";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    LinkingJob linkingJob;

    @BeforeEach
    void reset() throws Exception {
        clearItData();
    }

    @Test
    void endToEndLinking() throws Exception {
        UUID a = seedReadyPost("e2e-a");
        UUID b = seedReadyPost("e2e-b");
        // Shared entity for the entity-match path.
        seedEntity(a, "cve-2024-1234", "cve");
        seedEntity(b, "cve-2024-1234", "cve");
        // Identical embeddings for the semantic-match path.
        seedEmbedding(a, identityVectorLiteral());
        seedEmbedding(b, identityVectorLiteral());

        linkingJob.onTick();

        // Bidirectional emission per link_type → 4 rows total (2 entity + 2 semantic).
        int entityRows = countEdges(a, b, "entity") + countEdges(b, a, "entity");
        int semanticRows = countEdges(a, b, "semantic") + countEdges(b, a, "semantic");
        assertEquals(2, entityRows,
            "post_reference must contain bidirectional 'entity' rows after the tick");
        assertEquals(2, semanticRows,
            "post_reference must contain bidirectional 'semantic' rows after the tick");

        assertTrue(lastLinkedAtIsSet(a),
            "last_linked_at must advance for the driving post A after the tick");
        assertTrue(lastLinkedAtIsSet(b),
            "last_linked_at must advance for the driving post B after the tick");
    }

    // ---------- helpers ----------

    private UUID seedReadyPost(String slug) throws Exception {
        UUID sourceId = seedSource(slug);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body,"
                     + "  fetched_at, status, ready_at,"
                     + "  stage1_done, stage1_flagged, stage2_done, stage2_failed,"
                     + "  tagger_done, tagger_fallback, entity_done, embedding_done, tags, re_eval_attempts"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, 'Linking IT title', 'Linking IT body',"
                     + "  ?, 'READY', ?,"
                     + "  TRUE, FALSE, TRUE, FALSE,"
                     + "  TRUE, FALSE, TRUE, TRUE, '{}', 0"
                     + ") RETURNING id")) {
            ps.setString(1, UID_PREFIX + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "linking-it-upstream-" + slug);
            ps.setTimestamp(4, Timestamp.from(FETCHED_AT));
            ps.setTimestamp(5, Timestamp.from(FETCHED_AT));
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
            ps.setString(1, "https://linking-it.example/" + slug);
            ps.setString(2, "Linking IT source " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private void seedEntity(UUID postId, String text, String type) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post_entity (post_id, entity_text, entity_type, fetched_at) "
                     + "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
            ps.setObject(1, postId);
            ps.setString(2, text);
            ps.setString(3, type);
            ps.setTimestamp(4, Timestamp.from(FETCHED_AT));
            ps.executeUpdate();
        }
    }

    private void seedEmbedding(UUID postId, String vectorLiteral) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post_embedding (post_id, embedding, embedding_model, fetched_at) "
                     + "VALUES (?, ?::vector, 'nomic-embed-text', ?)")) {
            ps.setObject(1, postId);
            ps.setString(2, vectorLiteral);
            ps.setTimestamp(3, Timestamp.from(FETCHED_AT));
            ps.executeUpdate();
        }
    }

    /** 768-dim vector with a single 1.0 at index 0 — cosine distance from itself is 0. */
    private static String identityVectorLiteral() {
        StringBuilder sb = new StringBuilder(768 * 4 + 2);
        sb.append('[');
        for (int i = 0; i < 768; i++) {
            if (i > 0) sb.append(',');
            sb.append(i == 0 ? "1" : "0");
        }
        sb.append(']');
        return sb.toString();
    }

    private int countEdges(UUID fromPost, UUID toPost, String linkType) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(*) FROM post_reference "
                     + " WHERE from_post = ? AND to_post = ? AND link_type = ?")) {
            ps.setObject(1, fromPost);
            ps.setObject(2, toPost);
            ps.setString(3, linkType);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private boolean lastLinkedAtIsSet(UUID postId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT last_linked_at FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                rs.getTimestamp("last_linked_at");
                return !rs.wasNull();
            }
        }
    }

    private void clearItData() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM post_reference "
                    + "WHERE from_post IN (SELECT id FROM post WHERE uid LIKE ?) "
                    + "   OR to_post   IN (SELECT id FROM post WHERE uid LIKE ?)")) {
                ps.setString(1, UID_PREFIX + "%");
                ps.setString(2, UID_PREFIX + "%");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM post_entity WHERE post_id IN (SELECT id FROM post WHERE uid LIKE ?)")) {
                ps.setString(1, UID_PREFIX + "%");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM post_embedding WHERE post_id IN (SELECT id FROM post WHERE uid LIKE ?)")) {
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
}
