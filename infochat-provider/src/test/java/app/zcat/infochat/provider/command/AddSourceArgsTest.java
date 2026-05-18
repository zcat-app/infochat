package app.zcat.infochat.provider.command;

import app.zcat.infochat.provider.command.AddSourceArgs.Failure;
import app.zcat.infochat.provider.command.AddSourceArgs.ParseResult;
import app.zcat.infochat.provider.command.AddSourceArgs.Success;
import app.zcat.infochat.provider.source.KindResolver.SourceKind;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 unit tests for {@link AddSourceArgs#parse(String)}.
 * Per acceptance item §"AddSourceArgs" in
 * {@code docs/plan/m1/tickets/M1-036-add-source-command.md}: the
 * parser produces a typed {@link Failure} carrying the bundle key the
 * handler should surface, for each of the spec'd rejection paths:
 *
 * <ul>
 *   <li>(a) missing {@code --tags} → {@code error.add_source.tags_required}</li>
 *   <li>(b) empty {@code --tags=} → same key</li>
 *   <li>(c) unknown {@code --type} → {@code error.add_source.unknown_kind}
 *       (with a fuzzy-suggestion footer interpolation listing the closed
 *       enum)</li>
 *   <li>(d) unknown {@code --category} → {@code error.add_source.unknown_category}
 *       (enumerating {@code news|blog|social})</li>
 *   <li>(e) malformed URL (no scheme / invalid host) →
 *       {@code error.add_source.malformed_url}</li>
 * </ul>
 *
 * <p>The happy-path test confirms positional URL + {@code --tags} +
 * optional flags parse to a {@link Success} populated with the
 * normalized tag list, the {@link SourceKind} the {@code --type}
 * resolves to, and the {@code --name}/{@code --category} overrides
 * when supplied.</p>
 */
class AddSourceArgsTest {

    @Test
    void happyPathParsesPositionalUrlAndTagsCsv() {
        ParseResult result = AddSourceArgs.parse(
                "/add-source https://example.com/feed.xml --tags news,tech");

        Success success = assertInstanceOf(Success.class, result);
        AddSourceArgs args = success.args();
        assertEquals("https://example.com/feed.xml", args.url().toString());
        assertEquals(2, args.tags().size());
        assertEquals("news", args.tags().get(0));
        assertEquals("tech", args.tags().get(1));
        assertEquals(Optional.empty(), args.typeOverride());
        assertEquals("news", args.category(),
                "category default is 'news' per AddSourceArgs.DEFAULT_CATEGORY");
        assertEquals(Optional.empty(), args.displayNameOverride());
    }

    @Test
    void honorsExplicitTypeFlagWithEqualsForm() {
        ParseResult result = AddSourceArgs.parse(
                "/add-source https://example.com/feed.xml --tags news --type=rss");

        Success success = assertInstanceOf(Success.class, result);
        assertEquals(Optional.of(SourceKind.RSS), success.args().typeOverride());
    }

    @Test
    void honorsExplicitTypeFlagWithSpaceForm() {
        ParseResult result = AddSourceArgs.parse(
                "/add-source https://example.com/feed --tags news --type rss");

        Success success = assertInstanceOf(Success.class, result);
        assertEquals(Optional.of(SourceKind.RSS), success.args().typeOverride());
    }

    @Test
    void honorsExplicitCategoryFlag() {
        ParseResult result = AddSourceArgs.parse(
                "/add-source https://example.com/feed.xml --tags news --category=blog");

        Success success = assertInstanceOf(Success.class, result);
        assertEquals("blog", success.args().category());
    }

    @Test
    void honorsExplicitDisplayNameFlagWithQuotedValue() {
        ParseResult result = AddSourceArgs.parse(
                "/add-source https://example.com/feed.xml --tags news --name \"Example Feed\"");

        Success success = assertInstanceOf(Success.class, result);
        assertEquals(Optional.of("Example Feed"), success.args().displayNameOverride());
    }

    @Test
    void tagsNormalizationLowercasesViaNfc() {
        ParseResult result = AddSourceArgs.parse(
                "/add-source https://example.com/feed.xml --tags NEWS,Tech");

        Success success = assertInstanceOf(Success.class, result);
        assertEquals("news", success.args().tags().get(0));
        assertEquals("tech", success.args().tags().get(1));
    }

    // (a) missing --tags
    @Test
    void missingTagsFlagSurfacesTagsRequiredBundleKey() {
        ParseResult result = AddSourceArgs.parse(
                "/add-source https://example.com/feed.xml");
        Failure failure = assertInstanceOf(Failure.class, result);
        assertEquals("error.add_source.tags_required", failure.bundleKey());
    }

    // (b) empty --tags=
    @Test
    void emptyTagsEqualsValueSurfacesTagsRequiredBundleKey() {
        ParseResult result = AddSourceArgs.parse(
                "/add-source https://example.com/feed.xml --tags=");
        Failure failure = assertInstanceOf(Failure.class, result);
        assertEquals("error.add_source.tags_required", failure.bundleKey());
    }

    @Test
    void blankTagsValueAfterSplitSurfacesTagsRequiredBundleKey() {
        // --tags ,,,  ⇒ all elements blank after split
        ParseResult result = AddSourceArgs.parse(
                "/add-source https://example.com/feed.xml --tags ,,,");
        Failure failure = assertInstanceOf(Failure.class, result);
        assertEquals("error.add_source.tags_required", failure.bundleKey());
    }

    // (c) unknown --type
    @Test
    void unknownTypeFlagSurfacesUnknownKindBundleKey() {
        ParseResult result = AddSourceArgs.parse(
                "/add-source https://example.com/feed.xml --tags news --type=mastodon");
        Failure failure = assertInstanceOf(Failure.class, result);
        assertEquals("error.add_source.unknown_kind", failure.bundleKey());
        assertEquals(2, failure.interpolationArgs().size(),
                "unknown_kind failure carries (suppliedValue, validKindsCommaList)");
        assertEquals("mastodon", failure.interpolationArgs().get(0));
        // The closed enum list is the fuzzy-suggestion footer per spec
        // §Source management's unknown-type friendly error.
        assertTrue(failure.interpolationArgs().get(1).contains("rss"),
                "valid-kinds list must include rss");
        assertTrue(failure.interpolationArgs().get(1).contains("bluesky"),
                "valid-kinds list must include bluesky");
    }

    // (d) unknown --category
    @Test
    void unknownCategoryFlagSurfacesUnknownCategoryBundleKey() {
        ParseResult result = AddSourceArgs.parse(
                "/add-source https://example.com/feed.xml --tags news --category=podcast");
        Failure failure = assertInstanceOf(Failure.class, result);
        assertEquals("error.add_source.unknown_category", failure.bundleKey());
        assertEquals("news, blog, social", failure.interpolationArgs().get(1),
                "category enumeration must be the spec-closed news|blog|social trio");
    }

    // (e) malformed URL
    @Test
    void malformedUrlMissingSchemeSurfacesMalformedUrlBundleKey() {
        ParseResult result = AddSourceArgs.parse(
                "/add-source example.com/feed.xml --tags news");
        Failure failure = assertInstanceOf(Failure.class, result);
        assertEquals("error.add_source.malformed_url", failure.bundleKey());
    }

    @Test
    void malformedUrlInvalidSyntaxSurfacesMalformedUrlBundleKey() {
        ParseResult result = AddSourceArgs.parse(
                "/add-source http:// --tags news");
        Failure failure = assertInstanceOf(Failure.class, result);
        assertEquals("error.add_source.malformed_url", failure.bundleKey());
    }

    @Test
    void noPositionalUrlSurfacesMalformedUrlBundleKey() {
        ParseResult result = AddSourceArgs.parse(
                "/add-source --tags news");
        Failure failure = assertInstanceOf(Failure.class, result);
        assertEquals("error.add_source.malformed_url", failure.bundleKey());
    }
}
