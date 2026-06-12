package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.provider.group.GroupRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Records {@link GroupRepository#markRemovedAudited(UUID, String)} calls so
 * {@link OutboundDelivery}'s bot-removed counter logic can be asserted without
 * a database or an audit writer. The {@code null} datasource and the
 * uninjected audit writer are never touched because {@code markRemovedAudited}
 * is overridden.
 */
final class RecordingGroupRepository extends GroupRepository {

    final List<UUID> removed = new ArrayList<>();

    RecordingGroupRepository() {
        super(null);
    }

    @Override
    public void markRemovedAudited(UUID groupId, String adapter) {
        removed.add(groupId);
    }
}
