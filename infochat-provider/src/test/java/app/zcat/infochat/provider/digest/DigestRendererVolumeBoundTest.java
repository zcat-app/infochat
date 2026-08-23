package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.digest.DigestRenderer.DigestMode;
import app.zcat.infochat.provider.digest.DigestRenderer.RenderResult;
import app.zcat.infochat.provider.digest.DigestRenderer.RenderedSection;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.summary.ClusterTraversal;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.summary.EmptyEdgeSource;
import app.zcat.infochat.provider.summary.PostReferenceEdgeSource;
import app.zcat.infochat.provider.summary.SummaryProseGenerator;
import app.zcat.infochat.provider.summary.SummaryProseGenerator.ClusterProse;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import app.zcat.infochat.provider.translation.TranslationCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static app.zcat.infochat.provider.testsupport.TranslationFixtures.newEnShortCircuitPipeline;
import static app.zcat.infochat.provider.testsupport.TranslationFixtures.newRealBundleLoader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-912: the digest broadcast's render volume is bounded in every mode.
 * FULL applies the per-section item cap with the prominence head kept and
 * ONE per-section demotion line accounting for the capped-out clusters;
 * a fully degraded render lists at most {@code
 * infochat.digest.degraded-member-cap} member posts per cluster with a
 * "+M more" suffix, and reports every synthesis unit degraded so
 * DigestWorker can write an honest {@code is_degraded}.
 */
class DigestRendererVolumeBoundTest {

    private static final UUID GROUP_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");

    private DigestRenderer renderer;
    private BundleLoader bundleLoader;
    private RecordingSummaryProseGenerator proseGenerator;
    private RecordingCategoryRollupGenerator rollupGenerator;

    @BeforeEach
    void setUp() throws Exception {
        bundleLoader = newRealBundleLoader();
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
        renderer.categoryHeadlineCount = 5;
        renderer.degradedMemberCap = 3;
    }

    /**
     * REPRODUCTION (cap half). One 20-cluster section at item-cap 12: FULL
     * renders exactly the 12-cluster prominence head, generates prose for
     * exactly those 12 (the generate() input is the capped list in exact
     * render order), and appends ONE demotion line naming the 8 not shown.
     * The fixture's prominence order is controlled: post 07 carries the
     * quiet-source signal (1 window post vs 300) so it tops the section;
     * the rest tie and keep input order.
     */
    @Test
    void fullModeRendersAtMostItemCapClustersPerSectionAndAccountsForTheRest() {
        renderer.leadMinimum = Integer.MAX_VALUE;
        proseGenerator.setResponseText("prose");
        proseGenerator.setEchoTitle(true);
        List<Post> posts = twentySecurityPosts();

        RenderResult result =
                renderer.renderSections(posts, "en", DigestMode.FULL, GROUP_ID);

        List<RenderedSection> sections = result.sections();
        assertEquals(1, sections.size(), "fixture: one section, 20 clusters");
        List<String> expectedHead = List.of(
                "sec-07", "sec-00", "sec-01", "sec-02", "sec-03", "sec-04",
                "sec-05", "sec-06", "sec-08", "sec-09", "sec-10", "sec-11");
        assertEquals(expectedHead, proseGenerator.lastInputUids(),
                "generate() receives the capped cluster list in exact render order "
                        + "(the prominence head), so positional prose pairing survives the cap");
        assertEquals(12, proseGenerator.callCount(),
                "prose is generated for exactly the item-cap clusters — never for capped-out ones");
        assertEquals(12, result.synthesisTotal(),
                "each shown cluster's prose is one synthesis unit");
        assertEquals(0, result.synthesisDegraded(),
                "healthy prose: nothing degraded, so the worker keeps is_degraded=FALSE");
        String text = sections.getFirst().text();
        // Content pairing, not counts alone: the echoed title binds each prose
        // block to its own cluster, so a cross-cluster prose swap fails here.
        for (String uid : expectedHead) {
            assertTrue(text.contains("prose Story " + uid.substring(4)),
                    "shown cluster " + uid + " renders its own prose: " + text);
        }
        for (int i = 12; i < 20; i++) {
            assertFalse(text.contains("Story " + two(i)),
                    "capped-out cluster " + two(i) + " must not render: " + text);
        }
        assertEquals(1, countOccurrences(text, "+8 more stories"),
                "ONE demotion line names the 8 not shown: " + text);
        assertTrue(text.contains("/summary security --full"),
                "the demotion line steers to the tag's uncapped pull surface: " + text);
    }

    /**
     * REPRODUCTION (degraded half). A FULL render whose generator degrades
     * every cluster lists at most the degraded-member-cap (3) member posts
     * per cluster, with a "+M more" suffix accounting for the rest, and
     * reports every synthesis unit degraded — the counts DigestWorker's
     * zero-prose rule reads. The fixture is ONE cluster of 6 members
     * (complete edge graph).
     */
    @Test
    void fullyDegradedFullDigestIsBoundedAndFlaggedDegraded() {
        renderer.leadMinimum = Integer.MAX_VALUE;
        proseGenerator.setDegradedMode(true);
        renderer.clusterTraversal = new ClusterTraversal(new CompleteEdgeSource(), 3);
        List<Post> posts = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            posts.add(post("mem-" + two(i), "Member " + two(i), List.of("security"), 300));
        }

        RenderResult result =
                renderer.renderSections(posts, "en", DigestMode.FULL, GROUP_ID);

        assertEquals(1, result.sections().size(), "fixture: one section, one 6-member cluster");
        assertEquals(1, result.synthesisTotal(), "the one rendered cluster is one synthesis unit");
        assertEquals(1, result.synthesisDegraded(),
                "every rendered cluster degraded — zero generated prose, the "
                        + "counts the worker's zero-prose rule reads");
        String text = result.sections().getFirst().text();
        assertEquals(3, countOccurrences(text, "(uid "),
                "at most degraded-member-cap member lines render per cluster: " + text);
        assertEquals(1, countOccurrences(text, "+3 more posts"),
                "the suffix accounts for the capped-out members: " + text);
    }

    /**
     * P3: the member cap must not change the sanitize unit. A command-shaped
     * title in a RENDERED member redacts (one author's field per
     * DisplayHeadline.anchorFirst call, M1-697); the split pair
     * ({@code /list-sources} … {@code --all} in DIFFERENT members) renders
     * verbatim — no multi-member concatenation ever reaches the sanitizer
     * (the M1-694-r3 cross-post span shape); a capped-out member never
     * reaches the sanitizer at all.
     */
    @Test
    void memberCapKeepsThePerMemberSanitizeUnitAndSkipsCappedOutMembers() {
        renderer.leadMinimum = Integer.MAX_VALUE;
        proseGenerator.setDegradedMode(true);
        renderer.degradedMemberCap = 4;
        renderer.clusterTraversal = new ClusterTraversal(new CompleteEdgeSource(), 3);
        List<Post> posts = List.of(
                post("m-1", "/grant-admin 11111111-2222-3333-4444-555555555555",
                        List.of("security"), 300),
                post("m-2", "/list-sources", List.of("security"), 300),
                post("m-3", "--all", List.of("security"), 300),
                post("m-4", "Clean member", List.of("security"), 300),
                post("m-5", "Capped member five", List.of("security"), 300),
                post("m-6", "Capped member six", List.of("security"), 300));

        RenderResult result =
                renderer.renderSections(posts, "en", DigestMode.FULL, GROUP_ID);

        String text = result.sections().getFirst().text();
        assertTrue(text.contains(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT),
                "a command-shaped title in a rendered member redacts: " + text);
        assertFalse(text.contains("/grant-admin"),
                "the privileged command must not survive: " + text);
        assertTrue(text.contains("/list-sources") && text.contains("--all"),
                "the split pair across two members renders verbatim — members never "
                        + "share one sanitize input: " + text);
        assertTrue(text.contains("Clean member"), "clean members render: " + text);
        assertFalse(text.contains("Capped member five") || text.contains("Capped member six"),
                "capped-out members never render: " + text);
        assertEquals(4, countOccurrences(text, "(uid "),
                "exactly the member-cap lines render: " + text);
        assertEquals(1, countOccurrences(text, "+2 more posts"),
                "the suffix accounts for the two capped-out members: " + text);
    }

    /**
     * The FULL cap never reaches the brief/normal body: no prose calls, no
     * demotion line, and the roll-up still sees ALL clusters of the section
     * (its existing contract names what a capped headline list hides). The
     * true-count header still carries the full 20.
     */
    @Test
    void briefAndNormalModesAreUnaffectedByTheFullCap() {
        renderer.leadMinimum = Integer.MAX_VALUE;
        rollupGenerator.setResponse("roll-up");
        List<Post> posts = twentySecurityPosts();

        String brief = renderJoined(posts, DigestMode.BRIEF);

        assertEquals(0, proseGenerator.callCount(), "brief renders no per-cluster prose");
        assertEquals(1, rollupGenerator.callCount(), "one roll-up for the one section");
        assertTrue(brief.startsWith("SECURITY NEWS — 20 STORIES"),
                "brief keeps the TRUE-count header: " + brief);
        assertFalse(brief.contains("more stories"), "no demotion line in brief: " + brief);

        String normal = renderJoined(posts, DigestMode.NORMAL);

        assertEquals(0, proseGenerator.callCount(), "normal renders no per-cluster prose");
        assertEquals(2, rollupGenerator.callCount(), "one roll-up per render — the second render");
        assertTrue(normal.contains("roll-up"), "the roll-up renders: " + normal);
        assertFalse(normal.contains("more stories"), "no demotion line in normal: " + normal);
    }

    /** Under the cap the FULL body's shape is unchanged: every cluster renders, no demotion line. */
    @Test
    void fullDigestUnderTheCapAppendsNoDemotionLine() {
        renderer.leadMinimum = Integer.MAX_VALUE;
        proseGenerator.setResponseText("prose");
        List<Post> posts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            posts.add(post("sec-" + two(i), "Story " + two(i), List.of("security"), 300));
        }

        String text = renderJoined(posts, DigestMode.FULL);

        assertEquals(10, proseGenerator.callCount(), "all 10 clusters render under the cap");
        assertFalse(text.contains("more stories"), "no demotion line under the cap: " + text);
    }

    /**
     * The Other bucket's demotion line steers to BARE /summary --full — its
     * tag is not in the controlled vocabulary, the shortFooter split.
     */
    @Test
    void otherBucketDemotionLineSteersToBareSummaryFull() {
        renderer.leadMinimum = Integer.MAX_VALUE;
        proseGenerator.setResponseText("prose");
        List<Post> posts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            posts.add(post("u-" + two(i), "Untagged " + two(i), List.of(), 300));
        }

        String text = renderJoined(posts, DigestMode.FULL);

        assertEquals(12, proseGenerator.callCount(), "the cap applies to the Other section too");
        assertEquals(1, countOccurrences(text, "+8 more stories"),
                "ONE demotion line on the capped Other section: " + text);
        assertTrue(text.contains("/summary --full"),
                "the Other bucket steers to bare /summary --full: " + text);
        assertFalse(text.contains("/summary null") || text.contains("/summary other"),
                "no raw tag leaks into the Other bucket's steer: " + text);
    }

    /**
     * The cs demotion line renders the correct Czech plural form (P6 —
     * five-plus takes the "dalších zpráv" form; 2-4 would take "další
     * zprávy"), proving the count-bearing key is a real plural, not a
     * copied en template.
     */
    @Test
    void czechDemotionLineUsesTheCzechPluralForm() {
        renderer.leadMinimum = Integer.MAX_VALUE;
        proseGenerator.setResponseText("prose");
        List<Post> posts = twentySecurityPosts();

        String text = renderJoined(posts, DigestMode.FULL, "cs");

        assertTrue(text.contains("+8 dalších zpráv — /summary security --full"),
                "cs five-plus plural form renders: " + text);
        assertFalse(text.contains("more stories"), "never the en wording: " + text);
    }

    /**
     * The brief/normal half of the zero-prose rule: a render whose every
     * roll-up comes back empty (LLM outage / empty / REFUSAL) reports all
     * synthesis units degraded — the counts the worker reads for its
     * honest is_degraded.
     */
    @Test
    void fullyDegradedRollupsReportAllSynthesisDegraded() {
        renderer.leadMinimum = Integer.MAX_VALUE;
        rollupGenerator.setFailEverywhere(true);
        List<Post> posts = List.of(
                post("s-01", "Sec 1", List.of("security"), 300),
                post("s-02", "Sec 2", List.of("security"), 300),
                post("s-03", "Sec 3", List.of("security"), 300));

        RenderResult result =
                renderer.renderSections(posts, "en", DigestMode.NORMAL, GROUP_ID);

        assertEquals(1, result.synthesisTotal(), "the one section's roll-up is the synthesis unit");
        assertEquals(1, result.synthesisDegraded(),
                "a failed roll-up counts degraded — zero generated synthesis");
    }

    // ----- helpers ----------------------------------------------------------

    private String renderJoined(List<Post> posts, DigestMode mode) {
        return renderJoined(posts, mode, "en");
    }

    private String renderJoined(List<Post> posts, DigestMode mode, String langCode) {
        return String.join("\n\n",
                renderer.renderSections(posts, langCode, mode, GROUP_ID).sections().stream()
                        .map(RenderedSection::text)
                        .toList());
    }

    /**
     * Twenty security-tagged singleton clusters, uniform signals EXCEPT post
     * 07 (quiet source, 1 window post vs 300) — the controlled prominence
     * order: sec-07 tops the section, the rest tie and keep input order.
     */
    private static List<Post> twentySecurityPosts() {
        List<Post> posts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            posts.add(post("sec-" + two(i), "Story " + two(i), List.of("security"),
                    i == 7 ? 1 : 300));
        }
        return posts;
    }

    /**
     * Every input post neighbours every other: the traversal's BFS then
     * yields ONE cluster over all input posts (seed-first member order),
     * the multi-member-cluster fixture the EmptyEdgeSource tests cannot
     * build.
     */
    private static final class CompleteEdgeSource implements PostReferenceEdgeSource {
        @Override
        public Map<UUID, Set<UUID>> neighborsAmong(Collection<UUID> postIds) {
            Map<UUID, Set<UUID>> out = new java.util.HashMap<>();
            for (UUID id : postIds) {
                Set<UUID> neighbours = new LinkedHashSet<>(postIds);
                neighbours.remove(id);
                out.put(id, neighbours);
            }
            return out;
        }
    }

    private static DigestCategorizer newCategorizer(int minClusters) {
        DigestCategorizer categorizer = new DigestCategorizer();
        categorizer.categoryMinClusters = minClusters;
        return categorizer;
    }

    private static Post post(String uid, String title, List<String> tags, int windowPosts) {
        return new Post(
                UUID.randomUUID(), uid, UUID.randomUUID(), "TestSrc",
                title, "https://example.com/" + uid, "body",
                Instant.now(), tags, List.of("unknown"),
                null, null, "rss", windowPosts);
    }

    private static String two(int i) {
        return String.format("%02d", i);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0;
                i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    /** Recording roll-up stub: counts calls, canned synthesis or forced failure. */
    private static final class RecordingCategoryRollupGenerator extends CategoryRollupGenerator {
        private String response = "category roll-up";
        private boolean failEverywhere;
        private int calls;

        void setResponse(String text) { this.response = text; }
        void setFailEverywhere(boolean fail) { this.failEverywhere = fail; }
        int callCount() { return calls; }

        @Override
        public Optional<String> generateRollup(List<Cluster> categoryClusters,
                                                String sectionTag, String langCode) {
            calls++;
            return failEverywhere ? Optional.empty() : Optional.of(response);
        }
    }

    /**
     * Recording prose stub: echoes the cluster's first post title into the
     * prose (content pairing), records the generate() input uids in call
     * order, and can degrade every cluster (prose bytes are a deliberate
     * lie — the renderer derives degraded prose from the cluster, M1-697).
     */
    private static final class RecordingSummaryProseGenerator extends SummaryProseGenerator {
        private final AtomicReference<String> lastLang = new AtomicReference<>();
        private String responseText = "default summary";
        private boolean echoTitle;
        private boolean degradedMode;
        private int calls;
        private List<String> lastInputUids = List.of();

        void setResponseText(String text) { this.responseText = text; }
        void setEchoTitle(boolean echo) { this.echoTitle = echo; }
        void setDegradedMode(boolean degraded) { this.degradedMode = degraded; }
        String lastLanguage() { return lastLang.get(); }
        int callCount() { return calls; }
        List<String> lastInputUids() { return lastInputUids; }

        @Override
        public List<ClusterProse> generate(List<Cluster> clusters, String scopeLanguage) {
            lastLang.set(scopeLanguage);
            List<String> uids = new ArrayList<>(clusters.size());
            List<ClusterProse> out = new ArrayList<>(clusters.size());
            for (Cluster c : clusters) {
                calls++;
                uids.add(c.posts().getFirst().uid());
                out.add(new ClusterProse(c,
                        degradedMode ? "INJECTED degraded lie bytes"
                                : echoTitle ? responseText + " " + c.posts().getFirst().title()
                                : responseText,
                        degradedMode));
            }
            lastInputUids = List.copyOf(uids);
            return out;
        }
    }
}
