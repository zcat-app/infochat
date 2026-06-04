package app.zcat.infochat.provider.command;

import org.jspecify.annotations.Nullable;

/**
 * Pending {@code /ban <contact> [--reason ...]} confirm payload.
 *
 * <p>Top-level implementation of
 * {@link ConfirmStateService.PendingConfirm} owned by the
 * {@link BanCommandHandler} consumer (not by the service). Field shape,
 * {@link #commandName()}, and {@link #sweepPrefix()} are the wire-level
 * contract the router's step 4.5 sweep and the handler's
 * {@code takeMatching} call both depend on — neither return string may
 * change without coordinated edits in {@link BanCommandHandler} and
 * {@link app.zcat.infochat.provider.messaging.InboundRouter}.</p>
 */
public record BanConfirm(String targetContactId, @Nullable String reason)
        implements ConfirmStateService.PendingConfirm {

    @Override
    public String commandName() {
        return "ban";
    }

    @Override
    public String sweepPrefix() {
        return "ban";
    }
}
