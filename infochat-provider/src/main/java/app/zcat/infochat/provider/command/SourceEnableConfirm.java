package app.zcat.infochat.provider.command;

import java.util.UUID;

/**
 * Pending {@code /source-enable <id>} confirm payload (the
 * soft-deleted revival path only; the {@code failed}/{@code disabled}
 * paths are NOT confirm-gated). Top-level implementation of
 * {@link ConfirmStateService.PendingConfirm} owned by the
 * {@link SourceEnableCommandHandler} consumer. Mirrors the M1-051
 * separate-file precedent anticipated by M1-057 out_of_scope
 * clause #4.
 */
public record SourceEnableConfirm(UUID sourceId)
        implements ConfirmStateService.PendingConfirm {

    @Override
    public String commandName() {
        return "source-enable";
    }

    @Override
    public String sweepPrefix() {
        return "source-enable";
    }
}
