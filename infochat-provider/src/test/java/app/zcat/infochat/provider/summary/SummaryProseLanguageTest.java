package app.zcat.infochat.provider.summary;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmRouter;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code en} direction of M1-778: a {@code /summary} cluster came back
 * in Czech to an {@code en} scope, silently, because the summarizer was fed
 * the source-language columns although the post carried a populated English
 * anchor.
 *
 * <p>Both halves of the repair are pinned here, and each covers what the
 * other cannot. The anchored operands remove the steer for a post the ingest
 * translator succeeded on; the system prompt's language pin covers the
 * residual it cannot — a non-English post whose anchor is NULL because the
 * ingest translator gave up after its two attempts.
 *
 * <p><b>Why the wrong-language input is a fixture, never an observed
 * model.</b> The live failure was intermittent — a re-run summarized the
 * same post correctly — so a test that watched a real summarizer would pass
 * on the broken code most of the time. Every input here is a hand-built
 * {@link Post}, and the one test that calls the generator drives a stub
 * whose reply is a constant.
 */
class SummaryProseLanguageTest {

    private static final String CZECH_TITLE = "Tvorba interaktivních aplikací s GUI";
    private static final String CZECH_BODY =
            "Článek ukazuje, jak otevřít okno a vykreslit 2D scénu.";
    private static final String ANCHOR_TITLE = "Building interactive GUI applications";
    private static final String ANCHOR_BODY =
            "An article demonstrating how to open a window and render a 2D scene.";

    @Test
    void promptCarriesTheEnglishAnchorInsteadOfThePublishersOwnFields() {
        String prompt = SummaryProseGenerator.buildPrompt(clusterOf(
                post(CZECH_TITLE, CZECH_BODY, ANCHOR_TITLE, ANCHOR_BODY)));

        assertTrue(prompt.contains(ANCHOR_TITLE), "anchor title reaches the model; got: " + prompt);
        assertTrue(prompt.contains(ANCHOR_BODY), "anchor body reaches the model; got: " + prompt);
        assertFalse(prompt.contains(CZECH_TITLE),
                "the source-language title is what steered the model into Czech; got: " + prompt);
        assertFalse(prompt.contains(CZECH_BODY),
                "the source-language body is what steered the model into Czech; got: " + prompt);
    }

    @Test
    void promptFallsBackToThePublishersFieldsWhenNoAnchorWasStored() {
        String prompt = SummaryProseGenerator.buildPrompt(clusterOf(
                post(CZECH_TITLE, CZECH_BODY, null, null)));

        assertTrue(prompt.contains(CZECH_TITLE),
                "a post the ingest translator gave up on still has to be summarized; got: " + prompt);
        assertTrue(prompt.contains(CZECH_BODY), "same for its body; got: " + prompt);
    }

    @Test
    void anchorIsResolvedPerFieldNotPerPost() {
        // IngestTranslationWorker decides title_en and body_en independently
        // — a title-only post stores a NULL body_en — so an all-or-nothing
        // rule would throw away a usable title anchor.
        String prompt = SummaryProseGenerator.buildPrompt(clusterOf(
                post(CZECH_TITLE, CZECH_BODY, ANCHOR_TITLE, null)));

        assertTrue(prompt.contains(ANCHOR_TITLE), "the title anchor is used; got: " + prompt);
        assertTrue(prompt.contains(CZECH_BODY),
                "the body has no anchor and falls back on its own; got: " + prompt);
        assertFalse(prompt.contains(CZECH_TITLE),
                "the title must not fall back just because the body did; got: " + prompt);
    }

    @Test
    void blankAnchorCountsAsAbsent() {
        String prompt = SummaryProseGenerator.buildPrompt(clusterOf(
                post(CZECH_TITLE, CZECH_BODY, "   ", "")));

        assertTrue(prompt.contains(CZECH_TITLE),
                "whitespace is not text the model can summarize; got: " + prompt);
        assertTrue(prompt.contains(CZECH_BODY), "same for an empty body anchor; got: " + prompt);
    }

    @Test
    void redactionPlaceholderInsideTheAnchorReachesThePromptUnchanged() {
        // security.md §Failure handling: the placeholder is never stripped.
        // The coalesce changes its CARRIER for an anchored post (body →
        // body_en) and must not change that rule.
        String anchorWithPlaceholder = "An article about [REDACTED:a1b2c3] and its rollout.";
        String prompt = SummaryProseGenerator.buildPrompt(clusterOf(
                post(CZECH_TITLE, CZECH_BODY, ANCHOR_TITLE, anchorWithPlaceholder)));

        assertTrue(prompt.contains("[REDACTED:a1b2c3]"),
                "the placeholder survives the anchor swap; got: " + prompt);
    }

    @Test
    void systemPromptPinsTheOutputLanguageToEnglish() {
        // The residual the anchor cannot cover: a non-English post whose
        // ingest anchor is NULL still reaches the model in its own language,
        // and an `en` scope short-circuits TranslationPipeline, so nothing
        // downstream can notice the reply's language.
        String systemPrompt = SummaryProseGenerator.SUMMARIZER_SYSTEM_PROMPT;

        assertTrue(systemPrompt.contains("Always write in English"),
                "the summarizer's output language is a contract, not the model's choice; got: "
                        + systemPrompt);
        assertTrue(systemPrompt.contains("[REFUSAL:"),
                "the injection-defense framing is unchanged; got: " + systemPrompt);
    }

    @Test
    void enScopeSummarizingANonEnglishClusterAsksTheModelInEnglish() {
        CapturingStub stub = new CapturingStub();
        SummaryProseGenerator generator = new SummaryProseGenerator();
        generator.llmRouter = routerYielding(stub);
        generator.llmOutputSanitizer = SanitizerTestDoubles.noAuditSanitizer();

        generator.generate(
                List.of(clusterOf(post(CZECH_TITLE, CZECH_BODY, ANCHOR_TITLE, ANCHOR_BODY))),
                "en");

        assertEquals(1, stub.userPrompts.size(), "exactly one summarizer call");
        assertTrue(stub.userPrompts.get(0).contains(ANCHOR_TITLE),
                "the cluster reaches the model as English; got: " + stub.userPrompts.get(0));
        assertFalse(stub.userPrompts.get(0).contains(CZECH_TITLE),
                "no Czech operand is left to answer in; got: " + stub.userPrompts.get(0));
        assertTrue(stub.systemPrompts.get(0).contains("Always write in English"),
                "and the instruction rides the same call; got: " + stub.systemPrompts.get(0));
    }

    private static Cluster clusterOf(Post post) {
        return new Cluster("t-1", List.of(post));
    }

    /** A Czech-source post, with the anchor fields under test. */
    private static Post post(String title, String body,
                             @Nullable String titleEn, @Nullable String bodyEn) {
        return new Post(UUID.randomUUID(), "p-1", UUID.randomUUID(), "Root.cz", title,
                "https://example.com/p-1", body, Instant.now(),
                List.of("technology"), List.of("unknown"),
                null, null, null, null, "cs", titleEn, bodyEn);
    }

    private static LlmRouter routerYielding(LlmProvider provider) {
        return new LlmRouter(
                List.of(new LlmRouter.Entry("test-stub", provider, Set.of("en"))),
                LlmRouter.ConfigReader.fromMap(
                        Map.of(LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER, "test-stub")));
    }

    /** Hand-rolled capturing stub; the provider module carries no Mockito. */
    private static final class CapturingStub implements LlmProvider {
        final List<String> systemPrompts = new CopyOnWriteArrayList<>();
        final List<String> userPrompts = new CopyOnWriteArrayList<>();

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            systemPrompts.add(systemPrompt);
            userPrompts.add(userPrompt);
            return new LlmResponse("English synthesis of the cluster.");
        }
    }
}
