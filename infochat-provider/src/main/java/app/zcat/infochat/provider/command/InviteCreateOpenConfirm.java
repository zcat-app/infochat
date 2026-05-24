package app.zcat.infochat.provider.command;

/**
 * Pending {@code /invite create --adapter <name> --open} confirm payload.
 *
 * <p>Top-level implementation of
 * {@link ConfirmStateService.PendingConfirm} owned by the
 * {@link InviteCommandHandler} consumer. The
 * {@link #commandName()} key is colon-namespaced ({@code invite:create:open})
 * to disambiguate from {@link InviteRevokeConfirm}; the
 * {@link #sweepPrefix()} mirrors the user-visible {@code /invite create
 * --open} form (slash-stripped) the router's step 4.5 sweep matches on.</p>
 */
public record InviteCreateOpenConfirm(String targetAdapter)
        implements ConfirmStateService.PendingConfirm {

    @Override
    public String commandName() {
        return "invite:create:open";
    }

    @Override
    public String sweepPrefix() {
        return "invite create --open";
    }
}
