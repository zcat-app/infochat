package app.zcat.infochat.provider.command.asset;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.NonNull;

import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shared handler logic for asset commands ({@code /zcash},
 * {@code /monero}). Parses {@code [sub-verb] [--vs <currency>]},
 * validates against {@link AssetRegistry}, looks up the latest
 * snapshot via {@link AssetSnapshotReader}, and hands the result
 * to {@link AssetReplyRenderer}.
 *
 * <p>The handler path makes ZERO LLM calls and reads ONLY
 * {@code asset_config} + {@code price_snapshot}.</p>
 */
@ApplicationScoped
public class AssetHandler {

    private static final int FUZZY_SUGGESTION_MAX = 3;

    @Inject
    AssetRegistry assetRegistry;

    @Inject
    AssetSnapshotReader snapshotReader;

    @Inject
    AssetReplyRenderer replyRenderer;

    @Inject
    BundleLoader bundleLoader;

    /** CDI-required no-arg constructor. */
    public AssetHandler() {}

    /** Test constructor. */
    AssetHandler(@NonNull AssetRegistry assetRegistry,
                 @NonNull AssetSnapshotReader snapshotReader,
                 @NonNull AssetReplyRenderer replyRenderer,
                 @NonNull BundleLoader bundleLoader) {
        this.assetRegistry = assetRegistry;
        this.snapshotReader = snapshotReader;
        this.replyRenderer = replyRenderer;
        this.bundleLoader = bundleLoader;
    }

    /**
     * Handles an asset command invocation.
     *
     * @param assetName the asset name (e.g. "zcash")
     * @param scope     the originating scope
     * @param rawText   the full post-normalization command text (e.g. "/zcash kraken --vs eur")
     */
    public @NonNull OutboundMessage handle(@NonNull String assetName,
                                           @NonNull ScopeRef scope,
                                           @NonNull String rawText) {
        AssetRegistry.AssetEntry asset = assetRegistry.getAsset(assetName);
        if (asset == null) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_ASSET_NOT_CONFIGURED),
                    assetName, ""));
        }

        ParsedArgs args = parseArgs(rawText);
        List<String> enabledSubVerbs = asset.enabledSubVerbNames();

        // Resolve sub-verb
        String subVerb;
        if (args.subVerb == null) {
            // Bare invocation — resolve default
            AssetRegistry.SubVerbEntry defaultSv = asset.defaultSubVerb();
            if (defaultSv == null) {
                return reply(scope, MessageFormat.format(
                        bundleLoader.get(BundleKeys.ERROR_ASSET_NOT_CONFIGURED),
                        assetName, String.join(", ", enabledSubVerbs)));
            }
            if (!defaultSv.enabled()) {
                return reply(scope, MessageFormat.format(
                        bundleLoader.get(BundleKeys.ERROR_ASSET_DEFAULT_DISABLED),
                        assetName, String.join(", ", enabledSubVerbs)));
            }
            subVerb = defaultSv.subVerb();
        } else {
            // Explicit sub-verb
            AssetRegistry.SubVerbEntry svEntry = asset.findSubVerb(args.subVerb);
            if (svEntry == null) {
                // Unknown sub-verb — fuzzy suggestion
                List<String> allSubVerbs = asset.subVerbs().stream()
                        .map(AssetRegistry.SubVerbEntry::subVerb)
                        .toList();
                List<String> suggestions = fuzzySuggest(args.subVerb, allSubVerbs, FUZZY_SUGGESTION_MAX);
                String bestMatch = suggestions.isEmpty() ? "" : suggestions.getFirst();
                return reply(scope, MessageFormat.format(
                        bundleLoader.get(BundleKeys.ERROR_ASSET_UNKNOWN_SUB_VERB),
                        args.subVerb, bestMatch, assetName, String.join(", ", allSubVerbs)));
            }
            if (!svEntry.enabled()) {
                return reply(scope, MessageFormat.format(
                        bundleLoader.get(BundleKeys.ERROR_ASSET_SUB_VERB_NOT_ENABLED),
                        args.subVerb, assetName, String.join(", ", enabledSubVerbs)));
            }
            subVerb = svEntry.subVerb();
        }

        // Resolve vs currency
        String vsCurrency = args.vsCurrency;
        if (vsCurrency == null) {
            AssetRegistry.SubVerbEntry svEntry = asset.findSubVerb(subVerb);
            vsCurrency = svEntry != null ? svEntry.defaultQuoteCurrency() : "usd";
        } else {
            List<String> supported = asset.supportedVsCurrencies();
            if (!supported.contains(vsCurrency)) {
                List<String> suggestions = fuzzySuggest(vsCurrency, supported, FUZZY_SUGGESTION_MAX);
                String bestMatch = suggestions.isEmpty() ? "" : suggestions.getFirst();
                return reply(scope, MessageFormat.format(
                        bundleLoader.get(BundleKeys.ERROR_ASSET_UNSUPPORTED_QUOTE_CURRENCY),
                        vsCurrency, bestMatch, assetName, String.join(", ", supported)));
            }
        }

        // Look up snapshot
        AssetSnapshotReader.SnapshotResult result = snapshotReader.readLatest(assetName, subVerb, vsCurrency);
        if (result == null) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_ASSET_NO_DATA),
                    assetName, subVerb));
        }

        // Build attribution URL
        AssetRegistry.SubVerbEntry svEntry = asset.findSubVerb(subVerb);
        String attributionUrl = svEntry != null ? svEntry.attributionUrl() : "";

        return reply(scope, replyRenderer.render(result, asset.displayName(), attributionUrl));
    }

    /** Parses {@code /<asset> [sub-verb] [--vs <currency>]} from the raw command text. */
    static ParsedArgs parseArgs(@NonNull String rawText) {
        String[] tokens = rawText.trim().split("\\s+");
        String subVerb = null;
        String vsCurrency = null;

        int i = 1; // skip the command name (e.g. "/zcash")
        while (i < tokens.length) {
            if ("--vs".equals(tokens[i]) && i + 1 < tokens.length) {
                vsCurrency = tokens[i + 1].toLowerCase();
                i += 2;
            } else if (!tokens[i].startsWith("--")) {
                if (subVerb == null) {
                    subVerb = tokens[i].toLowerCase();
                }
                i++;
            } else {
                i++;
            }
        }
        return new ParsedArgs(subVerb, vsCurrency);
    }

    record ParsedArgs(@org.jspecify.annotations.Nullable String subVerb,
                      @org.jspecify.annotations.Nullable String vsCurrency) {}

    private static List<String> fuzzySuggest(String supplied, List<String> vocabulary, int max) {
        record Scored(String name, int shared) {}
        List<Scored> scored = new ArrayList<>(vocabulary.size());
        for (String v : vocabulary) {
            scored.add(new Scored(v, sharedPrefixLength(supplied, v)));
        }
        scored.sort((a, b) -> {
            int cmp = Integer.compare(b.shared, a.shared);
            return cmp != 0 ? cmp : a.name.compareTo(b.name);
        });
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(max, scored.size()); i++) {
            out.add(scored.get(i).name);
        }
        return out;
    }

    private static int sharedPrefixLength(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    private static OutboundMessage reply(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }
}
