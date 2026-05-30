package app.zcat.infochat.provider.summary;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stateless {@link PostReferenceEdgeSource} test helper that reports
 * every input post as having zero neighbours. Used by tests that build
 * a {@link ClusterTraversal} but exercise behaviour that does not
 * depend on the link graph (digest rendering, summary command flow).
 * Public so cross-package tests
 * ({@code app.zcat.infochat.provider.digest.*},
 * {@code app.zcat.infochat.provider.command.*}) can wire it.
 */
public final class EmptyEdgeSource implements PostReferenceEdgeSource {

    @Override
    public Map<UUID, Set<UUID>> neighborsAmong(Collection<UUID> postIds) {
        Map<UUID, Set<UUID>> out = new HashMap<>();
        for (UUID id : postIds) {
            out.put(id, new LinkedHashSet<>());
        }
        return out;
    }
}
