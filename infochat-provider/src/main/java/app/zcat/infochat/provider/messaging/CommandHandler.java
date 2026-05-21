package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import org.jspecify.annotations.NonNull;

/**
 * Provider-side dispatch contract for one slash-prefixed command.
 * {@link InboundRouter}'s slash-prefix branch resolves the matching
 * implementation by name via CDI {@code Instance<CommandHandler>}
 * discovery and invokes {@link #handle}.
 *
 * <p>v1 ships zero implementations in this subticket; M1-035c adds
 * {@code HelpCommandHandler} as the first {@code @ApplicationScoped
 * CommandHandler} bean. Until M1-035c lands the slash-prefix lookup
 * resolves to an empty set and every slash command falls through to
 * the unknown-command reply — which is the correct spec'd behavior
 * for an unknown command.</p>
 *
 * <p>The interface is intentionally minimal: a {@link #name()} accessor
 * (the literal command name without the leading slash; e.g.
 * {@code "help"}) and a {@link #handle} entry point that receives the
 * already-normalized inbound text and the originating {@link ScopeRef}.
 * Argument parsing is each command's concern; the router does NOT
 * pre-tokenize because future commands have heterogeneous argument
 * shapes (positional tags, named flags, quoted strings).</p>
 *
 * <p>M1-035c's {@code HelpCommandHandler} implements this interface
 * additively — adding fields or methods here without breaking
 * downstream implementations requires only that those additions are
 * default methods or new contract that M1-035c is authored against.</p>
 */
public interface CommandHandler {

    /**
     * The literal command name this handler binds to, without the
     * leading slash. {@code InboundRouter} performs an exact match
     * against the first whitespace-delimited token of the
     * already-normalized inbound body (the body has its leading
     * slash stripped before the match).
     */
    String name();

    /**
     * Build the outbound reply for one inbound command invocation.
     * The router has already normalized the body, dropped empties,
     * and confirmed the body begins with a slash; the handler sees
     * the post-normalization body verbatim.
     *
     * @param scope   the originating scope (DM or group).
     * @param rawText the post-normalization inbound body including the
     *                leading slash and any arguments.
     * @return the outbound reply to send via the originating adapter.
     */
    OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText);
}
