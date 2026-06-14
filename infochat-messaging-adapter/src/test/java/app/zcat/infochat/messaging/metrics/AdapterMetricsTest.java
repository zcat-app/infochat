package app.zcat.infochat.messaging.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import app.zcat.infochat.messaging.AdapterTrustLevel;
import app.zcat.infochat.messaging.CapabilityFlags;
import app.zcat.infochat.messaging.MessageHandle;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.OutboundMessage;
import app.zcat.infochat.messaging.ScopeRef;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * Pins the §6.12 adapter observability catalogue
 * (docs/design/06-messaging.md) at the {@link AdapterMetrics} surface:
 * every metric name, label key, and label-value domain the design
 * commits to, plus the per-adapter registration contract of
 * {@link AdapterMetrics#bindAdapter} — gauges sampling the live adapter,
 * the eagerly-registered counters, and the {@code bindMetrics} hand-off.
 */
class AdapterMetricsTest {

    private static final ScopeRef DM = new ScopeRef.Dm("contact-1");
    private static final ScopeRef GROUP = new ScopeRef.Group("group-1");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AdapterMetrics metrics = new AdapterMetrics(registry);

    @Test
    void connectionStatusGaugeTracksAdapterTransitions() {
        StubAdapter adapter = new StubAdapter("stub");
        metrics.bindAdapter(adapter);

        assertEquals(1.0, connectionStatus("stub"),
                "a connected adapter must read 1");
        adapter.connected = false;
        assertEquals(0.0, connectionStatus("stub"),
                "a disconnected adapter must transition the gauge to 0");
        adapter.connected = true;
        assertEquals(1.0, connectionStatus("stub"),
                "a reconnected adapter must transition the gauge back to 1");
    }

    private double connectionStatus(String adapter) {
        return registry.get("adapter.connection.status")
                .tags("adapter", adapter).gauge().value();
    }

    @Test
    void bindAdapterRegistersQueueGaugesAndEagerCounters() {
        StubAdapter adapter = new StubAdapter("stub");
        adapter.inboundQueueDepth = 7;
        metrics.bindAdapter(adapter);

        assertEquals(7.0, registry.get("adapter.inbound.queue.size")
                        .tags("adapter", "stub").gauge().value(),
                "inbound queue gauge must sample the adapter's live depth");
        assertEquals(0.0, registry.get("adapter.outbound.queue.size")
                        .tags("adapter", "stub").gauge().value(),
                "outbound queue gauge reads the v1 constant 0 (no outbound queue exists)");
        assertEquals(0.0, registry.get("adapter.identity.assert.fail")
                        .tags("adapter", "stub").counter().count(),
                "identity.assert.fail must be eagerly registered at zero");
        assertSame(metrics, adapter.boundMetrics,
                "bindAdapter must hand the emission point to the adapter");
    }

    @Test
    void simplexAuthFailCounterIsRegisteredOnlyForTheSimplexAdapter() {
        metrics.bindAdapter(new StubAdapter("simplex"));
        metrics.bindAdapter(new StubAdapter("signal"));

        assertEquals(0.0, registry.get("adapter.simplex.auth.fail")
                        .tags("adapter", "simplex").counter().count(),
                "the SimpleX-specific counter must be eagerly registered for simplex");
        assertNull(registry.find("adapter.simplex.auth.fail")
                        .tags("adapter", "signal").counter(),
                "no simplex.auth.fail series may exist for other adapters");
    }

    @Test
    void inboundCounterCarriesScopeKindLabel() {
        metrics.inbound("stub", DM);
        metrics.inbound("stub", GROUP);
        metrics.inbound("stub", GROUP);

        assertEquals(1.0, registry.get("adapter.inbound.total")
                .tags("adapter", "stub", "scope_kind", "dm").counter().count());
        assertEquals(2.0, registry.get("adapter.inbound.total")
                .tags("adapter", "stub", "scope_kind", "group").counter().count());
    }

    @Test
    void inboundDroppedCounterCarriesScopeKindAndReason() {
        metrics.inboundDropped("stub", DM, AdapterMetrics.DropReason.OVERSIZE);
        metrics.inboundDropped("stub", GROUP, AdapterMetrics.DropReason.OVERSIZE);
        // A queue-overflow drop fires before the frame is decoded into a
        // scope: a null scope is recorded as scope_kind="unknown".
        metrics.inboundDropped("stub", null, AdapterMetrics.DropReason.QUEUE_FULL);

        assertEquals(1.0, registry.get("adapter.inbound.dropped")
                        .tags("adapter", "stub", "scope_kind", "dm", "reason", "oversize")
                        .counter().count(),
                "a DM oversize drop must carry scope_kind=dm, reason=oversize");
        assertEquals(1.0, registry.get("adapter.inbound.dropped")
                        .tags("adapter", "stub", "scope_kind", "group", "reason", "oversize")
                        .counter().count(),
                "a group oversize drop must carry scope_kind=group, reason=oversize");
        assertEquals(1.0, registry.get("adapter.inbound.dropped")
                        .tags("adapter", "stub", "scope_kind", "unknown", "reason", "queue_full")
                        .counter().count(),
                "a queue-overflow drop with no decoded scope must record scope_kind=unknown");
    }

    @Test
    void outboundCounterCoversTheOutcomeDomain() {
        for (AdapterMetrics.SendOutcome outcome : AdapterMetrics.SendOutcome.values()) {
            metrics.outbound("stub", DM, outcome);
        }
        for (String label : new String[] {"ok", "retry", "fail"}) {
            assertEquals(1.0, registry.get("adapter.outbound.total")
                            .tags("adapter", "stub", "scope_kind", "dm", "outcome", label)
                            .counter().count(),
                    "outcome=" + label);
        }
    }

    @Test
    void updateOutcomeCounterCoversTheOutcomeDomain() {
        for (AdapterMetrics.UpdateOutcome outcome : AdapterMetrics.UpdateOutcome.values()) {
            metrics.updateOutcome("stub", GROUP, outcome);
        }
        for (String label : new String[] {"ok", "coalesced", "fail", "fallback_send"}) {
            assertEquals(1.0, registry.get("adapter.outbound.update.total")
                            .tags("adapter", "stub", "scope_kind", "group", "outcome", label)
                            .counter().count(),
                    "outcome=" + label);
        }
    }

    @Test
    void updateFailCounterCoversTheReasonDomain() {
        for (AdapterMetrics.UpdateFailReason reason : AdapterMetrics.UpdateFailReason.values()) {
            metrics.updateFail("stub", reason);
        }
        for (String label : new String[] {
                "item_too_old", "item_deleted", "not_owner", "transport", "unknown"}) {
            assertEquals(1.0, registry.get("adapter.outbound.update.fail")
                            .tags("adapter", "stub", "reason", label).counter().count(),
                    "reason=" + label);
        }
    }

    @Test
    void updateLagRecordsIntoThePerAdapterTimer() {
        metrics.updateLag("stub", Duration.ofMillis(42));

        assertEquals(1, registry.get("adapter.outbound.update.lag")
                .tags("adapter", "stub").timer().count());
    }

    @Test
    void typingToggleCounterCarriesValueLabel() {
        metrics.typingToggle("stub", DM, true);
        metrics.typingToggle("stub", DM, false);
        metrics.typingToggle("stub", DM, false);

        assertEquals(1.0, registry.get("adapter.typing.toggle")
                .tags("adapter", "stub", "scope_kind", "dm", "value", "on")
                .counter().count());
        assertEquals(2.0, registry.get("adapter.typing.toggle")
                .tags("adapter", "stub", "scope_kind", "dm", "value", "off")
                .counter().count());
    }

    @Test
    void messageBytesRecordsUtf8ByteLengthByDirection() {
        // "héllo→" in UTF-8: h(1) é(2) l(1) l(1) o(1) →(3) = 9 bytes,
        // vs. 6 chars — the multi-byte input catches a length() shortcut.
        metrics.messageBytes("stub", AdapterMetrics.Direction.INBOUND, "héllo→");
        metrics.messageBytes("stub", AdapterMetrics.Direction.OUTBOUND, "ok");

        assertEquals(9.0, registry.get("adapter.message.bytes")
                        .tags("adapter", "stub", "direction", "inbound")
                        .summary().totalAmount(),
                "inbound bytes must be the exact UTF-8 length, not the char count");
        assertEquals(2.0, registry.get("adapter.message.bytes")
                .tags("adapter", "stub", "direction", "outbound")
                .summary().totalAmount());
    }

    /**
     * Minimal adapter double for the registration contract: mutable
     * {@code connected} / {@code inboundQueueDepth} so the gauge tests
     * can drive transitions, and a captured {@code bindMetrics} argument.
     * Transport methods are unreachable from {@link AdapterMetrics}.
     */
    private static final class StubAdapter implements MessagingAdapter {
        private final String name;
        volatile boolean connected = true;
        volatile int inboundQueueDepth;
        volatile AdapterMetrics boundMetrics;

        StubAdapter(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean connected() {
            return connected;
        }

        @Override
        public int inboundQueueDepth() {
            return inboundQueueDepth;
        }

        @Override
        public void bindMetrics(AdapterMetrics metrics) {
            this.boundMetrics = metrics;
        }

        @Override
        public CapabilityFlags capabilities() {
            throw new UnsupportedOperationException();
        }

        @Override
        public AdapterTrustLevel trustLevel() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isWellFormedContactId(String contactId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MessageHandle send(OutboundMessage msg) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void update(MessageHandle handle, String body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void finalizeMessage(MessageHandle handle, String body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setTyping(ScopeRef scope, boolean typing) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setInboundHandler(InboundHandler handler) {
            throw new UnsupportedOperationException();
        }
    }
}
