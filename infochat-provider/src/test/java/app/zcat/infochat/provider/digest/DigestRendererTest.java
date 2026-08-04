package app.zcat.infochat.provider.digest;

import app.zcat.infochat.messaging.TranslationProvider;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.digest.DigestRenderer.DigestMode;
import app.zcat.infochat.provider.summary.ClusterTraversal;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.summary.EmptyEdgeSource;
import app.zcat.infochat.provider.summary.SummaryProseGenerator;
import app.zcat.infochat.provider.summary.SummaryProseGenerator.ClusterProse;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import app.zcat.infochat.provider.translation.TranslationCache;
import app.zcat.infochat.provider.translation.TranslationPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static app.zcat.infochat.provider.testsupport.TranslationFixtures.newEnShortCircuitPipeline;
import static app.zcat.infochat.provider.testsupport.TranslationFixtures.newRealBundleLoader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link DigestRenderer} wires the LLM summarizer pipeline
 * correctly — cluster → categorize → generate → sanitize → translate — and
 * renders the D62 category structure (uppercase headers, per-section item
 * cap, closing affordance) deterministically.
 */
class DigestRendererTest {

    /** The broadcast scope — the display-hit cache-partition dimension (M1-756). */
    private static final UUID GROUP_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    /** The cs bundle's machine-translation marker, appended by the display-hit leg. */
    private static final String CS_MARKER = "[strojový překlad]";

    private DigestRenderer renderer;
    private BundleLoader bundleLoader;
    private RecordingSummaryProseGenerator proseGenerator;
    private RecordingCategoryRollupGenerator rollupGenerator;

    @BeforeEach
    void setUp() throws Exception {
        bundleLoader = newRealBundleLoader();
        renderer = new DigestRenderer();
        renderer.clusterTraversal = new ClusterTraversal(new EmptyEdgeSource(), 3);
        proseGenerator = new RecordingSummaryProseGenerator();
        renderer.summaryProseGenerator = proseGenerator;
        rollupGenerator = new RecordingCategoryRollupGenerator();
        renderer.categoryRollupGenerator = rollupGenerator;
        renderer.llmOutputSanitizer = SanitizerTestDoubles.noAuditSanitizer();
        renderer.translationPipeline = newEnShortCircuitPipeline(bundleLoader);
        renderer.translationCache = new TranslationCache();
        renderer.digestCategorizer = newCategorizer(3);
        renderer.bundleLoader = bundleLoader;
        renderer.categoryItemCap = 12;
        renderer.categoryHeadlineCount = 5;
        renderer.translationMaxPerRender = 5;
    }

    /** The pre-M1-732 {@code render()} shape: renderSections at the given mode, joined with {@code "\n\n"}. */
    private String renderJoined(List<Post> posts, DigestMode mode) {
        return renderJoined(posts, mode, "en");
    }

    private String renderJoined(List<Post> posts, DigestMode mode, String langCode) {
        return String.join("\n\n",
                renderer.renderSections(posts, langCode, mode, GROUP_ID).stream()
                        .map(DigestRenderer.RenderedSection::text)
                        .toList());
    }

    @Test
    void render_producesLocalizedProse() {
        proseGenerator.setResponseText("LLM digest summary for cluster");

        List<Post> posts = List.of(
                post("uid-1", "Bitcoin hits $100k"),
                post("uid-2", "Ethereum update"));

        String result = renderJoined(posts, DigestMode.FULL);

        assertTrue(result.contains("LLM digest summary for cluster"),
                "LLM prose appears in rendered output");
        assertEquals("en", proseGenerator.lastLanguage(),
                "language code forwarded to prose generator");
        assertTrue(proseGenerator.callCount() > 0,
                "prose generator was invoked");
    }

    @Test
    void rendersUppercaseHeadersOrderedBySizeThenAlphaOtherLast() {
        // M1-725: the lead is disabled here (it is default-on for any
        // >= 6-cluster digest and would promote 3 of these 11 clusters,
        // reshaping the section sizes) — the D62 section ORDER is this
        // test's subject; the lead's own ordering is pinned in
        // DigestRendererSectionsTest.
        renderer.leadMinimum = Integer.MAX_VALUE;
        proseGenerator.setResponseText("story prose");
        // EmptyEdgeSource → one singleton cluster per post, so tag counts
        // are: security=4, ai=3, crypto=3 (all qualify at threshold 3) and
        // one untagged cluster lands in Other.
        List<Post> posts = List.of(
                post("s1", "Sec 1", List.of("security")),
                post("s2", "Sec 2", List.of("security")),
                post("s3", "Sec 3", List.of("security")),
                post("s4", "Sec 4", List.of("security")),
                post("a1", "AI 1", List.of("ai")),
                post("a2", "AI 2", List.of("ai")),
                post("a3", "AI 3", List.of("ai")),
                post("c1", "Crypto 1", List.of("crypto")),
                post("c2", "Crypto 2", List.of("crypto")),
                post("c3", "Crypto 3", List.of("crypto")),
                post("u1", "Untagged", List.of()));

        String result = renderJoined(posts, DigestMode.FULL);

        int security = result.indexOf("SECURITY NEWS");
        int ai = result.indexOf("AI NEWS");
        int crypto = result.indexOf("CRYPTO NEWS");
        int other = result.indexOf("OTHER NEWS");
        assertTrue(security >= 0 && ai >= 0 && crypto >= 0 && other >= 0,
                "all four uppercase headers render: " + result);
        assertTrue(security < ai, "largest category (security, 4 clusters) leads");
        assertTrue(ai < crypto, "equal-size categories tie-break alphabetically (ai before crypto)");
        assertTrue(crypto < other, "Other renders last");
    }

    @Test
    void fullModeLiftsItemCapAndEmitsNoOverflowLine() {
        // M1-732: full keeps the pre-M1-732 per-cluster prose but lifts the
        // item cap to a LOCAL Integer.MAX_VALUE — categoryItemCap is shared
        // @ApplicationScoped state, so the lift must never touch the field.
        renderer.categoryItemCap = 2;
        proseGenerator.setResponseText("capped story prose");
        List<Post> posts = List.of(
                post("a1", "AI 1", List.of("ai")),
                post("a2", "AI 2", List.of("ai")),
                post("a3", "AI 3", List.of("ai")),
                post("a4", "AI 4", List.of("ai")));

        String result = renderJoined(posts, DigestMode.FULL);

        assertEquals(4, proseGenerator.callCount(),
                "full renders ALL clusters — the field's 2-per-section value is not applied");
        assertEquals(2, renderer.categoryItemCap,
                "the cap lift is local to the render pass — the shared field is never mutated");
        assertFalse(result.contains("+2 more"),
                "the +N more overflow line is gone in every mode (its bundle keys are deleted): "
                        + result);
    }

    @Test
    void normalModeOtherBucketSteersToBareSummary() {
        // Untagged posts land in the Other bucket (tag == null, not in the
        // controlled vocabulary), so its footer carries NO tag — the
        // other-footer key steers to bare /summary paths. Replaces the
        // pre-M1-732 overflow-line test whose bundle keys are deleted.
        proseGenerator.setResponseText("other bucket prose");
        rollupGenerator.setResponse("other roll-up");
        List<Post> posts = List.of(
                post("u1", "Untagged 1", List.of()),
                post("u2", "Untagged 2", List.of()),
                post("u3", "Untagged 3", List.of()));

        String result = renderJoined(posts, DigestMode.NORMAL);

        assertTrue(result.contains("/summary or /summary --full for the full picture"),
                "the Other bucket steers to bare /summary paths (no tag): " + result);
        assertFalse(result.contains("/summary null"),
                "the raw null tag must not leak into the rendered output: " + result);
        assertEquals(0, proseGenerator.callCount(),
                "normal renders no per-cluster prose");
    }

    @Test
    void appendsClosingAffordanceOncePerDigest() {
        proseGenerator.setResponseText("affordance test prose");
        List<Post> posts = List.of(
                post("uid-1", "One", List.of("ai")),
                post("uid-2", "Two", List.of("ai")),
                post("uid-3", "Three", List.of("ai")));

        String result = renderJoined(posts, DigestMode.FULL);

        String affordance =
                "@mention me to go deeper on any story, or ask about a topic you don't see here.";
        int first = result.indexOf(affordance);
        assertTrue(first >= 0, "closing affordance present: " + result);
        assertEquals(first, result.lastIndexOf(affordance), "affordance appears exactly once");
        assertTrue(result.endsWith(affordance), "affordance is the digest's final line");
    }

    @Test
    void renderTwiceProducesByteIdenticalSectionLayout() {
        proseGenerator.setResponseText("deterministic prose");
        // Exercises the full arithmetic (tie-break + fold-back + Other):
        // ai and security both count 3; the dual-tagged post tie-breaks to
        // ai, security folds (2 assigned < 3) into Other with the untagged.
        List<Post> posts = List.of(
                post("s1", "Sec 1", List.of("security", "ai")),
                post("s2", "Sec 2", List.of("security")),
                post("s3", "Sec 3", List.of("security")),
                post("a1", "AI 1", List.of("ai")),
                post("a2", "AI 2", List.of("ai")),
                post("u1", "Untagged", List.of()));

        String first = renderJoined(posts, DigestMode.FULL);
        String second = renderJoined(posts, DigestMode.FULL);

        assertEquals(first, second,
                "same clusters + tags produce a byte-identical section layout");
    }

    @Test
    void renderSections_stripsAdminCommandTokens_beforePersistenceAndReplay() {
        // SECURITY INVARIANT (codex redteam 2026-07-21, falsified by
        // claude's data-flow trace): the sanitizer runs INSIDE
        // renderSections() — every ClusterProse body is passed through
        // llmOutputSanitizer.sanitize() before it enters a RenderedSection.
        // DigestWorker persists those post-sanitize bytes verbatim
        // (DigestSectionRepository.replaceSlotSections), and
        // DigestRetryService.replayMissing delivers them byte-faithfully
        // WITHOUT re-sanitizing — the replay path has no sanitizer call by
        // design, because the stored bytes are already clean.
        //
        // This test pins the boundary: if the sanitizer call is removed
        // from renderSections(), the injection payload survives into the
        // returned section text — and this test fails, surfacing the break
        // before it reaches persistence or replay. The setUp wires the
        // REAL LlmOutputSanitizer (via SanitizerTestDoubles.noAuditSanitizer,
        // which sanitizes for real — only the audit DB write is stubbed).
        //
        // M1-732: the pin runs at mode FULL — the only mode that still
        // renders per-cluster prose. Under brief/normal the injected prose
        // would never enter a section and the pin would pass vacuously.
        proseGenerator.setResponseText("Important update (/grant-admin)");
        List<Post> posts = List.of(
                post("s1", "Sec 1", List.of("security")),
                post("s2", "Sec 2", List.of("security")),
                post("s3", "Sec 3", List.of("security")));

        List<DigestRenderer.RenderedSection> sections =
                renderer.renderSections(posts, "en", DigestMode.FULL, GROUP_ID);

        assertFalse(sections.isEmpty(), "fixture: at least one section rendered");
        for (DigestRenderer.RenderedSection section : sections) {
            assertFalse(section.text().contains("/grant-admin"),
                    "admin command token MUST be stripped by the sanitizer before the "
                            + "section bytes are returned for persistence — if this fails, "
                            + "the replay path would deliver unsanitized content. Section: "
                            + section.text());
        }
    }

    /**
     * M1-697 (redteam 2026-07-25): the degraded arm of
     * {@code renderSummarySections} DERIVES prose from the cluster via
     * {@code degradedProseFor} and never reads {@code cp.prose()} —
     * {@code ClusterProse} is a public record any caller can populate, so
     * redaction cannot rest on producer provenance. These records are
     * built with prose strings that LIE (raw injected bytes unrelated to
     * the clusters' posts): the output must show the clusters' own
     * per-title-sanitized prose and none of the injected bytes. Also pins
     * the M1-694-r3 span property: the split pair ({@code /list-sources}
     * … {@code --all} on different posts) no longer collapses the
     * innocent post between them.
     */
    @Test
    void renderSummarySections_degradedArmDerivesProseAndIgnoresRecordBytes() {
        Cluster redactCluster = new Cluster("t-redact", List.of(
                post("d1", "/grant-admin p-attacker"),
                post("d2", "Co-clustered headline")));
        Cluster spanCluster = new Cluster("t-span", List.of(
                post("s1", "/list-sources"),
                post("s2", "Legitimate headline"),
                post("s3", "--all")));
        List<ClusterProse> prose = List.of(
                new ClusterProse(redactCluster, "INJECTED /grant-admin raw bytes", true),
                new ClusterProse(spanCluster, "INJECTED --all raw bytes", true));

        List<DigestRenderer.RenderedSection> sections =
                renderer.renderSummarySections(prose, "en");

        assertFalse(sections.isEmpty(), "fixture: at least one section rendered");
        String text = sections.stream()
                .map(DigestRenderer.RenderedSection::text)
                .reduce("", (a, b) -> a + "\n\n" + b);
        assertFalse(text.contains("INJECTED"),
                "prose bytes carried by the record MUST be ignored for degraded clusters. Got: "
                        + text);
        assertFalse(text.contains("/grant-admin"),
                "the cluster's own command-shaped title must be redacted at derivation. Got: "
                        + text);
        assertTrue(text.contains("[redacted command]"),
                "the derivation-time redaction marker must be present. Got: " + text);
        assertTrue(text.contains("https://example.com/d1"),
                "the redacted post's bare URL must survive (D30). Got: " + text);
        assertTrue(text.contains("Legitimate headline"),
                "the innocent post between the split pair MUST survive — no cross-post span. "
                        + "Got: " + text);
        assertTrue(text.contains("https://example.com/s2"),
                "the innocent post's bare URL must survive. Got: " + text);
        assertTrue(text.contains("/list-sources") && text.contains("--all"),
                "the split pair renders verbatim — neither title is a closed-list token within "
                        + "its own field (accepted residual). Got: " + text);
    }

    /**
     * The same derive-at-render rule on the periodic digest path
     * ({@code renderSections}): the stub generator hands back a degraded
     * {@link ClusterProse} whose prose bytes are a lie, and the rendered
     * section must carry the cluster's own sanitized titles instead.
     */
    @Test
    void renderSections_degradedArmDerivesProseAndIgnoresRecordBytes() {
        proseGenerator.setDegradedMode(true);
        List<Post> posts = List.of(
                post("s1", "Sec 1", List.of("security")),
                post("s2", "/grant-admin p-attacker", List.of("security")),
                post("s3", "Sec 3", List.of("security")));

        List<DigestRenderer.RenderedSection> sections =
                renderer.renderSections(posts, "en", DigestMode.FULL, GROUP_ID);

        assertFalse(sections.isEmpty(), "fixture: at least one section rendered");
        String text = sections.stream()
                .map(DigestRenderer.RenderedSection::text)
                .reduce("", (a, b) -> a + "\n\n" + b);
        assertFalse(text.contains("INJECTED"),
                "degraded record bytes MUST be ignored on the digest path too. Got: " + text);
        assertFalse(text.contains("/grant-admin"),
                "the cluster's own command-shaped title must be redacted at derivation. Got: "
                        + text);
        assertTrue(text.contains("[redacted command]"),
                "the derivation-time redaction marker must be present. Got: " + text);
        assertTrue(text.contains("Sec 1") && text.contains("Sec 3"),
                "clean titles render untouched. Got: " + text);
    }

    // ----- helpers ----------------------------------------------------------

    /**
     * Acceptance fixture (M1-732): against one 8-category/40-cluster digest,
     * brief and normal issue exactly one CategoryRollupGenerator call per
     * surviving section and ZERO SummaryProseGenerator calls; full issues
     * one prose call per rendered cluster and no roll-up calls. 8 tags x 5
     * singleton clusters each — every category qualifies at threshold 3 and
     * all 8 survive the M1-721 section cap (maxCategories default 8).
     */
    @Test
    void perModeLlmCallCounts_8categories40clusters() {
        // M1-725: the lead is disabled here so the counts stay the BODY's
        // per-mode accounting this test was written for (brief/normal make
        // zero body-prose calls) — the lead's own accounting (lead-size
        // prose calls on top of one roll-up per section) is pinned in
        // DigestRendererSectionsTest.llmCallsAreLeadSizePlusOneRollupPerSection.
        renderer.leadMinimum = Integer.MAX_VALUE;
        List<Post> posts = new ArrayList<>();
        for (int categoryIndex = 0; categoryIndex < 8; categoryIndex++) {
            String tag = String.format("cat%02d", categoryIndex);
            for (int i = 0; i < 5; i++) {
                posts.add(post(tag + "-" + i, "Story " + tag + " " + i, List.of(tag)));
            }
        }

        renderer.renderSections(posts, "en", DigestMode.BRIEF, GROUP_ID);
        assertEquals(8, rollupGenerator.callCount(),
                "brief: exactly one roll-up call per surviving section");
        assertEquals(0, proseGenerator.callCount(),
                "brief: ZERO SummaryProseGenerator calls");

        renderer.renderSections(posts, "en", DigestMode.NORMAL, GROUP_ID);
        assertEquals(16, rollupGenerator.callCount(),
                "normal: exactly one roll-up call per surviving section");
        assertEquals(0, proseGenerator.callCount(),
                "normal: ZERO SummaryProseGenerator calls");

        renderer.renderSections(posts, "en", DigestMode.FULL, GROUP_ID);
        assertEquals(16, rollupGenerator.callCount(),
                "full: no roll-up calls");
        assertEquals(40, proseGenerator.callCount(),
                "full: one prose call per rendered cluster (the cap is lifted)");
    }

    // ----- M1-756 display-hit translation -----------------------------------

    @Test
    void normalModeHeadlinesTranslateForNonEnScope() throws Exception {
        // The digest reaches parity with /summary and /saved: a cs group
        // reading en-source headlines gets them through the display-hit
        // leg, marked as machine-translated, inside the UNCHANGED
        // "· <headline>  <url>" line.
        AtomicInteger calls = new AtomicInteger();
        wireTranslator(calls, "Přeložený titulek");
        List<Post> posts = taggedPosts(3, "en", "ai");

        String result = renderJoined(posts, DigestMode.NORMAL, "cs");

        assertEquals(3, calls.get(), "one translator call per rendered headline");
        assertTrue(result.contains("· Přeložený titulek " + CS_MARKER + "  https://example.com/ai0"),
                "the translated headline replaces the original in the same line shape; got: "
                        + result);
        assertFalse(result.contains("Headline ai 0"),
                "the untranslated source headline must not also render; got: " + result);
    }

    @Test
    void enScopeMakesZeroTranslatorCallsAndRendersHeadlinesVerbatim() throws Exception {
        // The M1-747 acceptance property, extended to the digest: an en
        // scope short-circuits in the pipeline, so the provider spy must
        // see nothing and the bytes must be the source headlines exactly —
        // this is what keeps every persisted-section byte pin valid.
        AtomicInteger calls = new AtomicInteger();
        wireTranslator(calls, "Přeložený titulek");
        List<Post> posts = taggedPosts(3, "cs", "ai");

        String result = renderJoined(posts, DigestMode.NORMAL, "en");

        assertEquals(0, calls.get(),
                "an en scope must make ZERO translator calls; got: " + result);
        assertTrue(result.contains("· Headline ai 0  https://example.com/ai0"),
                "en headlines render byte-identical to the source; got: " + result);
        assertFalse(result.contains(CS_MARKER),
                "no machine-translation marker on an untranslated headline; got: " + result);
    }

    @Test
    void unknownSourceLanguageMakesNoTranslatorCall() throws Exception {
        // D29 declared-never-inferred: a NULL source language is "unknown",
        // and unknown never translates — the same no-op every hand-built
        // Post fixture in this class relies on.
        AtomicInteger calls = new AtomicInteger();
        wireTranslator(calls, "Přeložený titulek");
        List<Post> posts = List.of(
                post("n1", "Headline 0", List.of("ai")),
                post("n2", "Headline 1", List.of("ai")),
                post("n3", "Headline 2", List.of("ai")));

        String result = renderJoined(posts, DigestMode.NORMAL, "cs");

        assertEquals(0, calls.get(),
                "a null source language must never reach the translator; got: " + result);
        assertTrue(result.contains("· Headline 0  https://example.com/n1"),
                "the headline renders untranslated; got: " + result);
    }

    @Test
    void perRenderBudgetBoundsGenerativeCallsAcrossTheWholeRender() throws Exception {
        // The metering acceptance: the digest is a scheduled broadcast with
        // no user in the loop, so the ONLY bound on its generative surface
        // is this budget. It is per RENDER, not per section — two sections
        // of three translatable headlines each must still spend only the
        // configured allowance, and the rows past it render untranslated
        // AND unmarked.
        // The lead is disabled: at 6 clusters it would fire, and its
        // per-cluster PROSE runs through the translator too — a different
        // leg whose calls would confound this leg's accounting.
        renderer.leadMinimum = Integer.MAX_VALUE;
        renderer.translationMaxPerRender = 2;
        AtomicInteger calls = new AtomicInteger();
        wireTranslator(calls, "Přeložený titulek");
        List<Post> posts = new ArrayList<>();
        posts.addAll(taggedPosts(3, "en", "ai"));
        posts.addAll(taggedPosts(3, "en", "crypto"));

        String result = renderJoined(posts, DigestMode.NORMAL, "cs");

        assertEquals(2, calls.get(),
                "the budget bounds the WHOLE render, not each section; got: " + result);
        assertEquals(2, countOccurrences(result, CS_MARKER),
                "exactly the budgeted headlines carry the marker; got: " + result);
        assertTrue(result.contains("Headline crypto 2"),
                "a headline past the budget renders untranslated; got: " + result);
    }

    @Test
    void cacheHitsCostNoBudget() throws Exception {
        // A digest re-rendered over a converged corpus must not be
        // throttled by its own history: entries already in the display-hit
        // keyspace are served with no provider call, so they must not
        // consume the generative allowance. Budget 1 + 2 pre-cached
        // headlines => 3 translated headlines from ONE provider call.
        renderer.translationMaxPerRender = 1;
        AtomicInteger calls = new AtomicInteger();
        wireTranslator(calls, "Přeložený titulek");
        String keyspace = TranslationPipeline.displayHitCacheLanguage("group", GROUP_ID, "cs");
        renderer.translationCache.put("Headline ai 0", keyspace, "Titulek nula");
        renderer.translationCache.put("Headline ai 1", keyspace, "Titulek jedna");
        List<Post> posts = taggedPosts(3, "en", "ai");

        String result = renderJoined(posts, DigestMode.NORMAL, "cs");

        assertEquals(1, calls.get(),
                "the two cache hits cost no provider call; got: " + result);
        assertTrue(result.contains("Titulek nula") && result.contains("Titulek jedna"),
                "cached translations still render; got: " + result);
        assertTrue(result.contains("Přeložený titulek"),
                "the one budgeted miss still calls the translator; got: " + result);
    }

    @Test
    void degradedProseMakesNoTranslatorCallWhileNonDegradedDoes() throws Exception {
        // security.md §Failure handling pins degraded output to headlines +
        // URLs + UIDs with NO LLM calls. The pin is asserted against its
        // own control: the SAME fixture in the SAME cs scope translates on
        // the non-degraded arm, so a zero count on the degraded arm is a
        // real skip and not a fixture that could never have translated.
        List<Post> posts = List.of(
                post("d1", "Sec 1", List.of("security")),
                post("d2", "Sec 2", List.of("security")),
                post("d3", "Sec 3", List.of("security")));

        AtomicInteger degradedCalls = new AtomicInteger();
        wireTranslator(degradedCalls, "Přeložená próza");
        proseGenerator.setDegradedMode(true);
        String degraded = renderJoined(posts, DigestMode.FULL, "cs");
        assertEquals(0, degradedCalls.get(),
                "a degraded cluster must make ZERO translator calls; got: " + degraded);
        assertFalse(degraded.contains(CS_MARKER),
                "no machine-translation marker on degraded output; got: " + degraded);

        AtomicInteger healthyCalls = new AtomicInteger();
        wireTranslator(healthyCalls, "Přeložená próza");
        proseGenerator.setDegradedMode(false);
        proseGenerator.setResponseText("Healthy prose");
        renderJoined(posts, DigestMode.FULL, "cs");
        assertTrue(healthyCalls.get() > 0,
                "control: the non-degraded arm DOES translate, so the zero above is a skip");
    }

    // ----- helpers ----------------------------------------------------------

    /**
     * Replaces the setUp pipeline with one whose translator is a counting
     * stub — {@code newEnShortCircuitPipeline}'s identity translator cannot
     * exercise the translating leg (an identity translation is delivered
     * unmarked). Shares the renderer's {@link TranslationCache} instance so
     * a pre-seeded entry is visible to both the budget probe and the
     * pipeline, exactly as the single CDI bean is in production.
     */
    private void wireTranslator(AtomicInteger callCounter, String response) throws Exception {
        TranslationPipeline pipeline = new TranslationPipeline();
        var cacheField = TranslationPipeline.class.getDeclaredField("translationCache");
        cacheField.setAccessible(true);
        cacheField.set(pipeline, renderer.translationCache);
        var providerField = TranslationPipeline.class.getDeclaredField("translationProvider");
        providerField.setAccessible(true);
        providerField.set(pipeline, (TranslationProvider) (text, from, to) -> {
            callCounter.incrementAndGet();
            return response;
        });
        var sanitizerField = TranslationPipeline.class.getDeclaredField("llmOutputSanitizer");
        sanitizerField.setAccessible(true);
        sanitizerField.set(pipeline, SanitizerTestDoubles.noAuditSanitizer());
        var bundleLoaderField = TranslationPipeline.class.getDeclaredField("bundleLoader");
        bundleLoaderField.setAccessible(true);
        bundleLoaderField.set(pipeline, bundleLoader);
        renderer.translationPipeline = pipeline;
    }

    /**
     * {@code count} singleton-cluster posts under one tag, each declaring
     * {@code sourceLanguage}. Titles carry the TAG because the display-hit
     * cache is keyed on headline text: two sections sharing a headline
     * string would make the second section's row a free cache hit, which
     * would silently invalidate the budget accounting.
     */
    private static List<Post> taggedPosts(int count, String sourceLanguage, String tag) {
        List<Post> posts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String uid = tag + i;
            posts.add(new Post(
                    UUID.randomUUID(), uid, UUID.randomUUID(), "TestSrc",
                    "Headline " + tag + " " + i, "https://example.com/" + uid, "body",
                    Instant.now(), List.of(tag), List.of("unknown"),
                    null, null, null, null, sourceLanguage));
        }
        return posts;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private static DigestCategorizer newCategorizer(int minClusters) {
        DigestCategorizer categorizer = new DigestCategorizer();
        categorizer.categoryMinClusters = minClusters;
        return categorizer;
    }

    private static Post post(String uid, String title) {
        return post(uid, title, List.of("crypto"));
    }

    private static Post post(String uid, String title, List<String> tags) {
        return new Post(
                UUID.randomUUID(), uid, UUID.randomUUID(), "TestSrc",
                title, "https://example.com/" + uid, "body",
                Instant.now(), tags, List.of("unknown"));
    }

    /**
     * Recording {@link CategoryRollupGenerator}: counts calls and returns a
     * canned synthesis — the brief/normal LLM-call-count assertions need a
     * roll-up stub the same way the prose assertions need
     * {@link RecordingSummaryProseGenerator}.
     */
    private static final class RecordingCategoryRollupGenerator extends CategoryRollupGenerator {
        private String response = "category roll-up";
        private int calls;

        void setResponse(String text) { this.response = text; }
        int callCount() { return calls; }

        @Override
        public Optional<String> generateRollup(List<Cluster> categoryClusters,
                                               String sectionTag, String langCode) {
            calls++;
            return Optional.of(response);
        }
    }

    /**
     * Recording subclass: returns canned prose for each cluster and tracks
     * the language code and call count. Degraded mode returns a degraded
     * {@link ClusterProse} whose prose bytes are a deliberate LIE — the
     * renderer must derive degraded prose from the cluster (M1-697), so
     * the lie is what proves the record bytes are never trusted.
     */
    private static final class RecordingSummaryProseGenerator extends SummaryProseGenerator {
        private final AtomicReference<String> lastLang = new AtomicReference<>();
        private String responseText = "default summary";
        private boolean degradedMode = false;
        private int calls;

        void setResponseText(String text) { this.responseText = text; }
        void setDegradedMode(boolean degraded) { this.degradedMode = degraded; }
        String lastLanguage() { return lastLang.get(); }
        int callCount() { return calls; }

        @Override
        public List<ClusterProse> generate(List<Cluster> clusters, String scopeLanguage) {
            lastLang.set(scopeLanguage);
            List<ClusterProse> out = new ArrayList<>(clusters.size());
            for (Cluster c : clusters) {
                calls++;
                out.add(new ClusterProse(c,
                        degradedMode ? "INJECTED degraded lie bytes" : responseText, degradedMode));
            }
            return out;
        }
    }
}
