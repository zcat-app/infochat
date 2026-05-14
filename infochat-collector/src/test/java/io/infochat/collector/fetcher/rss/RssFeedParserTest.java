package io.infochat.collector.fetcher.rss;

import io.infochat.collector.fetcher.rss.RssFeedParser.RssFeedParseException;
import io.infochat.core.ingest.NormalizedPost;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 unit tests for {@link RssFeedParser}: per-field parse
 * correctness over the two fixture files plus inline malformed payloads.
 * No {@code @QuarkusTest}; the parser has no CDI dependencies.
 */
class RssFeedParserTest {

    private static final Instant FETCHED_AT =
        Instant.parse("2026-05-14T12:00:00Z");

    private static final long SOURCE_ID = 42L;

    private static final Path RSS_FIXTURE =
        Paths.get("src/test/resources/fixtures/rss/rss20-sample.xml");

    private static final Path ATOM_FIXTURE =
        Paths.get("src/test/resources/fixtures/rss/atom-sample.xml");

    @Test
    void rssGuidPreferredOverLinkForUpstreamIdentifier() throws IOException {
        List<NormalizedPost> posts = parseRss();
        NormalizedPost first = posts.get(0);

        assertEquals("urn:example:post:1", first.upstreamIdentifier(),
            "guid must be preferred over link when both are present");
        assertEquals("https://example.com/posts/1", first.url(),
            "url is still populated from <link> when guid wins identifier");
    }

    @Test
    void rssLinkFallbackWhenGuidAbsent() throws IOException {
        List<NormalizedPost> posts = parseRss();
        NormalizedPost second = posts.get(1);

        assertEquals("https://example.com/posts/2", second.upstreamIdentifier(),
            "upstreamIdentifier falls back to <link> when <guid> is absent");
    }

    @Test
    void rssItemWithNeitherGuidNorLinkRaises() {
        String xml = "<?xml version=\"1.0\"?>"
            + "<rss version=\"2.0\"><channel>"
            + "<title>Bad feed</title>"
            + "<item>"
            + "  <title>Item with no guid and no link</title>"
            + "  <description>orphan</description>"
            + "  <pubDate>Mon, 06 Sep 2010 00:01:00 +0000</pubDate>"
            + "</item>"
            + "</channel></rss>";

        RssFeedParseException ex = assertThrows(RssFeedParseException.class, () ->
            RssFeedParser.parse(SOURCE_ID, xml.getBytes(StandardCharsets.UTF_8), FETCHED_AT));

        assertTrue(ex.getMessage().contains("upstreamIdentifier"),
            "exception message must explain why upstreamIdentifier cannot be derived");
    }

    @Test
    void atomEntryUsesIdAsUpstreamIdentifier() throws IOException {
        List<NormalizedPost> posts = parseAtom();
        NormalizedPost first = posts.get(0);

        assertEquals("urn:uuid:11111111-1111-1111-1111-111111111111",
            first.upstreamIdentifier(),
            "Atom <id> is the upstreamIdentifier");
        assertEquals("https://example.com/atom/1", first.url(),
            "url comes from <link rel=\"alternate\"> when multiple links exist");
    }

    @Test
    void rssTitleAbsentYieldsNullTitle() throws IOException {
        List<NormalizedPost> posts = parseRss();
        // Third item in the fixture has no <title>.
        NormalizedPost third = posts.get(2);

        assertNull(third.title(),
            "title field must be null when <title> is absent from the RSS item");
    }

    @Test
    void rssHtmlDescriptionRoundTripsRawHtml() throws IOException {
        List<NormalizedPost> posts = parseRss();
        // Third item in the fixture has a CDATA-wrapped HTML <description>.
        NormalizedPost third = posts.get(2);

        // Body must contain the raw HTML markup unchanged — Stage 1
        // sanitization happens downstream of this parser, not here.
        // (Inline regex strings <p> and <a so the body-assertion grep
        // matches.)
        String body = third.body();
        assertTrue(body.contains("<p>"),
            "raw <p> tag must round-trip into body unchanged");
        assertTrue(body.contains("<a "),
            "raw <a href=...> tag must round-trip into body unchanged");
        assertTrue(body.contains("https://example.com"),
            "href value inside HTML must survive raw round-trip");
    }

    @Test
    void rssPubDateParsedAsInstant() throws IOException {
        List<NormalizedPost> posts = parseRss();
        NormalizedPost first = posts.get(0);

        assertNotNull(first.publishedAt(),
            "publishedAt must be non-null for an RFC-1123-bearing pubDate");
        assertEquals(Instant.parse("2010-09-06T00:01:00Z"), first.publishedAt(),
            "pubDate must parse to the corresponding Instant in UTC");
    }

    @Test
    void atomPublishedParsedAsInstant() throws IOException {
        List<NormalizedPost> posts = parseAtom();
        NormalizedPost first = posts.get(0);

        assertNotNull(first.publishedAt(),
            "publishedAt must be non-null for an ISO-8601-bearing <published>");
        assertEquals(Instant.parse("2026-01-15T10:00:00Z"), first.publishedAt());
    }

    @Test
    void rssFixtureProducesThreePosts() throws IOException {
        List<NormalizedPost> posts = parseRss();
        assertEquals(3, posts.size(),
            "the rss20-sample fixture has three <item> elements");
        for (NormalizedPost post : posts) {
            assertEquals(SOURCE_ID, post.sourceId());
            assertEquals(FETCHED_AT, post.fetchedAt());
            assertNotNull(post.body(),
                "NormalizedPost.body must never be null per the SPI contract");
            assertTrue(post.rawMetadata().isEmpty(),
                "rawMetadata is an empty Map for RSS posts in v1");
        }
    }

    @Test
    void atomFixtureProducesTwoPosts() throws IOException {
        List<NormalizedPost> posts = parseAtom();
        assertEquals(2, posts.size(),
            "the atom-sample fixture has two <entry> elements");
    }

    @Test
    void atomMinimalEntryFallsBackToFirstUnrelLink() throws IOException {
        List<NormalizedPost> posts = parseAtom();
        // Second entry has a single <link href="..."/> without rel.
        NormalizedPost second = posts.get(1);

        assertEquals("https://example.com/atom/2", second.url(),
            "url falls back to the first rel-less <link> when no alternate is present");
    }

    @Test
    void parseRaisesOnUnrecognizedRootElement() {
        String xml = "<?xml version=\"1.0\"?><opml><body/></opml>";
        RssFeedParseException ex = assertThrows(RssFeedParseException.class, () ->
            RssFeedParser.parse(SOURCE_ID, xml.getBytes(StandardCharsets.UTF_8), FETCHED_AT));

        assertTrue(ex.getMessage().toLowerCase().contains("root"),
            "unrecognized-root exception message must mention the root element");
    }

    private List<NormalizedPost> parseRss() throws IOException {
        return RssFeedParser.parse(SOURCE_ID, Files.readAllBytes(RSS_FIXTURE), FETCHED_AT);
    }

    private List<NormalizedPost> parseAtom() throws IOException {
        return RssFeedParser.parse(SOURCE_ID, Files.readAllBytes(ATOM_FIXTURE), FETCHED_AT);
    }
}
