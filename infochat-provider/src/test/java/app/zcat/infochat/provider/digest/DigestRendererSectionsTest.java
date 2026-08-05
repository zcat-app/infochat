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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static app.zcat.infochat.provider.testsupport.TranslationFixtures.newEnShortCircuitPipeline;
import static app.zcat.infochat.provider.testsupport.TranslationFixtures.newRealBundleLoader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link DigestRenderer#renderSections} unit surface: section order
 * matches D62, the closing affordance is folded into the LAST section's
 * text only, the M1-721 section cap shapes the digest only, (M1-732)
 * the {@code groups.digest_mode} body shapes — brief/normal hybrid
 * (true-count header + roll-up + headlines + footer) versus full's
 * per-cluster prose, and (M1-725) the lead section — the top
 * {@code lead-size} clusters by prominence across the whole digest,
 * rendered first with full prose and removed from their home sections.
 * Kept as a separate file so the pre-existing
 * {@link DigestRendererTest} stays the pipeline-wiring proof.
 */
class DigestRendererSectionsTest {

    /**
     * The broadcast scope every render in this class runs under (M1-756).
     * Every one of them renders an {@code "en"} scope, so the display-hit
     * leg short-circuits and this id only ever names a cache partition
     * nothing writes to — the section-byte pins below are unaffected by it.
     */
    private static final UUID GROUP_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

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
        // M1-767: the renderer's system-budget draw probes this cache at
        // appendClusterProse (to skip cache-hit translations), so the
        // field is now read on non-en scopes — wired here as
        // DigestRendererTest does.
        renderer.translationCache = new TranslationCache();
        renderer.digestCategorizer = newCategorizer(3);
        renderer.bundleLoader = bundleLoader;
        renderer.categoryItemCap = 12;
        renderer.categoryHeadlineCount = 5;
    }

    @Test
    void sectionsMatchD62OrderCountDescAlphaTiesOtherLast() {
        noLead(); // the D62 section order is this test's subject; the lead's own order tests are below
        proseGenerator.setResponseText("story prose");
        // EmptyEdgeSource → one singleton cluster per post, so tag counts
        // are: security=4, ai=3, crypto=3 (all qualify at threshold 3), and
        // one untagged cluster lands in Other.
        List<Post> posts = List.of(
                post("s1", "Sec 1", List.of("security")),
                post("s2", "Sec 2", List.of("security")),
                post("s3", "Sec 3", List.of("security")),
                post("s4", "Sec 4", List.of("security")),
                post("a1", "AI 1", List.of("ai")),
                post("a2", "AI 2", List.of("ai")),
                post("a3", "AI 3", List.of("ai")),
                post("c1", "Crypto 1", List.of("crypto")),
                post("c2", "Crypto 2", List.of("crypto")),
                post("c3", "Crypto 3", List.of("crypto")),
                post("u1", "Untagged", List.of()));

        List<RenderedSection> sections = renderer.renderSections(posts, "en", DigestMode.FULL, GROUP_ID);

        // Arrays.asList, not List.of: List.of is null-hostile and the Other
        // bucket's tag is null by construction (DigestCategorizer.CategorySection).
        assertEquals(Arrays.asList("security", "ai", "crypto", null),
                sections.stream().map(RenderedSection::tag).toList(),
                "D62 order: count desc (security=4 first), alpha tie (ai before crypto), Other last");
    }

    @Test
    void affordanceFoldedIntoLastSectionOnly() {
        proseGenerator.setResponseText("affordance test prose");
        List<Post> posts = List.of(
                post("s1", "Sec 1", List.of("security")),
                post("s2", "Sec 2", List.of("security")),
                post("s3", "Sec 3", List.of("security")),
                post("a1", "AI 1", List.of("ai")),
                post("a2", "AI 2", List.of("ai")),
                post("a3", "AI 3", List.of("ai")));

        List<RenderedSection> sections = renderer.renderSections(posts, "en", DigestMode.FULL, GROUP_ID);

        String affordance =
                "@mention me to go deeper on any story, or ask about a topic you don't see here.";
        // Every section except the last must NOT contain the affordance.
        for (int i = 0; i < sections.size() - 1; i++) {
            assertFalse(sections.get(i).text().contains(affordance),
                    "section " + i + " must not carry the closing affordance: " + sections.get(i).text());
        }
        // The last section's text ends with the affordance — folded inside
        // the section, not appended by the delivery path.
        String lastText = sections.getLast().text();
        assertTrue(lastText.contains(affordance),
                "last section carries the affordance: " + lastText);
        assertTrue(lastText.endsWith(affordance),
                "last section ends with the affordance (folded into the section text): " + lastText);
    }

    // ----- section cap (M1-721) ---------------------------------------------

    @Test
    void cappedDigestAppendsOneOverflowLineOnTheLastSection() {
        noLead(); // the section-cap shape is the subject; a lead would consume 3 clusters and shift the counts
        renderer.digestCategorizer = newCategorizer(3, 8);
        proseGenerator.setResponseText("capped prose");

        List<RenderedSection> sections =
                renderer.renderSections(twelveCategoryPosts(), "en", DigestMode.FULL, GROUP_ID);

        assertEquals(8, sections.size(), "12 categories capped to 8 sections");
        String overflow = "4 more categories are not shown";
        long carrying = sections.stream().filter(s -> s.text().contains(overflow)).count();
        assertEquals(1, carrying,
                "exactly one overflow line across the whole digest: "
                        + sections.stream().map(RenderedSection::text).toList());
        assertTrue(sections.getLast().text().contains(overflow),
                "the overflow line rides the last section: " + sections.getLast().text());
    }

    @Test
    void digestUnderTheCapAppendsNoOverflowLine() {
        renderer.digestCategorizer = newCategorizer(3, 8);
        proseGenerator.setResponseText("uncapped prose");
        List<Post> posts = List.of(
                post("s1", "Sec 1", List.of("security")),
                post("s2", "Sec 2", List.of("security")),
                post("s3", "Sec 3", List.of("security")));

        List<RenderedSection> sections = renderer.renderSections(posts, "en", DigestMode.FULL, GROUP_ID);

        assertEquals(1, sections.size());
        assertFalse(sections.getFirst().text().contains("not shown"),
                "no overflow line under the cap: " + sections.getFirst().text());
    }

    @Test
    void proseCoversOnlySectionsThatSurviveTheCap() {
        noLead(); // pins body prose against the cap; the lead's own call counts are pinned below
        renderer.digestCategorizer = newCategorizer(3, 8);
        proseGenerator.setResponseText("surviving prose");

        renderer.renderSections(twelveCategoryPosts(), "en", DigestMode.FULL, GROUP_ID);

        // D62 already commits that capped-out CLUSTERS waste no LLM calls;
        // the section cap extends the same property to whole sections. A
        // dropped section that still paid for prose would be pure waste.
        assertEquals(8 * 3, proseGenerator.callCount(),
                "per-cluster prose runs for the 8 surviving sections' 3 clusters each, and no others");
    }

    @Test
    void summaryEntryPointIsNotSectionCapped() {
        // The cap is a digest-broadcast bound. /summary is an interactive
        // pull the reader asked for and shares the same categorizer, so a
        // cap applied inside categorize() would silently reach it.
        renderer.digestCategorizer = newCategorizer(3, 8);
        List<Cluster> clusters = new ClusterTraversal(new EmptyEdgeSource(), 3)
                .cluster(twelveCategoryPosts());
        List<ClusterProse> proseList = clusters.stream()
                .map(c -> new ClusterProse(c, "summary prose", false))
                .toList();

        List<RenderedSection> sections = renderer.renderSummarySections(proseList, "en");

        assertEquals(12, sections.size(),
                "/summary renders all 12 categories: "
                        + sections.stream().map(RenderedSection::tag).toList());
    }

    // ----- digest_mode body shapes (M1-732) ---------------------------------

    @Test
    void normalModeRendersTrueCountHeaderRollupHeadlinesAndFooter() {
        noLead(); // the hybrid body shape is the subject (the "without a lead" half); the with-lead half is below
        // The hybrid body: UPPERCASE header with the section's TRUE cluster
        // count, the roll-up synthesis, up to category-headline-count (5)
        // bare headlines (title + URL, NO prose), and the category footer.
        // 13 clusters pin the count against the 5 headlines shown.
        rollupGenerator.setResponse("thirteen-story synthesis");
        List<Post> posts = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            posts.add(post("sec-" + i, "Story sec " + i, List.of("security")));
        }

        List<RenderedSection> sections =
                renderer.renderSections(posts, "en", DigestMode.NORMAL, GROUP_ID);

        assertEquals(1, sections.size());
        String text = sections.getFirst().text();
        assertTrue(text.startsWith("SECURITY NEWS — 13 STORIES"),
                "header carries the TRUE cluster count, not the 5 headlines shown: " + text);
        assertTrue(text.contains("thirteen-story synthesis"),
                "the roll-up synthesis renders: " + text);
        for (int i = 0; i < 5; i++) {
            assertTrue(text.contains("· Story sec " + i + "\nhttps://example.com/sec-" + i),
                    "headline " + i + " renders as bare title then URL on its own line: " + text);
        }
        assertFalse(text.contains("Story sec 5"),
                "the 6th headline is capped off by category-headline-count: " + text);
        assertTrue(text.contains("/summary security to expand this category"),
                "the category footer closes the section: " + text);
        assertEquals(0, proseGenerator.callCount(),
                "normal renders NO per-cluster prose");
    }

    @Test
    void briefModeDropsTheHeadlines() {
        rollupGenerator.setResponse("brief synthesis");
        List<Post> posts = List.of(
                post("s1", "Sec 1", List.of("security")),
                post("s2", "Sec 2", List.of("security")),
                post("s3", "Sec 3", List.of("security")));

        List<RenderedSection> sections =
                renderer.renderSections(posts, "en", DigestMode.BRIEF, GROUP_ID);

        String text = sections.getFirst().text();
        assertTrue(text.startsWith("SECURITY NEWS — 3 STORIES"),
                "brief keeps the true-count header: " + text);
        assertTrue(text.contains("brief synthesis"), "the roll-up renders: " + text);
        assertFalse(text.contains("· "), "brief renders NO headlines: " + text);
        assertTrue(text.contains("/summary security to expand this category"),
                "the footer stays: " + text);
        assertEquals(0, proseGenerator.callCount(),
                "brief renders NO per-cluster prose");
    }

    @Test
    void fullModeKeepsPerClusterProseAndThePlainHeader() {
        proseGenerator.setResponseText("full prose");
        List<Post> posts = List.of(
                post("s1", "Sec 1", List.of("security")),
                post("s2", "Sec 2", List.of("security")),
                post("s3", "Sec 3", List.of("security")));

        List<RenderedSection> sections =
                renderer.renderSections(posts, "en", DigestMode.FULL, GROUP_ID);

        String text = sections.getFirst().text();
        assertTrue(text.startsWith("SECURITY NEWS\n"),
                "full keeps the pre-M1-732 header bytes: " + text);
        assertEquals(3, text.split("full prose", -1).length - 1,
                "one prose paragraph per cluster: " + text);
        assertEquals(0, rollupGenerator.callCount(), "full makes no roll-up calls");
    }

    // ----- M1-762: header case follows the scope language --------------------

    @Test
    void turkishScopeCasesSectionHeadersWithTheScopeLanguage() {
        // Turkish uppercases i to İ; Locale.ROOT does not. The pre-M1-762
        // renderer cased every header under ROOT and so shipped
        // DIĞER HABERLER — a letter the Turkish word does not contain. Only a
        // Turkish-scope assertion separates the two implementations: en, cs,
        // es and ru all case identically under ROOT and under their own
        // locale, which is exactly why every other test in this class stayed
        // green while the defect was live.
        //
        // The lead header (reply.digest.lead.header) is cased by the same
        // change but is NOT pinned here: its tr value "öne çıkan haberler"
        // carries no dotted i, so it upper-cases identically either way and an
        // assertion on it would be vacuous.
        noLead();
        proseGenerator.setResponseText("prose");
        rollupGenerator.setResponse("synthesis");
        List<Post> posts = turkishScopeFixture();

        String full = sectionByTag(
                renderer.renderSections(posts, "tr", DigestMode.FULL, GROUP_ID), null).text();
        assertTrue(full.startsWith("DİĞER HABERLER"),
                "FULL cases reply.digest.category.other under the tr scope: " + full);

        String normal = sectionByTag(
                renderer.renderSections(posts, "tr", DigestMode.NORMAL, GROUP_ID), null).text();
        assertTrue(normal.startsWith("DİĞER HABERLER — 1 HABER"),
                "NORMAL cases reply.digest.category.other_count under the tr scope: " + normal);
    }

    @Test
    void turkishScopeLeavesTheInterpolatedCategoryTagEnglishCased() {
        // The trap a blanket ROOT → scope-locale swap walks into: the tag is
        // interpolated into the header BEFORE the case pass, so casing the
        // composed string under tr would turn the controlled-vocabulary tag
        // "ai" into "Aİ" — trading one wrong header for another. Tags are an
        // English controlled vocabulary (D38), so the prose cases under tr
        // while the tag stays ROOT-cased: AI HABERLERİ, never Aİ HABERLERİ.
        noLead();
        proseGenerator.setResponseText("prose");
        rollupGenerator.setResponse("synthesis");
        List<Post> posts = turkishScopeFixture();

        String full = sectionByTag(
                renderer.renderSections(posts, "tr", DigestMode.FULL, GROUP_ID), "ai").text();
        assertTrue(full.startsWith("AI HABERLERİ"),
                "the tag stays English-cased while the translated prose cases under tr: " + full);
        assertFalse(full.contains("Aİ"),
                "Turkish casing must not reach the tag: " + full);

        String normal = sectionByTag(
                renderer.renderSections(posts, "tr", DigestMode.NORMAL, GROUP_ID), "ai").text();
        assertTrue(normal.startsWith("AI HABERLERİ — 3 HABER"),
                "the count header keeps the same tag/prose split: " + normal);
        assertFalse(normal.contains("Aİ"),
                "Turkish casing must not reach the tag in the count header: " + normal);
    }

    /**
     * Three {@code ai} clusters (a qualifying category at min-clusters 3) plus
     * one untagged cluster for the Other bucket, so a single render exercises
     * both the tagged and the untagged header form. The posts carry no source
     * language, so M1-756's display-hit translation never engages under the
     * {@code tr} scope these two tests render.
     */
    private static List<Post> turkishScopeFixture() {
        return List.of(
                post("ai-1", "AI 1", List.of("ai")),
                post("ai-2", "AI 2", List.of("ai")),
                post("ai-3", "AI 3", List.of("ai")),
                post("u1", "Untagged", List.of()));
    }

    // ----- M1-724: prominence ordering within sections --------------------

    /**
     * Prominence reorders clusters WITHIN a section but leaves every
     * section's membership SET identical: the reorder is a within-section
     * sort, never a move between sections — which is also what makes a
     * high-scoring cluster unable to starve a small category.
     */
    @Test
    void prominenceReorderingLeavesSectionMembershipIdentical() {
        noLead(); // within-section reorder is the subject; lead extraction is covered below
        List<RenderedSection> sections =
                renderer.renderSections(prominenceFixture(), "en", DigestMode.NORMAL, GROUP_ID);

        assertEquals(3, sections.size());
        assertEquals(Set.of("AI 1", "AI 2", "AI 3"), headlineTitles(sectionByTag(sections, "ai")));
        assertEquals(Set.of("Sec 1", "Sec 2", "Sec 3"),
                headlineTitles(sectionByTag(sections, "security")));
        assertEquals(Set.of("Untagged"), headlineTitles(sectionByTag(sections, null)));
    }

    /**
     * The head of each section is its highest-scoring cluster, NOT its
     * newest: the input is recency-ordered (s1/a1 first), but the
     * quiet-source clusters (1 window post vs 300) win the scarcity term
     * and lead their sections.
     */
    @Test
    void sectionHeadIsTheHighestScoringClusterNotTheNewest() {
        noLead(); // within-section head selection is the subject; the lead's cross-digest selection is below
        List<RenderedSection> sections =
                renderer.renderSections(prominenceFixture(), "en", DigestMode.NORMAL, GROUP_ID);

        String ai = sectionByTag(sections, "ai").text();
        String security = sectionByTag(sections, "security").text();
        assertTrue(firstHeadline(ai).contains("AI 3"),
                "ai head is the high scorer, not the newest: " + ai);
        assertTrue(firstHeadline(security).contains("Sec 3"),
                "security head is the high scorer, not the newest: " + security);
        // Section ORDER is untouched: D62 (count desc, alphabetical ties,
        // Other last) — ai before security, Other last.
        assertEquals("ai", sections.get(0).tag());
        assertEquals("security", sections.get(1).tag());
        assertNull(sections.get(2).tag());
    }

    /**
     * A section whose clusters all score low still renders its own head:
     * the Other bucket's lone cluster scores bottom on every term, yet the
     * section ships with its headline — the score never decides WHETHER a
     * section renders, only the order inside it.
     */
    @Test
    void lowScoringSectionStillRendersItsOwnHead() {
        noLead(); // the no-starvation property is the subject; with-lead section behavior is covered below
        List<RenderedSection> sections =
                renderer.renderSections(prominenceFixture(), "en", DigestMode.NORMAL, GROUP_ID);

        RenderedSection other = sectionByTag(sections, null);
        assertTrue(other.text().contains("Untagged"),
                "the zero-signal Other section still renders its head: " + other.text());
    }

    /**
     * Six tagged posts (two categories × three, qualifying at threshold 3)
     * plus one untagged post. Input order is recency (newest first); the
     * THIRD post of each category carries the quiet-source signal
     * (1 window post vs 300 everywhere else), so prominence pulls the
     * oldest cluster to the head of each section. Kinds and social columns
     * are uniformly NULL, so corroboration ties within each section and
     * scarcity alone separates the head.
     */
    private static List<Post> prominenceFixture() {
        return List.of(
                post("s1", "Sec 1", List.of("security"), 300),
                post("s2", "Sec 2", List.of("security"), 300),
                post("s3", "Sec 3", List.of("security"), 1),
                post("a1", "AI 1", List.of("ai"), 300),
                post("a2", "AI 2", List.of("ai"), 300),
                post("a3", "AI 3", List.of("ai"), 1),
                post("u1", "Untagged", List.of(), 300));
    }

    private static RenderedSection sectionByTag(List<RenderedSection> sections, String tag) {
        return sections.stream()
                .filter(s -> Objects.equals(s.tag(), tag))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no section for tag " + tag));
    }

    private static String firstHeadline(String sectionText) {
        return sectionText.lines()
                .filter(line -> line.startsWith("· "))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no headline in: " + sectionText));
    }

    private static Set<String> headlineTitles(RenderedSection section) {
        Set<String> titles = new java.util.HashSet<>();
        section.text().lines()
                .filter(line -> line.startsWith("· "))
                .forEach(line -> titles.add(line.substring(2).split("  ")[0].trim()));
        return titles;
    }

    // ----- the lead section (M1-725) ----------------------------------------

    /**
     * The lead holds the top {@code lead-size} (default 3) clusters by
     * prominence across the WHOLE digest — not the newest three. The
     * fixture is the M1-724 one (recency-ordered input, the THIRD post of
     * each category carries the quiet-source signal), so prominence pulls
     * Sec 3 and AI 3 into the lead ahead of the newest cluster, and the
     * lead's internal order is the prominence total order.
     */
    @Test
    void leadHoldsTopClustersByProminenceAcrossTheWholeDigest() {
        proseGenerator.setEchoTitle(true);
        List<RenderedSection> sections =
                renderer.renderSections(prominenceFixture(), "en", DigestMode.NORMAL, GROUP_ID);

        RenderedSection lead = sections.getFirst();
        assertEquals(DigestRenderer.LEAD_TAG, lead.tag(),
                "the lead is the FIRST section, marked by LEAD_TAG");
        assertTrue(lead.text().startsWith("TOP STORIES"),
                "the lead opens with the localized UPPERCASE header: " + lead.text());
        int sec3 = lead.text().indexOf("Sec 3");
        int ai3 = lead.text().indexOf("AI 3");
        int sec1 = lead.text().indexOf("Sec 1");
        assertTrue(sec3 >= 0 && ai3 > sec3 && sec1 > ai3,
                "lead order is the prominence order (quiet-source winners first, then the "
                        + "input-order tiebreak), not recency: " + lead.text());
        assertEquals(3, proseGenerator.callCount(),
                "lead prose is generated for exactly the lead-size promoted clusters");
    }

    /**
     * No cluster renders twice: the union of lead and section clusters
     * contains no duplicate topicId. Observable proxy at the render
     * surface — every post title appears in EXACTLY ONE section's text
     * (lead prose or category headlines), never two.
     */
    @Test
    void noClusterRendersInBothLeadAndSection() {
        proseGenerator.setEchoTitle(true);
        List<Post> posts = List.of(
                post("s1", "Sec 1", List.of("security")),
                post("a1", "AI 1", List.of("ai")),
                post("s2", "Sec 2", List.of("security")),
                post("a2", "AI 2", List.of("ai")),
                post("s3", "Sec 3", List.of("security")),
                post("a3", "AI 3", List.of("ai")),
                post("s4", "Sec 4", List.of("security")),
                post("a4", "AI 4", List.of("ai")));

        List<RenderedSection> sections = renderer.renderSections(posts, "en", DigestMode.NORMAL, GROUP_ID);

        assertEquals(DigestRenderer.LEAD_TAG, sections.getFirst().tag());
        for (String title : List.of("Sec 1", "AI 1", "Sec 2", "AI 2",
                "Sec 3", "AI 3", "Sec 4", "AI 4")) {
            long carrying = sections.stream().filter(s -> s.text().contains(title)).count();
            assertEquals(1, carrying,
                    title + " renders in exactly one section — never both lead and category: "
                            + sections.stream().map(RenderedSection::text).toList());
        }
    }

    /**
     * The section's story count (M1-732 true-count header) reflects the
     * removal: a 13-cluster section that loses one to the lead reports 12.
     */
    @Test
    void sectionCountDropsByTheClustersPromotedToTheLead() {
        renderer.leadSize = 1;
        proseGenerator.setEchoTitle(true);
        rollupGenerator.setResponse("roll-up");
        List<Post> posts = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            posts.add(post("sec-" + i, "Story sec " + i, List.of("security")));
        }

        List<RenderedSection> sections = renderer.renderSections(posts, "en", DigestMode.NORMAL, GROUP_ID);

        assertEquals(DigestRenderer.LEAD_TAG, sections.getFirst().tag());
        assertTrue(sections.getFirst().text().contains("Story sec 0"),
                "the lead holds the promoted cluster's prose: " + sections.getFirst().text());
        RenderedSection body = sections.get(1);
        assertTrue(body.text().startsWith("SECURITY NEWS — 12 STORIES"),
                "the count drops with the promoted cluster — 12, not 13: " + body.text());
    }

    /**
     * The lead-minimum boundary in both directions at the DEFAULT minimum
     * (6): six clusters render a lead, five render none — a header over
     * nearly the whole digest says nothing and costs an extra message
     * under D63.
     */
    @Test
    void leadRendersAtTheMinimumAndNotBelowIt() {
        List<Post> six = List.of(
                post("s1", "Sec 1", List.of("security")),
                post("s2", "Sec 2", List.of("security")),
                post("s3", "Sec 3", List.of("security")),
                post("a1", "AI 1", List.of("ai")),
                post("a2", "AI 2", List.of("ai")),
                post("a3", "AI 3", List.of("ai")));

        List<RenderedSection> withLead = renderer.renderSections(six, "en", DigestMode.NORMAL, GROUP_ID);

        assertEquals(DigestRenderer.LEAD_TAG, withLead.getFirst().tag(),
                "six clusters >= the default lead-minimum: the lead renders");
        int proseAtMinimum = proseGenerator.callCount();

        List<Post> five = six.subList(0, 5);
        List<RenderedSection> withoutLead =
                renderer.renderSections(five, "en", DigestMode.NORMAL, GROUP_ID);

        assertTrue(withoutLead.stream().noneMatch(s -> DigestRenderer.LEAD_TAG.equals(s.tag())),
                "five clusters < the default lead-minimum: NO lead section at all");
        assertEquals(proseAtMinimum, proseGenerator.callCount(),
                "below the minimum no lead prose is paid for");
    }

    /**
     * A category the lead drops below the D62 qualifying threshold folds
     * into Other through the categorizer's EXISTING second pass: the
     * 3-cluster crypto category loses its quiet-source cluster to the
     * lead, and the remaining two render under Other — no dedicated code
     * path, no empty crypto section.
     */
    @Test
    void categoryFoldsIntoOtherAfterTheLeadGutsIt() {
        renderer.leadSize = 1;
        proseGenerator.setEchoTitle(true);
        rollupGenerator.setResponse("roll-up");
        List<Post> posts = List.of(
                post("s1", "Sec 1", List.of("security"), 300),
                post("s2", "Sec 2", List.of("security"), 300),
                post("s3", "Sec 3", List.of("security"), 300),
                post("c1", "Crypto 1", List.of("crypto"), 1),
                post("c2", "Crypto 2", List.of("crypto"), 300),
                post("c3", "Crypto 3", List.of("crypto"), 300));

        List<RenderedSection> sections = renderer.renderSections(posts, "en", DigestMode.NORMAL, GROUP_ID);

        assertEquals(DigestRenderer.LEAD_TAG, sections.getFirst().tag());
        assertTrue(sections.getFirst().text().contains("Crypto 1"),
                "the quiet-source crypto cluster wins the lead: " + sections.getFirst().text());
        assertTrue(sections.stream().noneMatch(s -> "crypto".equals(s.tag())),
                "the gutted crypto category (2 < category-min-clusters) keeps no section");
        RenderedSection other = sectionByTag(sections, null);
        assertTrue(other.text().contains("Crypto 2") && other.text().contains("Crypto 3"),
                "the surviving crypto clusters fold into Other: " + other.text());
        assertEquals(Set.of("Sec 1", "Sec 2", "Sec 3"),
                headlineTitles(sectionByTag(sections, "security")),
                "security is untouched — the lead took nothing from it");
    }

    /**
     * NORMAL mode: the lead renders full per-cluster prose (the render
     * the hybrid-body categories no longer do) while the categories keep
     * their roll-up + bare headlines. The lead carries NO headline lines
     * and NO footer.
     */
    @Test
    void leadRendersFullProseWhileCategoriesRenderHeadlines() {
        proseGenerator.setResponseText("lead prose");
        proseGenerator.setEchoTitle(true);
        rollupGenerator.setResponse("roll-up");
        List<Post> posts = List.of(
                post("s1", "Sec 1", List.of("security")),
                post("a1", "AI 1", List.of("ai")),
                post("s2", "Sec 2", List.of("security")),
                post("a2", "AI 2", List.of("ai")),
                post("s3", "Sec 3", List.of("security")),
                post("a3", "AI 3", List.of("ai")),
                post("s4", "Sec 4", List.of("security")),
                post("a4", "AI 4", List.of("ai")),
                post("s5", "Sec 5", List.of("security")),
                post("a5", "AI 5", List.of("ai")));

        List<RenderedSection> sections = renderer.renderSections(posts, "en", DigestMode.NORMAL, GROUP_ID);

        RenderedSection lead = sections.getFirst();
        assertEquals(DigestRenderer.LEAD_TAG, lead.tag());
        assertTrue(lead.text().contains("lead prose Sec 1")
                        && lead.text().contains("lead prose AI 1")
                        && lead.text().contains("lead prose Sec 2"),
                "the lead renders full prose for its clusters: " + lead.text());
        assertTrue(lead.text().lines().noneMatch(line -> line.startsWith("· ")),
                "the lead renders prose, NOT headlines: " + lead.text());
        for (RenderedSection section : sections.subList(1, sections.size())) {
            assertTrue(section.text().contains("· "),
                    "category sections keep their bare headlines: " + section.text());
            assertFalse(section.text().contains("lead prose"),
                    "category sections render NO per-cluster prose: " + section.text());
        }
    }

    /**
     * Message ordering: the lead is the FIRST section (its own first
     * message under M1-734's batched delivery — DigestDeliveryTest pins
     * the split) and the closing affordance appears exactly once across
     * the digest, on the LAST section — never on the lead.
     */
    @Test
    void leadIsFirstAndTheAffordanceStaysLast() {
        rollupGenerator.setResponse("roll-up");
        List<Post> posts = List.of(
                post("s1", "Sec 1", List.of("security")),
                post("a1", "AI 1", List.of("ai")),
                post("s2", "Sec 2", List.of("security")),
                post("a2", "AI 2", List.of("ai")),
                post("s3", "Sec 3", List.of("security")),
                post("a3", "AI 3", List.of("ai")));

        List<RenderedSection> sections = renderer.renderSections(posts, "en", DigestMode.NORMAL, GROUP_ID);

        String affordance =
                "@mention me to go deeper on any story, or ask about a topic you don't see here.";
        assertEquals(DigestRenderer.LEAD_TAG, sections.getFirst().tag(),
                "the lead is the first section — its own first message");
        assertFalse(sections.getFirst().text().contains(affordance),
                "the lead never carries the closing affordance");
        long occurrences = sections.stream()
                .mapToLong(s -> s.text().split(java.util.regex.Pattern.quote(affordance), -1).length - 1)
                .sum();
        assertEquals(1, occurrences,
                "the affordance appears exactly once across the whole digest: "
                        + sections.stream().map(RenderedSection::text).toList());
        assertTrue(sections.getLast().text().endsWith(affordance),
                "the affordance stays on the LAST section of the digest");
    }

    /**
     * brief renders no lead at all: a few lines per category with a prose
     * lead above would dominate the thing it introduces — and no lead
     * prose is paid for.
     */
    @Test
    void briefModeRendersNoLead() {
        rollupGenerator.setResponse("brief synthesis");
        List<Post> posts = List.of(
                post("s1", "Sec 1", List.of("security")),
                post("a1", "AI 1", List.of("ai")),
                post("s2", "Sec 2", List.of("security")),
                post("a2", "AI 2", List.of("ai")),
                post("s3", "Sec 3", List.of("security")),
                post("a3", "AI 3", List.of("ai")),
                post("s4", "Sec 4", List.of("security")),
                post("a4", "AI 4", List.of("ai")));

        List<RenderedSection> sections = renderer.renderSections(posts, "en", DigestMode.BRIEF, GROUP_ID);

        assertTrue(sections.stream().noneMatch(s -> DigestRenderer.LEAD_TAG.equals(s.tag())),
                "brief renders NO lead section, even above the minimum");
        assertEquals(0, proseGenerator.callCount(),
                "brief pays NO prose — no lead, no per-cluster body");
    }

    /**
     * LLM call accounting (NORMAL): exactly {@code lead-size} prose calls
     * for the lead, on top of M1-732's one roll-up per surviving section —
     * and nothing else.
     */
    @Test
    void llmCallsAreLeadSizePlusOneRollupPerSection() {
        proseGenerator.setResponseText("lead prose");
        rollupGenerator.setResponse("roll-up");
        List<Post> posts = List.of(
                post("s1", "Sec 1", List.of("security")),
                post("a1", "AI 1", List.of("ai")),
                post("s2", "Sec 2", List.of("security")),
                post("a2", "AI 2", List.of("ai")),
                post("s3", "Sec 3", List.of("security")),
                post("a3", "AI 3", List.of("ai")),
                post("s4", "Sec 4", List.of("security")),
                post("a4", "AI 4", List.of("ai")),
                post("s5", "Sec 5", List.of("security")),
                post("a5", "AI 5", List.of("ai")));

        renderer.renderSections(posts, "en", DigestMode.NORMAL, GROUP_ID);

        assertEquals(3, proseGenerator.callCount(),
                "lead prose runs for exactly the lead-size promoted clusters, no others");
        assertEquals(2, rollupGenerator.callCount(),
                "one roll-up per surviving section (ai 4, security 3 after the removal)");
    }

    /**
     * FULL mode with a lead: the lead's 3 prose calls plus every surviving
     * body cluster's — total calls equal the total cluster count, and no
     * cluster renders twice.
     */
    @Test
    void fullModeRendersLeadAndBodyProseWithoutDuplication() {
        proseGenerator.setEchoTitle(true);
        List<Post> posts = List.of(
                post("s1", "Sec 1", List.of("security")),
                post("a1", "AI 1", List.of("ai")),
                post("s2", "Sec 2", List.of("security")),
                post("a2", "AI 2", List.of("ai")),
                post("s3", "Sec 3", List.of("security")),
                post("a3", "AI 3", List.of("ai")),
                post("s4", "Sec 4", List.of("security")),
                post("a4", "AI 4", List.of("ai")));

        List<RenderedSection> sections = renderer.renderSections(posts, "en", DigestMode.FULL, GROUP_ID);

        assertEquals(DigestRenderer.LEAD_TAG, sections.getFirst().tag(),
                "full renders the lead first too");
        assertEquals(8, proseGenerator.callCount(),
                "lead (3) + every surviving body cluster (5) — every cluster exactly once");
        for (String title : List.of("Sec 1", "AI 1", "Sec 2", "AI 2",
                "Sec 3", "AI 3", "Sec 4", "AI 4")) {
            long carrying = sections.stream().filter(s -> s.text().contains(title)).count();
            assertEquals(1, carrying, title + " renders exactly once in full mode too");
        }
    }

    // ----- helpers ----------------------------------------------------------

    /**
     * Disable the lead for a test whose subject predates M1-725: the
     * default-on lead (3 of any >= 6-cluster non-brief digest) would
     * otherwise reshape these fixtures. The lead's own behavior — and the
     * with-lead half of the body assertions — is pinned in the M1-725
     * block above. Field-init keeps the production default; this is a
     * test-local override, the newCategorizer(3, 8) idiom.
     */
    private void noLead() {
        renderer.leadMinimum = Integer.MAX_VALUE;
    }

    /**
     * Twelve qualifying categories {@code cat00}..{@code cat11} of exactly 3
     * clusters each (EmptyEdgeSource → one singleton cluster per post). Equal
     * sizes mean D62's alphabetical tie-break fixes the order, so which
     * sections the cap keeps is deterministic.
     */
    private static List<Post> twelveCategoryPosts() {
        List<Post> posts = new ArrayList<>();
        for (int categoryIndex = 0; categoryIndex < 12; categoryIndex++) {
            String tag = String.format("cat%02d", categoryIndex);
            for (int i = 0; i < 3; i++) {
                posts.add(post(tag + "-" + i, "Story " + tag + " " + i, List.of(tag)));
            }
        }
        return posts;
    }

    private static DigestCategorizer newCategorizer(int minClusters) {
        DigestCategorizer categorizer = new DigestCategorizer();
        categorizer.categoryMinClusters = minClusters;
        return categorizer;
    }

    private static DigestCategorizer newCategorizer(int minClusters, int maxCategories) {
        DigestCategorizer categorizer = newCategorizer(minClusters);
        categorizer.maxCategories = maxCategories;
        return categorizer;
    }

    private static Post post(String uid, String title, List<String> tags) {
        return post(uid, title, tags, List.of("unknown"), "rss", null, null, null);
    }

    /** M1-724: a post with the prominence scarcity signal populated. */
    private static Post post(String uid, String title, List<String> tags, int windowPosts) {
        return post(uid, title, tags, List.of("unknown"), "rss", null, null, windowPosts);
    }

    private static Post post(String uid, String title, List<String> tags,
                             List<String> classification, String kind,
                             Integer reposts, Integer likes, Integer windowPosts) {
        return new Post(
                UUID.randomUUID(), uid, UUID.randomUUID(), "TestSrc",
                title, "https://example.com/" + uid, "body",
                Instant.now(), tags, classification, reposts, likes, kind, windowPosts);
    }

    /** Recording {@link CategoryRollupGenerator}: counts calls and returns a canned synthesis. */
    private static final class RecordingCategoryRollupGenerator extends CategoryRollupGenerator {
        private String response = "category roll-up";
        private int calls;

        void setResponse(String text) { this.response = text; }
        int callCount() { return calls; }

        @Override
        public Optional<String> generateRollup(List<Cluster> categoryClusters,
                                               String sectionTag, String langCode) {
            calls++;
            return Optional.of(response);
        }
    }

    /**
     * Recording subclass: returns canned prose for each cluster and tracks
     * the language code and call count. Echo mode appends the cluster's
     * first post title to the canned text, so a test can tell WHICH
     * clusters were promoted from the rendered bytes (the M1-725
     * no-duplicate and lead-membership assertions).
     */
    private static final class RecordingSummaryProseGenerator extends SummaryProseGenerator {
        private final AtomicReference<String> lastLang = new AtomicReference<>();
        private String responseText = "default summary";
        private boolean echoTitle;
        private int calls;

        void setResponseText(String text) { this.responseText = text; }
        void setEchoTitle(boolean echo) { this.echoTitle = echo; }
        String lastLanguage() { return lastLang.get(); }
        int callCount() { return calls; }

        @Override
        public List<ClusterProse> generate(List<Cluster> clusters, String scopeLanguage) {
            lastLang.set(scopeLanguage);
            List<ClusterProse> out = new ArrayList<>(clusters.size());
            for (Cluster c : clusters) {
                calls++;
                out.add(new ClusterProse(c,
                        echoTitle ? responseText + " " + c.posts().getFirst().title()
                                : responseText,
                        false));
            }
            return out;
        }
    }
}
