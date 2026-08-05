package app.zcat.infochat.provider.digest;

import app.zcat.infochat.core.ingest.IngestTextNormalizer;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.summary.EligiblePostQuery;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DegradedDigestRendererTest {

    private final DegradedDigestRenderer renderer = new DegradedDigestRenderer();

    {
        // Real closed-list sanitizer with the audit write stubbed out (no DB) —
        // exercises the actual redaction logic. Field-injected in production;
        // set directly here (same package). M1-675.
        renderer.llmOutputSanitizer = SanitizerTestDoubles.noAuditSanitizer();
    }

    @Test
    void render_producesHeadlinesOnly() {
        List<EligiblePostQuery.Post> posts = List.of(
                post("uid-1", "Bitcoin hits $100k", "TechCrunch", "https://tc.com/btc"),
                post("uid-2", "Ethereum update", "CoinDesk", "https://cd.com/eth"));

        String result = renderer.render(posts, "en");

        assertTrue(result.contains("Bitcoin hits $100k"), "first headline present");
        assertTrue(result.contains("TechCrunch"), "first source attribution present");
        assertTrue(result.contains("https://tc.com/btc"), "first URL present (bare)");
        assertTrue(result.contains("Ethereum update"), "second headline present");
        assertTrue(result.contains("CoinDesk"), "second source attribution present");
        assertTrue(result.contains("https://cd.com/eth"), "second URL present (bare)");

        assertFalse(result.contains("["), "no markdown link syntax");
        assertFalse(result.contains("]("), "no markdown link syntax");

        // Verify structure: two blocks separated by blank line
        String[] blocks = result.split("\n\n");
        assertEquals(2, blocks.length, "two post blocks separated by blank line");
    }

    @Test
    void render_redactsCommandShapedTitle() {
        // The degraded digest is a group broadcast rendered with no LLM in the
        // path, so a title shaped like a privileged command would otherwise
        // reach every member at line start. M1-675.
        List<EligiblePostQuery.Post> posts = List.of(
                post("uid-1", "/grant-admin 11111111-2222-3333-4444-555555555555",
                        "EvilFeed", "https://evil.example/x"));

        String result = renderer.render(posts, "en");

        assertTrue(result.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "command-shaped title must be redacted; got: " + result);
        assertFalse(result.contains("/grant-admin"),
                "the raw privileged command must not survive into the digest; got: " + result);
    }

    @Test
    void render_passesLegitSlashTitleByteIdentical() {
        // A title with a non-command slash (TCP/IP) is not a closed-list token,
        // so it must pass through untouched — no over-redaction. M1-675.
        List<EligiblePostQuery.Post> posts = List.of(
                post("uid-1", "TCP/IP explained", "NetNews", "https://net.example/tcpip"));

        String result = renderer.render(posts, "en");

        assertTrue(result.contains("TCP/IP explained"),
                "legit-slash title must render byte-identical; got: " + result);
        assertFalse(result.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "a non-command slash must not trigger redaction; got: " + result);
    }

    @Test
    void renderLeavesLinkAdjacencyVerbatim_layeringCarriedAtOutboundDelivery() {
        // M1-691 layering pin. sourceDisplayName can carry ]( — /add-source
        // --name rejects slashes but no bracket (SourceUpsertService
        // .acceptableOverride) — and the renderer does NOT sanitize it: the
        // no-link guarantee is carried once at OutboundDelivery, not at this
        // render site. This test pins the DELIBERATE leak so a future edit
        // that "fixes" the renderer to sanitize locally breaks here and
        // forces the editor to read the assembly-point comment naming
        // OutboundDelivery. (The title is sanitized; only the joined display
        // name and url are the unsanitized operands, and neither can carry a
        // closed-list token — every command starts with /, which --name
        // rejects and a stored url cannot lead with.)
        List<EligiblePostQuery.Post> posts = List.of(
                post("uid-1", "Legit headline", "Acme](https://evil.example/x",
                        "https://evil.example/x"));

        String result = renderer.render(posts, "en");

        assertTrue(result.contains("Acme](https://evil.example/x"),
                "the renderer returns the ](-bearing display name VERBATIM — the no-link "
                        + "guarantee is carried at OutboundDelivery (M1-691), not at this render "
                        + "site; got: " + result);
        assertTrue(result.contains("Legit headline"),
                "the sanitized title is still rendered; got: " + result);
    }

    @Test
    void render_emptyTitleFallsBackToBody() {
        // Every Bluesky post has an empty title, so without the fallback the
        // entry would open with a bare " — SourceName". M1-714.
        List<EligiblePostQuery.Post> posts = List.of(
                post("uid-1", "", "Bluesky", "https://bsky.app/p/1", "Body carries the text"));

        String result = renderer.render(posts, "en");

        assertTrue(result.startsWith("Body carries the text — Bluesky"),
                "an empty title must fall back to the body; got: " + result);
    }

    @Test
    void render_emptyTitleAndBodyOmitsHeadlineAndItsSeparator() {
        // No placeholder is invented; the entry leads with the source display
        // name and must not open with a dangling separator. M1-714.
        List<EligiblePostQuery.Post> posts = List.of(
                post("uid-1", "", "Bluesky", "https://bsky.app/p/1", ""));

        String result = renderer.render(posts, "en");

        assertTrue(result.startsWith("Bluesky"),
                "a post with no renderable text must lead with the source name; got: " + result);
        assertFalse(result.startsWith(" — "),
                "the separator must drop out with the headline; got: " + result);
        assertTrue(result.contains("https://bsky.app/p/1"),
                "the url still renders — it is the payload of an image-only post; got: " + result);
    }

    @Test
    void render_longTitleTruncatedWithEllipsis() {
        // A nitter title averages 334 characters and runs to 24,776; unbounded
        // it buries the entry it labels. M1-714.
        String longTitle = "y".repeat(400);
        List<EligiblePostQuery.Post> posts = List.of(
                post("uid-1", longTitle, "Nitter", "https://nitter.example/1", "body"));

        String result = renderer.render(posts, "en");

        assertFalse(result.contains(longTitle),
                "an over-long title must not render in full; got length " + result.length());
        assertTrue(result.contains("…"),
                "a cut headline must carry the trailing ellipsis; got: " + result);
    }

    @Test
    void render_nonEnglishPostLeadsWithAnchorAndBracketsTheOriginal() {
        // The whole point of M1-766: the anchor was computed at ingest, so the
        // one path guaranteed not to have a working model can still show an
        // English reader something they can read. The anchor line is BARE
        // because it IS in the reader's language; the publisher's own words
        // follow bracketed (D29 (c)).
        List<EligiblePostQuery.Post> posts = List.of(
                anchoredPost("uid-1", "Bitcoin dosáhl 100 tisíc", "Bitcoin hits $100k", "cs"));

        String result = renderer.render(posts, "en");

        assertTrue(result.startsWith("Bitcoin hits $100k — CzechFeed"),
                "the English anchor leads, unbracketed, with the source name on its line; got: "
                        + result);
        assertTrue(result.contains("\n[Bitcoin dosáhl 100 tisíc]"),
                "the publisher's own words follow bracketed on their own line; got: " + result);
    }

    @Test
    void render_readerOfTheSourceLanguageKeepsThePublisherWords() {
        // usesAnchor suppression: for a Czech reader of a Czech-source post the
        // publisher's own words ARE the reader-language line, so promoting the
        // anchor would show that reader English. One bare line, no bracket.
        List<EligiblePostQuery.Post> posts = List.of(
                anchoredPost("uid-1", "Bitcoin dosáhl 100 tisíc", "Bitcoin hits $100k", "cs"));

        String result = renderer.render(posts, "cs");

        assertTrue(result.startsWith("Bitcoin dosáhl 100 tisíc — CzechFeed"),
                "a reader of the source language sees the publisher's words; got: " + result);
        assertFalse(result.contains("Bitcoin hits $100k"),
                "the English anchor must NOT be promoted for a reader who reads the source "
                        + "language; got: " + result);
        assertFalse(result.contains("["),
                "an unbracketed line means 'already in your language'; got: " + result);
    }

    @Test
    void render_bracketsBothLinesForAReaderOfNeitherLanguage() {
        // A Czech reader of a German source: the anchor is promoted (de != cs)
        // but it is ENGLISH, so it is not in this reader's language either.
        // Both lines bracket — the degraded path makes no translator call, and
        // leaving the primary bare would be exactly the indistinguishability
        // the bracket exists to remove.
        List<EligiblePostQuery.Post> posts = List.of(
                anchoredPost("uid-1", "Bitcoin erreicht 100k", "Bitcoin hits $100k", "de"));

        String result = renderer.render(posts, "cs");

        assertTrue(result.startsWith("[Bitcoin hits $100k] — CzechFeed"),
                "the promoted anchor brackets for a non-English reader; got: " + result);
        assertTrue(result.contains("\n[Bitcoin erreicht 100k]"),
                "the original brackets beneath it; got: " + result);
    }

    @Test
    void render_titlelessNonEnglishPostRendersFromBodyNotATranslatedSentinel() {
        // M1-729 survives the switch: the field is chosen from the ORIGINAL,
        // then that field's anchor is taken. IngestTranslationWorker has no
        // sentinel guard, so title_en here is a TRANSLATION of the storage
        // placeholder — choosing the field from the anchor would resurrect the
        // headline M1-729 killed and the body fallback would never fire.
        List<EligiblePostQuery.Post> posts = List.of(
                new EligiblePostQuery.Post(
                        UUID.randomUUID(), "uid-1", UUID.randomUUID(), "CzechFeed",
                        IngestTextNormalizer.UNTITLED_TITLE, "https://cz.example/1",
                        "Tělo nese text", Instant.now(), List.of("crypto"), List.of("unknown"),
                        null, null, null, null, "cs", "Untitled", "The body carries the text"));

        String result = renderer.render(posts, "en");

        assertTrue(result.startsWith("The body carries the text — CzechFeed"),
                "a titleless non-English post renders from its BODY anchor; got: " + result);
        assertFalse(result.contains("Untitled"),
                "the translated storage sentinel must never surface as a headline; got: " + result);
    }

    @Test
    void render_emptyTitleAndBodyEmitsNoEmptyBracket() {
        // M1-714's omission contract must survive the second line: dropping the
        // headline drops the subordinate with it, so the entry can never open
        // with a dangling separator NOR carry a bare [].
        List<EligiblePostQuery.Post> posts = List.of(
                new EligiblePostQuery.Post(
                        UUID.randomUUID(), "uid-1", UUID.randomUUID(), "Bluesky",
                        "", "https://bsky.app/p/1", "", Instant.now(),
                        List.of("crypto"), List.of("unknown"),
                        null, null, null, null, "cs", "Anchor never reached", null));

        String result = renderer.render(posts, "en");

        assertTrue(result.startsWith("Bluesky"),
                "a post with no renderable text leads with the source name; got: " + result);
        assertFalse(result.contains("[]"),
                "the omission must never render as an empty bracket; got: " + result);
        assertFalse(result.contains("Anchor never reached"),
                "an anchor on a field the original could not supply is not rendered; got: "
                        + result);
    }

    @Test
    void render_redactsAClosedListEntrySpanningTheAnchorAndTheOriginal() {
        // Redteam 2026-08-05, medium/INJECTION. The command word rides the
        // anchor and the flag rides the original, so the two halves land
        // adjacent in the delivered message. Per-line sanitize calls matched
        // neither half and shipped a dispatchable line with no marker and no
        // audit row; the pair takes ONE call, so the flag-bearing entry
        // redacts here the way it would inside a single field.
        List<EligiblePostQuery.Post> posts = List.of(
                anchoredPost("uid-1", "--all", "/list-sources", "cs"));

        String result = renderer.render(posts, "en");

        assertTrue(result.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "a closed-list entry straddling the two lines must redact; got: " + result);
        assertFalse(result.contains("/list-sources"),
                "the command word must not survive; got: " + result);
        assertFalse(result.contains("--all"),
                "the flag must not survive either — the span runs from command word to "
                        + "flag token; got: " + result);
    }

    @Test
    void render_keepsTheUnitAtONEPostSoOneTitleCannotEraseAnother() {
        // The pair is the widest the unit may go. M1-697's cross-post span bug
        // is what bounds it: if two POSTS ever shared a sanitize input, a
        // command word in one post's title and a flag in another's would
        // delete every post between them. Two posts, each carrying one half,
        // must therefore both render intact.
        List<EligiblePostQuery.Post> posts = List.of(
                post("uid-1", "/list-sources", "FeedA", "https://a.example/1"),
                post("uid-2", "--all", "FeedB", "https://b.example/2"));

        String result = renderer.render(posts, "en");

        assertTrue(result.contains("/list-sources") && result.contains("--all"),
                "two posts must never share one sanitize input; got: " + result);
        assertFalse(result.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "a redaction spanning two posts would mean the unit widened past the "
                        + "field pair; got: " + result);
    }

    @Test
    void renderer_hasNoCollaboratorThatCouldReachAProvider() {
        // ZERO PROVIDER CALLS, pinned structurally rather than by counting on a
        // spy: this renderer has no LLM seam to count. It reaches the anchor
        // through a column already on the projection and decides the bracket
        // through TranslationPipeline's STATIC pure helpers, so there is no
        // instance to call. The assertion is therefore "no injected
        // collaborator could make a provider call at all" — which a call-count
        // spy cannot express, and which fails the moment someone adds an
        // @Inject TranslationPipeline here to "translate the degraded digest
        // too". That edit is exactly what docs/spec/security.md §Failure
        // handling forbids on this path.
        List<String> collaborators = Arrays.stream(DegradedDigestRenderer.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic() && !Modifier.isStatic(f.getModifiers()))
                .map(f -> f.getType().getSimpleName())
                .toList();

        assertEquals(List.of("LlmOutputSanitizer"), collaborators,
                "the degraded renderer's only collaborator is the sanitizer — anything else "
                        + "is a potential provider call on the path that exists BECAUSE no model "
                        + "is available; got: " + collaborators);
    }

    /** A post whose stored language is non-English and which carries a title anchor. */
    private static EligiblePostQuery.Post anchoredPost(String uid, String title,
                                                       String titleEn, String sourceLanguage) {
        return new EligiblePostQuery.Post(
                UUID.randomUUID(), uid, UUID.randomUUID(), "CzechFeed",
                title, "https://cz.example/" + uid, "body", Instant.now(),
                List.of("crypto"), List.of("unknown"),
                null, null, null, null, sourceLanguage, titleEn, null);
    }

    private static EligiblePostQuery.Post post(String uid, String title,
                                               String source, String url) {
        return post(uid, title, source, url, "body");
    }

    private static EligiblePostQuery.Post post(String uid, String title,
                                               String source, String url, String body) {
        return new EligiblePostQuery.Post(
                UUID.randomUUID(), uid, UUID.randomUUID(), source,
                title, url, body, Instant.now(), List.of("crypto"), List.of("unknown"));
    }
}
