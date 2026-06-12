package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.provider.group.GroupRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Records {@link GroupRepository#markRemoved(UUID)} calls so
 * {@link OutboundDelivery}'s bot-removed counter logic can be asserted
 * without a database. The {@code null} datasource is never touched because
 * {@code markRemoved} is overridden.
 */
final class RecordingGroupRepository extends GroupRepository {

    final List<UUID> removed = new ArrayList<>();

    RecordingGroupRepository() {
        super(null);
    }

    @Override
    public void markRemoved(UUID groupId) {
        removed.add(groupId);
    }
}
