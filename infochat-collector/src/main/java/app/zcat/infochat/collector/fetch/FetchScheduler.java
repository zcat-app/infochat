package app.zcat.infochat.collector.fetch;

import app.zcat.infochat.collector.fetcher.PaginationSaturationTracker;
import app.zcat.infochat.collector.outbox.EvalQueueProducer;
import app.zcat.infochat.collector.outbox.PostPersister;
import app.zcat.infochat.core.ingest.Fetcher;
import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.ssrf.UrlRedactor;
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
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Drives the per-source fetch loop for all polled source kinds.
 *
 * <h2>Polymorphic dispatch</h2>
 * <p>At startup, discovers all {@link Fetcher} CDI beans annotated with
 * {@link FetcherKind} and builds a kind&rarr;Fetcher dispatch map. A
 * periodic heartbeat enumerates the active sources of the kinds that are
 * due this tick (filtered at SQL) and dispatches each to the Fetcher
 * registered for its kind. A genuinely-missing polled-fetch binding (an
 * active kind with no registered Fetcher, excluding stream-shaped kinds)
 * is reported with a WARN log once per kind per scheduler lifecycle.
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
 *
 * <h2>Pagination-cap saturation (spec §Ingest SPIs)</h2>
 * <p>After each successful fetch the scheduler consumes the
 * {@link PaginationSaturationTracker} thread-local cap-hit signal a
 * paginating Fetcher may have raised, and records the tick outcome.
 * When a source saturates its per-tick page cap for
 * {@code infochat.fetch.saturation-threshold} consecutive ticks,
 * {@link ThrottledAdminNotifier#notifyOnce} fires once on the
 * transition tick, keyed on the source UUID.
 */
@Startup
@Priority(400)
@ApplicationScoped
public class FetchScheduler {

    private static final Logger LOG = Logger.getLogger(FetchScheduler.class);

    private static final Duration DEFAULT_KIND_INTERVAL = Duration.ofMinutes(5);

    // Closed v1 set of stream-shaped source kinds. Per
    // docs/spec/architecture.md §Ingest SPIs a source is EITHER polled
    // via a Fetcher OR event-driven via a StreamSource — the two MUST NOT
    // straddle — and stream kinds ingest through the
    // {@code StreamSourceSupervisor}, not this polled-fetch loop. They are
    // therefore deliberately never bound to a Fetcher, so they must be
    // excluded from the orphan-warning check: a missing Fetcher for a
    // stream kind is expected, not a misconfiguration. Without this
    // exclusion the heartbeat logs a misleading "No fetcher registered for
    // kind 'nostr'" for a deliberately-absent binding. nostr is the only
    // v1 stream kind.
    private static final Set<String> STREAM_KINDS = Set.of("nostr");

    @Inject
    DataSource dataSource;

    // The per-kind tick-interval gate reads its instant from the injected
    // Clock, not Instant.now(), so onTick is pinnable in tests. The single
    // per-tick sample feeds both shouldTick's Duration.between gate and the
    // lastTickByKind stamp, so the gate read and the stamp write never split
    // across two clocks (M1-444). (M1-449)
    @Inject
    Clock clock = Clock.systemUTC();

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

    @Inject
    PaginationSaturationTracker saturationTracker;

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

    // Per-host outbound pacing (M1-466): host -> earliest instant the next
    // request to that host may be dispatched. Pure in-memory DECISION state,
    // so it is read only from the injected Clock, never a DB clock (§Injectable
    // time). Written as each source dispatches; a source whose host is still
    // cooling down is deferred (kept in pendingByKind), never dropped. Bounded
    // by the number of distinct source hosts (tens), and a stale entry is inert
    // once `now` passes it, so no eviction is needed in v1.
    private final Map<String, Instant> hostNextAllowed = new ConcurrentHashMap<>();

    // Per-kind queue of due sources enumerated this fetch cycle but not yet
    // dispatched (M1-466). Populated by ONE enumeration when a kind becomes
    // due, then drained across heartbeats as per-host pacing frees each
    // source's host. A kind KEEPS its entry — and is therefore excluded from
    // re-enumeration while it drains — until its queue empties, at which point
    // lastTickByKind is stamped and the next kind-interval begins. This is what
    // keeps a deferred source delayed only WITHIN its cycle, never stranded a
    // whole kind-interval. Outer map ConcurrentHashMap for the same cross-tick
    // visibility reason as lastTickByKind; each Deque value is touched only
    // within a single onTick invocation (concurrentExecution=SKIP), so the
    // non-thread-safe ArrayDeque is safe.
    private final Map<String, Deque<SourceRow>> pendingByKind = new ConcurrentHashMap<>();

    // Tracks polled kinds already warned about (no registered Fetcher) to
    // avoid WARN-per-heartbeat noise. A genuine orphan is a polled kind
    // shipped in bootstrap-sources.json (or added via /add-source) before
    // its Fetcher implementation lands; STREAM_KINDS are excluded upstream
    // so they never enter this set.
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
    @Scheduled(every = "{infochat.fetch.heartbeat-interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void onTick() {
        Instant now = clock.instant();
        // Sample the pacing window ONCE per heartbeat (one config read), and
        // feed the same `now` to the dueness gate, the host-window comparison,
        // the hostNextAllowed write, and the lastTickByKind stamp — so the
        // whole component reads one clock (M1-444 / M1-449).
        Duration hostMinInterval = hostMinInterval();

        // Freshly-due kinds: a registered (bound) kind that is NOT mid-drain
        // from a prior heartbeat AND whose per-kind interval has elapsed. A
        // kind still draining its pending sources is excluded here so it is
        // not re-enumerated — its remaining due sources finish first (M1-466).
        // Built from the in-memory maps, so a fully-idle heartbeat (nothing
        // due, nothing pending) issues no SQL at all.
        Set<String> freshlyDue = new HashSet<>();
        for (String kind : fetchersByKind.keySet()) {
            if (!pendingByKind.containsKey(kind) && shouldTick(kind, now)) {
                freshlyDue.add(kind);
            }
        }
        if (freshlyDue.isEmpty() && pendingByKind.isEmpty()) {
            return;
        }

        if (!freshlyDue.isEmpty()) {
            // Orphan warning: warn once per active polled kind that has no
            // registered Fetcher. Driven by the distinct active kinds (a
            // handful of strings), not the full row set, and excludes
            // STREAM_KINDS so the signal flags only genuinely-missing
            // polled-fetch bindings. Non-fatal — a failure here must not
            // block the dispatch enumeration below.
            try {
                warnUnboundPolledKinds(distinctActiveKinds());
            } catch (SQLException e) {
                LOG.warn("FetchScheduler: failed to enumerate source kinds for orphan check", e);
            }

            // Enumerate only the freshly-due kinds' rows at SQL: the result
            // set scales by due-source count, not total active source count.
            // Stream kinds never appear here — they are never in
            // fetchersByKind, hence never freshly-due. On enumeration failure
            // we skip the whole heartbeat (no pending entries created yet, so
            // these kinds stay freshly-due and retry next heartbeat) — the
            // same skip semantics the single-pass loop had.
            List<SourceRow> rows;
            try {
                rows = enumerateActiveSourcesByKinds(freshlyDue);
            } catch (SQLException e) {
                LOG.warn("FetchScheduler: failed to enumerate sources; skipping tick", e);
                return;
            }
            // Give every freshly-due kind a pending queue (empty when it has
            // no active rows) BEFORE partitioning rows in, so a zero-row kind
            // is still stamped by the drain below rather than re-enumerated
            // every heartbeat.
            for (String kind : freshlyDue) {
                pendingByKind.put(kind, new ArrayDeque<>());
            }
            for (SourceRow row : rows) {
                // computeIfAbsent always returns the deque seeded above (every
                // enumerated row's kind is in freshlyDue); the fallback is dead
                // but keeps the result non-null for the dereference.
                pendingByKind.computeIfAbsent(row.kind(), k -> new ArrayDeque<>()).add(row);
            }
        }

        drainPending(now, hostMinInterval);
    }

    /**
     * Dispatch as many pending due sources as per-host pacing allows this
     * heartbeat. For each pending kind, in enumeration order
     * ({@code added_at, id} — so the same source deterministically wins a
     * crowded host's single slot), dispatch a source unless its host was
     * already requested within the {@code host-min-interval} window; such a
     * source stays pending and is retried on a later heartbeat. When a kind's
     * queue empties its cycle is complete: stamp {@link #lastTickByKind} (the
     * next per-kind interval starts) and drop the kind from
     * {@link #pendingByKind} so it can become freshly-due again.
     *
     * <p>When pacing is disabled ({@code hostMinInterval == null}) the host
     * gate is skipped entirely, so every pending source dispatches in the same
     * heartbeat it was enumerated and each kind stamps immediately — behavior
     * identical to the pre-M1-466 single-pass loop.
     */
    private void drainPending(Instant now, @Nullable Duration hostMinInterval) {
        Iterator<Map.Entry<String, Deque<SourceRow>>> kinds =
            pendingByKind.entrySet().iterator();
        while (kinds.hasNext()) {
            Map.Entry<String, Deque<SourceRow>> entry = kinds.next();
            Deque<SourceRow> queue = entry.getValue();
            Iterator<SourceRow> sources = queue.iterator();
            while (sources.hasNext()) {
                SourceRow row = sources.next();
                // Pacing disabled, or a host we cannot extract (malformed /
                // host-less identifier — a trusted-boundary fallback, all
                // polled kinds carry URL identifiers), dispatches un-paced.
                String host = hostMinInterval == null ? null : hostOf(row.identifier());
                if (host != null) {
                    Instant nextAllowed = hostNextAllowed.get(host);
                    if (nextAllowed != null && now.isBefore(nextAllowed)) {
                        continue;
                    }
                }
                tickOnce(row);
                sources.remove();
                if (host != null) {
                    hostNextAllowed.put(host, now.plus(hostMinInterval));
                }
            }
            if (queue.isEmpty()) {
                lastTickByKind.put(entry.getKey(), now);
                kinds.remove();
            }
        }
    }

    /**
     * The configured per-host minimum request interval, or {@code null} when
     * pacing is disabled — the {@code off} sentinel or an absent key, mirroring
     * the {@code infochat.linking.interval=off} convention. Read as a String
     * first to recognize {@code off}, then re-read through the SmallRye
     * {@code Duration} converter (NOT {@link Duration#parse}, which rejects the
     * Quarkus {@code 20s} shorthand).
     */
    @Nullable
    private Duration hostMinInterval() {
        Optional<String> raw =
            config.getOptionalValue("infochat.fetch.host-min-interval", String.class);
        if (raw.isEmpty() || raw.get().equalsIgnoreCase("off")) {
            return null;
        }
        return config.getOptionalValue("infochat.fetch.host-min-interval", Duration.class)
            .orElse(null);
    }

    /**
     * Host component of a source {@code identifier} URL, or {@code null} when
     * the identifier is not a parseable URL or carries no host. A null host
     * makes the source dispatch un-paced (it joins no host budget) — acceptable
     * because all polled kinds carry validated URL identifiers, so this is a
     * trusted-boundary fallback, not a defensive check.
     */
    @Nullable
    static String hostOf(String identifier) {
        try {
            return new URI(identifier).getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * Warn once per active <em>polled</em> kind that has no registered
     * Fetcher. {@link #STREAM_KINDS} are excluded: they ingest via the
     * {@code StreamSourceSupervisor}, not this polled-fetch loop, so a
     * deliberately-absent Fetcher binding for a stream kind is expected
     * rather than a misconfiguration. Excluding them keeps the orphan
     * signal meaningful for genuinely-missing polled-fetch bindings (a
     * polled kind shipped in {@code bootstrap-sources.json} with no
     * Fetcher impl).
     *
     * <p>Package-private so {@code FetchSchedulerKindFilterIT} can drive
     * the warn decision with a controlled present-kind set, without
     * seeding the DB or waiting on the scheduler clock.
     *
     * @param activeKinds the distinct {@code kind} values across all
     *                    active, non-soft-deleted sources.
     */
    void warnUnboundPolledKinds(Set<String> activeKinds) {
        for (String kind : activeKinds) {
            if (STREAM_KINDS.contains(kind)) {
                continue;
            }
            if (!fetchersByKind.containsKey(kind) && warnedOrphanKinds.add(kind)) {
                LOG.warnf("No fetcher registered for source kind '%s', skipping", kind);
            }
        }
    }

    /**
     * Distinct {@code kind} values across all active, non-soft-deleted
     * sources. The result is bounded by the number of configured source
     * kinds, not the number of sources, so it stays cheap as source count
     * grows. Used by {@link #warnUnboundPolledKinds(Set)}.
     *
     * <p>Package-private so {@code FetchSchedulerKindFilterIT} can confirm
     * a seeded active nostr source reaches the orphan check.
     */
    Set<String> distinctActiveKinds() throws SQLException {
        final String sql =
            "SELECT DISTINCT kind FROM source "
                + "WHERE status = 'active' AND deleted_at IS NULL";
        Set<String> kinds = new HashSet<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                kinds.add(rs.getString(1));
            }
        }
        return kinds;
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
    public void tickOnce(SourceRow row) {
        Fetcher fetcher = fetchersByKind.get(row.kind());
        if (fetcher == null) {
            if (warnedOrphanKinds.add(row.kind())) {
                LOG.warnf("No fetcher registered for source kind '%s', skipping",
                    row.kind());
            }
            return;
        }
        // Stage 1 — fetch(). ONLY a fetch() failure is a source-health
        // signal, so ONLY this is what the D42 per-source failure ladder
        // counts. AssetSnapshotFetcher.tickOnePair splits the fetch from
        // the store the same way.
        List<NormalizedPost> posts;
        boolean capHit;
        try {
            posts = fetcher.fetch(row.dispatchKey(), row.identifier());
            // Consume the thread-local cap-hit signal immediately after
            // fetch() returns — the flag is set on this thread inside
            // the fetcher's pagination loop and must not survive into
            // the next dispatch.
            capHit = saturationTracker.consumeCapHit();
        } catch (Exception e) {
            // Log the numeric dispatch key + UUID; NEVER the
            // identifier URL (which can carry embedded credentials
            // per M1-023's redteam INFO-LEAK finding). The chained
            // exception's message is walked and any embedded URL
            // substrings are redacted via UrlRedactor before logging
            // — see logFetchFailure / redactUrlsInText below for the
            // M1-042 redaction contract.
            logFetchFailure(row, e);
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
            // A failed tick did not saturate the cap — it breaks the
            // consecutive-saturation streak ("consistently saturates
            // ... across multiple ticks" reads consecutive).
            saturationTracker.recordTick(row.uuid(), false);
            return;
        }

        // Stage 2 — persist + enqueue. A failure here is a Collector-side
        // (DB / channel) fault, NOT a source-health signal: incrementing
        // the ladder would let one partition/DB fault flip EVERY active
        // source to terminal 'failed', each needing a manual
        // /source-enable. Log + admin-notify, but leave the D42 ladder
        // untouched. The saturation streak is broken exactly as the
        // pre-split single catch did (an incomplete tick is not a
        // saturating tick) — saturation semantics are unchanged.
        try {
            for (NormalizedPost post : posts) {
                Optional<PostPersister.PersistedPostKey> key =
                    postPersister.persist(row.uuid(), post);
                // Persist-before-enqueue per the outbox discipline.
                // On ON-CONFLICT dedup (empty), skip the enqueue —
                // the post has already been emitted on a prior tick.
                key.ifPresent(evalQueueProducer::emit);
            }
        } catch (Exception e) {
            // uuid + kind + class only — never the identifier URL
            // (M1-023 INFO-LEAK precedent) nor the exception message
            // (which can echo DB-side content).
            LOG.warnf(
                "FetchScheduler: persist/enqueue failed for source uuid=%s (dispatch=%d); "
                    + "D42 ladder left untouched (exception=%s)",
                row.uuid(), row.dispatchKey(), e.getClass().getName());
            throttledAdminNotifier.notifyOnce(
                "fetch_persist_failure:" + row.uuid(),
                "fetch_persist_failure",
                "Source uuid=" + row.uuid() + " kind=" + row.kind()
                    + " fetched successfully but a post failed to persist/enqueue;"
                    + " the D42 failure ladder is deliberately not incremented."
                    + " error class=" + e.getClass().getSimpleName());
            saturationTracker.recordTick(row.uuid(), false);
            return;
        }

        // D42 success path: zero the counter, refresh both timestamps.
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
        // Spec §Ingest SPIs saturation counter: when the source
        // has saturated its per-tick pagination cap for N
        // consecutive ticks, fire the throttled notification once
        // on the transition tick. uuid + kind only — never the
        // identifier URL (M1-023 INFO-LEAK precedent).
        if (saturationTracker.recordTick(row.uuid(), capHit)) {
            throttledAdminNotifier.notifyOnce(
                "fetch_saturation:" + row.uuid(),
                "fetch_saturation",
                "Source uuid=" + row.uuid() + " kind=" + row.kind()
                    + " saturated its per-tick pagination cap for "
                    + saturationTracker.saturationThreshold()
                    + " consecutive ticks; consider raising the per-source"
                    + " page cap or increasing fetch frequency");
        }
    }

    /**
     * M1-042 redaction-aware logger for the fetch-failure path. Walks
     * the {@link Throwable#getCause() cause chain}, builds a
     * {@code "ClassName: message"} digest per level, runs every level's
     * message through {@link #redactUrlsInText(String)} so any URL
     * substring containing {@code userinfo} (e.g. the embedded
     * credentials JDK's {@code IOException} surfaces when a connection
     * is refused mid-handshake) is stripped, then logs the digest at
     * WARN.
     *
     * <p>The throwable is NOT passed as the SLF4J/JBoss-Logger
     * {@code Throwable} parameter — doing so would cause the underlying
     * handler to invoke {@code printStackTrace} on the raw
     * {@code Throwable}, re-emitting the un-redacted message text
     * inside the stack rendering. Losing the stack trace is the
     * deliberate trade-off: in production we accept reduced
     * debuggability at the per-tick fetch-failure site for stronger
     * redaction guarantees, since URLs only appear in the per-level
     * message strings (stack frames carry class/file/line, not raw
     * input). The catch site itself is reached only on Fetcher
     * exceptions; the chain message identifies the immediate failure
     * class and the root cause's class, which is the diagnostic
     * surface that matters at this log level.
     *
     * <p>Package-private so {@code FetchSchedulerLogRedactionTest} can
     * invoke this helper directly without re-wiring the full
     * {@link #tickOnce(SourceRow)} dependency graph.
     */
    void logFetchFailure(SourceRow row, Throwable t) {
        String chain = redactUrlsInText(exceptionChainMessage(t));
        LOG.warnf(
            "FetchScheduler tick failed for source uuid=%s (dispatch=%d): %s",
            row.uuid(), row.dispatchKey(), chain);
    }

    /**
     * Walk the {@link Throwable#getCause() cause chain} and produce a
     * single-line digest in the form
     * {@code "ClassName: message" + " | caused by: " + ...}. Self-
     * referential cause cycles (an exception whose {@code getCause()}
     * eventually returns itself or an ancestor) are guarded against
     * via an {@link IdentityHashMap}-based visited set so the walk
     * always terminates.
     *
     * <p>Package-private (not private) so a future test can exercise
     * the chain walker in isolation if the format itself becomes
     * load-bearing.
     */
    static String exceptionChainMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        IdentityHashMap<Throwable, Boolean> seen = new IdentityHashMap<>();
        Throwable current = t;
        while (current != null && seen.put(current, Boolean.TRUE) == null) {
            if (sb.length() > 0) {
                sb.append(" | caused by: ");
            }
            sb.append(current.getClass().getSimpleName());
            String msg = current.getMessage();
            if (msg != null && !msg.isEmpty()) {
                sb.append(": ").append(msg);
            }
            current = current.getCause();
        }
        return sb.toString();
    }

    /**
     * Find every {@code http(s)://...} URL substring in {@code text}
     * and replace each with its {@link UrlRedactor#redact(String)}
     * rendering — which strips embedded userinfo and replaces the
     * query string with the literal placeholder. The regex matches
     * the URL up to the next whitespace, double quote, single quote,
     * comma, or closing parenthesis — punctuation that commonly
     * delimits a URL inside a free-text exception message. This is
     * deliberately conservative: ambiguous trailing punctuation
     * (e.g. a sentence-terminating period right after a URL) is
     * absorbed into the match and passed to {@link UrlRedactor#redact},
     * which renders it as {@code <malformed-url>} — strictly worse
     * for debuggability but strictly safer for redaction. URLs are
     * not common in exception messages outside of network-error
     * surfaces.
     */
    @Nullable
    static String redactUrlsInText(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher m = URL_PATTERN.matcher(text);
        if (!m.find()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        int cursor = 0;
        do {
            sb.append(text, cursor, m.start());
            sb.append(UrlRedactor.redact(m.group()));
            cursor = m.end();
        } while (m.find());
        sb.append(text, cursor, text.length());
        return sb.toString();
    }

    private static final Pattern URL_PATTERN = Pattern.compile(
        "https?://[^\\s\"',)]+");

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

    /**
     * Reads active source rows whose kind is in {@code kinds} —
     * the due-kind-filtered variant of {@link #enumerateActiveSources()}.
     * The heartbeat passes the kinds whose interval has elapsed, so the
     * SQL {@code kind = ANY(?)} filter makes the result set scale by
     * due-source count rather than total active source count.
     *
     * <p>Soft-deleted rows ({@code deleted_at IS NOT NULL}) and non-active
     * rows ({@code status != 'active'}) are skipped, identically to
     * {@link #enumerateActiveSources()}. An empty {@code kinds} set yields
     * an empty result (the SQL array matches nothing).
     */
    public List<SourceRow> enumerateActiveSourcesByKinds(Set<String> kinds) throws SQLException {
        final String sql =
            "SELECT id, identifier, kind FROM source "
                + "WHERE status = 'active' "
                + "  AND deleted_at IS NULL "
                + "  AND kind = ANY(?) "
                + "ORDER BY added_at, id";

        List<SourceRow> rows = new ArrayList<>();
        long dispatch = 1L;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Array kindArray = conn.createArrayOf("TEXT", kinds.toArray());
            ps.setArray(1, kindArray);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = (UUID) rs.getObject(1);
                    String identifier = rs.getString(2);
                    String kind = rs.getString(3);
                    rows.add(new SourceRow(id, identifier, dispatch++, kind));
                }
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
     * monotonically-assigned PER-TICK token passed to the Fetcher
     * SPI's {@code long sourceId} parameter; it is NOT the
     * {@code source.id} UUID, restarts at 1 on every
     * {@link #enumerateActiveSources()} call, and can name a
     * different source on the next tick. Opaque to the Fetcher —
     * do not key any cross-tick state on it.
     */
    public record SourceRow(UUID uuid, String identifier, long dispatchKey,
                               String kind) {
    }
}
