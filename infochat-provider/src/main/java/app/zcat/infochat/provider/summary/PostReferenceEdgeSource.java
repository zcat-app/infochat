package app.zcat.infochat.provider.summary;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.NonNull;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;

/**
 * Edge-source SPI for {@link ClusterTraversal}. Returns the symmetric
 * neighbour relation over a fixed set of input post ids, restricted to
 * intra-input edges (an edge to a post outside the input set is
 * dropped). This shape lets {@code ClusterTraversal} BFS the graph
 * without any per-node DB hits — one round-trip per traversal call.
 *
 * <p>Cross-scope leakage is impossible at this layer: the input post
 * set is already produced by {@code EligiblePostQuery}, which applies
 * the scope filter at SQL time. The SPI restricts edges to intra-input
 * pairs as an additional defence so a non-scope edge (if one ever
 * existed) could not pull an out-of-scope id into the cluster graph.
 *
 * <p>The interface lives separately from the implementation so unit
 * tests of {@link ClusterTraversal} can wire an in-memory edge source
 * directly (no @QuarkusTest required for the pure-traversal cases).
 */
public interface PostReferenceEdgeSource {

    /**
     * For each input post id, the set of input-post neighbours it
     * shares at least one {@code post_reference} edge with (in either
     * direction). Posts with no intra-input neighbours appear as keys
     * mapped to an empty set.
     */
    @NonNull Map<UUID, Set<UUID>> neighborsAmong(@NonNull Collection<UUID> postIds);

    /**
     * Production implementation backed by JDBC against the V29
     * {@code post_reference} table. One query fetches every edge whose
     * endpoints both lie in {@code postIds}; the result is folded into
     * a symmetric adjacency map.
     */
    @ApplicationScoped
    class Jdbc implements PostReferenceEdgeSource {

        @Inject
        DataSource dataSource;

        @Override
        public @NonNull Map<UUID, Set<UUID>> neighborsAmong(@NonNull Collection<UUID> postIds) {
            Map<UUID, Set<UUID>> adjacency = new HashMap<>();
            for (UUID id : postIds) {
                adjacency.put(id, new LinkedHashSet<>());
            }
            if (postIds.isEmpty()) {
                return adjacency;
            }
            final String sql =
                "SELECT from_post, to_post FROM post_reference "
                    + " WHERE from_post = ANY(?) AND to_post = ANY(?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                UUID[] idArray = postIds.toArray(new UUID[0]);
                Array sqlArray = conn.createArrayOf("uuid", idArray);
                ps.setArray(1, sqlArray);
                ps.setArray(2, sqlArray);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID from = (UUID) rs.getObject(1);
                        UUID to = (UUID) rs.getObject(2);
                        // from/to are drawn from the query, whose WHERE clause
                        // restricts both to postIds — every such key was seeded
                        // into adjacency above, so the get() is never null.
                        Objects.requireNonNull(adjacency.get(from)).add(to);
                        Objects.requireNonNull(adjacency.get(to)).add(from);
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException(
                    "PostReferenceEdgeSource.Jdbc.neighborsAmong failed", e);
            }
            return adjacency;
        }
    }
}
