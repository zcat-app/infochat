package app.zcat.infochat.collector.assets.source;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Shared JSON-number coercion for the asset-snapshot sources.
 *
 * <p>The three concrete {@link AssetDataSource} impls (CoinGecko,
 * Kraken, Bitfinex) read price/volume/high/low/delta fields out of
 * heterogeneous upstream JSON shapes, but coerce every field
 * identically: a present numeric-or-textual node becomes a
 * {@link BigDecimal}; a {@code null} / missing / non-numeric /
 * unparseable node becomes {@code null} so the corresponding nullable
 * {@link app.zcat.infochat.collector.assets.PriceSnapshot} field stays
 * absent rather than failing the whole fetch. Collapsed here (M1-484)
 * from a byte-identical private copy in each source so the coercion
 * has exactly one definition.
 */
final class JsonNumbers {

    private JsonNumbers() {
    }

    static @Nullable BigDecimal readBigDecimal(JsonNode node) {
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
