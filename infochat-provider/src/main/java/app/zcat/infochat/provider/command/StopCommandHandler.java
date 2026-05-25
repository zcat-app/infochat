package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.chat.InFlightTracker;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.NonNull;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@code /stop} per docs/spec/commands.md §Conversation control.
 * Cancels the calling (user, scope)'s in-flight interruptible request
 * and/or pending destructive-command confirmation. Idempotent when
 * nothing is in flight.
 */
@ApplicationScoped
public class StopCommandHandler implements CommandHandler {

    private static final String SELECT_USER_ID =
            "SELECT id FROM users WHERE adapter = ? AND contact_id = ?";

    @Inject
    BundleLoader bundleLoader;

    @Inject
    DataSource dataSource;

    @Inject
    CancellationService cancellationService;

    @Inject
    InFlightTracker inFlightTracker;

    @Inject
    ConfirmStateService confirmStateService;

    @Inject
    InboundContext inboundContext;

    @Override
    public @NonNull String name() {
        return "stop";
    }

    @Override
    public @NonNull OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
        Optional<UUID> userId = resolveUserId(scope);
        if (userId.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.REPLY_STOP_NOOP));
        }

        // v1 DM scope: scopeKind = "dm", scopeId = userId
        String scopeKind = "dm";
        UUID scopeId = userId.get();

        boolean cancelledInFlight = cancellationService.cancel(userId.get(), scopeKind, scopeId);

        // /stop is also the cancel verb for pending destructive-command
        // confirmations (spec §/stop).
        Optional<ConfirmStateService.PendingConfirm> cancelledConfirm =
                confirmStateService.takeAny(userId.get(), scope);

        if (cancelledInFlight && cancelledConfirm.isPresent()) {
            String text = MessageFormat.format(
                    bundleLoader.get(BundleKeys.REPLY_STOP_BOTH_CANCELLED),
                    cancelledConfirm.get().commandName());
            return reply(scope, text);
        }
        if (cancelledInFlight) {
            return reply(scope, bundleLoader.get(BundleKeys.REPLY_STOP_CANCELLED));
        }
        if (cancelledConfirm.isPresent()) {
            String text = MessageFormat.format(
                    bundleLoader.get(BundleKeys.REPLY_STOP_CONFIRM_CANCELLED),
                    cancelledConfirm.get().commandName());
            return reply(scope, text);
        }

        return reply(scope, bundleLoader.get(BundleKeys.REPLY_STOP_NOOP));
    }

    private Optional<UUID> resolveUserId(ScopeRef scope) {
        if (!(scope instanceof ScopeRef.Dm dm)) {
            return Optional.empty();
        }
        String adapterName = inboundContext.adapterName();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_USER_ID)) {
            ps.setString(1, adapterName);
            ps.setString(2, dm.contactId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of((UUID) rs.getObject("id"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("StopCommandHandler.resolveUserId failed", e);
        }
    }

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }
}
