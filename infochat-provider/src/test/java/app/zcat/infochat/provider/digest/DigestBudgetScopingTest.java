package app.zcat.infochat.provider.digest;

import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.digest.DigestRenderer.DigestMode;
import app.zcat.infochat.provider.digest.DigestRenderer.RenderedSection;
import app.zcat.infochat.provider.digest.DigestRetryService.RetryResult;
import app.zcat.infochat.provider.messaging.OutboundDelivery;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.provider.summary.ClusterTraversal;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import app.zcat.infochat.provider.summary.EmptyEdgeSource;
import app.zcat.infochat.provider.summary.SummaryProseGenerator;
import app.zcat.infochat.provider.summary.SummaryProseGenerator.ClusterProse;
import app.zcat.infochat.provider.testsupport.LlmChainFixtures;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import app.zcat.infochat.provider.translation.TranslationCache;
import app.zcat.infochat.provider.group.GroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static app.zcat.infochat.provider.testsupport.TranslationFixtures.newRealBundleLoader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WHERE the system LLM budget is drawn, as opposed to how much (M1-769
 * acceptance items 2 and 5). The generative helpers are shared: the same
 * {@code SummaryProseGenerator}, {@code CategoryRollupGenerator} and
 * {@code TranslationPipeline} instances serve the scheduled digest,
 * {@code /summary} and {@code /retry}. Only the binding decides what
 * charges, so only a test that runs BOTH kinds of route against ONE
 * budget can prove the split holds.
 *
 * <p>Every leg here issues REAL provider calls — a zero draw next to a
 * zero call count would prove nothing at all.</p>
 */
class DigestBudgetScopingTest {

    private static final UUID GROUP_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final String ADAPTER_NAME = "inmemory";
    private static final String UPSTREAM_GROUP_ID = "group-scoping";
    private static final String SLOT_KIND = "morning";
    private static final Instant NOW = Instant.parse("2026-08-05T09:00:00Z");
    private static final Instant SLOT_FIRED_AT = Instant.parse("2026-08-05T07:45:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    private BundleLoader bundleLoader;
    private LlmChainFixtures.Chain chain;
    private SystemLlmBudget budget;
    private DigestRenderer renderer;
    private SummaryProseGenerator proseGenerator;

    @BeforeEach
    void setUp() throws Exception {
        bundleLoader = newRealBundleLoader();
        chain = LlmChainFixtures.newChain(bundleLoader, FIXED);

        budget = new SystemLlmBudget();
        budget.window = Duration.ofHours(24);
        budget.ceiling = 1000;
        budget.groupReserve = 0;
        budget.clock = FIXED;

        proseGenerator = LlmChainFixtures.newProseGenerator(chain);

        CategoryRollupGenerator rollupGenerator = new CategoryRollupGenerator();
        rollupGenerator.llmRouter = chain.router;
        rollupGenerator.llmOutputSanitizer = SanitizerTestDoubles.noAuditSanitizer();
        rollupGenerator.translationPipeline = chain.translationPipeline;

        renderer = new DigestRenderer();
        renderer.clusterTraversal = new ClusterTraversal(new EmptyEdgeSource(), 3);
        renderer.summaryProseGenerator = proseGenerator;
        renderer.categoryRollupGenerator = rollupGenerator;
        renderer.llmOutputSanitizer = SanitizerTestDoubles.noAuditSanitizer();
        renderer.translationPipeline = chain.translationPipeline;
        renderer.translationCache = new TranslationCache();
        renderer.digestCategorizer = newCategorizer();
        renderer.bundleLoader = bundleLoader;
        renderer.categoryItemCap = 12;
        renderer.categoryHeadlineCount = 5;
        renderer.translationMaxPerRender = 5;
        renderer.systemLlmBudget = budget;
        renderer.leadMinimum = Integer.MAX_VALUE;
    }

    @Test
    void summaryDefaultRenderDrawsNothingThoughItSharesEveryGenerator() {
        // The /summary default path: the handler generates prose itself,
        // then renders through renderSummarySections — the same generator
        // and the same renderer instance the digest uses, deliberately NOT
        // through renderSections, which is the only binder.
        List<Cluster> clusters = renderer.clusterTraversal.cluster(posts(3, "ai"));
        List<ClusterProse> prose = proseGenerator.generate(clusters, "cs");
        renderer.renderSummarySections(prose, "cs");

        assertTrue(chain.providerCalls() > 0,
                "control: this really did issue provider calls — otherwise the zero below "
                        + "would be an artifact of a fixture that spends nothing");
        assertEquals(0, budget.callsInWindow(),
                "a user-initiated route is metered by LlmRateCap and the D47 per-group "
                        + "sub-bucket; drawing here too would double-meter it and break the "
                        + "spec's system-vs-user-initiated split");
    }

    @Test
    void summaryShortRenderDrawsNothingThoughItSharesTheRollupGenerator() {
        // /summary --short reaches CategoryRollupGenerator through
        // renderShortBody — the same generator renderSections calls.
        renderer.renderShortBody(renderer.clusterTraversal.cluster(posts(3, "ai")), "cs");

        assertTrue(chain.providerCalls() > 0, "control: the roll-up really did call the provider");
        assertEquals(0, budget.callsInWindow(),
                "renderShortBody is unbound, so the shared roll-up generator charges nothing");
    }

    @Test
    void retryFallbackRerunDrawsItsExactCalls() {
        // The one user-initiated route that DOES bind the pool (M1-767
        // policy, not re-decided here): its re-run reaches the very same
        // render, so its calls are genuinely digest cost.
        //
        // Same live cache row as the replay test below — the ONE variable
        // between the two is whether sections were persisted. That is the
        // real fork in retryDigest, and holding everything else equal is
        // what makes the pair a scoping test rather than two unrelated runs.
        Fixture fixture = newRetryFixture(List.of());

        RetryResult result = fixture.service.retryDigest(GROUP_ID);

        assertEquals(RetryResult.SUCCESS, result);
        assertTrue(chain.providerCalls() > 0, "control: the re-run rendered for real");
        assertEquals(chain.providerCalls(), budget.callsInWindow(),
                "the fallback re-run charges exactly the calls it issued — the sink reads "
                        + "the same on both bound entry points");
    }

    @Test
    void retryReplayLegDrawsNothing() {
        // A live cache row WITH persisted sections replays the stored bytes
        // and never re-renders, so it must charge nothing — otherwise a
        // user could drain the deployment's digest budget by replaying.
        Fixture fixture = newRetryFixture(
                List.of(new RenderedSection("ai", "persisted section bytes")));

        RetryResult result = fixture.service.retryDigest(GROUP_ID);

        assertNotEquals(RetryResult.NO_PRIOR_DIGEST, result);
        assertEquals(0, fixture.worker.executeCount(),
                "control: the replay leg must not have re-rendered at all");
        assertEquals(0, chain.providerCalls(), "control: and so issued no provider call");
        assertEquals(0, budget.callsInWindow(), "so it charges nothing");
    }

    /** A real {@link DigestRetryService} over a real {@link DigestWorker}. */
    private record Fixture(DigestRetryService service, CountingDigestWorker worker) {}

    /**
     * A live (unexpired) cache row plus {@code persistedSections}. Empty
     * sections is the fallback fork, non-empty the replay fork.
     */
    private Fixture newRetryFixture(List<RenderedSection> persistedSections) {
        CountingDigestWorker worker = new CountingDigestWorker();
        // The worker gates the render on time remaining in the slot window,
        // so it must read the same pinned clock the retry service does — on
        // a real clock every fixture window is long past and the render
        // degrades before it can spend anything.
        worker.clock = FIXED;
        worker.postCollector = seededCollector();
        worker.digestRenderer = renderer;
        worker.degradedRenderer = new RecordingDegradedRenderer();
        worker.cacheRepository = new RecordingCacheRepository();
        worker.sectionRepository = new RecordingSectionRepository();
        worker.bundleLoader = bundleLoader;
        worker.adapterRegistry = new DigestRetryServiceTest.StubAdapterRegistry(ADAPTER_NAME);
        worker.dataSource = new StubGroupDataSource(ADAPTER_NAME, UPSTREAM_GROUP_ID, "en", "full");
        worker.replayRetention = Duration.ofHours(24);
        worker.morningSlotHour = 8;
        worker.eveningSlotHour = 20;
        worker.systemLlmBudget = budget;
        worker.outboundDelivery = new OutboundDelivery(
                new ThrottledAdminNotifier(),
                new GroupRepository(
                        new StubGroupDataSource(ADAPTER_NAME, UPSTREAM_GROUP_ID, "en", "full")),
                3, 0L, 2.0, 3);
        DigestDelivery workerDelivery = new DigestDelivery();
        workerDelivery.outboundDelivery = worker.outboundDelivery;
        workerDelivery.deliveryRepository = new RecordingCategoryDeliveryRepository();
        worker.digestDelivery = workerDelivery;

        RecordingSectionRepository serviceSections = new RecordingSectionRepository();
        serviceSections.seedSections(persistedSections);

        DigestRetryService service = new DigestRetryService();
        service.digestWorker = worker;
        service.sectionRepository = serviceSections;
        service.deliveryRepository = new RecordingCategoryDeliveryRepository();
        service.digestDelivery = new DigestRetryServiceTest.RecordingDigestDelivery();
        service.adapterRegistry = new DigestRetryServiceTest.StubAdapterRegistry(ADAPTER_NAME);
        service.retryCooldown = Duration.ofMinutes(2);
        service.windowWidthMinutes = 30;
        service.clock = FIXED;
        service.dataSource = retryDataSource(NOW.plus(Duration.ofMinutes(30)));
        return new Fixture(service, worker);
    }

    private RecordingPostCollector seededCollector() {
        RecordingPostCollector collector = new RecordingPostCollector();
        collector.seed(posts(3, "ai"), 3, 5);
        return collector;
    }

    /** Counts renders so the replay leg's "never re-rendered" control is real. */
    private static final class CountingDigestWorker extends DigestWorker {
        private int executeCount;

        int executeCount() {
            return executeCount;
        }

        @Override
        public SlotOutcome execute(DigestSlot slot) {
            executeCount++;
            return super.execute(slot);
        }

        /**
         * Must override, even though nothing calls it: the inherited
         * method carries {@code @Observes}, and an un-overridden subclass
         * therefore registers as a second observer bean, making {@code
         * DigestRetryService#digestWorker} ambiguous and failing the whole
         * container build. The {@code RecordingDigestWorker} precedent in
         * {@link DigestRetryServiceTest} does the same.
         */
        @Override
        public void onDigestSlot(DigestSlot slot) {
            // no-op: this test drives execute(...) through the retry service
        }
    }

    /**
     * The two lookups {@link DigestRetryService#retryDigest} makes before
     * it can choose a leg: the latest {@code summary_cache} row (whose
     * {@code expires_at} IS the leg selector) and the group's replay
     * metadata. Everything else answers empty.
     */
    private static DataSource retryDataSource(Instant expiresAt) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[] { DataSource.class },
                (dsProxy, dsMethod, dsArgs) -> switch (dsMethod.getName()) {
                    case "getConnection" -> connection(expiresAt);
                    case "isWrapperFor" -> false;
                    case "getParentLogger" -> throw new SQLFeatureNotSupportedException();
                    case "getLogWriter" -> (PrintWriter) null;
                    case "getLoginTimeout" -> 0;
                    case "toString" -> "retryDataSource";
                    case "hashCode" -> System.identityHashCode(dsProxy);
                    case "equals" -> dsProxy == dsArgs[0];
                    default -> throw new UnsupportedOperationException("DataSource." + dsMethod.getName());
                });
    }

    private static Connection connection(Instant expiresAt) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> preparedStatement((String) args[0], expiresAt);
                    case "close" -> null;
                    case "toString" -> "retryConnection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("Conn." + method.getName());
                });
    }

    private static PreparedStatement preparedStatement(String sql, Instant expiresAt) {
        boolean cacheQuery = sql.contains("summary_cache");
        boolean groupQuery = sql.contains("FROM groups");
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] { PreparedStatement.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "setString", "setObject", "setTimestamp", "setInt", "close" -> null;
                    case "executeUpdate" -> 1;
                    case "executeQuery" -> {
                        if (cacheQuery) {
                            yield singleRow(Map.of(
                                    "slot_kind", SLOT_KIND,
                                    "slot_fired_at", Timestamp.from(SLOT_FIRED_AT),
                                    "expires_at", Timestamp.from(expiresAt)));
                        }
                        if (groupQuery) {
                            yield singleRow(Map.of(
                                    "timezone", "UTC",
                                    "adapter", ADAPTER_NAME,
                                    "upstream_group_id", UPSTREAM_GROUP_ID,
                                    "digest_mode", "full"));
                        }
                        yield emptyRow();
                    }
                    case "toString" -> "retryPreparedStatement";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("PS." + method.getName());
                });
    }

    private static ResultSet singleRow(Map<String, Object> columns) {
        boolean[] consumed = { false };
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> {
                        if (consumed[0]) {
                            yield false;
                        }
                        consumed[0] = true;
                        yield true;
                    }
                    case "getString", "getTimestamp", "getObject" -> columns.get((String) args[0]);
                    case "close" -> null;
                    case "toString" -> "singleRow";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("RS." + method.getName());
                });
    }

    private static ResultSet emptyRow() {
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> false;
                    case "close" -> null;
                    case "toString" -> "emptyRow";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("RS." + method.getName());
                });
    }

    private static DigestCategorizer newCategorizer() {
        DigestCategorizer categorizer = new DigestCategorizer();
        categorizer.categoryMinClusters = 3;
        return categorizer;
    }

    private static List<Post> posts(int count, String tag) {
        List<Post> posts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String uid = tag + i;
            posts.add(new Post(
                    UUID.randomUUID(), uid, UUID.randomUUID(), "TestSrc",
                    "Headline " + tag + " " + i, "https://example.com/" + uid, "body",
                    // Fixed, not wall-clock: a relative fixture time is the
                    // date-boundary bomb M1-602 censused, and this one is
                    // additionally filtered against the slot's lower bound.
                    NOW.minus(Duration.ofMinutes(10)), List.of(tag), List.of("unknown"),
                    null, null, null, null, "en"));
        }
        return posts;
    }
}
