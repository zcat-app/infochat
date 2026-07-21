package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.summary.ClusterTraversal;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.summary.EmptyEdgeSource;
import app.zcat.infochat.provider.summary.SummaryProseGenerator;
import app.zcat.infochat.provider.summary.SummaryProseGenerator.ClusterProse;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

    private DigestRenderer renderer;
    private RecordingSummaryProseGenerator proseGenerator;

    @BeforeEach
    void setUp() throws Exception {
        BundleLoader bundleLoader = newRealBundleLoader();
        renderer = new DigestRenderer();
        renderer.clusterTraversal = new ClusterTraversal(new EmptyEdgeSource(), 3);
        proseGenerator = new RecordingSummaryProseGenerator();
        renderer.summaryProseGenerator = proseGenerator;
        renderer.llmOutputSanitizer = SanitizerTestDoubles.noAuditSanitizer();
        renderer.translationPipeline = newEnShortCircuitPipeline(bundleLoader);
        renderer.digestCategorizer = newCategorizer(3);
        renderer.bundleLoader = bundleLoader;
        renderer.categoryItemCap = 12;
    }

    @Test
    void render_producesLocalizedProse() {
        proseGenerator.setResponseText("LLM digest summary for cluster");

        List<Post> posts = List.of(
                post("uid-1", "Bitcoin hits $100k"),
                post("uid-2", "Ethereum update"));

        String result = renderer.render(posts, "en");

        assertTrue(result.contains("LLM digest summary for cluster"),
                "LLM prose appears in rendered output");
        assertEquals("en", proseGenerator.lastLanguage(),
                "language code forwarded to prose generator");
        assertTrue(proseGenerator.callCount() > 0,
                "prose generator was invoked");
    }

    @Test
    void rendersUppercaseHeadersOrderedBySizeThenAlphaOtherLast() {
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

        String result = renderer.render(posts, "en");

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
    void capsItemsPerSectionWithLocalizedMoreHint() {
        renderer.categoryItemCap = 2;
        proseGenerator.setResponseText("capped story prose");
        List<Post> posts = List.of(
                post("a1", "AI 1", List.of("ai")),
                post("a2", "AI 2", List.of("ai")),
                post("a3", "AI 3", List.of("ai")),
                post("a4", "AI 4", List.of("ai")));

        String result = renderer.render(posts, "en");

        assertTrue(result.contains("+2 more — @mention me to see them"),
                "capped section appends the localized overflow line: " + result);
        assertEquals(2, proseGenerator.callCount(),
                "prose is generated only for the clusters actually shown");
    }

    @Test
    void appendsClosingAffordanceOncePerDigest() {
        proseGenerator.setResponseText("affordance test prose");
        List<Post> posts = List.of(
                post("uid-1", "One", List.of("ai")),
                post("uid-2", "Two", List.of("ai")),
                post("uid-3", "Three", List.of("ai")));

        String result = renderer.render(posts, "en");

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

        String first = renderer.render(posts, "en");
        String second = renderer.render(posts, "en");

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
        proseGenerator.setResponseText("Important update (/grant-admin)");
        List<Post> posts = List.of(
                post("s1", "Sec 1", List.of("security")),
                post("s2", "Sec 2", List.of("security")),
                post("s3", "Sec 3", List.of("security")));

        List<DigestRenderer.RenderedSection> sections = renderer.renderSections(posts, "en");

        assertFalse(sections.isEmpty(), "fixture: at least one section rendered");
        for (DigestRenderer.RenderedSection section : sections) {
            assertFalse(section.text().contains("/grant-admin"),
                    "admin command token MUST be stripped by the sanitizer before the "
                            + "section bytes are returned for persistence — if this fails, "
                            + "the replay path would deliver unsanitized content. Section: "
                            + section.text());
        }
    }

    // ----- helpers ----------------------------------------------------------

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
     * Recording subclass: returns canned prose for each cluster and tracks
     * the language code and call count.
     */
    private static final class RecordingSummaryProseGenerator extends SummaryProseGenerator {
        private final AtomicReference<String> lastLang = new AtomicReference<>();
        private String responseText = "default summary";
        private int calls;

        void setResponseText(String text) { this.responseText = text; }
        String lastLanguage() { return lastLang.get(); }
        int callCount() { return calls; }

        @Override
        public List<ClusterProse> generate(List<Cluster> clusters, String scopeLanguage) {
            lastLang.set(scopeLanguage);
            List<ClusterProse> out = new ArrayList<>(clusters.size());
            for (Cluster c : clusters) {
                calls++;
                out.add(new ClusterProse(c, responseText, false));
            }
            return out;
        }
    }
}
