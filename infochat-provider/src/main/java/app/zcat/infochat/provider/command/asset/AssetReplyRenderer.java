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

        if (snap.change24hPct() != null && snap.high24h() != null && snap.low24h() != null) {
            sb.append(MessageFormat.format(
                    bundleLoader.get(BundleKeys.REPLY_ASSET_DELTA_24H, language),
                    formatDelta(snap.change24hPct()),
                    formatPrice(snap.high24h(), snap.vsCurrency()),
                    formatPrice(snap.low24h(), snap.vsCurrency())));
            sb.append('\n');
        } else if (snap.high24h() != null && snap.low24h() != null) {
            // Exchange path: spread without delta percentage
            sb.append(MessageFormat.format(
                    bundleLoader.get(BundleKeys.REPLY_ASSET_SPREAD, language),
                    formatPrice(snap.high24h(), snap.vsCurrency()),
                    formatPrice(snap.low24h(), snap.vsCurrency())));
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
        String amount = price.stripTrailingZeros().toPlainString();
        return switch (vsCurrency) {
            case "usd" -> "$" + amount;
            case "btc" -> amount + " BTC";
            // No per-currency symbol table: the uppercase ISO-code
            // suffix ("123.45 CZK") is the plain-text-correct form for
            // every other vs-currency in the small closed v1 set.
            default -> amount + " " + vsCurrency.toUpperCase(Locale.ROOT);
        };
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
