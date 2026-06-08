package app.zcat.infochat.provider.messaging;

import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.MessagingException;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ProgressNotifier;
import app.zcat.infochat.messaging.ProgressStage;
import app.zcat.infochat.messaging.ScopeRef;
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
 * coalesced {@link MessagingAdapter#update}, honoring a single
 * system-wide edit-interval floor ({@link #minEditIntervalMs}; the
 * spec's {@code max(adapterMin, systemFloor)} degrades to this floor in
 * v1 because per-adapter {@code adapterMin} is not exposed). On terminal
 * {@link #complete} / {@link #fail} the placeholder is finalized and
 * typing turned off via try/finally so it is never left dangling.</p>
 *
 * <p><b>Security (no user input in stage strings).</b> Every stage
 * string is resolved by enum from the deterministic localization bundle
 * (decision D43) via {@link BundleLoader#get(String)} with no
 * interpolation of user-authored text. {@link #complete}'s
 * {@code finalText} is the caller-composed operation output (already
 * sanitized by the handler), not a stage label.</p>
 *
 * <p><b>Checked-exception absorption.</b> {@link MessagingException}
 * (checked) is caught and logged internally — the {@code ProgressNotifier}
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

    /**
     * Single system-wide edit-coalescing floor in milliseconds (design
     * {@code 06-messaging.md} §6.3.8 records 600ms). The notifier emits
     * at most one {@link MessagingAdapter#update} per this interval per
     * message; the terminal {@link #complete}/{@link #fail} finalize is
     * always sent regardless of the window.
     */
    @ConfigProperty(name = "infochat.messaging.progress.min-edit-interval-ms", defaultValue = "600")
    long minEditIntervalMs;

    /**
     * Per-scope notifier state. Created on the first publish for a
     * scope, removed on the terminal call. Concurrent publishes for the
     * SAME scope are serialized on the value monitor; different scopes
     * never share a state object.
     */
    private final ConcurrentHashMap<ScopeRef, ScopeState> states = new ConcurrentHashMap<>();

    @Override
    public void publish(ScopeRef scope, ProgressStage stage) {
        String text = bundleLoader.get(bundleKeyFor(stage));
        MessagingAdapter adapter = resolveAdapter();
        ScopeState state = states.computeIfAbsent(scope, s -> new ScopeState());
        synchronized (state) {
            if (state.handle == null) {
                // Step 1+2: acquire the placeholder, capture the handle,
                // turn typing on. A failed placeholder send leaves
                // handle null so a later publish retries and complete()
                // falls back to a fresh send (placeholder never dangles).
                try {
                    state.handle = adapter.send(outbound(scope, text));
                } catch (MessagingException e) {
                    SafeLog.error(log,
                            "ProgressNotifier placeholder send failed for adapter=" + adapter.name(), e);
                    return;
                }
                state.lastEditAt = Instant.now();
                adapter.setTyping(scope, true);
                return;
            }
            // Step 3: coalesce — emit at most one update per floor
            // window; intermediate texts inside the window are discarded
            // silently (the terminal finalize carries the real output).
            Instant now = Instant.now();
            if (Duration.between(state.lastEditAt, now).toMillis() < minEditIntervalMs) {
                return;
            }
            try {
                adapter.update(state.handle, text);
                state.lastEditAt = now;
            } catch (MessagingException e) {
                SafeLog.error(log,
                        "ProgressNotifier update failed for adapter=" + adapter.name(), e);
            }
        }
    }

    @Override
    public void complete(ScopeRef scope, String finalText) {
        terminate(scope, finalText);
    }

    @Override
    public void fail(ScopeRef scope) {
        terminate(scope, bundleLoader.get(BundleKeys.PROGRESS_FAILED));
    }

    /**
     * Step 4: finalize the placeholder with {@code text} and turn
     * typing off, both guaranteed via try/finally. When no placeholder
     * exists (publish never ran, or its send failed) the terminal text
     * is delivered as a fresh send so the user still receives the
     * outcome.
     */
    private void terminate(ScopeRef scope, String text) {
        MessagingAdapter adapter = resolveAdapter();
        ScopeState state = states.remove(scope);
        MessageHandle handle = state == null ? null : state.handle;
        try {
            if (handle != null) {
                adapter.finalizeMessage(handle, text);
            } else {
                adapter.send(outbound(scope, text));
            }
        } catch (MessagingException e) {
            SafeLog.error(log,
                    "ProgressNotifier terminal delivery failed for adapter=" + adapter.name(), e);
        } finally {
            // Typing was only turned on if a placeholder was acquired.
            if (handle != null) {
                adapter.setTyping(scope, false);
            }
        }
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
