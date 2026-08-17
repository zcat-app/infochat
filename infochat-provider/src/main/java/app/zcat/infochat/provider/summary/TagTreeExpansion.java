package app.zcat.infochat.provider.summary;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.jspecify.annotations.Nullable;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Read-time subtree expansion over the tag-tree parent link
 * (docs/plan/m1/tick-analysis/tag-tree-taxonomy-v2.md, decision 7).
 */
@ApplicationScoped
public class TagTreeExpansion {

    @Nullable
    @Inject
    DataSource dataSource;

    /** Subselect expanding a scope's followed nodes to their subtree leaf set. */
    public static final String SCOPE_FOLLOWED_LEAVES_SQL =
            "(WITH RECURSIVE subtree(node, kind) AS ("
                    + " SELECT t.name, t.node_kind FROM scope_tag st JOIN tag t ON t.id = st.tag_id"
                    + " WHERE st.scope_kind = ? AND st.scope_id = ?"
                    + " UNION SELECT c.name, c.node_kind FROM tag c JOIN subtree p ON c.parent_name = p.node"
                    + ") SELECT COALESCE(array_agg(DISTINCT node), ARRAY[]::TEXT[]) FROM subtree"
                    + " WHERE kind = 'leaf')";

    /** Subselect expanding validated tag names to their subtree leaf set. */
    public static final String NAMES_EXPANSION_SQL =
            "(WITH RECURSIVE subtree(node, kind) AS ("
                    + " SELECT ft.name, ft.node_kind FROM tag ft WHERE ft.name = ANY(?)"
                    + " UNION SELECT c.name, c.node_kind FROM tag c JOIN subtree p ON c.parent_name = p.node"
                    + ") SELECT COALESCE(array_agg(DISTINCT node), ARRAY[]::TEXT[]) FROM subtree"
                    + " WHERE kind = 'leaf')";

    /** Expand validated node names to their subtree leaf set (absent names fall back to themselves). */
    public List<String> expandNames(Connection conn, List<String> nodeNames) throws SQLException {
        if (nodeNames.isEmpty()) {
            return List.of();
        }
        String sql = "WITH RECURSIVE subtree(node, kind) AS ("
                + " SELECT name, node_kind FROM tag WHERE name = ANY(?)"
                + " UNION SELECT c.name, c.node_kind FROM tag c JOIN subtree p ON c.parent_name = p.node"
                + ") SELECT node, kind FROM subtree";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setArray(1, conn.createArrayOf("TEXT", nodeNames.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                Set<String> leaves = new LinkedHashSet<>();
                Set<String> knownNodes = new HashSet<>();
                while (rs.next()) {
                    String node = rs.getString(1);
                    knownNodes.add(node);
                    if ("leaf".equals(rs.getString(2))) {
                        leaves.add(node);
                    }
                }
                List<String> out = new ArrayList<>(leaves);
                for (String name : nodeNames) {
                    if (!knownNodes.contains(name)) {
                        out.add(name);
                    }
                }
                return out;
            }
        }
    }

    /** Leaf to most-specific followed node (deepest ancestor-or-self wins). */
    public Map<String, String> sectionKeyByLeaf(String scopeKind, UUID scopeId) {
        if (dataSource == null) {
            return Map.of();
        }
        String sql = "WITH RECURSIVE subtree(root, node, depth, kind) AS ("
                + " SELECT t.name, t.name, 0, t.node_kind FROM scope_tag st JOIN tag t ON t.id = st.tag_id"
                + " WHERE st.scope_kind = ? AND st.scope_id = ?"
                + " UNION SELECT s.root, c.name, s.depth + 1, c.node_kind"
                + " FROM tag c JOIN subtree s ON c.parent_name = s.node"
                + ") SELECT node, root FROM subtree s WHERE kind = 'leaf'"
                + " AND NOT EXISTS (SELECT 1 FROM subtree d WHERE d.node = s.node"
                + " AND d.kind = 'leaf' AND d.depth < s.depth)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeKind);
            ps.setObject(2, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, String> out = new HashMap<>();
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getString(2));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("TagTreeExpansion.sectionKeyByLeaf failed", e);
        }
    }
}
