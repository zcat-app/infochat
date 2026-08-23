package app.zcat.infochat.collector.fetcher.rss;

import app.zcat.infochat.collector.fetcher.PaginationSaturationTracker;
import app.zcat.infochat.core.ingest.NormalizedPost;
import org.jspecify.annotations.Nullable;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stateless RSS 2.0 / Atom 1.0 XML parser using the JDK's
 * {@code javax.xml.stream} (StAX) API.
 *
 * <p>Dialect routing: read the root element name. {@code <rss>} → RSS
 * 2.0; {@code <feed>} with the Atom namespace
 * ({@code http://www.w3.org/2005/Atom}) → Atom 1.0; anything else
 * raises {@link RssFeedParseException}.
 *
 * <p>Per-field mapping into {@link NormalizedPost}:
 * <ul>
 *   <li><b>RSS 2.0 / item</b>: upstreamIdentifier ← {@code <guid>} else
 *       {@code <link>} else raise; title ← {@code <title>} (nullable);
 *       body ← {@code <description>} (raw HTML preserved); url ←
 *       {@code <link>}; publishedAt ← {@code <pubDate>} parsed per
 *       RFC 1123 / RFC 822, nullable on parse failure.</li>
 *   <li><b>Atom 1.0 / entry</b>: upstreamIdentifier ← {@code <id>}
 *       (required, raise if absent); title ← {@code <title>} (nullable);
 *       body ← {@code <content>} (raw); url ← {@code href} of
 *       {@code <link rel="alternate">} or first {@code <link>} without
 *       {@code rel}; publishedAt ← {@code <published>} parsed per
 *       RFC 3339 / ISO 8601, nullable on parse failure; rawMetadata ←
 *       first {@code <author><name>} as {@code author} and first
 *       {@code <category term>} as {@code category} when present
 *       (reddit's listing Atom is the motivating source).</li>
 * </ul>
 *
 * <p>HTML stripping, NFKC normalization, and redaction live at Stage 1
 * downstream of the outbox per {@code docs/spec/security.md} §Ingest
 * pipeline (security side) — NOT here. This parser passes the raw
 * {@code <description>} / {@code <content>} text into
 * {@code NormalizedPost.body} unmodified; a reader who sees raw HTML
 * in a NormalizedPost should expect it.
 */
public final class RssFeedParser {

    private static final String ATOM_NS = "http://www.w3.org/2005/Atom";

    // Per-parse item-count cap. A normal feed publishes 10–500 items;
    // 1000 is an order of magnitude above legitimate use, small enough
    // to bound the allocation against a hostile feed serving an
    // unbounded item list.
    //
    // An over-cap feed is TRUNCATED to the first MAX_ITEMS items in
    // document order, not rejected. Rejecting is not the stricter
    // security posture it appears to be: against a hostile feed the
    // throw and the truncation stop at the same item and bound the
    // allocation identically, so the throw bought no security — while
    // against a legitimate archive feed it discarded the whole payload,
    // including the MAX_ITEMS items that had parsed cleanly. The cost
    // was total and permanent: M1-753 found a 1105-item feed that had
    // ingested nothing since the day it was seeded, because every fetch
    // failed identically. The cap's value is NOT what changed here —
    // only what happens on reaching it.
    //
    // The bound is enforced by stopping the read, not by trimming
    // afterwards: the check fires on the cap+1-th item's START_ELEMENT,
    // BEFORE that item is parsed, and breaks out of the read loop. The
    // remaining bytes are never walked and no post beyond MAX_ITEMS is
    // ever allocated, so a feed of any size costs O(MAX_ITEMS) posts.
    //
    // Truncation is not silent. RssFeedParser is a static utility with
    // no logger that receives a dispatchKey and never the source UUID,
    // so it cannot name the source it clipped; the signal instead
    // travels the established out-of-band path,
    // PaginationSaturationTracker.signalTruncation(), which the
    // scheduler drains immediately after fetch() returns and turns into
    // a throttled, durably-recorded admin notification.
    //
    // It is deliberately NOT signalCapHit(), the pagination-saturation
    // flag. That flag notifies only after N CONSECUTIVE saturated ticks,
    // and any non-saturated tick clears the streak — so a feed
    // oscillating across the cap (1001/1001/999, repeating) would reset
    // the counter every third tick, never notify, and silently discard
    // content on the other two. That is not a hypothetical: any feed
    // hovering near MAX_ITEMS oscillates naturally. Truncation is
    // therefore reported per occurrence and coalesced by the notifier's
    // own throttle window, not by a streak.
    private static final int MAX_ITEMS = 1000;

    private RssFeedParser() {
        // static-only
    }

    public static List<NormalizedPost> parse(long dispatchKey, byte[] body, Instant fetchedAt) {
        // External entity / DTD loading is disabled: feed payloads are
        // arbitrary remote XML; following a DTD reference would be an
        // XXE + outbound-network vector that the Stage-0 SSRF gate (still
        // pending) cannot see. The parser only needs the XML grammar
        // built into StAX.
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        // Tolerate insignificant leading whitespace before the XML
        // declaration. XML 1.0 forbids ANY content — even whitespace —
        // ahead of `<?xml`, so the strict StAX reader rejects it
        // (ParseError at [1,N]); some lenient RSS hosts (rss.xcancel.com
        // emits two spaces) still prefix it. Advance past the XML
        // whitespace set (#x20 #x9 #xD #xA) so a well-formed document
        // follows at the new offset. The skip is a no-op when the first
        // byte is already `<`, so well-formed feeds parse byte-identically.
        // A byte-order mark is intentionally NOT skipped — StAX auto-detects
        // it for encoding selection (M1-502).
        int offset = 0;
        while (offset < body.length && isXmlWhitespace(body[offset])) {
            offset++;
        }

        XMLStreamReader reader;
        try {
            reader = factory.createXMLStreamReader(
                new ByteArrayInputStream(body, offset, body.length - offset));
        } catch (XMLStreamException e) {
            throw new RssFeedParseException("Failed to open XML stream: " + e.getMessage(), e);
        }

        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String localName = reader.getLocalName();
                    String ns = reader.getNamespaceURI();
                    if ("rss".equals(localName)) {
                        return parseRss(reader, dispatchKey, fetchedAt);
                    } else if ("feed".equals(localName) && ATOM_NS.equals(ns)) {
                        return parseAtom(reader, dispatchKey, fetchedAt);
                    } else {
                        throw new RssFeedParseException(
                            "Unrecognized root element: <" + localName + "> (ns=" + ns + ")");
                    }
                }
            }
            throw new RssFeedParseException("No root element found in feed");
        } catch (XMLStreamException e) {
            throw new RssFeedParseException("XML stream error: " + e.getMessage(), e);
        } finally {
            try {
                reader.close();
            } catch (XMLStreamException ignored) {
                // close failures on a parsed payload are not actionable
            }
        }
    }

    private static List<NormalizedPost> parseRss(
            XMLStreamReader reader, long dispatchKey, Instant fetchedAt) throws XMLStreamException {
        List<NormalizedPost> posts = new ArrayList<>();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT && "item".equals(reader.getLocalName())) {
                // Truncate at the cap: stop before parsing the cap+1-th
                // item, so the stream is never walked past it. See
                // MAX_ITEMS for why this truncates instead of raising.
                if (posts.size() == MAX_ITEMS) {
                    PaginationSaturationTracker.signalTruncation();
                    break;
                }
                posts.add(parseRssItem(reader, dispatchKey, fetchedAt));
            }
        }
        return posts;
    }

    private static NormalizedPost parseRssItem(
            XMLStreamReader reader, long dispatchKey, Instant fetchedAt) throws XMLStreamException {
        String guid = null;
        String link = null;
        String title = null;
        String description = null;
        String pubDate = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.END_ELEMENT && "item".equals(reader.getLocalName())) {
                break;
            }
            if (event == XMLStreamConstants.START_ELEMENT) {
                String name = reader.getLocalName();
                switch (name) {
                    case "guid"        -> guid = readTextContent(reader).trim();
                    case "link"        -> link = readTextContent(reader).trim();
                    case "title"       -> title = readTextContent(reader);
                    case "description" -> description = readTextContent(reader);
                    case "pubDate"     -> pubDate = readTextContent(reader).trim();
                    default            -> readTextContent(reader);
                }
            }
        }

        String upstreamIdentifier;
        if (guid != null && !guid.isEmpty()) {
            upstreamIdentifier = guid;
        } else if (link != null && !link.isEmpty()) {
            upstreamIdentifier = link;
        } else {
            // SPI contract: upstreamIdentifier must be non-null. An item
            // with neither <guid> nor <link> is a malformed feed.
            throw new RssFeedParseException(
                "RSS <item> has neither <guid> nor <link>; cannot derive upstreamIdentifier");
        }

        Instant publishedAt = parseRfc1123(pubDate);

        return new NormalizedPost(
            dispatchKey,
            upstreamIdentifier,
            title,
            description == null ? "" : description,
            (link == null || link.isEmpty()) ? null : link,
            publishedAt,
            fetchedAt,
            Map.of()
        );
    }

    private static List<NormalizedPost> parseAtom(
            XMLStreamReader reader, long dispatchKey, Instant fetchedAt) throws XMLStreamException {
        List<NormalizedPost> posts = new ArrayList<>();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT && "entry".equals(reader.getLocalName())) {
                // Same truncation contract as the RSS <item> loop above;
                // Atom carries its own copy of the check because it is a
                // separate read loop, and a fix applied to only one of
                // them leaves the defect live on the other dialect.
                if (posts.size() == MAX_ITEMS) {
                    PaginationSaturationTracker.signalTruncation();
                    break;
                }
                posts.add(parseAtomEntry(reader, dispatchKey, fetchedAt));
            }
        }
        return posts;
    }

    private static NormalizedPost parseAtomEntry(
            XMLStreamReader reader, long dispatchKey, Instant fetchedAt) throws XMLStreamException {
        String id = null;
        String title = null;
        String content = null;
        String published = null;
        String alternateHref = null;
        String firstUnrelHref = null;
        String authorName = null;
        String categoryTerm = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.END_ELEMENT && "entry".equals(reader.getLocalName())) {
                break;
            }
            if (event == XMLStreamConstants.START_ELEMENT) {
                String name = reader.getLocalName();
                switch (name) {
                    case "id"        -> id = readTextContent(reader).trim();
                    case "title"     -> title = readTextContent(reader);
                    case "content"   -> content = readTextContent(reader);
                    case "published" -> published = readTextContent(reader).trim();
                    // First author wins (RFC 4287 allows 1..n), mirroring
                    // the category guard; every <author> is still drained
                    // so the stream advances.
                    case "author" -> {
                        if (authorName == null) {
                            authorName = readAuthorName(reader);
                        } else {
                            readAuthorName(reader);
                        }
                    }
                    case "category" -> {
                        // First category wins; reddit's listing Atom carries
                        // the subreddit here (term="java" label="r/java").
                        if (categoryTerm == null) {
                            categoryTerm = reader.getAttributeValue(null, "term");
                        }
                        readTextContent(reader);
                    }
                    case "link" -> {
                        // Atom <link> is empty-element with attributes;
                        // capture href + rel on START, then drain.
                        String rel = reader.getAttributeValue(null, "rel");
                        String href = reader.getAttributeValue(null, "href");
                        if ("alternate".equals(rel)) {
                            if (alternateHref == null) {
                                alternateHref = href;
                            }
                        } else if (rel == null) {
                            if (firstUnrelHref == null) {
                                firstUnrelHref = href;
                            }
                        }
                        // Self-links, enclosures, etc. are intentionally
                        // dropped — NormalizedPost v1 has one url field.
                        readTextContent(reader);
                    }
                    default -> readTextContent(reader);
                }
            }
        }

        if (id == null || id.isEmpty()) {
            // Atom RFC 4287 requires every <entry> to carry an <id>; a
            // missing id is a malformed feed.
            throw new RssFeedParseException(
                "Atom <entry> missing <id>; cannot derive upstreamIdentifier");
        }

        Instant publishedAt = parseIso8601(published);

        String url = alternateHref != null ? alternateHref : firstUnrelHref;

        Map<String, String> metadata = Map.of();
        if (authorName != null && categoryTerm != null) {
            metadata = Map.of("author", authorName, "category", categoryTerm);
        } else if (authorName != null) {
            metadata = Map.of("author", authorName);
        } else if (categoryTerm != null) {
            metadata = Map.of("category", categoryTerm);
        }

        return new NormalizedPost(
            dispatchKey,
            id,
            title,
            content == null ? "" : content,
            url,
            publishedAt,
            fetchedAt,
            metadata
        );
    }

    /**
     * Reads an {@code <author>} element's nested {@code <name>} text
     * (RFC 4287 atom:author/atom:name). Returns null when the author
     * carries no name. The reader is positioned on the author
     * START_ELEMENT and leaves it on the matching END_ELEMENT.
     */
    private static @Nullable String readAuthorName(XMLStreamReader reader) throws XMLStreamException {
        String name = null;
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            int event = reader.next();
            switch (event) {
                case XMLStreamConstants.START_ELEMENT -> {
                    depth++;
                    if (depth == 2 && "name".equals(reader.getLocalName())) {
                        name = readTextContent(reader);
                        depth--;
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> depth--;
                default -> {
                    // uri, email, text — ignored
                }
            }
        }
        return name == null || name.isBlank() ? null : name.strip();
    }

    /**
     * Read the text content of the currently-open element (the reader
     * is positioned on a START_ELEMENT). Concatenates CHARACTERS and
     * CDATA events; advances through and discards nested START/END
     * elements (so {@code <content type="xhtml"><div>...</div></content>}
     * does not corrupt the walker, though its inner XML is not
     * captured — Atom xhtml-typed content is rare in v1 sources).
     */
    private static String readTextContent(XMLStreamReader reader) throws XMLStreamException {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            int event = reader.next();
            switch (event) {
                case XMLStreamConstants.CHARACTERS,
                     XMLStreamConstants.CDATA,
                     XMLStreamConstants.SPACE,
                     XMLStreamConstants.ENTITY_REFERENCE -> {
                    if (depth == 1) {
                        sb.append(reader.getText());
                    }
                }
                case XMLStreamConstants.START_ELEMENT -> depth++;
                case XMLStreamConstants.END_ELEMENT   -> depth--;
                default -> {
                    // PI, comments, etc. — ignore
                }
            }
        }
        return sb.toString();
    }

    // XML 1.0 §2.3 white-space production (S): space, tab, CR, LF. Used only
    // to skip an insignificant whitespace prefix ahead of the declaration
    // (M1-502); high bytes (e.g. a 0xEF BOM lead) are not whitespace, so the
    // skip stops and leaves them for StAX.
    private static boolean isXmlWhitespace(byte b) {
        return b == ' ' || b == '\t' || b == '\r' || b == '\n';
    }

    private static @Nullable Instant parseRfc1123(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static @Nullable Instant parseIso8601(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Unchecked parse failure. Raised on malformed feeds (no root,
     * unrecognized root, item with no identifier, entry with no id).
     * The Fetcher SPI does not declare checked exceptions; the
     * FetchScheduler's per-tick error handler catches and counts.
     */
    public static final class RssFeedParseException extends RuntimeException {
        public RssFeedParseException(String message) {
            super(message);
        }

        public RssFeedParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
