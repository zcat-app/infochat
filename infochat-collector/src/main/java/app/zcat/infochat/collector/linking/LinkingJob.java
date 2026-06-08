package app.zcat.infochat.collector.linking;

import app.zcat.infochat.collector.eval.TransactionHelper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Collector-side scheduled job that consumes {@code post_entity} (V28)
 * and {@code post_embedding} (V11) and writes bidirectional
 * {@code post_reference} (V29) edges feeding the Tier-2 cross-source
 * linking surface (D6, docs/design/01-architecture.md §1.3.5).
 *
 * <h2>Driving set</h2>
 *
 * <p>{@code status='READY' AND (last_linked_at IS NULL OR last_linked_at
 * < fetched_at)} bounded to the {@code infochat.linking.lookback-days}
 * window. The V7 partial index {@code idx_post_link_cursor} backs this
 * scan exactly. Posts re-enter the driving set only when their
 * {@code last_linked_at} cursor drifts behind {@code fetched_at} — in
 * the normal pipeline this happens once per post, on the first tick
 * after the post reaches READY.
 *
 * <h2>Link types</h2>
 *
 * <p><b>{@code 'entity'}</b> — posts sharing at least one
 * {@code (entity_text, entity_type)} pair in {@code post_entity}.
 * Score is {@code COUNT(*)} of shared pairs (stored as REAL in V29).
 *
 * <p><b>{@code 'semantic'}</b> — posts within the
 * {@code infochat.linking.semantic-window-hours} time window whose
 * pgvector cosine distance is below
 * {@code infochat.linking.semantic-threshold}. Score is
 * {@code 1 - cosine_distance} (similarity). Driving posts without a
 * {@code post_embedding} row produce zero semantic candidates — the
 * driving-vector PK read finds no row, so no ANN probe is issued.
 *
 * <p>{@code 'repost'} (Nostr kind-6 cross-source linking) lands in
 * M1-100 and is out of scope here; the V29 CHECK constraint already
 * admits it so M1-100 needs no schema amendment.
 *
 * <h2>Bidirectional emission</h2>
 *
 * <p>Each accepted candidate produces two {@code post_reference} rows:
 * one {@code (driving → candidate)} and one
 * {@code (candidate → driving)}. ClusterTraversal walks both directions
 * of the graph, but a single-direction scan from either endpoint
 * surfaces the link.
 *
 * <h2>Cap and dedup</h2>
 *
 * <p>The {@code infochat.linking.max-links-per-post} cap is applied
 * <i>per link type</i> in-query (ORDER BY score DESC, post_id ASC
 * LIMIT N). Entity counts (≥1) and semantic similarities (0..1) are
 * not comparable on the same scale, so a total cap across types would
 * always evict semantic candidates; per-type is the only well-defined
 * reading.
 *
 * <p>Per-direction dedup uses a {@code NOT EXISTS} guard against
 * {@code post_reference} rows whose {@code created_at} is within the
 * lookback window. Because the bidirectional INSERT writes both legs in
 * the same transaction, the reverse direction of a previously written
 * edge naturally blocks the reverse driving post from re-emitting the
 * same logical link (its forward dedup check sees the prior write).
 *
 * <h2>Transaction shape (Invariant 5)</h2>
 *
 * <p>Per driving post: the INSERTs for both link types AND the
 * {@code UPDATE post SET last_linked_at = now()} commit inside one
 * {@link TransactionHelper#inTransaction} boundary. A crash mid-post
 * rolls back and the next tick re-picks the post via the
 * {@code last_linked_at IS NULL OR last_linked_at < fetched_at}
 * driving-set filter.
 */
@ApplicationScoped
public class LinkingJob {

    private static final Logger LOG = Logger.getLogger(LinkingJob.class);

    /**
     * Batch size for the driving-set enumeration. Bounds the work one
     * tick performs; if more posts are eligible they are picked up on
     * the next tick. Matches the {@link
     * app.zcat.infochat.collector.eval.reeval.AdminReviewTtlJob} pattern.
     */
    private static final int DRIVING_BATCH_SIZE = 64;

    /**
     * Over-fetch factor for the semantic ANN probe. The HNSW top-k scan
     * returns the k nearest embeddings by cosine distance; the wrapping
     * query then drops candidates over the distance threshold or already
     * linked (the {@code NOT EXISTS} dedup). Requesting
     * {@code maxLinksPerPost} × this factor leaves recall headroom so
     * dedup-eliminated rows and HNSW approximate-recall misses do not
     * starve the final per-post cap. The distance-threshold exclusion is
     * monotonic in distance (every farther row is also over the
     * threshold) so it needs no headroom; the dedup filter is not
     * monotonic, hence the multiple.
     */
    private static final int SEMANTIC_PROBE_OVERFETCH = 4;

    @Inject
    DataSource dataSource;

    @ConfigProperty(name = "infochat.linking.lookback-days")
    int lookbackDays;

    @ConfigProperty(name = "infochat.linking.semantic-window-hours")
    int semanticWindowHours;

    @ConfigProperty(name = "infochat.linking.semantic-threshold")
    double semanticThreshold;

    @ConfigProperty(name = "infochat.linking.max-links-per-post")
    int maxLinksPerPost;

    /**
     * Scheduled tick. Enumerates driving posts and processes each one.
     * A processing failure on one post does not abort the tick — the
     * post stays in the driving set and the next tick re-picks it.
     */
    @Scheduled(every = "{infochat.linking.interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void onTick() {
        List<DrivingPost> driving;
        try {
            driving = enumerateDriving(DRIVING_BATCH_SIZE);
        } catch (SQLException e) {
            LOG.warn("LinkingJob: failed to enumerate driving posts; skipping tick", e);
            return;
        }
        for (DrivingPost d : driving) {
            try {
                processOne(d);
            } catch (RuntimeException e) {
                LOG.warnf(e,
                    "LinkingJob: processing failed for post_id=%s; will retry next tick",
                    d.id());
            }
        }
    }

    /**
     * Driving-set query. The {@code idx_post_link_cursor} partial
     * index at V7 line 188 backs this scan. The lookback bound is
     * additive insurance: in a healthy pipeline {@code last_linked_at}
     * advances before the lookback elapses, so a post never re-enters
     * the driving set after its first link tick. The cutoff guards a
     * recovery scenario (e.g., LinkingJob disabled for days) by
     * capping the work one revived tick processes.
     */
    List<DrivingPost> enumerateDriving(int limit) throws SQLException {
        Instant cutoff = Instant.now().minus(Duration.ofDays(lookbackDays));
        final String sql =
            "SELECT id, fetched_at "
                + "  FROM post "
                + " WHERE status = 'READY' "
                + "   AND (last_linked_at IS NULL OR last_linked_at < fetched_at) "
                + "   AND fetched_at >= ? "
                + " ORDER BY fetched_at, id "
                + " LIMIT ?";
        List<DrivingPost> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = (UUID) rs.getObject(1);
                    Instant fetchedAt = rs.getTimestamp(2).toInstant();
                    rows.add(new DrivingPost(id, fetchedAt));
                }
            }
        }
        return rows;
    }

    /**
     * Process one driving post: find entity + semantic candidates,
     * INSERT bidirectional edges, advance {@code last_linked_at}.
     * Package-private so tests can drive the single-post path directly
     * without waiting on the scheduler.
     */
    void processOne(DrivingPost driving) {
        TransactionHelper.inTransaction(dataSource, "LinkingJob", conn -> {
            List<Candidate> entityCandidates = findEntityCandidates(conn, driving);
            List<Candidate> semanticCandidates = findSemanticCandidates(conn, driving);
            insertBidirectional(conn, driving, "entity", entityCandidates);
            insertBidirectional(conn, driving, "semantic", semanticCandidates);
            advanceLastLinkedAt(conn, driving);
        });
    }

    /**
     * Entity-match candidate query. Joins {@code post_entity} to itself
     * on the {@code (entity_text, entity_type)} pair; the score is the
     * COUNT of shared pairs. The NOT EXISTS clause folds the
     * per-direction dedup into the same query so re-runs within the
     * lookback window do not write duplicate logical edges. The LIMIT
     * applies the per-link-type outbound cap.
     */
    List<Candidate> findEntityCandidates(Connection conn, DrivingPost driving) throws SQLException {
        Instant cutoff = Instant.now().minus(Duration.ofDays(lookbackDays));
        final String sql =
            "SELECT pe2.post_id, COUNT(*) AS shared_count "
                + "  FROM post_entity pe1 "
                + "  JOIN post_entity pe2 "
                + "    ON pe1.entity_text = pe2.entity_text "
                + "   AND pe1.entity_type = pe2.entity_type "
                + " WHERE pe1.post_id = ? "
                + "   AND pe1.fetched_at = ? "
                + "   AND pe2.post_id <> pe1.post_id "
                + "   AND pe2.fetched_at >= ? "
                + "   AND NOT EXISTS ( "
                + "       SELECT 1 FROM post_reference pr "
                + "        WHERE pr.from_post = ? "
                + "          AND pr.to_post = pe2.post_id "
                + "          AND pr.link_type = 'entity' "
                + "          AND pr.created_at > ? "
                + "   ) "
                + " GROUP BY pe2.post_id "
                + " ORDER BY shared_count DESC, pe2.post_id ASC "
                + " LIMIT ?";
        List<Candidate> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, driving.id());
            ps.setTimestamp(2, Timestamp.from(driving.fetchedAt()));
            ps.setTimestamp(3, Timestamp.from(cutoff));
            ps.setObject(4, driving.id());
            ps.setTimestamp(5, Timestamp.from(cutoff));
            ps.setInt(6, maxLinksPerPost);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID candidateId = (UUID) rs.getObject(1);
                    int sharedCount = rs.getInt(2);
                    out.add(new Candidate(candidateId, (float) sharedCount));
                }
            }
        }
        return out;
    }

    /**
     * Semantic-match candidate query. Two SQL round-trips: read the
     * driving post's embedding by PK, then run an indexable top-k ANN
     * probe with that vector bound as a {@code ?::vector} parameter. The
     * score is {@code 1 - cosine_distance} ({@code pgvector}'s
     * {@code <=>} operator returns distance, not similarity).
     *
     * <p>Binding the driving vector as a plan-time parameter (rather than
     * referencing a second {@code post_embedding} column in a self-join)
     * is what lets PostgreSQL drive {@code ORDER BY embedding <=> ?
     * LIMIT k} through {@code idx_post_embedding_hnsw}; a column-vs-column
     * {@code <=>} cannot use the index and degrades to a full distance
     * scan of the window per driving post. The inner probe carries only
     * the index-compatible window/self filters and a distance-only
     * ORDER BY (the HNSW shape); the wrapping query then applies the
     * distance threshold, the {@code NOT EXISTS} dedup, and the
     * deterministic {@code (distance, post_id)} tie-break + cap over the
     * ≤ {@code maxLinksPerPost × SEMANTIC_PROBE_OVERFETCH} returned rows.
     *
     * <p>A driving post without a {@code post_embedding} row produces
     * zero candidates: the PK read finds no row, so the method returns an
     * empty list <i>without issuing the probe</i>. Acceptance item [9] +
     * LinkingJobTest.noEmbedding_semanticSkipped_entityStillWorks.
     */
    List<Candidate> findSemanticCandidates(Connection conn, DrivingPost driving) throws SQLException {
        String drivingVector = readDrivingEmbedding(conn, driving);
        if (drivingVector == null) {
            return List.of();
        }
        Instant lookbackCutoff = Instant.now().minus(Duration.ofDays(lookbackDays));
        Instant semanticCutoff = Instant.now().minus(Duration.ofHours(semanticWindowHours));
        final String sql =
            "SELECT post_id, distance "
                + "  FROM ( "
                + "    SELECT pe.post_id AS post_id, "
                + "           (pe.embedding <=> ?::vector) AS distance "
                + "      FROM post_embedding pe "
                + "     WHERE pe.post_id <> ? "
                + "       AND pe.fetched_at >= ? "
                + "     ORDER BY pe.embedding <=> ?::vector "
                + "     LIMIT ? "
                + "  ) probe "
                + " WHERE probe.distance < ? "
                + "   AND NOT EXISTS ( "
                + "       SELECT 1 FROM post_reference pr "
                + "        WHERE pr.from_post = ? "
                + "          AND pr.to_post = probe.post_id "
                + "          AND pr.link_type = 'semantic' "
                + "          AND pr.created_at > ? "
                + "   ) "
                + " ORDER BY probe.distance ASC, probe.post_id ASC "
                + " LIMIT ?";
        List<Candidate> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, drivingVector);
            ps.setObject(2, driving.id());
            ps.setTimestamp(3, Timestamp.from(semanticCutoff));
            ps.setString(4, drivingVector);
            ps.setInt(5, maxLinksPerPost * SEMANTIC_PROBE_OVERFETCH);
            ps.setDouble(6, semanticThreshold);
            ps.setObject(7, driving.id());
            ps.setTimestamp(8, Timestamp.from(lookbackCutoff));
            ps.setInt(9, maxLinksPerPost);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID candidateId = (UUID) rs.getObject(1);
                    double distance = rs.getDouble(2);
                    float similarity = (float) (1.0 - distance);
                    out.add(new Candidate(candidateId, similarity));
                }
            }
        }
        return out;
    }

    /**
     * Read the driving post's embedding by PK as its {@code pgvector}
     * text literal ({@code [v0,v1,...]}), suitable for re-binding through
     * a {@code ?::vector} cast on the probe. Returns {@code null} when the
     * post has no {@code post_embedding} row — the caller treats that as
     * "no semantic candidates" and skips the probe.
     */
    @Nullable
    private String readDrivingEmbedding(Connection conn, DrivingPost driving) throws SQLException {
        final String sql =
            "SELECT embedding::text FROM post_embedding WHERE post_id = ? AND fetched_at = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, driving.id());
            ps.setTimestamp(2, Timestamp.from(driving.fetchedAt()));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString(1);
            }
        }
    }

    /**
     * INSERT one forward and one reverse {@code post_reference} row per
     * candidate, batched. {@code created_at} defaults to {@code now()}
     * at the DB so both legs land with the same timestamp inside this
     * transaction. The reverse leg carries the same score — the link
     * is the same logical relationship from either endpoint.
     */
    private void insertBidirectional(Connection conn, DrivingPost driving,
                                     String linkType, List<Candidate> candidates) throws SQLException {
        if (candidates.isEmpty()) {
            return;
        }
        final String sql =
            "INSERT INTO post_reference (from_post, to_post, link_type, score) "
                + "VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Candidate c : candidates) {
                // Forward: driving → candidate.
                ps.setObject(1, driving.id());
                ps.setObject(2, c.postId());
                ps.setString(3, linkType);
                ps.setFloat(4, c.score());
                ps.addBatch();
                // Reverse: candidate → driving.
                ps.setObject(1, c.postId());
                ps.setObject(2, driving.id());
                ps.setString(3, linkType);
                ps.setFloat(4, c.score());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Advance {@code last_linked_at = now()} for the driving post. The
     * (id, fetched_at) WHERE clause matches the partitioned-PK shape so
     * the UPDATE plans on the right partition.
     */
    private void advanceLastLinkedAt(Connection conn, DrivingPost driving) throws SQLException {
        final String sql =
            "UPDATE post SET last_linked_at = now() WHERE id = ? AND fetched_at = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, driving.id());
            ps.setTimestamp(2, Timestamp.from(driving.fetchedAt()));
            ps.executeUpdate();
        }
    }

    /** One driving post enumerated by {@link #enumerateDriving}. */
    public record DrivingPost(UUID id, Instant fetchedAt) {
    }

    /** One link candidate with its score (entity count or semantic similarity). */
    public record Candidate(UUID postId, float score) {
    }
}
