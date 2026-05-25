package app.zcat.infochat.provider.command;

/**
 * Pending {@code /forget} confirm payload.
 *
 * <p>{@code /forget} takes no arguments — the purge target is always the
 * calling user in the calling scope — so the record carries no fields.
 * The router's step 4.5 sweep matches {@code /forget confirm} via
 * {@link #sweepPrefix()}; the handler's {@code takeMatching} call keys
 * on {@link #commandName()}.</p>
 */
public record ForgetConfirm()
        implements ConfirmStateService.PendingConfirm {

    @Override
    public String commandName() {
        return "forget";
    }

    @Override
    public String sweepPrefix() {
        return "forget";
    }
}
