package app.zcat.infochat.collector.assets.source;

import java.util.Set;

import org.jspecify.annotations.NonNull;

import app.zcat.infochat.collector.assets.PriceSnapshot;

/**
 * Per-host SPI for fetching one {@link PriceSnapshot} on demand.
 * Each public-endpoint exchange (CoinGecko, Kraken, Bitfinex in v1)
 * implements this interface as an {@code @ApplicationScoped} CDI bean
 * discovered by {@code AssetSnapshotFetcher} via
 * {@code @Inject Instance<AssetDataSource>}; matching to a particular
 * {@code asset_config.sub_verb} row happens via {@link #id()}.
 *
 * Per spec §SSRF and outbound connections, every concrete impl MUST
 * issue outbound HTTP through the shared {@code SsrfGuardedHttpClient}
 * library — no bespoke {@code HttpClient}, {@code URLConnection}, or
 * third-party HTTP construction.
 *
 * Per design §10.7 ToS attribution, {@link #attributionUrl} returns a
 * bare URL (no Markdown link syntax) the Provider includes verbatim in
 * the user-facing reply. The contract test pins this invariant.
 */
public interface AssetDataSource {

    /**
     * Lowercase host-derived identifier matching the bootstrap-assets.json
     * {@code sub_verbs[].id} values (e.g. {@code "coingecko"},
     * {@code "kraken"}, {@code "bitfinex"}). Used by the fetcher to
     * match a row's {@code sub_verb} column to its handler bean.
     */
    @NonNull String id();

    /**
     * The asset ids this source can quote (e.g.
     * {@code Set.of("zcash", "monero")} for v1). The fetcher MUST NOT
     * call {@link #fetchSnapshot} for an asset absent from this set.
     */
    @NonNull Set<String> supportedAssets();

    /**
     * The vs-currency codes this source supports for a given asset
     * (e.g. {@code Set.of("usd", "eur", "czk", "btc")}). Returns an
     * empty set for unsupported assets.
     */
    @NonNull Set<String> supportedQuoteCurrencies(@NonNull String asset);

    /**
     * Issue one outbound HTTP fetch, parse the response, and return
     * the snapshot. The caller commits to a successful return
     * indicating one row's worth of snapshot data ready for
     * {@code PriceSnapshotStore.store(...)}.
     *
     * Any infrastructure failure (network, SSRF block, HTTP error
     * status, malformed body, missing required field) MUST surface
     * as {@link FetchException}; runtime exceptions escape unwrapped
     * and indicate a programming bug rather than a fetch failure.
     */
    @NonNull PriceSnapshot fetchSnapshot(@NonNull String asset, @NonNull String vs) throws FetchException;

    /**
     * Per-source attribution URL (design §10.7 ToS attribution table).
     * Returned string is a bare URL (no Markdown {@code [label](url)}
     * syntax — D30 plain-text formatting invariant). The contract test
     * pins both the {@code http} prefix and the absence of
     * {@code "]("} in the returned string.
     */
    @NonNull String attributionUrl(@NonNull String asset, @NonNull String vs);

    /**
     * Checked exception raised on any infrastructure failure of a
     * {@link #fetchSnapshot} call. Carrying the failure as checked
     * (not runtime) makes the failure-counter call site in
     * {@code AssetSnapshotFetcher} unmissable — a compile error
     * surfaces if a caller forgets to handle the error path.
     *
     * Lives nested inside the SPI to keep the asset-SPI files_scope
     * at one file rather than two.
     */
    class FetchException extends Exception {

        private static final long serialVersionUID = 1L;

        public FetchException(@NonNull String message) {
            super(message);
        }

        public FetchException(@NonNull String message, @NonNull Throwable cause) {
            super(message, cause);
        }
    }
}
