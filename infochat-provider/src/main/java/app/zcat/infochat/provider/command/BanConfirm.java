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
 *
 * <p>{@code intentRequestId} carries the prompt-leg BAN_INTENT audit
 * row's {@code request_id} across to the confirm leg, which reuses it
 * for the BAN + INVITE_REVOKE effect rows — one request-id mint per
 * prompt→confirm pair, so intent and effect correlate.</p>
 */
public record BanConfirm(String targetContactId, @Nullable String reason,
                         String intentRequestId)
        implements ConfirmStateService.PendingConfirm {

    @Override
    public String commandName() {
        return "ban";
    }

    @Override
    public String sweepPrefix() {
        return "ban";
    }

    @Override
    public boolean matchesRetypedArguments(String retypedArguments) {
        // Case-SENSITIVE, unlike the UUID-valued payloads: a contact id
        // is an opaque adapter-minted identity (a SimpleX queue address,
        // a Signal ACI), so folding case here would be a widening of
        // which identity a ban confirm accepts. A retyped `--reason` is
        // not accepted — the stored reason is authoritative and the
        // prompt instructs the bare `/ban confirm`.
        return retypedArguments.equals(targetContactId);
    }
}
