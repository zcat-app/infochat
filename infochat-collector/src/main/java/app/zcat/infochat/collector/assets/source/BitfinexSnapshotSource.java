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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.zcat.infochat.collector.assets.PriceSnapshot;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Bitfinex public {@code /v2/ticker/t<TICKER><QUOTE>} reader.
 *
 * Field availability per design §10.5: price + volume_24h + high_24h
 * + low_24h + change_24h_pct (no 1h or 7d delta). Bitfinex's v2
 * ticker returns an unkeyed array; the position-encoded schema is
 * documented inline on the parse path.
 *
 * Pair construction: Bitfinex uses asset tickers ({@code ZEC},
 * {@code XMR}) prefixed with {@code t} and concatenated with the
 * upper-cased vs ({@code tZECUSD}, {@code tXMRBTC}). The v1 closed
 * asset set's ticker map lives in {@link #TICKERS}.
 *
 * Attribution URL per design §10.7 ToS table:
 * {@code https://www.bitfinex.com/t/<TICKER>:<QUOTE>}. Both the
 * TICKER and the QUOTE are upper-cased — matches
 * {@code BootstrapAssetsLoader.attributionUrl}.
 */
@ApplicationScoped
public class BitfinexSnapshotSource implements AssetDataSource {

    private static final String ID = "bitfinex";
    private static final String API_BASE =
        "https://api-pub.bitfinex.com/v2/ticker/";

    // v1 closed asset set's ticker map. A v2 broader set needs a
    // `bitfinex_ticker` column on asset_config (the ticker is not
    // persisted to asset_config; BootstrapAssetsEntry.ticker is
    // captured only at bootstrap parse time).
    private static final Map<String, String> TICKERS = Map.of(
        "zcash", "ZEC",
        "monero", "XMR"
    );

    private static final Set<String> SUPPORTED_VS = Set.of("usd", "btc");

    private final SsrfGuardedHttpClient client;
    private final ObjectMapper mapper;

    public BitfinexSnapshotSource() {
        this(new SsrfGuardedHttpClient());
    }

    BitfinexSnapshotSource(SsrfGuardedHttpClient client) {
        this.client = client;
        this.mapper = new ObjectMapper();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> supportedAssets() {
        return TICKERS.keySet();
    }

    @Override
    public Set<String> supportedQuoteCurrencies(String asset) {
        if (!TICKERS.containsKey(asset)) {
            return Set.of();
        }
        return SUPPORTED_VS;
    }

    @Override
    public PriceSnapshot fetchSnapshot(String asset, String vs)
            throws FetchException {
        String ticker = TICKERS.get(asset);
        if (ticker == null) {
            throw new FetchException("BitfinexSnapshotSource: unsupported asset '" + asset + "'");
        }
        String vsUpper = vs.toUpperCase(Locale.ROOT);
        if (!SUPPORTED_VS.contains(vs.toLowerCase(Locale.ROOT))) {
            throw new FetchException("BitfinexSnapshotSource: unsupported vs '" + vs + "'");
        }

        String pair = "t" + ticker + vsUpper;
        String url = API_BASE + URLEncoder.encode(pair, StandardCharsets.UTF_8);
        HttpResponse<byte[]> response;
        try {
            response = client.get(URI.create(url));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("BitfinexSnapshotSource: fetch interrupted for " + pair, e);
        } catch (IOException e) {
            throw new FetchException(
                "BitfinexSnapshotSource: I/O failure for " + pair + ": " + e.getMessage(), e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new FetchException(
                "BitfinexSnapshotSource: HTTP " + status + " for " + pair);
        }

        String body = new String(response.body(), StandardCharsets.UTF_8);
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException e) {
            throw new FetchException(
                "BitfinexSnapshotSource: malformed JSON for " + pair + ": " + e.getMessage(), e);
        }

        // Bitfinex v2 ticker response is a position-encoded array:
        //   [bid, bidSize, ask, askSize, dailyChange, dailyChangeRelative,
        //    lastPrice, volume, high, low]
        // We use lastPrice (idx 6), volume (idx 7), high (idx 8),
        // low (idx 9), dailyChangeRelative (idx 5, fraction → percent).
        if (!root.isArray() || root.size() < 10) {
            throw new FetchException(
                "BitfinexSnapshotSource: malformed array shape for " + pair);
        }

        BigDecimal price = JsonNumbers.readBigDecimal(root.get(6));
        if (price == null) {
            throw new FetchException(
                "BitfinexSnapshotSource: missing lastPrice for " + pair);
        }
        BigDecimal changeFraction = JsonNumbers.readBigDecimal(root.get(5));
        BigDecimal change24hPct = changeFraction == null
            ? null
            : changeFraction.multiply(BigDecimal.valueOf(100L));

        return new PriceSnapshot(
            asset,
            ID,
            vs.toLowerCase(Locale.ROOT),
            price,
            JsonNumbers.readBigDecimal(root.get(7)),
            JsonNumbers.readBigDecimal(root.get(8)),
            JsonNumbers.readBigDecimal(root.get(9)),
            null,
            change24hPct,
            null,
            Instant.now(),
            attributionUrl(asset, vs),
            body
        );
    }

    @Override
    public String attributionUrl(String asset, String vs) {
        String ticker = TICKERS.getOrDefault(asset, asset.toUpperCase(Locale.ROOT));
        return String.format("https://www.bitfinex.com/t/%s:%s", ticker, vs.toUpperCase(Locale.ROOT));
    }
}
