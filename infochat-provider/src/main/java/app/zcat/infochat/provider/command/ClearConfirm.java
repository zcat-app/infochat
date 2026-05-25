package app.zcat.infochat.provider.command;

/**
 * Pending {@code /clear} confirm payload.
 *
 * <p>{@code /clear} takes no arguments — the wipe target is always the
 * calling user's context window in the calling scope — so the record
 * carries no fields. Follows the {@link ForgetConfirm} pattern.</p>
 */
public record ClearConfirm()
        implements ConfirmStateService.PendingConfirm {

    @Override
    public String commandName() {
        return "clear";
    }

    @Override
    public String sweepPrefix() {
        return "clear";
    }
}
