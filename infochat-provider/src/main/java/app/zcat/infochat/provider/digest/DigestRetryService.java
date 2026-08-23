package app.zcat.infochat.provider.digest;

import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.provider.digest.DigestRenderer.DigestMode;
import app.zcat.infochat.provider.digest.DigestRenderer.RenderedSection;
import app.zcat.infochat.provider.messaging.AdapterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;

/**
 * Per-group serialized retry of the most recent digest. Invoked by
 * {@link app.zcat.infochat.provider.command.RetryCommandHandler} when
 * the {@code --digest} flag is present. At most one retry is in flight
 * per group at any time (Provider-instance-local; a restart clears
 * in-progress state per spec).
 *
 * <p><b>Gap-filling replay (M1-652, D65).</b> A retry for a slot with
 * persisted sections (written by {@link DigestWorker} at render time)
 * that rendered healthy replays those bytes — no re-collection, no
 * render, no LLM call — sending
 * ONLY the categories with no delivery record, sequentially in stored
 * position order, through {@link DigestDelivery#deliver} (which routes
 * through {@code OutboundDelivery.deliverSequenceToGroup} and records each
 * accepted category). A slot flagged degraded — whatever branch produced
 * the flag (M1-912) — or with NO persisted sections (pre-V61 row,
 * zero-post slot, or a crash-stranded cache row) falls back
 * to today's full re-run path. The fallback's render budget is bounded by
 *
 * <p><b>System budget (M1-767).</b> The fallback re-run is the retry's ONLY
 * LLM-spending leg, so it binds the deployment-wide {@link SystemLlmBudget}
 * — but the refusal decision lives UPSTREAM in {@code
 * RetryCommandHandler}: {@link #retryLeg} tells the handler which leg the
 * retry would take, and the handler refuses pre-charge (nothing drawn, no
 * cooldown) when the leg is {@link RetryLeg#FALLBACK} and the window is
 * at/over its ceiling. The zero-LLM replay leg is never gated in steady
 * state — a stale probe refuses it free, see {@link #retryLeg}. The worker's
 * own admission gate remains the authoritative check on both routes; a
 * window that fills after the pre-charge check degrades the re-run (the
 * conservative non-render convention).
 */
@ApplicationScoped
public class DigestRetryService {

    private static final String SELECT_LATEST_CACHE =
            "SELECT slot_kind, slot_fired_at, expires_at, is_degraded"
                    + " FROM summary_cache"
                    + " WHERE group_id = ?"
                    + " ORDER BY slot_fired_at DESC LIMIT 1";

    private static final String SELECT_GROUP_FOR_REPLAY =
            "SELECT timezone, adapter, upstream_group_id, digest_mode"
                    + " FROM groups WHERE id = ?";

    // Provider-instance-local lock: cleared on restart per spec
    private final ConcurrentHashMap<UUID, Boolean> inFlight = new ConcurrentHashMap<>();
    // Per-group cooldown: prevents unbounded LLM cost from rapid retries
    private final ConcurrentHashMap<UUID, Instant> lastRetryAt = new ConcurrentHashMap<>();

    @Inject
    DataSource dataSource;

    @Inject
    DigestWorker digestWorker;

    @Inject
    DigestSectionRepository sectionRepository;

    @Inject
    DigestCategoryDeliveryRepository deliveryRepository;

    @Inject
    DigestDelivery digestDelivery;

    @Inject
    AdapterRegistry adapterRegistry;

    // Per-group cooldown decision time comes from the injected Clock, not
    // Instant.now(), so the retry-cooldown gate is pinnable in tests. The
    // lastRetryAt write and the cooldown read both sample this one Clock — the
    // M1-444 no-two-clock-split rule for a value the component reads back to
    // gate a decision. (M1-449)
    @Inject
    Clock clock = Clock.systemUTC();

    @ConfigProperty(name = "infochat.digest.retry-cooldown", defaultValue = "PT2M")
    Duration retryCooldown;

    // The fallback render budget is bounded by the digest window width so a
    // section-less retry never acquires a many-hours LLM timeout (the
    // replay-retention horizon). An int-minutes property matching
    // DigestScheduler's slot window; the @ConfigProperty default carries the
    // same value as application.properties.
    @ConfigProperty(name = "infochat.digest.window-width-minutes", defaultValue = "30")
    int windowWidthMinutes;

    /**
     * Retry the most recent digest for the given group. Gate order is
     * preserved from pre-M1-652: cooldown first, then per-group in-flight,
     * then cache-row existence. Only the cache-row path is widened — a
     * live, section-bearing, non-degraded row now replays instead of
     * re-running; a degraded row re-runs even when it bears sections
     * (M1-912).
     *
     * <p>Cooldown stamping: stamped on REPLAYED_MISSING and the fallback
     * SUCCESS (both sent messages); NOT stamped on ALL_ALREADY_DELIVERED
     * (nothing sent, LLM-free — a later real retry should not be blocked).
     */
    public RetryResult retryDigest(UUID groupId) {
        Instant now = clock.instant();
        Instant last = lastRetryAt.get(groupId);
        if (last != null && now.isBefore(last.plus(retryCooldown))) {
            return RetryResult.RATE_LIMITED;
        }
        if (inFlight.putIfAbsent(groupId, Boolean.TRUE) != null) {
            return RetryResult.ALREADY_IN_PROGRESS;
        }
        try {
            SlotCoordinates coords = findLatestCacheEntry(groupId);
            if (coords == null) {
                return RetryResult.NO_PRIOR_DIGEST;
            }
            GroupReplayMeta meta = lookupGroupForReplay(groupId);
            if (meta == null) {
                return RetryResult.NO_PRIOR_DIGEST;
            }
            // Replay path (M1-912): non-expired + NON-degraded + persisted
            // sections replays the bytes; a degraded row re-runs — replay
            // never repeats a completed quality failure.
            if (coords.expiresAt.isAfter(now) && !coords.degraded()) {
                List<RenderedSection> sections;
                try {
                    sections = sectionRepository.findOrderedSections(groupId, coords.slotFiredAt);
                } catch (SQLException e) {
                    throw new IllegalStateException(
                            "DigestRetryService: section lookup failed", e);
                }
                if (!sections.isEmpty()) {
                    return replayMissing(groupId, coords, meta, sections, now);
                }
            }
            return fallbackRerun(groupId, coords, meta, now);
        } finally {
            inFlight.remove(groupId);
        }
    }

    /**
     * Replay only the categories with no delivery record. Returns
     * {@link RetryResult#ALL_ALREADY_DELIVERED} — WITHOUT touching
     * {@code deliverSequenceToGroup} — when every category is already
     * recorded, so an empty filtered list never reaches the chokepoint
     * (acceptance item 5's counter-safety guard; OutboundDelivery is
     * frozen so the caller-side short-circuit is the only in-scope option).
     * Stamps {@code lastRetryAt} only when messages were actually sent.
     */
    private RetryResult replayMissing(UUID groupId, SlotCoordinates coords,
                                      GroupReplayMeta meta,
                                      List<RenderedSection> sections,
                                      Instant now) {
        java.util.Set<String> delivered;
        try {
            delivered = deliveryRepository.findDeliveredSlugs(groupId, coords.slotFiredAt);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "DigestRetryService: delivery lookup failed", e);
        }
        List<RenderedSection> missing = new java.util.ArrayList<>();
        for (RenderedSection s : sections) {
            if (!delivered.contains(DigestSectionRepository.slugOf(s))) {
                missing.add(s);
            }
        }
        if (missing.isEmpty()) {
            // No-op retry: every category already delivered. Stamp NOTHING
            // (nothing was sent, no LLM cost) and report explicitly rather
            // than a bare SUCCESS — the caller surfaces a distinct reply.
            return RetryResult.ALL_ALREADY_DELIVERED;
        }
        MessagingAdapter adapter = findAdapter(meta.adapterName());
        if (adapter == null) {
            // No activated adapter for the group: mirror today's log-and-
            // succeed shape (DigestWorker:229-233) — the bytes are
            // persisted, a later delivery attempt picks them up. The
            // acceptance pins exactly two new constants, so inventing a
            // failure constant here is off the table.
            return RetryResult.REPLAYED_MISSING;
        }
        // SECURITY INVARIANT: the replayed bytes are POST-SANITIZE. The
        // sanitizer runs inside DigestRenderer.renderSections() before
        // DigestWorker persists the sections; replay delivers those stored
        // bytes verbatim. There is deliberately NO sanitizer call on this
        // path — re-sanitizing already-clean bytes is wasteful and would
        // imply the stored bytes might be dirty (they are not). Pinned by
        // DigestRendererTest.renderSections_stripsAdminCommandTokens_
        // beforePersistenceAndReplay and documented at the persist site
        // in DigestWorker.executeSlot.
        // The mode passed here is the group's CURRENT digest_mode (M1-734).
        // Absent a mid-window mode flip it equals the render-time mode, and
        // all-slugs-or-none keeps a batched slot's missing set either empty
        // (handled above) or the full section list, so the re-send
        // reproduces the original batched message byte-for-byte. After a
        // flip the framing follows the group's current preference while the
        // bytes stay byte-faithful (D65) — accepted residual, bounded by
        // the replay-retention horizon.
        digestDelivery.deliver(adapter, meta.upstreamGroupId(), groupId,
                coords.slotFiredAt, missing, meta.digestMode());
        lastRetryAt.put(groupId, now);
        return RetryResult.REPLAYED_MISSING;
    }

    /**
     * Fallback full re-run for a slot with no persisted sections: rebuild
     * the synthetic slot and invoke the worker. The slot's windowEnd is
     * clamped to {@code min(coords.expiresAt, now + windowWidth)} so the
     * worker's render budget is bounded by the window width (never the
     * 24-hour retention horizon), while an expired row still degrades
     * exactly as today (preserving the current behavior nothing in this
     * ticket authorizes changing).
     */
    private RetryResult fallbackRerun(UUID groupId, SlotCoordinates coords,
                                      GroupReplayMeta meta, Instant now) {
        // M1-767 redteam rounds 2-4: the fallback re-run is the retry's ONLY
        // LLM-spending leg, so it binds the deployment-wide pool. There is
        // deliberately NO gate here — the admission decision belongs to ONE
        // place: RetryCommandHandler's pre-charge refusal (leg probe +
        // SystemLlmBudget, so a refused re-run draws nothing) and
        // DigestWorker.executeSlot's admission gate (the authoritative
        // check; a window that fills in between degrades the re-run rather
        // than refusing after the charges — the conservative non-render
        // convention). A gate at this boundary would refuse AFTER the
        // per-user and D47 draws were spent, draining the group's shared
        // budget for a system-level denial (M1-767 redteam rounds 3+4).
        Duration windowWidth = Duration.ofMinutes(windowWidthMinutes);
        Instant clampedWindowEnd = min(coords.expiresAt, now.plus(windowWidth));
        DigestSlot slot = new DigestSlot(
                groupId, meta.timezone, coords.slotKind,
                coords.slotFiredAt, clampedWindowEnd);
        if (digestWorker.execute(slot) == DigestWorker.SlotOutcome.SKIPPED_IN_FLIGHT) {
            // A concurrent scheduled run owns the slot — the cache is
            // intact (we never deleted it) and untouched by this retry.
            return RetryResult.ALREADY_IN_PROGRESS;
        }
        lastRetryAt.put(groupId, now);
        return RetryResult.SUCCESS;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private @Nullable MessagingAdapter findAdapter(String adapterName) {
        for (MessagingAdapter adapter : adapterRegistry.activatedAdapters()) {
            if (adapter.name().equals(adapterName)) {
                return adapter;
            }
        }
        return null;
    }

    private @Nullable SlotCoordinates findLatestCacheEntry(UUID groupId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_LATEST_CACHE)) {
            ps.setObject(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new SlotCoordinates(
                        rs.getString("slot_kind"),
                        rs.getTimestamp("slot_fired_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getBoolean("is_degraded"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "DigestRetryService.findLatestCacheEntry failed", e);
        }
    }

    private @Nullable GroupReplayMeta lookupGroupForReplay(UUID groupId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_GROUP_FOR_REPLAY)) {
            ps.setObject(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new GroupReplayMeta(
                        rs.getString("timezone"),
                        rs.getString("adapter"),
                        rs.getString("upstream_group_id"),
                        // Same SQL-deserialization boundary rule as the
                        // worker's render path — one shared parse (M1-734).
                        DigestWorker.digestModeOrNormal(rs.getString("digest_mode"), groupId));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "DigestRetryService.lookupGroupForReplay failed", e);
        }
    }

    /**
     * Which leg {@link #retryDigest} would take for this group right now,
     * computed with the same reads {@code retryDigest} performs (latest
     * cache row + is_degraded + persisted-section presence). M1-767 redteam
     * rounds 3-5:
     * this is the pre-charge gate's input — {@code RetryCommandHandler}
     * refuses with the system-budget reply when the window is at/over its
     * ceiling AND the leg is {@link RetryLeg#FALLBACK}, so a refused
     * re-run draws no per-user token, no D47 draw and no cooldown. The
     * probe is an ESTIMATE, read outside any lock: between the probe's
     * reads and the refusal a concurrent scheduled render can persist
     * sections, flipping the would-be leg FALLBACK → REPLAY, so a refusal
     * can land on a retry that would by then have made no LLM call. What
     * bounds that residual is not the width of the interval but the
     * refusal's cost — it drew and stamped NOTHING, so the re-issued
     * retry probes REPLAY and proceeds; the REPLAY leg is never gated in
     * steady state (round-5 finding, documented here and in the handler,
     * which also explains why the probe runs BEFORE the budget read).
     * The authoritative leg
     * decision for a PROCEEDING retry remains {@code retryDigest}'s own
     * reads; a probe-vs-run flip to {@code FALLBACK} is caught by the
     * worker's admission gate (degrade, conservative convention).
     */
    public RetryLeg retryLeg(UUID groupId) {
        SlotCoordinates coords = findLatestCacheEntry(groupId);
        if (coords == null || lookupGroupForReplay(groupId) == null) {
            return RetryLeg.NO_PRIOR;
        }
        // Same leg rule as retryDigest: a degraded row re-runs even with
        // persisted sections (M1-912) — the pre-charge probe must agree
        // with the run or a refused re-run could shadow a replay.
        if (coords.expiresAt.isAfter(clock.instant()) && !coords.degraded()) {
            try {
                if (!sectionRepository.findOrderedSections(groupId, coords.slotFiredAt).isEmpty()) {
                    return RetryLeg.REPLAY;
                }
            } catch (SQLException e) {
                throw new IllegalStateException(
                        "DigestRetryService.retryLeg: section lookup failed", e);
            }
        }
        return RetryLeg.FALLBACK;
    }

    public enum RetryResult {
        SUCCESS,
        REPLAYED_MISSING,
        ALL_ALREADY_DELIVERED,
        ALREADY_IN_PROGRESS,
        NO_PRIOR_DIGEST,
        RATE_LIMITED
    }

    /** The leg {@link #retryDigest} would take for a group (see {@link #retryLeg}). */
    public enum RetryLeg {
        /** A live cache row with persisted sections — zero LLM calls. */
        REPLAY,
        /** The section-less/expired/degraded fallback re-run — the retry's only LLM-spending leg; a degraded row lands here even with sections (M1-912). */
        FALLBACK,
        /** No prior digest to retry. */
        NO_PRIOR
    }

    record SlotCoordinates(
            String slotKind,
            Instant slotFiredAt,
            Instant expiresAt,
            boolean degraded) {
    }

    record GroupReplayMeta(
            String timezone,
            String adapterName,
            String upstreamGroupId,
            DigestMode digestMode) {
    }
}
