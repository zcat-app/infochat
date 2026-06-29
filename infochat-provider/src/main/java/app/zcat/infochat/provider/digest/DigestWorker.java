package app.zcat.infochat.provider.digest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.sql.DataSource;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.digest.DigestPostCollector.CollectionResult;
import app.zcat.infochat.provider.messaging.AdapterRegistry;
import app.zcat.infochat.provider.messaging.OutboundDelivery;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Observes {@link DigestSlot} events fired by {@link DigestScheduler},
 * orchestrates the full digest pipeline: collect posts → render prose
 * (or degrade) → cache → deliver. Each slot is independent — a degraded
 * result does not affect subsequent slots.
 */
@ApplicationScoped
public class DigestWorker {

    private static final Logger LOG = Logger.getLogger(DigestWorker.class);

    // Bean-owned, not static: the executor's lifetime is tied to this
    // @ApplicationScoped bean (shut down in @PreDestroy with the application),
    // so render threads cannot outlive CDI shutdown.
    private final ExecutorService renderExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    @Inject
    DigestPostCollector postCollector;

    @Inject
    DigestRenderer digestRenderer;

    @Inject
    DegradedDigestRenderer degradedRenderer;

    @Inject
    SummaryCacheRepository cacheRepository;

    @Inject
    BundleLoader bundleLoader;

    @Inject
    AdapterRegistry adapterRegistry;

    @Inject
    OutboundDelivery outboundDelivery;

    @Inject
    DataSource dataSource;

    // The now-vs-windowEnd deadline that drives the degrade-vs-render +
    // timeout-budget decision is a decision-gate "now", so it reads from the
    // injected Clock to stay pinnable in tests (M1-454, engineering-rules §9).
    // The OutboundMessage send-timestamp below stays on Instant.now() — it
    // records, it gates nothing (§9 display/record exemption). CDI overrides
    // the systemUTC() default at runtime (M1-444 reference).
    @Inject
    Clock clock = Clock.systemUTC();

    // How long the cache row stays findable (non-expired) past the slot
    // window for /retry --digest. Reuses DigestRetryService's cooldown
    // property rather than introducing a new constant: the row's expires_at
    // becomes the synthetic retry slot's windowEnd, so it must outlive the
    // window the retry the cooldown permits is issued in.
    @ConfigProperty(name = "infochat.digest.retry-cooldown", defaultValue = "PT2M")
    Duration retryHorizon;

    // In-flight guard (ConcurrentHashMap-backed, keyed groupId+slotKind): a
    // scheduler tick overrun re-fires a slot whose previous execution is
    // still running; overlapping same-group processing would double-deliver.
    private final Set<String> inFlightSlots = ConcurrentHashMap.newKeySet();

    /** Whether a {@link #execute} call actually processed the slot. */
    public enum SlotOutcome {
        /** The slot ran (content generated and cached, even if it degraded). */
        RAN,
        /** Skipped: the in-flight guard for this (group, slotKind) was held. */
        SKIPPED_IN_FLIGHT
    }

    /** Void wrapper for the CDI observer (observer methods must return void). */
    public void onDigestSlot(@Observes DigestSlot slot) {
        execute(slot);
    }

    @PreDestroy
    void shutdownRenderExecutor() {
        renderExecutor.shutdown();
    }

    /**
     * Process one digest slot, reporting whether it actually ran. Returns
     * {@link SlotOutcome#SKIPPED_IN_FLIGHT} without touching the cache when a
     * concurrent execution already holds the in-flight guard — the caller
     * (e.g. {@link DigestRetryService}) relies on this distinction to avoid
     * reporting a skipped retry as a success.
     */
    public SlotOutcome execute(DigestSlot slot) {
        String inFlightKey = slot.groupId() + ":" + slot.slotKind();
        if (!inFlightSlots.add(inFlightKey)) {
            LOG.warnf("Digest already in flight for group %s slot %s — skipping overlapping execution",
                    slot.groupId(), slot.slotKind());
            return SlotOutcome.SKIPPED_IN_FLIGHT;
        }
        try {
            executeSlot(slot);
        } catch (SQLException e) {
            // Expected operational failures only — programming errors propagate.
            // Outbound delivery failures no longer surface here: the chokepoint
            // absorbs them (retry/abort) inside executeSlot.
            LOG.errorf(e, "Digest failed for group %s slot %s", slot.groupId(), slot.slotKind());
        } finally {
            inFlightSlots.remove(inFlightKey);
        }
        return SlotOutcome.RAN;
    }

    private void executeSlot(DigestSlot slot) throws SQLException {
        // Collect the full inter-digest period, not just the slot window: the
        // lower bound is the previous digest boundary (latest summary_cache
        // row before this slot), so posts published between two slots appear
        // in the next digest. First-ever digest falls back to the slot
        // window; a missed slot's sentinel row counts as a boundary
        // (skip-not-catch-up — its period is not folded into the next digest).
        Instant collectFrom = cacheRepository
                .findPreviousBoundary(slot.groupId(), slot.windowStart())
                .orElse(slot.windowStart());
        CollectionResult collection =
                postCollector.collectForGroup(slot.groupId(), collectFrom);
        GroupMetadata meta = readGroupMetadata(slot.groupId());

        String content;
        boolean isDegraded = false;

        if (collection.posts().isEmpty()) {
            content = bundleLoader.get(BundleKeys.REPLY_SUMMARY_NO_POSTS_YET, meta.language());
        } else {
            Duration remaining = Duration.between(clock.instant(), slot.windowEnd());
            if (remaining.isNegative() || remaining.isZero()) {
                content = degradedRenderer.render(collection.posts());
                isDegraded = true;
            } else {
                CompletableFuture<String> renderFuture = CompletableFuture.supplyAsync(
                        () -> digestRenderer.render(collection.posts(), meta.language()),
                        renderExecutor);
                try {
                    content = renderFuture.get(remaining.toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException | ExecutionException | InterruptedException e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    // Cancel the orphaned render rather than leaving it to run
                    // past the slot window after we have already degraded — the
                    // get(timeout) above stops waiting but does not stop the work
                    // behind the future. (M1-494 13#F4)
                    renderFuture.cancel(true);
                    content = degradedRenderer.render(collection.posts());
                    isDegraded = true;
                }
            }
        }

        cacheRepository.upsert(
                slot.groupId(),
                slot.slotKind(),
                slot.windowStart(),
                collection.tagSubscriptionVersion(),
                collection.sourceSubscriptionVersion(),
                content,
                isDegraded,
                // Outlive the slot window by the retry horizon so a
                // /retry --digest issued after windowEnd still finds a
                // non-expired row instead of degrading immediately.
                slot.windowEnd().plus(retryHorizon));

        MessagingAdapter adapter = findAdapter(meta.adapterName());
        if (adapter == null) {
            LOG.warnf("No activated adapter '%s' for group %s — digest cached but not delivered",
                    meta.adapterName(), slot.groupId());
            return;
        }

        String correlationId = "digest-" + slot.groupId() + "-" + slot.windowStart();
        OutboundMessage msg = new OutboundMessage(
                new ScopeRef.Group(meta.upstreamGroupId()),
                content,
                Instant.now(),
                correlationId);
        // Route through the chokepoint: retry on TRANSIENT, abort on
        // PERMANENT, and feed the per-group permanent-failure counter that
        // drives bot-removed cleanup. A null return means the delivery was
        // aborted — logged here; the next slot retries (spec §Failure handling).
        if (outboundDelivery.deliverToGroup(adapter, msg, slot.groupId()) == null) {
            LOG.warnf("Digest delivery aborted for group %s slot %s",
                    slot.groupId(), slot.slotKind());
        }
    }

    record GroupMetadata(String adapterName,
                         String upstreamGroupId,
                         String language) {}

    GroupMetadata readGroupMetadata(UUID groupId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(GROUP_META_SQL)) {
            ps.setObject(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Group not found: " + groupId);
                }
                return new GroupMetadata(
                        rs.getString("adapter"),
                        rs.getString("upstream_group_id"),
                        rs.getString("language"));
            }
        }
    }

    private @Nullable MessagingAdapter findAdapter(String adapterName) {
        for (MessagingAdapter adapter : adapterRegistry.activatedAdapters()) {
            if (adapter.name().equals(adapterName)) {
                return adapter;
            }
        }
        return null;
    }

    private static final String GROUP_META_SQL = """
            SELECT g.adapter, g.upstream_group_id,
                   COALESCE(sp.language, 'en') AS language
              FROM groups g
              LEFT JOIN scope_preferences sp
                ON sp.scope_kind = 'group' AND sp.scope_id = g.id
             WHERE g.id = ?""";
}
