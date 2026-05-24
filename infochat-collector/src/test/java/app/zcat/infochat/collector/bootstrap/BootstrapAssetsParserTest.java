package app.zcat.infochat.collector.bootstrap;

import app.zcat.infochat.collector.bootstrap.BootstrapAssetsParser.BootstrapAssetsParseException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 unit test (no {@code @QuarkusTest}) covering the
 * {@link BootstrapAssetsParser} shape rules from
 * {@code docs/design/10-asset-commands.md} §10.6: schema validation,
 * unknown-field rejection (strict-by-default Jackson), the
 * {@code default_sub_verb ∈ sub_verbs[].id} operator-typo guard, and
 * oversize-input defense (Jackson's default
 * {@code StreamReadConstraints} trip rather than OOM the JVM).
 */
class BootstrapAssetsParserTest {

    private final BootstrapAssetsParser parser = new BootstrapAssetsParser();

    @Test
    void parsesValidFixtureAsZcashAndMonero() throws IOException {
        Path fixture = Paths.get(
            "src/test/resources/bootstrap/bootstrap-assets-fixture.json");
        byte[] bytes = Files.readAllBytes(fixture);

        List<BootstrapAssetsEntry> entries = parser.parse(
            new ByteArrayInputStream(bytes));

        assertEquals(2, entries.size(),
            "fixture must parse to exactly 2 entries");
        BootstrapAssetsEntry zcash = entries.get(0);
        BootstrapAssetsEntry monero = entries.get(1);

        assertEquals("zcash", zcash.id());
        assertEquals("coingecko", zcash.defaultSubVerb());
        assertEquals(3, zcash.subVerbs().size(),
            "zcash entry must carry 3 sub_verbs (coingecko, kraken, bitfinex)");
        assertNotNull(zcash.defaultQuoteCurrency(),
            "default_quote_currency must be propagated from top-level default_vs");

        assertEquals("monero", monero.id());
        assertEquals("coingecko", monero.defaultSubVerb());
        assertEquals(3, monero.subVerbs().size());
    }

    @Test
    void rejectsUnknownFieldAtTopLevel() {
        String json = "{"
            + "\"default_vs\":\"usd\","
            + "\"assets\":[{"
            + "  \"id\":\"zcash\",\"display_name\":\"Zcash\",\"ticker\":\"ZEC\","
            + "  \"default_sub_verb\":\"coingecko\","
            + "  \"sub_verbs\":[{\"id\":\"coingecko\",\"external_id\":\"zcash\"}],"
            + "  \"supported_vs\":[\"usd\"]"
            + "}],"
            + "\"surprise\":\"unexpected-key\""
            + "}";

        BootstrapAssetsParseException ex = assertThrows(
            BootstrapAssetsParseException.class,
            () -> parser.parse(streamOf(json)));
        assertTrue(ex.getMessage().toLowerCase().contains("parse failed")
                || ex.getMessage().toLowerCase().contains("unrecognized")
                || ex.getMessage().toLowerCase().contains("unknown"),
            "parse-rejection should name the failure; got: " + ex.getMessage());
    }

    @Test
    void rejectsMissingDefaultSubVerbInSubVerbsList() {
        // default_sub_verb='kraken' but sub_verbs=[coingecko, bitfinex] —
        // the operator-typo guard fires; bare /zcash would otherwise
        // resolve to a non-existent sub-verb.
        String json = "{"
            + "\"default_vs\":\"usd\","
            + "\"assets\":[{"
            + "  \"id\":\"zcash\",\"display_name\":\"Zcash\",\"ticker\":\"ZEC\","
            + "  \"default_sub_verb\":\"kraken\","
            + "  \"sub_verbs\":["
            + "    {\"id\":\"coingecko\",\"external_id\":\"zcash\"},"
            + "    {\"id\":\"bitfinex\",\"external_id\":\"tZECUSD\"}"
            + "  ],"
            + "  \"supported_vs\":[\"usd\"]"
            + "}]"
            + "}";

        BootstrapAssetsParseException ex = assertThrows(
            BootstrapAssetsParseException.class,
            () -> parser.parse(streamOf(json)));
        assertTrue(ex.getMessage().contains("default_sub_verb"),
            "rejection message should name default_sub_verb; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("kraken"),
            "rejection message should name the offending value; got: " + ex.getMessage());
    }

    @Test
    void rejectsOversizeInputViaJacksonNestingDepthGuard() {
        // Jackson's default StreamReadConstraints caps maxNestingDepth
        // at 1000. Construct a deeply-nested array literal that crosses
        // the cap; the parser must surface BootstrapAssetsParseException
        // (wrapping Jackson's StreamConstraintsException) rather than
        // OOMing the JVM or blowing the stack.
        int depth = 2000;
        StringBuilder nested = new StringBuilder(depth * 2);
        for (int i = 0; i < depth; i++) {
            nested.append('[');
        }
        for (int i = 0; i < depth; i++) {
            nested.append(']');
        }
        // Wrap the deeply-nested array as the value of the top-level
        // "assets" field so the parser starts down its real code path
        // before the constraint fires.
        String json = "{\"default_vs\":\"usd\",\"assets\":" + nested + "}";

        assertThrows(BootstrapAssetsParseException.class,
            () -> parser.parse(streamOf(json)),
            "oversize input must throw a BootstrapAssetsParseException, not OOM the JVM");
    }

    private static InputStream streamOf(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
}
