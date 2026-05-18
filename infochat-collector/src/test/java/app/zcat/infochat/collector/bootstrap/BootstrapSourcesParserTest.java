package app.zcat.infochat.collector.bootstrap;

import app.zcat.infochat.collector.bootstrap.BootstrapSourcesParser.BootstrapSourcesParseException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 unit test (no {@code @QuarkusTest}) covering the
 * {@link BootstrapSourcesParser} shape rules from
 * {@code docs/design/07-deployment.md} §7.6.1: schema validation,
 * unknown-field rejection, ≥1-tag rule, per-kind config shape
 * (HTTP-shaped sources require {@code config} to be {@code null} or
 * omitted), and Nostr identifier canonicalization (D38 — equivalent
 * filter specs with swapped JSON key orders canonicalize to identical
 * strings).
 */
class BootstrapSourcesParserTest {

    private final BootstrapSourcesParser parser = new BootstrapSourcesParser();

    @Test
    void validFixtureParsesToThreeEntries() throws IOException {
        Path fixture = Paths.get(
            "src/test/resources/bootstrap/bootstrap-sources-fixture.json");
        byte[] bytes = Files.readAllBytes(fixture);

        List<BootstrapSourcesEntry> entries = parser.parse(bytes);

        assertEquals(3, entries.size(),
            "fixture must parse to exactly 3 entries");
        assertEquals("rss",     entries.get(0).kind());
        assertEquals("bluesky", entries.get(1).kind());
        assertEquals("nostr",   entries.get(2).kind());
    }

    @Test
    void unknownTopLevelFieldIsRejected() {
        String json = "["
            + "{\"kind\":\"rss\","
            + "\"identifier\":\"https://example.com/a\","
            + "\"name\":\"A\",\"category\":\"news\","
            + "\"tags\":[\"AI\"],"
            + "\"surprise\":\"unexpected\"}"
            + "]";
        BootstrapSourcesParseException ex = assertThrows(
            BootstrapSourcesParseException.class,
            () -> parser.parse(json.getBytes(StandardCharsets.UTF_8)));
        assertTrue(ex.getMessage().toLowerCase().contains("parse failed")
                || ex.getMessage().toLowerCase().contains("unknown"),
            "parse-rejection should name the failure; got: " + ex.getMessage());
    }

    @Test
    void zeroTagsIsRejected() {
        String json = "["
            + "{\"kind\":\"rss\","
            + "\"identifier\":\"https://example.com/b\","
            + "\"name\":\"B\",\"category\":\"news\","
            + "\"tags\":[]}"
            + "]";
        BootstrapSourcesParseException ex = assertThrows(
            BootstrapSourcesParseException.class,
            () -> parser.parse(json.getBytes(StandardCharsets.UTF_8)));
        assertTrue(ex.getMessage().contains("tags"),
            "tags ≥1 rule should fire; got: " + ex.getMessage());
    }

    @Test
    void nostrKeyOrderSwapCanonicalizesIdentically() {
        // Two Nostr entries with the same logical filter spec but the
        // JSON object keys in opposite order. The parser must
        // canonicalize each identifier to the same string so the
        // (kind, identifier) UNIQUE key in `source` does not fork into
        // two rows (D38).
        String keysFirst = "[{"
            + "\"kind\":\"nostr\","
            + "\"identifier\":\"{\\\"authors\\\":[\\\"npub1abc\\\"],\\\"kinds\\\":[1,6]}\","
            + "\"name\":\"X\",\"category\":\"social\","
            + "\"tags\":[\"Nostr\"],"
            + "\"config\":{\"relays\":[\"wss://relay.example.com\"]}}]";
        String authorsFirst = "[{"
            + "\"kind\":\"nostr\","
            + "\"identifier\":\"{\\\"kinds\\\":[1,6],\\\"authors\\\":[\\\"npub1abc\\\"]}\","
            + "\"name\":\"X\",\"category\":\"social\","
            + "\"tags\":[\"Nostr\"],"
            + "\"config\":{\"relays\":[\"wss://relay.example.com\"]}}]";

        List<BootstrapSourcesEntry> a = parser.parse(keysFirst.getBytes(StandardCharsets.UTF_8));
        List<BootstrapSourcesEntry> b = parser.parse(authorsFirst.getBytes(StandardCharsets.UTF_8));

        assertEquals(a.get(0).identifier(), b.get(0).identifier(),
            "Nostr identifiers with swapped key order must canonicalize to identical strings");
    }

    @Test
    void httpShapedKindRejectsNonNullConfig() {
        // HTTP-shaped kinds (rss, bluesky, nitter, reddit, youtube,
        // odysee) must carry a null/omitted `config` per the Per-kind
        // config shape table in docs/design/07-deployment.md §7.6.1.
        // A non-null config object is parse-rejected to keep the
        // shape table honest at the storage boundary.
        String rssWithConfig = "[{"
            + "\"kind\":\"rss\","
            + "\"identifier\":\"https://example.com/c\","
            + "\"name\":\"C\",\"category\":\"news\","
            + "\"tags\":[\"AI\"],"
            + "\"config\":{\"relays\":[\"wss://wrongplace.example\"]}}]";

        BootstrapSourcesParseException ex = assertThrows(
            BootstrapSourcesParseException.class,
            () -> parser.parse(rssWithConfig.getBytes(StandardCharsets.UTF_8)));
        assertTrue(ex.getMessage().contains("config"),
            "rejection should name config; got: " + ex.getMessage());
    }

    @Test
    void nostrConfigRelaysMustBeNonEmpty() {
        String json = "[{"
            + "\"kind\":\"nostr\","
            + "\"identifier\":\"{\\\"authors\\\":[\\\"npub1xyz\\\"]}\","
            + "\"name\":\"Y\",\"category\":\"social\","
            + "\"tags\":[\"Nostr\"],"
            + "\"config\":{\"relays\":[]}}]";
        BootstrapSourcesParseException ex = assertThrows(
            BootstrapSourcesParseException.class,
            () -> parser.parse(json.getBytes(StandardCharsets.UTF_8)));
        assertTrue(ex.getMessage().contains("relays"),
            "rejection should name relays; got: " + ex.getMessage());
    }
}
