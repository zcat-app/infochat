package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ProgressNotifier;
import app.zcat.infochat.messaging.ProgressStage;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.metrics.AdapterMetrics;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Concrete provider-side {@link ProgressNotifier} (decision D31,
 * {@code docs/spec/messaging.md} §Progress notifications steps 1–4).
 * Turns a stream of {@link ProgressStage} events into a single
 * visibly-evolving message on the adapter the inbound was delivered
 * through.
 *
 * <p><b>Adapter resolution.</b> The bound adapter is the one the
 * router dispatched from: resolved from
 * {@link AdapterRegistry#activatedAdapters()} keyed by the current
 * request's {@link InboundContext#adapterName()}. This keeps delivery
 * on the same adapter identity space the request arrived on (D46) — a
 * summary requested over {@code inmemory} is finalized over
 * {@code inmemory}, never another activated adapter.</p>
 *
 * <p><b>Lifecycle (spec steps 1–4).</b> The first {@link #publish} for
 * a scope sends a placeholder via {@link MessagingAdapter#send},
 * captures the {@link MessageHandle}, and turns typing on. Subsequent
 * non-terminal publishes render the stage's localized string and emit a
 * coalesced {@link MessagingAdapter#update}, honoring the spec's
 * {@code max(adapterMin, systemFloor)} edit-interval floor — the larger
 * of the system-wide floor ({@link #minEditIntervalMs}) and the bound
 * adapter's declared {@code minEditInterval} capability (SimpleX declares
 * 600ms). On terminal
 * {@link #complete} / {@link #fail} the placeholder is finalized and
 * typing turned off via try/finally so it is never left dangling.</p>
 *
 * <p><b>Security (no user input in stage strings).</b> Every stage
 * string is resolved by enum from the deterministic localization bundle
 * (decision D43) via {@link BundleLoader#get(String, String)} in the
 * requester's effective scope language with no
 * interpolation of user-authored text. {@link #complete}'s
 * {@code finalText} is the caller-composed operation output (already
 * sanitized by the handler), not a stage label.</p>
 *
 * <p><b>Checked-exception absorption.</b> Every send/update/finalize goes
 * through {@link OutboundDelivery}, which absorbs {@link MessagingException}
 * (retry on transient, abort on permanent) — the {@code ProgressNotifier}
 * SPI does not declare it, and the spec mandates that an intermediate
 * transport failure never leaves a dangling placeholder or leaks out of
 * the calling handler.</p>
 */
@ApplicationScoped
public class StageProgressNotifier implements ProgressNotifier {

    private static final Logger log = LoggerFactory.getLogger(StageProgressNotifier.class);

    @Inject
    AdapterRegistry adapterRegistry;

    @Inject
    InboundContext inboundContext;

    @Inject
    BundleLoader bundleLoader;

    @Inject
    OutboundDelivery outboundDelivery;

    /**
     * §6.12 adapter-metrics emission point for the edit lifecycle
     * ({@code adapter.outbound.update.total} ok/coalesced/fail and
     * {@code adapter.outbound.update.lag}) — this class owns the
     * coalescing window, so only it can classify these. The
     * throwaway-registry initializer keeps the plain-constructed
     * notifier tests working unmodified; CDI replaces it with the
     * produced deployment-wide bean.
     */
    @Inject
    AdapterMetrics adapterMetrics = AdapterMetrics.noop();

    /**
     * Single system-wide edit-coalescing floor in milliseconds (design
     * {@code 06-messaging.md} §6.3.8 records 600ms). The notifier emits
     * at most one {@link MessagingAdapter#update} per the effective floor
     * (the larger of this value and the bound adapter's declared
     * {@code minEditInterval}) per message; the terminal
     * {@link #complete}/{@link #fail} finalize is always sent regardless
     * of the window.
     */
    @ConfigProperty(name = "infochat.messaging.progress.min-edit-interval-ms", defaultValue = "600")
    long minEditIntervalMs;

    /**
     * Per-operation notifier state, keyed by the request-scoped
     * {@link InboundContext#operationId()} (one inbound dispatch = one
     * operation), NOT by the destination scope. Created on the first publish
     * of an operation, removed on that operation's terminal call. Keying by
     * operation is what lets two operations publishing concurrently into the
     * SAME scope — two users' chat turns in one approved group, or a chat turn
     * alongside {@code /summary} — each own an independent placeholder,
     * instead of the second taking the UPDATE path against the first's handle
     * and whichever terminal lands first finalizing the wrong operation's text
     * (M1-611). Concurrent publishes of the SAME operation are serialized on
     * the value monitor.
     */
    private final ConcurrentHashMap<String, ScopeState> states = new ConcurrentHashMap<>();

    @Override
    public void publish(ScopeRef scope, ProgressStage stage) {
        String text = bundleLoader.get(bundleKeyFor(stage), inboundContext.effectiveLanguage());
        MessagingAdapter adapter = resolveAdapter();
        if (!adapter.capabilities().supportsMessageEdit()) {
            // Spec messaging.md §Progress notifications: adapters without
            // supportsMessageEdit collapse to a single final send of the
            // completed text — no placeholder, no intermediate edits, and
            // callers never branch on transport (M1-607). With no ScopeState
            // minted here, the terminal complete()/fail() takes terminate()'s
            // no-handle branch, which delivers the final text as the one
            // fresh send; typing is never turned on, so nothing needs
            // turning off.
            return;
        }
        String operationId = inboundContext.operationId();
        ScopeState state = states.computeIfAbsent(operationId, id -> new ScopeState());
        // Register the request-end safety net before any outbound work: if
        // this dispatch abandons the operation without a terminal
        // complete()/fail(), the @RequestScoped InboundContext's @PreDestroy
        // drains it so the placeholder is finalized and typing turned off —
        // the publish->terminate lifecycle is never left dangling, not only
        // the paths that reach terminate() (M1-334). The cleanup captures THIS
        // operation's id so, under concurrent same-scope operations, an
        // abandoned operation terminates only its own placeholder and never a
        // live concurrent one's (M1-611). Idempotent per scope.
        inboundContext.registerProgressCleanup(scope, () -> terminateAbandoned(operationId, scope));
        synchronized (state) {
            if (state.handle == null) {
                // Step 1+2: acquire the placeholder via the outbound
                // chokepoint, capture the handle, turn typing on. A null
                // handle means the chokepoint aborted the send (after its
                // TRANSIENT retries); leave handle null so a later publish
                // retries and complete() falls back to a fresh send
                // (placeholder never dangles).
                MessageHandle handle = outboundDelivery.deliver(adapter, outbound(scope, text));
                if (handle == null) {
                    return;
                }
                state.handle = handle;
                state.lastEditAt = Instant.now();
                adapter.setTyping(scope, true);
                return;
            }
            // Step 3: coalesce — emit at most one update per the effective
            // edit-interval floor; intermediate texts inside the window are
            // discarded silently (the terminal finalize carries the real
            // output). The effective floor is max(systemFloor, adapterMin)
            // per docs/spec/messaging.md §Progress notifications: the
            // adapter's declared minEditInterval (SimpleX's 600ms) wins when
            // it is the stricter of the two.
            long effectiveEditIntervalMs = Math.max(
                    minEditIntervalMs, adapter.capabilities().minEditInterval().toMillis());
            Instant now = Instant.now();
            if (Duration.between(state.lastEditAt, now).toMillis() < effectiveEditIntervalMs) {
                adapterMetrics.updateOutcome(adapter.name(), scope,
                        AdapterMetrics.UpdateOutcome.COALESCED);
                return;
            }
            if (outboundDelivery.updateInPlace(adapter, state.handle, text)) {
                state.lastEditAt = now;
                adapterMetrics.updateOutcome(adapter.name(), scope,
                        AdapterMetrics.UpdateOutcome.OK);
                // §6.12 update lag: this publish's entry to the edit on
                // the wire. Coalescing here discards rather than defers,
                // so the lag of a transmitted edit is its own delivery
                // time (including the chokepoint's transient retries).
                adapterMetrics.updateLag(adapter.name(), Duration.between(now, Instant.now()));
                adapterMetrics.messageBytes(adapter.name(),
                        AdapterMetrics.Direction.OUTBOUND, text);
            } else {
                adapterMetrics.updateOutcome(adapter.name(), scope,
                        AdapterMetrics.UpdateOutcome.FAIL);
            }
        }
    }

    /**
     * Queued-dispatch acknowledgement (M1-635): open an operation's
     * placeholder on the TRANSPORT thread, at submit time, under the
     * caller-supplied purpose-minted {@code operationId} — never this
     * request's own id, which dies with the submitting context the moment
     * {@code onMessage} returns. Same first-publish semantics as
     * {@link #publish}'s STARTED branch — placeholder send via the outbound
     * chokepoint, handle captured, typing on, D31 collapse no-op for
     * adapters without message edit — with ONE deliberate difference: NO
     * request-end cleanup is registered. The submitted task may still sit
     * in the dispatch queue when the transport context is destroyed; a
     * cleanup registered here would fire {@link #terminateAbandoned}
     * against a merely-queued operation, finalizing the placeholder with
     * FAILED text before the worker ever ran. Lifecycle ownership transfers
     * to the worker instead: its own publishes register the M1-334 drain
     * under the seeded id, and its terminal (or the router's queued-Reply
     * reconciliation) removes the state. A chokepoint-aborted send leaves
     * the handle null exactly like {@link #publish}'s first branch — the
     * worker's next publish retries the send and the terminal degrades to
     * a fresh send, so nothing dangles.
     */
    void publishQueuedPlaceholder(String operationId, ScopeRef scope) {
        MessagingAdapter adapter = resolveAdapter();
        if (!adapter.capabilities().supportsMessageEdit()) {
            return;
        }
        String text = bundleLoader.get(
                BundleKeys.PROGRESS_STARTED, inboundContext.effectiveLanguage());
        ScopeState state = states.computeIfAbsent(operationId, id -> new ScopeState());
        // The monitor pairs with the worker-side publish/terminate blocks so
        // the handle written here is visible to the worker thread that later
        // updates or finalizes this placeholder. Instant.now() (not an
        // injected Clock) deliberately: the coalescing window's read side and
        // every other lastEditAt write in this component use ambient time —
        // a lone injected-clock write would split one component across two
        // clocks (engineering-rules §9 forbids exactly that split; the
        // whole-component migration is M1-447).
        synchronized (state) {
            MessageHandle handle = outboundDelivery.deliver(adapter, outbound(scope, text));
            if (handle == null) {
                return;
            }
            state.handle = handle;
            state.lastEditAt = Instant.now();
            adapter.setTyping(scope, true);
        }
    }

    @Override
    public void complete(ScopeRef scope, String finalText) {
        completeDelivered(scope, finalText);
    }

    /**
     * Terminal success like {@link #complete}, additionally reporting
     * whether {@code finalText} actually reached the adapter — {@code true}
     * when the placeholder finalize (or, with no placeholder, the fresh
     * terminal send) was accepted by the outbound chokepoint, {@code false}
     * on a permanent/exhausted abort. The chat dispatch gates its deferred
     * post-delivery persist on this outcome so a permanently-failed reply
     * leaves the context window "as if the message was never generated"
     * (spec {@code messaging.md} §Failure handling, M1-607). Not part of
     * the {@link ProgressNotifier} SPI: handlers that carry no deferred
     * commit ({@code /summary}) keep the fire-and-forget {@code complete}.
     */
    public boolean completeDelivered(ScopeRef scope, String finalText) {
        return terminate(inboundContext.operationId(), scope, finalText);
    }

    @Override
    public void fail(ScopeRef scope) {
        terminate(inboundContext.operationId(), scope, failedText());
    }

    private String failedText() {
        return bundleLoader.get(BundleKeys.PROGRESS_FAILED, inboundContext.effectiveLanguage());
    }

    /**
     * Request-end safety net (M1-334): finalize a placeholder the operation
     * identified by {@code operationId} still holds because its dispatch
     * abandoned it without a terminal {@link #complete}/{@link #fail}.
     * Degrades to the documented {@link #fail} outcome — friendly failed text,
     * typing OFF — so the user is never left with a perpetual typing
     * indicator. Keyed by the abandoning operation's id (captured in the
     * cleanup closure at publish time, not re-read from the request-scoped
     * context being torn down), so it terminates ONLY that operation's
     * placeholder and never a live concurrent operation's in the same scope
     * (M1-611). A no-op once that operation terminated normally (its
     * {@code states} entry is already removed), so the normal lifecycle is
     * untouched. Invoked by {@link InboundContext#drainAbandonedProgress()}.
     */
    void terminateAbandoned(String operationId, ScopeRef scope) {
        if (states.containsKey(operationId)) {
            terminate(operationId, scope, failedText());
        }
    }

    /**
     * Step 4: finalize the placeholder with {@code text} and turn typing
     * off. When no placeholder exists (publish never ran, or its send was
     * aborted) the terminal text is delivered as a fresh send so the user
     * still receives the outcome. Both the finalize and the fresh send go
     * through the outbound chokepoint, which absorbs transport failures
     * (retry/abort) internally — so typing is turned off unconditionally
     * after a finalize, with no exception able to skip it.
     *
     * @return whether {@code text} reached the adapter (finalize accepted,
     *         or the fresh send yielded a handle) — the delivery outcome
     *         {@link #completeDelivered} reports.
     */
    private boolean terminate(String operationId, ScopeRef scope, String text) {
        MessagingAdapter adapter = resolveAdapter();
        ScopeState state = states.remove(operationId);
        MessageHandle handle = state == null ? null : state.handle;
        if (handle != null) {
            // The terminal finalize is an in-place edit, so it counts
            // under the §6.12 update outcomes (the fresh-send branch
            // below is counted by the chokepoint as a send).
            boolean finalized = outboundDelivery.finalizeInPlace(adapter, handle, text);
            if (finalized) {
                adapterMetrics.updateOutcome(adapter.name(), scope,
                        AdapterMetrics.UpdateOutcome.OK);
                adapterMetrics.messageBytes(adapter.name(),
                        AdapterMetrics.Direction.OUTBOUND, text);
            } else {
                adapterMetrics.updateOutcome(adapter.name(), scope,
                        AdapterMetrics.UpdateOutcome.FAIL);
            }
            // Typing was only turned on if a placeholder was acquired.
            adapter.setTyping(scope, false);
            return finalized;
        }
        return outboundDelivery.deliver(adapter, outbound(scope, text)) != null;
    }

    private MessagingAdapter resolveAdapter() {
        String adapterName = inboundContext.adapterName();
        for (MessagingAdapter adapter : adapterRegistry.activatedAdapters()) {
            if (adapter.name().equals(adapterName)) {
                return adapter;
            }
        }
        throw new IllegalStateException(
                "ProgressNotifier: no activated adapter named '" + adapterName + "'");
    }

    private static OutboundMessage outbound(ScopeRef scope, String text) {
        return new OutboundMessage(scope, text, Instant.now(), UUID.randomUUID().toString());
    }

    private static String bundleKeyFor(ProgressStage stage) {
        return switch (stage) {
            case STARTED -> BundleKeys.PROGRESS_STARTED;
            case RETRIEVING -> BundleKeys.PROGRESS_RETRIEVING;
            case GENERATING -> BundleKeys.PROGRESS_GENERATING;
            case TRANSLATING -> BundleKeys.PROGRESS_TRANSLATING;
            case FINALIZING -> BundleKeys.PROGRESS_FINALIZING;
            case COMPLETED -> BundleKeys.PROGRESS_COMPLETED;
            case FAILED -> BundleKeys.PROGRESS_FAILED;
        };
    }

    private static final class ScopeState {
        @Nullable MessageHandle handle;
        Instant lastEditAt = Instant.EPOCH;
    }
}
