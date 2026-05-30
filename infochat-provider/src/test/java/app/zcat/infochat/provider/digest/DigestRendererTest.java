package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.summary.ClusterTraversal;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.summary.EmptyEdgeSource;
import app.zcat.infochat.provider.summary.SummaryProseGenerator;
import app.zcat.infochat.provider.summary.SummaryProseGenerator.ClusterProse;
import app.zcat.infochat.provider.translation.TranslationCache;
import app.zcat.infochat.provider.translation.TranslationPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link DigestRenderer} wires the LLM summarizer pipeline
 * correctly: cluster → generate → sanitize → translate.
 */
class DigestRendererTest {

    private DigestRenderer renderer;
    private RecordingSummaryProseGenerator proseGenerator;

    @BeforeEach
    void setUp() throws Exception {
        renderer = new DigestRenderer();
        renderer.clusterTraversal = new ClusterTraversal(new EmptyEdgeSource(), 3);
        proseGenerator = new RecordingSummaryProseGenerator();
        renderer.summaryProseGenerator = proseGenerator;
        renderer.llmOutputSanitizer = new LlmOutputSanitizer();
        renderer.translationPipeline = newEnShortCircuitPipeline();
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

    // ----- helpers ----------------------------------------------------------

    private static Post post(String uid, String title) {
        return new Post(
                UUID.randomUUID(), uid, UUID.randomUUID(), "TestSrc",
                title, "https://example.com/" + uid, "body",
                Instant.now(), List.of("crypto"));
    }

    private static TranslationPipeline newEnShortCircuitPipeline() throws Exception {
        TranslationPipeline pipeline = new TranslationPipeline();
        java.lang.reflect.Field cacheField =
                TranslationPipeline.class.getDeclaredField("translationCache");
        cacheField.setAccessible(true);
        cacheField.set(pipeline, new TranslationCache());

        java.lang.reflect.Field providerField =
                TranslationPipeline.class.getDeclaredField("translationProvider");
        providerField.setAccessible(true);
        providerField.set(pipeline,
                (app.zcat.infochat.messaging.TranslationProvider) (text, from, to) -> text);

        java.lang.reflect.Field sanitizerField =
                TranslationPipeline.class.getDeclaredField("llmOutputSanitizer");
        sanitizerField.setAccessible(true);
        sanitizerField.set(pipeline, new LlmOutputSanitizer());
        return pipeline;
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
