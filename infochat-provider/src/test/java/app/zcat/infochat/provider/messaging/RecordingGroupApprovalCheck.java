package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.provider.group.GroupApprovalCheck;

import java.util.UUID;

/**
 * Records {@code groupApprovalCheck.check} and returns a programmable
 * {@link GroupApprovalCheck.Outcome}. The recording variant exists
 * because the package-level {@link NoopGroupApprovalCheck} is
 * deliberately log-silent — the per-step call-order assertions pin
 * precise sequences including the step-3.5 entry.
 *
 * <p>The outcome is configurable so a scenario can drive either the
 * approved path (carrying the {@code groups.id} the router forwards to
 * step 4.1 as the dispatch scope id) or the silent-drop path (the
 * removed/vanished-group case that, since the router's step-4.1
 * re-read was dropped, now resolves to {@link Outcome.SilentDrop} at
 * step 3.5).</p>
 */
final class RecordingGroupApprovalCheck extends GroupApprovalCheck {
    private final CallLog log;
    private final Outcome outcome;

    RecordingGroupApprovalCheck(CallLog log) {
        this(log, new Outcome.Approved(UUID.randomUUID()));
    }

    RecordingGroupApprovalCheck(CallLog log, Outcome outcome) {
        this.log = log;
        this.outcome = outcome;
    }

    @Override
    public Outcome check(String adapter, String upstreamGroupId,
                         UUID activatorUserId, String activatorRedactedContactId) {
        log.calls.add("groupApprovalCheck.check");
        return outcome;
    }
}
