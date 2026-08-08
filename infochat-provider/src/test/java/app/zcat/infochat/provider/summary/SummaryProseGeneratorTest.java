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
    void aRefusalMarkerSurfacedBySanitizationDegradesTheCluster() {
        // The leading zero-width space hides the marker from a raw-text
        // check; the /ban hit makes sanitize() return the canonical form,
        // which leads with the marker.
        CapturingStub stub = new CapturingStub();
        stub.responseText.set("\u200B[REFUSAL: wrapped content asked for an action]\n/ban");
        SummaryProseGenerator gen = generatorWith(stub);

        List<ClusterProse> result = gen.generate(List.of(singletonCluster("p-1", "Title 1")), "en");

        assertEquals(1, result.size());
        assertTrue(result.get(0).degraded(),
                "a refusal marker surfaced only by sanitization degrades the cluster");
        assertFalse(result.get(0).prose().contains("[REFUSAL:"),
                "the marker never reaches user-visible output");
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
                Instant.now(), List.of("news"), List.of("unknown"));
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
                "https://example.com/d", "Body", Instant.now(), List.of("news"), List.of("unknown"));
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
                "https://example.com/x", "Body", Instant.now(), List.of("news"), List.of("unknown"));
        String degraded = SummaryProseGenerator.degradedProseFor(
                new Cluster("t-bare", List.of(p)), SanitizerTestDoubles.noAuditSanitizer(), "en");
        assertFalse(degraded.contains("]("),
                "degraded prose MUST NOT contain markdown link syntax");
        assertTrue(degraded.contains("https://example.com/x"));
    }

    @Test
    void degradedProseLeadsWithTheAnchorAndKeepsTheUidOnThatLine() {
        // The uid identifies the ENTRY, so it stays on the primary line: the
        // publisher's own words are a continuation beneath it, and moving the
        // uid down there would leave the line a reader actually scans
        // unidentified.
        String degraded = SummaryProseGenerator.degradedProseFor(
                new Cluster("t-cs", List.of(anchoredPost("p-cs", "Bitcoin dosáhl 100 tisíc",
                        "Bitcoin hits $100k", "cs"))),
                SanitizerTestDoubles.noAuditSanitizer(), "en");

        assertEquals("Bitcoin hits $100k — https://example.com/p-cs (uid p-cs)\n"
                        + "[Bitcoin dosáhl 100 tisíc]",
                degraded,
                "the anchor leads unbracketed with url and uid, the original brackets beneath");
    }

    @Test
    void degradedProseSuppressesTheAnchorForAReaderOfTheSourceLanguage() {
        // usesAnchor suppression, same rule the digest surface applies: a Czech
        // reader of a Czech source must not be shown English.
        String degraded = SummaryProseGenerator.degradedProseFor(
                new Cluster("t-cs", List.of(anchoredPost("p-cs", "Bitcoin dosáhl 100 tisíc",
                        "Bitcoin hits $100k", "cs"))),
                SanitizerTestDoubles.noAuditSanitizer(), "cs");

        assertEquals("Bitcoin dosáhl 100 tisíc — https://example.com/p-cs (uid p-cs)", degraded,
                "one unbracketed line — the publisher's words already are the reader's language");
    }

    @Test
    void degradedProseWithNoRenderableTextOpensWithTheUrlAndEmitsNoEmptyBracket() {
        // M1-714's omission contract survives the second line: no dangling
        // " — ", and the dropped headline cannot surface as a bare [].
        Post p = new Post(UUID.randomUUID(), "p-empty", UUID.randomUUID(), "Src", "",
                "https://example.com/e", "", Instant.now(), List.of("news"), List.of("unknown"),
                null, null, null, null, "cs", "Anchor never reached", null);

        String degraded = SummaryProseGenerator.degradedProseFor(
                new Cluster("t-empty", List.of(p)), SanitizerTestDoubles.noAuditSanitizer(), "en");

        assertEquals("https://example.com/e (uid p-empty)", degraded,
                "the line opens with its url and the uid still identifies it");
        assertFalse(degraded.contains("[]"), "no empty bracket; got: " + degraded);
    }

    @Test
    void degradedProseRedactsAClosedListEntrySpanningTheAnchorAndTheOriginal() {
        // Redteam 2026-08-05, medium/INJECTION — the same gap the digest
        // surface had. Command word on the anchor, flag on the original; one
        // sanitize call over the pair is what makes the entry match.
        String degraded = SummaryProseGenerator.degradedProseFor(
                new Cluster("t-unit", List.of(
                        anchoredPost("p-unit", "--all", "/list-sources", "cs"))),
                SanitizerTestDoubles.noAuditSanitizer(), "en");

        assertTrue(degraded.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "a closed-list entry straddling the two lines must redact; got: " + degraded);
        assertFalse(degraded.contains("/list-sources") || degraded.contains("--all"),
                "neither half of the span may survive; got: " + degraded);
    }

    @Test
    void degradedProseKeepsTheUnitAtOnePostSoOneTitleCannotEraseAnother() {
        // The bound on the widened unit: two POSTS must never share a sanitize
        // input, or a command word in one title and a flag in another would
        // delete every post between them (M1-694 round 3).
        String degraded = SummaryProseGenerator.degradedProseFor(
                new Cluster("t-two", List.of(
                        plainPost("p-one", "/list-sources"),
                        plainPost("p-two", "--all"))),
                SanitizerTestDoubles.noAuditSanitizer(), "en");

        assertTrue(degraded.contains("/list-sources") && degraded.contains("--all"),
                "two posts must never share one sanitize input; got: " + degraded);
        assertFalse(degraded.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "a redaction spanning two posts would mean the unit widened past the "
                        + "field pair; got: " + degraded);
    }

    /** An English-source post with no anchor, for the cross-post unit bound. */
    private static Post plainPost(String uid, String title) {
        return new Post(UUID.randomUUID(), uid, UUID.randomUUID(), "Src", title,
                "https://example.com/" + uid, "Body for " + uid, Instant.now(),
                List.of("news"), List.of("unknown"));
    }

    @Test
    void renderingTheAnchorCostsNoAdditionalProviderCall() {
        // ZERO PROVIDER CALLS on the degraded path, on the spy this surface
        // actually has: the generator's only LLM seam is the router. The
        // summarizer call throws (that is what degrades the cluster), and
        // composing the anchor-first prose afterwards must not reach the
        // provider again — the anchor is a column already on the projection,
        // not a translation.
        CapturingStub stub = new CapturingStub();
        stub.throwOnCall.set(true);
        SummaryProseGenerator gen = generatorWith(stub);

        List<ClusterProse> result = gen.generate(
                List.of(new Cluster("t-anchor", List.of(
                        anchoredPost("p-anchor", "Bitcoin dosáhl 100 tisíc",
                                "Bitcoin hits $100k", "cs")))),
                "en");

        assertEquals(1, stub.callCount.get(),
                "exactly the one failed summarizer call — rendering the anchor adds none");
        assertTrue(result.get(0).degraded(), "the throwing summarizer degrades the cluster");
        assertTrue(result.get(0).prose().contains("Bitcoin hits $100k"),
                "the degraded prose carries the anchor; got: " + result.get(0).prose());
    }

    /** A post whose stored language is non-English and which carries a title anchor. */
    private static Post anchoredPost(String uid, String title, String titleEn,
                                     String sourceLanguage) {
        return new Post(UUID.randomUUID(), uid, UUID.randomUUID(), "Src", title,
                "https://example.com/" + uid, "Body for " + uid, Instant.now(),
                List.of("news"), List.of("unknown"),
                null, null, null, null, sourceLanguage, titleEn, null);
    }

    private SummaryProseGenerator generatorWith(LlmProvider provider) {
        SummaryProseGenerator gen = new SummaryProseGenerator();
        gen.llmRouter = routerYielding(provider);
        // The degraded-fallback paths sanitize each post's title at
        // composition (M1-697), so the generator now needs a real
        // sanitizer — the double sanitizes for real; only its audit
        // DB write is stubbed.
        gen.llmOutputSanitizer = SanitizerTestDoubles.noAuditSanitizer();
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
                List.of("news"), List.of("unknown"));
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
