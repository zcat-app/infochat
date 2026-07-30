package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.summary.SummaryProseGenerator.ClusterProse;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import app.zcat.infochat.provider.translation.TranslationPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
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
 * byte-for-byte as the byte-identical-replay guard. The fixture prose
 * string is a marker the renderer IGNORES — degraded prose is derived
 * from the cluster at render (M1-697), so the {@code summary:} line shows
 * the derived {@code title — url (uid)} composition, and the marker's
 * absence from the output is itself part of the pin.
 */
class ClusterBlockRendererTest {

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
        // TranslationPipeline is never exercised here: all fixtures use degraded
        // prose, which the renderer derives from the cluster (M1-697) without
        // touching the pipeline.
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
        // audit row that docs/spec/security.md commits to per occurrence.
        // M1-714.
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
                "en");
        String rendered = out.toString();

        assertFalse(auditRows.isEmpty(),
                "the flagged span sits beyond the truncation point, but the sanitizer "
                        + "must still have seen the FULL title and written its audit row — "
                        + "an empty list means truncation ran first");
        assertTrue(auditRows.stream()
                        .allMatch(row -> row.action() == AuditAction.LLM_OUTPUT_SANITIZED),
                "every emitted row must carry action LLM_OUTPUT_SANITIZED; got: " + auditRows);
        assertFalse(rendered.contains("/grant-admin"),
                "the raw privileged command must not survive into the block; got: " + rendered);
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
