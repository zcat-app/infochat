package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.summary.SummaryProseGenerator.ClusterProse;
import app.zcat.infochat.messaging.TranslationProvider;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import app.zcat.infochat.provider.translation.TranslationCache;
import app.zcat.infochat.provider.translation.TranslationPipeline;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain-JUnit test for {@link ClusterBlockRenderer} — the renderer shared by
 * {@code /summary} and {@code /retry} (U-47). Pins the bundle-resolved cluster
 * labels (U-43) in both {@code en} and {@code cs}, including the
 * {@code {0,choice,...}} score plural across Czech's three-form plural
 * (1 zdroj / 2 zdroje / 5 zdrojů). No {@code @QuarkusTest}.
 *
 * <p>Every fixture cluster uses <em>degraded</em> prose so the LLM
 * sanitize&rarr;translate path is bypassed (D43) and the assertions isolate
 * the deterministic label layer; the en single-source case is asserted
 * byte-for-byte as the byte-identical-replay guard. The fixture prose
 * string is a marker the renderer IGNORES — degraded prose is derived
 * from the cluster at render (M1-697), so the {@code summary:} line shows
 * the derived {@code title — url (uid)} composition, and the marker's
 * absence from the output is itself part of the pin.
 */
class ClusterBlockRendererTest {

    /** The fixture rendering scope — the display-hit cache-partition dimensions. */
    private static final String SCOPE_KIND = "group";
    private static final UUID SCOPE_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private ClusterBlockRenderer renderer;
    private BundleLoader bundleLoader;

    @BeforeEach
    void setUp() {
        bundleLoader = new BundleLoader();
        try {
            var method = BundleLoader.class.getDeclaredMethod("load");
            method.setAccessible(true);
            method.invoke(bundleLoader);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize BundleLoader for test", e);
        }
        // The bare TranslationPipeline's collaborators are never dereferenced
        // here: all fixtures use degraded prose, which the renderer derives
        // from the cluster (M1-697) without touching the pipeline, and a
        // degraded cluster skips the headline's display-hit leg (M1-747)
        // outright — the leg would no-op anyway for these fixtures (en scope
        // for the en cases, null sourceLanguage from the compat constructors
        // for the cs cases). The translating leg is exercised by
        // csScopeTranslatesHeadline... below with its own wired pipeline.
        renderer = new ClusterBlockRenderer(
                SanitizerTestDoubles.noAuditSanitizer(), new TranslationPipeline(), bundleLoader);
    }

    @Test
    void enClusterBlockRendersLabelsAndSingularScoreByteForByte() {
        Cluster cluster = clusterWithSources(1, List.of("a"));
        String rendered = render(cluster, "en");

        assertEquals(
                "[topic_id=t-1]\n"
                        + "Headline\n"
                        + "covered by: Src1 (uid p-1)\n"
                        + "score: 1 source\n"
                        + "summary: Headline — https://example.com/p-1 (uid p-1)\n"
                        + "classification: factual\n"
                        + "tags: a\n"
                        + "\n",
                rendered,
                "en cluster block must render verbatim (byte-identical-replay guard); "
                        + "classification (factual) is independent of tags (a)");
    }

    @Test
    void enScorePluralRendersSourcesForMultipleSources() {
        assertTrue(render(clusterWithSources(2, List.of("a")), "en").contains("score: 2 sources\n"),
                "en score line pluralizes for 2 sources");
        assertTrue(render(clusterWithSources(5, List.of("a")), "en").contains("score: 5 sources\n"),
                "en score line pluralizes for 5 sources");
    }

    @Test
    void csClusterBlockRendersTranslatedLabelsByteForByte() {
        Cluster cluster = clusterWithSources(1, List.of("a"));
        String rendered = render(cluster, "cs");

        assertEquals(
                "[topic_id=t-1]\n"
                        + "Headline\n"
                        + "pokrývají: Src1 (uid p-1)\n"
                        + "skóre: 1 zdroj\n"
                        + "shrnutí: Headline — https://example.com/p-1 (uid p-1)\n"
                        + "klasifikace: factual\n"
                        + "tagy: a\n"
                        + "\n",
                rendered,
                "cs cluster block must render translated labels verbatim; the "
                        + "classification value (factual) stays verbatim, only the label is translated");
    }

    @Test
    void csScorePluralRendersCzechThreeForms() {
        assertTrue(render(clusterWithSources(1, List.of("a")), "cs").contains("skóre: 1 zdroj\n"),
                "cs score: one-form (1 zdroj)");
        assertTrue(render(clusterWithSources(2, List.of("a")), "cs").contains("skóre: 2 zdroje\n"),
                "cs score: few-form (2 zdroje)");
        assertTrue(render(clusterWithSources(5, List.of("a")), "cs").contains("skóre: 5 zdrojů\n"),
                "cs score: many-form (5 zdrojů)");
    }

    @Test
    void classificationUnionDropsUnknownWhenSubstantiveLabelPresent() {
        // Cross-post union {factual, unknown} → the no-signal `unknown` is
        // dropped, leaving only the substantive label.
        Cluster cluster = clusterWithPerPostClassification(
                List.of(List.of("factual"), List.of("unknown")));
        String rendered = render(cluster, "en");

        assertTrue(rendered.contains("classification: factual\n"),
                "substantive label rendered when the union mixes it with unknown");
        assertFalse(rendered.contains("unknown"),
                "`unknown` dropped from the union when a substantive label is present");
    }

    @Test
    void classificationRendersUnknownWhenUnionIsOnlyUnknown() {
        // Whole union is exactly {unknown} → the line shows `unknown` (always
        // populated, never mirrors tags).
        Cluster cluster = clusterWithPerPostClassification(List.of(List.of("unknown")));
        String rendered = render(cluster, "en");

        assertTrue(rendered.contains("classification: unknown\n"),
                "sole-unknown union renders classification: unknown");
    }

    @Test
    void csScopeTranslatesHeadlineOfDifferingSourceLanguagePostWithBracketedOriginal() throws Exception {
        // The renderer-level pin for the M1-747 display-hit wiring: without
        // this case, deleting the runForDisplayHit call in appendClusterBlock
        // would keep every test green (the pipeline-level tests prove the leg
        // works WHEN called; only this proves the renderer calls it). The
        // subordinate line is asserted alongside it so the block SHAPE is
        // pinned end to end. NON-degraded prose on purpose: a degraded cluster
        // skips the leg (see the degraded pin below), so only this shape
        // exercises the wiring.
        ClusterBlockRenderer translatingRenderer = new ClusterBlockRenderer(
                SanitizerTestDoubles.noAuditSanitizer(),
                translatingPipeline((text, from, to) -> "Přeložený titulek"),
                bundleLoader);
        Cluster cluster = clusterWithSourceLanguage("en");

        StringBuilder out = new StringBuilder();
        translatingRenderer.appendClusterBlock(
                out, new ClusterProse(cluster, "Prose.", false), "cs",
                SCOPE_KIND, SCOPE_ID);
        String rendered = out.toString();

        assertTrue(rendered.startsWith(
                        "[topic_id=t-1]\nPřeložený titulek\n[Original headline]\n"),
                "cs-scope block must lead with the translated headline UNBRACKETED — it is in "
                        + "the reader's language — and carry the publisher's own words on the "
                        + "bracketed line beneath it; got: " + rendered);
    }

    @Test
    void degradedClusterMakesNoTranslatorCallAndRendersHeadlineUntranslated() throws Exception {
        // [redteam 2026-08-03, low/DOS] The degraded branch exists because
        // the LLM path already failed (security.md §Failure handling pins
        // degraded = headlines + URLs + UIDs, no prose) — the first
        // implementation ran the display-hit leg ahead of the degraded
        // check, turning the cost-shedding path into one translator
        // round-trip per cluster. The post's differing sourceLanguage in a
        // cs scope is exactly the shape that WOULD translate on the
        // non-degraded path above, so a zero call count pins the skip.
        AtomicInteger translatorCalls = new AtomicInteger();
        ClusterBlockRenderer degradedRenderer = new ClusterBlockRenderer(
                SanitizerTestDoubles.noAuditSanitizer(),
                translatingPipeline((text, from, to) -> {
                    translatorCalls.incrementAndGet();
                    return "Přeložený titulek";
                }),
                bundleLoader);
        Cluster cluster = clusterWithSourceLanguage("en");

        StringBuilder out = new StringBuilder();
        degradedRenderer.appendClusterBlock(
                out, new ClusterProse(cluster, "Degraded prose.", true), "cs",
                SCOPE_KIND, SCOPE_ID);
        String rendered = out.toString();

        assertEquals(0, translatorCalls.get(),
                "a degraded cluster must make ZERO translator calls; got a call for: " + rendered);
        assertTrue(rendered.startsWith("[topic_id=t-1]\n[Original headline]\n"),
                "the degraded headline renders untranslated and therefore BRACKETED: skipping "
                        + "the leg leaves the primary line in a language the cs reader did not "
                        + "ask for, and a bare line there is exactly what D29 (c)'s invariant "
                        + "forbids. The bracket is punctuation — it costs no translator call, "
                        + "which the assertion above pins; got: " + rendered);
    }

    @Test
    void englishReaderOfANonEnglishAnchoredPostRendersTheAnchorWithZeroTranslatorCalls() throws Exception {
        // The case D29's amendment exists for, and the one a regression is
        // most expensive in: for the DEFAULT reader the anchor is a COLUMN
        // READ. If this ever becomes a model call it puts generative cost on
        // every English result set in the deployment.
        AtomicInteger translatorCalls = new AtomicInteger();
        ClusterBlockRenderer renderer = new ClusterBlockRenderer(
                SanitizerTestDoubles.noAuditSanitizer(),
                translatingPipeline((text, from, to) -> {
                    translatorCalls.incrementAndGet();
                    return "should never be called";
                }),
                bundleLoader);
        Cluster cluster = anchoredCluster("tr", "English anchor headline");

        StringBuilder out = new StringBuilder();
        renderer.appendClusterBlock(
                out, new ClusterProse(cluster, "Prose.", false), "en",
                SCOPE_KIND, SCOPE_ID);
        String rendered = out.toString();

        assertEquals(0, translatorCalls.get(),
                "reading the anchor column must make ZERO translator calls; got a call for: "
                        + rendered);
        assertTrue(rendered.startsWith(
                        "[topic_id=t-1]\nEnglish anchor headline\n[Original headline]\n"),
                "the anchor renders UNBRACKETED in the primary slot (it is English, and so is "
                        + "the reader) above the bracketed original; got: " + rendered);
    }

    @Test
    void englishReaderOfANonEnglishPostWithNoAnchorGetsTheOriginalBracketed() throws Exception {
        // The anchor-absent case: the ingest translator never reached this
        // post, or gave up. The original is promoted to the primary slot but
        // must NOT render bare — bare is indistinguishable from a genuinely
        // English post, which is the whole thing the bracket invariant fixes.
        // Repairing the missing anchor is collector-side (M1-760), never a
        // display-time retry.
        AtomicInteger translatorCalls = new AtomicInteger();
        ClusterBlockRenderer renderer = new ClusterBlockRenderer(
                SanitizerTestDoubles.noAuditSanitizer(),
                translatingPipeline((text, from, to) -> {
                    translatorCalls.incrementAndGet();
                    return "should never be called";
                }),
                bundleLoader);
        Cluster cluster = anchoredCluster("tr", null);

        StringBuilder out = new StringBuilder();
        renderer.appendClusterBlock(
                out, new ClusterProse(cluster, "Prose.", false), "en",
                SCOPE_KIND, SCOPE_ID);
        String rendered = out.toString();

        assertEquals(0, translatorCalls.get(),
                "a NULL anchor must NOT trigger a display-time translator retry; got a call for: "
                        + rendered);
        assertTrue(rendered.startsWith("[topic_id=t-1]\n[Original headline]\n"),
                "a non-English original in the primary slot must be bracketed, and must not "
                        + "repeat as its own subordinate line; got: " + rendered);
    }

    @Test
    void aFeedTitleImpersonatingTheRedactedCommandMarkerCannotForgeIt() throws Exception {
        // [redteam 2026-08-04, low/INJECTION] The renderer supplies the
        // brackets, so an attacker only has to supply the CONTENTS. A bare
        // `redacted command` title carries no leading `/`, so it survives
        // Stage 1 and the closed list byte-for-byte and would otherwise be
        // wrapped into a string byte-identical to a real redaction — for a
        // post that was never flagged and produced no audit row.
        AtomicInteger translatorCalls = new AtomicInteger();
        ClusterBlockRenderer renderer = new ClusterBlockRenderer(
                SanitizerTestDoubles.noAuditSanitizer(),
                translatingPipeline((text, from, to) -> {
                    translatorCalls.incrementAndGet();
                    return "should never be called";
                }),
                bundleLoader);
        Cluster cluster = new Cluster("t-1", List.of(new Post(
                UUID.randomUUID(), "p-1", UUID.randomUUID(), "Src1",
                "redacted command", "https://example.com/p-1", "body",
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of("a"), List.of("factual"),
                null, null, null, null, "tr", null, null)));

        StringBuilder out = new StringBuilder();
        renderer.appendClusterBlock(
                out, new ClusterProse(cluster, "Prose.", false), "en",
                SCOPE_KIND, SCOPE_ID);
        String rendered = out.toString();

        assertFalse(rendered.contains("[redacted command]"),
                "a feed title must never be wrapped into a string byte-identical to "
                        + "LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT — the threat model "
                        + "commits that literal for exact-match recognition; got: " + rendered);
        assertTrue(rendered.contains("[ redacted command ]"),
                "the collision is broken by a renderer-authored space inside each bracket, "
                        + "which no feed text can reproduce (flatten strips outer whitespace), "
                        + "and the publisher's words stay readable; got: " + rendered);
    }

    @Test
    void aFeedTitleImpersonatingTheStage1PlaceholderCannotForgeIt() throws Exception {
        // The same forgery against the OTHER spec-committed literal. The
        // per-row random <id> is what the threat model names as stopping a
        // pre-crafted placeholder, and that argument holds only while the
        // attacker must supply the brackets too — they do not have to guess
        // the id to produce something a reader reads as a placeholder.
        ClusterBlockRenderer renderer = new ClusterBlockRenderer(
                SanitizerTestDoubles.noAuditSanitizer(),
                translatingPipeline((text, from, to) -> "should never be called"),
                bundleLoader);
        Cluster cluster = new Cluster("t-1", List.of(new Post(
                UUID.randomUUID(), "p-1", UUID.randomUUID(), "Src1",
                "REDACTED:9f3a2c11", "https://example.com/p-1", "body",
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of("a"), List.of("factual"),
                null, null, null, null, "tr", null, null)));

        StringBuilder out = new StringBuilder();
        renderer.appendClusterBlock(
                out, new ClusterProse(cluster, "Prose.", false), "en",
                SCOPE_KIND, SCOPE_ID);
        String rendered = out.toString();

        assertFalse(rendered.contains("[REDACTED:9f3a2c11]"),
                "a feed title must never be wrapped into the Stage 1 placeholder shape; got: "
                        + rendered);
        assertTrue(rendered.contains("[ REDACTED:9f3a2c11 ]"),
                "the shape match breaks the collision regardless of the id; got: " + rendered);
    }

    @Test
    void aFeedTitleClosingTheRendererBracketCannotForgeEitherMarker() throws Exception {
        // [redteam 2026-08-04 round 2, low/INJECTION] Round 1 compared the
        // WHOLE wrapped string, so it closed only a payload that supplies no
        // bracket of its own. A title carrying its own `]` pairs with the
        // renderer's OPENING bracket and leaves the committed literal as a
        // SUBSTRING of the delivered line, which reads to an operator exactly
        // as a redaction that never happened.
        String rendered = renderForeignTitleIntoEnglishScope("redacted command] x");

        assertFalse(rendered.contains("[redacted command]"),
                "a title closing the renderer's opening bracket must not synthesize "
                        + "LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT anywhere in the "
                        + "line; got: " + rendered);
        assertTrue(rendered.contains("[ redacted command] x ]"),
                "and the wrap must still have happened — this pins that the assertion above "
                        + "is not passing because the payload never reached the bracket; got: "
                        + rendered);

        String placeholder = renderForeignTitleIntoEnglishScope("REDACTED:9f3a2c11] x");

        assertFalse(placeholder.contains("[REDACTED:9f3a2c11]"),
                "the same payload shape must not synthesize a Stage 1 placeholder; got: "
                        + placeholder);
        assertTrue(placeholder.contains("[ REDACTED:9f3a2c11] x ]"),
                "and the wrap must still have happened; got: " + placeholder);
    }

    @Test
    void aFeedTitleOpeningABracketAtItsEndCannotForgeEitherMarker() throws Exception {
        // The symmetric half: an UNTERMINATED marker at the end of the title
        // is completed by the renderer's CLOSING bracket. Bare on main it
        // forges nothing — the wrap is what supplies the `]` — so both
        // brackets have to be broken, not just the opening one.
        String rendered = renderForeignTitleIntoEnglishScope("x [redacted command");

        assertFalse(rendered.contains("[redacted command]"),
                "a title whose unterminated marker is closed by the renderer's own bracket "
                        + "must not forge the literal either; got: " + rendered);
        assertTrue(rendered.contains("[ x [redacted command ]"),
                "and the wrap must still have happened; got: " + rendered);

        String placeholder = renderForeignTitleIntoEnglishScope("x [REDACTED:9f3a2c11");

        assertFalse(placeholder.contains("[REDACTED:9f3a2c11]"),
                "nor the Stage 1 placeholder; got: " + placeholder);
        assertTrue(placeholder.contains("[ x [REDACTED:9f3a2c11 ]"),
                "and the wrap must still have happened; got: " + placeholder);
    }

    /**
     * Render a single-post cluster carrying {@code title} on a non-English
     * source into an {@code en} scope — the shortest route to
     * {@code DisplayHeadline.bracketed}, since a declared source language
     * differing from the reader's is what brackets the primary line. The
     * anchor is absent and the translator returns a value that would fail
     * every assertion below, so a display-time translation cannot pass
     * unnoticed.
     */
    private String renderForeignTitleIntoEnglishScope(String title) throws Exception {
        ClusterBlockRenderer bracketRenderer = new ClusterBlockRenderer(
                SanitizerTestDoubles.noAuditSanitizer(),
                translatingPipeline((text, from, to) -> "should never be called"),
                bundleLoader);
        Cluster cluster = new Cluster("t-1", List.of(new Post(
                UUID.randomUUID(), "p-1", UUID.randomUUID(), "Src1",
                title, "https://example.com/p-1", "body",
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of("a"), List.of("factual"),
                null, null, null, null, "tr", null, null)));

        StringBuilder out = new StringBuilder();
        bracketRenderer.appendClusterBlock(
                out, new ClusterProse(cluster, "Prose.", false), "en",
                SCOPE_KIND, SCOPE_ID);
        return out.toString();
    }

    @Test
    void aGenuineRedactionInsideAHeadlineStillRendersItsMarkerExactly() throws Exception {
        // The control: the fix must not weaken exact-match recognition of a
        // REAL redaction. A privileged-command title is redacted by the
        // sanitizer to the committed literal, and wrapping that as the
        // subordinate line yields [[redacted command]] — the inner literal
        // still byte-exact, so an operator's exact-match grep still finds it.
        ClusterBlockRenderer renderer = new ClusterBlockRenderer(
                SanitizerTestDoubles.noAuditSanitizer(),
                translatingPipeline((text, from, to) -> "Přeložený titulek"),
                bundleLoader);
        Cluster cluster = new Cluster("t-1", List.of(new Post(
                UUID.randomUUID(), "p-1", UUID.randomUUID(), "Src1",
                "/ban someone", "https://example.com/p-1", "body",
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of("a"), List.of("factual"),
                null, null, null, null, "en", null, null)));

        StringBuilder out = new StringBuilder();
        renderer.appendClusterBlock(
                out, new ClusterProse(cluster, "Prose.", false), "cs",
                SCOPE_KIND, SCOPE_ID);
        String rendered = out.toString();

        assertTrue(rendered.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "a genuine redaction must still render its literal byte-exact, so exact-match "
                        + "recognition and audit correlation keep working; got: " + rendered);
    }

    /**
     * A single-post cluster whose post declares {@code sourceLanguage} —
     * the 15-component compat {@link Post} constructor, so the English
     * anchor is absent — for the M1-747 renderer-wiring pins above.
     */
    private static Cluster clusterWithSourceLanguage(String sourceLanguage) {
        return new Cluster("t-1", List.of(new Post(
                UUID.randomUUID(),
                "p-1",
                UUID.randomUUID(),
                "Src1",
                "Original headline",
                "https://example.com/p-1",
                "body",
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of("a"),
                List.of("factual"),
                null, null, null, null,
                // The declared source language differing from the cs scope is
                // what routes the headline through the translating leg.
                sourceLanguage)));
    }

    /**
     * As {@link #clusterWithSourceLanguage}, but on the 17-component
     * canonical constructor so {@code titleEn} can be set — null for the
     * anchor-ABSENT case.
     */
    private static Cluster anchoredCluster(String sourceLanguage, @Nullable String titleEn) {
        return new Cluster("t-1", List.of(new Post(
                UUID.randomUUID(),
                "p-1",
                UUID.randomUUID(),
                "Src1",
                "Original headline",
                "https://example.com/p-1",
                "body",
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of("a"),
                List.of("factual"),
                null, null, null, null,
                sourceLanguage,
                titleEn,
                null)));
    }

    /**
     * A {@link TranslationPipeline} whose translator is the supplied stub,
     * wired reflectively like
     * {@code TranslationFixtures.newEnShortCircuitPipeline} — that fixture's
     * identity translator cannot exercise the translating leg (an identity
     * translation is deliberately delivered unbracketed).
     */
    private TranslationPipeline translatingPipeline(TranslationProvider translator) throws Exception {
        TranslationPipeline pipeline = new TranslationPipeline();
        var cacheField = TranslationPipeline.class.getDeclaredField("translationCache");
        cacheField.setAccessible(true);
        cacheField.set(pipeline, new TranslationCache());
        var providerField = TranslationPipeline.class.getDeclaredField("translationProvider");
        providerField.setAccessible(true);
        providerField.set(pipeline, translator);
        var sanitizerField = TranslationPipeline.class.getDeclaredField("llmOutputSanitizer");
        sanitizerField.setAccessible(true);
        sanitizerField.set(pipeline, SanitizerTestDoubles.noAuditSanitizer());
        var bundleLoaderField = TranslationPipeline.class.getDeclaredField("bundleLoader");
        bundleLoaderField.setAccessible(true);
        bundleLoaderField.set(pipeline, bundleLoader);
        return pipeline;
    }

    @Test
    void headlineShapedLikeCommandIsRedacted() {
        // The cluster headline is the first post's title and renders at line
        // start in a group-visible /summary reply; a command-shaped title
        // must be closed-list-redacted, not echoed. M1-675.
        String rendered = render(
                clusterWithHeadline("/grant-admin 11111111-2222-3333-4444-555555555555"), "en");

        assertTrue(rendered.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "command-shaped headline must be redacted; got: " + rendered);
        assertFalse(rendered.contains("/grant-admin"),
                "the raw privileged command must not survive into the cluster block; got: " + rendered);
    }

    @Test
    void headlineWithNonCommandSlashRendersByteIdentical() {
        // A non-command slash (TCP/IP) is not a closed-list token, so the
        // headline passes through untouched — no over-redaction, and the
        // byte-identical-replay property is preserved. M1-675.
        String rendered = render(clusterWithHeadline("TCP/IP explained"), "en");

        assertTrue(rendered.contains("TCP/IP explained\n"),
                "legit-slash headline must render byte-identical; got: " + rendered);
        assertFalse(rendered.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "a non-command slash must not trigger redaction; got: " + rendered);
    }

    @Test
    void emptyTitleRendersBodyAsHeadline() {
        // All 729 Bluesky posts in the live corpus have an empty title; 728 of
        // them carry usable body text, so the fallback resolves nearly all of
        // them and the block no longer leads with a blank line. M1-714.
        String rendered = render(clusterWithTitleAndBody("", "Body becomes the headline"), "en");

        assertTrue(rendered.startsWith("[topic_id=t-1]\nBody becomes the headline\ncovered by: "),
                "an empty title must fall back to the body as the headline; got: " + rendered);
        assertFalse(rendered.contains("[topic_id=t-1]\n\n"),
                "the block must not emit a blank headline line; got: " + rendered);
    }

    @Test
    void emptyTitleAndBodyOmitsTheHeadlineLineEntirely() {
        // No placeholder is invented for a post with no renderable text: the
        // headline line is omitted outright, so topic_id is followed directly
        // by covered-by with no blank line between them. M1-714.
        String rendered = render(clusterWithTitleAndBody("", ""), "en");

        assertTrue(rendered.startsWith("[topic_id=t-1]\ncovered by: "),
                "the headline line must be omitted, not blank; got: " + rendered);
        assertFalse(rendered.contains("[topic_id=t-1]\n\n"),
                "omitting the headline must not leave a blank line; got: " + rendered);
    }

    @Test
    void flaggedSpanBeyondTheTruncationPointStillWritesItsAuditRow() {
        // Sanitize-then-truncate ordering pin. The command sits well past the
        // display bound, so a truncate-first implementation would cut it away
        // before the sanitizer ever saw it — losing the LLM_OUTPUT_SANITIZED
        // audit row that docs/spec/security.md commits to (counted, never
        // throttled: one row per distinct token per call carrying the exact
        // occurrence count). M1-714; aggregated shape per M1-737.
        List<RedactionHook.AuditRow> auditRows = new ArrayList<>();
        AuditLogWriter capturingWriter = new AuditLogWriter(row -> row) {
            @Override
            public void write(Connection conn, RedactionHook.AuditRow row) {
                auditRows.add(row);
            }
        };
        ClusterBlockRenderer auditingRenderer = new ClusterBlockRenderer(
                new LlmOutputSanitizer(capturingWriter, SanitizerTestDoubles.noOpDataSource()),
                new TranslationPipeline(), bundleLoader);

        String farPastTheBound = "x".repeat(300)
                + "/grant-admin 11111111-2222-3333-4444-555555555555";
        StringBuilder out = new StringBuilder();
        auditingRenderer.appendClusterBlock(
                out,
                new ClusterProse(clusterWithTitleAndBody(farPastTheBound, "body"),
                        "Degraded prose.", true),
                "en", SCOPE_KIND, SCOPE_ID);
        String rendered = out.toString();

        assertFalse(auditRows.isEmpty(),
                "the flagged span sits beyond the truncation point, but the sanitizer "
                        + "must still have seen the FULL title and written its audit row — "
                        + "an empty list means truncation ran first");
        assertTrue(auditRows.stream()
                        .allMatch(row -> row.action() == AuditAction.LLM_OUTPUT_SANITIZED),
                "every emitted row must carry action LLM_OUTPUT_SANITIZED; got: " + auditRows);
        assertTrue(auditRows.stream()
                        .allMatch(row -> row.detailsJson() != null
                                && row.detailsJson().contains("\"match_count\":1")),
                "one row per distinct token per call carrying the exact count — each "
                        + "sanitize call saw the single /grant-admin occurrence once; got: "
                        + auditRows);
        assertFalse(rendered.contains("/grant-admin"),
                "the raw privileged command must not survive into the block; got: " + rendered);
    }

    private String render(Cluster cluster, String language) {
        StringBuilder out = new StringBuilder();
        renderer.appendClusterBlock(out, new ClusterProse(cluster, "Degraded prose.", true), language,
                SCOPE_KIND, SCOPE_ID);
        return out.toString();
    }

    /**
     * Build a cluster of {@code sourceCount} posts each carrying a distinct
     * source display name (Src1..SrcN), so the score line's distinct-source
     * count equals {@code sourceCount}. The headline is the first post's title.
     */
    private static Cluster clusterWithSources(int sourceCount, List<String> tags) {
        List<Post> posts = new ArrayList<>();
        for (int i = 1; i <= sourceCount; i++) {
            posts.add(new Post(
                    UUID.randomUUID(),
                    "p-" + i,
                    UUID.randomUUID(),
                    "Src" + i,
                    i == 1 ? "Headline" : "Headline " + i,
                    "https://example.com/p-" + i,
                    "body",
                    Instant.parse("2026-01-01T00:00:00Z"),
                    tags,
                    // classification seeded DISTINCT from tags so the two rendered
                    // lines are shown genuinely independent (not the M1-591 mirror).
                    List.of("factual")));
        }
        return new Cluster("t-1", posts);
    }

    /**
     * Build a single-post cluster whose headline (first post's title) is the
     * supplied string, for the M1-675 headline-redaction tests.
     */
    private static Cluster clusterWithHeadline(String headline) {
        List<Post> posts = new ArrayList<>();
        posts.add(new Post(
                UUID.randomUUID(),
                "p-1",
                UUID.randomUUID(),
                "Src1",
                headline,
                "https://example.com/p-1",
                "body",
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of("a"),
                List.of("factual")));
        return new Cluster("t-1", posts);
    }

    /**
     * Build a single-post cluster with the supplied title and body, for the
     * M1-714 headline-fallback and headline-omission tests.
     */
    private static Cluster clusterWithTitleAndBody(String title, String body) {
        return new Cluster("t-1", List.of(new Post(
                UUID.randomUUID(),
                "p-1",
                UUID.randomUUID(),
                "Src1",
                title,
                "https://example.com/p-1",
                body,
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of("a"),
                List.of("factual"))));
    }

    /**
     * Build a cluster with one post per supplied classification list (all
     * sharing a fixed tag {@code a}), so a test can exercise the cross-post
     * classification union + the {@code unknown} drop rule independent of tags.
     */
    private static Cluster clusterWithPerPostClassification(List<List<String>> perPostClassification) {
        List<Post> posts = new ArrayList<>();
        for (int i = 0; i < perPostClassification.size(); i++) {
            posts.add(new Post(
                    UUID.randomUUID(),
                    "p-" + (i + 1),
                    UUID.randomUUID(),
                    "Src" + (i + 1),
                    i == 0 ? "Headline" : "Headline " + (i + 1),
                    "https://example.com/p-" + (i + 1),
                    "body",
                    Instant.parse("2026-01-01T00:00:00Z"),
                    List.of("a"),
                    perPostClassification.get(i)));
        }
        return new Cluster("t-1", posts);
    }
}
