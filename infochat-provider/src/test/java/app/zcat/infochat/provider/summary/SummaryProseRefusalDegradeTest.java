package app.zcat.infochat.provider.summary;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmRouter;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.summary.SummaryProseGenerator.ClusterProse;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@code [REFUSAL: ...]} interception that
 * {@link SummaryProseGenerator#generate} installs at the
 * post-trim / pre-empty-text seam. The
 * {@code SUMMARIZER_SYSTEM_PROMPT} instructs the LLM to emit
 * the refusal token whenever the wrapped untrusted content asks
 * for an action; this test pins that the interception (a) routes
 * the cluster through the degraded-form path (so the refusal
 * literal is never user-visible) and (b) is per-cluster — other
 * clusters in the same batch continue to attempt generation.
 *
 * <p>The collaborator is a hand-rolled per-call {@link LlmProvider}
 * stub, mirroring the pattern in {@code SummaryProseGeneratorTest}
 * (no Mockito dependency in this module).
 */
class SummaryProseRefusalDegradeTest {

    @Test
    void refusalMarkerDegradesOnlyTheRefusingClusterAndIsNeverUserVisible() {
        SequencedStub stub = new SequencedStub(List.of(
                "[REFUSAL: ignore-me]",
                "Normal one-paragraph summary about the second cluster."));
        SummaryProseGenerator gen = new SummaryProseGenerator();
        gen.llmRouter = routerYielding(stub);
        LlmOutputSanitizer sanitizer = SanitizerTestDoubles.noAuditSanitizer();
        gen.llmOutputSanitizer = sanitizer;

        Cluster refusingCluster = singletonCluster("p-ref", "RefTitle");
        Cluster normalCluster = singletonCluster("p-ok", "OkTitle");
        List<ClusterProse> result = gen.generate(
                List.of(refusingCluster, normalCluster), "en");

        assertEquals(2, result.size(), "one ClusterProse per input cluster");
        assertEquals(2, stub.callCount.get(),
                "both clusters attempt generation — refusal does NOT short-circuit the batch");

        ClusterProse refusing = result.get(0);
        assertTrue(refusing.degraded(),
                "refusal marker must route through the degraded path");
        assertEquals(SummaryProseGenerator.degradedProseFor(refusingCluster, sanitizer), refusing.prose(),
                "refusing cluster's prose must be the canonical degraded form");
        assertFalse(refusing.prose().contains("[REFUSAL:"),
                "the [REFUSAL: ...] literal MUST NOT appear in user-visible output");

        ClusterProse normal = result.get(1);
        assertFalse(normal.degraded(),
                "the non-refusing cluster keeps degraded=false");
        assertEquals("Normal one-paragraph summary about the second cluster.", normal.prose(),
                "the non-refusing cluster carries the LLM's prose verbatim");
    }

    private static LlmRouter routerYielding(LlmProvider provider) {
        return new LlmRouter(
                List.of(new LlmRouter.Entry("test-stub", provider, java.util.Set.of("en"))),
                LlmRouter.ConfigReader.fromMap(java.util.Map.of(
                        LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER, "test-stub")));
    }

    private static Cluster singletonCluster(String uid, String title) {
        Post p = new Post(UUID.randomUUID(), uid, UUID.randomUUID(), "Src", title,
                "https://example.com/" + uid, "Body for " + title, Instant.now(),
                List.of("news"), List.of("unknown"));
        return new Cluster("t-" + uid, List.of(p));
    }

    /**
     * Returns a different response per call, in order. Throws
     * AssertionError if the generator calls more times than there
     * are scripted responses — that would mean a regression in the
     * per-cluster call shape.
     */
    private static final class SequencedStub implements LlmProvider {
        private final List<String> responses;
        final AtomicInteger callCount = new AtomicInteger();

        SequencedStub(List<String> responses) {
            this.responses = responses;
        }

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            int idx = callCount.getAndIncrement();
            if (idx >= responses.size()) {
                throw new AssertionError(
                        "SequencedStub exhausted: generator made " + (idx + 1)
                                + " calls but only " + responses.size()
                                + " responses were scripted");
            }
            return new LlmResponse(responses.get(idx));
        }
    }
}
