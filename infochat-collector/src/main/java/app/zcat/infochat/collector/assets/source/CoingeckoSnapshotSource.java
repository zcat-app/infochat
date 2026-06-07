package app.zcat.infochat.collector.assets.source;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.zcat.infochat.collector.assets.PriceSnapshot;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * CoinGecko public free-tier {@code /api/v3/coins/<id>} reader.
 *
 * Field availability per design §10.5: price + volume_24h + high_24h
 * + low_24h + change_1h_pct + change_24h_pct + change_7d_pct (full
 * row). CoinGecko's free tier is rate-limited (~30 req/min as of
 * 2026); the per-host 90s default tick keeps two assets × four quote
 * currencies inside that budget by a wide margin.
 *
 * Attribution URL per design §10.7 ToS table: bare
 * {@code https://www.coingecko.com/en/coins/<slug>}. The slug equals
 * the asset id for the v1 closed set (zcash, monero); a v2 asset set
 * with different slug/id pairs would need a `coingecko_slug` column
 * on {@code asset_config}, captured here for the future maintainer.
 */
@ApplicationScoped
public class CoingeckoSnapshotSource implements AssetDataSource {

    private static final String ID = "coingecko";
    private static final String API_BASE =
        "https://api.coingecko.com/api/v3/coins/";
    private static final String ATTRIBUTION_BASE =
        "https://www.coingecko.com/en/coins/";

    // v1 closed asset set: the slug equals the asset id. A v2 broader
    // set with mismatching slug/id pairs needs a column on asset_config.
    private static final Map<String, String> SLUGS = Map.of(
        "zcash", "zcash",
        "monero", "monero"
    );

    // Quote currencies CoinGecko supports for the v1 asset set per
    // design §10.6 (the API itself accepts ~60 quotes; we narrow to the
    // four operator-facing ones to keep parity with Kraken / Bitfinex
    // unsupported-vs reporting).
    private static final Set<String> SUPPORTED_VS = Set.of("usd", "eur", "czk", "btc");

    private final SsrfGuardedHttpClient client;
    private final ObjectMapper mapper;

    public CoingeckoSnapshotSource() {
        this(new SsrfGuardedHttpClient());
    }

    // Package-private constructor seam — see RssFetcher precedent.
    CoingeckoSnapshotSource(SsrfGuardedHttpClient client) {
        this.client = client;
        this.mapper = new ObjectMapper();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> supportedAssets() {
        return SLUGS.keySet();
    }

    @Override
    public Set<String> supportedQuoteCurrencies(String asset) {
        if (!SLUGS.containsKey(asset)) {
            return Set.of();
        }
        return SUPPORTED_VS;
    }

    @Override
    public PriceSnapshot fetchSnapshot(String asset, String vs)
            throws FetchException {
        String slug = SLUGS.get(asset);
        if (slug == null) {
            throw new FetchException("CoingeckoSnapshotSource: unsupported asset '" + asset + "'");
        }
        String vsLower = vs.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_VS.contains(vsLower)) {
            throw new FetchException("CoingeckoSnapshotSource: unsupported vs '" + vs + "'");
        }

        String url = API_BASE + URLEncoder.encode(slug, StandardCharsets.UTF_8)
            + "?localization=false&tickers=false&community_data=false"
            + "&developer_data=false&sparkline=false";
        HttpResponse<byte[]> response;
        try {
            response = client.get(URI.create(url));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("CoingeckoSnapshotSource: fetch interrupted for " + slug, e);
        } catch (IOException e) {
            throw new FetchException(
                "CoingeckoSnapshotSource: I/O failure for " + slug + ": " + e.getMessage(), e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new FetchException(
                "CoingeckoSnapshotSource: HTTP " + status + " for " + slug);
        }

        String body = new String(response.body(), StandardCharsets.UTF_8);
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException e) {
            throw new FetchException(
                "CoingeckoSnapshotSource: malformed JSON for " + slug + ": " + e.getMessage(), e);
        }

        JsonNode marketData = root.path("market_data");
        BigDecimal price = readBigDecimal(marketData.path("current_price").path(vsLower));
        if (price == null) {
            throw new FetchException(
                "CoingeckoSnapshotSource: missing market_data.current_price." + vsLower
                + " for " + slug);
        }

        return new PriceSnapshot(
            asset,
            ID,
            vsLower,
            price,
            readBigDecimal(marketData.path("total_volume").path(vsLower)),
            readBigDecimal(marketData.path("high_24h").path(vsLower)),
            readBigDecimal(marketData.path("low_24h").path(vsLower)),
            readBigDecimal(marketData.path("price_change_percentage_1h_in_currency").path(vsLower)),
            readBigDecimal(marketData.path("price_change_percentage_24h_in_currency").path(vsLower)),
            readBigDecimal(marketData.path("price_change_percentage_7d_in_currency").path(vsLower)),
            Instant.now(),
            attributionUrl(asset, vsLower),
            body
        );
    }

    @Override
    public String attributionUrl(String asset, String vs) {
        String slug = SLUGS.getOrDefault(asset, asset);
        return ATTRIBUTION_BASE + slug;
    }

    private static @Nullable BigDecimal readBigDecimal(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isNumber() && !node.isTextual()) {
            return null;
        }
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
