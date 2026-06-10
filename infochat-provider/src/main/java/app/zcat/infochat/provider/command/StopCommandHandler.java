package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.chat.InFlightTracker;
import app.zcat.infochat.provider.group.GroupRepository;
import app.zcat.infochat.provider.messaging.CommandHandler;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.user.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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

    @Inject
    BundleLoader bundleLoader;

    @Inject
    CancellationService cancellationService;

    @Inject
    InFlightTracker inFlightTracker;

    @Inject
    ConfirmStateService confirmStateService;

    @Inject
    InboundContext inboundContext;

    @Inject
    GroupRepository groupRepository;

    @Inject
    UserRepository userRepository;

    @Override
    public String name() {
        return "stop";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        Optional<UUID> userId = resolveUserId();
        if (userId.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.REPLY_STOP_NOOP, inboundContext.effectiveLanguage()));
        }

        // Per-(user, scope) cancellation key, mirroring
        // InboundRouter.resolveChatScopeId (D35): DM → (dm, userId),
        // group → (group, groupId). A group with no row (cannot host
        // in-flight chat work) yields the idempotent no-op.
        Optional<ScopeResolution> resolved = resolveScope(scope, userId.get());
        if (resolved.isEmpty()) {
            return reply(scope, bundleLoader.get(BundleKeys.REPLY_STOP_NOOP, inboundContext.effectiveLanguage()));
        }
        String scopeKind = resolved.get().scopeKind();
        UUID scopeId = resolved.get().scopeId();

        boolean cancelledInFlight = cancellationService.cancel(userId.get(), scopeKind, scopeId);

        // /stop is also the cancel verb for pending destructive-command
        // confirmations (spec §/stop).
        Optional<ConfirmStateService.PendingConfirm> cancelledConfirm =
                confirmStateService.takeAny(userId.get(), scope);

        if (cancelledInFlight && cancelledConfirm.isPresent()) {
            String text = MessageFormat.format(
                    bundleLoader.get(BundleKeys.REPLY_STOP_BOTH_CANCELLED, inboundContext.effectiveLanguage()),
                    cancelledConfirm.get().commandName());
            return reply(scope, text);
        }
        if (cancelledInFlight) {
            return reply(scope, bundleLoader.get(BundleKeys.REPLY_STOP_CANCELLED, inboundContext.effectiveLanguage()));
        }
        if (cancelledConfirm.isPresent()) {
            String text = MessageFormat.format(
                    bundleLoader.get(BundleKeys.REPLY_STOP_CONFIRM_CANCELLED, inboundContext.effectiveLanguage()),
                    cancelledConfirm.get().commandName());
            return reply(scope, text);
        }

        return reply(scope, bundleLoader.get(BundleKeys.REPLY_STOP_NOOP, inboundContext.effectiveLanguage()));
    }

    /**
     * Resolve the calling user's id from {@code (adapter, contact_id)}.
     * The sender's contact id is read from {@link InboundContext} so the
     * lookup works in BOTH DM and group scope — the prior version pulled
     * the contact id from {@code ScopeRef.Dm} and returned empty for any
     * non-DM scope, which is the A17 bug that left group chat work
     * uncancellable.
     */
    private Optional<UUID> resolveUserId() {
        return userRepository.resolveUserId(
                inboundContext.adapterName(), inboundContext.senderContactId());
    }

    /**
     * Resolve the {@code (scopeKind, scopeId)} that keys the calling
     * user's in-flight chat work, mirroring
     * {@code InboundRouter.resolveChatScopeId}: DM scope keys on the
     * user's own id; group scope keys on the {@code groups} row's UUID.
     * A group scope with no {@code groups} row returns empty — there is
     * no in-flight work to cancel, so the caller emits the no-op reply.
     */
    private Optional<ScopeResolution> resolveScope(ScopeRef scope, UUID userId) {
        return switch (scope) {
            case ScopeRef.Dm ignored -> Optional.of(new ScopeResolution("dm", userId));
            case ScopeRef.Group group -> groupRepository
                    .findApprovalRow(inboundContext.adapterName(), group.adapterGroupId())
                    .map(row -> new ScopeResolution("group", row.id()));
        };
    }

    private record ScopeResolution(String scopeKind, UUID scopeId) {}

    private OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }
}
