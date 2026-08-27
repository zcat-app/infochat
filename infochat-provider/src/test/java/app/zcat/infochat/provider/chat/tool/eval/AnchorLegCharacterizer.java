package app.zcat.infochat.provider.chat.tool.eval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure sibling-pair derivations for the M1-945 characterization record (same raw-recall definition as the scorer). */
final class AnchorLegCharacterizer {

    private AnchorLegCharacterizer() {
    }

    /** Count of uids present in both windows, order-independent. */
    static int windowOverlap(List<String> windowA, List<String> windowB) {
        Set<String> b = new HashSet<>(windowB);
        return (int) windowA.stream().filter(b::contains).count();
    }

    /** 1-based ranks at which expected uids appear in the returned window, ascending. */
    static List<Integer> hitRanks(List<String> returned, List<String> expected) {
        Set<String> targets = new HashSet<>(expected);
        List<Integer> ranks = new ArrayList<>();
        for (int i = 0; i < returned.size(); i++) {
            if (targets.contains(returned.get(i))) {
                ranks.add(i + 1);
            }
        }
        return ranks;
    }

    /** Raw recall: expected-uid hits over |E|; an empty expected set is a contract violation. */
    static double rawRecall(List<String> returned, List<String> expected) {
        if (expected.isEmpty()) {
            throw new IllegalArgumentException(
                    "raw recall over an empty expected set is undefined, never a silent 0");
        }
        return (double) hitRanks(returned, expected).size() / expected.size();
    }

    /** Per-pair raw-recall delta: anchored window's recall minus the sibling's. */
    static double rawRecallDelta(List<String> anchoredWindow, List<String> siblingWindow,
                                 List<String> expected) {
        return rawRecall(anchoredWindow, expected) - rawRecall(siblingWindow, expected);
    }
}
