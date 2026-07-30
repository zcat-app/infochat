package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.summary.EligiblePostQuery;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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

        String result = renderer.render(posts);

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

        String result = renderer.render(posts);

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

        String result = renderer.render(posts);

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

        String result = renderer.render(posts);

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

        String result = renderer.render(posts);

        assertTrue(result.startsWith("Body carries the text — Bluesky"),
                "an empty title must fall back to the body; got: " + result);
    }

    @Test
    void render_emptyTitleAndBodyOmitsHeadlineAndItsSeparator() {
        // No placeholder is invented; the entry leads with the source display
        // name and must not open with a dangling separator. M1-714.
        List<EligiblePostQuery.Post> posts = List.of(
                post("uid-1", "", "Bluesky", "https://bsky.app/p/1", ""));

        String result = renderer.render(posts);

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

        String result = renderer.render(posts);

        assertFalse(result.contains(longTitle),
                "an over-long title must not render in full; got length " + result.length());
        assertTrue(result.contains("…"),
                "a cut headline must carry the trailing ellipsis; got: " + result);
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
