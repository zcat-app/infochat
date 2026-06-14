package app.zcat.infochat.provider.messaging;

import java.util.UUID;

/**
 * Plain-JUnit collaborator stand-in for {@link ProbationCheck} used by
 * every {@code InboundRouter*Test} that constructs an
 * {@code InboundRouter} directly (outside CDI). The two new
 * {@code @Inject} fields M1-045 adds to {@link InboundRouter}
 * ({@code probationCheck}, {@code commandPermissions}) would be
 * {@code null} at the step-5 splice site, NPEing every pre-existing
 * scenario that reaches step 5; this stand-in fills the slot.
 *
 * <p><b>Log-silent on purpose.</b> Unlike the recording fakes inside
 * each test file (which write into the test's {@code CallLog} to pin
 * per-step ordering), this class records nothing. The pre-existing
 * per-step call-order assertions in scenarios across
 * {@code InboundRouter*Test} (most notably
 * {@code InboundRouterIntakeOrderingTest} scenarios (g) and (h))
 * pin precise sequences that would break if {@code inProbation} or
 * {@code clearIfPromoted} appended spurious entries. The same rule
 * applies to the {@code NoopConfirmStateService} precedent in
 * {@code InboundRouterIntakeOrderingTest}.
 *
 * <p><b>Chosen behavior.</b> {@code inProbation} returns {@code false}
 * and {@code clearIfPromoted} is a no-op, so every pre-existing
 * scenario routes down the not-in-probation arm of step 5. None of
 * the pre-existing scenarios seed {@code probation_until}, so this
 * is the correct shape for their setup.
 */
class NoopProbationCheck extends ProbationCheck {
    @Override
    public boolean inProbation(UUID userId) {
        return false;
    }

    @Override
    public void clearIfPromoted(UUID userId) {
    }
}
