package app.zcat.infochat.provider.health;

import app.zcat.infochat.messaging.MessagingAdapter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * Provider readiness rule per {@code docs/spec/deployment.md}
 * §Bootstrap behavior on startup: ready when <b>at least one</b>
 * enabled adapter is connected (the Provider can serve traffic via
 * that adapter); not-ready when zero adapters are connected.
 *
 * <p>Three signals are folded per adapter. The startup outcome comes from
 * {@link AdapterConnectionState} (did the adapter's transport
 * {@code start()} return?). The <em>live</em> outcomes come from the
 * adapter itself: {@link MessagingAdapter#supervisorTerminallyFailed()}
 * reports a subprocess supervisor that exhausted its restart cap
 * <em>after</em> a clean start, and {@link MessagingAdapter#connected()}
 * reports the transport channel itself — a channel can die (peer close,
 * severed socket) while the supervisor still counts its child as running,
 * which is precisely the outage the recovery machinery exists to bridge
 * (M1-681). An adapter counts as connected only when it started, has not
 * since terminally failed, and its transport reports connected —
 * otherwise a deployment could read "ready" with a permanently dead (or
 * currently dead-and-recovering) adapter, since the startup snapshot
 * alone never observes the later failure.</p>
 *
 * <p>The per-adapter {@code data} entries carry the operational detail:
 * each adapter name maps to its effective up/down boolean, and an
 * adapter that has dropped inbound messages on queue overflow contributes
 * a {@code <name>.dropped-inbound} count
 * ({@link MessagingAdapter#droppedInboundCount()}) so a silently
 * overflowing queue is visible on the readiness payload without log
 * scraping. The Micrometer per-adapter metrics lift named in the spec's
 * readiness rule ("Per-adapter connection state is exposed separately via
 * metrics") and the degraded-LLM-probe leg remain deferred to the
 * observability backlog; until then these data entries are the status
 * surface.</p>
 */
@Readiness
@ApplicationScoped
public class AdapterReadinessCheck implements HealthCheck {

    static final String DROPPED_INBOUND_SUFFIX = ".dropped-inbound";

    @Inject
    AdapterConnectionState connectionState;

    @Inject
    @Any
    Instance<MessagingAdapter> discoveredAdapters;

    @Override
    public HealthCheckResponse call() {
        return evaluate(connectionState.snapshot(), adaptersByName());
    }

    private Map<String, MessagingAdapter> adaptersByName() {
        Map<String, MessagingAdapter> byName = new HashMap<>();
        for (MessagingAdapter adapter : discoveredAdapters) {
            byName.put(adapter.name(), adapter);
        }
        return byName;
    }

    /**
     * Pure readiness evaluation, factored out so it is unit-testable
     * without a CDI container: {@code started} is the per-adapter startup
     * snapshot, {@code adaptersByName} the live adapter instances keyed by
     * {@link MessagingAdapter#name()}. An adapter present in the snapshot
     * but absent from {@code adaptersByName} keeps its snapshot value (it
     * has no live supervisor to consult).
     */
    static HealthCheckResponse evaluate(Map<String, Boolean> started,
                                        Map<String, MessagingAdapter> adaptersByName) {
        HealthCheckResponseBuilder response = HealthCheckResponse.named("messaging-adapters");
        boolean anyUp = false;
        for (Map.Entry<String, Boolean> entry : started.entrySet()) {
            String name = entry.getKey();
            MessagingAdapter adapter = adaptersByName.get(name);
            boolean terminallyFailed = adapter != null && adapter.supervisorTerminallyFailed();
            // Transport truth is only consultable on a live instance; an
            // absent adapter keeps its snapshot value (the documented
            // absent-adapter contract above).
            boolean transportConnected = adapter == null || adapter.connected();
            boolean up = entry.getValue() && !terminallyFailed && transportConnected;
            anyUp = anyUp || up;
            response.withData(name, up);
            if (adapter != null) {
                long dropped = adapter.droppedInboundCount();
                if (dropped > 0) {
                    response.withData(name + DROPPED_INBOUND_SUFFIX, dropped);
                }
            }
        }
        return response.status(anyUp).build();
    }
}
