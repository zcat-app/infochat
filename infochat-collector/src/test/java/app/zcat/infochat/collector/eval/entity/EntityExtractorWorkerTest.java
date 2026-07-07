package app.zcat.infochat.collector.eval.entity;

import app.zcat.infochat.collector.eval.testing.StubLlmProvider;
import app.zcat.infochat.collector.testsupport.SeedDataSource;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral assertions for {@link EntityExtractorWorker}: successful
 * extraction + insertion, the empty-result and failure-release flag
 * advances, entity_text normalization, and out-of-vocab entity_type
 * filtering. Drives the worker through {@link EntityExtractorWorker#processOne}
 * with the shared {@link StubLlmProvider} replacing the production LLM
 * provider, mirroring {@code TaggerWorkerTest}.
 */
@QuarkusTest
class EntityExtractorWorkerTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-05-20T14:00:00Z");
    private static final String UID_PREFIX = "entity-test/";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    EntityExtractorWorker entityExtractorWorker;

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
        clearItData();
    }

    @Test
    void successfulExtraction_insertsEntitiesAndSetsFlag() throws Exception {
        stub().setNextResponse(
            "[{\"text\":\"CVE-2024-1234\",\"type\":\"cve\"},"
                + "{\"text\":\"OpenSSL\",\"type\":\"product\"},"
                + "{\"text\":\"Acme Corp\",\"type\":\"org\"}]");
        SeededPost post = seedPost("success");

        entityExtractorWorker.processOne(rowFor(post));

        List<EntityPair> entities = queryEntities(post.id);
        assertEquals(3, entities.size(), "all three entities must be inserted");
        assertTrue(entities.contains(new EntityPair("cve-2024-1234", "cve")));
        assertTrue(entities.contains(new EntityPair("openssl", "product")));
        assertTrue(entities.contains(new EntityPair("acme corp", "org")));
        assertEntityDone(post.id);
    }

    @Test
    void noEntitiesFound_setsFlag() throws Exception {
        stub().setNextResponse("[]");
        SeededPost post = seedPost("none");

        entityExtractorWorker.processOne(rowFor(post));

        assertTrue(queryEntities(post.id).isEmpty(), "no entities inserted on empty result");
        assertEntityDone(post.id);
    }

    @Test
    void llmFailureAfterRetry_releasesWithoutEntities() throws Exception {
        // failAll makes every generate call throw → initial attempt
        // plus one retry both fail → failure-release.
        stub().failAll();
        SeededPost post = seedPost("fail");

        entityExtractorWorker.processOne(rowFor(post));

        assertTrue(queryEntities(post.id).isEmpty(), "no entities on failure-release");
        assertEntityDone(post.id);
        assertEquals(2, stub().callCount(), "exactly one retry after the initial failure");
        var state = throttledAdminNotifier.getState(
            EntityExtractorWorker.ERROR_CLASS_ENTITY_EXTRACTION_FAILURE);
        assertTrue(state.isPresent(),
            "throttled admin notification must fire on failure-release");
    }

    @Test
    void entityTextNormalization_lowerCasesAndStrips() throws Exception {
        stub().setNextResponse("[{\"text\":\"  OpenSSL  \",\"type\":\"product\"}]");
        SeededPost post = seedPost("normalize");

        entityExtractorWorker.processOne(rowFor(post));

        List<EntityPair> entities = queryEntities(post.id);
        assertEquals(1, entities.size());
        assertEquals("openssl", entities.get(0).text(),
            "entity_text must be lower-cased and stripped before INSERT");
        assertEntityDone(post.id);
    }

    @Test
    void invalidEntityType_droppedSilently() throws Exception {
        // 'malware' is not in the entity_type vocabulary; it is dropped
        // while the valid 'product' entity from the same response is kept.
        stub().setNextResponse(
            "[{\"text\":\"Real Product\",\"type\":\"product\"},"
                + "{\"text\":\"Some Threat\",\"type\":\"malware\"}]");
        SeededPost post = seedPost("invalid-type");

        entityExtractorWorker.processOne(rowFor(post));

        List<EntityPair> entities = queryEntities(post.id);
        assertEquals(1, entities.size(), "out-of-vocab entity_type dropped, valid entity kept");
        assertEquals(new EntityPair("real product", "product"), entities.get(0));
        assertEntityDone(post.id);
    }

    @Test
    void fencedJsonArray_recoversEntitiesInsteadOfSchemaViolating() throws Exception {
        // The exact DeepSeek shape observed live 2026-07-07 (M1-586): a
        // valid JSON array wrapped in a ```json markdown code fence. Before
        // the fence-strip the strict readTree rejected the fence →
        // SCHEMA_VIOLATING → release without entities; now it is recovered on
        // the first attempt (callCount==1, no retry).
        stub().setNextResponse("```json\n[\n  {\"text\": \"CISA\", \"type\": \"org\"}\n]\n```");
        SeededPost post = seedPost("fenced");

        entityExtractorWorker.processOne(rowFor(post));

        List<EntityPair> entities = queryEntities(post.id);
        assertEquals(1, entities.size(),
            "fenced-but-valid JSON array must be recovered, not released without entities");
        assertEquals(new EntityPair("cisa", "org"), entities.get(0));
        assertEntityDone(post.id);
        assertEquals(1, stub().callCount(),
            "fenced-but-valid reply parses on the first attempt — no schema-violating retry");
    }

    // ---------- helpers ----------

    private EntityExtractorWorker.PostRow rowFor(SeededPost post) {
        return new EntityExtractorWorker.PostRow(post.id, post.fetchedAt, "title", "body");
    }

    private SeededPost seedPost(String slug) throws Exception {
        UUID sourceId = seedSource(slug);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post ("
                     + "  id, uid, source_id, upstream_identifier, title, body,"
                     + "  fetched_at, status,"
                     + "  stage1_done, stage1_flagged, stage2_done, stage2_failed,"
                     + "  tagger_done, tagger_fallback, entity_done, embedding_done, tags, re_eval_attempts"
                     + ") VALUES ("
                     + "  gen_random_uuid(), ?, ?, ?, 'title', 'body',"
                     + "  ?, 'RAW',"
                     + "  TRUE, FALSE, FALSE, FALSE,"
                     + "  TRUE, FALSE, FALSE, FALSE, '{}', 0"
                     + ") RETURNING id, fetched_at")) {
            ps.setString(1, UID_PREFIX + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "entity-upstream-" + slug);
            ps.setTimestamp(4, Timestamp.from(FETCHED_AT));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new SeededPost((UUID) rs.getObject(1), rs.getTimestamp(2).toInstant());
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
            ps.setString(1, "https://entity-test.example/" + slug);
            ps.setString(2, "Entity Test " + slug);
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

    private void assertEntityDone(UUID postId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT entity_done FROM post WHERE id = ?")) {
            ps.setObject(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getBoolean("entity_done"), "entity_done must be TRUE after processing");
            }
        }
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

    private record SeededPost(UUID id, Instant fetchedAt) {
    }

    private record EntityPair(String text, String type) {
    }
}
