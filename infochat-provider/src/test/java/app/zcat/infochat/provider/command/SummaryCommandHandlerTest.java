package app.zcat.infochat.provider.command;

import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.chat.SummaryAnchorRepository;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.summary.ClusterTraversal;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Result;
import app.zcat.infochat.provider.summary.SummaryProseGenerator;
import app.zcat.infochat.provider.summary.SummaryProseGenerator.ClusterProse;
import app.zcat.infochat.provider.translation.TranslationPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import app.zcat.infochat.provider.translation.TranslationCache;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Handler-tier (plain JUnit, no Quarkus boot) tests for
 * {@link SummaryCommandHandler} per the test-pyramid convention at
 * {@code docs/process/test-pyramid.md} §Handler unit tests.
 *
 * <p>The handler's seven {@code @Inject} collaborators are stubbed in
 * {@link #buildHandlerWithStubs()}: {@link BundleLoader} (real,
 * loaded by hand), {@link RecordingEligiblePostQuery},
 * {@link RecordingSummaryProseGenerator}, {@link LlmOutputSanitizer}
 * (real), real {@link ClusterTraversal}, {@link InboundContext}
 * (constructed), and {@link StubUserDataSource} for the DM-scope
 * users-id lookup.
 *
 * <p>Asserted invariants (one {@code @Test} per behavioral branch):
 * <ul>
 *   <li>Name: handler returns the literal {@code "summary"}.</li>
 *   <li>Dispatch: a single {@code /summary} call produces one
 *       {@link OutboundMessage} and exercises {@link EligiblePostQuery}
 *       exactly once.</li>
 *   <li>Zero-subscriptions / empty-window branches: empty post list →
 *       no_posts_yet reply, prose generator NOT invoked.</li>
 *   <li>Happy path: 3 posts → 3 clusters → 3 prose calls → reply has
 *       three cluster blocks in the documented structure.</li>
 *   <li>LLM-unreachable branch: degraded prose → reply carries the
 *       degraded_notice prefix.</li>
 *   <li>Cap-excess branch: cap_excess_notice prefix interpolated.</li>
 *   <li>Group scope: handler returns no_posts_yet without calling
 *       the prose generator.</li>
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
    private BundleLoader bundleLoader;

    @BeforeEach
    void buildHandlerWithStubs() throws Exception {
        bundleLoader = newRealBundleLoader();
        eligiblePostQuery = new RecordingEligiblePostQuery();
        proseGenerator = new RecordingSummaryProseGenerator();
        anchorRepository = new RecordingSummaryAnchorRepository();
        handler = new SummaryCommandHandler();
        handler.bundleLoader = bundleLoader;
        handler.dataSource = StubUserDataSource.userExists(UUID.randomUUID());
        handler.eligiblePostQuery = eligiblePostQuery;
        handler.clusterTraversal = new ClusterTraversal();
        handler.summaryProseGenerator = proseGenerator;
        handler.llmOutputSanitizer = new LlmOutputSanitizer();
        handler.translationPipeline = newEnShortCircuitPipeline();
        handler.summaryAnchorRepository = anchorRepository;
        InboundContext context = new InboundContext();
        context.setAdapterName("inmemory");
        handler.inboundContext = context;
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
    void zeroSubscriptionsProducesNoPostsYetReplyWithoutLlmCall() {
        // Zero subscriptions modeled as the EligiblePostQuery returning
        // an empty Result for the scope; the handler short-circuits
        // before reaching the prose generator.
        eligiblePostQuery.seedNoPosts();

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "nosub"), "/summary");

        assertTrue(reply.text().contains("No posts to summarize"),
                "zero subscriptions → no_posts_yet reply. Got: " + reply.text());
        assertEquals(0, proseGenerator.callCount(),
                "zero-subscription path must NOT call the LLM");
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

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "happy"), "/summary");

        assertEquals(3, proseGenerator.callCount(), "one LLM call per cluster");
        String body = reply.text();
        int blocks = body.split("\\[topic_id=").length - 1;
        assertEquals(3, blocks, "three cluster blocks in reply. Got: " + body);
        assertTrue(body.contains("Summary prose for the cluster."),
                "LLM-authored prose lands at the summary: slot. Got: " + body);
        assertTrue(body.contains("covered by:"));
        assertTrue(body.contains("score:"));
        assertTrue(body.contains("classification:"));
        assertTrue(body.contains("tags:"));
        assertTrue(body.contains("Headline A"));
    }

    @Test
    void llmUnreachableYieldsDegradedFallbackReply() {
        Post p = post(PREFIX + "d1", "Degraded headline", Instant.now());
        eligiblePostQuery.seedPosts(List.of(p), 0);
        proseGenerator.setDegradedMode(true);

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "deg"), "/summary");

        String body = reply.text();
        assertTrue(body.contains("LLM is unreachable"),
                "degraded reply must include the degraded_notice prefix. Got: " + body);
        assertTrue(body.contains("Degraded headline"),
                "degraded prose includes the headline");
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

        String body = reply.text();
        assertTrue(body.contains("Showing 3 of 5"),
                "cap-excess prefix must cite included/total counts. Got: " + body);
        assertTrue(body.contains("2 oldest excluded"),
                "cap-excess prefix must cite the excluded count. Got: " + body);
        assertEquals(3, proseGenerator.callCount(),
                "only the retained 3 posts (= 3 clusters) get LLM calls");
    }

    @Test
    void groupScopeReturnsNoPostsYet() {
        // Group scope: handler.resolveScopeId returns Optional.empty()
        // before touching DataSource. Wire a NEVER stub so an
        // accidental SQL call would surface loudly.
        handler.dataSource = StubUserDataSource.neverCalled();

        OutboundMessage reply = handler.handle(new ScopeRef.Group("g-some-id"), "/summary");

        assertTrue(reply.text().contains("No posts to summarize"),
                "group scope (no actor seam in v1) falls through to no_posts_yet. Got: "
                        + reply.text());
        assertEquals(0, proseGenerator.callCount(),
                "group scope must NOT invoke the LLM");
    }

    @Test
    void sanitizerStripsPrivilegedCommandFromLlmAuthoredProse() {
        Post p = post(PREFIX + "s1", "San headline", Instant.now());
        eligiblePostQuery.seedPosts(List.of(p), 0);
        // A small LLM emits prose containing /grant-admin — the sanitizer
        // must replace it with [redacted command] before the reply lands.
        proseGenerator.setResponseText("Ops should run /grant-admin to escalate.");

        OutboundMessage reply = handler.handle(new ScopeRef.Dm(PREFIX + "san"), "/summary");

        String body = reply.text();
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

    // ----- fixtures + collaborator stubs --------------------------------

    private static TranslationPipeline newEnShortCircuitPipeline() throws Exception {
        TranslationPipeline pipeline = new TranslationPipeline();
        java.lang.reflect.Field cacheField = TranslationPipeline.class.getDeclaredField("translationCache");
        cacheField.setAccessible(true);
        cacheField.set(pipeline, new TranslationCache());

        java.lang.reflect.Field providerField = TranslationPipeline.class.getDeclaredField("translationProvider");
        providerField.setAccessible(true);
        providerField.set(pipeline, (app.zcat.infochat.messaging.TranslationProvider) (text, from, to) -> text);

        java.lang.reflect.Field sanitizerField = TranslationPipeline.class.getDeclaredField("llmOutputSanitizer");
        sanitizerField.setAccessible(true);
        sanitizerField.set(pipeline, new LlmOutputSanitizer());
        return pipeline;
    }

    private static BundleLoader newRealBundleLoader() throws Exception {
        BundleLoader loader = new BundleLoader();
        Method load = BundleLoader.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(loader);
        return loader;
    }

    private static Post post(String uid, String title, Instant publishedAt) {
        return new Post(
                UUID.randomUUID(),
                uid,
                UUID.randomUUID(),
                "TestSrc",
                title,
                "https://example.com/" + uid,
                "Body for " + title,
                publishedAt,
                List.of(PREFIX + "news"));
    }

    /**
     * Recording subclass of {@link EligiblePostQuery}: returns the
     * seeded {@link Result} on every {@code fetch}; records the call
     * count so the dispatch test can assert exactly-one query per
     * handler invocation.
     */
    private static final class RecordingEligiblePostQuery extends EligiblePostQuery {
        private final AtomicInteger fetchCallCount = new AtomicInteger();
        private Result seeded =
                new Result(List.of(), 0, 0, 200, "laptop", Optional.empty());

        void seedNoPosts() {
            seeded = new Result(List.of(), 0, 0, 200, "laptop", Optional.empty());
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

        int fetchCallCount() {
            return fetchCallCount.get();
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

        void setResponseText(String text) {
            this.responseText = text;
        }

        void setDegradedMode(boolean degraded) {
            this.degradedMode = degraded;
        }

        @Override
        public List<ClusterProse> generate(List<Cluster> clusters, String scopeLanguage) {
            List<ClusterProse> out = new ArrayList<>(clusters.size());
            for (Cluster c : clusters) {
                callCount.incrementAndGet();
                if (degradedMode) {
                    out.add(new ClusterProse(c, SummaryProseGenerator.degradedProseFor(c), true));
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
     * Hand-rolled JDBC stub: returns the seeded {@code users} row id
     * for {@code SummaryCommandHandler.resolveScopeId}'s single SELECT.
     * Mockito is intentionally absent from the Provider classpath.
     */
    private static class StubUserDataSource extends UnsupportedDataSource {
        private final UUID userId;

        static StubUserDataSource userExists(UUID userId) {
            return new StubUserDataSource(userId);
        }

        static StubUserDataSource neverCalled() {
            return new StubUserDataSource(null) {
                @Override
                public Connection getConnection() {
                    throw new AssertionError(
                            "DataSource.getConnection() called on neverCalled() stub");
                }
            };
        }

        private StubUserDataSource(UUID userId) {
            this.userId = userId;
        }

        @Override
        public Connection getConnection() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] { Connection.class },
                    (proxy, method, methodArgs) -> switch (method.getName()) {
                        case "prepareStatement" -> {
                            String sql = (String) methodArgs[0];
                            yield newPreparedStatement(sql);
                        }
                        case "close" -> null;
                        case "toString" -> "StubConnection";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == methodArgs[0];
                        default -> throw new UnsupportedOperationException(
                                "Connection." + method.getName() + " not stubbed");
                    });
        }

        private PreparedStatement newPreparedStatement(String sql) {
            boolean isScopePrefsQuery = sql.contains("scope_preferences");
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[] { PreparedStatement.class },
                    (proxy, method, methodArgs) -> switch (method.getName()) {
                        case "setString", "setObject" -> null;
                        case "executeQuery" ->
                                isScopePrefsQuery ? newLanguageResultSet() : newUserIdResultSet();
                        case "close" -> null;
                        case "toString" -> "StubPreparedStatement";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == methodArgs[0];
                        default -> throw new UnsupportedOperationException(
                                "PreparedStatement." + method.getName() + " not stubbed");
                    });
        }

        private ResultSet newUserIdResultSet() {
            boolean[] consumed = { false };
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[] { ResultSet.class },
                    (proxy, method, methodArgs) -> switch (method.getName()) {
                        case "next" -> {
                            if (consumed[0]) yield false;
                            consumed[0] = true;
                            yield true;
                        }
                        case "getObject" -> userId;
                        case "close" -> null;
                        case "toString" -> "StubResultSet(userId)";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == methodArgs[0];
                        default -> throw new UnsupportedOperationException(
                                "ResultSet." + method.getName() + " not stubbed");
                    });
        }

        private ResultSet newLanguageResultSet() {
            boolean[] consumed = { false };
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[] { ResultSet.class },
                    (proxy, method, methodArgs) -> switch (method.getName()) {
                        case "next" -> {
                            if (consumed[0]) yield false;
                            consumed[0] = true;
                            yield true;
                        }
                        case "getString" -> "en";
                        case "close" -> null;
                        case "toString" -> "StubResultSet(language)";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == methodArgs[0];
                        default -> throw new UnsupportedOperationException(
                                "ResultSet." + method.getName() + " not stubbed");
                    });
        }
    }

    /**
     * Base implementation of {@link DataSource} that throws
     * {@link UnsupportedOperationException} for everything except
     * {@link #getConnection()}.
     */
    private static class UnsupportedDataSource implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            throw new UnsupportedOperationException("getConnection() not stubbed");
        }

        @Override
        public Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException("getConnection(String,String) not stubbed");
        }

        @Override
        public PrintWriter getLogWriter() {
            throw new UnsupportedOperationException("getLogWriter not stubbed");
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            throw new UnsupportedOperationException("setLogWriter not stubbed");
        }

        @Override
        public void setLoginTimeout(int seconds) {
            throw new UnsupportedOperationException("setLoginTimeout not stubbed");
        }

        @Override
        public int getLoginTimeout() {
            throw new UnsupportedOperationException("getLoginTimeout not stubbed");
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException("unwrap not stubbed");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    /**
     * Recording stub for {@link SummaryAnchorRepository}: captures
     * write calls without touching the DB.
     */
    private static final class RecordingSummaryAnchorRepository extends SummaryAnchorRepository {
        private final AtomicInteger writes = new AtomicInteger();
        private volatile List<String> lastPostUids = List.of();

        @Override
        public void write(UUID userId, UUID scopeId,
                          String commandName, String argHash,
                          List<String> postUids, String clusterMapJson) {
            writes.incrementAndGet();
            lastPostUids = List.copyOf(postUids);
        }

        @Override
        public Optional<AnchorRow> read(UUID userId, UUID scopeId) {
            return Optional.empty();
        }

        @Override
        public void clear(UUID userId, UUID scopeId) {
            // no-op in test
        }

        int writeCount() { return writes.get(); }
        List<String> lastPostUids() { return lastPostUids; }
    }
}
