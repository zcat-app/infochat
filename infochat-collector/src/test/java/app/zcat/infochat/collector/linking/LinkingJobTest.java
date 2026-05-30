package app.zcat.infochat.collector.linking;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral assertions for {@link LinkingJob}. Drives the job through
 * {@link LinkingJob#processOne} with directly seeded
 * {@code post}/{@code post_entity}/{@code post_embedding} fixtures so
 * each test pins one observable behaviour from the acceptance criteria.
 * Mirrors {@code EntityExtractorWorkerTest} for the seed-and-drive
 * pattern.
 */
@QuarkusTest
@TestProfile(LinkingJobTest.WideLookbackProfile.class)
class LinkingJobTest {

    /** All seeded posts use the same fetched_at so they share the V29 bootstrap partition. */
    private static final Instant FETCHED_AT = Instant.parse("2026-05-22T10:00:00Z");
    private static final String UID_PREFIX = "linking-test/";

    /**
     * Widens the LinkingJob driving-set and semantic-window cutoffs to
     * ~100 years so the fixed May 2026 fixture timestamps are always
     * inside the window — keeps the tests independent of wall-clock
     * drift while still exercising the production query shapes against
     * the V29 bootstrap partition.
     */
    public static final class WideLookbackProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "infochat.linking.lookback-days", "36500",
                "infochat.linking.semantic-window-hours", "876000");
        }
    }

    @Inject
    DataSource dataSource;

    @Inject
    LinkingJob linkingJob;

    @BeforeEach
    void reset() throws Exception {
        clearTestData();
    }

    @Test
    void entityMatch_createsBidirectionalReferences() throws Exception {
        // Two READY posts sharing 2 (text, type) entity pairs. Driving
        // post A: one tick → bidirectional 'entity' rows with score=2.
        UUID a = seedReadyPost("a");
        UUID b = seedReadyPost("b");
        seedEntity(a, "cve-2024-0001", "cve");
        seedEntity(a, "openssl", "product");
        seedEntity(b, "cve-2024-0001", "cve");
        seedEntity(b, "openssl", "product");

        linkingJob.processOne(driving(a));

        List<Edge> edges = queryEdges("entity");
        assertEquals(2, edges.size(),
            "bidirectional emission writes exactly 2 'entity' rows (A→B and B→A)");
        assertTrue(edges.contains(new Edge(a, b, "entity", 2.0f)),
            "forward A→B edge with score=2 (shared count)");
        assertTrue(edges.contains(new Edge(b, a, "entity", 2.0f)),
            "reverse B→A edge with same score");
    }

    @Test
    void semanticMatch_createsBidirectionalReferences() throws Exception {
        // Two READY posts with identical embeddings → cosine_distance=0
        // → similarity=1 (well below 0.18 threshold under %test). Same
        // bidirectional emission as the entity case.
        UUID a = seedReadyPost("sema");
        UUID b = seedReadyPost("semb");
        seedEmbedding(a, unitVector(0));
        seedEmbedding(b, unitVector(0));

        linkingJob.processOne(driving(a));

        List<Edge> edges = queryEdges("semantic");
        assertEquals(2, edges.size(),
            "bidirectional emission writes exactly 2 'semantic' rows");
        assertTrue(edges.contains(new Edge(a, b, "semantic", 1.0f)),
            "forward A→B 'semantic' edge with similarity=1 for identical vectors");
        assertTrue(edges.contains(new Edge(b, a, "semantic", 1.0f)),
            "reverse B→A 'semantic' edge with the same similarity");
    }

    @Test
    void linkCap_highestScoreWins() throws Exception {
        // Driving post D shares N entities with 12 candidates: candidate
        // i shares (i+1) entities with D, so the score for D→cand_i is
        // (i+1). The cap (max-links-per-post=10) must keep the top-10
        // by score — that is, candidates indexed 2..11 (scores 3..12);
        // candidates 0 and 1 (scores 1 and 2) must be dropped.
        UUID driving = seedReadyPost("cap-d");
        // 12 distinct entity texts so each candidate shares a different
        // subset with the driving post.
        String[] entityTexts = new String[12];
        for (int i = 0; i < 12; i++) {
            entityTexts[i] = "ent-" + i;
            // The driving post has ALL 12 entities.
            seedEntity(driving, entityTexts[i], "product");
        }
        // Candidate i shares the first (i+1) entities with the driving post.
        List<UUID> candidates = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID cand = seedReadyPost("cap-c" + i);
            candidates.add(cand);
            for (int j = 0; j <= i; j++) {
                seedEntity(cand, entityTexts[j], "product");
            }
        }

        linkingJob.processOne(driving(driving));

        Map<UUID, Float> forwardEdges = queryForwardEdgesFrom(driving, "entity");
        assertEquals(10, forwardEdges.size(),
            "cap retains exactly max-links-per-post=10 forward edges");
        // The two lowest-score candidates (i=0 score=1; i=1 score=2) MUST be dropped.
        assertFalse(forwardEdges.containsKey(candidates.get(0)),
            "lowest-score candidate (score=1) is dropped by the cap");
        assertFalse(forwardEdges.containsKey(candidates.get(1)),
            "second-lowest-score candidate (score=2) is dropped by the cap");
        // The 10 highest-score candidates (i=2..11) MUST all be present
        // with their expected scores.
        for (int i = 2; i < 12; i++) {
            UUID c = candidates.get(i);
            assertTrue(forwardEdges.containsKey(c),
                "candidate i=" + i + " (score=" + (i + 1) + ") must survive the cap");
            assertEquals((float) (i + 1), forwardEdges.get(c),
                "score equals the COUNT of shared entities for candidate i=" + i);
        }
    }

    @Test
    void lastLinkedAtAdvances_skipsDrivingPostOnNextRun() throws Exception {
        // After processing, last_linked_at advances past fetched_at, so
        // the driving-set predicate (last_linked_at IS NULL OR
        // last_linked_at < fetched_at) no longer admits the post.
        UUID a = seedReadyPost("ll-a");
        UUID b = seedReadyPost("ll-b");
        seedEntity(a, "shared-ent", "product");
        seedEntity(b, "shared-ent", "product");

        // Confirm A is in the driving set before processing.
        List<UUID> beforeDriving = enumerateDrivingIds();
        assertTrue(beforeDriving.contains(a),
            "post A must be in the driving set before processing");

        linkingJob.processOne(driving(a));

        List<UUID> afterDriving = enumerateDrivingIds();
        assertFalse(afterDriving.contains(a),
            "post A must NOT be in the driving set after last_linked_at advance");
    }

    @Test
    void noEmbedding_semanticSkipped_entityStillWorks() throws Exception {
        // Driving post with entity rows but NO post_embedding row →
        // entity-match runs normally, semantic-match returns nothing.
        UUID a = seedReadyPost("ne-a");
        UUID b = seedReadyPost("ne-b");
        seedEntity(a, "ent-x", "product");
        seedEntity(b, "ent-x", "product");
        // Note: NO seedEmbedding call for either post.

        linkingJob.processOne(driving(a));

        assertEquals(2, queryEdges("entity").size(),
            "entity-match still produces the bidirectional pair when embeddings are absent");
        assertEquals(0, queryEdges("semantic").size(),
            "no embedding for the driving post → zero semantic edges (acceptance item 9)");
    }

    @Test
    void duplicateEdge_skippedOnReprocessing() throws Exception {
        // After A→B is linked once, a re-run of processOne(A) inside
        // the lookback window must NOT write a second pair of rows.
        // Pins the per-direction NOT EXISTS dedup guard called out in
        // the ticket §Notes ("Duplicate edges across runs").
        UUID a = seedReadyPost("dup-a");
        UUID b = seedReadyPost("dup-b");
        seedEntity(a, "dup-ent", "product");
        seedEntity(b, "dup-ent", "product");

        linkingJob.processOne(driving(a));
        assertEquals(2, queryEdges("entity").size(), "first tick writes the bidirectional pair");

        // Force a second pass: clear last_linked_at on A so the
        // driving-set predicate would re-admit it, then re-process.
        clearLastLinkedAt(a);
        linkingJob.processOne(driving(a));

        assertEquals(2, queryEdges("entity").size(),
            "dedup must prevent a second pair of rows from landing on re-process");
    }

    // ---------- helpers ----------

    private LinkingJob.DrivingPost driving(UUID id) {
        return new LinkingJob.DrivingPost(id, FETCHED_AT);
    }

    private List<UUID> enumerateDrivingIds() throws Exception {
        List<UUID> out = new ArrayList<>();
        for (LinkingJob.DrivingPost d : linkingJob.enumerateDriving(256)) {
            out.add(d.id());
        }
        return out;
    }

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
                     + "  gen_random_uuid(), ?, ?, ?, 'Linking test title', 'Linking test body',"
                     + "  ?, 'READY', ?,"
                     + "  TRUE, FALSE, TRUE, FALSE,"
                     + "  TRUE, FALSE, TRUE, TRUE, '{}', 0"
                     + ") RETURNING id")) {
            ps.setString(1, UID_PREFIX + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "linking-upstream-" + slug);
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
            ps.setString(1, "https://linking-test.example/" + slug);
            ps.setString(2, "Linking test source " + slug);
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

    /**
     * Build a 768-dim unit-vector literal with a single 1.0 at the
     * given index. Same-index vectors produce cosine_distance=0
     * (identical direction); different-index vectors produce
     * cosine_distance=1 (orthogonal). Matches the V11 dimension (768)
     * configured under the test profile.
     */
    private static String unitVector(int hotIndex) {
        StringBuilder sb = new StringBuilder(768 * 4 + 2);
        sb.append('[');
        for (int i = 0; i < 768; i++) {
            if (i > 0) sb.append(',');
            sb.append(i == hotIndex ? "1" : "0");
        }
        sb.append(']');
        return sb.toString();
    }

    private List<Edge> queryEdges(String linkType) throws Exception {
        List<Edge> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT pr.from_post, pr.to_post, pr.link_type, pr.score "
                     + "  FROM post_reference pr "
                     + "  JOIN post p ON p.id = pr.from_post "
                     + " WHERE p.uid LIKE ? AND pr.link_type = ?")) {
            ps.setString(1, UID_PREFIX + "%");
            ps.setString(2, linkType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Edge(
                        (UUID) rs.getObject("from_post"),
                        (UUID) rs.getObject("to_post"),
                        rs.getString("link_type"),
                        rs.getFloat("score")));
                }
            }
        }
        return out;
    }

    /** Forward edges from a given source post, keyed by destination, for one link_type. */
    private Map<UUID, Float> queryForwardEdgesFrom(UUID source, String linkType) throws Exception {
        Map<UUID, Float> out = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT to_post, score FROM post_reference "
                     + " WHERE from_post = ? AND link_type = ?")) {
            ps.setObject(1, source);
            ps.setString(2, linkType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put((UUID) rs.getObject("to_post"), rs.getFloat("score"));
                }
            }
        }
        return out;
    }

    private void clearLastLinkedAt(UUID postId) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE post SET last_linked_at = NULL WHERE id = ?")) {
            ps.setObject(1, postId);
            ps.executeUpdate();
        }
    }

    private void clearTestData() throws Exception {
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

    private record Edge(UUID fromPost, UUID toPost, String linkType, float score) {
    }
}
