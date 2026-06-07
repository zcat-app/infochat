package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.provider.group.GroupApprovalCheck;

import java.util.UUID;

/**
 * Plain-JUnit collaborator stand-in for {@link GroupApprovalCheck} used
 * by every {@code InboundRouter*Test} that constructs an
 * {@code InboundRouter} directly (outside CDI). The new
 * {@code @Inject GroupApprovalCheck} field M1-112 adds to
 * {@code InboundRouter} would be {@code null} at the step-3.5 splice
 * site for tests that don't wire it; the {@code != null} guard on the
 * router's step-3.5 branch lets such tests skip the gate entirely. For
 * tests that DO need step-3.5 to fire (chat-mode IT, ordering tests),
 * this stand-in plugs the slot with the always-approved decision so
 * the rest of the dispatch flows unchanged.
 *
 * <p><b>Log-silent on purpose.</b> Unlike the recording fakes inside
 * each test file (which write into the test's {@code CallLog} to pin
 * per-step ordering), this class records nothing. The pre-existing
 * per-step call-order assertions in scenarios across
 * {@code InboundRouter*Test} (most notably
 * {@code InboundRouterIntakeOrderingTest} scenario (h) and
 * {@code InboundRouterProbationOrderingTest} scenario (g)) pin precise
 * sequences that would break if {@code check} appended spurious entries.
 * The recording subclass lives in those test files only. The same rule
 * applies to the {@link NoopProbationCheck} precedent.</p>
 *
 * <p><b>Chosen behavior.</b> {@code check} returns
 * {@link GroupApprovalCheck.Outcome.Approved} so every pre-existing
 * scenario flows down the step-4 ban-check arm of the splice. None of
 * the pre-existing scenarios seed pending or rejected approval_status,
 * so the always-approved shape matches their setup.</p>
 */
class NoopGroupApprovalCheck extends GroupApprovalCheck {
    @Override
    public Outcome check(String adapter,
                                  String upstreamGroupId,
                                  UUID activatorUserId,
                                  String activatorRedactedContactId) {
        return new Outcome.Approved();
    }
}
