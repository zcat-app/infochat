package app.zcat.infochat.provider.digest;

import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.Identity;
import app.zcat.infochat.messaging.InboundMessage;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.digest.DigestPostCollector.CollectionResult;
import app.zcat.infochat.provider.messaging.AdapterRegistry;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

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
    }

    @Test
    void execute_generatesProseAndCaches() {
        postCollector.seed(testPosts(), 3, 5);
        digestRenderer.setResponse("Digest prose summary");
        DigestSlot slot = futureSlot();

        worker.execute(slot);

        assertEquals(1, cacheRepository.insertCount());
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
        assertEquals(1, cacheRepository.insertCount());
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
        assertEquals(1, cacheRepository.insertCount());
        assertEquals("Headlines only", cacheRepository.lastContent());
        assertTrue(cacheRepository.lastIsDegraded());
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
        worker.execute(slot);
        assertEquals(0, cacheRepository.insertCount(),
                "overlapping same-group execution must be skipped, not processed");

        renderRelease.countDown();
        firstExecution.join(5_000);
        assertEquals(1, cacheRepository.insertCount(),
                "only the first execution inserts");

        // Guard must be released after completion: the slot processes again
        worker.execute(slot);
        assertEquals(2, cacheRepository.insertCount(),
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
        assertEquals(1, cacheRepository.insertCount(),
                "guard must be released after a propagated error");
    }

    @Test
    void execute_logsExpectedSqlFailureWithoutRethrow() {
        postCollector.seed(testPosts(), 1, 1);
        digestRenderer.setResponse("prose");
        cacheRepository.failNextInsert(new SQLException("connection refused"));
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

    private static final class RecordingPostCollector extends DigestPostCollector {
        private List<Post> posts = List.of();
        private long tagVer;
        private long srcVer;
        private RuntimeException failure;

        void seed(List<Post> posts, long tagVer, long srcVer) {
            this.posts = posts;
            this.tagVer = tagVer;
            this.srcVer = srcVer;
            this.failure = null;
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public CollectionResult collectForGroup(UUID groupId, Instant since) {
            if (failure != null) {
                throw failure;
            }
            return new CollectionResult(posts, tagVer, srcVer);
        }
    }

    private static final class RecordingDigestRenderer extends DigestRenderer {
        private String response = "default prose";
        private int calls;
        private CountDownLatch entered;
        private CountDownLatch release;

        void setResponse(String r) { this.response = r; }
        int callCount() { return calls; }

        /** Make render() signal entry then block until released. */
        void setBlocking(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public String render(List<Post> posts, String langCode) {
            calls++;
            if (entered != null) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return response;
        }
    }

    private static final class RecordingDegradedRenderer extends DegradedDigestRenderer {
        private String response = "default headlines";
        private int calls;

        void setResponse(String r) { this.response = r; }
        int callCount() { return calls; }

        @Override
        public String render(List<Post> posts) {
            calls++;
            return response;
        }
    }

    private static final class RecordingCacheRepository extends SummaryCacheRepository {
        private int inserts;
        private String lastContent;
        private boolean lastIsDegraded;
        private long lastTagSubVer;
        private long lastSrcSubVer;
        private SQLException nextFailure;

        int insertCount() { return inserts; }
        String lastContent() { return lastContent; }
        boolean lastIsDegraded() { return lastIsDegraded; }
        long lastTagSubVer() { return lastTagSubVer; }
        long lastSrcSubVer() { return lastSrcSubVer; }

        void failNextInsert(SQLException failure) {
            this.nextFailure = failure;
        }

        @Override
        public void insert(UUID groupId, String slotKind, Instant slotFiredAt,
                           long tagSubscriptionVersion, long sourceSubscriptionVersion,
                           String content, boolean isDegraded, Instant expiresAt)
                throws SQLException {
            if (nextFailure != null) {
                SQLException failure = nextFailure;
                nextFailure = null;
                throw failure;
            }
            inserts++;
            lastContent = content;
            lastIsDegraded = isDegraded;
            lastTagSubVer = tagSubscriptionVersion;
            lastSrcSubVer = sourceSubscriptionVersion;
        }
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

        RecordingAdapter(String name) { this.name = name; }

        int sendCount() { return sent.size(); }
        OutboundMessage lastMessage() { return sent.getLast(); }

        @Override public String name() { return name; }
        @Override public app.zcat.infochat.messaging.CapabilityFlags capabilities() { return null; }
        @Override public AdapterTrustLevel trustLevel() { return AdapterTrustLevel.HIGH; }
        @Override public Identity assertIdentity(InboundMessage msg) {
            throw new UnsupportedOperationException();
        }
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

    /**
     * JDBC stub for the group-metadata query ({@code DigestWorker.readGroupMetadata}).
     */
    private static final class StubGroupDataSource implements DataSource {
        private final String adapter;
        private final String upstreamGroupId;
        private final String language;

        StubGroupDataSource(String adapter, String upstreamGroupId, String language) {
            this.adapter = adapter;
            this.upstreamGroupId = upstreamGroupId;
            this.language = language;
        }

        @Override
        public Connection getConnection() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> newPs();
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(
                                "Connection." + method.getName());
                    });
        }

        private PreparedStatement newPs() {
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "setObject", "setString" -> null;
                        case "executeQuery" -> groupResultSet();
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(
                                "PreparedStatement." + method.getName());
                    });
        }

        private ResultSet groupResultSet() {
            boolean[] consumed = {false};
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> {
                            if (consumed[0]) yield false;
                            consumed[0] = true;
                            yield true;
                        }
                        case "getString" -> {
                            String col = (String) args[0];
                            yield switch (col) {
                                case "adapter" -> adapter;
                                case "upstream_group_id" -> upstreamGroupId;
                                case "language" -> language;
                                default -> null;
                            };
                        }
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(
                                "ResultSet." + method.getName());
                    });
        }

        @Override public Connection getConnection(String u, String p) { return getConnection(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int s) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
        @Override public <T> T unwrap(Class<T> i) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> i) { return false; }
    }
}
