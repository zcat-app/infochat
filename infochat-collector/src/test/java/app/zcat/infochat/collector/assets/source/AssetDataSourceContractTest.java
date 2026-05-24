package app.zcat.infochat.collector.assets.source;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Plain JUnit parameterized contract test across the three concrete
 * {@code AssetDataSource} impls. Exercises the STATIC surface only —
 * {@link AssetDataSource#supportedAssets},
 * {@link AssetDataSource#supportedQuoteCurrencies},
 * {@link AssetDataSource#attributionUrl} — never
 * {@link AssetDataSource#fetchSnapshot}, which would require outbound
 * HTTP this test deliberately does not perform.
 *
 * <p>The impls are instantiated with their no-arg constructors
 * (which allocate the production {@code SsrfGuardedHttpClient} with
 * the strict blocklist). Since {@code fetchSnapshot} is never called,
 * the production client is never dialled and the strict blocklist
 * is irrelevant. A future contract check that exercises a fake HTTP
 * fixture would need to pass a permissive client via the
 * package-private test-seam constructor (RssFetcherTest precedent).
 *
 * <p>No {@code @QuarkusTest} — this is a plain JUnit unit test that
 * runs under surefire with no CDI context, no DevServices DB, and
 * no application boot.
 */
class AssetDataSourceContractTest {

    private static final String ZCASH = "zcash";

    static Stream<Arguments> sources() {
        return Stream.of(
            Arguments.of(new CoingeckoSnapshotSource(), "coingecko", "usd"),
            Arguments.of(new KrakenSnapshotSource(), "kraken", "usd"),
            Arguments.of(new BitfinexSnapshotSource(), "bitfinex", "usd")
        );
    }

    @ParameterizedTest(name = "{1}: supportedAssets is non-empty")
    @MethodSource("sources")
    void supportedAssetsIsNonEmpty(AssetDataSource source, String expectedId, String vs) {
        assertNotNull(source.id(), "id() must return non-null for " + expectedId);
        Set<String> assets = source.supportedAssets();
        assertNotNull(assets, "supportedAssets() must return non-null for " + expectedId);
        assertFalse(assets.isEmpty(),
            "supportedAssets() must be non-empty for " + expectedId);
    }

    @ParameterizedTest(name = "{1}: supportedQuoteCurrencies(zcash) is non-empty")
    @MethodSource("sources")
    void supportedQuoteCurrenciesForZcashIsNonEmpty(AssetDataSource source, String expectedId, String vs) {
        // All three v1 sources list zcash per design §10.1 v1 sub-verb
        // set. Any future source that lists a NEW asset set but
        // omits zcash would need to opt out of this assertion — at
        // that point the contract test grows beyond a single
        // parameterized shape, but the v1 closed set keeps it simple.
        Set<String> quotes = source.supportedQuoteCurrencies(ZCASH);
        assertNotNull(quotes, "supportedQuoteCurrencies(zcash) must return non-null for " + expectedId);
        assertFalse(quotes.isEmpty(),
            "supportedQuoteCurrencies(zcash) must be non-empty for " + expectedId);
    }

    @ParameterizedTest(name = "{1}: attributionUrl(zcash, {2}) is a bare http URL")
    @MethodSource("sources")
    void attributionUrlIsBareHttpUrl(AssetDataSource source, String expectedId, String vs) {
        String url = source.attributionUrl(ZCASH, vs);
        assertNotNull(url, "attributionUrl(zcash, " + vs + ") must return non-null for " + expectedId);
        assertTrue(url.startsWith("http"),
            "attributionUrl must start with 'http' for " + expectedId + "; got: " + url);
        assertFalse(url.contains("]("),
            "attributionUrl must not contain markdown link syntax ']('"
                + " (D30 bare-URL invariant) for " + expectedId + "; got: " + url);
    }

    @ParameterizedTest(name = "{1}: id() returns the expected lowercase host identifier")
    @MethodSource("sources")
    void idMatchesExpectedHost(AssetDataSource source, String expectedId, String vs) {
        // The impl's id() value must match the bootstrap-assets.json
        // sub_verbs[].id values; the AssetSnapshotFetcher uses this
        // string to route per-host ticks to the correct bean.
        // Pinning the literal here prevents an accidental rename
        // from breaking the runtime match.
        assertTrue(expectedId.equals(source.id()),
            "id() must return '" + expectedId + "' for the matching impl; got: " + source.id());
    }
}
