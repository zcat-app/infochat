package app.zcat.infochat.provider.digest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.digest.DigestPostCollector.CollectionResult;
import app.zcat.infochat.provider.digest.DigestRenderer.DigestMode;
import app.zcat.infochat.provider.digest.DigestRenderer.RenderedSection;
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

    private static final Logger LOG = LoggerFactory.getLogger(DigestWorker.class);

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
    DigestDelivery digestDelivery;

    @Inject
    SummaryCacheRepository cacheRepository;

    @Inject
    DigestSectionRepository sectionRepository;

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
    // window for /retry --digest, AND how long the persisted render output
    // (digest_section) + delivery records (digest_category_delivery) stay
    // prune-eligible (M1-652, D65). Decoupled from the user-facing cooldown
    // (infochat.digest.retry-cooldown) so widening the replay horizon does
    // not widen the cooldown: a retry now works at any point inside the
    // retention horizon instead of a ~2-minute window. Default PT24H per
    // M1-652 acceptance item 7.
    @ConfigProperty(name = "infochat.digest.replay-retention", defaultValue = "PT24H")
    Duration replayRetention;

    // The two slot centre hours, read here only to derive the first-run
    // lookback in firstRunLookback(). DigestScheduler owns the slot
    // arithmetic; this worker needs nothing from it but the gap between the
    // two centres, so the hours are re-read rather than plumbed through
    // DigestSlot.
    @ConfigProperty(name = "infochat.digest.morning-slot-hour", defaultValue = "8")
    int morningSlotHour;

    @ConfigProperty(name = "infochat.digest.evening-slot-hour", defaultValue = "20")
    int eveningSlotHour;

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
            LOG.warn("Digest already in flight for group {} slot {} — skipping overlapping execution",
                    slot.groupId(), slot.slotKind());
            return SlotOutcome.SKIPPED_IN_FLIGHT;
        }
        try {
            executeSlot(slot);
        } catch (SQLException e) {
            // Expected operational failures only — programming errors propagate.
            // Outbound delivery failures no longer surface here: the chokepoint
            // absorbs them (retry/abort) inside executeSlot. Routed through
            // SafeLog: the guarded executeSlot binds post-derived prose into
            // the cache/section tables, and §Secrets handling commits the
            // Throwable never reaches the underlying SLF4J logger.
            SafeLog.error(LOG,
                    "Digest failed for group " + slot.groupId() + " slot " + slot.slotKind(),
                    e);
        } finally {
            inFlightSlots.remove(inFlightKey);
        }
        return SlotOutcome.RAN;
    }

    private void executeSlot(DigestSlot slot) throws SQLException {
        // Collect the full inter-digest period, not just the slot window: the
        // lower bound is the previous digest boundary (latest summary_cache
        // row before this slot), so posts published between two slots appear
        // in the next digest. First-ever digest falls back one inter-slot
        // period; a missed slot's sentinel row counts as a boundary
        // (skip-not-catch-up — its period is not folded into the next digest).
        Instant collectFrom = cacheRepository
                .findPreviousBoundary(slot.groupId(), slot.windowStart())
                .orElse(slot.windowStart().minus(firstRunLookback(slot.slotKind())));
        CollectionResult collection =
                postCollector.collectForGroup(slot.groupId(), collectFrom);
        GroupMetadata meta = readGroupMetadata(slot.groupId());

        // The non-degraded, non-zero-posts path renders to a per-category
        // section list (the exact delivery bytes — M1-652 fork closed, arm
        // (b)); the cache stores the "\n\n" join of that list (the join the
        // pre-M1-732 DigestRenderer.render() performed, before its deletion).
        // The single-message paths
        // (zero-posts fixed reply, degraded headlines-only) keep a plain
        // String — they have no per-category structure and deliver via
        // deliverToGroup, not DigestDelivery. `content` is always definitely
        // assigned in one of the branches below; `renderedSections` is non-
        // null only on the multi-section render path and drives the
        // per-category delivery branch at the bottom.
        List<RenderedSection> renderedSections = null;
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
                CompletableFuture<List<RenderedSection>> renderFuture = CompletableFuture.supplyAsync(
                        () -> digestRenderer.renderSections(
                                collection.posts(), meta.language(), meta.digestMode()),
                        renderExecutor);
                try {
                    renderedSections = renderFuture.get(remaining.toMillis(), TimeUnit.MILLISECONDS);
                    // NEVER re-render after renderSections() — a second render
                    // pass re-runs the whole LLM pipeline and lets the cached
                    // prose silently diverge from the delivered bytes (the
                    // hand-wired stubs cannot catch it). The cache stores the
                    // join of the section list already in hand.
                    content = String.join("\n\n", renderedSections.stream()
                            .map(RenderedSection::text).toList());
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
                // Outlive the slot window by the replay retention so a
                // /retry --digest issued after windowEnd still finds a
                // non-expired row AND can replay persisted sections (M1-652
                // decoupled this from retry-cooldown so the horizon can be
                // PT24H without widening the user-facing cooldown).
                slot.windowEnd().plus(replayRetention));

        if (renderedSections != null) {
            // Persist the exact delivery bytes (M1-652 arm (b)) right after
            // the cache upsert and BEFORE any category message leaves the
            // process (including before the adapter lookup below). A crash
            // between this persist and the delivery call leaves the sections
            // durably readable for a later gap-filling retry; a crash during
            // the delivery loop leaves the undelivered categories' bytes
            // intact. Persist-before-deliver is the crash-window correctness
            // property the whole ticket exists for; a persist-after-deliver
            // mistake would pass every happy-path test but lose the window.
            //
            // SECURITY INVARIANT: these bytes are POST-SANITIZE. The
            // sanitizer runs inside DigestRenderer.renderSections() (the
            // prose loop in full mode; DisplayHeadline.of on the normal-mode
            // headline path, M1-732) before the sections are returned, so
            // the persisted digest_section.content is already clean. The
            // replay path
            // (DigestRetryService.replayMissing) delivers these bytes
            // verbatim WITHOUT re-sanitizing — that is correct by design,
            // not an oversight. If a future change persists pre-sanitize
            // bytes, replay would deliver unsanitized content; the test
            // DigestRendererTest.renderSections_stripsAdminCommandTokens_
            // beforePersistenceAndReplay pins this boundary (at mode full,
            // the only mode that still renders per-cluster prose).
            // A persist failure logs and continues — the digest still
            // delivers; a later retry falls back to the full re-run path
            // (no sections found → fallback), so the system degrades
            // gracefully rather than dropping the user's digest.
            try {
                sectionRepository.replaceSlotSections(
                        slot.groupId(), slot.windowStart(),
                        renderedSections, clock.instant());
            } catch (SQLException persistFailure) {
                // Catch-and-log, never propagate — the digest still delivers.
                // Routed through SafeLog: replaceSlotSections batch-INSERTs
                // digest_section.content (post-derived prose), and §Secrets
                // handling commits the Throwable is never passed to the
                // underlying SLF4J logger.
                SafeLog.warn(LOG,
                        "Section persist failed for group " + slot.groupId()
                                + " slot " + slot.slotKind() + " — delivering without replay state",
                        persistFailure);
            }
        }

        MessagingAdapter adapter = findAdapter(meta.adapterName());
        if (adapter == null) {
            LOG.warn("No activated adapter '{}' for group {} — digest cached but not delivered",
                    meta.adapterName(), slot.groupId());
            return;
        }

        if (renderedSections != null) {
            // Per-category delivery: one OutboundMessage per section,
            // sequentially in section order, through deliverSequenceToGroup
            // (one aggregate counter outcome per slot). The single-message
            // paths below stay on deliverToGroup.
            digestDelivery.deliver(adapter, meta.upstreamGroupId(), slot.groupId(),
                    slot.windowStart(), renderedSections);
        } else {
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
                LOG.warn("Digest delivery aborted for group {} slot {}",
                        slot.groupId(), slot.slotKind());
            }
        }
    }

    /**
     * How far back a group's FIRST-EVER digest collects, when no previous
     * boundary exists to bound the period: one inter-slot period, which is
     * exactly the span every subsequent digest covers.
     *
     * <p>Falling back to {@code windowStart} instead made an opening digest
     * span only {@code infochat.digest.window-width-minutes} (30 by default),
     * so a new group's first digest reported "no posts yet" against a full
     * corpus (M1-688). The span is derived from the configured centre hours
     * rather than hardcoded to 12h, so a deployment that re-points them gets
     * a matching lookback; {@code floorMod} keeps the gap correct whichever
     * order the two hours are configured in.
     */
    private Duration firstRunLookback(String slotKind) {
        int morningToEveningHours = Math.floorMod(eveningSlotHour - morningSlotHour, 24);
        return switch (slotKind) {
            // The evening slot's predecessor is the same day's morning slot.
            case "evening" -> Duration.ofHours(morningToEveningHours);
            // The morning slot's predecessor is the PREVIOUS day's evening
            // slot, so its lookback is the complement of the same gap.
            default -> Duration.ofHours(24 - morningToEveningHours);
        };
    }

    record GroupMetadata(String adapterName,
                         String upstreamGroupId,
                         String language,
                         DigestMode digestMode) {}

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
                        rs.getString("language"),
                        digestModeOrNormal(rs.getString("digest_mode"), groupId));
            }
        }
    }

    /**
     * The {@code digest_mode} SQL-deserialization boundary (M1-732). The
     * V67 CHECK constraint pins the closed set in the real schema, but a
     * stubbed or out-of-band write can still yield NULL or garbage, so the
     * parse defends anyway: anything unreadable resolves to
     * {@link DigestMode#NORMAL} — the value every pre-V67 group renders
     * with — logged once at WARN per fallback event. The render path never
     * sees an unvalidated storage string.
     */
    private static DigestMode digestModeOrNormal(@Nullable String raw, UUID groupId) {
        if (raw != null) {
            try {
                return DigestMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unrecognized) {
                // fall through to the WARN + default below
            }
        }
        LOG.warn("Group {} has NULL or unrecognized digest_mode '{}' — falling back to normal",
                groupId, raw);
        return DigestMode.NORMAL;
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
            SELECT g.adapter, g.upstream_group_id, g.digest_mode,
                   COALESCE(sp.language, 'en') AS language
              FROM groups g
              LEFT JOIN scope_preferences sp
                ON sp.scope_kind = 'group' AND sp.scope_id = g.id
             WHERE g.id = ?""";
}
