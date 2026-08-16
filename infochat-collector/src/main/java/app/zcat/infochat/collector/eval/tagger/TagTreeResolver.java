package app.zcat.infochat.collector.eval.tagger;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic resolution of a validated proposal set to ONE branch of the v2 tag tree (M1-865, decision 2): highest-priority top per {@link #TOP_PRIORITY} (unlisted tops below News, identity branches last), deepest leaf within the branch, emission order breaks ties. No top-rooted branch → identity passthrough (analysis P6). Pure Java — tree and priority order never enter a prompt (analysis P8, D19). */
@ApplicationScoped
public class TagTreeResolver {

    /** Fixed top priority: Sport &gt; Health &gt; Fashion &gt; Culture &gt; Science &gt; Tech &gt; Business &gt; News-last (decision 2). Java-side only. */
    static final List<String> TOP_PRIORITY = List.of(
        "sport", "health", "fashion", "culture", "science", "tech", "business", "news");

    /** {@code stored} = what post.tags receives (the input unchanged on passthrough); {@code losers} = the losing validated leaves in emission order, exposed for M1-868's candidate array. */
    public record Resolution(List<String> stored, List<String> losers) {
    }

    /** One leaf's branch: the root name, whether the root is a top row, the leaf's depth below it. */
    private record Branch(String root, boolean top, int depth) {
        static Branch identity(String leaf) {
            return new Branch(leaf, false, 0);
        }
    }

    /** A leaf with its depth inside its branch, in emission order. */
    private record ScoredLeaf(String leaf, int depth) {
    }

    /** Resolve the post-validate, capped set (emission order) against the tree; the input is never mutated. */
    public Resolution resolve(List<String> validLeaves, Map<String, TagVocabulary.TagNode> tree) {
        if (validLeaves.size() <= 1) {
            return new Resolution(validLeaves, List.of());
        }
        Map<String, Branch> branches = new LinkedHashMap<>();
        Map<String, List<ScoredLeaf>> leavesByBranch = new LinkedHashMap<>();
        for (String leaf : validLeaves) {
            Branch branch = branchOf(leaf, tree);
            branches.putIfAbsent(branch.root(), branch);
            leavesByBranch.computeIfAbsent(branch.root(), k -> new ArrayList<>())
                .add(new ScoredLeaf(leaf, branch.depth()));
        }
        if (branches.values().stream().noneMatch(Branch::top)) {
            // Identity passthrough: no top-rooted branch exists (the
            // pre-seed state), so the whole set is stored as today.
            return new Resolution(validLeaves, List.of());
        }
        Branch winner = null;
        List<ScoredLeaf> winnerLeaves = null;
        for (Map.Entry<String, Branch> e : branches.entrySet()) {
            // Strictly-less keeps the FIRST of equal-ranked branches in
            // encounter order, which is what makes emission order the
            // tiebreak (same discipline for the depth max below).
            if (winner == null || rank(e.getValue()) < rank(winner)) {
                winner = e.getValue();
                winnerLeaves = leavesByBranch.get(e.getKey());
            }
        }
        String winningLeaf = null;
        int winningDepth = -1;
        // validLeaves.size() >= 2 guarantees the winner and its branch's
        // leaf list exist; the final null check documents that invariant.
        if (winnerLeaves != null) {
            for (ScoredLeaf scored : winnerLeaves) {
                if (scored.depth() > winningDepth) {
                    winningLeaf = scored.leaf();
                    winningDepth = scored.depth();
                }
            }
        }
        if (winningLeaf == null) {
            throw new IllegalStateException(
                "TagTreeResolver: a top-rooted branch exists but no winning leaf — unreachable");
        }
        List<String> losers = new ArrayList<>(validLeaves);
        losers.remove(winningLeaf);
        return new Resolution(List.of(winningLeaf), List.copyOf(losers));
    }

    /** Listed tops rank by list order; an unlisted top ranks below News; an identity branch last. */
    private static int rank(Branch branch) {
        if (!branch.top()) {
            return TOP_PRIORITY.size() + 1;
        }
        int index = TOP_PRIORITY.indexOf(branch.root());
        return index >= 0 ? index : TOP_PRIORITY.size();
    }

    /** Walk parent links to the branch root; a parentless/unknown leaf is its own identity branch, and an unknown mid-chain node or cycle (defensive only) degrades to identity. */
    private static Branch branchOf(String leaf, Map<String, TagVocabulary.TagNode> tree) {
        String current = leaf;
        int depth = 0;
        Set<String> seen = new HashSet<>();
        seen.add(leaf);
        while (true) {
            TagVocabulary.TagNode node = tree.get(current);
            if (node == null) {
                return Branch.identity(leaf);
            }
            String parent = node.parent();
            if (parent == null) {
                return current.equals(leaf)
                    ? Branch.identity(leaf)
                    : new Branch(current, node.top(), depth);
            }
            if (!seen.add(parent)) {
                return Branch.identity(leaf);
            }
            current = parent;
            depth++;
        }
    }
}
