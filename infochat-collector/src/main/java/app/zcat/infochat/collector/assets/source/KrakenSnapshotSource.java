package app.zcat.infochat.collector.assets.source;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.zcat.infochat.collector.assets.PriceSnapshot;
import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Kraken public {@code /0/public/Ticker?pair=<pair>} reader.
 *
 * Field availability per design §10.5: price + volume_24h + high_24h
 * + low_24h. Kraken's public ticker has no 1h / 24h / 7d delta
 * percentages — those nullable fields stay null on Kraken snapshots
 * by design, and the renderer (M1-055c) decides whether to omit or
 * show as N/A.
 *
 * Pair construction: Kraken uses asset tickers ({@code ZEC}, {@code XMR})
 * concatenated with the upper-cased vs ({@code ZECUSD}, {@code XMRBTC}).
 * The v1 closed asset set's ticker map lives in {@link #TICKERS}.
 *
 * Attribution URL per design §10.7 ToS table, with the quote-currency
 * segment reflecting the requested {@code vs} so a non-USD reply links
 * to the matching chart rather than the USD one:
 * {@code https://www.kraken.com/prices/<asset>-<vs>-<asset>-price-chart}.
 * {@code BootstrapAssetsLoader.attributionUrl} builds the same shape
 * from each asset's default quote currency.
 */
@ApplicationScoped
public class KrakenSnapshotSource implements AssetDataSource {

    private static final String ID = "kraken";
    private static final String API_BASE =
        "https://api.kraken.com/0/public/Ticker?pair=";

    // v1 closed asset set's ticker map. A v2 broader set needs a
    // `kraken_ticker` column on asset_config.
    private static final Map<String, String> TICKERS = Map.of(
        "zcash", "ZEC",
        "monero", "XMR"
    );

    // Kraken supports usd/eur/btc for both ZEC and XMR; czk is not on
    // Kraken's public quote currencies for these pairs as of design
    // §10.6 capture.
    private static final Set<String> SUPPORTED_VS = Set.of("usd", "eur", "btc");

    private final SsrfGuardedHttpClient client;
    private final ObjectMapper mapper;

    public KrakenSnapshotSource() {
        this(new SsrfGuardedHttpClient());
    }

    KrakenSnapshotSource(SsrfGuardedHttpClient client) {
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
            throw new FetchException("KrakenSnapshotSource: unsupported asset '" + asset + "'");
        }
        if (!SUPPORTED_VS.contains(vs.toLowerCase(Locale.ROOT))) {
            throw new FetchException("KrakenSnapshotSource: unsupported vs '" + vs + "'");
        }
        String vsUpper = vs.toUpperCase(Locale.ROOT);

        String pair = ticker + vsUpper;
        String url = API_BASE + URLEncoder.encode(pair, StandardCharsets.UTF_8);
        HttpResponse<byte[]> response;
        try {
            response = client.get(URI.create(url));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("KrakenSnapshotSource: fetch interrupted for " + pair, e);
        } catch (IOException e) {
            throw new FetchException(
                "KrakenSnapshotSource: I/O failure for " + pair + ": " + e.getMessage(), e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new FetchException(
                "KrakenSnapshotSource: HTTP " + status + " for " + pair);
        }

        String body = new String(response.body(), StandardCharsets.UTF_8);
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException e) {
            throw new FetchException(
                "KrakenSnapshotSource: malformed JSON for " + pair + ": " + e.getMessage(), e);
        }

        // Kraken returns an error array; success body is
        // {"error":[],"result":{"<canonical-pair>":{...}}}. The
        // canonical-pair key Kraken returns may not equal the
        // requested pair (Kraken sometimes returns XZEC/ZUSD). Walk
        // the result map's first entry.
        JsonNode errorArr = root.path("error");
        if (errorArr.isArray() && errorArr.size() > 0) {
            // The error array is untrusted upstream bytes; control-strip
            // and truncate it before it lands in exception text that may
            // reach logs or admin notifications.
            throw new FetchException(
                "KrakenSnapshotSource: API error for " + pair + ": "
                    + stripAndTruncate(errorArr.toString()));
        }
        JsonNode result = root.path("result");
        if (!result.isObject() || !result.fields().hasNext()) {
            throw new FetchException(
                "KrakenSnapshotSource: empty result for " + pair);
        }
        Iterator<Map.Entry<String, JsonNode>> fields = result.fields();
        JsonNode tickerNode = fields.next().getValue();

        // Kraken ticker schema: c=[last, lot_volume], v=[today, last24h],
        // h=[today, last24h], l=[today, last24h]. We use [1] (24h).
        BigDecimal price = JsonNumbers.readBigDecimal(tickerNode.path("c").path(0));
        if (price == null) {
            throw new FetchException(
                "KrakenSnapshotSource: missing c[0] for " + pair);
        }

        return new PriceSnapshot(
            asset,
            ID,
            vs.toLowerCase(Locale.ROOT),
            price,
            JsonNumbers.readBigDecimal(tickerNode.path("v").path(1)),
            JsonNumbers.readBigDecimal(tickerNode.path("h").path(1)),
            JsonNumbers.readBigDecimal(tickerNode.path("l").path(1)),
            null,
            null,
            null,
            Instant.now(),
            attributionUrl(asset, vs),
            body
        );
    }

    @Override
    public String attributionUrl(String asset, String vs) {
        return String.format("https://www.kraken.com/prices/%s-%s-%s-price-chart",
            asset, vs.toLowerCase(Locale.ROOT), asset);
    }

    // Bound on upstream bytes admitted into exception text. A Kraken
    // error array is a short JSON list; this leaves room for a couple of
    // messages while capping a hostile or runaway body.
    private static final int MAX_UPSTREAM_CHARS = 200;

    // Package-private so a unit test can pin the control-strip + truncation
    // shape directly; the production URL is hardcoded, so the error path
    // cannot be reached through a loopback HTTP fixture.
    static String stripAndTruncate(String upstream) {
        String stripped = SafeLog.stripControls(upstream);
        if (stripped.length() <= MAX_UPSTREAM_CHARS) {
            return stripped;
        }
        return stripped.substring(0, MAX_UPSTREAM_CHARS) + "…";
    }
}
