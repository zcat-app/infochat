package app.zcat.infochat.collector.assets;

import java.math.BigDecimal;
import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * Immutable per-source price snapshot. The boxed numeric fields
 * (volume_24h, high_24h, low_24h, change_*_pct) are nullable because
 * the design §10.5 per-source field availability table is asymmetric:
 * Kraken's public ticker exposes no 7-day delta, Bitfinex's `v2/ticker`
 * has only a 24h volume + last/bid/ask, and degraded responses may
 * drop fields the upstream normally populates. A null Double here
 * means "the source did not return this field for this snapshot",
 * NOT "the value is zero" — primitives would conflate the two.
 *
 * raw_payload is the verbatim upstream response body, preserved for
 * forensic / replay purposes. Persisted as JSONB in price_snapshot.
 */
public record PriceSnapshot(
        String asset,
        String subVerb,
        String vsCurrency,
        BigDecimal price,
        @Nullable BigDecimal volume24h,
        @Nullable BigDecimal high24h,
        @Nullable BigDecimal low24h,
        @Nullable BigDecimal change1hPct,
        @Nullable BigDecimal change24hPct,
        @Nullable BigDecimal change7dPct,
        Instant capturedAt,
        String sourceUrl,
        @Nullable String rawPayload
) { }
