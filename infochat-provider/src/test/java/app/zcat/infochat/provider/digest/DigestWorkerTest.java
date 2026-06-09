package app.zcat.infochat.provider.digest;

import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.AdapterRegistry;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
    private static final Duration RETRY_HORIZON = Duration.ofMinutes(10);

    private DigestWorker worker;
    private RecordingPostCollector postCollector;
    private RecordingDigestRenderer digestRenderer;
    private RecordingDegradedRenderer degradedRenderer;
    private RecordingCacheRepository cacheRepository;
    private RecordingAdapter recordingAdapter;
    private StubBundleLoader bundleLoader;

    @BeforeEach
    void setUp() {
        worker = new DigestWorker();
        postCollector = new RecordingPostCollector();
        digestRenderer = new RecordingDigestRenderer();
        degradedRenderer = new RecordingDegradedRenderer();
        cacheRepository = new RecordingCacheRepository();
        bundleLoader = new StubBundleLoader();
        recordingAdapter = new RecordingAdapter(ADAPTER_NAME);

        worker.postCollector = postCollector;
        worker.digestRenderer = digestRenderer;
        worker.degradedRenderer = degradedRenderer;
        worker.cacheRepository = cacheRepository;
        worker.bundleLoader = bundleLoader;
        worker.adapterRegistry = new StubAdapterRegistry(recordingAdapter);
        worker.dataSource = new StubGroupDataSource(ADAPTER_NAME, UPSTREAM_GROUP_ID, "en");
        worker.retryHorizon = RETRY_HORIZON;
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
    void execute_collectsFromWindowStartWhenNoPreviousDigest() {
        postCollector.seed(testPosts(), 1, 1);
        digestRenderer.setResponse("prose");
        DigestSlot slot = futureSlot();

        worker.execute(slot);

        assertEquals(slot.windowStart(), postCollector.lastSince(),
                "first-ever digest falls back to the slot window");
    }

    @Test
    void execute_cacheExpiryOutlivesWindowEndByRetryHorizon() {
        postCollector.seed(testPosts(), 1, 1);
        digestRenderer.setResponse("prose");
        DigestSlot slot = futureSlot();

        worker.execute(slot);

        assertEquals(slot.windowEnd().plus(RETRY_HORIZON), cacheRepository.lastExpiresAt(),
                "expires_at must outlive the slot window by the retry horizon");
    }

    @Test
    void retryAfterWindowEnd_withinRetryHorizon_rendersFullProse() {
        // Scheduled run whose window already closed: it degrades, but writes
        // expires_at = windowEnd + horizon, which is still in the future.
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
        // horizon that instant is still ahead, so the retry renders full
        // prose instead of degrading again.
        Instant expiresAt = cacheRepository.lastExpiresAt();
        assertTrue(expiresAt.isAfter(Instant.now()),
                "the cache row must be non-expired after windowEnd");
        DigestSlot retrySlot = new DigestSlot(
                GROUP_ID, "UTC", "morning", missed.windowStart(), expiresAt);

        worker.execute(retrySlot);

        assertFalse(cacheRepository.lastIsDegraded(),
                "a retry within the horizon must render full prose, not degrade");
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
                "body", publishedAt, List.of("crypto"));
    }

    private static List<Post> testPosts() {
        return List.of(
                new Post(UUID.randomUUID(), "uid-1", UUID.randomUUID(),
                        "TechCrunch", "Bitcoin $100k", "https://tc.com/btc",
                        "body", Instant.now(), List.of("crypto")),
                new Post(UUID.randomUUID(), "uid-2", UUID.randomUUID(),
                        "CoinDesk", "Ethereum update", "https://cd.com/eth",
                        "body", Instant.now(), List.of("crypto")));
    }

    // ----- recording/stub collaborators -------------------------------------

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

        RecordingAdapter(String name) { this.name = name; }

        int sendCount() { return sent.size(); }
        OutboundMessage lastMessage() { return sent.getLast(); }

        @Override public String name() { return name; }
        @Override public app.zcat.infochat.messaging.CapabilityFlags capabilities() { return null; }
        @Override public AdapterTrustLevel trustLevel() { return AdapterTrustLevel.HIGH; }
        @Override public MessageHandle send(OutboundMessage msg) {
            sent.add(msg);
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
