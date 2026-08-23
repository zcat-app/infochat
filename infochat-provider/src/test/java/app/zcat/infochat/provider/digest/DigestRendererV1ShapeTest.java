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
import app.zcat.infochat.provider.translation.TranslationCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static app.zcat.infochat.provider.testsupport.TranslationFixtures.newEnShortCircuitPipeline;
import static app.zcat.infochat.provider.testsupport.TranslationFixtures.newRealBundleLoader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The v1 digest shape: a NORMAL digest opens with ONE window-size line —
 * the window's TRUE pre-cap story and topic-section counts, riding the
 * first section's text — and closes with the /summary drill-down affordance.
 */
class DigestRendererV1ShapeTest {

    private static final UUID GROUP_ID =
            UUID.fromString("55555555-5555-5555-5555-555555555555");

    /** The approved closing-affordance copy (en) — pinned at full strength. */
    private static final String CLOSING_AFFORDANCE =
            "/summary <tag> to drill into a topic, or @mention me to go deeper "
                    + "on any story or ask about one you don't see here.";

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
        renderer.translationCache = new TranslationCache();
        renderer.digestCategorizer = newCategorizer(3);
        renderer.bundleLoader = bundleLoader;
        renderer.categoryItemCap = 12;
        // The 10-headline default is part of the v1 shape this class pins;
        // the field is left at its field-initialized default on purpose.
    }

    /**
     * The reproduction: a multi-section NORMAL digest with a lead. The
     * window held 10 stories in 2 topic sections; the lead promotes 3 and
     * the body renders 7 — the header must name the WINDOW totals (10/2),
     * never the rendered subset (7/2), and must sit ahead of the lead
     * section's own content as the digest's first line.
     */
    @Test
    void normalDigestOpensWithWindowStoryCountAndClosesWithSummaryDrilldown() {
        proseGenerator.setResponseText("lead prose");
        rollupGenerator.setResponse("roll-up");
        // security=6, ai=4 (threshold 3, no Other); the lead promotes the
        // top 3 by input order, both sections survive — window totals stay
        // 10 stories / 2 topics while the body renders 7.
        List<Post> posts = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            posts.add(post("s" + i, "Sec " + i, List.of("security")));
        }
        for (int i = 1; i <= 4; i++) {
            posts.add(post("a" + i, "AI " + i, List.of("ai")));
        }

        List<RenderedSection> sections =
                renderer.renderSections(posts, "en", DigestMode.NORMAL, GROUP_ID).sections();

        assertEquals(3, sections.size(), "LEAD + security + ai");
        assertEquals(DigestRenderer.LEAD_TAG, sections.getFirst().tag());
        assertTrue(sections.getFirst().text().startsWith(
                        "Digest window: 10 stories across 2 topics\n\nTOP STORIES"),
                "the window header is the digest's first line, ahead of the lead "
                        + "content, naming the WINDOW totals (10 stories, 2 topics), "
                        + "not the rendered subset: " + sections.getFirst().text());
        assertAffordanceExactlyOnceAtTheEnd(sections);
    }

    /**
     * Failure mode: a single-section, leadless digest. The header must not
     * assume a lead exists, and header and affordance sharing ONE section
     * must not duplicate either — one window line at the top, one
     * affordance at the bottom. Also pins the en singular branch
     * ("1 topic") and the single-section true-count header that follows.
     */
    @Test
    void zeroOrOneSectionDigestStillClosesWithTheAffordanceExactlyOnce() {
        renderer.leadMinimum = Integer.MAX_VALUE; // no lead: 3 clusters < default minimum anyway
        rollupGenerator.setResponse("roll-up");
        List<Post> posts = List.of(
                post("s1", "Sec 1", List.of("security")),
                post("s2", "Sec 2", List.of("security")),
                post("s3", "Sec 3", List.of("security")));

        List<RenderedSection> sections =
                renderer.renderSections(posts, "en", DigestMode.NORMAL, GROUP_ID).sections();

        assertEquals(1, sections.size());
        String text = sections.getFirst().text();
        assertTrue(text.startsWith("Digest window: 3 stories across 1 topic\n\nSECURITY NEWS — 3 STORIES"),
                "the window header rides the single section ahead of its own header, "
                        + "singular branch for one topic: " + text);
        assertEquals(1, countOccurrences(text, "Digest window:"),
                "exactly one window line on the shared section: " + text);
        assertAffordanceExactlyOnceAtTheEnd(sections);
    }

    /**
     * The window header's plural shapes are per-language (D43): cs uses the
     * 1 / 2-4 / 5+ categories, not the en 1 / other split — 10 falls in the
     * 5+ genitive plural ("zpráv"), 2 in the 2-4 locative ("tématech").
     */
    @Test
    void windowHeaderRendersCzechPluralShapes() {
        renderer.leadMinimum = Integer.MAX_VALUE;
        rollupGenerator.setResponse("roll-up");
        List<Post> posts = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            posts.add(post("s" + i, "Sec " + i, List.of("security")));
        }
        for (int i = 1; i <= 5; i++) {
            posts.add(post("a" + i, "AI " + i, List.of("ai")));
        }

        List<RenderedSection> sections =
                renderer.renderSections(posts, "cs", DigestMode.NORMAL, GROUP_ID).sections();

        String firstLine = sections.getFirst().text().split("\n\n", 2)[0];
        assertEquals("Digestní okno: 10 zpráv v 2 tématech", firstLine,
                "cs plural categories: 10 → 5+ form, 2 → 2-4 form: " + firstLine);
    }

    // ----- helpers ----------------------------------------------------------

    private void assertAffordanceExactlyOnceAtTheEnd(List<RenderedSection> sections) {
        int carrying = 0;
        for (RenderedSection section : sections) {
            if (section.text().contains(CLOSING_AFFORDANCE)) {
                carrying++;
            }
        }
        assertEquals(1, carrying, "the closing affordance appears exactly once per digest");
        assertTrue(sections.getLast().text().endsWith(CLOSING_AFFORDANCE),
                "the affordance closes the LAST section: " + sections.getLast().text());
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static DigestCategorizer newCategorizer(int minClusters) {
        DigestCategorizer categorizer = new DigestCategorizer();
        categorizer.categoryMinClusters = minClusters;
        return categorizer;
    }

    private static Post post(String uid, String title, List<String> tags) {
        return new Post(
                UUID.randomUUID(), uid, UUID.randomUUID(), "TestSrc",
                title, "https://example.com/" + uid, "body",
                Instant.now(), tags, List.of("unknown"), null, null, "rss", null);
    }

    /** Recording {@link CategoryRollupGenerator}: returns a canned synthesis. */
    private static final class RecordingCategoryRollupGenerator extends CategoryRollupGenerator {
        private String response = "category roll-up";

        void setResponse(String text) { this.response = text; }

        @Override
        public Optional<String> generateRollup(List<Cluster> categoryClusters,
                                               String sectionTag, String langCode) {
            return Optional.of(response);
        }
    }

    /** Recording {@link SummaryProseGenerator}: canned prose for the lead. */
    private static final class RecordingSummaryProseGenerator extends SummaryProseGenerator {
        private String responseText = "default summary";

        void setResponseText(String text) { this.responseText = text; }

        @Override
        public List<ClusterProse> generate(List<Cluster> clusters, String scopeLanguage) {
            List<ClusterProse> out = new ArrayList<>(clusters.size());
            for (Cluster c : clusters) {
                out.add(new ClusterProse(c, responseText, false));
            }
            return out;
        }
    }
}
