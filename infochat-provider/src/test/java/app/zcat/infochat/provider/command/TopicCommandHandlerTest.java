package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.InFlightTracker;
import app.zcat.infochat.provider.chat.LlmRateCap;
import app.zcat.infochat.provider.digest.DigestRenderer;
import app.zcat.infochat.provider.digest.SummaryCacheRepository;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.summary.ClusterTraversal;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EmptyEdgeSource;
import app.zcat.infochat.provider.summary.EligiblePostQuery;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Result;
import app.zcat.infochat.provider.summary.SummaryProseGenerator;
import app.zcat.infochat.provider.summary.SummaryProseGenerator.ClusterProse;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import app.zcat.infochat.provider.translation.TranslationPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.text.MessageFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static app.zcat.infochat.provider.testsupport.TranslationFixtures.newEnShortCircuitPipeline;
import static app.zcat.infochat.provider.testsupport.TranslationFixtures.newRealBundleLoader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Handler-tier (plain JUnit) tests for {@link TopicCommandHandler}:
 * bare listing, default window, --full shape, tolerant zero-match,
 * render safety; the drill-down IT pins the render machinery. */
class TopicCommandHandlerTest {

    private static final String PREFIX = "m1-936h-";
    private static final UUID SRC_A = UUID.randomUUID();
    private static final UUID SRC_B = UUID.randomUUID();
    private static final UUID SRC_C = UUID.randomUUID();

    private TopicCommandHandler handler;
    private RecordingEligiblePostQuery eligiblePostQuery;
    private RecordingCacheRepository cacheRepository;
    private RecordingProseGenerator proseGenerator;
    private BundleLoader bundleLoader;
    private LlmRateCap llmRateCap;
    private InFlightTracker tracker;
    private RecordingProgressNotifier progressNotifier;
    private UUID userId;
    private Instant now;

    @BeforeEach
    void buildHandlerWithStubs() throws Exception {
        bundleLoader = newRealBundleLoader();
        eligiblePostQuery = new RecordingEligiblePostQuery();
        cacheRepository = new RecordingCacheRepository();
        proseGenerator = new RecordingProseGenerator();
        tracker = new InFlightTracker();
        llmRateCap = new LlmRateCap(10);
        userId = UUID.randomUUID();
        now = Instant.parse("2026-08-28T12:00:00Z");
        handler = new TopicCommandHandler();
        handler.bundleLoader = bundleLoader;
        handler.dataSource = new FixedUserAndLanguageDataSource(userId);
        handler.eligiblePostQuery = eligiblePostQuery;
        handler.summaryCacheRepository = cacheRepository;
        handler.clusterTraversal = new ClusterTraversal(new EmptyEdgeSource(), 3);
        handler.summaryProseGenerator = proseGenerator;
        LlmOutputSanitizer sanitizer = SanitizerTestDoubles.noAuditSanitizer();
        TranslationPipeline translationPipeline = newEnShortCircuitPipeline(bundleLoader);
        handler.llmOutputSanitizer = sanitizer;
        handler.digestRenderer = DigestRenderer.forSummaryRendering(
                sanitizer, translationPipeline, bundleLoader, 12, 3);
        handler.inFlightTracker = tracker;
        handler.llmRateCap = llmRateCap;
        progressNotifier = new RecordingProgressNotifier();
        handler.progressNotifier = progressNotifier;
        InboundContext context = new InboundContext();
        context.setAdapterName("inmemory");
        context.setSenderContactId(PREFIX + "caller");
        handler.inboundContext = context;
        handler.clock = Clock.fixed(now, ZoneOffset.UTC);
        handler.summarizerPostCap = 50;
    }

    @Test
    void handlerNameIsLiteralTopic() {
        assertEquals("topic", handler.name());
    }

    @Test
    void bareTopicListsWindowTopicsRankedByWeightedCount() {
        // alpha: 5 posts/1 source (38); zeta: 3 posts/3 sources (103) —
        // corroboration flips the order against alphabetical AND
        // post-count ranking.
        eligiblePostQuery.seedPosts(List.of(
                post("a1", SRC_A, List.of("alpha")),
                post("a2", SRC_A, List.of("alpha")),
                post("a3", SRC_A, List.of("alpha")),
                post("a4", SRC_A, List.of("alpha")),
                post("a5", SRC_A, List.of("alpha")),
                post("z1", SRC_A, List.of("zeta")),
                post("z2", SRC_B, List.of("zeta")),
                post("z3", SRC_C, List.of("zeta"))));

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "dm"), "/topic");

        String expected = bundleLoader.get(BundleKeys.REPLY_TOPIC_LISTING_HEADER, "en")
                + " zeta (3), alpha (5)";
        assertNotNull(reply, "the bare listing is a router-sent reply");
        assertEquals(expected, reply.text(),
                "the listing ranks by weighted count (zeta's 3 corroborating sources"
                        + " outrank alpha's 5 single-source posts)");
        assertEquals(0, proseGenerator.generateCalls,
                "the bare listing makes no LLM call");
        assertEquals(0, llmRateCap.entryCount(), "the bare listing draws no rate-cap token");
        assertFalse(tracker.isInFlight(userId, "dm", userId),
                "the bare listing holds no in-flight slot");
    }

    @Test
    void groupDefaultWindowIsThePreviousDigestBoundaryAndDmDefaultsTo24h() {
        eligiblePostQuery.seedPosts(List.of());
        cacheRepository.boundary = Optional.of(now.minus(Duration.ofHours(5)));

        handler.handle(new ScopeRef.Group(PREFIX + "group"), "/topic");
        assertEquals(Duration.ofHours(5), eligiblePostQuery.capturedWindow,
                "group default window = the period since the previous digest boundary");

        handler.handle(new ScopeRef.Dm(PREFIX + "dm"), "/topic");
        assertEquals(SummaryArgs.DEFAULT_WINDOW, eligiblePostQuery.capturedWindow,
                "DM default window = 24h (SummaryArgs.DEFAULT_WINDOW)");

        cacheRepository.boundary = Optional.empty();
        handler.handle(new ScopeRef.Group(PREFIX + "group"), "/topic");
        assertEquals(SummaryArgs.DEFAULT_WINDOW, eligiblePostQuery.capturedWindow,
                "a never-digested group falls back to the 24h default");

        handler.handle(new ScopeRef.Dm(PREFIX + "dm"), "/topic -w 3d");
        assertEquals(Duration.ofDays(3), eligiblePostQuery.capturedWindow,
                "-w overrides every default");
    }

    @Test
    void unknownTopicGetsFuzzyReplyNotVocabularyError() {
        eligiblePostQuery.seedDrillPosts(List.of());
        eligiblePostQuery.seedPosts(List.of(
                post("c1", SRC_A, List.of("czechia")),
                post("p1", SRC_A, List.of("prague"))));

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "dm"), "/topic zzz");

        String expected = MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_TOPIC_NO_MATCH, "en"), "zzz")
                + MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_TOPIC_SUGGESTIONS_FOOTER, "en"),
                "czechia, prague");
        assertEquals(expected, reply.text());
        assertFalse(reply.text().contains("Unknown tag"),
                "/topic never speaks the bounded-vocabulary error");
    }

    @Test
    void fullListingFloorsCapsAndCountsSinglePostTopics() {
        eligiblePostQuery.seedPosts(List.of(
                post("c1", SRC_A, List.of("czechia")),
                post("c2", SRC_A, List.of("czechia")),
                post("c3", SRC_A, List.of("czechia")),
                post("p1", SRC_A, List.of("prague")),
                post("p2", SRC_A, List.of("prague")),
                post("b1", SRC_A, List.of("brno")),
                post("o1", SRC_A, List.of("ostrava"))));
        handler.fullListingCap = 1;

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "dm"), "/topic --full");

        String expected = bundleLoader.get(BundleKeys.REPLY_TOPIC_LISTING_HEADER, "en")
                + " czechia (3) " + MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_TOPIC_MORE, "en"), 1)
                + "\n" + MessageFormat.format(
                bundleLoader.get(BundleKeys.REPLY_TOPIC_MORE_SINGLE_POST, "en"), 2);
        assertEquals(expected, reply.text(),
                "--full floors at >=2 posts, caps at the configured bound, and"
                        + " counts single-post topics in one overflow line");
    }

    @Test
    void bareTopicWithNoFreeTopicsGetsTheNoneReply() {
        eligiblePostQuery.seedPosts(List.of(
                post("c1", SRC_A, List.of()),
                post("p1", SRC_A, List.of())));

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "dm"), "/topic");

        assertEquals(bundleLoader.get(BundleKeys.REPLY_TOPIC_NONE, "en"), reply.text());
    }

    @Test
    void hostileTopicCorpusRendersOnlyCanonicalTokens() {
        eligiblePostQuery.seedPosts(List.of(
                post("h1", SRC_A, List.of("/grant-admin", "czechia")),
                post("h2", SRC_A, List.of("Grant Admin", "with space")),
                post("h3", SRC_A, List.of("UPPER"))));

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "dm"), "/topic");

        String expected = bundleLoader.get(BundleKeys.REPLY_TOPIC_LISTING_HEADER, "en")
                + " czechia (1)";
        assertEquals(expected, reply.text(),
                "every rendered token is a stored canonical value; the line composes"
                        + " from the bundle template with tokens as inert arguments");
        assertFalse(reply.text().contains("/grant-admin"),
                "a non-canonical value can never forge a command token");
    }

    @Test
    void overCapDrillDownRendersDegradedForm() {
        // The /summary over-cap gate, mirrored: past the summarizer cap
        // the degraded form renders per-section with the too-large notice
        // — deterministic, no LLM call, no rate-cap token, no in-flight
        // slot. The handler has no SummaryAnchorRepository at all, so the
        // no-anchor guarantee is structural.
        eligiblePostQuery.seedDrillPosts(List.of(
                post("c1", SRC_A, List.of("czechia")),
                post("c2", SRC_A, List.of("czechia")),
                post("c3", SRC_A, List.of("czechia"))));
        handler.summarizerPostCap = 2;

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "dm"), "/topic czech");

        assertNull(reply, "the over-cap form self-delivers per-section");
        String notice = MessageFormat.format(
                bundleLoader.get(
                        app.zcat.infochat.provider.bundle.BundleKeys.REPLY_SUMMARY_WINDOW_TOO_LARGE_NOTICE,
                        "en"),
                3, 2);
        assertFalse(progressNotifier.freshSends().isEmpty(),
                "the degraded sections are delivered as fresh sends");
        assertTrue(progressNotifier.freshSends().get(0).contains(notice),
                "the first section carries the too-large notice with the true"
                        + " pre-cap total and the cap. Got: "
                        + progressNotifier.freshSends().get(0));
        assertEquals(0, proseGenerator.generateCalls,
                "the over-cap branch never reaches the summarizer");
        assertEquals(0, llmRateCap.entryCount(), "no rate-cap token is drawn");
        assertFalse(tracker.isInFlight(userId, "dm", userId),
                "no in-flight slot is held");
    }

    @Test
    void minutesWindowSuffixIsTheSharedParseError() {
        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "dm"), "/topic -w 5m");
        assertEquals(bundleLoader.get(SummaryArgs.BUNDLE_WINDOW_MINUTES_NOT_ACCEPTED, "en"),
                reply.text());
    }

    // -- fixtures --------------------------------------------------------------

    private static Post post(String uid, UUID sourceId, List<String> searchTags) {
        return new Post(UUID.nameUUIDFromBytes((PREFIX + uid).getBytes()), PREFIX + uid,
                sourceId, "Source " + sourceId, "Title " + uid,
                "https://example.com/" + uid, "Body " + uid + ".", Instant.now(),
                List.of("ai"), List.of("factual"),
                null, null, null, null, null, null, null, null, searchTags);
    }

    private static final class RecordingEligiblePostQuery extends EligiblePostQuery {
        Duration capturedWindow;
        private Result seeded =
                new Result(List.of(), 0, 0, 200, "laptop", Optional.empty());
        private Result drillSeeded =
                new Result(List.of(), 0, 0, 200, "laptop", Optional.empty());

        void seedPosts(List<Post> posts) {
            seeded = new Result(posts, posts.size(), 0, 200, "laptop", Optional.empty());
        }

        void seedDrillPosts(List<Post> posts) {
            drillSeeded = new Result(posts, posts.size(), 0, 200, "laptop", Optional.empty());
        }

        @Override
        public Result fetch(String scopeKind, UUID scopeId,
                            Optional<String> positionalTag, Duration window) {
            capturedWindow = window;
            return seeded;
        }

        @Override
        public Result fetchByTopicPrefix(String scopeKind, UUID scopeId,
                                         String prefix, Duration window) {
            capturedWindow = window;
            return drillSeeded;
        }

        @Override
        public List<String> readVocabulary() {
            return List.of();
        }

        @Override
        public int countWorldSources(String scopeKind, UUID scopeId) {
            return 1;
        }
    }

    private static final class RecordingCacheRepository extends SummaryCacheRepository {
        Optional<Instant> boundary = Optional.empty();

        @Override
        public Optional<Instant> findPreviousBoundary(UUID groupId, Instant before) {
            return boundary;
        }
    }

    private static final class RecordingProseGenerator extends SummaryProseGenerator {
        int generateCalls;

        @Override
        public List<ClusterProse> generate(List<Cluster> clusters, String scopeLanguage) {
            generateCalls++;
            return List.of();
        }
    }
}
