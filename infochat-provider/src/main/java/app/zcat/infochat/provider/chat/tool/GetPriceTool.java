package app.zcat.infochat.provider.chat.tool;

import app.zcat.infochat.provider.chat.ChatToolRegistry;
import app.zcat.infochat.provider.command.asset.AssetRegistry;
import app.zcat.infochat.provider.command.asset.AssetSnapshotReader;
import org.jspecify.annotations.Nullable;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static app.zcat.infochat.provider.chat.tool.SearchPostsTool.jsonStr;

/**
 * Latest stored price for an operator-configured asset, as a chat tool — reusing the asset commands' single read path end to end (AssetRegistry exact-key pair resolution; AssetSnapshotReader's SQL row choice, freshness verdict and TTL cache). The reader opens its own connection, so nothing is armed via CancellationService: the read is the same sub-ms indexed latest-row lookup the un-armed command path performs. Price data is deployment-global operator config — no (user, scope) world predicate applies and the result never feeds the feed-post provenance count (commands.md §Asset commands).
 */
@ApplicationScoped
public class GetPriceTool implements ChatToolRegistry.ChatTool {

    private final AssetRegistry assetRegistry;
    private final AssetSnapshotReader snapshotReader;
    private final Clock clock;

    @Inject
    public GetPriceTool(AssetRegistry assetRegistry,
                        AssetSnapshotReader snapshotReader,
                        Clock clock) {
        this.assetRegistry = assetRegistry;
        this.snapshotReader = snapshotReader;
        this.clock = clock;
    }

    @Override
    public String execute(UUID userId, String scopeKind,
                          UUID scopeId, Map<String, Object> args) {
        String assetName = (String) args.get("asset");
        if (assetName == null) {
            throw new IllegalArgumentException("Missing required parameter: asset");
        }
        AssetRegistry.AssetEntry asset = assetRegistry.getAsset(assetName);
        if (asset == null) {
            // Enumerates the configured names so the model self-corrects
            // within the turn (natural-language normalization, e.g. "xmr",
            // is the model's job — the tool resolves exact names only).
            throw new IllegalArgumentException(
                "Unknown asset: " + assetName + ". Enabled assets: "
                    + String.join(", ", assetRegistry.getEnabledAssetNames()));
        }
        AssetRegistry.SubVerbEntry defaultPair = asset.defaultSubVerb();
        if (defaultPair == null || !defaultPair.enabled()) {
            throw new IllegalArgumentException(
                "No enabled default pair for " + assetName + ". Enabled sub-verbs: "
                    + String.join(", ", asset.enabledSubVerbNames()));
        }
        // Availability, not capability: one pair holds exactly one quote
        // currency, so any other value can never have a row (M1-671).
        String vsCurrency = (String) args.get("vs_currency");
        if (vsCurrency == null) {
            vsCurrency = defaultPair.defaultQuoteCurrency();
        } else if (!vsCurrency.equals(defaultPair.defaultQuoteCurrency())) {
            throw new IllegalArgumentException(
                "Unsupported vs_currency: " + vsCurrency + ". Only "
                    + defaultPair.defaultQuoteCurrency() + " is available for " + assetName);
        }
        AssetSnapshotReader.SnapshotResult result =
            snapshotReader.readLatest(assetName, defaultPair.subVerb(), vsCurrency);
        if (result == null) {
            throw new IllegalArgumentException(
                "No price data for " + assetName + "/" + defaultPair.subVerb());
        }
        AssetSnapshotReader.Snapshot snapshot = result.snapshot();
        return "{\"asset\":" + jsonStr(assetName)
            + ",\"name\":" + jsonStr(asset.displayName())
            + ",\"source\":" + jsonStr(snapshot.subVerb())
            + ",\"vs_currency\":" + jsonStr(snapshot.vsCurrency())
            + ",\"price\":" + num(snapshot.price())
            + ",\"volume_24h\":" + num(snapshot.volume24h())
            + ",\"high_24h\":" + num(snapshot.high24h())
            + ",\"low_24h\":" + num(snapshot.low24h())
            + ",\"change_1h_pct\":" + num(snapshot.change1hPct())
            + ",\"change_24h_pct\":" + num(snapshot.change24hPct())
            + ",\"change_7d_pct\":" + num(snapshot.change7dPct())
            + ",\"captured_at\":\"" + snapshot.capturedAt() + "\""
            + ",\"age_seconds\":"
            + Duration.between(snapshot.capturedAt(), clock.instant()).toSeconds()
            + ",\"stale\":" + result.stale()
            + ",\"source_url\":" + jsonStr(defaultPair.attributionUrl())
            + "}";
    }

    // Absent numerics emit null, never invented zeros (the AssetReplyRenderer rule).
    private static String num(@Nullable BigDecimal value) {
        return value == null ? "null" : value.toPlainString();
    }
}
