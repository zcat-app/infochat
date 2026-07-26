package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ProgressStage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.InFlightTracker;
import app.zcat.infochat.provider.chat.LlmRateCap;
import app.zcat.infochat.provider.chat.SummaryAnchorRepository;
import app.zcat.infochat.provider.digest.DigestRenderer;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.summary.ClusterTraversal;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Result;
import app.zcat.infochat.provider.summary.EmptyEdgeSource;
import app.zcat.infochat.provider.summary.PostReferenceEdgeSource;
import app.zcat.infochat.provider.summary.SummaryProseGenerator;
import app.zcat.infochat.provider.summary.SummaryProseGenerator.ClusterProse;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import app.zcat.infochat.provider.translation.TranslationPipeline;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static app.zcat.infochat.provider.testsupport.TranslationFixtures.newEnShortCircuitPipeline;
import static app.zcat.infochat.provider.testsupport.TranslationFixtures.newRealBundleLoader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Handler-tier (plain JUnit, no Quarkus boot) tests for
 * {@link SummaryCommandHandler} per the test-pyramid convention at
 * {@code docs/process/test-pyramid.md} §Handler unit tests.
 *
 * <p>The handler's {@code @Inject} collaborators are stubbed in
 * {@link #buildHandlerWithStubs()}: {@link BundleLoader} (real,
 * loaded by hand), {@link RecordingEligiblePostQuery},
 * {@link RecordingSummaryProseGenerator}, {@link LlmOutputSanitizer}
 * (real), real {@link ClusterTraversal}, {@link InboundContext}
 * (constructed), {@link FixedUserAndLanguageDataSource} for the
 * users-id (and, in group scope, group-id) lookup, plus a real {@link InFlightTracker} and a
 * real {@link LlmRateCap} (M1-183 admission control).
 *
 * <p>Asserted invariants (one {@code @Test} per behavioral branch):
 * <ul>
 *   <li>Name: handler returns the literal {@code "summary"}.</li>
 *   <li>Dispatch: a single {@code /summary} call produces one
 *       {@link OutboundMessage} and exercises {@link EligiblePostQuery}
 *       exactly once.</li>
 *   <li>Empty-eligible-set branches (prose generator NOT invoked):
 *       an empty world (zero non-excluded bootstrap sources AND zero
 *       subscriptions, M1-621) → the no_subscriptions-keyed steer;
 *       non-empty world but empty window → no_posts_yet; the world
 *       count is read only on the empty branch.</li>
 *   <li>Happy path: 3 posts → 3 clusters → 3 prose calls → the
 *       handler self-delivers via the {@link RecordingProgressNotifier}
 *       ({@code handle} returns null), and the body passed to
 *       {@code complete} has three cluster blocks in the documented
 *       structure. Guard / error branches still return a non-null
 *       {@link OutboundMessage}.</li>
 *   <li>LLM-unreachable branch: degraded prose → reply carries the
 *       degraded_notice prefix.</li>
 *   <li>Cap-excess branch: cap_excess_notice prefix interpolated.</li>
 *   <li>Group scope: resolves the caller's users.id and the group's
 *       scope id, then runs the prose generator and writes a
 *       group-scoped personal anchor (scope_kind="group",
 *       scope_id=group id, distinct from the DM anchor key) — the
 *       end-to-end group flow is pinned by {@code SummaryGroupScopeIT}.</li>
 *   <li>Sanitizer: LLM-authored prose containing {@code /grant-admin}
 *       lands as {@code [redacted command]} in the outbound.</li>
 * </ul>
 */
class SummaryCommandHandlerTest {

    private static final String PREFIX = "m1-037h-";

    private SummaryCommandHandler handler;
    private RecordingEligiblePostQuery eligiblePostQuery;
    private RecordingSummaryProseGenerator proseGenerator;
    private RecordingSummaryAnchorRepository anchorRepository;
    private RecordingProgressNotifier progressNotifier;
    private BundleLoader bundleLoader;
    private InFlightTracker tracker;
    private UUID userId;

    @BeforeEach
    void buildHandlerWithStubs() throws Exception {
        bundleLoader = newRealBundleLoader();
        eligiblePostQuery = new RecordingEligiblePostQuery();
        proseGenerator = new RecordingSummaryProseGenerator();
        anchorRepository = new RecordingSummaryAnchorRepository();
        tracker = new InFlightTracker();
        userId = UUID.randomUUID();
        handler = new SummaryCommandHandler();
        handler.bundleLoader = bundleLoader;
        handler.dataSource = new FixedUserAndLanguageDataSource(userId);
        handler.eligiblePostQuery = eligiblePostQuery;
        handler.clusterTraversal = new ClusterTraversal(new EmptyEdgeSource(), 3);
        handler.summaryProseGenerator = proseGenerator;
        LlmOutputSanitizer sanitizer = SanitizerTestDoubles.noAuditSanitizer();
        proseGenerator.degradedSanitizer = sanitizer;
        TranslationPipeline translationPipeline = newEnShortCircuitPipeline(bundleLoader);
        handler.llmOutputSanitizer = sanitizer;
        handler.translationPipeline = translationPipeline;
        // The default (categorized) render path runs inside DigestRenderer,
        // so the renderer must hold THIS test's sanitizer and pipeline —
        // otherwise sanitizerStripsPrivilegedCommandFromLlmAuthoredProse and
        // the cs-scope tests would be asserting against a different
        // collaborator than the one they configure. The cap values mirror the
        // production @ConfigProperty defaults that manual field injection
        // does not apply (same reason summarizerPostCap is set below).
        handler.digestRenderer = DigestRenderer.forSummaryRendering(
                sanitizer, translationPipeline, bundleLoader,
                /* categoryItemCap */ 12, /* categoryMinClusters */ 3);
        handler.summaryAnchorRepository = anchorRepository;
        handler.inFlightTracker = tracker;
        handler.llmRateCap = new LlmRateCap(10);
        // Manual field injection misses @ConfigProperty defaults: an unset
        // summarizerPostCap would be 0 and every window would trip the
        // M1-623 over-cap gate. Mirror the production default.
        handler.summarizerPostCap = 50;
        progressNotifier = new RecordingProgressNotifier();
        handler.progressNotifier = progressNotifier;
        InboundContext context = new InboundContext();
        context.setAdapterName("inmemory");
        // The handler resolves the caller's users.id from the inbound
        // (adapter, contact_id) carried by InboundContext (not from the
        // ScopeRef), so the sender contact id must be set; the stub
        // DataSource returns the fixed userId for any contact id.
        context.setSenderContactId(PREFIX + "caller");
        handler.inboundContext = context;
    }

    @Test
    void serializeClusterMapProducesStableJsonForStandardIds() {
        // U-62: routing the persisted cluster-map JSON through JsonEscaper
        // must leave the bytes for today's t-/p- id shapes byte-identical
        // to the prior raw concatenation (escape() is the identity for
        // ids with no backslash/quote/control characters).
        Cluster cluster = new Cluster("t-1", List.of(
                post("p-1", "Title one", Instant.now()),
                post("p-2", "Title two", Instant.now())));
        String json = SummaryCommandHandler.serializeClusterMap(List.of(cluster));
        assertEquals("[{\"topicId\":\"t-1\",\"postUids\":[\"p-1\",\"p-2\"]}]", json,
                "standard t-/p- ids serialize byte-identically through JsonEscaper");
    }

    @Test
    void handlerNameIsLiteralSummary() {
        assertEquals("summary", handler.name(),
                "name() returns the literal `summary` (router strips the slash)");
        // Sanity: a /summary dispatch through handle() must produce a
        // real reply for the registered name — exercising the dispatch
        // surface alongside the name() assertion keeps the dispatch /
        // name pair coupled.
        eligiblePostQuery.seedNoPosts();
        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "nm"), "/summary");
        assertTrue(reply.text().contains("No posts to summarize"),
                "dispatch through handle() must return the no_posts_yet reply for "
                        + "the bare /summary form. Got: " + reply.text());
    }

    @Test
    void inboundRouterDispatchesSummaryToHandlerExactlyOnce() {
        // No posts seeded → handler still runs once and returns
        // no_posts_yet. The single-dispatch claim is encoded as
        // EligiblePostQuery being queried exactly once.
        eligiblePostQuery.seedNoPosts();

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "disp"), "/summary");

        assertEquals(1, eligiblePostQuery.fetchCallCount(),
                "EligiblePostQuery must be queried exactly once per dispatch");
        assertTrue(reply.text().contains("No posts to summarize"),
                "dispatch with no posts must yield the no_posts_yet reply. Got: " + reply.text());
    }

    @Test
    void emptyWorldProducesSteerReplyWithoutLlmCall() {
        // Empty eligible set AND an empty world — zero non-excluded
        // bootstrap sources and zero subscriptions (M1-621) → the distinct
        // steer reply, NOT the window-blaming no_posts_yet; the handler
        // short-circuits before reaching the prose generator.
        eligiblePostQuery.seedNoPosts();
        eligiblePostQuery.setWorldSourceCount(0);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "nosub"), "/summary");

        assertEquals(bundleLoader.get(BundleKeys.REPLY_SUMMARY_NO_SUBSCRIPTIONS, "en"), reply.text(),
                "empty world → the steer reply. Got: " + reply.text());
        assertEquals(1, eligiblePostQuery.countWorldSourcesCallCount(),
                "the empty branch reads the world count exactly once");
        assertEquals(0, proseGenerator.callCount(),
                "empty-world path must NOT call the LLM");
    }

    @Test
    void emptyEligibleSetWithNonEmptyWorldProducesNoPostsYetReply() {
        // Empty eligible set but the scope's world is non-empty (count > 0 —
        // under D59's implicit bootstrap this is every fresh scope) → the
        // window-blaming no_posts_yet, NOT the steer. Proves the
        // countWorldSources RESULT drives the branch, not merely that the
        // branch exists.
        eligiblePostQuery.seedNoPosts();
        eligiblePostQuery.setWorldSourceCount(2);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "hassub"), "/summary");

        assertEquals(bundleLoader.get(BundleKeys.REPLY_SUMMARY_NO_POSTS_YET, "en"), reply.text(),
                "non-empty-world-but-empty-window → no_posts_yet. Got: " + reply.text());
        assertEquals(0, proseGenerator.callCount(),
                "empty-window path must NOT call the LLM");
    }

    @Test
    void nonEmptySummaryDoesNotReadWorldSourceCount() {
        // The world count is read ONLY on the empty branch (M1-593 shape,
        // M1-621 semantics), so a /summary that returns posts runs no
        // additional query. A non-empty result must never touch
        // countWorldSources.
        eligiblePostQuery.seedPosts(
                List.of(post(PREFIX + "np", "Headline", Instant.now())), /* excludedCount */ 0);
        proseGenerator.setResponseText("Summary prose for the cluster.");

        handler.handle(new ScopeRef.Dm(PREFIX + "haspost"), "/summary");

        assertEquals(0, eligiblePostQuery.countWorldSourcesCallCount(),
                "a non-empty /summary must not read the world count (no extra query)");
    }

    @Test
    void emptyWindowProducesNoPostsYetReplyWithoutLlmCall() {
        // Subscriptions present (modeled by the handler reaching
        // EligiblePostQuery) but no READY posts in the window → empty
        // Result.
        eligiblePostQuery.seedNoPosts();

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "empty"), "/summary");

        assertTrue(reply.text().contains("No posts to summarize"));
        assertEquals(0, proseGenerator.callCount());
    }

    @Test
    void happyPathThreeEligiblePostsYieldsThreeClusterBlocksAndThreeLlmCalls() {
        Instant now = Instant.now();
        List<Post> posts = List.of(
                post(PREFIX + "h1", "Headline A", now.minus(Duration.ofMinutes(1))),
                post(PREFIX + "h2", "Headline B", now.minus(Duration.ofMinutes(2))),
                post(PREFIX + "h3", "Headline C", now.minus(Duration.ofMinutes(3))));
        eligiblePostQuery.seedPosts(posts, /* excludedCount */ 0);
        proseGenerator.setResponseText("Summary prose for the cluster.");

        // Terminal path: handle() self-delivers via the notifier and
        // returns null; the composed body is the argument to complete().
        // --full is what renders the flat per-cluster blocks this test
        // asserts on (M1-694 made the categorized form the default).
        OutboundMessage reply =
                handler.handle(new ScopeRef.Dm(PREFIX + "happy"), "/summary --full");
        assertNull(reply, "terminal /summary self-delivers via the notifier and returns null");

        assertEquals(3, proseGenerator.callCount(), "one LLM call per cluster");
        String body = progressNotifier.completedText();
        assertNotNull(body, "the composed summary must reach the notifier's complete() call");
        int blocks = body.split("\\[topic_id=").length - 1;
        assertEquals(3, blocks, "three cluster blocks in reply. Got: " + body);
        assertTrue(body.contains("Summary prose for the cluster."),
                "LLM-authored prose lands at the summary: slot. Got: " + body);
        assertTrue(body.contains("covered by:"));
        assertTrue(body.contains("score:"));
        assertTrue(body.contains("classification: technical\n"),
                "classification: line reflects the seeded classification (technical), not the tags. Got: " + body);
        assertTrue(body.contains("tags: " + PREFIX + "news\n"),
                "tags: line reflects the seeded tags, distinct from classification. Got: " + body);
        assertTrue(body.contains("Headline A"));

        // M1-699: the anchor's render_form is the typed /retry dispatch axis.
        // --full anchors the flat replay ('flat'); the bare default anchors
        // the categorized replay ('bare').
        assertEquals(1, anchorRepository.writeCount(),
                "the terminal /summary path must write one anchor");
        assertEquals("flat", anchorRepository.lastRenderForm(),
                "a --full /summary must anchor render_form='flat' for /retry dispatch");
    }

    /**
     * The M1-694 default: the same three posts render as one prose paragraph
     * per cluster under a category header, with none of the seven
     * ClusterBlockRenderer fields the --full sibling above asserts on. The
     * three posts share one tag and categoryMinClusters is 3, so the tag
     * qualifies as a category rather than falling into Other.
     */
    @Test
    void defaultSummaryRendersCategorizedFormWithoutClusterBlockFields() {
        Instant now = Instant.now();
        List<Post> posts = List.of(
                post(PREFIX + "g1", "Headline A", now.minus(Duration.ofMinutes(1))),
                post(PREFIX + "g2", "Headline B", now.minus(Duration.ofMinutes(2))),
                post(PREFIX + "g3", "Headline C", now.minus(Duration.ofMinutes(3))));
        eligiblePostQuery.seedPosts(posts, /* excludedCount */ 0);
        proseGenerator.setResponseText("Summary prose for the cluster.");

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "cat"), "/summary");
        assertNull(reply, "terminal /summary self-delivers via the notifier and returns null");

        assertEquals(3, proseGenerator.callCount(),
                "the default form generates prose exactly as the flat form does");
        String body = progressNotifier.completedText();
        assertNotNull(body, "the composed summary must reach the notifier's complete() call");
        assertTrue(body.contains((PREFIX + "news news").toUpperCase(Locale.ROOT)),
                "default form must carry the uppercased category header. Got: " + body);
        assertTrue(body.contains("Summary prose for the cluster."),
                "default form must carry the LLM-authored cluster prose. Got: " + body);
        assertFalse(body.contains("[topic_id="),
                "default form must not emit the flat cluster-block topic id. Got: " + body);
        assertFalse(body.contains("covered by:"),
                "default form must not emit the flat covered-by line. Got: " + body);
        assertFalse(body.contains("score:"),
                "default form must not emit the flat score line. Got: " + body);
        assertFalse(body.contains("classification:"),
                "default form must not emit the flat classification line. Got: " + body);
        assertFalse(body.contains("tags:"),
                "default form must not emit the flat tags line. Got: " + body);
        assertFalse(body.contains("Headline A"),
                "the categorized form renders prose only — no per-post headline. Got: " + body);

        // M1-699: the bare (default) /summary anchors render_form='bare' for
        // /retry dispatch — the typed column counterpart to the 'flat' assertion
        // in the --full sibling above.
        assertEquals(1, anchorRepository.writeCount(),
                "the terminal /summary path must write one anchor");
        assertEquals("bare", anchorRepository.lastRenderForm(),
                "a bare /summary must anchor render_form='bare' for /retry dispatch");
    }

    /**
     * M1-695 acceptance: the default form is delivered as ONE outbound
     * message per category section — the placeholder is finalized with
     * the first section and each remaining section goes out as a fresh
     * send, in section order (assigned-cluster count descending, Other
     * last). Three alpha posts clear categoryMinClusters (3) and form a
     * real category; the two beta posts fold into Other.
     */
    @Test
    void defaultSummaryDeliversOneMessagePerCategorySection() {
        Instant now = Instant.now();
        List<Post> posts = List.of(
                post(PREFIX + "sa1", "Alpha one", now.minus(Duration.ofMinutes(1)),
                        List.of(PREFIX + "alpha")),
                post(PREFIX + "sa2", "Alpha two", now.minus(Duration.ofMinutes(2)),
                        List.of(PREFIX + "alpha")),
                post(PREFIX + "sa3", "Alpha three", now.minus(Duration.ofMinutes(3)),
                        List.of(PREFIX + "alpha")),
                post(PREFIX + "sb1", "Beta one", now.minus(Duration.ofMinutes(4)),
                        List.of(PREFIX + "beta")),
                post(PREFIX + "sb2", "Beta two", now.minus(Duration.ofMinutes(5)),
                        List.of(PREFIX + "beta")));
        eligiblePostQuery.seedPosts(posts, /* excludedCount */ 0);
        proseGenerator.setResponseText("Section prose.");

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "sect"), "/summary");
        assertNull(reply, "terminal /summary self-delivers via the notifier and returns null");

        assertEquals(5, proseGenerator.callCount(), "one LLM call per cluster");
        String first = progressNotifier.completedText();
        assertNotNull(first, "the placeholder is finalized with the FIRST section");
        assertEquals(1, progressNotifier.freshSends().size(),
                "two sections → exactly one follow-on fresh send");
        String second = progressNotifier.freshSends().get(0);
        String alphaHeader = (PREFIX + "alpha news").toUpperCase(Locale.ROOT);
        String otherHeader = bundleLoader.get(BundleKeys.REPLY_DIGEST_CATEGORY_OTHER, "en")
                .toUpperCase(Locale.ROOT);
        assertTrue(first.contains(alphaHeader),
                "the first message is the alpha category section. Got: " + first);
        assertFalse(first.contains(otherHeader),
                "sections never merge — the first message carries no Other content. Got: " + first);
        assertTrue(second.contains(otherHeader),
                "the second message is the Other section (Other always last). Got: " + second);
        assertFalse(second.contains(alphaHeader),
                "sections never merge — the second message carries no alpha content. Got: " + second);
    }

    /**
     * M1-695 acceptance: the over-cap degraded form is delivered
     * per-section too — every section is a fresh send (the over-cap
     * branch runs before any publish, so there is no placeholder to
     * finalize), the too-large notice rides on the first message, and
     * the guard's other properties (no LLM call, no anchor) hold.
     */
    @Test
    void overCapDefaultFormDeliversPerSectionViaFreshSends() {
        Instant now = Instant.now();
        List<Post> posts = List.of(
                post(PREFIX + "oa1", "Over alpha one", now.minus(Duration.ofMinutes(1)),
                        List.of(PREFIX + "alpha")),
                post(PREFIX + "oa2", "Over alpha two", now.minus(Duration.ofMinutes(2)),
                        List.of(PREFIX + "alpha")),
                post(PREFIX + "oa3", "Over alpha three", now.minus(Duration.ofMinutes(3)),
                        List.of(PREFIX + "alpha")),
                post(PREFIX + "ob1", "Over beta one", now.minus(Duration.ofMinutes(4)),
                        List.of(PREFIX + "beta")),
                post(PREFIX + "ob2", "Over beta two", now.minus(Duration.ofMinutes(5)),
                        List.of(PREFIX + "beta")));
        eligiblePostQuery.seedPosts(posts, /* excludedCount */ 0);
        handler.summarizerPostCap = 4;

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "osect"), "/summary");

        assertNull(reply, "the default-form over-cap reply self-delivers per-section and returns null");
        assertEquals(0, proseGenerator.callCount(),
                "the over-cap guard must not reach the summarizer");
        assertEquals(0, anchorRepository.writeCount(),
                "the over-cap guard must write no summary anchor");
        assertNull(progressNotifier.completedText(),
                "no placeholder finalize on the over-cap path");
        assertEquals(2, progressNotifier.freshSends().size(),
                "one fresh send per category section");
        String first = progressNotifier.freshSends().get(0);
        assertTrue(first.contains("more than the 4-post summarizer limit"),
                "the too-large notice rides on the first section message. Got: " + first);
        assertTrue(first.contains((PREFIX + "alpha news").toUpperCase(Locale.ROOT)),
                "the first message is the alpha category section. Got: " + first);
        assertTrue(progressNotifier.freshSends().get(1).contains(
                        bundleLoader.get(BundleKeys.REPLY_DIGEST_CATEGORY_OTHER, "en")
                                .toUpperCase(Locale.ROOT)),
                "the second message is the Other section. Got: "
                        + progressNotifier.freshSends().get(1));
    }

    /**
     * The over-cap branch (M1-623) is the one production actually hits, so
     * it must categorize too — while keeping every guarantee that made it a
     * guard in the first place: no LLM call, no anchor, and the too-large
     * notice. The degraded prose still carries headline/url/uid because
     * degradedProseFor composes them INTO the prose.
     */
    @Test
    void overCapDefaultFormIsCategorizedAndStillMakesNoLlmCall() {
        Instant now = Instant.now();
        // Three posts (not two) so the shared tag reaches categoryMinClusters
        // and forms a real category section rather than falling into Other.
        List<Post> posts = List.of(
                post(PREFIX + "o1", "Over headline A", now.minus(Duration.ofMinutes(1))),
                post(PREFIX + "o2", "Over headline B", now.minus(Duration.ofMinutes(2))),
                post(PREFIX + "o3", "Over headline C", now.minus(Duration.ofMinutes(3))));
        eligiblePostQuery.seedPosts(posts, /* excludedCount */ 0);
        handler.summarizerPostCap = 2;

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "over"), "/summary");

        // M1-695: the default-form over-cap reply is delivered per-section
        // through the notifier's fresh sends — no router reply and no
        // placeholder lifecycle (this branch runs before any publish).
        assertNull(reply, "the default-form over-cap reply self-delivers per-section and returns null");
        assertNull(progressNotifier.completedText(),
                "no placeholder finalize on the over-cap path");
        assertEquals(1, progressNotifier.freshSends().size(),
                "one category section → exactly one fresh send");
        String body = progressNotifier.freshSends().get(0);
        assertEquals(0, proseGenerator.callCount(),
                "the over-cap guard must not reach the summarizer");
        assertEquals(0, anchorRepository.writeCount(),
                "the over-cap guard must write no summary anchor");
        assertTrue(body.contains((PREFIX + "news news").toUpperCase(Locale.ROOT)),
                "over-cap default form must carry the category header. Got: " + body);
        assertFalse(body.contains("[topic_id="),
                "over-cap default form must not emit flat cluster blocks. Got: " + body);
        // Every cluster's OWN degraded prose must render. These three posts
        // share an 8-char uid prefix, so their clusters share a topicId
        // (ClusterTraversal.topicIdFor truncates); a prose lookup keyed on
        // topicId instead of cluster identity renders one cluster's prose
        // three times and drops the other two.
        assertTrue(body.contains("Over headline A"),
                "degraded prose composes the headline into the prose itself. Got: " + body);
        assertTrue(body.contains("Over headline B"),
                "each cluster must render its OWN degraded prose. Got: " + body);
        assertTrue(body.contains("Over headline C"),
                "each cluster must render its OWN degraded prose. Got: " + body);
    }

    /**
     * The M1-675 render-side redaction must survive the render swap. The
     * categorized form emits no headline, so the degraded prose — which
     * composes the raw feed title — is the only place a command-shaped
     * title can be redacted on the default path. Driven through the
     * over-cap branch because that is the deterministically reachable one:
     * a feed can force it with volume alone, no LLM outage required.
     */
    @Test
    void defaultFormRedactsCommandShapedFeedTitleInDegradedProse() {
        Instant now = Instant.now();
        List<Post> posts = List.of(
                post(PREFIX + "r1", "/grant-admin p-attacker", now.minus(Duration.ofMinutes(1))),
                post(PREFIX + "r2", "Ordinary headline", now.minus(Duration.ofMinutes(2))),
                post(PREFIX + "r3", "Another headline", now.minus(Duration.ofMinutes(3))));
        eligiblePostQuery.seedPosts(posts, /* excludedCount */ 0);
        handler.summarizerPostCap = 2;

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "redact"), "/summary");

        assertNull(reply, "the default-form over-cap reply self-delivers per-section and returns null");
        String body = String.join("\n\n", progressNotifier.freshSends());
        assertFalse(body.contains("/grant-admin"),
                "a command-shaped feed title MUST NOT reach the reader verbatim on the "
                        + "default form. Got: " + body);
        assertTrue(body.contains("[redacted command]"),
                "the sanitizer must replace the command-shaped title with the fixed "
                        + "literal. Got: " + body);
        assertTrue(body.contains("Ordinary headline"),
                "redaction must be surgical — innocuous titles still render. Got: " + body);
        assertTrue(body.contains("https://example.com/" + PREFIX + "r1"),
                "the bare URL must survive sanitization (D30 plain-text). Got: " + body);
    }

    /**
     * The capped-section overflow line must use the /summary-scoped bundle
     * key, not the digest's. The digest's `reply.digest.category.more` ends
     * "@mention me to see them", which is meaningless in a DM — and the
     * digest's closing affordance is a broadcast device that this form must
     * not emit at all. Without this test a one-token swap to the digest key
     * ships the group wording into every DM and survives the whole suite:
     * BundleLoaderTest pins only en/cs keyset parity, not which key a call
     * site reads.
     */
    @Test
    void defaultFormOverflowLineUsesDmWordingNotTheGroupDigestWording() {
        Instant now = Instant.now();
        // 14 singleton clusters sharing one tag: the tag clears
        // categoryMinClusters (3) so they form one real section, and the
        // section overruns categoryItemCap (12) by exactly 2.
        List<Post> posts = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            posts.add(post(PREFIX + "ov" + i, "Overflow headline " + i,
                    now.minus(Duration.ofMinutes(i + 1L))));
        }
        eligiblePostQuery.seedPosts(posts, /* excludedCount */ 0);
        proseGenerator.setResponseText("Overflow prose.");

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "ovf"), "/summary");
        assertNull(reply, "terminal /summary self-delivers via the notifier and returns null");

        String body = progressNotifier.completedText();
        assertNotNull(body, "the composed summary must reach the notifier's complete() call");
        assertTrue(body.contains("+2 more stories — narrow with a tag or -w to see them"),
                "the overflow line must use the /summary-scoped DM wording. Got: " + body);
        assertFalse(body.contains("@mention me to see them"),
                "the group-worded digest overflow line MUST NOT reach a DM. Got: " + body);
        assertFalse(body.contains("@mention me to go deeper"),
                "the digest's closing affordance is a broadcast device and must not be "
                        + "emitted by /summary at all. Got: " + body);
    }

    /**
     * M1-697 gap 1 — the exact M1-694-r3 repro: posts titled
     * {@code /list-sources}, a legitimate headline and {@code --all}
     * co-clustered in that order. With the sanitize unit narrowed to ONE
     * post's title at composition, the flag-entry span can no longer cross
     * post boundaries: the innocent post's headline, bare URL and uid all
     * SURVIVE. The two crafted titles render VERBATIM — neither is a
     * closed-list token within its own field (a flag-bearing entry redacts
     * only when command word and flag share one sanitize input), which is
     * the accepted residual the ticket and security.md name. Driven through
     * the over-cap branch, the degraded path a feed can force with volume
     * alone (no LLM outage needed).
     */
    @Test
    void defaultFormDegradedProseSpanCannotCrossPostBoundaries() {
        Instant now = Instant.now();
        Post command = post(PREFIX + "x1", "/list-sources", now.minus(Duration.ofMinutes(1)));
        Post victim = post(PREFIX + "x2", "Legitimate headline", now.minus(Duration.ofMinutes(2)));
        Post flag = post(PREFIX + "x3", "--all", now.minus(Duration.ofMinutes(3)));
        eligiblePostQuery.seedPosts(List.of(command, victim, flag), /* excludedCount */ 0);
        handler.clusterTraversal = new ClusterTraversal(
                new ChainEdgeSource(List.of(command, victim, flag)), 3);
        handler.summarizerPostCap = 2;

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "span"), "/summary");

        assertNull(reply, "the default-form over-cap reply self-delivers per-section and returns null");
        String body = String.join("\n\n", progressNotifier.freshSends());
        assertTrue(body.contains("Legitimate headline"),
                "the innocent post's headline MUST survive — the span must not cross post "
                        + "boundaries. Got: " + body);
        assertTrue(body.contains("https://example.com/" + PREFIX + "x2"),
                "the innocent post's bare URL must survive (D30). Got: " + body);
        assertTrue(body.contains("(uid " + PREFIX + "x2)"),
                "the innocent post's uid must survive. Got: " + body);
        assertTrue(body.contains("/list-sources"),
                "a bare /list-sources title is not a closed-list token within its own field — "
                        + "it renders verbatim (accepted residual). Got: " + body);
        assertTrue(body.contains("--all"),
                "a bare --all title is not a closed-list token within its own field — "
                        + "it renders verbatim (accepted residual). Got: " + body);
        assertFalse(body.contains("[redacted command]"),
                "no redaction span may form across posts. Got: " + body);
    }

    /**
     * M1-697 gap 2 — the flat ({@code --full}) form writes degraded prose
     * verbatim into the {@code summary:} field, so a command-shaped feed
     * title used to ship unredacted two lines below a
     * {@code [redacted command]} headline. Composition now sanitizes every
     * post's title, so the flat form redacts too. Driven through the
     * over-cap branch, which renders degraded prose deterministically.
     */
    @Test
    void fullFormRedactsCommandShapedFeedTitleInDegradedProse() {
        Instant now = Instant.now();
        List<Post> posts = List.of(
                post(PREFIX + "f1", "/grant-admin p-attacker", now.minus(Duration.ofMinutes(1))),
                post(PREFIX + "f2", "Ordinary headline", now.minus(Duration.ofMinutes(2))),
                post(PREFIX + "f3", "Another headline", now.minus(Duration.ofMinutes(3))));
        eligiblePostQuery.seedPosts(posts, /* excludedCount */ 0);
        handler.summarizerPostCap = 2;

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "full"), "/summary --full");

        assertNotNull(reply, "the over-cap guard replies through the router");
        String body = reply.text();
        assertFalse(body.contains("/grant-admin"),
                "a command-shaped feed title MUST NOT reach the reader verbatim anywhere on "
                        + "the flat form — headline or summary: field. Got: " + body);
        assertTrue(body.contains("[redacted command]"),
                "the flat form must carry the redaction marker. Got: " + body);
        assertTrue(body.contains("https://example.com/" + PREFIX + "f1"),
                "the bare URL must survive sanitization (D30 plain-text). Got: " + body);
    }

    /**
     * M1-697 gap 3 — pre-fix the flat form's only title sanitize call saw
     * {@code posts.get(0)}, so a command-shaped title on a NON-first post
     * was neither redacted nor audited. Composition now sanitizes every
     * post's title, so the second post's title lands as
     * {@code [redacted command]} AND emits its per-occurrence
     * LLM_OUTPUT_SANITIZED row: the operator's detector no longer depends
     * on cluster position. The flat form is what {@code /retry} replays for
     * a {@code --full} anchor (RetryCommandHandler constructs
     * ClusterBlockRenderer on that branch, M1-696), so this pins the
     * inherited fix on that path too.
     */
    @Test
    void fullFormRedactsAndAuditsCommandShapedTitleOnNonFirstPost() {
        Instant now = Instant.now();
        Post first = post(PREFIX + "n1", "Innocent headline", now.minus(Duration.ofMinutes(1)));
        Post second = post(PREFIX + "n2", "/grant-admin p-attacker", now.minus(Duration.ofMinutes(2)));
        eligiblePostQuery.seedPosts(List.of(first, second), /* excludedCount */ 0);
        handler.clusterTraversal = new ClusterTraversal(
                new ChainEdgeSource(List.of(first, second)), 3);
        handler.summarizerPostCap = 1;
        RecordingAuditLogWriter auditWriter = new RecordingAuditLogWriter();
        handler.llmOutputSanitizer =
                new LlmOutputSanitizer(auditWriter, SanitizerTestDoubles.noOpDataSource());

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "pos"), "/summary --full");

        assertNotNull(reply, "the over-cap guard replies through the router");
        String body = reply.text();
        assertTrue(body.contains("Innocent headline"),
                "the first post's clean title renders untouched. Got: " + body);
        assertFalse(body.contains("/grant-admin"),
                "a command-shaped title on a NON-first post must be redacted. Got: " + body);
        assertTrue(body.contains("[redacted command]"),
                "the redaction marker must be present. Got: " + body);
        assertEquals(2, auditWriter.rows(),
                "one LLM_OUTPUT_SANITIZED row at producer composition and one at renderer "
                        + "derivation — two sanitize calls, two truthful per-occurrence rows "
                        + "(M1-697 derive-at-render); the key property is that the non-first "
                        + "post's title is redacted AND audited at all");
    }

    @Test
    void terminalSummaryPublishesNonTerminalStagesInOrderThenCompletes() {
        eligiblePostQuery.seedPosts(
                List.of(post(PREFIX + "st1", "Stage headline", Instant.now())), 0);
        proseGenerator.setResponseText("Stage prose.");

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "stg"), "/summary");

        assertNull(reply, "terminal /summary self-delivers via the notifier and returns null");
        assertEquals(
                List.of(ProgressStage.STARTED, ProgressStage.RETRIEVING,
                        ProgressStage.GENERATING, ProgressStage.FINALIZING),
                progressNotifier.publishedStages(),
                "the handler must publish the four non-terminal stages in spec order "
                        + "for an English scope (TRANSLATING is suppressed when scope language is 'en') "
                        + "before the terminal complete()");
        assertNotNull(progressNotifier.completedText(),
                "the terminal path must call complete() with the composed summary");
        assertEquals(0, progressNotifier.failCount(),
                "a successful summary must not call fail()");
    }

    @Test
    void csScopePublishesTranslatingStage() {
        // For a Czech-scope user, the TRANSLATING stage must be published
        // in spec order (between GENERATING and FINALIZING), unlike the
        // English-default case where it is suppressed.
        eligiblePostQuery.seedPosts(
                List.of(post(PREFIX + "cs1", "CS headline", Instant.now())), 0);
        proseGenerator.setResponseText("CS prose.");

        // Override the DataSource to return "cs" for the scope_preferences lookup.
        UUID groupId = UUID.randomUUID();
        handler.dataSource = new FixedUserAndLanguageDataSource(userId, groupId, "cs");

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "cs"), "/summary");

        assertNull(reply, "terminal /summary self-delivers via the notifier and returns null");
        assertTrue(
                progressNotifier.publishedStages().contains(ProgressStage.TRANSLATING),
                "a Czech-scope summary must publish TRANSLATING in the stage sequence. "
                        + "Published stages: " + progressNotifier.publishedStages());
        assertNotNull(progressNotifier.completedText(),
                "the terminal path must call complete() with the composed summary");
    }

    @Test
    void llmUnreachableYieldsDegradedFallbackReply() {
        Post p = post(PREFIX + "d1", "Degraded headline", Instant.now());
        eligiblePostQuery.seedPosts(List.of(p), 0);
        proseGenerator.setDegradedMode(true);

        // Degraded prose is still a successful terminal delivery →
        // complete(), not fail().
        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "deg"), "/summary");
        assertNull(reply, "terminal /summary self-delivers and returns null");

        String body = progressNotifier.completedText();
        assertNotNull(body, "degraded summary still reaches complete()");
        assertEquals(0, progressNotifier.failCount(),
                "a degraded (but composed) summary is success, not fail()");
        assertTrue(body.contains("LLM is unreachable"),
                "degraded reply must include the degraded_notice prefix. Got: " + body);
        assertTrue(body.contains("Degraded headline"),
                "degraded prose includes the headline");
    }

    @Test
    void errorEscapingGenerationStillFinalizesNotifierAndReleasesSlot() {
        eligiblePostQuery.seedPosts(
                List.of(post(PREFIX + "err1", "Error headline", Instant.now())), 0);
        // A non-RuntimeException throwable (OOM) escapes the prose generator
        // after STARTED/RETRIEVING/GENERATING were published. The catch is
        // RuntimeException-only, so without the finally the notifier's
        // per-scope placeholder state would dangle.
        proseGenerator.setThrowErrorOnGenerate(true);

        ScopeRef scope = new ScopeRef.Dm(PREFIX + "err");
        assertThrows(OutOfMemoryError.class, () -> handler.handle(scope, "/summary"),
                "an Error escaping generation must propagate, not be swallowed");

        assertEquals(1, progressNotifier.failCount(),
                "the finally must drive fail() on the Error path so the notifier "
                        + "placeholder is finalized and its per-scope state evicted "
                        + "(spec step 4: placeholders are never left dangling)");
        assertNull(progressNotifier.completedText(),
                "the Error path must not call complete()");
        assertFalse(tracker.isInFlight(userId, "dm", userId),
                "the in-flight slot must be released even when an Error escapes");
    }

    @Test
    void landedInterruptRendersStoppedTerminalNotFailure() {
        eligiblePostQuery.seedPosts(
                List.of(post(PREFIX + "stop1", "Stopped headline", Instant.now())), 0);
        // /stop lands mid-generation: mark the in-flight handle cancelled,
        // then a RuntimeException escapes the prose generator (a landed
        // interrupt). The catch must render the D35 stopped terminal, not the
        // generic progress.failed reply (which D31/D35 forbid for /stop).
        proseGenerator.runBeforeGenerate(() ->
                tracker.getCancellationHandle(userId, "dm", userId)
                        .ifPresent(InFlightTracker.CancellationHandle::markCancelled));
        proseGenerator.setThrowRuntimeOnGenerate(true);

        ScopeRef scope = new ScopeRef.Dm(PREFIX + "stop");
        handler.handle(scope, "/summary");

        assertEquals(bundleLoader.get(BundleKeys.PROGRESS_STOPPED, "en"),
                progressNotifier.completedText(),
                "a landed interrupt must render the D35 stopped terminal via complete()");
        assertEquals(0, progressNotifier.failCount(),
                "cancellation must not render the generic failure terminal");
        assertFalse(tracker.isInFlight(userId, "dm", userId),
                "the in-flight slot must be released after a cancelled summary");
    }

    @Test
    void capExcessYieldsCapExcessNoticePrefix() {
        // Modeled by the EligiblePostQuery seed: 3 retained posts +
        // excludedCount=2 (so totalBeforeCap=5). Profile label is
        // pinned to "test" via the seed.
        Instant now = Instant.now();
        List<Post> retained = List.of(
                post(PREFIX + "c0", "Cap headline 0", now.minus(Duration.ofMinutes(0))),
                post(PREFIX + "c1", "Cap headline 1", now.minus(Duration.ofMinutes(1))),
                post(PREFIX + "c2", "Cap headline 2", now.minus(Duration.ofMinutes(2))));
        eligiblePostQuery.seedPostsWithCap(retained, /* totalBeforeCap */ 5,
                /* excludedCount */ 2, /* profileCap */ 3, /* profileLabel */ "test");
        proseGenerator.setResponseText("Prose.");

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "cap"), "/summary");
        assertNull(reply, "terminal /summary self-delivers and returns null");

        String body = progressNotifier.completedText();
        assertNotNull(body, "the cap-excess summary must reach complete()");
        assertTrue(body.contains("Showing 3 of 5"),
                "cap-excess prefix must cite included/total counts. Got: " + body);
        assertTrue(body.contains("2 oldest excluded"),
                "cap-excess prefix must cite the excluded count. Got: " + body);
        assertEquals(3, proseGenerator.callCount(),
                "only the retained 3 posts (= 3 clusters) get LLM calls");
    }

    @Test
    void groupScopeResolvesGroupIdAndWritesGroupScopedAnchor() {
        // Group scope is functional (M1-288): the handler resolves the
        // caller's users.id AND the group's scope id, runs the prose
        // generator, and writes a personal anchor keyed on the GROUP's
        // scope id — not the caller's user id (the DM key). The stub
        // answers the users-id lookup with `userId` and the group-id
        // lookup with a distinct `groupId`.
        UUID groupId = UUID.randomUUID();
        handler.dataSource = new FixedUserAndLanguageDataSource(userId, groupId);
        eligiblePostQuery.seedPosts(
                List.of(post(PREFIX + "g1", "Group headline", Instant.now())), 0);
        proseGenerator.setResponseText("Group summary prose.");

        OutboundMessage reply = handler.handle(new ScopeRef.Group("g-some-id"), "/summary");
        assertNull(reply, "terminal group /summary self-delivers via the notifier and returns null");

        assertEquals(1, proseGenerator.callCount(),
                "group scope MUST run the prose generator (no longer short-circuits to no_posts_yet)");
        assertEquals(1, anchorRepository.writeCount(),
                "group /summary must write a personal anchor");
        // Per-(user, scope) isolation: the anchor is keyed on the caller's
        // user id as user_id but the GROUP's id as scope_id + scope_kind
        // 'group' — distinct from a DM anchor (scope_kind 'dm', scope_id =
        // user id), so the two never alias.
        assertEquals(userId, anchorRepository.lastUserId(),
                "anchor user_id must be the caller's users.id");
        assertEquals("group", anchorRepository.lastScopeKind(),
                "anchor scope_kind must be 'group' for a group invocation");
        assertEquals(groupId, anchorRepository.lastScopeId(),
                "anchor scope_id must be the group's id, not the caller's user id");
        assertNotEquals(userId, anchorRepository.lastScopeId(),
                "the group scope id must differ from the caller's user id (DM/group anchor isolation)");
    }

    @Test
    void sanitizerStripsPrivilegedCommandFromLlmAuthoredProse() {
        Post p = post(PREFIX + "s1", "San headline", Instant.now());
        eligiblePostQuery.seedPosts(List.of(p), 0);
        // A small LLM emits prose containing /grant-admin — the sanitizer
        // must replace it with [redacted command] before the reply lands.
        proseGenerator.setResponseText("Ops should run /grant-admin to escalate.");

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "san"), "/summary");
        assertNull(reply, "terminal /summary self-delivers and returns null");

        String body = progressNotifier.completedText();
        assertNotNull(body, "the sanitized summary must reach complete()");
        assertFalse(body.contains("/grant-admin"),
                "sanitizer MUST strip /grant-admin from LLM-authored prose. Got: " + body);
        assertTrue(body.contains("[redacted command]"),
                "sanitizer MUST replace the matched command with the fixed literal. Got: " + body);
    }

    @Test
    void anchorWrittenOnSuccessfulSummary() {
        Post p = post(PREFIX + "a1", "Anchor headline", Instant.now());
        eligiblePostQuery.seedPosts(List.of(p), 0);
        proseGenerator.setResponseText("Anchor prose.");

        handler.handle(new ScopeRef.Dm(PREFIX + "anc"), "/summary");

        assertEquals(1, anchorRepository.writeCount(),
                "anchor must be written after successful /summary");
        assertFalse(anchorRepository.lastPostUids().isEmpty(),
                "anchor must contain the frozen post UIDs");
    }

    @Test
    void anchorWrittenOnDegradedFallbackPath() {
        Post p = post(PREFIX + "d2", "Degraded anchor headline", Instant.now());
        eligiblePostQuery.seedPosts(List.of(p), 0);
        proseGenerator.setDegradedMode(true);

        handler.handle(new ScopeRef.Dm(PREFIX + "dega"), "/summary");

        assertEquals(1, anchorRepository.writeCount(),
                "anchor IS written on degraded path (spec: '/retry against "
                        + "this degraded run regenerates the prose')");
    }

    // ----- M1-183: LLM rate cap + in-flight coverage ---------------------
    //
    // Per docs/spec/security.md §Rate limiting, /summary draws from the
    // same per-user LLM bucket as chat replies and /retry re-rolls; per
    // docs/spec/commands.md §Conversation control + §Surface conventions,
    // a /summary prose generation is interruptible (registered with
    // InFlightTracker) and at most one interruptible request may be in
    // flight per (user, scope).

    @Test
    void summaryRejectedWithRateLimitReplyWhenLlmBucketExhausted() {
        eligiblePostQuery.seedPosts(
                List.of(post(PREFIX + "rl1", "Rate headline", Instant.now())), 0);
        // Exhaust the per-user LLM bucket: a single-token bucket whose
        // token is already taken for this user.
        LlmRateCap exhausted = new LlmRateCap(1);
        assertTrue(exhausted.tryAcquire(userId), "drain the bucket's only token");
        handler.llmRateCap = exhausted;

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "rl"), "/summary");

        assertTrue(reply.text().contains("too quickly"),
                "rate-capped /summary must get the rate-limit reply. Got: " + reply.text());
        assertEquals(0, proseGenerator.callCount(),
                "rate-capped /summary must make no LLM call");
        assertFalse(tracker.isInFlight(userId, "dm", userId),
                "rate-cap rejection must not leave the in-flight slot held");
    }

    @Test
    void summaryRegistersInFlightDuringProseGenerationAndReleasesAfterwards() {
        eligiblePostQuery.seedPosts(
                List.of(post(PREFIX + "if1", "InFlight headline", Instant.now())), 0);
        proseGenerator.setResponseText("In-flight prose.");
        proseGenerator.probeInFlightDuringGenerate(
                () -> tracker.isInFlight(userId, "dm", userId));

        handler.handle(new ScopeRef.Dm(PREFIX + "if"), "/summary");

        assertTrue(proseGenerator.wasInFlightDuringGenerate(),
                "/summary must hold the InFlightTracker slot during prose "
                        + "generation so /stop can find it");
        assertFalse(tracker.isInFlight(userId, "dm", userId),
                "the slot must be released after the reply is composed");
    }

    @Test
    void secondSummaryWhileOneInFlightRepliesInProgressWithoutSecondLlmCall() {
        eligiblePostQuery.seedPosts(
                List.of(post(PREFIX + "sec1", "Second headline", Instant.now())), 0);
        // Model the first /summary still in flight for the same
        // (user, scope) by holding its tracker slot.
        assertNotNull(tracker.tryAcquire(userId, "dm", userId));

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "sec"), "/summary");

        assertTrue(reply.text().contains("already in progress"),
                "second /summary while one is in flight must get the "
                        + "in-progress reply. Got: " + reply.text());
        assertEquals(0, proseGenerator.callCount(),
                "the rejected second /summary must make no LLM call");
        assertTrue(tracker.isInFlight(userId, "dm", userId),
                "the first request's slot must remain held — the rejection "
                        + "must not release a slot it failed to acquire");
    }

    @Test
    void rejectedSummaryLeavesSlotAndBucketUsableForNextRequest() {
        eligiblePostQuery.seedPosts(
                List.of(post(PREFIX + "nl1", "NoLeak headline", Instant.now())), 0);
        proseGenerator.setResponseText("Recovered prose.");
        // Single-token bucket: the follow-up request below only succeeds
        // if the rejected one consumed nothing from it.
        handler.llmRateCap = new LlmRateCap(1);
        InFlightTracker.CancellationHandle slot = tracker.tryAcquire(userId, "dm", userId);
        assertNotNull(slot, "occupy the slot");

        OutboundMessage rejected = handler.handle(new ScopeRef.Dm(PREFIX + "nl"), "/summary");
        assertTrue(rejected.text().contains("already in progress"),
                "the occupied slot must reject the request. Got: " + rejected.text());

        tracker.release(userId, "dm", userId, slot); // the first request finishes
        // The next request reaches the terminal path: it self-delivers
        // via the notifier (returns null) and its composed body — carrying
        // the recovered prose — is the argument to complete().
        OutboundMessage ok = handler.handle(new ScopeRef.Dm(PREFIX + "nl"), "/summary");
        assertNull(ok, "the permitted terminal /summary self-delivers and returns null");

        String okBody = progressNotifier.completedText();
        assertNotNull(okBody, "the permitted request must reach complete()");
        assertTrue(okBody.contains("Recovered prose."),
                "the next permitted request must succeed — the rejection "
                        + "consumed neither the slot nor the single bucket token. Got: "
                        + okBody);
        assertFalse(tracker.isInFlight(userId, "dm", userId),
                "the successful run must release its slot");
    }

    // ----- fixtures + collaborator stubs --------------------------------

    private static Post post(String uid, String title, Instant publishedAt) {
        return post(uid, title, publishedAt, List.of(PREFIX + "news"));
    }

    /** Tag-parameterized variant so multi-section fixtures can seed distinct category tags (M1-695). */
    private static Post post(String uid, String title, Instant publishedAt, List<String> tags) {
        return new Post(
                UUID.randomUUID(),
                uid,
                UUID.randomUUID(),
                "TestSrc",
                title,
                "https://example.com/" + uid,
                "Body for " + title,
                publishedAt,
                tags,
                // classification seeded DISTINCT from tags so the summary
                // assertion can prove the classification: line is not the tag copy.
                List.of("technical"));
    }

    /**
     * Recording subclass of {@link EligiblePostQuery}: returns the
     * seeded {@link Result} on every {@code fetch}; records the call
     * count so the dispatch test can assert exactly-one query per
     * handler invocation.
     */
    private static final class RecordingEligiblePostQuery extends EligiblePostQuery {
        private final AtomicInteger fetchCallCount = new AtomicInteger();
        private final AtomicInteger countWorldSourcesCallCount = new AtomicInteger();
        private Result seeded =
                new Result(List.of(), 0, 0, 200, "laptop", Optional.empty());
        // Default models a NON-EMPTY world so the empty-window branch resolves
        // to no_posts_yet; the empty-world case seeds 0 explicitly (M1-621).
        private int worldSourceCount = 1;

        void seedNoPosts() {
            seeded = new Result(List.of(), 0, 0, 200, "laptop", Optional.empty());
        }

        void setWorldSourceCount(int count) {
            this.worldSourceCount = count;
        }

        void seedPosts(List<Post> posts, int excludedCount) {
            int total = posts.size() + excludedCount;
            seeded = new Result(posts, total, excludedCount, 200, "laptop", Optional.empty());
        }

        void seedPostsWithCap(List<Post> posts, int totalBeforeCap, int excludedCount,
                              int profileCap, String profileLabel) {
            seeded = new Result(posts, totalBeforeCap, excludedCount, profileCap, profileLabel,
                    Optional.empty());
        }

        @Override
        public Result fetch(String scopeKind, UUID scopeId,
                            Optional<String> positionalTag, Duration window) {
            fetchCallCount.incrementAndGet();
            return seeded;
        }

        @Override
        public List<String> readVocabulary() {
            return List.of();
        }

        @Override
        public int countWorldSources(String scopeKind, UUID scopeId) {
            countWorldSourcesCallCount.incrementAndGet();
            return worldSourceCount;
        }

        int fetchCallCount() {
            return fetchCallCount.get();
        }

        int countWorldSourcesCallCount() {
            return countWorldSourcesCallCount.get();
        }
    }

    /**
     * Recording subclass of {@link SummaryProseGenerator}: every
     * generate call returns the configured response text (or degraded
     * fallback) wrapped per-cluster; tracks the per-call count.
     */
    private static final class RecordingSummaryProseGenerator extends SummaryProseGenerator {
        private final AtomicInteger callCount = new AtomicInteger();
        private String responseText = "default test summary";
        private boolean degradedMode = false;
        private boolean throwErrorOnGenerate = false;
        private boolean throwRuntimeOnGenerate = false;
        private @Nullable Runnable beforeGenerate;
        private @Nullable BooleanSupplier inFlightProbe;
        private boolean inFlightDuringGenerate;

        void setResponseText(String text) {
            this.responseText = text;
        }

        /** Sanitizer used when composing degraded prose (M1-697 signature). */
        LlmOutputSanitizer degradedSanitizer = SanitizerTestDoubles.noAuditSanitizer();

        void setDegradedMode(boolean degraded) {
            this.degradedMode = degraded;
        }

        /**
         * Models a non-RuntimeException throwable (e.g. OOM) escaping the
         * generation step — used to prove the handler still finalizes the
         * notifier placeholder and releases the in-flight slot on that path.
         */
        void setThrowErrorOnGenerate(boolean throwError) {
            this.throwErrorOnGenerate = throwError;
        }

        /**
         * Models a landed cancellation interrupt: a RuntimeException escapes
         * generation (the catch is RuntimeException-aware, unlike the OOM
         * Error path above).
         */
        void setThrowRuntimeOnGenerate(boolean throwRuntime) {
            this.throwRuntimeOnGenerate = throwRuntime;
        }

        /**
         * Side-effecting hook run at the top of {@link #generate} — lets a
         * test mark the in-flight handle cancelled at the moment the LLM
         * layer runs, modelling /stop landing mid-generation.
         */
        void runBeforeGenerate(Runnable hook) {
            this.beforeGenerate = hook;
        }

        /**
         * Evaluated at the top of {@link #generate} — lets a test
         * observe tracker state at the moment the LLM layer runs.
         */
        void probeInFlightDuringGenerate(BooleanSupplier probe) {
            this.inFlightProbe = probe;
        }

        boolean wasInFlightDuringGenerate() {
            return inFlightDuringGenerate;
        }

        @Override
        public List<ClusterProse> generate(List<Cluster> clusters, String scopeLanguage) {
            if (inFlightProbe != null) {
                inFlightDuringGenerate = inFlightProbe.getAsBoolean();
            }
            if (beforeGenerate != null) {
                beforeGenerate.run();
            }
            if (throwRuntimeOnGenerate) {
                throw new RuntimeException("test-injected interrupt during generate");
            }
            if (throwErrorOnGenerate) {
                throw new OutOfMemoryError("test-injected error during generate");
            }
            List<ClusterProse> out = new ArrayList<>(clusters.size());
            for (Cluster c : clusters) {
                callCount.incrementAndGet();
                if (degradedMode) {
                    out.add(new ClusterProse(c, SummaryProseGenerator.degradedProseFor(c, degradedSanitizer), true));
                } else {
                    out.add(new ClusterProse(c, responseText, false));
                }
            }
            return out;
        }

        int callCount() {
            return callCount.get();
        }
    }

    /**
     * Edge source reporting the given posts as a chain in list order
     * (p0—p1—p2—…), so {@link ClusterTraversal} merges them into ONE
     * cluster whose {@code posts()} preserve that order — the multi-post
     * cluster shape the M1-697 span tests need, which
     * {@link EmptyEdgeSource} (all singletons) cannot produce.
     */
    private static final class ChainEdgeSource implements PostReferenceEdgeSource {
        private final List<UUID> orderedIds;

        ChainEdgeSource(List<Post> posts) {
            orderedIds = posts.stream().map(Post::id).toList();
        }

        @Override
        public Map<UUID, Set<UUID>> neighborsAmong(Collection<UUID> postIds) {
            Map<UUID, Set<UUID>> out = new HashMap<>();
            for (UUID id : postIds) {
                out.put(id, new LinkedHashSet<>());
            }
            for (int i = 0; i + 1 < orderedIds.size(); i++) {
                UUID current = orderedIds.get(i);
                UUID next = orderedIds.get(i + 1);
                if (out.containsKey(current) && out.containsKey(next)) {
                    out.get(current).add(next);
                    out.get(next).add(current);
                }
            }
            return out;
        }
    }

    /**
     * {@link AuditLogWriter} that counts rows instead of discarding them
     * (the {@link SanitizerTestDoubles} no-op variant with a counter) so a
     * test can pin the per-occurrence LLM_OUTPUT_SANITIZED emission.
     */
    private static final class RecordingAuditLogWriter extends AuditLogWriter {
        private final AtomicInteger rows = new AtomicInteger();

        RecordingAuditLogWriter() {
            super(row -> row);
        }

        @Override
        public void write(Connection conn, RedactionHook.AuditRow row) {
            rows.incrementAndGet();
        }

        int rows() {
            return rows.get();
        }
    }

    /**
     * Recording stub for {@link SummaryAnchorRepository}: captures
     * write calls without touching the DB.
     */
    private static final class RecordingSummaryAnchorRepository extends SummaryAnchorRepository {
        private final AtomicInteger writes = new AtomicInteger();
        private volatile List<String> lastPostUids = List.of();
        private volatile @Nullable UUID lastUserId;
        private volatile @Nullable String lastScopeKind;
        private volatile @Nullable UUID lastScopeId;
        private volatile @Nullable String lastRenderForm;

        @Override
        public void write(UUID userId, String scopeKind, UUID scopeId,
                          String commandName, String renderForm, String argHash,
                          List<String> postUids, String clusterMapJson) {
            writes.incrementAndGet();
            lastPostUids = List.copyOf(postUids);
            lastUserId = userId;
            lastScopeKind = scopeKind;
            lastScopeId = scopeId;
            lastRenderForm = renderForm;
        }

        @Override
        public Optional<AnchorRow> read(UUID userId, String scopeKind, UUID scopeId) {
            return Optional.empty();
        }

        @Override
        public void clear(UUID userId, String scopeKind, UUID scopeId) {
            // no-op in test
        }

        int writeCount() { return writes.get(); }
        List<String> lastPostUids() { return lastPostUids; }
        @Nullable UUID lastUserId() { return lastUserId; }
        @Nullable String lastScopeKind() { return lastScopeKind; }
        @Nullable UUID lastScopeId() { return lastScopeId; }
        @Nullable String lastRenderForm() { return lastRenderForm; }
    }
}
