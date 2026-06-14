package app.zcat.infochat.messaging.metrics;

import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.messaging.Utf8;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jspecify.annotations.Nullable;

import java.time.Duration;

/**
 * Micrometer emission point for the adapter observability catalogue
 * (docs/design/06-messaging.md §6.12):
 *
 * <ul>
 *   <li>{@code adapter.inbound.total}{adapter, scope_kind} — counter</li>
 *   <li>{@code adapter.inbound.dropped}{adapter, scope_kind, reason} — counter,
 *       reason ∈ {oversize, queue_full} (§6.3.10 transport size cap, §6.3.7
 *       inbound-queue overflow)</li>
 *   <li>{@code adapter.outbound.total}{adapter, scope_kind, outcome} — counter,
 *       outcome ∈ {ok, retry, fail}</li>
 *   <li>{@code adapter.inbound.queue.size}{adapter} — gauge</li>
 *   <li>{@code adapter.outbound.queue.size}{adapter} — gauge</li>
 *   <li>{@code adapter.connection.status}{adapter} — gauge (1 connected, 0 disconnected)</li>
 *   <li>{@code adapter.identity.assert.fail}{adapter} — counter</li>
 *   <li>{@code adapter.simplex.auth.fail}{adapter} — counter</li>
 *   <li>{@code adapter.message.bytes}{adapter, direction} — histogram</li>
 *   <li>{@code adapter.outbound.update.total}{adapter, scope_kind, outcome} — counter,
 *       outcome ∈ {ok, coalesced, fail, fallback_send}</li>
 *   <li>{@code adapter.outbound.update.fail}{adapter, reason} — counter,
 *       reason ∈ {item_too_old, item_deleted, not_owner, transport, unknown}</li>
 *   <li>{@code adapter.outbound.update.lag}{adapter} — histogram</li>
 *   <li>{@code adapter.typing.toggle}{adapter, scope_kind, value} — counter,
 *       value ∈ {on, off}</li>
 * </ul>
 *
 * <p>Emission is split by who owns the signal. The Provider-side
 * chokepoints drive the traffic counters: {@code InboundRouter}
 * (inbound.total, inbound message bytes), {@code OutboundDelivery}
 * (outbound.total with its retry/fail classification, outbound message
 * bytes), and {@code StageProgressNotifier} (update ok/coalesced/fail,
 * update lag — the coalescer owns the edit-window timestamps). The
 * adapters drive the transport-internal signals through
 * {@link MessagingAdapter#bindMetrics}: the §6.3.8/§6.4.5 edit-failure
 * fallback ({@code fallback_send} + per-reason fail counter) and the
 * typing toggle fire where the transport call happens, so a
 * capability-declared no-op {@code setTyping} naturally stays at zero.
 * The per-adapter gauges are registered once per activated adapter via
 * {@link #bindAdapter}.</p>
 *
 * <p><b>Registered-but-unwired counters.</b>
 * {@code adapter.identity.assert.fail} and
 * {@code adapter.simplex.auth.fail} are eagerly registered at
 * {@link #bindAdapter} so the catalogue is complete on every scrape,
 * but no production path increments them yet: identity-assertion drops
 * happen inside the codec decode paths (which discard malformed frames
 * without distinguishing identity failures from other ignore reasons),
 * and the §6.4.6 session-token auth classification that
 * {@code adapter.simplex.auth.fail} counts is not implemented in the
 * SimpleX transport. Each increment site lands with the machinery it
 * counts.</p>
 */
public final class AdapterMetrics {

    /** {@code adapter.outbound.total} outcome label domain (§6.12). */
    public enum SendOutcome {
        OK("ok"), RETRY("retry"), FAIL("fail");

        private final String label;

        SendOutcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** {@code adapter.outbound.update.total} outcome label domain (§6.12). */
    public enum UpdateOutcome {
        OK("ok"), COALESCED("coalesced"), FAIL("fail"), FALLBACK_SEND("fallback_send");

        private final String label;

        UpdateOutcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * {@code adapter.outbound.update.fail} reason label domain (§6.12).
     * {@link #UNKNOWN} is the honest value for an edit rejection the
     * transport does not discriminate — SimpleX's single
     * {@code CEInvalidChatItemUpdate} tag covers "item too old, deleted,
     * or not the bot's own message" (§6.4.5) without saying which.
     */
    public enum UpdateFailReason {
        ITEM_TOO_OLD("item_too_old"),
        ITEM_DELETED("item_deleted"),
        NOT_OWNER("not_owner"),
        TRANSPORT("transport"),
        UNKNOWN("unknown");

        private final String label;

        UpdateFailReason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * {@code adapter.inbound.dropped} reason label domain. {@link #OVERSIZE}
     * is the §6.3.10 transport size-cap shed (the decoded body exceeded
     * {@code maxInboundMessageBytes}); {@link #QUEUE_FULL} is the §6.3.7
     * inbound-dispatch-queue overflow drop-newest.
     */
    public enum DropReason {
        OVERSIZE("oversize"), QUEUE_FULL("queue_full");

        private final String label;

        DropReason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** {@code adapter.message.bytes} direction label domain (§6.12). */
    public enum Direction {
        INBOUND("inbound"), OUTBOUND("outbound");

        private final String label;

        Direction(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * Reserved production name of the SimpleX adapter — gates the
     * SimpleX-only {@code adapter.simplex.auth.fail} registration in
     * {@link #bindAdapter}. Mirrors {@code SimpleXAdapter.name()}.
     */
    static final String SIMPLEX_NAME = "simplex";

    private final MeterRegistry registry;

    public AdapterMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * An instance backed by a throwaway in-memory registry. This is the
     * initializer value for the {@code @Inject AdapterMetrics} fields on
     * the Provider-side emission points, so plain-constructed unit tests
     * (which assign fields directly and predate metrics) keep working
     * unmodified — their emissions land in the throwaway registry. CDI
     * field injection replaces the value with the produced
     * deployment-wide bean.
     */
    public static AdapterMetrics noop() {
        return new AdapterMetrics(new SimpleMeterRegistry());
    }

    /**
     * Register the per-adapter gauges and the eagerly-registered
     * counters for one activated adapter, then hand this emission point
     * to the adapter for its transport-internal signals
     * ({@link MessagingAdapter#bindMetrics}). Called once per adapter by
     * the registry's activation loop; gauge re-registration on an
     * idempotent re-activation resolves to the already-registered meter.
     *
     * <p>{@code adapter.outbound.queue.size} reads a constant 0: v1 has
     * no outbound queue anywhere — every send runs synchronously through
     * the Provider's retry chokepoint — so zero is the true depth, and
     * the gauge exists because the §6.12 catalogue commits to it.</p>
     */
    public void bindAdapter(MessagingAdapter adapter) {
        String name = adapter.name();
        registry.gauge("adapter.connection.status", Tags.of("adapter", name),
                adapter, a -> a.connected() ? 1.0 : 0.0);
        registry.gauge("adapter.inbound.queue.size", Tags.of("adapter", name),
                adapter, MessagingAdapter::inboundQueueDepth);
        registry.gauge("adapter.outbound.queue.size", Tags.of("adapter", name),
                adapter, a -> 0.0);
        registry.counter("adapter.identity.assert.fail", "adapter", name);
        if (SIMPLEX_NAME.equals(name)) {
            registry.counter("adapter.simplex.auth.fail", "adapter", name);
        }
        adapter.bindMetrics(this);
    }

    /** One inbound message dispatched to the Provider. */
    public void inbound(String adapter, ScopeRef scope) {
        registry.counter("adapter.inbound.total",
                        "adapter", adapter, "scope_kind", scopeKind(scope))
                .increment();
    }

    /**
     * One inbound message dropped at the adapter boundary before delivery
     * to Provider (§6.3.10 oversize shed, §6.3.7 queue overflow). {@code
     * scope} is the decoded scope where the drop site knows it (every
     * oversize drop); it is {@code null} for the queue-overflow drop, which
     * fires before the frame is decoded into a scope, and is recorded as
     * {@code scope_kind="unknown"}.
     */
    public void inboundDropped(String adapter, @Nullable ScopeRef scope, DropReason reason) {
        registry.counter("adapter.inbound.dropped",
                        "adapter", adapter,
                        "scope_kind", scope == null ? "unknown" : scopeKind(scope),
                        "reason", reason.label())
                .increment();
    }

    /** One outbound send attempt's outcome at the delivery chokepoint. */
    public void outbound(String adapter, ScopeRef scope, SendOutcome outcome) {
        registry.counter("adapter.outbound.total",
                        "adapter", adapter, "scope_kind", scopeKind(scope),
                        "outcome", outcome.label())
                .increment();
    }

    /** One in-place update attempt's outcome. */
    public void updateOutcome(String adapter, ScopeRef scope, UpdateOutcome outcome) {
        registry.counter("adapter.outbound.update.total",
                        "adapter", adapter, "scope_kind", scopeKind(scope),
                        "outcome", outcome.label())
                .increment();
    }

    /** One unrecoverable in-place edit, by failure reason. */
    public void updateFail(String adapter, UpdateFailReason reason) {
        registry.counter("adapter.outbound.update.fail",
                        "adapter", adapter, "reason", reason.label())
                .increment();
    }

    /** Time from a caller's update to the edit actually transmitted, after coalescing. */
    public void updateLag(String adapter, Duration lag) {
        registry.timer("adapter.outbound.update.lag", "adapter", adapter)
                .record(lag);
    }

    /** One typing-indicator toggle on an adapter that supports typing. */
    public void typingToggle(String adapter, ScopeRef scope, boolean value) {
        registry.counter("adapter.typing.toggle",
                        "adapter", adapter, "scope_kind", scopeKind(scope),
                        "value", value ? "on" : "off")
                .increment();
    }

    /**
     * One message's body size, by transport direction. The UTF-8 byte
     * length is walked without a {@code getBytes} copy — the inbound
     * call site sits ahead of the Provider's rate cap, so a hostile
     * flood must not buy a byte-array allocation per message. Used by the
     * outbound callers ({@code OutboundDelivery}, {@code StageProgressNotifier})
     * that hold only the body String; the inbound caller in
     * {@code InboundRouter} precomputes the length once and uses the
     * {@code int} overload below so the body is not walked twice.
     */
    public void messageBytes(String adapter, Direction direction, String body) {
        messageBytes(adapter, direction, Utf8.byteLength(body));
    }

    /**
     * One message's body size, by transport direction, from a UTF-8 byte
     * length the caller has already computed (single-sourced through
     * {@link Utf8}). Lets the inbound path record the same {@code int} it
     * tested against the size cap, so the metric value and the cap
     * decision cannot diverge.
     */
    public void messageBytes(String adapter, Direction direction, int utf8ByteLength) {
        registry.summary("adapter.message.bytes",
                        "adapter", adapter, "direction", direction.label())
                .record(utf8ByteLength);
    }

    /** §6.12 {@code scope_kind} label value for a scope. */
    private static String scopeKind(ScopeRef scope) {
        return switch (scope) {
            case ScopeRef.Dm ignored -> "dm";
            case ScopeRef.Group ignored -> "group";
        };
    }
}
