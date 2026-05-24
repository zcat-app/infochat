package app.zcat.infochat.collector.bootstrap;

import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * One entry in {@code bootstrap-assets.json} per the schema in
 * {@code docs/design/10-asset-commands.md} §10.6. The parser
 * ({@link BootstrapAssetsParser}) deserializes the top-level
 * {@code assets[]} array into a {@code List<BootstrapAssetsEntry>}
 * and applies post-parse semantic validation
 * ({@code default_sub_verb} must appear in {@code sub_verbs[].id})
 * before the loader sees the records.
 *
 * <p>{@code id} is the lowercase asset slug used as the slash-command
 * verb (e.g. {@code zcash} → {@code /zcash}); {@code ticker} is the
 * uppercase exchange ticker (e.g. {@code ZEC}) consumed by the
 * Bitfinex attribution URL template per §10.7.
 *
 * <p>{@code defaultQuoteCurrency} is propagated by the parser from
 * the top-level {@code default_vs} field of the document. Every
 * row the loader writes carries the same value in v1 (per-asset
 * overrides of the document-level quote default are a v2 candidate);
 * threading it onto the entry keeps the loader purely entry-driven.
 *
 * <p>{@code supportedVs} is the closed list of quote-currency strings
 * the operator has enabled for this asset (D33); future per-scope
 * preference (M1-054) does not extend this set.
 */
public record BootstrapAssetsEntry(
    @NonNull String id,
    @NonNull String displayName,
    @NonNull String ticker,
    @NonNull String defaultSubVerb,
    @NonNull List<SubVerb> subVerbs,
    @NonNull List<String> supportedVs,
    @NonNull String defaultQuoteCurrency
) {
    /**
     * One {@code (id, external_id)} pair from an entry's
     * {@code sub_verbs[]} list. {@code id} is the sub-verb slug used
     * in slash-command argument position (e.g.
     * {@code /zcash kraken} → {@code id = "kraken"}); {@code externalId}
     * is the upstream identifier the fetcher hands to the asset SPI
     * (e.g. CoinGecko coin id {@code "zcash"}, Kraken pair
     * {@code "ZECUSD"}, Bitfinex pair {@code "tZECUSD"}).
     */
    public record SubVerb(@NonNull String id, @NonNull String externalId) {
    }
}
