package app.zcat.infochat.provider.command;

import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.summary.SummaryProseGenerator.ClusterProse;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import app.zcat.infochat.provider.translation.TranslationPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
 * byte-for-byte as the byte-identical-replay guard.
 */
class ClusterBlockRendererTest {

    private ClusterBlockRenderer renderer;

    @BeforeEach
    void setUp() {
        BundleLoader bundleLoader = new BundleLoader();
        try {
            var method = BundleLoader.class.getDeclaredMethod("load");
            method.setAccessible(true);
            method.invoke(bundleLoader);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize BundleLoader for test", e);
        }
        // TranslationPipeline is never exercised here: all fixtures use degraded
        // prose, which the renderer copies verbatim without touching the pipeline.
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
                        + "summary: Degraded prose.\n"
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
                        + "shrnutí: Degraded prose.\n"
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

    private String render(Cluster cluster, String language) {
        StringBuilder out = new StringBuilder();
        renderer.appendClusterBlock(out, new ClusterProse(cluster, "Degraded prose.", true), language);
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
