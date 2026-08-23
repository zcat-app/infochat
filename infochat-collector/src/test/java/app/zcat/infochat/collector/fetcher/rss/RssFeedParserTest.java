package app.zcat.infochat.collector.fetcher.rss;

import app.zcat.infochat.collector.fetcher.PaginationSaturationTracker;
import app.zcat.infochat.collector.fetcher.rss.RssFeedParser.RssFeedParseException;
import app.zcat.infochat.core.ingest.NormalizedPost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // Mirrors the private RssFeedParser.MAX_ITEMS, matching the convention
    // the Reddit and Bluesky cap tests already use. Those two parsers copied
    // their cap from this one and each got a dedicated cap test; the original
    // had none until M1-753, which is why its reject-vs-truncate asymmetry
    // survived undetected.
    private static final int MAX_ITEMS = 1000;

    @BeforeEach
    void clearTruncationSignal() {
        // The truncation flag is a static ThreadLocal shared by every test
        // on this thread. Start each case from a known-clear baseline so an
        // assertion about THIS parse cannot read a previous one's signal.
        drainTruncationSignal();
    }

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
    void atomAuthorAndCategoryLandInRawMetadata() {
        // Reddit's listing Atom is the motivating shape: t3_ id, /u/name
        // author, subreddit category term (M1-915).
        String xml = """
            <?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom">
            <entry>
              <author><name>/u/desrtfx</name><uri>https://www.reddit.com/user/desrtfx</uri></author>
              <category term="java" label="r/java"/>
              <id>t3_j7h9er</id>
              <title>Some title</title>
              <content type="html">body</content>
            </entry>
            <entry><id>t3_noauthor</id><title>Bare</title><content>b</content></entry>
            </feed>""";

        List<NormalizedPost> posts = RssFeedParser.parse(SOURCE_ID, xml.getBytes(StandardCharsets.UTF_8), FETCHED_AT);

        assertEquals(2, posts.size());
        assertEquals("/u/desrtfx", posts.get(0).rawMetadata().get("author"),
            "first <author><name> lands in rawMetadata under 'author'");
        assertEquals("java", posts.get(0).rawMetadata().get("category"),
            "first <category term> lands in rawMetadata under 'category'");
        assertTrue(posts.get(1).rawMetadata().isEmpty(),
            "an entry without author/category keeps empty rawMetadata");
    }

    @Test
    void atomMultipleAuthorsAreFirstWins() {
        // RFC 4287 allows 1..n atom:author per entry; the FIRST name wins
        // (the javadoc's contract, mirrored from the category guard).
        String xml = """
            <?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom">
            <entry>
              <author><name>Alice</name></author>
              <author><name>Bob</name></author>
              <id>t3_multi</id><title>Co-authored</title><content>body</content>
            </entry>
            </feed>""";

        List<NormalizedPost> posts = RssFeedParser.parse(
            SOURCE_ID, xml.getBytes(StandardCharsets.UTF_8), FETCHED_AT);

        assertEquals(1, posts.size());
        assertEquals("Alice", posts.get(0).rawMetadata().get("author"),
            "a second <author> must not overwrite the first — first-wins, "
                + "same as the category capture");
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
            assertEquals(SOURCE_ID, post.dispatchKey());
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

    @Test
    void parseToleratesTwoLeadingSpacesBeforeXmlDeclaration() {
        // rss.xcancel.com serves RSS with two spaces before <?xml. XML 1.0
        // forbids any content ahead of the declaration, so the strict StAX
        // reader rejected it at [row,col]=[1,8] before M1-502; the parser now
        // skips the insignificant whitespace prefix and parses the feed.
        String xml = "  <?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<rss version=\"2.0\"><channel>"
            + "<title>Prefixed feed</title>"
            + "<item><guid>urn:example:post:1</guid>"
            + "<link>https://example.com/posts/1</link>"
            + "<description>hello</description></item>"
            + "</channel></rss>";

        List<NormalizedPost> posts =
            RssFeedParser.parse(SOURCE_ID, xml.getBytes(StandardCharsets.UTF_8), FETCHED_AT);

        assertEquals(1, posts.size(),
            "a feed with a two-space prefix before <?xml must parse its single <item>");
        assertEquals("urn:example:post:1", posts.get(0).upstreamIdentifier(),
            "the prefixed feed's <guid> is the upstreamIdentifier, proving the body parsed");
    }

    @Test
    void parseToleratesMixedLeadingWhitespaceBeforeXmlDeclaration() {
        // Tab, CR, LF, and space ahead of the declaration are all in the XML
        // whitespace set (#x9 #xD #xA #x20) and must all be skipped.
        String xml = "\t\r\n <?xml version=\"1.0\"?>"
            + "<rss version=\"2.0\"><channel>"
            + "<item><guid>urn:example:post:42</guid>"
            + "<description>hi</description></item>"
            + "</channel></rss>";

        List<NormalizedPost> posts =
            RssFeedParser.parse(SOURCE_ID, xml.getBytes(StandardCharsets.UTF_8), FETCHED_AT);

        assertEquals(1, posts.size(),
            "leading tab/CR/LF/space before <?xml must be skipped, leaving a parseable feed");
        assertEquals("urn:example:post:42", posts.get(0).upstreamIdentifier(),
            "the <guid> survives once the whitespace prefix is skipped");
    }

    @Test
    void parseRaisesOnAllWhitespaceBody() {
        // The whitespace tolerance must not mask a contentless feed: a body
        // that is ENTIRELY whitespace has no root element and still raises.
        String xml = "   \t\r\n  ";
        RssFeedParseException ex = assertThrows(RssFeedParseException.class, () ->
            RssFeedParser.parse(SOURCE_ID, xml.getBytes(StandardCharsets.UTF_8), FETCHED_AT));

        String message = ex.getMessage().toLowerCase();
        assertTrue(message.contains("root") || message.contains("xml stream"),
            "an all-whitespace body must still raise — no root element / empty stream, "
                + "not a silently-empty success");
    }

    @Test
    void rssFeedOverCapTruncatesToMaxItemsAndSignalsCapHit() {
        byte[] body = rssFeedWith(MAX_ITEMS + 1).getBytes(StandardCharsets.UTF_8);

        List<NormalizedPost> posts = assertDoesNotThrow(
            () -> RssFeedParser.parse(SOURCE_ID, body, FETCHED_AT),
            "an over-cap RSS feed must be truncated, not rejected — rejecting it "
                + "discarded the MAX_ITEMS items that parsed cleanly (M1-753)");

        assertEquals(MAX_ITEMS, posts.size(),
            "an over-cap feed yields exactly MAX_ITEMS posts");
        assertTrue(drainTruncationSignal(),
            "truncation must not be silent: the parser signals the cap hit "
                + "out-of-band so the scheduler can surface it");
    }

    @Test
    void atomFeedOverCapTruncatesToMaxItemsAndSignalsCapHit() {
        byte[] body = atomFeedWith(MAX_ITEMS + 1).getBytes(StandardCharsets.UTF_8);

        List<NormalizedPost> posts = assertDoesNotThrow(
            () -> RssFeedParser.parse(SOURCE_ID, body, FETCHED_AT),
            "the Atom <entry> loop carries its own copy of the cap check; fixing "
                + "only the RSS path would leave the identical defect live here");

        assertEquals(MAX_ITEMS, posts.size(),
            "an over-cap Atom feed yields exactly MAX_ITEMS posts");
        assertTrue(drainTruncationSignal(),
            "the Atom path signals the cap hit exactly as the RSS path does");
    }

    @Test
    void rssFeedAtExactlyMaxItemsParsesCleanlyWithoutCapHitSignal() {
        byte[] body = rssFeedWith(MAX_ITEMS).getBytes(StandardCharsets.UTF_8);

        List<NormalizedPost> posts = assertDoesNotThrow(
            () -> RssFeedParser.parse(SOURCE_ID, body, FETCHED_AT),
            "a feed carrying precisely the cap is not over it");

        assertEquals(MAX_ITEMS, posts.size(),
            "every item of an exactly-at-cap feed survives — the boundary is "
                + "inclusive, and nothing pinned it before M1-753");
        assertFalse(drainTruncationSignal(),
            "a feed that exactly fills the cap was not truncated, so it must NOT "
                + "raise the saturation signal — otherwise every at-cap source "
                + "would alarm without losing a single item");
    }

    @Test
    void atomFeedAtExactlyMaxItemsParsesCleanlyWithoutCapHitSignal() {
        byte[] body = atomFeedWith(MAX_ITEMS).getBytes(StandardCharsets.UTF_8);

        List<NormalizedPost> posts = assertDoesNotThrow(
            () -> RssFeedParser.parse(SOURCE_ID, body, FETCHED_AT),
            "a feed carrying precisely the cap is not over it");

        assertEquals(MAX_ITEMS, posts.size(),
            "every entry of an exactly-at-cap Atom feed survives");
        assertFalse(drainTruncationSignal(),
            "an exactly-at-cap Atom feed was not truncated and must not signal");
    }

    @Test
    void rssTruncationKeepsTheFirstMaxItemsInDocumentOrder() {
        byte[] body = rssFeedWith(MAX_ITEMS + 5).getBytes(StandardCharsets.UTF_8);

        List<NormalizedPost> posts = RssFeedParser.parse(SOURCE_ID, body, FETCHED_AT);

        // WHICH items survive is a specified behaviour, not an accident of
        // loop order: the first MAX_ITEMS in document order. RSS conventionally
        // publishes newest-first, so in practice that is the newest MAX_ITEMS —
        // a convention, not a guarantee (M1-753 §Ordering caveat).
        assertEquals("urn:example:post:0", posts.get(0).upstreamIdentifier(),
            "truncation keeps the document-order prefix, so item 0 survives");
        assertEquals("urn:example:post:" + (MAX_ITEMS - 1),
            posts.get(MAX_ITEMS - 1).upstreamIdentifier(),
            "the last surviving item is the cap-th in document order; items "
                + "beyond it are the ones dropped");
        drainTruncationSignal();
    }

    @Test
    void atomTruncationKeepsTheFirstMaxItemsInDocumentOrder() {
        byte[] body = atomFeedWith(MAX_ITEMS + 5).getBytes(StandardCharsets.UTF_8);

        List<NormalizedPost> posts = RssFeedParser.parse(SOURCE_ID, body, FETCHED_AT);

        assertEquals("urn:example:entry:0", posts.get(0).upstreamIdentifier(),
            "the Atom path truncates on the same document-order prefix rule");
        assertEquals("urn:example:entry:" + (MAX_ITEMS - 1),
            posts.get(MAX_ITEMS - 1).upstreamIdentifier(),
            "the last surviving entry is the cap-th in document order");
        drainTruncationSignal();
    }

    @Test
    void rssTruncationStopsConsumingTheStreamAtTheCap() {
        // The allocation bound is the reason the cap exists, and truncating
        // preserves it ONLY if the parser stops READING at the cap rather than
        // parsing the whole payload and trimming afterwards. Both strategies
        // return MAX_ITEMS posts, so a size assertion cannot tell them apart.
        // This one can: the item just past the cap is malformed (neither <guid>
        // nor <link>), which parseRssItem rejects. A parser that stops at the
        // cap never reaches it; a parse-everything-then-subList parser raises.
        String xml = rssFeedWith(MAX_ITEMS).replace("</channel></rss>",
            "<item><title>never reached</title>"
                + "<description>no guid, no link</description></item>"
                + "</channel></rss>");
        byte[] body = xml.getBytes(StandardCharsets.UTF_8);

        List<NormalizedPost> posts = assertDoesNotThrow(
            () -> RssFeedParser.parse(SOURCE_ID, body, FETCHED_AT),
            "the parser must stop consuming at the cap — reaching the malformed "
                + "item past it proves the payload was walked in full, which is "
                + "the allocation bound the cap exists to enforce");

        assertEquals(MAX_ITEMS, posts.size(),
            "the surviving prefix is unaffected by what follows the cap");
        assertTrue(drainTruncationSignal(),
            "stopping at the cap is still a truncation and still signals");
    }

    @Test
    void atomTruncationStopsConsumingTheStreamAtTheCap() {
        // Atom twin of the RSS stop-consuming test: the entry past the cap
        // omits the RFC 4287-required <id>, which parseAtomEntry rejects.
        String xml = atomFeedWith(MAX_ITEMS).replace("</feed>",
            "<entry><title>never reached</title>"
                + "<content>no id</content></entry>"
                + "</feed>");
        byte[] body = xml.getBytes(StandardCharsets.UTF_8);

        List<NormalizedPost> posts = assertDoesNotThrow(
            () -> RssFeedParser.parse(SOURCE_ID, body, FETCHED_AT),
            "the Atom loop must stop consuming at the cap for the same reason");

        assertEquals(MAX_ITEMS, posts.size(),
            "the surviving Atom prefix is unaffected by what follows the cap");
        assertTrue(drainTruncationSignal(),
            "the Atom path signals its truncation too");
    }

    private List<NormalizedPost> parseRss() throws IOException {
        return RssFeedParser.parse(SOURCE_ID, Files.readAllBytes(RSS_FIXTURE), FETCHED_AT);
    }

    private List<NormalizedPost> parseAtom() throws IOException {
        return RssFeedParser.parse(SOURCE_ID, Files.readAllBytes(ATOM_FIXTURE), FETCHED_AT);
    }

    /**
     * Consume-and-clear the thread-local truncation flag, returning whether
     * the parse under test raised it. This is both the assertion and the
     * cleanup: the flag is a static ThreadLocal that only
     * {@code consumeTruncation()} clears, so leaving it set would leak into
     * the next test on this thread. Instantiated directly rather than
     * injected — the flag is static, and this class is a plain JUnit test
     * with no CDI container.
     */
    private static boolean drainTruncationSignal() {
        return new PaginationSaturationTracker().consumeTruncation();
    }

    private static String rssFeedWith(int itemCount) {
        StringBuilder xml = new StringBuilder(
            "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel>"
                + "<title>Archive feed</title>");
        for (int i = 0; i < itemCount; i++) {
            xml.append("<item><guid>urn:example:post:").append(i).append("</guid>")
                .append("<title>Item ").append(i).append("</title>")
                .append("<description>body ").append(i).append("</description></item>");
        }
        return xml.append("</channel></rss>").toString();
    }

    private static String atomFeedWith(int entryCount) {
        StringBuilder xml = new StringBuilder(
            "<?xml version=\"1.0\"?><feed xmlns=\"http://www.w3.org/2005/Atom\">"
                + "<title>Archive feed</title>");
        for (int i = 0; i < entryCount; i++) {
            xml.append("<entry><id>urn:example:entry:").append(i).append("</id>")
                .append("<title>Entry ").append(i).append("</title>")
                .append("<content>body ").append(i).append("</content></entry>");
        }
        return xml.append("</feed>").toString();
    }
}
