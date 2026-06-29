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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins candidate-set equivalence for {@link LinkingJob#findSemanticCandidates}
 * after the self-join → HNSW-probe rewrite (M1-230). Each test seeds a
 * co-temporal {@code post_embedding} fixture against Testcontainers
 * pgvector and asserts the rewritten query honours the same distance
 * threshold, {@code NOT EXISTS} dedup, distance ordering, and per-post
 * cap the prior self-join produced.
 *
 * <p>Vectors are unit-ish 768-d literals tilted toward index 1: the
 * driving vector is {@code [1,0,0,…]} and a candidate {@code [1,a,0,…]}
 * sits at cosine distance {@code 1 - 1/sqrt(1+a²)}, which grows
 * monotonically with {@code a}. That gives a deterministic distance
 * ranking from the bound query vector — the exact property the HNSW
 * probe is supposed to exploit.
 */
@QuarkusTest
@TestProfile(LinkingJobBehaviorIT.WideLookbackProfile.class)
class LinkingJobSemanticProbeIT {

    /** Co-temporal: every seeded post shares one fetched_at (and partition). */
    private static final Instant FETCHED_AT = Instant.parse("2026-05-22T12:00:00Z");
    private static final String UID_PREFIX = "linking-probe/";

    @Inject
    @SeedDataSource
    DataSource dataSource;

    @Inject
    LinkingJob linkingJob;

    @BeforeEach
    void reset() throws Exception {
        clearTestData();
    }

    @Test
    void semanticProbe_excludesOverThreshold_ordersByDistance() throws Exception {
        // Driving D = [1,0,…]. Three candidates under the 0.18 threshold
        // at increasing distance, plus one orthogonal candidate at
        // distance 1.0 that the threshold must drop.
        UUID d = seedReadyPost("thr-d");
        seedEmbedding(d, tiltedVector(0.0));
        UUID near0 = seedReadyPost("thr-near0");
        seedEmbedding(near0, tiltedVector(0.0));   // distance 0.0
        UUID near1 = seedReadyPost("thr-near1");
        seedEmbedding(near1, tiltedVector(0.2));   // distance ~0.019
        UUID near2 = seedReadyPost("thr-near2");
        seedEmbedding(near2, tiltedVector(0.5));   // distance ~0.106
        UUID far = seedReadyPost("thr-far");
        seedEmbedding(far, orthogonalVector());    // distance 1.0 → excluded

        List<UUID> candidates = semanticCandidateIds(d);

        assertEquals(List.of(near0, near1, near2), candidates,
            "probe returns exactly the sub-threshold candidates, nearest-first");
        assertFalse(candidates.contains(far),
            "the over-threshold (orthogonal) candidate is excluded by the distance filter");
    }

    @Test
    void semanticProbe_capsAtMaxLinksPerPost_keepingNearest() throws Exception {
        // 12 sub-threshold candidates at strictly increasing distance.
        // The cap (max-links-per-post=10) must keep the nearest 10 and
        // drop the two farthest, in distance order.
        UUID d = seedReadyPost("cap-d");
        seedEmbedding(d, tiltedVector(0.0));
        List<UUID> ordered = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID c = seedReadyPost("cap-c" + i);
            // a = (i+1)*0.04 → a ∈ [0.04, 0.48]; max distance ~0.098 < 0.18.
            seedEmbedding(c, tiltedVector((i + 1) * 0.04));
            ordered.add(c);
        }

        List<UUID> candidates = semanticCandidateIds(d);

        assertEquals(ordered.subList(0, 10), candidates,
            "cap keeps exactly the nearest max-links-per-post=10 candidates, in distance order");
        assertFalse(candidates.contains(ordered.get(10)),
            "the 11th-nearest candidate is dropped by the cap");
        assertFalse(candidates.contains(ordered.get(11)),
            "the 12th-nearest candidate is dropped by the cap");
    }

    @Test
    void semanticProbe_missingDrivingEmbedding_returnsEmpty() throws Exception {
        // Driving post with NO post_embedding row. Another post DOES have
        // an embedding, so a wrongly-issued probe could match it — the
        // method must instead short-circuit to an empty list.
        UUID d = seedReadyPost("noemb-d");
        UUID other = seedReadyPost("noemb-other");
        seedEmbedding(other, tiltedVector(0.0));

        List<UUID> candidates = semanticCandidateIds(d);

        assertTrue(candidates.isEmpty(),
            "no driving embedding → empty candidate list without issuing the probe");
    }

    @Test
    void semanticProbe_dedupExcludesAlreadyLinkedCandidate() throws Exception {
        // Two sub-threshold candidates; one already has a 'semantic'
        // post_reference from the driving post. The NOT EXISTS dedup must
        // drop the already-linked one and keep the other.
        UUID d = seedReadyPost("dedup-d");
        seedEmbedding(d, tiltedVector(0.0));
        UUID linked = seedReadyPost("dedup-linked");
        seedEmbedding(linked, tiltedVector(0.1));
        UUID fresh = seedReadyPost("dedup-fresh");
        seedEmbedding(fresh, tiltedVector(0.2));
        seedSemanticReference(d, linked);

        List<UUID> candidates = semanticCandidateIds(d);

        assertEquals(List.of(fresh), candidates,
            "dedup excludes the already-linked candidate, keeping only the fresh one");
    }

    // ---------- helpers ----------

    private List<UUID> semanticCandidateIds(UUID drivingId) throws Exception {
        LinkingJob.DrivingPost driving = new LinkingJob.DrivingPost(drivingId, FETCHED_AT);
        List<UUID> ids = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            for (LinkingJob.Candidate c : linkingJob.findSemanticCandidates(conn, driving)) {
                ids.add(c.postId());
            }
        }
        return ids;
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
                     + "  gen_random_uuid(), ?, ?, ?, 'Probe IT title', 'Probe IT body',"
                     + "  ?, 'READY', ?,"
                     + "  TRUE, FALSE, TRUE, FALSE,"
                     + "  TRUE, FALSE, TRUE, TRUE, '{}', 0"
                     + ") RETURNING id")) {
            ps.setString(1, UID_PREFIX + slug);
            ps.setObject(2, sourceId);
            ps.setString(3, "linking-probe-upstream-" + slug);
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
            ps.setString(1, "https://linking-probe.example/" + slug);
            ps.setString(2, "Linking probe source " + slug);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
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

    private void seedSemanticReference(UUID fromPost, UUID toPost) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO post_reference (from_post, to_post, link_type, score) "
                     + "VALUES (?, ?, 'semantic', 1.0)")) {
            ps.setObject(1, fromPost);
            ps.setObject(2, toPost);
            ps.executeUpdate();
        }
    }

    /**
     * 768-d vector {@code [1, a, 0, 0, …]}. Cosine distance from the
     * driving vector {@code [1,0,…]} is {@code 1 - 1/sqrt(1+a²)}, which
     * increases monotonically with {@code a}, giving a deterministic
     * distance ranking.
     */
    private static String tiltedVector(double a) {
        StringBuilder sb = new StringBuilder(768 * 4 + 2);
        sb.append('[');
        for (int i = 0; i < 768; i++) {
            if (i > 0) sb.append(',');
            if (i == 0) {
                sb.append('1');
            } else if (i == 1) {
                sb.append(a);
            } else {
                sb.append('0');
            }
        }
        sb.append(']');
        return sb.toString();
    }

    /** 768-d vector orthogonal to the driving vector → cosine distance 1.0. */
    private static String orthogonalVector() {
        StringBuilder sb = new StringBuilder(768 * 4 + 2);
        sb.append('[');
        for (int i = 0; i < 768; i++) {
            if (i > 0) sb.append(',');
            sb.append(i == 1 ? "1" : "0");
        }
        sb.append(']');
        return sb.toString();
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
            // The HNSW probe is a GLOBAL nearest-neighbour scan over the
            // whole post_embedding window (the wide-lookback profile makes
            // that window effectively unbounded), so the exact-candidate-set
            // assertions below can only hold if no foreign embeddings are
            // visible. Sibling linking tests (e.g. LinkingJobIT) seed
            // identity vectors that survive into this shared test DB and
            // would otherwise appear as distance-0 candidates. Wipe every
            // embedding so this fixture fully controls the probe universe;
            // post_embedding is a leaf table (no FK children) and each test
            // class re-seeds what it needs, so a full wipe is safe under
            // sequential class execution.
            try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM post_embedding")) {
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
