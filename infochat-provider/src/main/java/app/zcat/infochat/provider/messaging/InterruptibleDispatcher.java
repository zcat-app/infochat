package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.core.log.SafeLog;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded per-request worker seam for the D35 interruptible dispatch
 * class (chat-mode turns, user-issued {@code /summary}, user-issued
 * {@code /retry} re-roll). {@link InboundRouter#onMessage} hands the
 * step-6 stage of an interruptible inbound to {@link #dispatch} so the
 * slow LLM turn runs OFF the transport's single dispatch thread; every
 * other intake step and every non-interruptible dispatch stays on the
 * transport thread in arrival order.
 *
 * <p><b>Why (M1-634).</b> With the whole turn inline on the
 * single-threaded transport executor, a second same-{@code (user,
 * scope)} request could only dequeue after the first released its
 * {@code InFlightTracker} slot — the spec'd "request already in
 * progress" reject (commands.md §Surface conventions) was structurally
 * unreachable, and {@code /stop} (D35 "immediately") queued behind the
 * very call it should cancel. Offloading ONLY the interruptible stage
 * makes both reachable: the guard observes real contention on the
 * worker, and {@code /stop} — never interruptible itself — runs inline
 * while the worker holds the LLM call.
 *
 * <p><b>Bounded load (llm.md §Bounded concurrency, D46).</b> The pool
 * IS the provider-side interruptible-LLM concurrency bound: pool size
 * and queue depth are capped by {@code
 * infochat.chat.dispatch.max-concurrency}, and saturation degrades via
 * {@link ThreadPoolExecutor.CallerRunsPolicy} to the pre-M1-634
 * inline-on-transport-thread behaviour — the transport's own bounded
 * inbound queue (M1-224) then applies back-pressure exactly as before,
 * so a burst can never spawn unbounded concurrent LLM calls and needs
 * no new "busy" reply surface. {@code LlmRateCap} and the group
 * backstops are untouched and keep binding per user/group.
 *
 * <p><b>Request-scope isolation across the hop (the {@code risk:
 * high} core).</b> Each worker task activates a FRESH CDI request
 * context ({@link #runStage} is invoked through the bean's own client
 * proxy so the {@code @ActivateRequestContext} interceptor fires) and
 * seeds the new {@link InboundContext} with the three scalars captured
 * on the transport thread. Nothing else crosses the hop: every
 * interruptible stage additionally seeds a PURPOSE-MINTED per-turn id
 * as its {@code operationId} (M1-635/M1-638) — the turn's identity in
 * {@code InFlightTracker} and the key for M1-611 per-operation
 * progress state — captured in the stage closure as a plain String,
 * never the submitting context's own id, so the contract that nothing
 * on the worker reads the submitting context holds verbatim. The
 * {@code pendingChatCommit} / {@code requestEndCleanups} live out
 * their whole lifecycle inside the worker context, whose destroy runs
 * the M1-334 abandoned-progress drain.
 */
@Startup
@ApplicationScoped
public class InterruptibleDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InterruptibleDispatcher.class);

    /**
     * The provider-side interruptible-LLM concurrency bound. Validated
     * ≥ 2 at init: a bound of 1 structurally recreates the M1-629
     * serialization this seam exists to fix (one slot means the guard
     * again never observes contention), just at the pool level.
     */
    @ConfigProperty(name = "infochat.chat.dispatch.max-concurrency", defaultValue = "4")
    int maxConcurrency;

    @Inject
    InboundContext inboundContext;

    /**
     * The bean's own client proxy. {@link #dispatch} routes each task
     * through {@code self.runStage(...)} so the
     * {@code @ActivateRequestContext} interceptor actually fires — a
     * plain {@code this.runStage(...)} would bypass interception and
     * run the stage with no request context on the pool thread.
     */
    @Inject
    InterruptibleDispatcher self;

    // Initialized in @PostConstruct (CDI-managed path only); the
    // direct() test instance never touches it (dispatch() short-
    // circuits on directMode first).
    @SuppressWarnings("NullAway.Init")
    private ThreadPoolExecutor executor;

    /**
     * Exact submitted-but-not-finished task count (queued + running,
     * including CallerRunsPolicy inline runs). Kept by hand because
     * {@link ThreadPoolExecutor#getActiveCount()} is documented
     * approximate — tests await {@code inFlightTaskCount() == 0} as the
     * happens-before fact for negative asserts (no-double-send, exact
     * counts), and an approximate count would reintroduce the timing
     * flake class those asserts must exclude.
     */
    private final AtomicInteger inFlightTasks = new AtomicInteger();

    /** True only for {@link #direct()} instances — run the stage on the caller thread. */
    private boolean directMode;

    /**
     * Run-on-caller-thread instance for hand-constructed router tests:
     * no executor, no CDI context dance — {@link #dispatch} degenerates
     * to {@code stage.run()}, preserving the exact synchronous
     * behaviour those tests were written against. Field-init default
     * for {@code InboundRouter}'s injected field, same pattern as
     * {@code AdapterMetrics.noop()} / {@code Clock.systemUTC()} there.
     */
    public static InterruptibleDispatcher direct() {
        InterruptibleDispatcher dispatcher = new InterruptibleDispatcher();
        dispatcher.directMode = true;
        return dispatcher;
    }

    @PostConstruct
    void init() {
        if (maxConcurrency < 2) {
            // Config-boundary validation: see the field javadoc — 1
            // would silently re-serialize interruptible dispatch.
            throw new IllegalStateException(
                    "infochat.chat.dispatch.max-concurrency must be >= 2 (was "
                            + maxConcurrency + ")");
        }
        // Pin each pool thread's TCCL to the application classloader at
        // creation. Pool threads are minted lazily on first use, which
        // can be triggered from an adapter-owned transport thread whose
        // TCCL is foreign — TCCL-keyed lookups in provider code (most
        // notably MicroProfile Config resolution at lazy ARC bean
        // creation) would then fail exactly as live finding F-live-1 /
        // M1-543 documented for adapter callbacks. @Startup makes THIS
        // bean's own config injection happen on the startup thread; the
        // factory extends the same guarantee to every stage the pool
        // ever runs.
        ClassLoader applicationClassLoader = InterruptibleDispatcher.class.getClassLoader();
        AtomicInteger threadCounter = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task,
                    "interruptible-dispatch-" + threadCounter.incrementAndGet());
            thread.setDaemon(true);
            thread.setContextClassLoader(applicationClassLoader);
            return thread;
        };
        // Fixed-size pool; the queue holds at most one short burst
        // (capacity = pool size) before CallerRunsPolicy degrades
        // submissions to the pre-M1-634 inline behaviour — the
        // transport's bounded inbound queue is the real back-pressure
        // surface, so a deep queue here would only hide it.
        executor = new ThreadPoolExecutor(
                maxConcurrency, maxConcurrency,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxConcurrency),
                factory,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * Run {@code stage} on a bounded worker with a fresh, seeded CDI
     * request context. {@code adapterName} / {@code senderContactId} /
     * {@code effectiveLanguage} are the plain values the transport
     * thread captured from ITS request context before submitting —
     * the stage must never read the submitting thread's context after
     * this call (that context is destroyed when {@code onMessage}
     * returns; a worker-side read of it is exactly the cross-user leak
     * M1-634 guards against).
     */
    public void dispatch(String adapterName, String senderContactId,
                         String effectiveLanguage, Runnable stage) {
        if (directMode) {
            stage.run();
            return;
        }
        inFlightTasks.incrementAndGet();
        executor.execute(() -> {
            try {
                self.runStage(adapterName, senderContactId, effectiveLanguage, stage);
            } finally {
                inFlightTasks.decrementAndGet();
            }
        });
    }

    /**
     * Worker-side stage execution. Public (never call directly — only
     * via {@link #dispatch}) so the client-proxy interception that
     * powers {@code @ActivateRequestContext} applies: on a pool thread
     * the interceptor activates a fresh request context; on the
     * CallerRunsPolicy fallback the transport thread's own context is
     * already active, the interceptor no-ops, and the seed below
     * rewrites the identical values — inline degradation stays
     * behaviour-identical to pre-M1-634 dispatch.
     */
    @ActivateRequestContext
    public void runStage(String adapterName, String senderContactId,
                         String effectiveLanguage, Runnable stage) {
        try {
            inboundContext.setAdapterName(adapterName);
            inboundContext.setSenderContactId(senderContactId);
            inboundContext.setEffectiveLanguage(effectiveLanguage);
            stage.run();
        } catch (RuntimeException e) {
            // Async boundary: pre-M1-634 an escaped stage exception
            // propagated into the adapter's dispatch loop and its D37
            // catch logged it; on a pool thread it would vanish to the
            // default UncaughtExceptionHandler instead. Keep the drop
            // observable at the same severity.
            SafeLog.error(log, "Interruptible dispatch stage failed", e);
        }
        // Interrupt hygiene lives in CancellationHandle: /stop's interrupt
        // is issued only while the handle's monitor-guarded gate is open,
        // and releaseWorker() closes the gate and clears the thread's
        // interrupt status atomically at the end of the in-flight section —
        // so no cancellation interrupt can exist here, after the stage, to
        // poison the pool thread's next task (M1-634 redteam remediation).
    }

    /**
     * Would a stage submitted right now sit in the pool queue rather
     * than start immediately? Exact-counter comparison: at least
     * {@code maxConcurrency} stages are submitted-but-unfinished, so
     * every worker is occupied and the next submission queues (or, past
     * queue capacity, degrades to a CallerRuns inline run — an
     * acknowledgement preceding that stays honest, since work then
     * starts immediately on the caller thread). Racy by nature at the
     * call site (M1-635): a worker freeing between this check and
     * {@link #dispatch} costs one acknowledgement for a turn that runs
     * immediately; the inverse window restores the pre-M1-635 silence
     * for that one turn. Both degrade to prior behaviour — never to a
     * double placeholder, because the acknowledgement and the worker's
     * own publishes share one seeded operationId. Always {@code false}
     * in {@code directMode}: a {@link #direct()} instance runs the
     * stage synchronously on the caller thread (nothing ever queues),
     * and its uninjected {@code maxConcurrency} of 0 would otherwise
     * make the comparison vacuously true for every hand-constructed
     * router test.
     */
    public boolean wouldQueue() {
        return !directMode && inFlightTasks.get() >= maxConcurrency;
    }

    /**
     * Exact count of dispatched-but-unfinished interruptible stages
     * (queued + running). Test await point: negative asserts
     * (no-double-send, exact message counts) poll this to zero so
     * "no further send can arrive" is a happens-before fact rather
     * than a sleep — same test-only-seam precedent as
     * {@code InboundRouter.NORMALIZE_INVOCATIONS}.
     */
    public int inFlightTaskCount() {
        return inFlightTasks.get();
    }

    @PreDestroy
    void shutdown() {
        // shutdownNow (not shutdown): at container stop an in-flight
        // LLM call may block for minutes; interrupting it is exactly
        // the D35 cancellation path workers already honour.
        executor.shutdownNow();
    }
}
