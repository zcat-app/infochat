package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.command.ConfirmStateService;

import java.util.Optional;
import java.util.UUID;

/**
 * Log-silent no-op {@link ConfirmStateService}. All accessor
 * methods return {@code Optional.empty()} / no-op WITHOUT logging
 * into {@link CallLog} — the per-step call-order assertions pin
 * precise sequences that an extra log entry would break, and the
 * empty peek keeps the step 4.5 confirm-cancel sweep silent so
 * dispatch proceeds with no extra outbound.
 */
final class NoopConfirmStateService extends ConfirmStateService {
    @Override
    public Optional<ConfirmStateService.PendingConfirm> peek(UUID actor, ScopeRef scope) {
        return Optional.empty();
    }

    @Override
    public Optional<ConfirmStateService.PendingConfirm> takeAny(UUID actor, ScopeRef scope) {
        return Optional.empty();
    }

    @Override
    public Optional<ConfirmStateService.PendingConfirm> takeMatching(UUID actor, ScopeRef scope, String commandName) {
        return Optional.empty();
    }

    @Override
    public void remember(UUID actor, ScopeRef scope, ConfirmStateService.PendingConfirm pending) {
        // no-op
    }
}
