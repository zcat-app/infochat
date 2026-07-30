package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.bundle.BundleLoader;
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
 * text only, and {@code String.join("\n\n", sections.map(text))} equals
 * {@link DigestRenderer#render} byte-for-byte. Kept as a separate file so
 * the pre-existing {@link DigestRendererTest} stays the unmodified
 * byte-identity proof (acceptance item 8).
 */
class DigestRendererSectionsTest {

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

        List<RenderedSection> sections = renderer.renderSections(posts, "en");

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

        List<RenderedSection> sections = renderer.renderSections(posts, "en");

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

    @Test
    void joinOfSectionsEqualsRenderByteForByte() {
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

        List<RenderedSection> sections = renderer.renderSections(posts, "en");
        String joined = String.join("\n\n",
                sections.stream().map(RenderedSection::text).toList());
        String rendered = renderer.render(posts, "en");

        assertEquals(rendered, joined,
                "String.join(\"\\n\\n\", sections) must equal render() byte-for-byte — "
                        + "render() is a thin join over renderSections()");
    }

    @Test
    void rollupPrefixAppearsInRenderedSectionWhenGeneratorReturnsOne() {
        // End-of-path pin for the renderSections consumer line
        // `categoryRollupGenerator.generateRollup(...).ifPresent(rollup -> sb.append("\n\n").append(rollup))`
        // (DigestRenderer.java:121-122). A stub generator that returns a
        // fixed prefix must cause that prefix to appear INSIDE the rendered
        // section text; removing the ifPresent append (or the generateRollup
        // call feeding it) fails this assertion. Every other renderSections
        // test runs with categorySummaryEnabled at its default false, so the
        // integration line is a no-op they cannot constrain (round-1 rework
        // item 1, ASSERTION-ADEQUACY-CHECK).
        renderer.categoryRollupGenerator = new CategoryRollupGenerator() {
            @Override
            public Optional<String> generateRollup(List<Cluster> categoryClusters, String langCode) {
                return Optional.of("TEST-ROLLUP-PREFIX");
            }
        };
        proseGenerator.setResponseText("section prose");
        List<Post> posts = List.of(
                post("s1", "Sec 1", List.of("security")),
                post("s2", "Sec 2", List.of("security")),
                post("s3", "Sec 3", List.of("security")));

        List<RenderedSection> sections = renderer.renderSections(posts, "en");

        boolean anySectionCarriesPrefix = sections.stream()
                .anyMatch(s -> s.text().contains("TEST-ROLLUP-PREFIX"));
        assertTrue(anySectionCarriesPrefix,
                "roll-up prefix must appear inside a rendered section when the generator returns one: "
                        + sections.stream().map(RenderedSection::text).toList());
    }

    // ----- section cap (M1-721) ---------------------------------------------

    @Test
    void cappedDigestAppendsOneOverflowLineOnTheLastSection() {
        renderer.digestCategorizer = newCategorizer(3, 8);
        proseGenerator.setResponseText("capped prose");

        List<RenderedSection> sections = renderer.renderSections(twelveCategoryPosts(), "en");

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

        List<RenderedSection> sections = renderer.renderSections(posts, "en");

        assertEquals(1, sections.size());
        assertFalse(sections.getFirst().text().contains("not shown"),
                "no overflow line under the cap: " + sections.getFirst().text());
    }

    @Test
    void proseAndRollupsCoverOnlySectionsThatSurviveTheCap() {
        renderer.digestCategorizer = newCategorizer(3, 8);
        RecordingRollupGenerator rollupGenerator = new RecordingRollupGenerator();
        renderer.categoryRollupGenerator = rollupGenerator;
        proseGenerator.setResponseText("surviving prose");

        renderer.renderSections(twelveCategoryPosts(), "en");

        // D62 already commits that capped-out CLUSTERS waste no LLM calls;
        // the section cap extends the same property to whole sections. A
        // dropped section that still paid for prose would be pure waste.
        assertEquals(8 * 3, proseGenerator.callCount(),
                "per-cluster prose runs for the 8 surviving sections' 3 clusters each, and no others");
        assertEquals(8, rollupGenerator.callCount(),
                "one roll-up attempt per surviving section, none for the dropped four");
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

    /** Counts {@link CategoryRollupGenerator#generateRollup} calls without reaching an LLM. */
    private static final class RecordingRollupGenerator extends CategoryRollupGenerator {
        private int calls;

        int callCount() { return calls; }

        @Override
        public Optional<String> generateRollup(List<Cluster> categoryClusters, String langCode) {
            calls++;
            return Optional.empty();
        }
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
