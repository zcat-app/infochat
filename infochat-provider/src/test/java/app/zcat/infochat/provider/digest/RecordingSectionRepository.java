package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.digest.DigestRenderer.RenderedSection;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Recording {@link DigestSectionRepository} stub: captures every
 * {@code replaceSlotSections} call's arguments without touching the DB, and
 * appends to an optional shared call-log so a test can pin cross-stub call
 * order (M1-652's persist-before-deliver property). Used by
 * {@link DigestWorkerTest} and {@link DigestRetryServiceTest}; the read-back
 * methods are overridable for replay seeding.
 */
class RecordingSectionRepository extends DigestSectionRepository {
    private final List<ReplaceCall> replaceCalls = new ArrayList<>();
    private final List<String> callLog;
    private List<RenderedSection> seededSections = List.of();

    /** Captured arguments of one {@code replaceSlotSections} call. */
    record ReplaceCall(UUID groupId, Instant windowStart,
                       List<RenderedSection> sections, Instant now) {}

    RecordingSectionRepository() { this(new ArrayList<>()); }

    /** Share a call-log so cross-stub ordering is observable; appends {@code marker}. */
    RecordingSectionRepository(List<String> sharedCallLog) {
        this.callLog = sharedCallLog;
    }

    List<ReplaceCall> replaceCalls() { return replaceCalls; }

    void mark(String marker) { callLog.add(marker); }

    /** Seed the sections {@code findOrderedSections} returns (replay tests). */
    void seedSections(List<RenderedSection> sections) { this.seededSections = sections; }

    @Override
    public void replaceSlotSections(UUID groupId,
                                    Instant windowStart,
                                    List<RenderedSection> sections,
                                    Instant now) {
        replaceCalls.add(new ReplaceCall(
                groupId, windowStart, List.copyOf(sections), now));
        callLog.add("replace");
    }

    @Override
    public void replaceSlotSections(Connection conn,
                                    UUID groupId,
                                    Instant windowStart,
                                    List<RenderedSection> sections) {
        replaceCalls.add(new ReplaceCall(
                groupId, windowStart, List.copyOf(sections), Instant.now()));
        callLog.add("replace");
    }

    @Override
    public List<RenderedSection> findOrderedSections(UUID groupId, Instant windowStart) {
        return seededSections;
    }

    @Override
    public int pruneExpiredForGroup(UUID groupId, Instant now) {
        return 0;
    }

    @Override
    public int pruneExpiredForGroup(Connection conn, UUID groupId, Instant now) {
        return 0;
    }
}
