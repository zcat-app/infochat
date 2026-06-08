package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.messaging.CommandHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Implements {@code /get-sources} per {@code docs/spec/commands.md}
 * §Discovery + design notes {@code docs/design/03-commands.md}
 * §{@code /get-sources}: an alias of {@code /list-sources} accepting the
 * same flags <b>except {@code --all}</b> (and therefore not
 * {@code --include-deleted}, which requires {@code --all}). Available to
 * any non-banned user; read-only, scope-filtered.
 *
 * <p>The alias is implemented by stripping the two admin-only flags from
 * the inbound body and delegating to {@link ListSourcesCommandHandler},
 * so {@code /get-sources [--page N]} returns exactly the caller-scoped
 * listing {@code /list-sources [--page N]} returns. A caller passing
 * {@code --all} or {@code --include-deleted} has the flag silently
 * dropped (the spec defines {@code /get-sources} as the un-privileged
 * subset — the flag is not part of this command's identity), so the
 * privileged deployment-wide enumeration is unreachable through this
 * name. {@code --page} and {@code --page=N} pass through unchanged.</p>
 */
@ApplicationScoped
public class GetSourcesCommandHandler implements CommandHandler {

    @Inject
    ListSourcesCommandHandler listSourcesCommandHandler;

    @Override
    public String name() {
        return "get-sources";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        return listSourcesCommandHandler.handle(scope, stripAdminFlags(rawText));
    }

    /**
     * Drop the {@code --all} and {@code --include-deleted} tokens from
     * {@code rawText}, preserving every other token (the command name and
     * any {@code --page} flag/value). {@link ListSourcesCommandHandler}
     * ignores the leading command-name token, so the substituted name
     * ({@code /get-sources}) does not affect parsing.
     */
    static String stripAdminFlags(String rawText) {
        String[] tokens = rawText.trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String token : tokens) {
            if (token.equals("--all") || token.equals("--include-deleted")) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(token);
        }
        return out.toString();
    }
}
