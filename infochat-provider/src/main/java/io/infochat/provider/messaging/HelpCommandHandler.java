package io.infochat.provider.messaging;

import io.infochat.messaging.OutboundMessage;
import io.infochat.messaging.ScopeRef;
import io.infochat.provider.bundle.BundleKeys;
import io.infochat.provider.bundle.BundleLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.UUID;

/**
 * Implements the {@code /help} command per
 * {@code docs/design/03-commands.md} §3.4 (Discovery commands) and
 * {@code docs/spec/commands.md} §Discovery. MVP composes a fixed
 * header followed by the per-command short-help lines for the three
 * commands the MVP exposes — {@code /help}, {@code /add-source},
 * {@code /summary} — drawn entirely from {@link BundleLoader}. No
 * footer in MVP; the {@code help.footer.probation} key lands when
 * T2-A wires the slow-start tier.
 *
 * <p>The CDI-bean-discovery mechanism matches what {@link InboundRouter}
 * shipped at M1-035b: the router injects {@code Instance<CommandHandler>}
 * and looks up by {@link #name()}. Declaring this class
 * {@link ApplicationScoped} alone is sufficient to make it visible —
 * the router does not need a router-side edit when this handler lands.</p>
 *
 * <p>Output is plain text per decision D30: no markdown links, no
 * emoji, no auto-formatting beyond the literal bundle strings. The
 * regression guard against an accidental markdown-link bundle value
 * lives in {@code HelpCommandHandlerTest}.</p>
 */
@ApplicationScoped
public class HelpCommandHandler implements CommandHandler {

    @Inject
    BundleLoader bundleLoader;

    @Override
    public String name() {
        return "help";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        // MVP permitted-set per docs/design/00-mvp.md §4: every non-admin user
        // sees the same three commands (no admin commands ship in T1-E, so
        // the actor-tier filter degenerates to one tier; T2-A adds the
        // probation footer + tier filter, T2-B+ add admin-set keys).
        String body = bundleLoader.get(BundleKeys.HELP_HEADER_DM_USER)
                + "\n"
                + bundleLoader.get(BundleKeys.HELP_CMD_HELP_SHORT)
                + "\n"
                + bundleLoader.get(BundleKeys.HELP_CMD_ADD_SOURCE_SHORT)
                + "\n"
                + bundleLoader.get(BundleKeys.HELP_CMD_SUMMARY_SHORT);
        return new OutboundMessage(scope, body, Instant.now(), UUID.randomUUID().toString());
    }
}
