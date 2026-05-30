package app.zcat.infochat.collector.fetch;

import app.zcat.infochat.collector.outbox.EvalQueueProducer;
import app.zcat.infochat.collector.outbox.PostPersister;
import app.zcat.infochat.core.ingest.Fetcher;
import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import io.quarkus.arc.All;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;

/**
 * Drives the per-source fetch loop for all polled source kinds.
 *
 * <h2>Polymorphic dispatch</h2>
 * <p>At startup, discovers all {@link Fetcher} CDI beans annotated with
 * {@link FetcherKind} and builds a kind&rarr;Fetcher dispatch map. A
 * periodic heartbeat enumerates all active sources and dispatches each
 * to the Fetcher registered for its kind. Sources whose kind has no
 * registered Fetcher are skipped with a WARN log (once per kind per
 * scheduler lifecycle).
 *
 * <h2>Per-kind intervals</h2>
 * <p>Each source kind ticks at its own configured interval via
 * {@code infochat.fetch.<kind>.interval}. A kind whose interval has
 * not elapsed since its last tick is skipped until the next heartbeat.
 *
 * <h2>Outbox discipline</h2>
 * <p>Per tick, for each enabled source: invoke the matching Fetcher,
 * then for each {@link NormalizedPost} call {@link PostPersister#persist}
 * BEFORE {@link EvalQueueProducer#emit}. Persist-then-enqueue is the
 * outbox discipline per {@code docs/spec/architecture.md} §Pipelines
 * and §Architectural principles 2 — a crash between the two leaves the
 * post recoverable via
 * {@link app.zcat.infochat.collector.outbox.OutboxRehydrator}
 * on next startup. When the persist returns a no-op (ON CONFLICT dedup
 * hit), the enqueue is skipped.
 *
 * <h2>Startup ordering</h2>
 * <p>{@code @Priority(400)} per
 * {@code docs/design/01-architecture.md} §1.4.3 — runs after
 * Flyway (100), BootstrapLoader (200), and OutboxRehydrator (300),
 * and before any future {@code StreamSourceSupervisor} (450). The
 * rehydrator's older {@code fetched_at} posts drain ahead of new
 * fetches naturally.
 *
 * <h2>Failure handling (D42)</h2>
 * <p>Per-tick exceptions are caught and logged at WARN with the
 * {@code source.id} (the UUID — never the source identifier URL,
 * which can carry embedded credentials per M1-023's redteam
 * INFO-LEAK finding). After the log, {@link SourceRepository#recordFailure}
 * increments {@code source.consecutive_failures}, refreshes
 * {@code last_fetch_at}, and flips {@code status} from
 * {@code active} to {@code failed} when the post-increment counter
 * reaches {@link #failureThreshold}. On the crossing tick (and only
 * on that tick), {@link ThrottledAdminNotifier#notifyOnce} fires a
 * coalesced admin notification keyed on the source UUID.
 *
 * <p>On a successful tick {@link SourceRepository#recordSuccess}
 * zeroes the counter and refreshes both {@code last_fetch_at} and
 * {@code last_success_at}. A failed source is mechanically excluded
 * from {@link #enumerateActiveSources} (the SELECT filters
 * {@code status = 'active'}); recovery is operator-driven via
 * {@code /source-enable}, which resets the counter back to 0 (the
 * SOURCE_ENABLE handler's UPDATE already sets
 * {@code consecutive_failures = 0} so a re-enabled row does not
 * immediately re-trip the threshold).
 */
@Startup
@Priority(400)
@ApplicationScoped
public class FetchScheduler {

    private static final Logger LOG = Logger.getLogger(FetchScheduler.class);

    private static final Duration DEFAULT_KIND_INTERVAL = Duration.ofMinutes(5);

    @Inject
    DataSource dataSource;

    @Inject
    PostPersister postPersister;

    @Inject
    EvalQueueProducer evalQueueProducer;

    @Inject
    Config config;

    @Inject
    SourceRepository sourceRepository;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    // Single-global tunable (no per-profile branching), so inline
    // defaultValue is allowed per the AssetSnapshotFetcher convention.
    // Operator override:
    // -Dinfochat.fetch.failure-threshold=<count>.
    @ConfigProperty(name = "infochat.fetch.failure-threshold", defaultValue = "5")
    int failureThreshold;

    @Inject
    @All
    List<InstanceHandle<Fetcher>> fetcherHandles;

    private final Map<String, Fetcher> fetchersByKind = new HashMap<>();

    // Per-kind last-tick tracking for interval gating.
    private final Map<String, Instant> lastTickByKind = new ConcurrentHashMap<>();

    // Tracks kinds already warned about (no registered Fetcher) to
    // avoid WARN-per-heartbeat noise for expected orphan kinds
    // (bootstrap-sources.json ships bluesky/nostr before their
    // Fetcher implementations land).
    private final Set<String> warnedOrphanKinds = ConcurrentHashMap.newKeySet();

    @PostConstruct
    void discoverFetchers() {
        for (InstanceHandle<Fetcher> handle : fetcherHandles) {
            FetcherKind kind = handle.getBean().getBeanClass().getAnnotation(FetcherKind.class);
            if (kind == null) {
                continue;
            }
            Fetcher prev = fetchersByKind.put(kind.value(), handle.get());
            if (prev != null) {
                throw new IllegalStateException(
                    "Duplicate Fetcher for kind '" + kind.value() + "': "
                    + prev.getClass().getSimpleName() + " and "
                    + handle.getBean().getBeanClass().getSimpleName());
            }
            LOG.infof("Registered fetcher for kind '%s': %s",
                kind.value(), handle.getBean().getBeanClass().getSimpleName());
        }
    }

    /**
     * Heartbeat tick that drives per-kind dispatch. Fires at a base
     * interval; each registered kind is gated by its own configured
     * interval ({@code infochat.fetch.<kind>.interval}).
     */
    @Scheduled(every = "{infochat.fetch.heartbeat-interval}")
    void onTick() {
        Instant now = Instant.now();

        Set<String> kindsToTick = new HashSet<>();
        for (String kind : fetchersByKind.keySet()) {
            if (shouldTick(kind, now)) {
                kindsToTick.add(kind);
            }
        }
        if (kindsToTick.isEmpty()) {
            return;
        }

        List<SourceRow> rows;
        try {
            rows = enumerateActiveSources();
        } catch (SQLException e) {
            LOG.warn("FetchScheduler: failed to enumerate sources; skipping tick", e);
            return;
        }
        for (SourceRow row : rows) {
            if (!kindsToTick.contains(row.kind())) {
                if (!fetchersByKind.containsKey(row.kind())
                        && warnedOrphanKinds.add(row.kind())) {
                    LOG.warnf("No fetcher registered for source kind '%s', skipping",
                        row.kind());
                }
                continue;
            }
            tickOnce(row);
        }

        for (String kind : kindsToTick) {
            lastTickByKind.put(kind, now);
        }
    }

    /**
     * Fetch one source's current batch, persist each post as
     * {@code 'RAW'}, then enqueue the persisted-post keys onto
     * {@code eval-queue}. Public so the IT can invoke ticks
     * deterministically without waiting on the scheduler clock.
     *
     * @param row the source row to tick (already enumerated as
     *            {@code status='active' AND deleted_at IS NULL}).
     */
    public void tickOnce(@NonNull SourceRow row) {
        Fetcher fetcher = fetchersByKind.get(row.kind());
        if (fetcher == null) {
            if (warnedOrphanKinds.add(row.kind())) {
                LOG.warnf("No fetcher registered for source kind '%s', skipping",
                    row.kind());
            }
            return;
        }
        try {
            List<NormalizedPost> posts = fetcher.fetch(row.dispatchKey(), row.identifier());
            for (NormalizedPost post : posts) {
                Optional<PostPersister.PersistedPostKey> key =
                    postPersister.persist(row.uuid(), post);
                // Persist-before-enqueue per the outbox discipline.
                // On ON-CONFLICT dedup (empty), skip the enqueue —
                // the post has already been emitted on a prior tick.
                key.ifPresent(evalQueueProducer::emit);
            }
            // D42 success path: zero the counter, refresh both
            // timestamps. Done AFTER persist+enqueue so a mid-loop
            // PostPersister/EvalQueueProducer throw lands on the
            // failure path (the catch below).
            try {
                sourceRepository.recordSuccess(row.uuid());
            } catch (SQLException sqlE) {
                // The DB write itself failed; the tick's posts already
                // landed, so this is a bookkeeping miss, not a tick
                // failure. Log and continue — the next successful tick
                // re-establishes the counter/timestamp state.
                LOG.warnf(sqlE,
                    "FetchScheduler: failed to record success for source uuid=%s",
                    row.uuid());
            }
        } catch (Exception e) {
            // Log the numeric dispatch key + UUID; NEVER the
            // identifier URL (which can carry embedded credentials
            // per M1-023's redteam INFO-LEAK finding).
            LOG.warnf(e,
                "FetchScheduler tick failed for source uuid=%s (dispatch=%d)",
                row.uuid(), row.dispatchKey());
            // D42 failure path: increment counter, refresh
            // last_fetch_at, flip active→failed when threshold
            // reached. Fire the throttled admin notification once on
            // the crossing tick.
            try {
                SourceRepository.FailureOutcome outcome =
                    sourceRepository.recordFailure(row.uuid(), failureThreshold);
                if (outcome.crossedThreshold()) {
                    throttledAdminNotifier.notifyOnce(
                        "fetch_failure_ladder:" + row.uuid(),
                        "fetch_failure_ladder",
                        "Source uuid=" + row.uuid() + " kind=" + row.kind()
                            + " transitioned to status='failed' after "
                            + outcome.consecutiveFailures()
                            + " consecutive failures; last error class="
                            + e.getClass().getSimpleName());
                }
            } catch (SQLException sqlE) {
                LOG.warnf(sqlE,
                    "FetchScheduler: failed to record failure for source uuid=%s",
                    row.uuid());
            }
        }
    }

    /**
     * Reads all active source rows regardless of kind. Public so the
     * IT can re-invoke after seeding test sources mid-test.
     *
     * <p>Soft-deleted rows ({@code deleted_at IS NOT NULL}) and
     * non-active rows ({@code status != 'active'}) are skipped.
     */
    public List<SourceRow> enumerateActiveSources() throws SQLException {
        final String sql =
            "SELECT id, identifier, kind FROM source "
                + "WHERE status = 'active' "
                + "  AND deleted_at IS NULL "
                + "ORDER BY added_at, id";

        List<SourceRow> rows = new ArrayList<>();
        long dispatch = 1L;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID id = (UUID) rs.getObject(1);
                String identifier = rs.getString(2);
                String kind = rs.getString(3);
                rows.add(new SourceRow(id, identifier, dispatch++, kind));
            }
        }
        return rows;
    }

    private boolean shouldTick(String kind, Instant now) {
        Instant lastTick = lastTickByKind.get(kind);
        if (lastTick == null) {
            return true;
        }
        Duration interval = getKindInterval(kind);
        return Duration.between(lastTick, now).compareTo(interval) >= 0;
    }

    private Duration getKindInterval(String kind) {
        return config.getOptionalValue("infochat.fetch." + kind + ".interval", Duration.class)
            .orElse(DEFAULT_KIND_INTERVAL);
    }

    /**
     * One enumerated source row. The {@code dispatchKey} is a
     * monotonically-assigned per-startup token passed to the Fetcher
     * SPI's {@code long sourceId} parameter; it is NOT the
     * {@code source.id} UUID and is opaque to the Fetcher.
     */
    public record SourceRow(@NonNull UUID uuid, @NonNull String identifier, long dispatchKey,
                               @NonNull String kind) {
    }
}
