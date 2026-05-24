package app.zcat.infochat.provider.command;

/**
 * Pending {@code /unfollow-tag --all} confirm payload.
 *
 * <p>Top-level implementation of
 * {@link ConfirmStateService.PendingConfirm} owned by the
 * {@link UnfollowTagCommandHandler} consumer (not by the service).
 * Field shape, {@link #commandName()}, and {@link #sweepPrefix()} are
 * the wire-level contract the router's step 4.5 sweep and the
 * handler's {@code takeMatching} call both depend on — neither return
 * string may change without coordinated edits in
 * {@link UnfollowTagCommandHandler} and
 * {@link app.zcat.infochat.provider.messaging.InboundRouter}.</p>
 *
 * <p>No fields — the wipe operates on the actor's
 * {@code (scope_kind, scope_id)} which is already part of the
 * confirm-store key. The bulk reset DELETEs every row for that scope
 * and UPDATEs {@code tag_mode='ALL'}; no per-tag detail needs to
 * survive between the prompt and the confirm.</p>
 */
public record UnfollowTagAllConfirm() implements ConfirmStateService.PendingConfirm {

    @Override
    public String commandName() {
        return "unfollow-tag-all";
    }

    @Override
    public String sweepPrefix() {
        // The canonical user-visible confirm form is
        // `/unfollow-tag --all confirm`; sweepPrefix is the slash-stripped
        // prefix InboundRouter.isConfirmShape matches against. With the
        // M1-051 contract `normalized.equals("/" + sweepPrefix + " confirm")`
        // (canonical) or `normalized.startsWith("/" + sweepPrefix + " ")
        //   && normalized.endsWith(" confirm")` (args-retyped relaxation),
        // the prefix MUST embed the literal `--all` token so that the
        // canonical body the user types is recognized as confirm-shape
        // (otherwise the step 4.5 sweep cancels the pending and the
        // confirm-leg dispatch never runs). The commandName key
        // ({@link #commandName()}, "unfollow-tag-all") stays hyphen-
        // separated because that lookup is internal to the handler's
        // takeMatching call and has no router relationship.
        return "unfollow-tag --all";
    }
}
