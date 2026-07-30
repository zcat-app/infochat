package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.CancellationService;
import app.zcat.infochat.provider.chat.InFlightTracker;
import app.zcat.infochat.provider.chat.LlmRateCap;
import app.zcat.infochat.provider.chat.SummaryAnchorRepository;
import app.zcat.infochat.provider.chat.SummaryAnchorRepository.AnchorRow;
import app.zcat.infochat.provider.digest.CategoryRollupGenerator;
import app.zcat.infochat.provider.digest.DigestRenderer;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.summary.SummaryProseGenerator;
import app.zcat.infochat.provider.summary.SummaryProseGenerator.ClusterProse;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import app.zcat.infochat.provider.user.UserRepository;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static app.zcat.infochat.provider.testsupport.TranslationFixtures.newEnShortCircuitPipeline;
import static app.zcat.infochat.provider.testsupport.TranslationFixtures.newRealBundleLoader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryCommandHandlerTest {

    private static final String PREFIX = "m1-065-retry-";
    private static final UUID USER_ID = UUID.randomUUID();

    private RetryCommandHandler handler;
    private StubAnchorRepository anchorRepo;
    private RecordingProseGenerator proseGenerator;
    private RecordingCategoryRollupGenerator rollupGenerator;
    private InFlightTracker tracker;

    @BeforeEach
    void setUp() throws Exception {
        anchorRepo = new StubAnchorRepository();
        proseGenerator = new RecordingProseGenerator();
        tracker = new InFlightTracker();

        CancellationService cancellationService = new CancellationService();
        java.lang.reflect.Field timeoutField = CancellationService.class.getDeclaredField("statementTimeout");
        timeoutField.setAccessible(true);
        timeoutField.set(cancellationService, Duration.ofSeconds(30));

        handler = new RetryCommandHandler();
        BundleLoader bundleLoader = newRealBundleLoader();
        handler.bundleLoader = bundleLoader;
        handler.cancellationService = cancellationService;
        handler.dataSource = stubUserAndPostsDataSource(USER_ID, List.of());
        handler.userRepository = new UserRepository(
                stubUserAndPostsDataSource(USER_ID, List.of()));
        handler.summaryAnchorRepository = anchorRepo;
        handler.summaryProseGenerator = proseGenerator;
        handler.llmOutputSanitizer = SanitizerTestDoubles.noAuditSanitizer();
        handler.translationPipeline = newEnShortCircuitPipeline(bundleLoader);
        // The default (categorized) replay form runs inside DigestRenderer
        // (M1-696), so the renderer must hold THIS test's sanitizer and
        // pipeline — same wiring rule as SummaryCommandHandlerTest. The cap
        // values mirror the production @ConfigProperty defaults that manual
        // field injection does not apply. The 6-arg seam wires a
        // RecordingCategoryRollupGenerator so the --short replay test can
        // assert the roll-up call count; the flat/bare/full tests never
        // reach renderShortBody so it is inert for them.
        rollupGenerator = new RecordingCategoryRollupGenerator();
        handler.digestRenderer = DigestRenderer.forSummaryRendering(
                SanitizerTestDoubles.noAuditSanitizer(),
                handler.translationPipeline, bundleLoader,
                /* categoryItemCap */ 12, /* categoryMinClusters */ 3,
                rollupGenerator);
        handler.inFlightTracker = tracker;
        handler.llmRateCap = new LlmRateCap(10);
        handler.retryCap = 3;
        handler.statusDriftThreshold = 0.25;
        InboundContext ctx = new InboundContext();
        ctx.setAdapterName("inmemory");
        handler.inboundContext = ctx;
    }

    @Test
    void handlerNameIsLiteralRetry() {
        assertEquals("retry", handler.name());
    }

    @Test
    void retriesFromAnchor() {
        List<String> postUids = List.of(PREFIX + "r1");
        String clusterMapJson = "[{\"topicId\":\"t-abc\",\"postUids\":[\"" + postUids.get(0) + "\"]}]";
        anchorRepo.seedAnchor(USER_ID, USER_ID, "summary", "bare", "hash", postUids, clusterMapJson);

        // Stub the DataSource to return the posts as READY
        Post readyPost = post(PREFIX + "r1", "Retry headline", Instant.now());
        handler.dataSource = stubUserAndPostsDataSource(USER_ID, List.of(readyPost));
        proseGenerator.responseText = "Retried prose.";

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(PREFIX + "anchor"), "/retry");

        assertTrue(proseGenerator.callCount > 0,
                "prose generator must be called for the retry");
        assertTrue(reply.text().contains("Retried prose."),
                "reply must contain the re-generated prose. Got: " + reply.text());
    }

    @Test
    void rejectsWhenCapExhausted() {
        List<String> postUids = List.of(PREFIX + "cap1");
        anchorRepo.seedAnchor(USER_ID, USER_ID, "summary", "bare", "hash", postUids, null);

        // Provide READY posts so handler reaches the cap check
        Post readyPost = post(PREFIX + "cap1", "Cap headline", Instant.now());
        handler.dataSource = stubUserAndPostsDataSource(USER_ID, List.of(readyPost));

        // Exhaust the cap (3 retries)
        anchorRepo.incrementAndGetRetryCount(USER_ID, "dm", USER_ID);
        anchorRepo.incrementAndGetRetryCount(USER_ID, "dm", USER_ID);
        anchorRepo.incrementAndGetRetryCount(USER_ID, "dm", USER_ID);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(PREFIX + "cap"), "/retry");

        assertTrue(reply.text().contains("Retry limit reached"),
                "reply must indicate cap exhausted. Got: " + reply.text());
        assertEquals(0, proseGenerator.callCount,
                "prose generator must NOT be called when cap is exhausted");
    }

    @Test
    void atCapRetryConsumesNeitherCounterGrowthNorLlmToken() {
        // U-42 residual: an at-cap /retry must read-then-check the cap
        // BEFORE incrementing or spending an LLM token, so the monotonic
        // counter does not grow past the cap and no token is burned on a
        // re-roll the cap already forbids.
        List<String> postUids = List.of(PREFIX + "atcap1");
        String json = "[{\"topicId\":\"t-atcap\",\"postUids\":[\"" + postUids.get(0) + "\"]}]";
        anchorRepo.seedAnchor(USER_ID, USER_ID, "summary", "bare", "hash", postUids, json);
        Post readyPost = post(postUids.get(0), "AtCap headline", Instant.now());
        handler.dataSource = stubUserAndPostsDataSource(USER_ID, List.of(readyPost));

        // Drive the counter to exactly the cap (3).
        anchorRepo.incrementAndGetRetryCount(USER_ID, "dm", USER_ID);
        anchorRepo.incrementAndGetRetryCount(USER_ID, "dm", USER_ID);
        anchorRepo.incrementAndGetRetryCount(USER_ID, "dm", USER_ID);

        // A single-token bucket: if the at-cap retry spent the token, the
        // tryAcquire assertion below would fail.
        LlmRateCap singleToken = new LlmRateCap(1);
        handler.llmRateCap = singleToken;

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "atcap"), "/retry");

        assertTrue(reply.text().contains("Retry limit reached"),
                "at-cap /retry must get the cap-exhausted reply. Got: " + reply.text());
        assertEquals(0, proseGenerator.callCount,
                "at-cap /retry must make no LLM re-roll");
        assertEquals(3, anchorRepo.incrementCallCount,
                "at-cap /retry must NOT increment the counter — only the three "
                        + "setup increments ran");
        assertEquals(3, anchorRepo.peekRetryCount(USER_ID, "dm", USER_ID),
                "the retry counter must stay pinned at the cap, never growing past it");
        assertTrue(singleToken.tryAcquire(USER_ID),
                "the at-cap retry must NOT have spent the LLM token — it is still available");
    }

    @Test
    void filtersNonReadyUids() {
        // Anchor has 4 UIDs but only 1 is READY (the rest are filtered out).
        // 3/4 = 75% drift > 25% threshold, so the drift notice must appear.
        List<String> postUids = List.of(
                PREFIX + "f1", PREFIX + "f2",
                PREFIX + "f3", PREFIX + "f4");
        String json = "[{\"topicId\":\"t-x\",\"postUids\":[\""
                + postUids.get(0) + "\",\"" + postUids.get(1) + "\",\""
                + postUids.get(2) + "\",\"" + postUids.get(3) + "\"]}]";
        anchorRepo.seedAnchor(USER_ID, USER_ID, "summary", "bare", "hash", postUids, json);

        // Only one post is READY
        Post readyPost = post(postUids.get(0), "Surviving post", Instant.now());
        handler.dataSource = stubUserAndPostsDataSource(USER_ID, List.of(readyPost));
        proseGenerator.responseText = "Filtered retry.";

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(PREFIX + "filter"), "/retry");

        assertTrue(reply.text().contains("3 of 4"),
                "drift notice must cite excluded/original counts. Got: " + reply.text());
        assertTrue(reply.text().contains("Filtered retry."),
                "reply must contain the prose for surviving posts. Got: " + reply.text());
    }

    @Test
    void filtersToEmptyReturnsError() {
        List<String> postUids = List.of(PREFIX + "gone1");
        anchorRepo.seedAnchor(USER_ID, USER_ID, "summary", "bare", "hash", postUids, null);

        // No posts are READY
        handler.dataSource = stubUserAndPostsDataSource(USER_ID, List.of());

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(PREFIX + "empty"), "/retry");

        assertTrue(reply.text().contains("no longer available"),
                "reply must indicate no eligible posts. Got: " + reply.text());
        assertEquals(0, proseGenerator.callCount,
                "prose generator must NOT be called when all UIDs filtered out");
        assertEquals(0, anchorRepo.incrementCallCount,
                "retry counter must not increment when all posts are filtered out");
    }

    @Test
    void noAnchorReturnsError() {
        // No anchor seeded
        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(PREFIX + "noanchor"), "/retry");

        assertTrue(reply.text().contains("Nothing to retry"),
                "reply must indicate no anchor. Got: " + reply.text());
        assertEquals(0, proseGenerator.callCount);
    }

    @Test
    void outputPassesThroughSanitizer() {
        List<String> postUids = List.of(PREFIX + "s1");
        String json = "[{\"topicId\":\"t-san\",\"postUids\":[\"" + postUids.get(0) + "\"]}]";
        anchorRepo.seedAnchor(USER_ID, USER_ID, "summary", "bare", "hash", postUids, json);

        Post readyPost = post(PREFIX + "s1", "San headline", Instant.now());
        handler.dataSource = stubUserAndPostsDataSource(USER_ID, List.of(readyPost));
        // The LLM output contains a privileged command
        proseGenerator.responseText = "Run /grant-admin to escalate.";

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(PREFIX + "san"), "/retry");

        assertFalse(reply.text().contains("/grant-admin"),
                "sanitizer must strip /grant-admin. Got: " + reply.text());
        assertTrue(reply.text().contains("[redacted command]"),
                "sanitizer must replace with [redacted command]. Got: " + reply.text());
    }

    // ----- M1-699: render_form column is the /retry dispatch axis --------
    //
    // Pre-M1-699 the form was recovered by string-matching command_name
    // (RetryCommandHandler.isFullFormAnchor did hasFlag(commandName,"--full")).
    // M1-699 moves the dispatch axis to a typed column; these tests seed
    // render_form on the anchor row directly (not via command_name matching).

    @Test
    void fullFormAnchorReplaysFlatBlocks() {
        List<String> postUids = List.of(PREFIX + "full1");
        String json = "[{\"topicId\":\"t-full\",\"postUids\":[\"" + postUids.get(0) + "\"]}]";
        anchorRepo.seedAnchor(USER_ID, USER_ID, "summary --full", "flat", "hash", postUids, json);

        Post readyPost = post(PREFIX + "full1", "Full-form headline", Instant.now());
        handler.dataSource = stubUserAndPostsDataSource(USER_ID, List.of(readyPost));
        proseGenerator.responseText = "Full-form prose.";

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(PREFIX + "full"), "/retry");

        assertTrue(reply.text().contains("[topic_id=t-full]"),
                "a render_form='flat' anchor must replay the flat per-cluster blocks. Got: " + reply.text());
        assertTrue(reply.text().contains("Full-form headline"),
                "the flat form renders the headline. Got: " + reply.text());
        assertTrue(reply.text().contains("Full-form prose."),
                "the flat form renders the re-generated prose. Got: " + reply.text());
    }

    @Test
    void defaultAnchorReplaysCategorized() {
        List<String> postUids = List.of(PREFIX + "cat1");
        String json = "[{\"topicId\":\"t-cat\",\"postUids\":[\"" + postUids.get(0) + "\"]}]";
        anchorRepo.seedAnchor(USER_ID, USER_ID, "summary", "bare", "hash", postUids, json);

        Post readyPost = post(PREFIX + "cat1", "Categorized headline", Instant.now());
        handler.dataSource = stubUserAndPostsDataSource(USER_ID, List.of(readyPost));
        proseGenerator.responseText = "Categorized prose.";

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(PREFIX + "cat"), "/retry");

        // A single cluster falls into the Other bucket (categoryMinClusters
        // is wired to the production default 3 in setUp).
        assertTrue(reply.text().contains("OTHER NEWS"),
                "a render_form='bare' anchor must replay the categorized sections. Got: " + reply.text());
        assertTrue(reply.text().contains("Categorized prose."),
                "the categorized form renders the re-generated prose. Got: " + reply.text());
        assertFalse(reply.text().contains("[topic_id="),
                "the categorized form renders no flat cluster blocks. Got: " + reply.text());
    }

    @Test
    void replaysFromRenderFormColumnNotCommandName() {
        // The dispatch axis moved from command_name string-matching to the
        // typed render_form column (M1-699). Prove render_form WINS over
        // command_name by seeding MISMATCHED pairs: a command_name that
        // legacy string-matching would read as one form, with a render_form
        // that says the other. The replay must follow render_form in both
        // directions, and the replayed bytes are byte-identical to the
        // pre-refactor output for each form (the form shapes did not move
        // — only the dispatch axis did).
        //
        // Flat direction: command_name carries NO --full, but render_form='flat'.
        List<String> flatUids = List.of(PREFIX + "rf-flat");
        String flatJson = "[{\"topicId\":\"t-rf-flat\",\"postUids\":[\"" + flatUids.get(0) + "\"]}]";
        anchorRepo.seedAnchor(USER_ID, USER_ID, "summary", "flat", "hash", flatUids, flatJson);
        Post flatPost = post(PREFIX + "rf-flat", "RF flat headline", Instant.now());
        handler.dataSource = stubUserAndPostsDataSource(USER_ID, List.of(flatPost));
        proseGenerator.responseText = "RF flat prose.";
        OutboundMessage flatReply = handler.handle(new ScopeRef.Dm(PREFIX + "rf-flat"), "/retry");
        assertTrue(flatReply.text().contains("[topic_id=t-rf-flat]"),
                "render_form='flat' must win over a non-'--full' command_name and replay flat. Got: " + flatReply.text());
        assertFalse(flatReply.text().contains("OTHER NEWS"),
                "render_form='flat' must not fall through to categorized despite the bare command_name. Got: " + flatReply.text());

        // Bare direction: command_name carries --full, but render_form='bare'.
        List<String> bareUids = List.of(PREFIX + "rf-bare");
        String bareJson = "[{\"topicId\":\"t-rf-bare\",\"postUids\":[\"" + bareUids.get(0) + "\"]}]";
        anchorRepo.seedAnchor(USER_ID, USER_ID, "summary --full", "bare", "hash", bareUids, bareJson);
        Post barePost = post(PREFIX + "rf-bare", "RF bare headline", Instant.now());
        handler.dataSource = stubUserAndPostsDataSource(USER_ID, List.of(barePost));
        proseGenerator.responseText = "RF bare prose.";
        OutboundMessage bareReply = handler.handle(new ScopeRef.Dm(PREFIX + "rf-bare"), "/retry");
        assertTrue(bareReply.text().contains("OTHER NEWS"),
                "render_form='bare' must win over a '--full' command_name and replay categorized. Got: " + bareReply.text());
        assertFalse(bareReply.text().contains("[topic_id="),
                "render_form='bare' must not replay flat despite the --full command_name. Got: " + bareReply.text());
    }

    @Test
    void oldAnchorBackfilledToFlatForm() {
        // V65 backfills render_form from command_name: LIKE '%--full%' ⇒
        // 'flat', else ⇒ 'bare' (SummaryAnchorRenderFormBackfillIT proves the
        // migration itself). This test proves /retry reads the BACKFILLED
        // column: a pre-V65 anchor whose command_name contains '--full' is
        // backfilled to render_form='flat' and replays flat; a pre-V65
        // anchor with command_name 'summary' or '/summary' backfills to
        // 'bare' and replays categorized. The leading-slash '/summary'
        // variant is the exact unnormalized fragility the typed column
        // retires (OutboundDeliveryCleanupIT, ChatMemoryPrunerTest).
        List<String> fullUids = List.of(PREFIX + "bf-full");
        String fullJson = "[{\"topicId\":\"t-bf-full\",\"postUids\":[\"" + fullUids.get(0) + "\"]}]";
        anchorRepo.seedAnchor(USER_ID, USER_ID, "summary --full", "flat", "hash", fullUids, fullJson);
        Post fullPost = post(PREFIX + "bf-full", "Backfill flat headline", Instant.now());
        handler.dataSource = stubUserAndPostsDataSource(USER_ID, List.of(fullPost));
        proseGenerator.responseText = "Backfill flat prose.";
        OutboundMessage fullReply = handler.handle(new ScopeRef.Dm(PREFIX + "bf-full"), "/retry");
        assertTrue(fullReply.text().contains("[topic_id=t-bf-full]"),
                "a backfilled '--full' anchor (render_form='flat') must replay flat. Got: " + fullReply.text());

        List<String> bareUids = List.of(PREFIX + "bf-bare");
        String bareJson = "[{\"topicId\":\"t-bf-bare\",\"postUids\":[\"" + bareUids.get(0) + "\"]}]";
        // The leading-slash '/summary' variant backfills to 'bare'.
        anchorRepo.seedAnchor(USER_ID, USER_ID, "/summary", "bare", "hash", bareUids, bareJson);
        Post barePost = post(PREFIX + "bf-bare", "Backfill bare headline", Instant.now());
        handler.dataSource = stubUserAndPostsDataSource(USER_ID, List.of(barePost));
        proseGenerator.responseText = "Backfill bare prose.";
        OutboundMessage bareReply = handler.handle(new ScopeRef.Dm(PREFIX + "bf-bare"), "/retry");
        assertTrue(bareReply.text().contains("OTHER NEWS"),
                "a backfilled '/summary' anchor (render_form='bare') must replay categorized. Got: " + bareReply.text());
        assertFalse(bareReply.text().contains("[topic_id="),
                "a backfilled '/summary' anchor must not replay flat. Got: " + bareReply.text());
    }

    // ----- M1-700: --short and --full replay arms ------------------------

    /**
     * Acceptance item 5 — {@code /retry} against a {@code render_form='short'}
     * anchor re-runs CategoryRollupGenerator per category and emits NO
     * per-cluster prose (SummaryProseGenerator is not called on this replay
     * path). The re-rolled roll-up is a fresh LLM generation over the same
     * anchored cluster set.
     */
    @Test
    void shortAnchorReplaysRollupNotClusterProse() {
        List<String> postUids = List.of(PREFIX + "sh1");
        String json = "[{\"topicId\":\"t-sh\",\"postUids\":[\"" + postUids.get(0) + "\"]}]";
        anchorRepo.seedAnchor(USER_ID, USER_ID, "summary --short", "short", "hash", postUids, json);

        Post readyPost = post(PREFIX + "sh1", "Short-replay headline", Instant.now());
        handler.dataSource = stubUserAndPostsDataSource(USER_ID, List.of(readyPost));
        proseGenerator.responseText = "Should NOT be called on --short replay.";
        rollupGenerator.responseText = "Short replay roll-up.";

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(PREFIX + "shretry"), "/retry");

        assertEquals(0, proseGenerator.callCount,
                "a 'short' anchor replay must NOT call SummaryProseGenerator. Got calls: "
                        + proseGenerator.callCount);
        assertEquals(1, rollupGenerator.callCount(),
                "a 'short' anchor replay re-runs CategoryRollupGenerator once per category "
                        + "(one Other bucket here). Got: " + rollupGenerator.callCount());
        assertTrue(reply.text().contains("Short replay roll-up."),
                "the --short replay must carry the re-rolled roll-up line. Got: " + reply.text());
        assertFalse(reply.text().contains("[topic_id="),
                "the --short replay emits NO flat cluster blocks. Got: " + reply.text());
        assertFalse(reply.text().contains("Should NOT be called"),
                "the --short replay must not leak per-cluster prose. Got: " + reply.text());
    }

    /**
     * Redteam M1-700 kimi r1 — {@code /retry} against a {@code short} anchor
     * during a summarizer outage must emit the D43 degraded_notice, not a
     * silent wall of empty headers (mirrors the /summary --short path).
     */
    @Test
    void shortAnchorReplayEmitsDegradedNoticeWhenRollupFails() {
        List<String> postUids = List.of(PREFIX + "shd1");
        String json = "[{\"topicId\":\"t-shd\",\"postUids\":[\"" + postUids.get(0) + "\"]}]";
        anchorRepo.seedAnchor(USER_ID, USER_ID, "summary --short", "short", "hash", postUids, json);

        Post readyPost = post(PREFIX + "shd1", "Short-degraded headline", Instant.now());
        handler.dataSource = stubUserAndPostsDataSource(USER_ID, List.of(readyPost));
        proseGenerator.responseText = "Should NOT be called.";
        rollupGenerator.setReturnEmpty(true);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(PREFIX + "shdretry"), "/retry");

        assertEquals(0, proseGenerator.callCount,
                "a 'short' anchor replay must NOT call SummaryProseGenerator");
        assertTrue(reply.text().contains("LLM is unreachable"),
                "the --short replay must prefix the degraded_notice on roll-up failure. Got: "
                        + reply.text());
        // The D17 degraded FORM half (redteam r2): the deterministic
        // headline must render, not just an empty header.
        assertTrue(reply.text().contains("Short-degraded headline"),
                "the --short replay degraded path must render the post headline (D17 form). Got: "
                        + reply.text());
    }

    /**
     * M1-703 acceptance item 3 — {@code /retry} against a per-cluster anchor
     * (bare/full/flat) with SOME clusters degraded on the re-roll must NOT
     * claim total degradation. The replay mirrors the {@code /summary}
     * partial-vs-total distinction so {@code /retry} never contradicts the
     * {@code /summary} the user just saw.
     */
    @Test
    void perClusterReplayPartialDegradationShowsPartialNoticeNotTotalNotice() {
        // 3 clusters in the anchored map; the re-roll degrades only the first.
        List<String> postUids = new ArrayList<>();
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < 3; i++) {
            String uid = PREFIX + "pcrep" + i;
            postUids.add(uid);
            if (i > 0) json.append(",");
            json.append("{\"topicId\":\"t-pcrep").append(i).append("\",\"postUids\":[\"").append(uid).append("\"]}");
        }
        json.append("]");
        anchorRepo.seedAnchor(USER_ID, USER_ID, "summary", "bare", "hash", postUids, json.toString());

        Instant now = Instant.now();
        List<Post> readyPosts = new ArrayList<>();
        for (int i = 0; i < postUids.size(); i++) {
            String uid = postUids.get(i);
            readyPosts.add(post(uid, "Replay headline " + uid, now.minus(Duration.ofMinutes(i + 1L))));
        }
        handler.dataSource = stubUserAndPostsDataSource(USER_ID, readyPosts);
        proseGenerator.responseText = "Healthy replay prose.";
        proseGenerator.setDegradeFirstN(1);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(PREFIX + "pcretry"), "/retry");

        assertEquals(3, proseGenerator.callCount,
                "the per-cluster replay re-rolls prose for all 3 anchored clusters");

        String text = reply.text();
        assertFalse(text.contains("no prose"),
                "a partial per-cluster replay must NOT claim 'no prose'. Got: " + text);
        assertFalse(text.contains("LLM is unreachable"),
                "a partial per-cluster replay must NOT prefix the total degraded_notice. Got: " + text);
        assertTrue(text.contains("1 of 3 topics"),
                "a partial per-cluster replay must name the degraded subset honestly. Got: " + text);
        assertTrue(text.contains("Healthy replay prose."),
                "the healthy clusters carry their re-rolled prose. Got: " + text);
    }

    /**
     * M1-703 acceptance item 3 ({@code --short} arm) — {@code /retry} against
     * a {@code short} anchor with SOME categories' roll-ups failing on the
     * re-roll must NOT claim total degradation. Mirrors the {@code /summary
     * --short} partial-vs-total distinction so the replay stays in lockstep
     * with the original {@code /summary}.
     */
    @Test
    void shortReplayPartialDegradationShowsPartialNoticeNotTotalNotice() {
        // Two categories (alpha + beta), each with 3 clusters. The cluster
        // map carries all 6; the re-roll fails only the first roll-up call.
        List<String> postUids = new ArrayList<>();
        StringBuilder json = new StringBuilder("[");
        String[] tags = {PREFIX + "alpha", PREFIX + "beta"};
        for (int t = 0; t < tags.length; t++) {
            for (int i = 0; i < 3; i++) {
                String uid = PREFIX + "shr" + t + i;
                postUids.add(uid);
                if (!(t == 0 && i == 0)) json.append(",");
                json.append("{\"topicId\":\"t-shr").append(t).append(i)
                        .append("\",\"postUids\":[\"").append(uid).append("\"]}");
            }
        }
        json.append("]");
        anchorRepo.seedAnchor(USER_ID, USER_ID, "summary --short", "short", "hash", postUids, json.toString());

        Instant now = Instant.now();
        List<Post> readyPosts = new ArrayList<>();
        int idx = 0;
        int minOffset = 0;
        for (String tag : tags) {
            for (int i = 0; i < 3; i++) {
                String uid = postUids.get(idx++);
                readyPosts.add(post(uid, "Short-replay headline " + uid,
                        now.minus(Duration.ofMinutes(++minOffset)), List.of(tag)));
            }
        }
        handler.dataSource = stubUserAndPostsDataSource(USER_ID, readyPosts);
        proseGenerator.responseText = "Should NOT be called on --short replay.";
        rollupGenerator.setResponseText("Roll-up replay synthesis.");
        // Fail ONLY the first category's roll-up; the second succeeds.
        rollupGenerator.setReturnEmptyForFirstCalls(1);

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(PREFIX + "shrretry"), "/retry");

        assertEquals(0, proseGenerator.callCount,
                "a 'short' anchor replay must NOT call SummaryProseGenerator");
        assertEquals(2, rollupGenerator.callCount(),
                "one roll-up call per category (alpha + beta). Got: " + rollupGenerator.callCount());

        String text = reply.text();
        assertFalse(text.contains("no prose"),
                "a partial --short replay must NOT claim 'no prose'. Got: " + text);
        assertFalse(text.contains("LLM is unreachable"),
                "a partial --short replay must NOT prefix the total degraded_notice. Got: " + text);
        assertTrue(text.contains("3 of 6 topics"),
                "a partial --short replay must name the degraded subset honestly. Got: " + text);
        assertTrue(text.contains("Roll-up replay synthesis."),
                "the successful category still carries its roll-up. Got: " + text);
    }

    /**
     * Acceptance item 6 — {@code /retry} against a {@code render_form='full'}
     * anchor replays categorized sections with ALL clusters (no 12-cap),
     * matching the {@code /summary --full} shape. 14 clusters exceed
     * categoryItemCap (12): a {@code bare} replay would cap at 12 and emit
     * "+2 more stories", but a {@code full} replay renders all 14 with no
     * overflow line.
     */
    @Test
    void fullAnchorReplaysCategorizedUncapped() {
        // 14 singleton clusters all sharing test-tag → one category of 14
        // clusters (> categoryItemCap 12). The cluster map carries all 14.
        List<String> postUids = new ArrayList<>();
        List<Post> readyPosts = new ArrayList<>();
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < 14; i++) {
            String uid = PREFIX + "fu" + i;
            postUids.add(uid);
            readyPosts.add(post(uid, "Full-replay headline " + i, Instant.now()));
            if (i > 0) json.append(",");
            json.append("{\"topicId\":\"t-").append(uid).append("\",\"postUids\":[\"").append(uid).append("\"]}");
        }
        json.append("]");
        anchorRepo.seedAnchor(USER_ID, USER_ID, "summary --full", "full", "hash", postUids, json.toString());

        handler.dataSource = stubUserAndPostsDataSource(USER_ID, readyPosts);
        proseGenerator.responseText = "Full-replay prose.";

        OutboundMessage reply = handler.handle(
                new ScopeRef.Dm(PREFIX + "fulretry"), "/retry");

        assertEquals(14, proseGenerator.callCount,
                "a 'full' anchor replay generates per-cluster prose for ALL 14 clusters, not 12");
        assertEquals(0, rollupGenerator.callCount(),
                "a 'full' anchor replay does not call the roll-up generator");
        assertFalse(reply.text().contains("more stories"),
                "a 'full' anchor replay emits NO '+N more' overflow line (cap skipped). Got: "
                        + reply.text());
        assertFalse(reply.text().contains("[topic_id="),
                "a 'full' anchor replay is categorized, not flat. Got: " + reply.text());
        assertTrue(reply.text().contains("Full-replay prose."),
                "a 'full' anchor replay carries the re-generated per-cluster prose. Got: " + reply.text());
    }

    // ----- M1-183: LLM rate cap + in-flight no-leak --------------------
    //
    // Per docs/spec/security.md §Rate limiting, /retry re-rolls draw
    // from the same per-user LLM bucket as chat replies and /summary.

    @Test
    void retryRejectedWithRateLimitReplyWhenLlmBucketExhausted() {
        List<String> postUids = List.of(PREFIX + "rl1");
        String json = "[{\"topicId\":\"t-rl\",\"postUids\":[\"" + postUids.get(0) + "\"]}]";
        anchorRepo.seedAnchor(USER_ID, USER_ID, "summary", "bare", "hash", postUids, json);
        Post readyPost = post(postUids.get(0), "Rate headline", Instant.now());
        handler.dataSource = stubUserAndPostsDataSource(USER_ID, List.of(readyPost));
        // Exhaust the per-user LLM bucket: a single-token bucket whose
        // token is already taken for this user.
        LlmRateCap exhausted = new LlmRateCap(1);
        assertTrue(exhausted.tryAcquire(USER_ID), "drain the bucket's only token");
        handler.llmRateCap = exhausted;

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "rl"), "/retry");

        assertTrue(reply.text().contains("too quickly"),
                "rate-capped /retry must get the rate-limit reply. Got: " + reply.text());
        assertEquals(0, proseGenerator.callCount,
                "rate-capped /retry must make no LLM re-roll");
        assertEquals(0, anchorRepo.incrementCallCount,
                "a rate-cap rejection must not burn one of the anchor's retry slots");
        assertFalse(tracker.isInFlight(USER_ID, "dm", USER_ID),
                "rate-cap rejection must not leave the in-flight slot held");
    }

    @Test
    void rejectedRetryLeavesSlotAndBucketUsableForNextRequest() {
        List<String> postUids = List.of(PREFIX + "nl1");
        String json = "[{\"topicId\":\"t-nl\",\"postUids\":[\"" + postUids.get(0) + "\"]}]";
        anchorRepo.seedAnchor(USER_ID, USER_ID, "summary", "bare", "hash", postUids, json);
        Post readyPost = post(postUids.get(0), "NoLeak headline", Instant.now());
        handler.dataSource = stubUserAndPostsDataSource(USER_ID, List.of(readyPost));
        proseGenerator.responseText = "Recovered re-roll.";
        // Single-token bucket: the follow-up request below only succeeds
        // if the rejected one consumed nothing from it.
        handler.llmRateCap = new LlmRateCap(1);
        InFlightTracker.CancellationHandle slot = tracker.tryAcquire(USER_ID, "dm", USER_ID);
        assertNotNull(slot, "occupy the slot");

        handler.handle(new ScopeRef.Dm(PREFIX + "nl"), "/retry");
        assertEquals(0, proseGenerator.callCount,
                "the in-flight-busy rejection must make no LLM re-roll");
        assertEquals(0, anchorRepo.incrementCallCount,
                "a busy rejection must not burn one of the anchor's retry slots");

        tracker.release(USER_ID, "dm", USER_ID, slot); // the first request finishes
        OutboundMessage ok = handler.handle(new ScopeRef.Dm(PREFIX + "nl"), "/retry");

        assertTrue(ok.text().contains("Recovered re-roll."),
                "the next permitted request must succeed — the rejection "
                        + "consumed neither the slot nor the single bucket token. Got: "
                        + ok.text());
        assertFalse(tracker.isInFlight(USER_ID, "dm", USER_ID),
                "the successful re-roll must release its slot");
    }

    // ----- M1-218: busy /retry names the in-flight condition ----------------

    @Test
    void retryWhileRequestInFlightRepliesWithInFlightMessageNotNoAnchor() throws Exception {
        List<String> postUids = List.of(PREFIX + "if1");
        String json = "[{\"topicId\":\"t-if\",\"postUids\":[\"" + postUids.get(0) + "\"]}]";
        anchorRepo.seedAnchor(USER_ID, USER_ID, "summary", "bare", "hash", postUids, json);
        Post readyPost = post(postUids.get(0), "InFlight headline", Instant.now());
        handler.dataSource = stubUserAndPostsDataSource(USER_ID, List.of(readyPost));
        // The caller's previous request is still running.
        assertNotNull(tracker.tryAcquire(USER_ID, "dm", USER_ID), "occupy the slot");

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "if"), "/retry");

        BundleLoader bundles = newRealBundleLoader();
        assertEquals(bundles.get(BundleKeys.ERROR_RETRY_IN_FLIGHT), reply.text(),
                "busy /retry must name the in-flight condition");
        assertNotEquals(bundles.get(BundleKeys.ERROR_RETRY_NO_ANCHOR), reply.text(),
                "busy /retry must be distinguishable from the nothing-to-retry case");
        assertEquals(0, proseGenerator.callCount,
                "busy /retry must make no LLM re-roll");
    }

    // ----- fixtures + stubs ------------------------------------------------

    private static Post post(String uid, String title, Instant publishedAt) {
        return new Post(
                UUID.randomUUID(), uid, UUID.randomUUID(), "TestSrc",
                title, "https://example.com/" + uid, "Body for " + title,
                publishedAt, List.of("test-tag"), List.of("unknown"));
    }

    /** Tag-parameterized variant so the M1-703 --short replay test can seed distinct category tags. */
    private static Post post(String uid, String title, Instant publishedAt, List<String> tags) {
        return new Post(
                UUID.randomUUID(), uid, UUID.randomUUID(), "TestSrc",
                title, "https://example.com/" + uid, "Body for " + title,
                publishedAt, tags, List.of("unknown"));
    }

    private static class StubAnchorRepository extends SummaryAnchorRepository {
        private AnchorRow seeded = null;
        int incrementCallCount = 0;

        void seedAnchor(UUID userId, UUID scopeId, String commandName, String renderForm,
                        String argHash, List<String> postUids, String clusterMapJson) {
            seeded = new AnchorRow(userId, scopeId, commandName, renderForm, argHash,
                    postUids, clusterMapJson, Instant.now());
        }

        @Override
        public int incrementAndGetRetryCount(UUID userId, String scopeKind, UUID scopeId) {
            incrementCallCount++;
            return super.incrementAndGetRetryCount(userId, scopeKind, scopeId);
        }

        @Override
        public void write(UUID userId, String scopeKind, UUID scopeId, String commandName,
                          String renderForm, String argHash,
                          List<String> postUids, String clusterMapJson) {
            seeded = new AnchorRow(userId, scopeId, commandName, renderForm, argHash,
                    postUids, clusterMapJson, Instant.now());
            clearRetryCount(userId, scopeKind, scopeId);
        }

        @Override
        public Optional<AnchorRow> read(UUID userId, String scopeKind, UUID scopeId) {
            return Optional.ofNullable(seeded);
        }

        @Override
        public void clear(UUID userId, String scopeKind, UUID scopeId) {
            seeded = null;
            clearRetryCount(userId, scopeKind, scopeId);
        }
    }

    private static class RecordingProseGenerator extends SummaryProseGenerator {
        int callCount = 0;
        String responseText = "default retry prose";
        // M1-703: degrade only the first N clusters (partial outage).
        int degradeFirstN = 0;

        void setDegradeFirstN(int n) { this.degradeFirstN = n; }

        @Override
        public List<ClusterProse> generate(List<Cluster> clusters, String scopeLanguage) {
            List<ClusterProse> out = new ArrayList<>();
            for (int i = 0; i < clusters.size(); i++) {
                Cluster c = clusters.get(i);
                callCount++;
                boolean degraded = degradeFirstN > 0 && i < degradeFirstN;
                out.add(new ClusterProse(c, degraded ? "degraded replay prose" : responseText, degraded));
            }
            return out;
        }
    }

    /**
     * Recording stub for {@link CategoryRollupGenerator}: the {@code --short}
     * replay path calls {@link CategoryRollupGenerator#generateRollup}
     * per category, so this stub counts calls and returns a fixed synthesis
     * string. Inert for the flat/bare/full replay tests (they never reach
     * renderShortBody).
     */
    private static final class RecordingCategoryRollupGenerator extends CategoryRollupGenerator {
        private final AtomicInteger callCount = new AtomicInteger();
        private final List<Integer> clusterCounts = new CopyOnWriteArrayList<>();
        private volatile String responseText = "roll-up synthesis";
        // Simulate a summarizer outage (redteam M1-700 kimi r1).
        private volatile boolean returnEmpty = false;
        // M1-703: return empty for only the first N roll-up calls (partial).
        private volatile int returnEmptyForFirstCalls = 0;

        void setResponseText(String text) {
            this.responseText = text;
        }

        void setReturnEmpty(boolean empty) {
            this.returnEmpty = empty;
        }

        /** M1-703: empty roll-up for the first N calls only (partial outage). */
        void setReturnEmptyForFirstCalls(int n) {
            this.returnEmptyForFirstCalls = n;
        }

        @Override
        public Optional<String> generateRollup(List<Cluster> clusters, String langCode) {
            int n = callCount.incrementAndGet();
            clusterCounts.add(clusters.size());
            if (returnEmpty || (returnEmptyForFirstCalls > 0 && n <= returnEmptyForFirstCalls)) {
                return Optional.empty();
            }
            return Optional.of(responseText);
        }

        int callCount() { return callCount.get(); }
    }

    /**
     * Stub DataSource that handles two SQL patterns:
     * 1. SELECT id FROM users WHERE adapter = ? AND contact_id = ? → returns userId
     * 2. SELECT ... FROM post ... WHERE p.uid = ANY(?) AND p.status = 'READY' → returns readyPosts
     * 3. SELECT language FROM scope_preferences ... → returns "en"
     */
    private static DataSource stubUserAndPostsDataSource(UUID userId, List<Post> readyPosts) {
        return new DataSource() {
            @Override
            public Connection getConnection() {
                return (Connection) Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] { Connection.class },
                        (proxy, method, args) -> switch (method.getName()) {
                            case "prepareStatement" -> {
                                String sql = (String) args[0];
                                yield newPreparedStatement(sql, userId, readyPosts);
                            }
                            case "createStatement" -> newStatementProxy();
                            case "createArrayOf" -> newArrayProxy();
                            case "setAutoCommit" -> null;
                            case "close" -> null;
                            default -> throw new UnsupportedOperationException(
                                    "Conn." + method.getName());
                        });
            }

            @Override public Connection getConnection(String u, String p) { return getConnection(); }
            @Override public PrintWriter getLogWriter() { throw new UnsupportedOperationException(); }
            @Override public void setLogWriter(PrintWriter out) { throw new UnsupportedOperationException(); }
            @Override public void setLoginTimeout(int seconds) { throw new UnsupportedOperationException(); }
            @Override public int getLoginTimeout() { throw new UnsupportedOperationException(); }
            @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
            @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }

    private static PreparedStatement newPreparedStatement(String sql, UUID userId, List<Post> readyPosts) {
        boolean isUsersQuery = sql.contains("FROM users");
        boolean isPostsQuery = sql.contains("FROM post");
        boolean isScopePrefsQuery = sql.contains("scope_preferences");
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] { PreparedStatement.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "setString", "setObject", "setArray", "setInt" -> null;
                    case "executeQuery" -> {
                        if (isUsersQuery) yield userIdResultSet(userId);
                        if (isPostsQuery) yield postsResultSet(readyPosts);
                        if (isScopePrefsQuery) yield languageResultSet();
                        yield emptyResultSet();
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(
                            "PS." + method.getName());
                });
    }

    private static Statement newStatementProxy() {
        return (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(),
                new Class<?>[] { Statement.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "execute" -> false;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(
                            "Stmt." + method.getName());
                });
    }

    private static Array newArrayProxy() {
        return (Array) Proxy.newProxyInstance(
                Array.class.getClassLoader(),
                new Class<?>[] { Array.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getArray" -> new String[0];
                    case "free" -> null;
                    default -> throw new UnsupportedOperationException(
                            "Array." + method.getName());
                });
    }

    private static ResultSet userIdResultSet(UUID userId) {
        boolean[] consumed = { false };
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> { if (consumed[0]) yield false; consumed[0] = true; yield true; }
                    case "getObject" -> userId;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException("RS." + method.getName());
                });
    }

    private static ResultSet postsResultSet(List<Post> posts) {
        int[] idx = { -1 };
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> { idx[0]++; yield idx[0] < posts.size(); }
                    case "getObject" -> {
                        Post p = posts.get(idx[0]);
                        String col = (String) args[0];
                        yield switch (col) {
                            case "id" -> p.id();
                            case "source_id" -> p.sourceId();
                            default -> throw new UnsupportedOperationException("col: " + col);
                        };
                    }
                    case "getString" -> {
                        Post p = posts.get(idx[0]);
                        String col = (String) args[0];
                        yield switch (col) {
                            case "uid" -> p.uid();
                            case "source_display_name" -> p.sourceDisplayName();
                            case "title" -> p.title();
                            case "url" -> p.url();
                            case "body" -> p.body();
                            default -> throw new UnsupportedOperationException("col: " + col);
                        };
                    }
                    case "getTimestamp" -> Timestamp.from(posts.get(idx[0]).publishedAt());
                    case "getArray" -> {
                        Post p = posts.get(idx[0]);
                        yield (Array) Proxy.newProxyInstance(
                                Array.class.getClassLoader(),
                                new Class<?>[] { Array.class },
                                (aProxy, aMethod, aArgs) -> switch (aMethod.getName()) {
                                    case "getArray" -> p.tags().toArray(new String[0]);
                                    case "free" -> null;
                                    default -> throw new UnsupportedOperationException(
                                            "TagArray." + aMethod.getName());
                                });
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException("RS." + method.getName());
                });
    }

    private static ResultSet languageResultSet() {
        boolean[] consumed = { false };
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> { if (consumed[0]) yield false; consumed[0] = true; yield true; }
                    case "getString" -> "en";
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException("RS." + method.getName());
                });
    }

    private static ResultSet emptyResultSet() {
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> false;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException("RS." + method.getName());
                });
    }
}
