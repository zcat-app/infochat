package app.zcat.infochat.provider.digest;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;

/**
 * System-wide sliding-window budget for LLM calls made by the scheduled
 * digest route — the aggregate system LLM budget {@code
 * docs/spec/security.md} §Rate limiting names as the backstop for digest
 * cost (M1-767). The scheduled route ({@code DigestScheduler} → {@code
 * DigestWorker.executeSlot} → {@code DigestRenderer}) is the one
 * LLM-spending surface with no meter of its own: no user is in the loop,
 * so no per-user or per-group bucket is drawn. This budget is that
 * route's sole rate-limiting control. The unit is LLM CALLS over a
 * rolling window, not tokens or currency.
 *
 * <p><b>Drawing.</b> The render's generative call sites draw here ({@link
 * #recordCalls}): {@code DigestRenderer.appendClusterProse} and {@code
 * summaryProseGenerator.generate} on the DigestRenderer side, and the
 * {@code CategoryRollupGenerator.generateRollup} call site. The headline
 * display-hit leg ({@code appendHeadlines}) is NOT drawn: it already has
 * its own per-render budget ({@code translation-max-per-render}, M1-756)
 * and is not among the ticket's enumerated sites.
 *
 * <p><b>The count is an APPROXIMATION, and deliberately so.</b> These draw
 * sites live in {@code DigestRenderer.renderSections}, which is reached by
 * BOTH the scheduled route ({@code DigestScheduler} → {@code
 * DigestWorker.executeSlot}) and {@code /retry --digest} ({@code
 * DigestRetryService.fallbackRerun} → {@code DigestWorker.execute} →
 * {@code executeSlot}). The draws sit at that altitude not because the
 * entry point is single-route — it is not — but because every generative
 * helper below it, {@code SummaryProseGenerator},
 * {@code CategoryRollupGenerator} (also reached via
 * {@code renderShortBody}), {@code TranslationPipeline}, is SHARED with
 * {@code /summary}, {@code /retry} (the personal re-roll, not the digest
 * re-run), chat and saves, so a draw moved down to where the provider call
 * actually happens would meter the user-initiated routes into this
 * system-initiated budget and break the split {@code LlmRateCap} (M1-183)
 * and the D47 per-group sub-bucket exist to maintain (M1-767 redteam
 * 2026-08-04 round 2: the earlier "only scheduled-route-only entry point"
 * claim was FALSE — the retry route reaches the very same render; the
 * retry route is a deliberate exception that BINDS this budget: its
 * FALLBACK re-run is refused pre-charge in {@code
 * RetryCommandHandler} via {@code DigestRetryService.retryLeg} + this
 * budget, and its replay leg — zero LLM calls — is never gated in steady
 * state, a stale probe refusing it free; M1-767 redteam rounds 3-5).
 * Correct SCOPE is bought here at
 * the price of an inexact COUNT. The known divergences (M1-767 redteam
 * 2026-08-04; exact accounting is M1-769):
 *
 * <ul>
 *   <li>OVER-counts when the render issues no HTTP request at all: an
 *       unresolvable SUMMARIZER provider makes {@code
 *       SummaryProseGenerator.generate} return every cluster degraded
 *       with zero calls; an OPEN circuit breaker short-circuits each
 *       {@code provider.generate} "without an HTTP attempt"; {@code
 *       generateRollup}'s empty-prompt skip returns before the router
 *       runs. A sustained LLM outage therefore fills the window at full
 *       nominal rate.</li>
 *   <li>OVER-counts when M1-763's slot-window cancellation discards a
 *       render: on {@code TimeoutException} the worker degrades the
 *       digest and {@code renderFuture.cancel(true)} interrupts the
 *       spend, but the render LOOP still runs to completion and every
 *       draw site still fires for a render nobody received — up to
 *       ~211 calls charged at the cluster cap (M1-767 redteam
 *       2026-08-04 round 2, the largest named leg). This is the ONLY
 *       leg that fires while the endpoint is UP and answering, just
 *       slow, so it is not outage-driven.</li>
 *   <li>UNDER-counts the roll-up's second provider-reaching leg: {@code
 *       CategoryRollupGenerator.generateRollup} calls {@code
 *       translationPipeline.run} after {@code provider.generate}, so a
 *       non-{@code en} roll-up spends two calls and draws one. Also
 *       under-counts when the translation cache evicts between {@code
 *       appendClusterProse}'s pre-call probe and the pipeline's own
 *       read.</li>
 * </ul>
 *
 * <p>The default ceiling is 100× the measured cost of ONE render, which
 * is exactly the documented daily capacity (~50 full-mode groups at two
 * slots/day) — so at that capacity there is NO headroom left for the
 * error legs above; operators running at or near it must scale the
 * ceiling to deployment size. Do not read these draws as a call count;
 * read them as a bounded-error estimate whose error legs are named above.
 *
 * <p><b>Gating.</b> {@code DigestWorker.executeSlot} consults {@link
 * #canStartRender()} before starting a render; a window at/over the
 * ceiling refuses admission and the digest degrades to its existing
 * non-generative path (the same degraded renderer the slot-window timeout
 * uses) — the digest still goes out. The gate is consulted ONCE per
 * render, so an admitted render draws as it runs and may push the window
 * over; the NEXT admission then degrades until the window drains. The
 * overshoot is bounded only by the M1-763 slot window, not by this
 * ceiling — FULL mode lifts the per-section cluster cap, so a render
 * admitted with one call of headroom can still spend the whole slot
 * (M1-767 redteam 2026-08-04, medium/DOS). A per-call bound, and the
 * per-group share that stops one group starving the deployment, are
 * M1-769; they need the exact accounting that ticket also brings. A
 * refused call records nothing (rejection never consumes budget).
 *
 * <p><b>Breach signal.</b> A refused admission emits an operator signal
 * through {@link ThrottledAdminNotifier} — whose own per-(key,
 * throttle-window) coalescing makes it at most one emission per window,
 * never one per suppressed render. That coalescing bounds the MESSAGE,
 * not the work: {@code notifyOnce} still opens a JDBC connection and
 * UPSERTs {@code admin_notification_state} on every refusal. Which is
 * why {@link #canStartRender()} calls it outside this budget's monitor.
 */
@ApplicationScoped
public class SystemLlmBudget {

    /**
     * Coalescing key for the breach signal. Low-cardinality by design —
     * the notifier's {@code admin_notification_state} table grows
     * monotonically, so the key must not vary per group or per slot.
     */
    static final String BREACH_KEY = "digest-system-llm-budget-exhausted";

    // One system-wide deque of call timestamps — no per-user or per-group
    // key, unlike the meters this sits above. The aggregate is the point.
    private final Deque<Long> callTimestamps = new ArrayDeque<>();

    /**
     * Sliding-window length for {@link #callTimestamps} (M1-767). Default
     * PT24H matches the digest cadence the per-slot cost was measured
     * against. Field-initialized so a plain-JUnit construction carries the
     * shipped default (the @ConfigProperty default carries the same value
     * as application.properties) — the {@link #ceiling} pattern.
     */
    @ConfigProperty(name = "infochat.digest.system-llm-call-window", defaultValue = "PT24H")
    Duration window = Duration.ofHours(24);

    /**
     * Call ceiling over {@link #window} (M1-767). Default 3000 = 100× the
     * measured per-render cost (30 generative calls at a realistic
     * 15-cluster full render — ticket body §Measurement), a stated
     * multiple that clears normal operation and trips on runaway spend.
     * Field-initialized so a plain-JUnit construction carries the shipped
     * default — the {@link DigestRenderer#categoryItemCap} pattern.
     */
    @ConfigProperty(name = "infochat.digest.system-llm-call-ceiling", defaultValue = "3000")
    int ceiling = 3000;

    // Decision-gate time reads the injected Clock so the window prune is
    // pinnable under a fixed test clock and the budget never splits across
    // two clocks (engineering-rules §9 / M1-444). Field-initialized so a
    // plain-JUnit construction keeps a real clock — the DigestWorker.clock
    // pattern. Package-visible so tests in this package can pin it.
    @Inject
    Clock clock = Clock.systemUTC();

    // The breach signal rides the existing notifier; package-visible so
    // tests in this package can substitute a recording stub.
    @Inject
    ThrottledAdminNotifier adminNotifier;

    /**
     * Admission gate for one scheduled render: {@code true} while the
     * window is under the ceiling. On refusal — window at/over the
     * ceiling — the caller degrades the digest to its non-generative
     * path, and the breach is signalled via {@link
     * ThrottledAdminNotifier#notifyOnce} (coalesced to at most one
     * emission per its throttle window; never one per suppressed render).
     * A refused call records nothing, so the window drains and admission
     * recovers.
     *
     * <p>NOT {@code synchronized} — only the window read is (see {@link
     * #underCeiling}). The breach signal must not fire while holding this
     * budget's monitor: {@code notifyOnce} opens a JDBC connection and
     * UPSERTs {@code admin_notification_state} on EVERY call, because its
     * coalescing suppresses the *emission*, not the round-trip. Since
     * {@link #recordCalls} and {@link #callsInWindow} share that monitor,
     * signalling inside it would queue every concurrent render's draw
     * behind a DB write on the small (12–16) provider pool — adding work
     * on the breach path precisely under the overload this control exists
     * to shed (M1-767 redteam 2026-08-04, low/DOS).
     */
    public boolean canStartRender() {
        if (underCeiling()) {
            return true;
        }
        adminNotifier.notifyOnce(BREACH_KEY, "llm-budget-exhausted",
                "scheduled digest degraded: system LLM call budget exhausted");
        return false;
    }

    /** The window read alone, under the monitor. See {@link #canStartRender}. */
    private synchronized boolean underCeiling() {
        prune();
        return callTimestamps.size() < ceiling;
    }

    /**
     * Draw {@code calls} LLM calls at the current instant — invoked by the
     * render's generative call sites after the calls complete. The
     * timestamps land in the window and count against the ceiling. What
     * the caller passes is an estimate, not a call count; see the class
     * javadoc's named divergence legs.
     */
    public synchronized void recordCalls(int calls) {
        prune();
        long now = clock.millis();
        for (int i = 0; i < calls; i++) {
            callTimestamps.addLast(now);
        }
    }

    /** Current call count within the window (pruned). Test/observability surface. */
    public synchronized int callsInWindow() {
        prune();
        return callTimestamps.size();
    }

    private void prune() {
        long cutoff = clock.millis() - window.toMillis();
        while (!callTimestamps.isEmpty() && callTimestamps.peekFirst() < cutoff) {
            callTimestamps.pollFirst();
        }
    }
}
