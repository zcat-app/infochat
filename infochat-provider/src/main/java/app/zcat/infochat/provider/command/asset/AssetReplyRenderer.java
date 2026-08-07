package app.zcat.infochat.provider.command.asset;

import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Renders a plain-text reply body for an asset price snapshot
 * per design §10.5. No capability-flag branches — D30
 * mandates bare URLs and plain text universally.
 *
 * <p>Absent snapshot fields are silently omitted. The renderer
 * never invents zeros.</p>
 */
@ApplicationScoped
public class AssetReplyRenderer {

    private static final DateTimeFormatter UTC_TIME =
            DateTimeFormatter.ofPattern("HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    @Inject
    BundleLoader bundleLoader;

    /** CDI-required no-arg constructor. */
    public AssetReplyRenderer() {}

    /** Test constructor. */
    AssetReplyRenderer(BundleLoader bundleLoader) {
        this.bundleLoader = bundleLoader;
    }

    /**
     * Renders the reply body for a successful snapshot lookup.
     *
     * @param result      the snapshot with staleness metadata
     * @param displayName the human-readable asset name (e.g. "Zcash")
     * @param sourceUrl   the attribution URL for this source
     * @param language    the requester's effective scope language (D43)
     */
    public String render(AssetSnapshotReader.SnapshotResult result,
                         String displayName,
                         String sourceUrl,
                         String language) {
        AssetSnapshotReader.Snapshot snap = result.snapshot();
        StringBuilder sb = new StringBuilder();

        // Header: <DisplayName> (<source>) + optional stale marker
        String header = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_ASSET_HEADER, language),
                displayName, snap.subVerb());
        sb.append(header);
        if (result.stale()) {
            sb.append(bundleLoader.get(BundleKeys.REPLY_ASSET_STALE_MARKER, language));
        }
        sb.append('\n');

        // Price line
        String priceFormatted = formatPrice(snap.price(), snap.vsCurrency());
        sb.append(MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_ASSET_PRICE_LINE, language),
                priceFormatted));
        sb.append('\n');

        // Delta lines (coingecko only — exchanges omit)
        if (snap.change1hPct() != null) {
            sb.append(MessageFormat.format(
                    bundleLoader.get(BundleKeys.REPLY_ASSET_DELTA_1H, language),
                    formatDelta(snap.change1hPct())));
            sb.append('\n');
        }

        // The 24h delta and the 24h spread are independent facts, gated
        // independently (M1-678). They used to share one bundle key, which made
        // the delta conditional on the spread: a degraded upstream returning
        // change_24h_pct without high_24h/low_24h (CoinGecko reads each from its
        // own JSON path) satisfied neither branch and lost the delta silently.
        // Emitting delta-then-spread keeps the all-three output byte-identical.
        if (snap.change24hPct() != null) {
            sb.append(MessageFormat.format(
                    bundleLoader.get(BundleKeys.REPLY_ASSET_DELTA_24H, language),
                    formatDelta(snap.change24hPct())));
            sb.append('\n');
        }

        if (snap.high24h() != null && snap.low24h() != null) {
            // The range must contain the price: a bound that excludes it is
            // rendered as the price itself.
            BigDecimal high = snap.high24h().max(snap.price());
            BigDecimal low = snap.low24h().min(snap.price());
            sb.append(MessageFormat.format(
                    bundleLoader.get(BundleKeys.REPLY_ASSET_SPREAD, language),
                    formatPrice(high, snap.vsCurrency()),
                    formatPrice(low, snap.vsCurrency())));
            sb.append('\n');
        }

        // Capture timestamp + cache age
        String utcTime = UTC_TIME.format(snap.capturedAt());
        long cacheAgeSeconds = Duration.between(snap.capturedAt(), Instant.now()).toSeconds();
        sb.append(MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_ASSET_CAPTURE_LINE, language),
                utcTime, String.valueOf(cacheAgeSeconds)));
        sb.append('\n');

        // Attribution URL on its own line, bare per D30. The 2-space indent
        // and the separator space are layout (owned here); only the label word
        // is bundle-resolved (D43).
        sb.append("  ")
          .append(bundleLoader.get(BundleKeys.REPLY_ASSET_SOURCE_LABEL, language))
          .append(' ').append(sourceUrl);

        return sb.toString();
    }

    private static String formatPrice(BigDecimal price, String vsCurrency) {
        return switch (vsCurrency) {
            case "usd" -> "$" + formatFiatAmount(price);
            // BTC is carved out of the fiat scale: crypto-vs-crypto quotes are
            // sub-unit (0.000651), so a 2-dp scale would round every one of them
            // to 0.00. Crypto quotes keep the source's own precision (M1-678).
            case "btc" -> price.stripTrailingZeros().toPlainString() + " BTC";
            // No per-currency symbol table: the uppercase ISO-code
            // suffix ("123.45 CZK") is the plain-text-correct form for
            // every other vs-currency in the small closed v1 set.
            default -> formatFiatAmount(price) + " " + vsCurrency.toUpperCase(Locale.ROOT);
        };
    }

    // Fiat prices read as money at a fixed 2 dp (HALF_UP), matching every design
    // §10.5 example reply. stripTrailingZeros() alone printed a round 41.00 as
    // "$41" and 961.30 as "961.3 CZK" — neither a shape the examples ever showed
    // (M1-678).
    private static String formatFiatAmount(BigDecimal price) {
        return price.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    // U+2212 MINUS SIGN for negative values per design §10.5. Fixed 2-dp scale
    // (HALF_UP) so every delta reads at the same precision — raw API precision
    // otherwise printed e.g. 1h at 3 dp next to 24h at 4 dp in the same reply
    // (M1-592).
    private static String formatDelta(BigDecimal pct) {
        String magnitude = pct.abs().setScale(2, RoundingMode.HALF_UP).toPlainString();
        if (pct.signum() >= 0) {
            return "+" + magnitude + "%";
        }
        return "−" + magnitude + "%";
    }
}
