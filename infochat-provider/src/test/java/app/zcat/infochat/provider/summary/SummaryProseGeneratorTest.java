package app.zcat.infochat.provider.summary;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmRouter;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.summary.SummaryProseGenerator.ClusterProse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SummaryProseGenerator}. The {@link LlmProvider}
 * collaborator is a hand-rolled stub (no Mockito dependency — the
 * provider module pom intentionally avoids Mockito and uses
 * stub-and-flag patterns instead, mirroring
 * {@code Stage2WorkerIT.TestStubLlmProvider} on the collector side).
 * The generator's only seam to the LLM is the {@link LlmRouter} call,
 * so the test wires the stub through a hand-built router.
 */
class SummaryProseGeneratorTest {

    @Test
    void singleClusterInvokesLlmExactlyOnce() {
        CapturingStub stub = new CapturingStub();
        stub.responseText.set("one-cluster summary");
        SummaryProseGenerator gen = generatorWith(stub);

        List<ClusterProse> result = gen.generate(List.of(singletonCluster("p-1", "Title 1")), "en");

        assertEquals(1, result.size());
        assertEquals(1, stub.callCount.get(), "exactly one LLM call");
        assertFalse(result.get(0).degraded());
        assertEquals("one-cluster summary", result.get(0).prose());
    }

    @Test
    void threeClustersInvokeLlmExactlyThreeTimes() {
        CapturingStub stub = new CapturingStub();
        stub.responseText.set("prose");
        SummaryProseGenerator gen = generatorWith(stub);

        List<ClusterProse> result = gen.generate(List.of(
                singletonCluster("p-a", "A"),
                singletonCluster("p-b", "B"),
                singletonCluster("p-c", "C")
        ), "en");

        assertEquals(3, result.size());
        assertEquals(3, stub.callCount.get(), "exactly three LLM calls");
    }

    @Test
    void promptIncludesRedactedPlaceholderUnchanged() {
        CapturingStub stub = new CapturingStub();
        stub.responseText.set("ok");
        SummaryProseGenerator gen = generatorWith(stub);

        Post p = new Post(UUID.randomUUID(), "p-redact", UUID.randomUUID(), "Src", "RedTitle",
                "https://example.com/r", "Body with [REDACTED:abc123] placeholder.",
                Instant.now(), List.of("news"));
        Cluster cluster = new Cluster("t-redact", List.of(p));

        gen.generate(List.of(cluster), "en");

        assertEquals(1, stub.capturedUserPrompts.size(), "exactly one user prompt captured");
        String userPrompt = stub.capturedUserPrompts.get(0);
        assertTrue(userPrompt.contains("[REDACTED:abc123]"),
                "the redaction placeholder MUST survive into the LLM prompt verbatim "
                        + "(docs/spec/security.md §Failure handling). Captured prompt: " + userPrompt);
    }

    @Test
    void llmFailureProducesDegradedFallbackPerCluster() {
        CapturingStub stub = new CapturingStub();
        stub.throwOnCall.set(true);
        SummaryProseGenerator gen = generatorWith(stub);

        Post p = new Post(UUID.randomUUID(), "p-degraded", UUID.randomUUID(), "Src", "DegTitle",
                "https://example.com/d", "Body", Instant.now(), List.of("news"));
        List<ClusterProse> result = gen.generate(
                List.of(new Cluster("t-d", List.of(p))), "en");

        assertEquals(1, result.size());
        assertTrue(result.get(0).degraded(), "LLM throw → degraded=true");
        assertTrue(result.get(0).prose().contains("DegTitle"),
                "degraded prose includes the headline");
        assertTrue(result.get(0).prose().contains("https://example.com/d"),
                "degraded prose includes the bare URL");
        assertTrue(result.get(0).prose().contains("p-degraded"),
                "degraded prose includes the post UID");
    }

    @Test
    void degradedProseAvoidsMarkdownLinkSyntax() {
        Post p = new Post(UUID.randomUUID(), "p-bare", UUID.randomUUID(), "Src", "Title",
                "https://example.com/x", "Body", Instant.now(), List.of("news"));
        String degraded = SummaryProseGenerator.degradedProseFor(
                new Cluster("t-bare", List.of(p)));
        assertFalse(degraded.contains("]("),
                "degraded prose MUST NOT contain markdown link syntax");
        assertTrue(degraded.contains("https://example.com/x"));
    }

    private SummaryProseGenerator generatorWith(LlmProvider provider) {
        SummaryProseGenerator gen = new SummaryProseGenerator();
        gen.llmRouter = routerYielding(provider);
        return gen;
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
                List.of("news"));
        return new Cluster("t-" + uid, List.of(p));
    }

    /**
     * Hand-rolled {@link LlmProvider} stub mirroring the
     * stub-and-flag shape used in
     * {@code Stage2WorkerIT.TestStubLlmProvider} on the collector
     * side. No Mockito dependency.
     */
    private static final class CapturingStub implements LlmProvider {
        final AtomicInteger callCount = new AtomicInteger();
        final AtomicReference<String> responseText = new AtomicReference<>("default");
        final AtomicBoolean throwOnCall = new AtomicBoolean(false);
        final List<String> capturedSystemPrompts = new CopyOnWriteArrayList<>();
        final List<String> capturedUserPrompts = new CopyOnWriteArrayList<>();

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            callCount.incrementAndGet();
            capturedSystemPrompts.add(systemPrompt);
            capturedUserPrompts.add(userPrompt);
            if (throwOnCall.get()) {
                throw new RuntimeException("LLM unreachable (test stub)");
            }
            return new LlmResponse(responseText.get());
        }
    }
}
