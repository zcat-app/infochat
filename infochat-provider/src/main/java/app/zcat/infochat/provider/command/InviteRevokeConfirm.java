package app.zcat.infochat.provider.command;

import java.util.UUID;

/**
 * Pending {@code /invite revoke <code>} confirm payload.
 *
 * <p>Top-level implementation of
 * {@link ConfirmStateService.PendingConfirm} owned by the
 * {@link InviteCommandHandler} consumer. The {@link #commandName()} key
 * is the colon-namespaced {@code invite:revoke} the handler passes to
 * {@code takeMatching}; the {@link #sweepPrefix()} is the slash-stripped
 * {@code invite revoke} form the router's step 4.5 sweep matches on.</p>
 */
public record InviteRevokeConfirm(UUID code)
        implements ConfirmStateService.PendingConfirm {

    @Override
    public String commandName() {
        return "invite:revoke";
    }

    @Override
    public String sweepPrefix() {
        return "invite revoke";
    }
}
