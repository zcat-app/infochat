package app.zcat.infochat.provider.digest;

import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.digest.DigestRenderer.DigestMode;
import app.zcat.infochat.provider.summary.ClusterTraversal;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.summary.EmptyEdgeSource;
import app.zcat.infochat.provider.testsupport.LlmChainFixtures;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import app.zcat.infochat.provider.translation.TranslationCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static app.zcat.infochat.provider.testsupport.TranslationFixtures.newRealBundleLoader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the scheduled digest render actually CHARGES, driven through a
 * real provider chain (M1-769). This is the seam
 * {@code DigestRendererTest}'s exact counts moved to: there the
 * generative collaborators are non-calling doubles, so the only honest
 * number is zero, and an exact count means nothing unless a provider was
 * on the other end of it.
 *
 * <p>Every assertion compares the budget against
 * {@link LlmChainFixtures.Chain#providerCalls()} rather than a
 * hand-computed constant — the property under test is "the meter equals
 * the calls issued", and a literal would pin arithmetic instead.</p>
 */
class DigestRenderCallAccountingTest {

    private static final UUID GROUP_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-08-05T08:00:00Z"), ZoneOffset.UTC);

    private DigestRenderer renderer;
    private SystemLlmBudget budget;
    private LlmChainFixtures.Chain chain;

    @BeforeEach
    void setUp() throws Exception {
        BundleLoader bundleLoader = newRealBundleLoader();
        chain = LlmChainFixtures.newChain(bundleLoader, FIXED);

        budget = new SystemLlmBudget();
        budget.window = Duration.ofHours(24);
        budget.ceiling = 1000;
        budget.groupReserve = 0;
        budget.clock = FIXED;

        CategoryRollupGenerator rollupGenerator = new CategoryRollupGenerator();
        rollupGenerator.llmRouter = chain.router;
        rollupGenerator.llmOutputSanitizer = SanitizerTestDoubles.noAuditSanitizer();
        rollupGenerator.translationPipeline = chain.translationPipeline;

        renderer = new DigestRenderer();
        renderer.clusterTraversal = new ClusterTraversal(new EmptyEdgeSource(), 3);
        renderer.summaryProseGenerator = LlmChainFixtures.newProseGenerator(chain);
        renderer.categoryRollupGenerator = rollupGenerator;
        renderer.llmOutputSanitizer = SanitizerTestDoubles.noAuditSanitizer();
        renderer.translationPipeline = chain.translationPipeline;
        renderer.translationCache = new TranslationCache();
        renderer.digestCategorizer = newCategorizer(3);
        renderer.bundleLoader = bundleLoader;
        renderer.categoryItemCap = 12;
        renderer.categoryHeadlineCount = 5;
        renderer.translationMaxPerRender = 5;
        renderer.systemLlmBudget = budget;
        // The lead would promote clusters into a second summarizer batch;
        // off here so each test's call count has one source.
        renderer.leadMinimum = Integer.MAX_VALUE;
    }

    @Test
    void nonEnRollupDrawsBothItsSummarizerAndItsTranslatorCall() {
        // The M1-767 under-count: generateRollup calls translationPipeline
        // .run AFTER provider.generate, so a non-en roll-up spends two
        // calls where the render loop could only see one.
        renderer.renderSections(taggedPosts(3, "ai"), "cs", DigestMode.BRIEF, GROUP_ID);

        assertEquals(1, chain.providerCalls(ModelTask.SUMMARIZER),
                "brief renders one roll-up for the single surviving section");
        assertEquals(1, chain.providerCalls(ModelTask.TRANSLATOR),
                "and translates it, on a second provider call M1-767 never saw");
        assertEquals(chain.providerCalls(), budget.callsInWindow(),
                "the meter must equal the calls issued — both legs, not one");
    }

    @Test
    void enScopeRollupDrawsOnlyItsSummarizerCall() {
        renderer.renderSections(taggedPosts(3, "ai"), "en", DigestMode.BRIEF, GROUP_ID);

        assertEquals(0, chain.providerCalls(ModelTask.TRANSLATOR),
                "the en short-circuit returns before the translation provider");
        assertEquals(chain.providerCalls(), budget.callsInWindow());
        assertTrue(budget.callsInWindow() > 0, "control: the render did spend something");
    }

    @Test
    void fullRenderDrawsExactlyItsPerClusterSummarizerCalls() {
        renderer.renderSections(taggedPosts(4, "ai"), "en", DigestMode.FULL, GROUP_ID);

        assertEquals(4, chain.providerCalls(ModelTask.SUMMARIZER),
                "full renders one summarizer call per shown cluster");
        assertEquals(chain.providerCalls(), budget.callsInWindow());
    }

    @Test
    void openBreakerRenderDrawsNothing() {
        // The phantom-charge leg (M1-767 redteam, claude f3): a sustained
        // outage filled the window at full nominal rate and converted a
        // transient provider failure into a 24h deployment degradation.
        chain.breakers.recordUnreachableForTask(ModelTask.SUMMARIZER);
        assertTrue(chain.breakers.wouldShortCircuit(ModelTask.SUMMARIZER),
                "control: the breaker is OPEN before the render starts");

        List<DigestRenderer.RenderedSection> sections =
                renderer.renderSections(taggedPosts(4, "ai"), "en", DigestMode.FULL, GROUP_ID);

        assertEquals(0, chain.providerCalls(),
                "control: an OPEN breaker short-circuits every call without an HTTP attempt");
        assertEquals(0, budget.callsInWindow(),
                "so the render charges nothing — an outage must not consume the budget");
        assertFalse(sections.isEmpty(), "and the digest still goes out, degraded");
    }

    @Test
    void emptyPromptRollupSkipDrawsNothing() {
        // buildPrompt yields empty when no cluster carries renderable
        // content, and generateRollup then returns BEFORE llmRouter.forTask.
        renderer.renderSections(untitledPosts(3, "ai"), "en", DigestMode.BRIEF, GROUP_ID);

        assertEquals(0, chain.providerCalls(),
                "control: the empty-prompt skip issues no call");
        assertEquals(0, budget.callsInWindow(), "so it charges none");
    }

    @Test
    void renderStopsIssuingCallsAtTheCeilingAndDegradesTheRemainder() {
        // The admission-only bound (both auditors' finding 1): a render
        // admitted with one call of headroom could previously spend the
        // whole slot window. Now the ceiling engages mid-render.
        budget.ceiling = 3;

        List<DigestRenderer.RenderedSection> sections =
                renderer.renderSections(taggedPosts(9, "ai"), "en", DigestMode.FULL, GROUP_ID);

        assertEquals(3, budget.callsInWindow(),
                "spend stops AT the ceiling, however many clusters remain");
        assertEquals(3, chain.providerCalls(),
                "and the refused calls never reach the provider — a refusal is not a "
                        + "call that happened and was discarded");
        assertFalse(sections.isEmpty(),
                "the digest still goes out: the over-ceiling clusters degrade through "
                        + "SummaryProseGenerator's existing per-cluster fallback");
    }

    private static DigestCategorizer newCategorizer(int minClusters) {
        DigestCategorizer categorizer = new DigestCategorizer();
        categorizer.categoryMinClusters = minClusters;
        return categorizer;
    }

    private static List<Post> taggedPosts(int count, String tag) {
        return posts(count, tag, true);
    }

    private static List<Post> untitledPosts(int count, String tag) {
        return posts(count, tag, false);
    }

    private static List<Post> posts(int count, String tag, boolean titled) {
        List<Post> posts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String uid = tag + i;
            posts.add(new Post(
                    UUID.randomUUID(), uid, UUID.randomUUID(), "TestSrc",
                    titled ? "Headline " + tag + " " + i : "",
                    "https://example.com/" + uid, "body",
                    // Fixed instant: a fixture time relative to wall-clock
                    // is the date-boundary time bomb M1-602 censused.
                    FIXED.instant(), List.of(tag), List.of("unknown"),
                    null, null, null, null, "en"));
        }
        return posts;
    }
}
