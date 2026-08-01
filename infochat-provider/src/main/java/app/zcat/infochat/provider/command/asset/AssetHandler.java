package app.zcat.infochat.provider.command.asset;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.messaging.RateCapBucket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Shared handler logic for asset commands ({@code /zcash},
 * {@code /monero}). Parses {@code [sub-verb] [--vs <currency>]},
 * validates against {@link AssetRegistry}, looks up the latest
 * snapshot via {@link AssetSnapshotReader}, and hands the result
 * to {@link AssetReplyRenderer}.
 *
 * <p>The handler path makes ZERO LLM calls and reads ONLY
 * {@code asset_config} + {@code price_snapshot}. Per design
 * §10.10 the asset commands share the parser-only cheap-command
 * bucket (M1-705): every invocation draws one token BEFORE any
 * snapshot read, and over-cap traffic gets the friendly reject naming
 * the retry delay — never the LLM bucket, never a silent drop.</p>
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

    @Inject
    InboundContext inboundContext;

    @Inject
    RateCapBucket rateCapBucket;

    /** CDI-required no-arg constructor. */
    public AssetHandler() {}

    /**
     * Test constructor for callers that do not exercise the
     * cheap-command bucket (M1-705) — the bucket defaults to a real
     * {@link RateCapBucket} at the {@code @ConfigProperty} defaults via
     * the public {@code (Clock, Settings)} seam, so this overload
     * spares bucket-agnostic tests (e.g. {@code AssetPerSourceCurrencyTest},
     * outside this change's scope) a mechanical signature update.
     */
    AssetHandler(AssetRegistry assetRegistry,
                 AssetSnapshotReader snapshotReader,
                 AssetReplyRenderer replyRenderer,
                 BundleLoader bundleLoader,
                 InboundContext inboundContext) {
        this(assetRegistry, snapshotReader, replyRenderer, bundleLoader, inboundContext,
                new RateCapBucket(java.time.Clock.systemUTC(), RateCapBucket.Settings.defaults()));
    }

    /** Test constructor. */
    AssetHandler(AssetRegistry assetRegistry,
                 AssetSnapshotReader snapshotReader,
                 AssetReplyRenderer replyRenderer,
                 BundleLoader bundleLoader,
                 InboundContext inboundContext,
                 RateCapBucket rateCapBucket) {
        this.assetRegistry = assetRegistry;
        this.snapshotReader = snapshotReader;
        this.replyRenderer = replyRenderer;
        this.bundleLoader = bundleLoader;
        this.inboundContext = inboundContext;
        this.rateCapBucket = rateCapBucket;
    }

    /**
     * Handles an asset command invocation.
     *
     * @param assetName the asset name (e.g. "zcash")
     * @param scope     the originating scope
     * @param rawText   the full post-normalization command text (e.g. "/zcash kraken --vs eur")
     */
    public OutboundMessage handle(String assetName,
                                           ScopeRef scope,
                                           String rawText) {
        String language = inboundContext.effectiveLanguage();

        // Cheap-command bucket (M1-705): asset commands share the
        // parser-only command bucket per design §10.10. Drawn BEFORE
        // any AssetSnapshotReader call so over-cap asset traffic is
        // rejected with the friendly retry-delay reply (design §4.9),
        // not silently dropped. Never the LLM bucket — this path makes
        // zero LLM calls.
        String contactId = scope instanceof ScopeRef.Dm dm
                ? dm.contactId() : inboundContext.senderContactId();
        String adapter = inboundContext.adapterName();
        if (!rateCapBucket.tryAcquireCheapCommand(adapter, contactId)) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_COMMAND_RATE_LIMIT, language),
                    Long.toString(rateCapBucket.cheapCommandRetryAfterSeconds(adapter, contactId))));
        }

        AssetRegistry.AssetEntry asset = assetRegistry.getAsset(assetName);
        if (asset == null) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_ASSET_NOT_CONFIGURED, language),
                    assetName, ""));
        }

        ParsedArgs args = parseArgs(rawText);
        if (args.missingVsValue) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_USAGE_MISSING_ARGUMENT, language),
                    "/" + assetName + " [sub-verb] [--vs <currency>]"));
        }
        List<String> enabledSubVerbs = asset.enabledSubVerbNames();

        // Resolve sub-verb. The pair's configured quote currency is carried out
        // of this block alongside it: it is the only currency the pair can hold
        // data for, so it doubles as the --vs allowlist below.
        String subVerb;
        String availableCurrency;
        if (args.subVerb == null) {
            // Bare invocation — resolve default
            AssetRegistry.SubVerbEntry defaultSv = asset.defaultSubVerb();
            if (defaultSv == null) {
                return reply(scope, MessageFormat.format(
                        bundleLoader.get(BundleKeys.ERROR_ASSET_NOT_CONFIGURED, language),
                        assetName, String.join(", ", enabledSubVerbs)));
            }
            if (!defaultSv.enabled()) {
                return reply(scope, MessageFormat.format(
                        bundleLoader.get(BundleKeys.ERROR_ASSET_DEFAULT_DISABLED, language),
                        assetName, String.join(", ", enabledSubVerbs)));
            }
            subVerb = defaultSv.subVerb();
            availableCurrency = defaultSv.defaultQuoteCurrency();
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
                        bundleLoader.get(BundleKeys.ERROR_ASSET_UNKNOWN_SUB_VERB, language),
                        bestMatch, assetName, String.join(", ", allSubVerbs)));
            }
            if (!svEntry.enabled()) {
                return reply(scope, MessageFormat.format(
                        bundleLoader.get(BundleKeys.ERROR_ASSET_SUB_VERB_NOT_ENABLED, language),
                        args.subVerb, assetName, String.join(", ", enabledSubVerbs)));
            }
            subVerb = svEntry.subVerb();
            availableCurrency = svEntry.defaultQuoteCurrency();
        }

        // Resolve vs currency. Validated against AVAILABILITY (what this pair
        // actually fetches), not against any upstream's CAPABILITY: the
        // collector fetches exactly asset_config.default_quote_currency per
        // (asset, sub_verb) and the provider has no on-demand fetch, so no
        // other currency can ever have a price_snapshot row. Refusing here
        // keeps the no-data reply honest — it can then only mean a genuine
        // missing row, never a currency the deployment cannot serve (M1-671).
        String vsCurrency = args.vsCurrency;
        if (vsCurrency == null) {
            vsCurrency = availableCurrency;
        } else if (!vsCurrency.equals(availableCurrency)) {
            // Suggestion and available-list are the same single value by
            // construction: one pair, one currency.
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_ASSET_UNSUPPORTED_QUOTE_CURRENCY, language),
                    availableCurrency, assetName, availableCurrency));
        }

        // Look up snapshot
        AssetSnapshotReader.SnapshotResult result = snapshotReader.readLatest(assetName, subVerb, vsCurrency);
        if (result == null) {
            return reply(scope, MessageFormat.format(
                    bundleLoader.get(BundleKeys.ERROR_ASSET_NO_DATA, language),
                    assetName, subVerb));
        }

        // Build attribution URL
        AssetRegistry.SubVerbEntry svEntry = asset.findSubVerb(subVerb);
        String attributionUrl = svEntry != null ? svEntry.attributionUrl() : "";

        return reply(scope, replyRenderer.render(result, asset.displayName(), attributionUrl, language));
    }

    /** Parses {@code /<asset> [sub-verb] [--vs <currency>]} from the raw command text. */
    static ParsedArgs parseArgs(String rawText) {
        String[] tokens = rawText.trim().split("\\s+");
        String subVerb = null;
        String vsCurrency = null;

        int i = 1; // skip the command name (e.g. "/zcash")
        while (i < tokens.length) {
            if ("--vs".equals(tokens[i])) {
                if (i + 1 >= tokens.length) {
                    // Value-less --vs: signal a usage error rather than
                    // silently dropping the flag.
                    return new ParsedArgs(subVerb, null, true);
                }
                vsCurrency = tokens[i + 1].toLowerCase(Locale.ROOT);
                i += 2;
            } else if (!tokens[i].startsWith("--")) {
                if (subVerb == null) {
                    subVerb = tokens[i].toLowerCase(Locale.ROOT);
                }
                i++;
            } else {
                i++;
            }
        }
        return new ParsedArgs(subVerb, vsCurrency, false);
    }

    record ParsedArgs(@org.jspecify.annotations.Nullable String subVerb,
                      @org.jspecify.annotations.Nullable String vsCurrency,
                      boolean missingVsValue) {}

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
