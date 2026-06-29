package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.provider.command.AssetCommandFamilyOracle;
import app.zcat.infochat.provider.command.CommandPermissions;
import app.zcat.infochat.provider.command.asset.AssetRegistry;

/**
 * Plain-JUnit collaborator stand-in for {@link CommandPermissions}
 * used by every {@code InboundRouter*Test} that constructs an
 * {@code InboundRouter} directly (outside CDI). Companion to
 * {@link NoopProbationCheck}: see that class's javadoc for the
 * full rationale (null @Inject field + log-silent requirement).
 *
 * <p><b>Chosen behavior.</b> {@code allowedDuringProbation} returns
 * {@code true} for every command. This means: if a test happens to
 * route through the in-probation arm of step 5 (no pre-existing
 * test currently does — they all use the not-in-probation arm via
 * {@link NoopProbationCheck#inProbation} returning {@code false}),
 * dispatch proceeds normally. This is the safe choice: returning
 * {@code false} here would mean every in-probation request short-
 * circuits to the {@code error.probation.blocked} reply, which is
 * the wrong default for tests that don't care about the gate.
 */
class NoopCommandPermissions extends CommandPermissions {
    NoopCommandPermissions() {
        super(new AssetCommandFamilyOracle(new AssetRegistry()));
    }

    @Override
    public boolean allowedDuringProbation(String slashCommand) {
        return true;
    }
}
