package app.zcat.infochat.provider.digest;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.llm.LlmCallBudget;

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
 * <p><b>Drawing is exact, and it happens where the call is issued</b>
 * (M1-769). {@link #forRender} hands out an {@link LlmCallBudget} sink
 * that {@code DigestRenderer.renderSections} binds around the whole
 * render; {@code BudgetedLlmProvider}, a CDI decorator sitting inside
 * the circuit breaker, draws once per {@code LlmProvider.generate} that
 * actually reaches a provider impl. So the count is a count of issued
 * calls, not of loop iterations:
 *
 * <ul>
 *   <li>A leg that makes no call charges nothing, BY CONSTRUCTION rather
 *       than by a maintained list — an unresolvable router throws before
 *       {@code provider.generate}, an OPEN breaker short-circuits one
 *       decorator layer further out, {@code generateRollup}'s
 *       empty-prompt skip returns before the router runs, and the
 *       {@code en} short-circuit and translation-cache hit both return
 *       before the translation provider. M1-767 charged all of these,
 *       which let a transient outage fill the whole window and convert
 *       itself into a deployment-wide 24h degradation.</li>
 *   <li>A leg that makes a SECOND call charges twice: {@code
 *       generateRollup} calls {@code translationPipeline.run} after
 *       {@code provider.generate}, which M1-767 could not see from the
 *       render loop and under-counted by half on every non-{@code en}
 *       roll-up.</li>
 *   <li>A cancelled render (M1-763 interrupts the render thread) charges
 *       nothing for its remaining calls — the decorator skips the draw
 *       on an already-interrupted caller, matching the M1-764 contract
 *       that such a caller sends no request. This was M1-767's largest
 *       named over-count leg, at up to ~211 phantom calls per timeout.</li>
 *   <li>The NORMAL-mode headline display-hit leg now charges too — it is
 *       a real call. It keeps its own per-render translation allowance
 *       ({@code translation-max-per-render}, M1-756): that bounds one
 *       render's headline spend, this bounds the deployment's, and
 *       neither replaces the other.</li>
 * </ul>
 *
 * <p><b>Scope survives the move down</b> only because the sink is
 * {@code ScopedValue}-carried and bound at exactly one site. Every
 * generative helper the render uses — {@code SummaryProseGenerator},
 * {@code CategoryRollupGenerator} (also reached via {@code
 * renderShortBody}), {@code TranslationPipeline} — is SHARED with
 * {@code /summary}, {@code /retry} (the personal re-roll), chat and
 * saves, and the decorator wraps every provider in the deployment
 * including the collector's. All of those run with nothing bound and so
 * draw nothing, which is what keeps this system-initiated budget off the
 * user-initiated routes {@code LlmRateCap} (M1-183) and the D47
 * per-group sub-bucket meter. The one deliberate exception is {@code
 * /retry --digest}'s FALLBACK re-run, which reaches the very same render
 * and genuinely is digest cost: it binds this pool, refused pre-charge in
 * {@code RetryCommandHandler} via {@code DigestRetryService.retryLeg} +
 * this budget, while its replay leg — zero LLM calls — is never gated in
 * steady state (M1-767 redteam rounds 2-5).
 *
 * <p>The default ceiling is 100× the measured cost of ONE render, which
 * is exactly the documented daily capacity (~50 full-mode groups at two
 * slots/day) — operators running at or near it must scale the ceiling to
 * deployment size.
 *
 * <p><b>Gating, at two altitudes.</b> {@code DigestWorker.executeSlot}
 * consults {@link #canStartRender()} before starting a render; a window
 * at/over the ceiling refuses ADMISSION and the digest degrades to its
 * existing non-generative path (the same degraded renderer the
 * slot-window timeout uses) — the digest still goes out. That gate alone
 * bounded nothing once a render was admitted (M1-767 redteam
 * 2026-08-04, medium/DOS: FULL mode lifts the per-section cluster cap,
 * so a render admitted with one call of headroom could still spend the
 * whole slot), so {@link #tryDraw} now refuses per CALL as well: past the
 * ceiling the render's remaining generative calls are refused and each
 * affected unit degrades through the generators' existing per-call
 * outcomes. A refused call records nothing (rejection never consumes
 * budget), so the window drains and spending recovers.
 *
 * <p><b>Fairness: a reserved tail.</b> {@code
 * DigestScheduler.staggerOffset} is a deterministic {@code groupId hash %
 * windowWidthMinutes}, so under a purely global ceiling the losers are
 * not a random group each day — they are the SAME late-firing groups
 * every day, permanently. The last {@link #groupReserve} calls of the
 * window are therefore spendable only by groups that have drawn fewer
 * than that many themselves — at BOTH altitudes, which is the whole of
 * {@link #wouldAdmit} — so a group that has not had its share still
 * renders however much an earlier group burned. Below the tail nothing
 * changes; normal operation is untouched, which is what keeps this a
 * backstop rather than a cap on ordinary per-group spend.
 *
 * <p>What that buys is bounded, and the bound is the honest statement of
 * it: the reserve is a shared BAND, not a per-group allocation, so a
 * band sized at one render funds ONE latecomer per window and the groups
 * behind it still degrade. It converts "the same groups starve every
 * day, permanently" into "one of them renders" — not into "none of them
 * starves", which would need the band sized at a floor times the number
 * of late-firing groups, an input this budget does not have. A flat
 * per-group sub-cap delivers more whenever {@code N * cap <= ceiling},
 * but it bounds a group on an EMPTY window too, and bounding normal
 * operation is the trade this control exists to avoid.
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

    /**
     * One charged call: when it was drawn, and which group's render drew
     * it. The group is null for {@link #recordCalls}, which has no render
     * scope — such draws count toward the aggregate but toward no group's
     * reserve.
     */
    private record Draw(long millis, @Nullable UUID groupId) {}

    // One system-wide deque of charged calls, oldest first — the aggregate
    // is the point, and the per-group attribution rides along rather than
    // living in a second structure, so pruning the window prunes the
    // fairness accounting with it and the two can never disagree.
    private final Deque<Draw> callTimestamps = new ArrayDeque<>();

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

    /**
     * Calls at the end of {@link #window} reserved for groups that have
     * drawn fewer than this many themselves (M1-769) — the anti-starvation
     * floor described in the class javadoc. Default 30 = the measured cost
     * of ONE full render, the same basis the ceiling's 100× multiple uses,
     * so a group reaching an exhausted window still gets a whole render's
     * worth rather than a fraction of one. Field-initialized so a
     * plain-JUnit construction carries the shipped default — the {@link
     * #ceiling} pattern.
     */
    @ConfigProperty(name = "infochat.digest.system-llm-call-group-reserve", defaultValue = "30")
    int groupReserve = 30;

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
        signalBreach();
        return false;
    }

    /**
     * Admission gate for {@code groupId}'s scheduled render: {@code true}
     * exactly when {@link #tryDraw} would admit that group's next call
     * (M1-769). The two altitudes decide on ONE predicate deliberately —
     * a group-blind gate admits renders whose every call the draw then
     * refuses, so they issue nothing, ship prose-less, and raise no
     * breach signal because the gate never went false. Refusing HERE
     * instead degrades the digest to its non-generative path and emits
     * the operator signal, which is the outcome the reserve was for.
     *
     * <p>The no-arg {@link #canStartRender()} survives as {@code
     * RetryCommandHandler}'s coarse pre-charge probe on the {@code
     * /retry --digest} fallback leg (its draw order is M1-767-decided and
     * out of scope here); that route still reaches this group-aware gate
     * afterwards, inside {@code DigestWorker.executeSlot}, so the
     * authoritative decision is made in one place either way.
     *
     * <p>NOT {@code synchronized}, for the reason {@link
     * #canStartRender()} documents: the breach signal must not fire while
     * holding this budget's monitor.
     */
    public boolean canStartRender(UUID groupId) {
        if (admissible(groupId)) {
            return true;
        }
        signalBreach();
        return false;
    }

    /** The window read alone, under the monitor. See {@link #canStartRender}. */
    private synchronized boolean underCeiling() {
        prune();
        return callTimestamps.size() < ceiling;
    }

    /** The group-aware read alone, under the monitor. See {@link #canStartRender(UUID)}. */
    private synchronized boolean admissible(UUID groupId) {
        prune();
        return wouldAdmit(groupId);
    }

    private void signalBreach() {
        adminNotifier.notifyOnce(BREACH_KEY, "llm-budget-exhausted",
                "scheduled digest degraded: system LLM call budget exhausted");
    }

    /**
     * The render-scoped sink {@code DigestRenderer.renderSections} binds
     * for {@code groupId}'s render, so every provider call issued inside
     * that render draws here and every call issued anywhere else does not
     * (M1-769). Attributing the draws to the group is what lets the
     * reserved tail tell an early spender from a starved latecomer.
     */
    public LlmCallBudget forRender(UUID groupId) {
        return () -> tryDraw(groupId);
    }

    /**
     * Charge ONE call for {@code groupId}'s render if the budget allows
     * it, atomically — the check and the charge share this monitor, so
     * concurrent renders (slot dispatch runs on virtual threads bounded
     * by {@code infochat.summary.workers}) can never push {@link
     * #callsInWindow} past {@link #ceiling} between another's check and
     * its charge. That is the exact bound admission alone cannot give:
     * {@link #canStartRender} is check-then-act across renders, so N of
     * them may still be ADMITTED under the ceiling, but their combined
     * SPEND is hard-capped here.
     *
     * @return {@code true} when the call is charged and may be issued;
     *         {@code false} when the window is at the ceiling, or is
     *         inside the reserved tail and this group has already had its
     *         reserve. A refusal charges nothing.
     */
    public synchronized boolean tryDraw(UUID groupId) {
        prune();
        if (!wouldAdmit(groupId)) {
            return false;
        }
        record(1, groupId);
        return true;
    }

    /**
     * Whether one more call for {@code groupId} is admissible right now —
     * the single predicate BOTH altitudes decide on, so {@link
     * #canStartRender(UUID)} and {@link #tryDraw} cannot drift apart.
     * Extracted rather than duplicated because the drift is exactly the
     * defect: while the reserve lived only in the draw, a render could be
     * admitted into a window that refused its very first call.
     *
     * <p>Callers must hold the monitor and have pruned.
     */
    private boolean wouldAdmit(UUID groupId) {
        if (callTimestamps.size() >= ceiling) {
            return false;
        }
        // Inside the reserved tail, only a group still under its own
        // reserve may spend; below the tail nothing is held back.
        return callTimestamps.size() < ceiling - groupReserve
            || callsForGroup(groupId) < groupReserve;
    }

    /**
     * Draw {@code calls} LLM calls at the current instant, unattributed
     * and UNGATED — they land in the window and count against the ceiling
     * without being refused by it.
     *
     * <p>M1-769 moved the render's own drawing to {@link #tryDraw}, so
     * this has no production caller left; it survives as the seeding
     * surface the M1-767 tests use to put the window in a given state
     * before exercising {@link #canStartRender}. Keep the ungated
     * semantics: those tests pin them.
     */
    public synchronized void recordCalls(int calls) {
        prune();
        record(calls, null);
    }

    /** Current call count within the window (pruned). Test/observability surface. */
    public synchronized int callsInWindow() {
        prune();
        return callTimestamps.size();
    }

    /** Callers must hold the monitor and have pruned. */
    private void record(int calls, @Nullable UUID groupId) {
        long now = clock.millis();
        for (int i = 0; i < calls; i++) {
            callTimestamps.addLast(new Draw(now, groupId));
        }
    }

    /**
     * This group's draws in the pruned window. A linear scan rather than a
     * running per-group tally: the deque is bounded by {@link #ceiling}
     * (3000 by default) and this runs once per LLM call, so it is
     * microseconds against a call measured in seconds — and a second
     * structure would need its own pruning, which is exactly where an
     * expiry accounting bug would hide.
     *
     * <p>Callers must hold the monitor and have pruned.
     */
    private int callsForGroup(UUID groupId) {
        int count = 0;
        for (Draw draw : callTimestamps) {
            if (groupId.equals(draw.groupId())) {
                count++;
            }
        }
        return count;
    }

    private void prune() {
        long cutoff = clock.millis() - window.toMillis();
        while (!callTimestamps.isEmpty() && callTimestamps.peekFirst().millis() < cutoff) {
            callTimestamps.pollFirst();
        }
    }
}
