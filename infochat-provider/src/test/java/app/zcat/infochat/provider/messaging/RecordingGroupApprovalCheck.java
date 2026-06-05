package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.provider.group.GroupApprovalCheck;

import java.util.UUID;

/**
 * Records {@code groupApprovalCheck.check}. Returns
 * {@link GroupApprovalCheck.Outcome.Approved} so dispatch falls
 * through to step 4. The recording variant exists because the
 * package-level {@link NoopGroupApprovalCheck} is deliberately
 * log-silent — the per-step call-order assertions pin precise
 * sequences including the step-3.5 entry.
 */
final class RecordingGroupApprovalCheck extends GroupApprovalCheck {
    private final CallLog log;

    RecordingGroupApprovalCheck(CallLog log) {
        this.log = log;
    }

    @Override
    public Outcome check(String adapter, String upstreamGroupId,
                         UUID activatorUserId, String activatorRedactedContactId) {
        log.calls.add("groupApprovalCheck.check");
        return new Outcome.Approved();
    }
}
