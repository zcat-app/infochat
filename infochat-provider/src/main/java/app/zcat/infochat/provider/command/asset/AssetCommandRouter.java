package app.zcat.infochat.provider.command.asset;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.messaging.CommandHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.NonNull;

/**
 * Houses the two per-asset {@link CommandHandler} beans for v1:
 * {@code /zcash} and {@code /monero}. Each bean's {@link #name()}
 * returns the asset name, matching InboundRouter's exact-match
 * dispatch at handleSlash line 562.
 *
 * <p>Both delegate to the shared {@link AssetHandler} for parsing,
 * validation, snapshot lookup, and reply rendering.</p>
 */
public final class AssetCommandRouter {

    private AssetCommandRouter() {}

    @ApplicationScoped
    public static class ZcashCommandHandler implements CommandHandler {

        @Inject
        AssetHandler assetHandler;

        @Override
        public String name() {
            return "zcash";
        }

        @Override
        public OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
            return assetHandler.handle("zcash", scope, rawText);
        }
    }

    @ApplicationScoped
    public static class MoneroCommandHandler implements CommandHandler {

        @Inject
        AssetHandler assetHandler;

        @Override
        public String name() {
            return "monero";
        }

        @Override
        public OutboundMessage handle(@NonNull ScopeRef scope, @NonNull String rawText) {
            return assetHandler.handle("monero", scope, rawText);
        }
    }
}
