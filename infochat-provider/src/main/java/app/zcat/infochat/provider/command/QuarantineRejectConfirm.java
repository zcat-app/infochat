package app.zcat.infochat.provider.command;

import java.util.UUID;

/**
 * Pending {@code /quarantine reject <id>} confirm payload — the
 * forensic ({@code BENIGN_CLOSED} → {@code REJECTED}) leg only; the
 * routine {@code PENDING} reject is NOT confirm-gated (M1-458).
 *
 * <p>Top-level implementation of
 * {@link ConfirmStateService.PendingConfirm} owned by the
 * {@link QuarantineCommandHandler} consumer. {@link #commandName()} is
 * the {@code quarantine-reject} key the handler passes to
 * {@code takeMatching}; {@link #sweepPrefix()} is the slash-stripped
 * {@code quarantine reject} form the router's step 4.5 sweep matches on
 * so {@code /quarantine reject <id> confirm} is recognized as the
 * confirm body. Mirrors the M1-051 / M1-053 separate-file precedent.</p>
 */
public record QuarantineRejectConfirm(UUID quarantineId)
        implements ConfirmStateService.PendingConfirm {

    @Override
    public String commandName() {
        return "quarantine-reject";
    }

    @Override
    public String sweepPrefix() {
        return "quarantine reject";
    }
}
