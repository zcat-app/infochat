package app.zcat.infochat.collector.stream.nostr;

import app.zcat.infochat.collector.eval.PartitionScan;
import app.zcat.infochat.collector.eval.reeval.PerSourceUnknownTracker;
import app.zcat.infochat.collector.outbox.EvalQueueProducer;
import app.zcat.infochat.collector.outbox.PostPersister;
import app.zcat.infochat.collector.stream.StreamDispatchKey;
import app.zcat.infochat.collector.stream.StreamSourceSupervisor;
import app.zcat.infochat.core.ingest.NormalizedPost;
import app.zcat.infochat.core.ingest.StreamSource;
import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.ssrf.SsrfGuardedHttpClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The Nostr ingest worker: one {@link StreamSource} per {@code kind='nostr'}
 * source. {@link #start} fans out to one {@link NostrRelayConnection} per
 * configured relay and runs a single delivery loop that maps each received
 * {@link NostrEvent} to a {@link NormalizedPost} and hands it to the outbox
 * callback. {@link #stop} tears the relays down, drains the buffered events,
 * and joins the delivery loop so no callback fires after it returns.
 *
 * <p>Construction is per-source and carries the relay list, the {@code since}
 * cursor supplier, and the backoff bounds — none of which the {@link
 * StreamSource} SPI's {@code start} parameters convey. The nested {@link
 * Registrar} is the startup bean that reads those from each source row and
 * registers a worker per source with the {@link StreamSourceSupervisor}.</p>
 */
public final class NostrStreamSource implements StreamSource {

    private static final Logger LOG = LoggerFactory.getLogger(NostrStreamSource.class);

    /**
     * Build the shared relay-dial {@link HttpClient}. NO_PROXY disables
     * proxying: a WebSocket dial inherits its proxy selector from this
     * client, and an ambient JVM proxy (http/https/socksProxyHost) would
     * re-resolve the relay host itself, voiding the validated peer IP and
     * the DNS pin {@code SsrfGuardedHttpClient.checkAndPinForWebSocket}
     * installs. The builder is the only lever — there is no per-dial
     * proxy override. Package-private static so the posture is assertable
     * without standing up the CDI {@code Registrar} bean.
     */
    static HttpClient newRelayDialClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .proxy(HttpClient.Builder.NO_PROXY)
                .build();
    }

    // Delivery-loop wake cadence: how often it re-checks the stop flag while
    // the inbound queue is idle. Short enough that stop()/drain is prompt.
    private static final Duration DELIVERY_POLL = Duration.ofMillis(100);

    // Cap on parsed-but-not-yet-delivered events held in memory per source.
    // A hostile relay can flood EVENT frames faster than PostPersister drains
    // (~10ms/write); without a cap the queue grows unboundedly until OOM.
    // Drop-on-overflow rather than back-pressure: the WebSocket listener
    // thread is a shared executor slot and must not block. Dropped events
    // replay on reconnect via the since cursor.
    static final int INBOUND_CAPACITY = 10_000;

    // Kind allowlist per security.md §Nostr (StreamSource, v1): only text
    // notes (1) and reposts (6) reach the ingest pipeline; every other kind
    // is dropped after signature verification passes. Compile-time constant
    // in v1; a future ticket can extend this via config if needed.
    static final Set<Integer> ALLOWED_KINDS = Set.of(1, 6);

    private final List<URI> relayUris;
    private final Supplier<OptionalLong> sinceCursor;
    private final Duration backoffBase;
    private final Duration backoffMax;
    private final HttpClient httpClient;
    private final SsrfGuardedHttpClient ssrfClient;
    private final NostrEventVerifier verifier;
    private final RelayHealthTracker healthTracker;
    private final NostrDedupFilter dedupFilter;

    private final List<NostrRelayConnection> connections = new ArrayList<>();
    private final int inboundCapacity;
    private final BlockingQueue<NostrEvent> inbound;
    private final AtomicLong droppedEvents = new AtomicLong();
    // Per-source counter of events that failed BIP-340 verification. A hostile
    // relay can produce many; no admin notification per failure — the counter
    // is the audit surface, exposed via the same first+every-100th log pattern
    // used for droppedEvents.
    private final AtomicLong failedSig = new AtomicLong();
    // Cumulative count of successfully enqueued inbound events. Observation-
    // only test seam (M1-555): monotonic, so a test can await "every sent
    // event is buffered in the source" before stop() — awaiting queue depth
    // instead would race the concurrently-polling delivery loop. Never read
    // by production code.
    private final AtomicLong arrived = new AtomicLong();

    private volatile boolean delivering;
    // deliveryThread and deliver are set in start(); null only in the
    // pre-start window (stop() null-guards deliveryThread), non-null at
    // every delivery-loop deref — hence NullAway.Init, not @Nullable.
    @SuppressWarnings("NullAway.Init")
    private volatile Thread deliveryThread;
    private long dispatchKey;
    @SuppressWarnings("NullAway.Init")
    private Consumer<NormalizedPost> deliver;

    NostrStreamSource(List<URI> relayUris, Supplier<OptionalLong> sinceCursor,
                      Duration backoffBase, Duration backoffMax,
                      HttpClient httpClient, SsrfGuardedHttpClient ssrfClient,
                      NostrEventVerifier verifier,
                      RelayHealthTracker healthTracker, NostrDedupFilter dedupFilter) {
        this(relayUris, sinceCursor, backoffBase, backoffMax, httpClient, ssrfClient,
                verifier, healthTracker, dedupFilter, INBOUND_CAPACITY);
    }

    // Capacity-override constructor: the record-after-offer test drives a
    // 1-slot inbound queue so a single filler event makes offer() return false
    // deterministically, instead of flooding INBOUND_CAPACITY (10K) events
    // through a relay. Production always uses the INBOUND_CAPACITY default.
    NostrStreamSource(List<URI> relayUris, Supplier<OptionalLong> sinceCursor,
                      Duration backoffBase, Duration backoffMax,
                      HttpClient httpClient, SsrfGuardedHttpClient ssrfClient,
                      NostrEventVerifier verifier,
                      RelayHealthTracker healthTracker, NostrDedupFilter dedupFilter,
                      int inboundCapacity) {
        this.relayUris = List.copyOf(relayUris);
        this.sinceCursor = sinceCursor;
        this.backoffBase = backoffBase;
        this.backoffMax = backoffMax;
        this.httpClient = httpClient;
        this.ssrfClient = ssrfClient;
        this.verifier = verifier;
        this.healthTracker = healthTracker;
        this.dedupFilter = dedupFilter;
        this.inboundCapacity = inboundCapacity;
        this.inbound = new LinkedBlockingQueue<>(inboundCapacity);
    }

    @Override
    public void start(long dispatchKey, String filterSpec, Consumer<NormalizedPost> deliver) {
        this.dispatchKey = dispatchKey;
        this.deliver = deliver;
        this.delivering = true;
        this.deliveryThread = Thread.ofVirtual()
                .name("nostr-deliver-" + dispatchKey)
                .start(this::deliveryLoop);
        for (URI relayUri : relayUris) {
            NostrRelayConnection connection = new NostrRelayConnection(
                    relayUri, filterSpec, sinceCursor, this::enqueueInbound,
                    backoffBase, backoffMax, httpClient, ssrfClient,
                    NostrRelayConnection.DEFAULT_PEER_IP_CHECK_INTERVAL, healthTracker);
            connections.add(connection);
            connection.start();
        }
    }

    // Package-private (not private) so NostrDedupRecordAfterOfferTest can drive
    // the dedup-record-after-offer ordering synchronously through a 1-slot
    // queue, without a live relay flooding INBOUND_CAPACITY events.
    boolean enqueueInbound(NostrEvent event) {
        // Trust-boundary gate per security.md §Per-source trust boundaries:
        // signature verification → kind allowlist → outbox write. The order
        // is load-bearing — kind alone gates nothing if the sig is forged, and
        // a hostile relay can ignore the REQ subscription filter regardless.
        if (!verifier.verify(event)) {
            long total = failedSig.incrementAndGet();
            if (total == 1 || total % 100 == 0) {
                LOG.warn("Nostr signature verification failed; dropped {} event(s) cumulative for source {}",
                        total, dispatchKey);
            }
            return false;
        }
        if (!ALLOWED_KINDS.contains(event.kind())) {
            // Silent drop: a well-behaved relay's REQ filter should have
            // suppressed disallowed kinds, but the relay is untrusted. No
            // counter — kind drops are noise, not a signal that needs auditing.
            return false;
        }
        // verify() above rejected null fields; requireNonNull re-states
        // that invariant for the type system.
        String eventId = Objects.requireNonNull(event.id());
        if (dedupFilter.seen(eventId)) {
            // Same event id already delivered (most likely from another
            // relay of this same source). Silent drop: the in-memory filter
            // is the authoritative cross-relay dedup per architecture.md
            // §Ingest SPIs, and one-event-many-relays is the design.
            return false;
        }
        boolean accepted = inbound.offer(event);
        if (!accepted) {
            long total = droppedEvents.incrementAndGet();
            // Log first drop and every 100th thereafter so a flood doesn't
            // drown operator logs. No SafeLog: msg is operator-authored.
            if (total == 1 || total % 100 == 0) {
                LOG.warn("Nostr inbound queue full (cap={}); dropped {} event(s) cumulative for source {}",
                        inboundCapacity, total, dispatchKey);
            }
            // Do NOT record the id on a dropped event: the outbox write is
            // at-least-once (architecture.md §"an event is written to the
            // outbox before the implementation considers it processed"), so a
            // queue-full drop must stay replayable on the relay's reconnect
            // (since cursor). Recording here would poison the dedup set and
            // turn a transient burst into a permanent coverage gap.
            return false;
        }
        // Record only after a successful enqueue. Window: two relays of this
        // source can both pass seen() and both enqueue the same id before
        // either records — a double-enqueue the DB collapses to one posts row
        // (PostPersister's WHERE NOT EXISTS uid pre-filter + ON CONFLICT
        // (source_id, upstream_identifier, fetched_at) DO NOTHING).
        dedupFilter.record(eventId);
        arrived.incrementAndGet();
        return true;
    }

    // Package-private drain seam for NostrDedupRecordAfterOfferTest: empties
    // the inbound queue so a replayed event finds room. Production draining is
    // the delivery loop's inbound.poll only.
    void drainInbound() {
        inbound.clear();
    }

    /** Package-private accessor for the failed-sig counter used by the IT. */
    long failedSigCount() {
        return failedSig.get();
    }

    /** Package-private accessor for the arrival counter used by the drain test (M1-555). */
    long arrivedCount() {
        return arrived.get();
    }

    @Override
    public void stop() {
        // Stop the relays first so no further events are enqueued, then let
        // the delivery loop drain what is already buffered and exit.
        for (NostrRelayConnection connection : connections) {
            connection.stop();
        }
        delivering = false;
        Thread thread = deliveryThread;
        if (thread != null) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void deliveryLoop() {
        try {
            while (delivering || !inbound.isEmpty()) {
                NostrEvent event = inbound.poll(DELIVERY_POLL.toMillis(), TimeUnit.MILLISECONDS);
                if (event != null) {
                    deliverOne(event);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void deliverOne(NostrEvent event) {
        try {
            deliver.accept(event.toNormalizedPost(dispatchKey, Instant.now()));
        } catch (RuntimeException e) {
            // A failed outbox write (e.g. JDBC down) must not kill the stream;
            // the post reappears on reconnect via the since cursor. Mirrors
            // FetchScheduler.tickOnce's per-source failure isolation. SafeLog
            // because the underlying SQLException can echo bound parameters
            // (relay-supplied content) per security.md §Secrets handling.
            SafeLog.warn(LOG, "Nostr outbox delivery failed for event " + event.id(), e);
        }
    }

    /**
     * Startup wiring: enumerate every active {@code kind='nostr'} source,
     * build a per-source {@link NostrStreamSource} from its {@code config.relays}
     * and filter {@code identifier}, and register it with the supervisor.
     *
     * <p>Runs at {@code @Priority(460)} — after the {@link StreamSourceSupervisor}
     * ({@code 450}) is initialized and after BootstrapLoader ({@code 200}) has
     * seeded the {@code source} rows. Registration is async (the supervisor
     * submits each {@code start()} to a virtual thread), so a relay unreachable
     * at boot never blocks Collector startup.</p>
     */
    @Startup
    @Priority(460)
    @ApplicationScoped
    static class Registrar {

        @Inject
        StreamSourceSupervisor supervisor;

        @Inject
        DataSource dataSource;

        @Inject
        PostPersister postPersister;

        @Inject
        EvalQueueProducer evalQueueProducer;

        @Inject
        Kind6Handler kind6Handler;

        @Inject
        RepostEdgeResolver repostEdgeResolver;

        @Inject
        ThrottledAdminNotifier adminNotifier;

        @Inject
        Clock clock;

        @ConfigProperty(name = "infochat.stream.nostr.reconnect-base-delay")
        Duration backoffBase;

        @ConfigProperty(name = "infochat.stream.nostr.reconnect-max-delay")
        Duration backoffMax;

        @ConfigProperty(name = "infochat.stream.nostr.relay-failure-threshold")
        int relayFailureThreshold;

        @ConfigProperty(name = "infochat.stream.nostr.cooldown-duration")
        Duration cooldownDuration;

        @ConfigProperty(name = "infochat.stream.nostr.all-relays-bad-cycle-cap")
        int allRelaysBadCycleCap;

        // The post partition retention horizon — the same property the
        // PartitionPruner ages partitions out with — reused as the since-
        // cursor scan window so the fetched_at floor never excludes a live
        // post. Widened by the shared PartitionScan.PARTITION_SCAN_SLACK,
        // the single source every partition-pruning scan references.
        @ConfigProperty(name = "infochat.partitions.retention-days.post")
        int postRetentionDays;

        // One shared client (and its executor) across every relay of every
        // nostr source; relay connections only subscribe and read. The
        // connect timeout bounds the handshake so a relay host that accepts
        // the TCP SYN but never completes the connection cannot pin this
        // shared client open indefinitely (mirrors the 10s handshake bound
        // in NostrRelayConnection.CONNECT_TIMEOUT).
        private final HttpClient httpClient = newRelayDialClient();

        // CDI-managed default-strict SSRF guard, supplied by
        // CollectorSsrfClientProducer (one shared @Singleton instance) rather
        // than constructed here — so the Registrar routes through the same
        // configured guard a test can override, not a privately-newed one.
        // The guard refuses loopback, private, link-local, CGNAT,
        // cloud-metadata, multicast, and the host's own non-loopback
        // interfaces (security.md §SSRF); it is stateless apart from its
        // configuration.
        @Inject
        SsrfGuardedHttpClient ssrfClient;

        // Stateless and thread-safe — one verifier shared across every source.
        private final NostrEventVerifier verifier = new NostrEventVerifier();

        // sourceId → dispatchKey for every registered nostr worker. The
        // Registrar is the only bean that can translate the source id the
        // re-eval tracker knows into the dispatch key the supervisor keys
        // workers by, so it owns this map and the SourceDisabled observer.
        // Package-private (not private) so the wiring IT can populate it on a
        // directly-constructed Registrar without a CDI proxy.
        final Map<UUID, StreamDispatchKey> dispatchKeyBySource = new ConcurrentHashMap<>();

        @PostConstruct
        void registerNostrSources() {
            List<NostrSourceRow> rows;
            try {
                rows = enumerateNostrSources();
            } catch (SQLException e) {
                SafeLog.error(LOG, "Failed to enumerate nostr sources; none registered", e);
                return;
            }
            long nextDispatchKey = 1L;
            for (NostrSourceRow row : rows) {
                List<URI> relays = parseRelays(row);
                if (relays.isEmpty()) {
                    LOG.warn("Nostr source {} has no relays in config; skipping registration", row.id());
                    continue;
                }
                final UUID sourceUuid = row.id();
                // Mint the typed handle from the long counter; the supervisor
                // surface is handle-keyed from here on (M1-371).
                final StreamDispatchKey dispatchKey = new StreamDispatchKey(nextDispatchKey++);
                RelayHealthTracker tracker = new RelayHealthTracker(
                        relays, relayFailureThreshold, cooldownDuration, allRelaysBadCycleCap,
                        clock, transition -> handleTransition(transition, sourceUuid, dispatchKey));
                Supplier<OptionalLong> since = () -> latestPublishedAtEpochSeconds(sourceUuid);
                // One filter per source: dedup state is per-publisher
                // (see NostrDedupFilter javadoc). Verifier is shared
                // because it's stateless.
                NostrDedupFilter dedupFilter = new NostrDedupFilter();
                NostrStreamSource worker = new NostrStreamSource(
                        relays, since, backoffBase, backoffMax,
                        httpClient, ssrfClient, verifier, tracker, dedupFilter);
                supervisor.register(dispatchKey, row.identifier(), worker, deliverFor(sourceUuid));
                dispatchKeyBySource.put(sourceUuid, dispatchKey);
                LOG.info("Registered NostrStreamSource for source {} across {} relay(s)",
                        sourceUuid, relays.size());
            }
        }

        /**
         * The per-source outbox-delivery callback the supervisor invokes for
         * every {@link NormalizedPost} this source yields.
         *
         * <p>Dispatch by NormalizedPost.rawMetadata's NostrEvent.META_KIND key
         * per M1-100. NostrEvent.toNormalizedPost populates the key for kind-6
         * events; kind-1 events emit an empty rawMetadata and follow the existing
         * persist→eval-queue path. Reading the kind here (rather than threading
         * Kind6Handler through NostrStreamSource's constructor) leaves the SPI
         * surface untouched. Both branches resolve repost edges naming the new
         * post as their target after a successful persist — a kind-6 can itself
         * be a repost target — covering the repost-arrived-first order (the
         * handler's own original lookup covers original-arrived-first).
         *
         * <p>Package-private so Kind6LinkingIT / Kind6RepostResolutionIT drive
         * the real production callback rather than a re-implemented copy (M1-498).
         */
        Consumer<NormalizedPost> deliverFor(UUID sourceUuid) {
            return post -> {
                if ("6".equals(post.rawMetadata().get(NostrEvent.META_KIND))) {
                    kind6Handler.handle(post, sourceUuid).ifPresent(key ->
                            repostEdgeResolver.resolveEdgesPointingTo(key.id(), post.upstreamIdentifier()));
                } else {
                    postPersister.persist(sourceUuid, post).ifPresent(key -> {
                        evalQueueProducer.emit(key);
                        repostEdgeResolver.resolveEdgesPointingTo(key.id(), post.upstreamIdentifier());
                    });
                }
            };
        }

        /**
         * Stop the stream worker for an auto-disabled source (U-03). Observes
         * the {@link PerSourceUnknownTracker.SourceDisabled} signal the re-eval
         * tracker fires when a source crosses the UNKNOWN-rate threshold: the
         * worker must stop enqueueing new posts the moment the source is
         * disabled, not only at the next restart. Synchronous observer (default
         * {@code @Observes}) so the tracker's "source disabled" notification is
         * true the moment it fires.
         *
         * <p>The signal arrives for EVERY auto-disabled source — most are
         * polled (non-stream) sources with no entry in the map, so a map miss is
         * a logged no-op. {@code remove} (not {@code get}) makes a repeated
         * signal for an already-stopped source a no-op too. The lookup is the
         * system boundary between the tracker's source-id keyspace and the
         * supervisor's dispatch-key keyspace; this guard is boundary handling,
         * not internal defensive code between two trusted internal classes.
         */
        void onSourceDisabled(@Observes PerSourceUnknownTracker.SourceDisabled event) {
            StreamDispatchKey dispatchKey = dispatchKeyBySource.remove(event.sourceId());
            if (dispatchKey == null) {
                LOG.debug("SourceDisabled for {} has no registered stream worker; no-op",
                        event.sourceId());
                return;
            }
            supervisor.stop(dispatchKey);
        }

        /**
         * Package-private accessor for the injected SSRF client, used by the
         * wiring IT to assert the Registrar resolves to the CDI-produced bean.
         * A method (not a direct field read) so the call dispatches through the
         * {@code @ApplicationScoped} client proxy to the contextual instance.
         */
        SsrfGuardedHttpClient ssrfClient() {
            return ssrfClient;
        }

        /**
         * Source-level transition side effects. ALL_RELAYS_BAD / RECOVERED fire
         * a throttled admin notification on the calling thread (the tracker
         * invokes this callback outside its synchronized region, so JDBC here
         * does not block parallel relay-worker calls). TERMINAL additionally
         * marks the source row failed and stops the supervisor's worker; the
         * supervisor's stop joins the relay loopThread, so the call MUST run on
         * a different thread than the loopThread that fired the transition —
         * we spawn a fresh virtual thread for the terminal teardown.
         */
        void handleTransition(RelayHealthTracker.Transition transition,
                              UUID sourceUuid, StreamDispatchKey dispatchKey) {
            switch (transition) {
                case ALL_RELAYS_BAD -> adminNotifier.notifyOnce(
                        "nostr-all-relays-bad:" + sourceUuid,
                        "nostr_all_relays_bad",
                        "All relays for nostr source " + sourceUuid + " are in cooldown");
                case RECOVERED -> adminNotifier.notifyOnce(
                        "nostr-recovered:" + sourceUuid,
                        "nostr_recovery",
                        "Nostr source " + sourceUuid + " recovered from all-relays-bad");
                case TERMINAL -> Thread.ofVirtual()
                        .name("nostr-terminal-" + sourceUuid)
                        .start(() -> handleTerminal(sourceUuid, dispatchKey));
                case NONE -> { /* unreachable: tracker only invokes the callback for non-NONE transitions */ }
            }
        }

        private void handleTerminal(UUID sourceUuid, StreamDispatchKey dispatchKey) {
            try {
                markSourceFailed(sourceUuid);
            } catch (SQLException e) {
                SafeLog.warn(LOG, "Failed to mark nostr source " + sourceUuid + " as failed in DB", e);
            }
            adminNotifier.notifyOnce(
                    "nostr-source-failed:" + sourceUuid,
                    "nostr_terminal_failure",
                    "StreamSource for source " + sourceUuid
                            + " permanently stopped: all-relays-bad cycle cap exhausted");
            supervisor.stop(dispatchKey);
        }

        // Inline SQL via the already-injected DataSource — SourceRepository is
        // out of scope for M1-099, and a single-row status flip does not need
        // a repository method. The `status='active'` guard means a concurrent
        // failure path (e.g. D42 fetcher ladder for a misclassified row) does
        // not clobber a status that has already moved on.
        private void markSourceFailed(UUID sourceUuid) throws SQLException {
            final String sql = "UPDATE source SET status = 'failed' WHERE id = ? AND status = 'active'";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, sourceUuid);
                ps.executeUpdate();
            }
        }

        private List<NostrSourceRow> enumerateNostrSources() throws SQLException {
            final String sql =
                    "SELECT id, identifier, config FROM source "
                            + "WHERE kind = 'nostr' "
                            + "  AND status = 'active' "
                            + "  AND deleted_at IS NULL "
                            + "ORDER BY added_at, id";
            List<NostrSourceRow> rows = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new NostrSourceRow(
                            (UUID) rs.getObject(1), rs.getString(2), rs.getString(3)));
                }
            }
            return rows;
        }

        private List<URI> parseRelays(NostrSourceRow row) {
            List<URI> relays = new ArrayList<>();
            try {
                JsonNode config = NostrMessage.MAPPER.readTree(row.config());
                JsonNode relayArray = config.get("relays");
                if (relayArray == null || !relayArray.isArray()) {
                    return relays;
                }
                for (JsonNode relay : relayArray) {
                    relays.add(URI.create(relay.asText()));
                }
            } catch (RuntimeException | JsonProcessingException e) {
                // config is JSONB deserialized from the DB — a system boundary.
                // A malformed relay list disables this one source rather than
                // failing Collector startup.
                SafeLog.warn(LOG, "Nostr source " + row.id() + " has an unparseable config.relays; skipping", e);
                return List.of();
            }
            return relays;
        }

        /**
         * Since-cursor for a relay reconnect. The fast path bounds
         * fetched_at at {@code now() - (retention horizon + slack)} so
         * the RANGE(fetched_at) partitioned post table prunes partitions
         * instead of scanning every live one on every reconnect.
         * Cursor-correctness argument: published_at is clamped to
         * fetched_at at persist time, so a row outside the window can
         * only carry a published_at older than the floor — missing it
         * can only lower the cursor, and a lower {@code since} merely
         * replays events the dedup filter drops. A stale source (no row
         * inside the window) falls back to the unbounded scan, keeping
         * the cursor semantics identical to the pre-bound form.
         * Package-private for the NostrSinceCursorIT seam.
         */
        OptionalLong latestPublishedAtEpochSeconds(UUID sourceUuid) {
            OptionalLong bounded = maxPublishedAtEpochSeconds(sourceUuid, true);
            if (bounded.isPresent()) {
                return bounded;
            }
            return maxPublishedAtEpochSeconds(sourceUuid, false);
        }

        private OptionalLong maxPublishedAtEpochSeconds(UUID sourceUuid, boolean boundedToScanWindow) {
            final String sql = boundedToScanWindow
                ? "SELECT MAX(published_at) FROM post WHERE source_id = ? AND fetched_at >= ?"
                : "SELECT MAX(published_at) FROM post WHERE source_id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, sourceUuid);
                if (boundedToScanWindow) {
                    // App-clock floor bound from the injected Clock instead of an
                    // in-SQL time read: this scanner already injects the Clock for
                    // every other time read, so binding the partition-pruning floor
                    // from clock.instant() keeps the whole component on one clock
                    // (no app-vs-DB split, §9 / M1-452). Same whole-day (retention +
                    // slack) arithmetic the INTERVAL expressed, so the floor is
                    // byte-for-byte preserved under Clock.systemUTC().
                    ps.setTimestamp(2, Timestamp.from(clock.instant().minus(
                        Duration.ofDays(postRetentionDays + PartitionScan.PARTITION_SCAN_SLACK.toDays()))));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Timestamp maxPublished = rs.getTimestamp(1);
                        if (maxPublished != null) {
                            return OptionalLong.of(maxPublished.toInstant().getEpochSecond());
                        }
                    }
                }
            } catch (SQLException e) {
                // No cursor on failure: the reconnect omits `since` and the
                // relay replays from its own default window.
                SafeLog.warn(LOG, "Failed to read since cursor for nostr source " + sourceUuid, e);
            }
            return OptionalLong.empty();
        }
    }

    /** One enumerated {@code kind='nostr'} source row: id, filter spec, raw config JSON. */
    record NostrSourceRow(UUID id, String identifier, String config) {
    }
}
