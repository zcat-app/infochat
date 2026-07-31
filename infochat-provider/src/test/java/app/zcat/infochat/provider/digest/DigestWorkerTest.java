package app.zcat.infochat.provider.digest;

import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.group.GroupRepository;
import app.zcat.infochat.provider.messaging.AdapterRegistry;
import app.zcat.infochat.provider.messaging.OutboundDelivery;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.digest.DigestRenderer.DigestMode;
import app.zcat.infochat.provider.digest.DigestRenderer.RenderedSection;
import org.jboss.logmanager.LogContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the full {@link DigestWorker} pipeline with hand-rolled stubs
 * (Mockito is intentionally absent from the Provider classpath). Five
 * acceptance scenarios covering happy path, zero-posts, LLM timeout,
 * message delivery, and subscription-version cache keying.
 */
class DigestWorkerTest {

    private static final UUID GROUP_ID = UUID.randomUUID();
    private static final String ADAPTER_NAME = "inmemory";
    private static final String UPSTREAM_GROUP_ID = "group-456";
    private static final Duration REPLAY_RETENTION = Duration.ofMinutes(10);
    // Production defaults (application.properties): 08:00 and 20:00, so the
    // inter-slot period — and therefore the first-run lookback — is 12h.
    private static final int MORNING_SLOT_HOUR = 8;
    private static final int EVENING_SLOT_HOUR = 20;
    private static final Duration DEFAULT_SLOT_INTERVAL = Duration.ofHours(12);

    private DigestWorker worker;
    private RecordingPostCollector postCollector;
    private RecordingDigestRenderer digestRenderer;
    private RecordingDegradedRenderer degradedRenderer;
    private RecordingCacheRepository cacheRepository;
    private RecordingSectionRepository sectionRepository;
    private RecordingAdapter recordingAdapter;
    private StubBundleLoader bundleLoader;
    private List<String> callLog;

    @BeforeEach
    void setUp() {
        worker = new DigestWorker();
        postCollector = new RecordingPostCollector();
        digestRenderer = new RecordingDigestRenderer();
        degradedRenderer = new RecordingDegradedRenderer();
        cacheRepository = new RecordingCacheRepository();
        callLog = new ArrayList<>();
        sectionRepository = new RecordingSectionRepository(callLog);
        bundleLoader = new StubBundleLoader();
        recordingAdapter = new RecordingAdapter(ADAPTER_NAME, callLog);

        worker.postCollector = postCollector;
        worker.digestRenderer = digestRenderer;
        worker.degradedRenderer = degradedRenderer;
        worker.cacheRepository = cacheRepository;
        worker.sectionRepository = sectionRepository;
        worker.bundleLoader = bundleLoader;
        worker.adapterRegistry = new StubAdapterRegistry(recordingAdapter);
        worker.dataSource = new StubGroupDataSource(ADAPTER_NAME, UPSTREAM_GROUP_ID, "en");
        worker.replayRetention = REPLAY_RETENTION;
        // @ConfigProperty fields are not injected into a hand-constructed
        // worker, so the production defaults are set explicitly — a 0/0 pair
        // would silently give the first-run lookback a different span than
        // any real deployment has.
        worker.morningSlotHour = MORNING_SLOT_HOUR;
        worker.eveningSlotHour = EVENING_SLOT_HOUR;
        // Pass-through chokepoint via the public constructor: the recording
        // adapter never throws, so the retry/cleanup collaborators (notifier,
        // group repo) are never exercised on these success/programming-error
        // paths. base-delay 0 keeps any (unused) back-off instant.
        worker.outboundDelivery = new OutboundDelivery(
                new ThrottledAdminNotifier(),
                new GroupRepository(new StubGroupDataSource(ADAPTER_NAME, UPSTREAM_GROUP_ID, "en")),
                3, 0L, 2.0, 3);
        DigestDelivery digestDelivery = new DigestDelivery();
        digestDelivery.outboundDelivery = worker.outboundDelivery;
        digestDelivery.deliveryRepository = new RecordingCategoryDeliveryRepository();
        worker.digestDelivery = digestDelivery;
    }

    @Test
    void execute_generatesProseAndCaches() {
        postCollector.seed(testPosts(), 3, 5);
        digestRenderer.setResponse("Digest prose summary");
        DigestSlot slot = futureSlot();

        worker.execute(slot);

        assertEquals(1, cacheRepository.upsertCount());
        assertEquals("Digest prose summary", cacheRepository.lastContent());
        assertFalse(cacheRepository.lastIsDegraded());
    }

    @Test
    void execute_sendsDigestToGroup() {
        postCollector.seed(testPosts(), 1, 1);
        digestRenderer.setResponse("Prose for group");
        DigestSlot slot = futureSlot();

        worker.execute(slot);

        assertEquals(1, recordingAdapter.sendCount());
        OutboundMessage sent = recordingAdapter.lastMessage();
        assertEquals("Prose for group", sent.text());
        assertEquals(UPSTREAM_GROUP_ID,
                ((app.zcat.infochat.messaging.ScopeRef.Group) sent.scope()).adapterGroupId());
    }

    @Test
    void execute_zeroPosts_sendsFixedReply() {
        postCollector.seed(List.of(), 0, 0);
        DigestSlot slot = futureSlot();

        worker.execute(slot);

        assertEquals(0, digestRenderer.callCount(),
                "LLM renderer not invoked on empty posts");
        assertEquals(1, cacheRepository.upsertCount());
        assertEquals(StubBundleLoader.NO_POSTS_TEXT, cacheRepository.lastContent());
        assertFalse(cacheRepository.lastIsDegraded(),
                "zero-posts is not a degraded result");
        assertEquals(1, recordingAdapter.sendCount());
        assertEquals(StubBundleLoader.NO_POSTS_TEXT, recordingAdapter.lastMessage().text());
    }

    @Test
    void execute_llmTimeout_fallsToDegraded() {
        postCollector.seed(testPosts(), 1, 1);
        degradedRenderer.setResponse("Headlines only");
        // Window already expired → immediate degraded fallback
        DigestSlot slot = pastSlot();

        worker.execute(slot);

        assertEquals(0, digestRenderer.callCount(),
                "LLM renderer not invoked when window expired");
        assertEquals(1, degradedRenderer.callCount());
        assertEquals(1, cacheRepository.upsertCount());
        assertEquals("Headlines only", cacheRepository.lastContent());
        assertTrue(cacheRepository.lastIsDegraded());
    }

    @Test
    void execute_includesPostPublishedBetweenSlots() {
        // The previous digest boundary is 12h before this slot; the post was
        // published between the two slots, OUTSIDE this slot's own window.
        DigestSlot slot = futureSlot();
        Instant previousBoundary = slot.windowStart().minusSeconds(12 * 3600);
        cacheRepository.seedPreviousBoundary(previousBoundary);
        postCollector.seed(
                List.of(postPublishedAt(slot.windowStart().minusSeconds(6 * 3600))), 1, 1);
        digestRenderer.setResponse("prose covering the inter-digest period");

        worker.execute(slot);

        assertEquals(previousBoundary, postCollector.lastSince(),
                "collection lower bound must be the previous digest boundary, not windowStart");
        assertEquals(1, digestRenderer.callCount(),
                "the between-slots post must reach the renderer, not the no-posts reply");
        assertEquals("prose covering the inter-digest period", cacheRepository.lastContent(),
                "a post published between two digest slots must appear in the next digest");
    }

    @Test
    void execute_collectsOneSlotIntervalBackWhenNoPreviousDigest() {
        postCollector.seed(testPosts(), 1, 1);
        digestRenderer.setResponse("prose");
        DigestSlot slot = futureSlot();

        worker.execute(slot);

        assertEquals(slot.windowStart().minus(DEFAULT_SLOT_INTERVAL), postCollector.lastSince(),
                "a first-ever digest collects one inter-slot period back, not from the "
                        + "~30-minute slot window — collecting from windowStart made a new "
                        + "group's opening digest report 'no posts yet' against a full corpus");
    }

    @Test
    void execute_firstRunLookbackTracksConfiguredSlotHours() {
        // Non-default centre hours: morning 08:00, evening 14:00. The gap is
        // 6h forward, so an evening slot's predecessor is 6h back and a
        // morning slot's — the PREVIOUS day's evening slot — is 18h back.
        // Pins the lookback to the configured hours rather than a hardcoded 12h.
        worker.eveningSlotHour = 14;
        postCollector.seed(testPosts(), 1, 1);
        digestRenderer.setResponse("prose");

        DigestSlot eveningSlot = new DigestSlot(GROUP_ID, "UTC", "evening",
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        worker.execute(eveningSlot);

        assertEquals(eveningSlot.windowStart().minus(Duration.ofHours(6)),
                postCollector.lastSince(),
                "an evening first-run looks back the morning-to-evening gap");

        DigestSlot morningSlot = futureSlot();
        worker.execute(morningSlot);

        assertEquals(morningSlot.windowStart().minus(Duration.ofHours(18)),
                postCollector.lastSince(),
                "a morning first-run looks back the complement of that gap");
    }

    @Test
    void execute_cacheExpiryOutlivesWindowEndByReplayRetention() {
        postCollector.seed(testPosts(), 1, 1);
        digestRenderer.setResponse("prose");
        DigestSlot slot = futureSlot();

        worker.execute(slot);

        assertEquals(slot.windowEnd().plus(REPLAY_RETENTION), cacheRepository.lastExpiresAt(),
                "expires_at must outlive the slot window by the replay retention (PT24H default), "
                        + "decoupled from retry-cooldown so a later retry replays persisted sections "
                        + "instead of degrading immediately");
    }

    @Test
    void execute_persistsSectionsAlongsideCacheRow() {
        // M1-652 acceptance item 2: every render that produces sections
        // persists the ordered list alongside the cache upsert, as the EXACT
        // delivery bytes. The persist MUST happen before delivery so a crash
        // between them leaves the sections durably readable for replay —
        // pinned here via the shared call-log ordering.
        postCollector.seed(testPosts(), 1, 1);
        digestRenderer.setMultiSections(List.of(
                new RenderedSection("security", "section A prose"),
                new RenderedSection("crypto", "section B prose"),
                new RenderedSection(null, "section Other prose")));
        DigestSlot slot = futureSlot();

        worker.execute(slot);

        assertEquals(1, sectionRepository.replaceCalls().size(),
                "the rendered section list is persisted exactly once");
        RecordingSectionRepository.ReplaceCall persist = sectionRepository.replaceCalls().get(0);
        assertEquals(GROUP_ID, persist.groupId());
        assertEquals(slot.windowStart(), persist.windowStart());
        assertEquals(List.of(
                new RenderedSection("security", "section A prose"),
                new RenderedSection("crypto", "section B prose"),
                new RenderedSection(null, "section Other prose")),
                persist.sections(),
                "persisted bytes are the exact renderSections() output in order");
        // Persist-before-deliver: the shared call-log records "replace" when
        // the persist lands and "send" when each adapter send fires; the
        // persist marker must precede every send marker.
        int firstSend = callLog.indexOf("send");
        int replace = callLog.indexOf("replace");
        assertTrue(replace >= 0 && firstSend >= 0,
                "both persist and delivery markers recorded: " + callLog);
        assertTrue(replace < firstSend,
                "persist MUST happen before delivery — call-log order: " + callLog);
    }

    @Test
    void execute_degradedRenderPersistsNoSections() {
        // A degraded render (window closed) keeps the single-message path —
        // no per-category structure, so no sections to persist. The gap-fill
        // replay path later finds no sections and falls back to the full
        // re-run (acceptance item 6).
        postCollector.seed(testPosts(), 1, 1);
        degradedRenderer.setResponse("Headlines only");
        DigestSlot slot = pastSlot();

        worker.execute(slot);

        assertTrue(sectionRepository.replaceCalls().isEmpty(),
                "degraded renders persist no sections — the fallback path owns them");
        assertTrue(cacheRepository.lastIsDegraded());
    }

    @Test
    void execute_zeroPostsPersistsNoSections() {
        // The zero-posts fixed reply keeps the single-message path too.
        postCollector.seed(List.of(), 0, 0);
        DigestSlot slot = futureSlot();

        worker.execute(slot);

        assertTrue(sectionRepository.replaceCalls().isEmpty(),
                "zero-posts renders persist no sections");
    }

    @Test
    void retryAfterWindowEnd_withinReplayRetention_rendersFullProse() {
        // Scheduled run whose window already closed: it degrades, but writes
        // expires_at = windowEnd + replayRetention, which is still in the
        // future (PT24H default horizon, not the old PT2M cooldown).
        postCollector.seed(testPosts(), 1, 1);
        degradedRenderer.setResponse("Headlines only");
        digestRenderer.setResponse("Full retry prose");
        DigestSlot missed = pastSlot();

        worker.execute(missed);
        assertTrue(cacheRepository.lastIsDegraded(),
                "a closed window degrades the scheduled run");

        // /retry --digest rebuilds the slot from the cache row's coordinates
        // with windowEnd = the row's expires_at (pinned by
        // DigestRetryServiceTest.retryDigest_replacesCacheRow). Within the
        // replay retention that instant is still ahead, so the retry renders
        // full prose instead of degrading again.
        Instant expiresAt = cacheRepository.lastExpiresAt();
        assertTrue(expiresAt.isAfter(Instant.now()),
                "the cache row must be non-expired after windowEnd");
        DigestSlot retrySlot = new DigestSlot(
                GROUP_ID, "UTC", "morning", missed.windowStart(), expiresAt);

        worker.execute(retrySlot);

        assertFalse(cacheRepository.lastIsDegraded(),
                "a retry within the replay retention must render full prose, not degrade");
        assertEquals("Full retry prose", cacheRepository.lastContent());
    }

    @Test
    void execute_writesSubscriptionVersions() {
        postCollector.seed(testPosts(), 7, 12);
        digestRenderer.setResponse("prose");
        DigestSlot slot = futureSlot();

        worker.execute(slot);

        assertEquals(7L, cacheRepository.lastTagSubVer());
        assertEquals(12L, cacheRepository.lastSrcSubVer());
    }

    @Test
    void execute_multiSectionRenderProducesOneSendPerSection() {
        postCollector.seed(testPosts(), 1, 1);
        digestRenderer.setMultiSections(List.of(
                new RenderedSection("security", "section A prose"),
                new RenderedSection("crypto", "section B prose"),
                new RenderedSection(null, "section Other prose")));
        DigestSlot slot = futureSlot();

        worker.execute(slot);

        assertEquals(3, recordingAdapter.sendCount(),
                "an N-section render produces N sends through the chokepoint");
        // Sequential section order: the adapter's recorded sends preserve the
        // section order DigestDelivery received.
        assertEquals("section A prose", recordingAdapter.sent.get(0).text());
        assertEquals("section B prose", recordingAdapter.sent.get(1).text());
        assertEquals("section Other prose", recordingAdapter.sent.get(2).text());
    }

    @Test
    void execute_skipsOverlappingSameGroupSlot() throws Exception {
        postCollector.seed(testPosts(), 1, 1);
        CountDownLatch renderEntered = new CountDownLatch(1);
        CountDownLatch renderRelease = new CountDownLatch(1);
        digestRenderer.setBlocking(renderEntered, renderRelease);
        digestRenderer.setResponse("prose");
        DigestSlot slot = futureSlot();

        Thread firstExecution = new Thread(() -> worker.execute(slot));
        firstExecution.start();
        assertTrue(renderEntered.await(5, TimeUnit.SECONDS),
                "first execution must reach the renderer");

        // Same group+slot while the first execution is still rendering
        assertEquals(DigestWorker.SlotOutcome.SKIPPED_IN_FLIGHT, worker.execute(slot),
                "overlapping same-group execution must report it did not run");
        assertEquals(0, cacheRepository.upsertCount(),
                "overlapping same-group execution must be skipped, not processed");

        renderRelease.countDown();
        firstExecution.join(5_000);
        assertEquals(1, cacheRepository.upsertCount(),
                "only the first execution upserts");

        // Guard must be released after completion: the slot processes again
        assertEquals(DigestWorker.SlotOutcome.RAN, worker.execute(slot),
                "guard released — a fresh execution reports it ran");
        assertEquals(2, cacheRepository.upsertCount(),
                "guard must be released once the in-flight execution finishes");
    }

    @Test
    void execute_propagatesProgrammingErrors() {
        postCollector.failWith(new IllegalStateException("group not found"));
        DigestSlot slot = futureSlot();

        assertThrows(IllegalStateException.class, () -> worker.execute(slot),
                "programming errors must not be suppressed by the digest catch");

        // The guard must be released even when the error propagates
        postCollector.seed(testPosts(), 1, 1);
        digestRenderer.setResponse("prose");
        worker.execute(slot);
        assertEquals(1, cacheRepository.upsertCount(),
                "guard must be released after a propagated error");
    }

    @Test
    void execute_logsExpectedSqlFailureWithoutRethrow() {
        postCollector.seed(testPosts(), 1, 1);
        digestRenderer.setResponse("prose");
        cacheRepository.failNextUpsert(new SQLException("connection refused"));
        DigestSlot slot = futureSlot();

        assertDoesNotThrow(() -> worker.execute(slot),
                "SQLException is an expected operational failure — logged, not rethrown");
    }

    @Test
    void readGroupMetadata_nullOrUnrecognizedDigestModeFallsBackToNormalWithOneWarn() {
        // M1-732 acceptance: digest_mode is a SQL-deserialization boundary.
        // The V67 CHECK constraint pins the closed set in the real schema,
        // but a stubbed or out-of-band write can still yield NULL or
        // garbage — both resolve to normal, each logging exactly one WARN.
        CapturingHandler logCapture = new CapturingHandler();
        org.jboss.logmanager.Logger jbossLogger =
                LogContext.getLogContext().getLogger(DigestWorker.class.getName());
        java.util.logging.Logger julLogger =
                java.util.logging.Logger.getLogger(DigestWorker.class.getName());
        jbossLogger.addHandler(logCapture);
        julLogger.addHandler(logCapture);
        try {
            worker.dataSource = new StubGroupDataSource(ADAPTER_NAME, UPSTREAM_GROUP_ID, "en", null);
            DigestWorker.GroupMetadata nullMode =
                    assertDoesNotThrow(() -> worker.readGroupMetadata(GROUP_ID));
            assertEquals(DigestMode.NORMAL, nullMode.digestMode(),
                    "a NULL digest_mode resolves to normal");

            worker.dataSource =
                    new StubGroupDataSource(ADAPTER_NAME, UPSTREAM_GROUP_ID, "en", "verbose");
            DigestWorker.GroupMetadata garbageMode =
                    assertDoesNotThrow(() -> worker.readGroupMetadata(GROUP_ID));
            assertEquals(DigestMode.NORMAL, garbageMode.digestMode(),
                    "an unrecognized digest_mode resolves to normal");

            long warns = logCapture.records.stream()
                    .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                    .filter(r -> r.getMessage().contains("digest_mode"))
                    .count();
            assertEquals(2, warns,
                    "exactly one WARN per fallback event (NULL + unrecognized): "
                            + logCapture.records);
        } finally {
            jbossLogger.removeHandler(logCapture);
            julLogger.removeHandler(logCapture);
        }
    }

    @Test
    void readGroupMetadata_recognizedDigestModePassesThrough() {
        worker.dataSource = new StubGroupDataSource(ADAPTER_NAME, UPSTREAM_GROUP_ID, "en", "brief");

        DigestWorker.GroupMetadata meta =
                assertDoesNotThrow(() -> worker.readGroupMetadata(GROUP_ID));

        assertEquals(DigestMode.BRIEF, meta.digestMode(),
                "a stored mode parses case-insensitively to its enum value");
    }

    // ----- helpers ----------------------------------------------------------

    private DigestSlot futureSlot() {
        Instant windowStart = Instant.now().minusSeconds(3600);
        Instant windowEnd = Instant.now().plusSeconds(3600);
        return new DigestSlot(GROUP_ID, "UTC", "morning", windowStart, windowEnd);
    }

    private DigestSlot pastSlot() {
        Instant windowStart = Instant.now().minusSeconds(7200);
        Instant windowEnd = Instant.now().minusSeconds(1);
        return new DigestSlot(GROUP_ID, "UTC", "morning", windowStart, windowEnd);
    }

    private static Post postPublishedAt(Instant publishedAt) {
        return new Post(UUID.randomUUID(), "uid-between", UUID.randomUUID(),
                "TechCrunch", "Between slots", "https://tc.com/between",
                "body", publishedAt, List.of("crypto"), List.of("unknown"));
    }

    private static List<Post> testPosts() {
        return List.of(
                new Post(UUID.randomUUID(), "uid-1", UUID.randomUUID(),
                        "TechCrunch", "Bitcoin $100k", "https://tc.com/btc",
                        "body", Instant.now(), List.of("crypto"), List.of("unknown")),
                new Post(UUID.randomUUID(), "uid-2", UUID.randomUUID(),
                        "CoinDesk", "Ethereum update", "https://cd.com/eth",
                        "body", Instant.now(), List.of("crypto"), List.of("unknown")));
    }

    // ----- recording/stub collaborators -------------------------------------

    /**
     * JUL capturing handler — SLF4J in Quarkus routes through
     * jboss-logmanager, which IS a JUL implementation, so attaching to the
     * {@link DigestWorker} logger captures the records the production code
     * emits (the DigestSchedulerTest idiom).
     */
    private static final class CapturingHandler extends Handler {
        private final ConcurrentLinkedQueue<LogRecord> records = new ConcurrentLinkedQueue<>();

        CapturingHandler() {
            setLevel(Level.ALL);
        }

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override public void flush() {}
        @Override public void close() {}
    }

    private static final class StubBundleLoader extends BundleLoader {
        static final String NO_POSTS_TEXT = "No posts yet for this period.";

        @Override
        public String get(String key, String langCode) {
            if (BundleKeys.REPLY_SUMMARY_NO_POSTS_YET.equals(key)) {
                return NO_POSTS_TEXT;
            }
            return "bundle:" + key;
        }
    }

    private static final class RecordingAdapter implements MessagingAdapter {
        private final String name;
        private final List<OutboundMessage> sent = new ArrayList<>();
        private final List<String> callLog;

        RecordingAdapter(String name) { this(name, new ArrayList<>()); }

        RecordingAdapter(String name, List<String> callLog) {
            this.name = name;
            this.callLog = callLog;
        }

        int sendCount() { return sent.size(); }
        OutboundMessage lastMessage() { return sent.getLast(); }

        @Override public String name() { return name; }
        @Override public app.zcat.infochat.messaging.CapabilityFlags capabilities() { return null; }
        @Override public AdapterTrustLevel trustLevel() { return AdapterTrustLevel.HIGH; }
        @Override public boolean isWellFormedContactId(String contactId) { return true; }
        @Override public MessageHandle send(OutboundMessage msg) {
            sent.add(msg);
            callLog.add("send");
            return null;
        }
        @Override public void update(MessageHandle h, String b) {}
        @Override public void finalizeMessage(MessageHandle h, String b) {}
        @Override public void setTyping(ScopeRef s, boolean t) {}
        @Override public void setInboundHandler(InboundHandler h) {}
    }

    private static final class StubAdapterRegistry extends AdapterRegistry {
        private final List<MessagingAdapter> adapters;

        StubAdapterRegistry(MessagingAdapter... adapters) {
            this.adapters = List.of(adapters);
        }

        @Override
        public List<MessagingAdapter> activatedAdapters() {
            return adapters;
        }
    }
}
