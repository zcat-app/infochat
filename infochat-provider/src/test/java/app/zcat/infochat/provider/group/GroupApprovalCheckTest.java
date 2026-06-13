package app.zcat.infochat.provider.group;

import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.messaging.RateCapBucket;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Plain-JUnit coverage for {@link GroupApprovalCheck}. Exercises the
 * per-group reply rate bucket gate that fronts the
 * {@link GroupApprovalService} approval decision.
 *
 * <p>Scenarios mirror acceptance item 10:</p>
 * <ul>
 *   <li>(a) per-group reply rate bucket exhausted → silent drop, service NOT called</li>
 *   <li>(b) bucket not exhausted → service consulted, its outcome returned</li>
 * </ul>
 *
 * <p>Stub strategy: {@link StubGroupRepository} carries a fixed
 * lookup response so the bucket either fires (row present) or is
 * skipped (row absent); {@link RecordingGroupApprovalService} records
 * whether it was consulted and returns a programmable outcome.</p>
 */
class GroupApprovalCheckTest {

    private static final String TEST_ADAPTER = "inmemory";
    private static final String TEST_UPSTREAM = "check-test-upstream";
    private static final UUID TEST_USER_ID = UUID.randomUUID();
    private static final UUID TEST_GROUP_ID = UUID.randomUUID();
    private static final String REDACTED_CONTACT = "abcdef12...wxyz";

    @Test
    void bucketExhaustedReturnsSilentDropWithoutCallingService() {
        // Drain the bucket (cap=3): 3 successful acquires, then the 4th
        // call inside Check returns false → SilentDrop short-circuits
        // BEFORE the service consultation.
        RateCapBucket bucket = bucketWithGroupCap(3);
        bucket.tryAcquireGroupReply(TEST_GROUP_ID);
        bucket.tryAcquireGroupReply(TEST_GROUP_ID);
        bucket.tryAcquireGroupReply(TEST_GROUP_ID);

        RecordingGroupApprovalService service = new RecordingGroupApprovalService(
                new GroupApprovalCheck.Outcome.FixedReply(BundleKeys.GROUP_PENDING));
        GroupApprovalCheck check = checkWith(
                stubRepoWithExistingRow(TEST_GROUP_ID, "pending"),
                bucket,
                service);

        GroupApprovalCheck.Outcome outcome = check.check(
                TEST_ADAPTER, TEST_UPSTREAM, TEST_USER_ID, REDACTED_CONTACT);

        assertInstanceOf(GroupApprovalCheck.Outcome.SilentDrop.class, outcome);
        assertNull(service.lastInvocation,
                "service.evaluate must NOT be called when the bucket is exhausted");
    }

    @Test
    void bucketNotExhaustedDelegatesToServiceAndReturnsItsOutcome() {
        // Fresh bucket has tokens; Check should consume one then delegate
        // to Service. Service returns Approved (existing approved row).
        RateCapBucket bucket = bucketWithGroupCap(3);

        RecordingGroupApprovalService service = new RecordingGroupApprovalService(
                new GroupApprovalCheck.Outcome.Approved(TEST_GROUP_ID));
        GroupApprovalCheck check = checkWith(
                stubRepoWithExistingRow(TEST_GROUP_ID, "approved"),
                bucket,
                service);

        GroupApprovalCheck.Outcome outcome = check.check(
                TEST_ADAPTER, TEST_UPSTREAM, TEST_USER_ID, REDACTED_CONTACT);

        assertInstanceOf(GroupApprovalCheck.Outcome.Approved.class, outcome);
        assertNotNull(service.lastInvocation,
                "service.evaluate must be consulted when the bucket has tokens");
        assertEquals(TEST_ADAPTER, service.lastInvocation.adapter());
        assertEquals(TEST_UPSTREAM, service.lastInvocation.upstreamGroupId());
        assertEquals(TEST_USER_ID, service.lastInvocation.userId());
        assertEquals(REDACTED_CONTACT, service.lastInvocation.redactedContact());
    }

    private static GroupApprovalCheck checkWith(StubGroupRepository repo,
                                                RateCapBucket bucket,
                                                RecordingGroupApprovalService service) {
        GroupApprovalCheck check = new GroupApprovalCheck();
        check.groupRepository = repo;
        check.rateCapBucket = bucket;
        check.groupApprovalService = service;
        return check;
    }

    private static StubGroupRepository stubRepoWithExistingRow(UUID groupId,
                                                               String approvalStatus) {
        return new StubGroupRepository(Optional.of(
                new GroupRepository.GroupApprovalRow(
                        groupId, approvalStatus, UUID.randomUUID(), null)));
    }

    private static RateCapBucket bucketWithGroupCap(int groupReplyCap) {
        // Stubbed bucket: drains by one token per call until the supplied
        // cap is exhausted, then returns false. Avoids reaching across
        // package boundaries into RateCapBucket's package-private test
        // constructor (the group-test package cannot see it).
        return new CountingBucket(groupReplyCap);
    }

    /**
     * Test bucket that overrides {@link RateCapBucket#tryAcquireGroupReply}
     * with a deterministic token-draining counter. The contact-bucket path
     * is not exercised — the new check only consults
     * {@code tryAcquireGroupReply(UUID)}.
     */
    static final class CountingBucket extends RateCapBucket {
        int tokensLeft;

        CountingBucket(int cap) {
            this.tokensLeft = cap;
        }

        @Override
        public boolean tryAcquireGroupReply(UUID groupId) {
            if (tokensLeft > 0) {
                tokensLeft--;
                return true;
            }
            return false;
        }
    }

    /**
     * Stub {@link GroupRepository} returning a fixed
     * {@link GroupRepository.GroupApprovalRow} from
     * {@link #findApprovalRow}. Every other repository method is
     * unimplemented — Check only consults findApprovalRow.
     */
    static final class StubGroupRepository extends GroupRepository {
        private final Optional<GroupApprovalRow> response;

        StubGroupRepository(Optional<GroupApprovalRow> response) {
            super(GroupApprovalServiceTest.noopDataSource());
            this.response = response;
        }

        @Override
        public Optional<GroupApprovalRow> findApprovalRow(
                String adapter, String upstreamGroupId) {
            return response;
        }
    }

    /**
     * Stub {@link GroupApprovalService} that records each {@code evaluate}
     * invocation and returns a programmable outcome. The recording lets
     * the bucket-exhausted scenario prove the service was NOT consulted
     * while the bucket-OK scenario proves it WAS.
     */
    static final class RecordingGroupApprovalService extends GroupApprovalService {
        private final GroupApprovalCheck.Outcome programmedOutcome;
        @org.jspecify.annotations.Nullable Invocation lastInvocation;

        RecordingGroupApprovalService(GroupApprovalCheck.Outcome programmedOutcome) {
            this.programmedOutcome = programmedOutcome;
        }

        @Override
        public GroupApprovalCheck.Outcome evaluate(
                String adapter,
                String upstreamGroupId,
                UUID activatorUserId,
                String activatorRedactedContactId) {
            lastInvocation = new Invocation(
                    adapter, upstreamGroupId, activatorUserId, activatorRedactedContactId);
            return programmedOutcome;
        }

        record Invocation(
                String adapter,
                String upstreamGroupId,
                UUID userId,
                String redactedContact) {}
    }
}
