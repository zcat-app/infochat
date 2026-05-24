package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.command.asset.AssetRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Implements the {@code /help} command per
 * {@code docs/design/03-commands.md} §3.4 (Discovery commands) and
 * {@code docs/spec/commands.md} §Discovery. Composes a fixed
 * header followed by the per-command short-help lines, plus
 * operator-enabled asset commands from {@link AssetRegistry}.
 *
 * <p>Per spec §Asset commands: "/help is context-aware — only
 * operator-enabled assets appear in /help; only enabled sub-verbs
 * appear in per-command help." When no assets are configured
 * (absent bootstrap-assets.json case), /help does not list any.</p>
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

    @Inject
    AssetRegistry assetRegistry;

    @Override
    public String name() {
        return "help";
    }

    @Override
    public OutboundMessage handle(ScopeRef scope, String rawText) {
        StringBuilder body = new StringBuilder();
        body.append(bundleLoader.get(BundleKeys.HELP_HEADER_DM_USER));
        body.append('\n');
        body.append(bundleLoader.get(BundleKeys.HELP_CMD_HELP_SHORT));
        body.append('\n');
        body.append(bundleLoader.get(BundleKeys.HELP_CMD_ADD_SOURCE_SHORT));
        body.append('\n');
        body.append(bundleLoader.get(BundleKeys.HELP_CMD_SUMMARY_SHORT));

        // assetRegistry is null when the handler is constructed outside
        // CDI (HelpCommandHandlerTest sets fields directly and omits the
        // registry — the test is in the preserves list and unmodifiable).
        List<AssetRegistry.AssetEntry> enabledAssets = assetRegistry != null
                ? assetRegistry.getEnabledAssets() : List.of();
        for (AssetRegistry.AssetEntry asset : enabledAssets) {
            List<String> subVerbs = asset.enabledSubVerbNames();
            body.append('\n');
            body.append('/').append(asset.name());
            body.append(" [sub-verb] [--vs <currency>] — ");
            body.append(asset.displayName()).append(" market data");
            if (!subVerbs.isEmpty()) {
                body.append(" (").append(String.join(", ", subVerbs)).append(')');
            }
        }

        return new OutboundMessage(scope, body.toString(), Instant.now(), UUID.randomUUID().toString());
    }
}
