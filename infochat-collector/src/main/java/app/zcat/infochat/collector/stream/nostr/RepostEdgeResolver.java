package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.collector.eval.TransactionHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves kind-6 repost edges to real {@code post.id} values. A repost
 * edge is written with {@code to_upstream_identifier} = the original
 * event id (verbatim) and {@code to_post} = NULL; per architecture.md
 * §Ingest SPIs the link is resolved "if and when the original event is
 * also seen and stored". Two halves cover both arrival orders:
 *
 * <ul>
 *   <li>{@link #findNostrOriginalPostId} — original arrived first: the
 *     kind-6 handler looks the original up at edge-write time.</li>
 *   <li>{@link #resolveEdgesPointingTo} — repost arrived first: every
 *     successful Nostr persist sweeps the unresolved edges that name
 *     the new post's upstream_identifier.</li>
 * </ul>
 *
 * <p>Resolution is deterministic across relay/source interleaving:
 * {@code ORDER BY fetched_at ASC, id ASC LIMIT 1} makes the lookup
 * first-wins when overlapping-filter sources hold two rows for the same
 * event, and the {@code to_post IS NULL} guard makes the UPDATE
 * first-wins (a resolved edge is never re-pointed). Resolution is
 * status-independent — RAW originals resolve too; READY filtering
 * happens at read (GetReferencesTool).</p>
 */
@ApplicationScoped
public class RepostEdgeResolver {

    @Inject
    DataSource dataSource;

    /**
     * Look up an already-persisted Nostr original by its protocol-level
     * event id. The {@code s.kind = 'nostr'} join prevents a non-Nostr
     * upstream_identifier (e.g. an RSS guid) that happens to equal the
     * event-id hex string from resolving the edge.
     *
     * @param upstreamIdentifier the original event id, verbatim
     * @return the first-seen matching {@code post.id}, or empty when the
     *         original has not been persisted yet
     */
    public Optional<UUID> findNostrOriginalPostId(String upstreamIdentifier) {
        final String sql =
            "SELECT p.id FROM post p JOIN source s ON s.id = p.source_id "
                + "WHERE p.upstream_identifier = ? AND s.kind = 'nostr' "
                + "ORDER BY p.fetched_at ASC, p.id ASC LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, upstreamIdentifier);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of((UUID) rs.getObject(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                "RepostEdgeResolver: original-post lookup failed", e);
        }
    }

    /**
     * Resolve every unresolved repost edge that names the given
     * upstream_identifier as its target. The {@code to_post IS NULL}
     * guard keeps already-resolved edges pointed at their first-seen
     * original (first-wins).
     *
     * @param originalPostId the persisted original's {@code post.id}
     * @param originalUpstreamIdentifier the original event id the edges
     *                                   stored verbatim at write time
     */
    public void resolveEdgesPointingTo(UUID originalPostId, String originalUpstreamIdentifier) {
        TransactionHelper.inTransaction(dataSource, "RepostEdgeResolver", conn -> {
            final String sql =
                "UPDATE post_reference SET to_post = ? "
                    + "WHERE link_type = 'repost' AND to_upstream_identifier = ? "
                    + "AND to_post IS NULL";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, originalPostId);
                ps.setString(2, originalUpstreamIdentifier);
                ps.executeUpdate();
            }
        });
    }
}
