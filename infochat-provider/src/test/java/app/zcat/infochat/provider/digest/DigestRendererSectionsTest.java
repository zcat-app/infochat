package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.digest.DigestRenderer.DigestMode;
import app.zcat.infochat.provider.digest.DigestRenderer.RenderedSection;
import app.zcat.infochat.provider.summary.ClusterTraversal;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.summary.EmptyEdgeSource;
import app.zcat.infochat.provider.summary.SummaryProseGenerator;
import app.zcat.infochat.provider.summary.SummaryProseGenerator.ClusterProse;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static app.zcat.infochat.provider.testsupport.TranslationFixtures.newEnShortCircuitPipeline;
import static app.zcat.infochat.provider.testsupport.TranslationFixtures.newRealBundleLoader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link DigestRenderer#renderSections} unit surface: section order
 * matches D62, the closing affordance is folded into the LAST section's
 * text only, the M1-721 section cap shapes the digest only, and (M1-732)
 * the {@code groups.digest_mode} body shapes — brief/normal hybrid
 * (true-count header + roll-up + headlines + footer) versus full's
 * per-cluster prose. Kept as a separate file so the pre-existing
 * {@link DigestRendererTest} stays the pipeline-wiring proof.
 */
class DigestRendererSectionsTest {

    private DigestRenderer renderer;
    private RecordingSummaryProseGenerator proseGenerator;
    private RecordingCategoryRollupGenerator rollupGenerator;

    @BeforeEach
    void setUp() throws Exception {
        BundleLoader bundleLoader = newRealBundleLoader();
        renderer = new DigestRenderer();
        renderer.clusterTraversal = new ClusterTraversal(new EmptyEdgeSource(), 3);
        proseGenerator = new RecordingSummaryProseGenerator();
        renderer.summaryProseGenerator = proseGenerator;
        rollupGenerator = new RecordingCategoryRollupGenerator();
        renderer.categoryRollupGenerator = rollupGenerator;
        renderer.llmOutputSanitizer = SanitizerTestDoubles.noAuditSanitizer();
        renderer.translationPipeline = newEnShortCircuitPipeline(bundleLoader);
        renderer.digestCategorizer = newCategorizer(3);
        renderer.bundleLoader = bundleLoader;
        renderer.categoryItemCap = 12;
        renderer.categoryHeadlineCount = 5;
    }

    @Test
    void sectionsMatchD62OrderCountDescAlphaTiesOtherLast() {
        proseGenerator.setResponseText("story prose");
        // EmptyEdgeSource → one singleton cluster per post, so tag counts
        // are: security=4, ai=3, crypto=3 (all qualify at threshold 3), and
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

        List<RenderedSection> sections = renderer.renderSections(posts, "en", DigestMode.FULL);

        // Arrays.asList, not List.of: List.of is null-hostile and the Other
        // bucket's tag is null by construction (DigestCategorizer.CategorySection).
        assertEquals(Arrays.asList("security", "ai", "crypto", null),
                sections.stream().map(RenderedSection::tag).toList(),
                "D62 order: count desc (security=4 first), alpha tie (ai before crypto), Other last");
    }

    @Test
    void affordanceFoldedIntoLastSectionOnly() {
        proseGenerator.setResponseText("affordance test prose");
        List<Post> posts = List.of(
                post("s1", "Sec 1", List.of("security")),
                post("s2", "Sec 2", List.of("security")),
                post("s3", "Sec 3", List.of("security")),
                post("a1", "AI 1", List.of("ai")),
                post("a2", "AI 2", List.of("ai")),
                post("a3", "AI 3", List.of("ai")));

        List<RenderedSection> sections = renderer.renderSections(posts, "en", DigestMode.FULL);

        String affordance =
                "@mention me to go deeper on any story, or ask about a topic you don't see here.";
        // Every section except the last must NOT contain the affordance.
        for (int i = 0; i < sections.size() - 1; i++) {
            assertFalse(sections.get(i).text().contains(affordance),
                    "section " + i + " must not carry the closing affordance: " + sections.get(i).text());
        }
        // The last section's text ends with the affordance — folded inside
        // the section, not appended by the delivery path.
        String lastText = sections.getLast().text();
        assertTrue(lastText.contains(affordance),
                "last section carries the affordance: " + lastText);
        assertTrue(lastText.endsWith(affordance),
                "last section ends with the affordance (folded into the section text): " + lastText);
    }

    // ----- section cap (M1-721) ---------------------------------------------

    @Test
    void cappedDigestAppendsOneOverflowLineOnTheLastSection() {
        renderer.digestCategorizer = newCategorizer(3, 8);
        proseGenerator.setResponseText("capped prose");

        List<RenderedSection> sections =
                renderer.renderSections(twelveCategoryPosts(), "en", DigestMode.FULL);

        assertEquals(8, sections.size(), "12 categories capped to 8 sections");
        String overflow = "4 more categories are not shown";
        long carrying = sections.stream().filter(s -> s.text().contains(overflow)).count();
        assertEquals(1, carrying,
                "exactly one overflow line across the whole digest: "
                        + sections.stream().map(RenderedSection::text).toList());
        assertTrue(sections.getLast().text().contains(overflow),
                "the overflow line rides the last section: " + sections.getLast().text());
    }

    @Test
    void digestUnderTheCapAppendsNoOverflowLine() {
        renderer.digestCategorizer = newCategorizer(3, 8);
        proseGenerator.setResponseText("uncapped prose");
        List<Post> posts = List.of(
                post("s1", "Sec 1", List.of("security")),
                post("s2", "Sec 2", List.of("security")),
                post("s3", "Sec 3", List.of("security")));

        List<RenderedSection> sections = renderer.renderSections(posts, "en", DigestMode.FULL);

        assertEquals(1, sections.size());
        assertFalse(sections.getFirst().text().contains("not shown"),
                "no overflow line under the cap: " + sections.getFirst().text());
    }

    @Test
    void proseCoversOnlySectionsThatSurviveTheCap() {
        renderer.digestCategorizer = newCategorizer(3, 8);
        proseGenerator.setResponseText("surviving prose");

        renderer.renderSections(twelveCategoryPosts(), "en", DigestMode.FULL);

        // D62 already commits that capped-out CLUSTERS waste no LLM calls;
        // the section cap extends the same property to whole sections. A
        // dropped section that still paid for prose would be pure waste.
        assertEquals(8 * 3, proseGenerator.callCount(),
                "per-cluster prose runs for the 8 surviving sections' 3 clusters each, and no others");
    }

    @Test
    void summaryEntryPointIsNotSectionCapped() {
        // The cap is a digest-broadcast bound. /summary is an interactive
        // pull the reader asked for and shares the same categorizer, so a
        // cap applied inside categorize() would silently reach it.
        renderer.digestCategorizer = newCategorizer(3, 8);
        List<Cluster> clusters = new ClusterTraversal(new EmptyEdgeSource(), 3)
                .cluster(twelveCategoryPosts());
        List<ClusterProse> proseList = clusters.stream()
                .map(c -> new ClusterProse(c, "summary prose", false))
                .toList();

        List<RenderedSection> sections = renderer.renderSummarySections(proseList, "en");

        assertEquals(12, sections.size(),
                "/summary renders all 12 categories: "
                        + sections.stream().map(RenderedSection::tag).toList());
    }

    // ----- digest_mode body shapes (M1-732) ---------------------------------

    @Test
    void normalModeRendersTrueCountHeaderRollupHeadlinesAndFooter() {
        // The hybrid body: UPPERCASE header with the section's TRUE cluster
        // count, the roll-up synthesis, up to category-headline-count (5)
        // bare headlines (title + URL, NO prose), and the category footer.
        // 13 clusters pin the count against the 5 headlines shown.
        rollupGenerator.setResponse("thirteen-story synthesis");
        List<Post> posts = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            posts.add(post("sec-" + i, "Story sec " + i, List.of("security")));
        }

        List<RenderedSection> sections =
                renderer.renderSections(posts, "en", DigestMode.NORMAL);

        assertEquals(1, sections.size());
        String text = sections.getFirst().text();
        assertTrue(text.startsWith("SECURITY NEWS — 13 STORIES"),
                "header carries the TRUE cluster count, not the 5 headlines shown: " + text);
        assertTrue(text.contains("thirteen-story synthesis"),
                "the roll-up synthesis renders: " + text);
        for (int i = 0; i < 5; i++) {
            assertTrue(text.contains("· Story sec " + i + "  https://example.com/sec-" + i),
                    "headline " + i + " renders as bare title + URL: " + text);
        }
        assertFalse(text.contains("Story sec 5"),
                "the 6th headline is capped off by category-headline-count: " + text);
        assertTrue(text.contains("/summary security to expand this category"),
                "the category footer closes the section: " + text);
        assertEquals(0, proseGenerator.callCount(),
                "normal renders NO per-cluster prose");
    }

    @Test
    void briefModeDropsTheHeadlines() {
        rollupGenerator.setResponse("brief synthesis");
        List<Post> posts = List.of(
                post("s1", "Sec 1", List.of("security")),
                post("s2", "Sec 2", List.of("security")),
                post("s3", "Sec 3", List.of("security")));

        List<RenderedSection> sections =
                renderer.renderSections(posts, "en", DigestMode.BRIEF);

        String text = sections.getFirst().text();
        assertTrue(text.startsWith("SECURITY NEWS — 3 STORIES"),
                "brief keeps the true-count header: " + text);
        assertTrue(text.contains("brief synthesis"), "the roll-up renders: " + text);
        assertFalse(text.contains("· "), "brief renders NO headlines: " + text);
        assertTrue(text.contains("/summary security to expand this category"),
                "the footer stays: " + text);
        assertEquals(0, proseGenerator.callCount(),
                "brief renders NO per-cluster prose");
    }

    @Test
    void fullModeKeepsPerClusterProseAndThePlainHeader() {
        proseGenerator.setResponseText("full prose");
        List<Post> posts = List.of(
                post("s1", "Sec 1", List.of("security")),
                post("s2", "Sec 2", List.of("security")),
                post("s3", "Sec 3", List.of("security")));

        List<RenderedSection> sections =
                renderer.renderSections(posts, "en", DigestMode.FULL);

        String text = sections.getFirst().text();
        assertTrue(text.startsWith("SECURITY NEWS\n"),
                "full keeps the pre-M1-732 header bytes: " + text);
        assertEquals(3, text.split("full prose", -1).length - 1,
                "one prose paragraph per cluster: " + text);
        assertEquals(0, rollupGenerator.callCount(), "full makes no roll-up calls");
    }

    // ----- helpers ----------------------------------------------------------

    /**
     * Twelve qualifying categories {@code cat00}..{@code cat11} of exactly 3
     * clusters each (EmptyEdgeSource → one singleton cluster per post). Equal
     * sizes mean D62's alphabetical tie-break fixes the order, so which
     * sections the cap keeps is deterministic.
     */
    private static List<Post> twelveCategoryPosts() {
        List<Post> posts = new ArrayList<>();
        for (int categoryIndex = 0; categoryIndex < 12; categoryIndex++) {
            String tag = String.format("cat%02d", categoryIndex);
            for (int i = 0; i < 3; i++) {
                posts.add(post(tag + "-" + i, "Story " + tag + " " + i, List.of(tag)));
            }
        }
        return posts;
    }

    private static DigestCategorizer newCategorizer(int minClusters) {
        DigestCategorizer categorizer = new DigestCategorizer();
        categorizer.categoryMinClusters = minClusters;
        return categorizer;
    }

    private static DigestCategorizer newCategorizer(int minClusters, int maxCategories) {
        DigestCategorizer categorizer = newCategorizer(minClusters);
        categorizer.maxCategories = maxCategories;
        return categorizer;
    }

    private static Post post(String uid, String title, List<String> tags) {
        return new Post(
                UUID.randomUUID(), uid, UUID.randomUUID(), "TestSrc",
                title, "https://example.com/" + uid, "body",
                Instant.now(), tags, List.of("unknown"));
    }

    /** Recording {@link CategoryRollupGenerator}: counts calls and returns a canned synthesis. */
    private static final class RecordingCategoryRollupGenerator extends CategoryRollupGenerator {
        private String response = "category roll-up";
        private int calls;

        void setResponse(String text) { this.response = text; }
        int callCount() { return calls; }

        @Override
        public Optional<String> generateRollup(List<Cluster> categoryClusters, String langCode) {
            calls++;
            return Optional.of(response);
        }
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
