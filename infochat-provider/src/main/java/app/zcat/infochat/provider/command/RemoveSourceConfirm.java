package app.zcat.infochat.provider.command;

import java.util.UUID;

/**
 * Pending {@code /remove-source <id>} confirm payload.
 *
 * <p>Top-level implementation of
 * {@link ConfirmStateService.PendingConfirm} owned by the
 * {@link RemoveSourceCommandHandler} consumer (not by the service).
 * Mirrors the M1-051 separate-file precedent
 * ({@link BanConfirm}, {@link InviteCreateOpenConfirm},
 * {@link InviteRevokeConfirm}) anticipated by M1-057 out_of_scope
 * clause #4. Field shape, {@link #commandName()}, and
 * {@link #sweepPrefix()} are the wire-level contract the router's
 * step 4.5 sweep and the handler's {@code takeMatching} call both
 * depend on — neither return string may change without coordinated
 * edits in {@link RemoveSourceCommandHandler} and
 * {@link app.zcat.infochat.provider.messaging.InboundRouter}.</p>
 */
public record RemoveSourceConfirm(UUID sourceId)
        implements ConfirmStateService.PendingConfirm {

    @Override
    public String commandName() {
        return "remove-source";
    }

    @Override
    public String sweepPrefix() {
        return "remove-source";
    }
}
