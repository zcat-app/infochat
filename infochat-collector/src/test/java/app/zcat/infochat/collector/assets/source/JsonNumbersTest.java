package app.zcat.infochat.collector.assets.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pins the coercion contract of the shared {@link JsonNumbers#readBigDecimal}
 * helper (M1-484 / F3) — the single definition that replaced the
 * byte-identical private copy in CoinGecko / Kraken / Bitfinex. A present
 * numeric-or-textual node yields the matching {@link BigDecimal}; every
 * other shape (missing, JSON null, non-numeric text, boolean, object)
 * yields {@code null}, so a nullable {@code PriceSnapshot} field stays
 * absent rather than failing the whole fetch.
 */
class JsonNumbersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void numericNodeParses() throws Exception {
        JsonNode root = MAPPER.readTree("{\"v\": 123.45}");
        assertEquals(new BigDecimal("123.45"), JsonNumbers.readBigDecimal(root.path("v")));
    }

    @Test
    void integerNodeParses() throws Exception {
        JsonNode root = MAPPER.readTree("{\"v\": 42}");
        assertEquals(new BigDecimal("42"), JsonNumbers.readBigDecimal(root.path("v")));
    }

    @Test
    void numericStringNodeParsesIdenticallyToNumber() throws Exception {
        // Kraken and Bitfinex encode prices as JSON strings; the coercion
        // must accept the textual form identically to a bare number.
        JsonNode root = MAPPER.readTree("{\"v\": \"678.90\"}");
        assertEquals(new BigDecimal("678.90"), JsonNumbers.readBigDecimal(root.path("v")));
    }

    @Test
    void missingNodeIsNull() throws Exception {
        JsonNode root = MAPPER.readTree("{}");
        assertNull(JsonNumbers.readBigDecimal(root.path("absent")));
    }

    @Test
    void explicitJsonNullIsNull() throws Exception {
        JsonNode root = MAPPER.readTree("{\"v\": null}");
        assertNull(JsonNumbers.readBigDecimal(root.path("v")));
    }

    @Test
    void nonNumericTextIsNull() throws Exception {
        JsonNode root = MAPPER.readTree("{\"v\": \"not-a-number\"}");
        assertNull(JsonNumbers.readBigDecimal(root.path("v")));
    }

    @Test
    void booleanNodeIsNull() throws Exception {
        JsonNode root = MAPPER.readTree("{\"v\": true}");
        assertNull(JsonNumbers.readBigDecimal(root.path("v")));
    }

    @Test
    void objectNodeIsNull() throws Exception {
        JsonNode root = MAPPER.readTree("{\"v\": {\"nested\": 1}}");
        assertNull(JsonNumbers.readBigDecimal(root.path("v")));
    }
}
