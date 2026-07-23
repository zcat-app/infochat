package app.zcat.infochat.provider.health;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.zcat.infochat.messaging.MessagingAdapter;
import java.util.Map;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the deployment-spec readiness rule ("ready when at
 * least one enabled adapter is connected; not-ready when zero adapters
 * are connected") against every reachable state, exercised through the
 * pure {@link AdapterReadinessCheck#evaluate} core so no CDI container is
 * needed. The endpoint-level UP path is exercised by
 * {@code ProviderReadinessEndpointIT}; the DOWN states cannot be produced
 * in a booted container (the startup gates require a working adapter), so
 * they live here. The startup snapshot leg passes an empty adapter map
 * (no live supervisor to consult); the terminal-failure, transport-death
 * and drop-counter legs pass a {@link FakeReadinessAdapter}.
 */
class AdapterReadinessCheckTest {

    @Test
    void readinessIsDownWithZeroConnectedAdapters() {
        AdapterConnectionState state = new AdapterConnectionState();

        HealthCheckResponse response =
                AdapterReadinessCheck.evaluate(state.snapshot(), Map.of());

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus(),
                "zero connected adapters must report not-ready");
    }

    @Test
    void readinessIsDownWhenEveryAdapterFailedToStart() {
        AdapterConnectionState state = new AdapterConnectionState();
        state.reportFailed("simplex");
        state.reportFailed("signal");

        HealthCheckResponse response =
                AdapterReadinessCheck.evaluate(state.snapshot(), Map.of());

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus(),
                "activated-but-unconnected adapters must not count toward readiness");
    }

    @Test
    void readinessIsUpWithOneOfTwoConnected() {
        AdapterConnectionState state = new AdapterConnectionState();
        state.reportFailed("simplex");
        state.reportStarted("signal");

        HealthCheckResponse response =
                AdapterReadinessCheck.evaluate(state.snapshot(), Map.of());

        assertEquals(HealthCheckResponse.Status.UP, response.getStatus(),
                "one connected adapter of two must report ready (at-least-one rule)");
    }

    @Test
    void resetDropsPriorOutcomes() {
        AdapterConnectionState state = new AdapterConnectionState();
        state.reportStarted("simplex");
        state.reset();

        HealthCheckResponse response =
                AdapterReadinessCheck.evaluate(state.snapshot(), Map.of());

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus(),
                "an idempotent adapter-start re-run must not inherit stale connected flags");
    }

    @Test
    void readinessIsDownWhenStartedAdapterSupervisorTerminallyFailed() {
        AdapterConnectionState state = new AdapterConnectionState();
        state.reportStarted("signal");
        // The adapter started cleanly at boot, then its subprocess supervisor
        // exhausted its restart cap — the M-P11 "ready with a dead adapter" gap.
        Map<String, MessagingAdapter> adapters = Map.of(
                "signal", new FakeReadinessAdapter("signal", /* terminallyFailed */ true, 0));

        HealthCheckResponse response =
                AdapterReadinessCheck.evaluate(state.snapshot(), adapters);

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus(),
                "a started-then-terminally-failed supervisor must flip readiness to not-ready");
        assertEquals(Boolean.FALSE, response.getData().orElseThrow().get("signal"),
                "the failed adapter's per-adapter datum must read down");
    }

    @Test
    void readinessIsDownWhenStartedAdapterTransportDisconnected() {
        AdapterConnectionState state = new AdapterConnectionState();
        state.reportStarted("signal");
        // The adapter started cleanly and its supervisor still counts the
        // child as running — but the transport channel itself is dead (peer
        // close, severed socket): the M1-674 audit finding 1 gap, where the
        // readiness payload stayed green for the whole outage because it
        // never consulted connected().
        Map<String, MessagingAdapter> adapters = Map.of(
                "signal", new FakeReadinessAdapter(
                        "signal", /* terminallyFailed */ false, /* connected */ false, 0));

        HealthCheckResponse response =
                AdapterReadinessCheck.evaluate(state.snapshot(), adapters);

        assertEquals(Boolean.FALSE, response.getData().orElseThrow().get("signal"),
                "a started adapter with a dead transport must read down in the payload");
        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus(),
                "overall status must be DOWN when the only adapter's transport is dead");
    }

    @Test
    void readinessIsUpWhenOneTransportDeadButAnotherConnected() {
        AdapterConnectionState state = new AdapterConnectionState();
        state.reportStarted("signal");
        state.reportStarted("simplex");
        Map<String, MessagingAdapter> adapters = Map.of(
                "signal", new FakeReadinessAdapter(
                        "signal", /* terminallyFailed */ false, /* connected */ false, 0),
                "simplex", new FakeReadinessAdapter(
                        "simplex", /* terminallyFailed */ false, /* connected */ true, 0));

        HealthCheckResponse response =
                AdapterReadinessCheck.evaluate(state.snapshot(), adapters);

        assertEquals(HealthCheckResponse.Status.UP, response.getStatus(),
                "the at-least-one rule must survive the transport fold: one live"
                        + " adapter keeps the Provider ready");
        assertEquals(Boolean.FALSE, response.getData().orElseThrow().get("signal"),
                "the dead-transport adapter still reads down individually");
        assertEquals(Boolean.TRUE, response.getData().orElseThrow().get("simplex"),
                "the connected adapter reads up");
    }

    @Test
    void readinessSurfacesDropCounterWhenInboundDropped() {
        AdapterConnectionState state = new AdapterConnectionState();
        state.reportStarted("signal");
        Map<String, MessagingAdapter> adapters = Map.of(
                "signal", new FakeReadinessAdapter("signal", /* terminallyFailed */ false, 7));

        HealthCheckResponse response =
                AdapterReadinessCheck.evaluate(state.snapshot(), adapters);

        assertEquals(HealthCheckResponse.Status.UP, response.getStatus(),
                "drops do not by themselves make an otherwise-connected adapter not-ready");
        assertEquals(7L,
                response.getData().orElseThrow().get("signal" + AdapterReadinessCheck.DROPPED_INBOUND_SUFFIX),
                "the inbound drop count must be surfaced on the readiness payload");
    }

    @Test
    void readinessOmitsDropCounterDatumWhenNoneDropped() {
        AdapterConnectionState state = new AdapterConnectionState();
        state.reportStarted("signal");
        Map<String, MessagingAdapter> adapters = Map.of(
                "signal", new FakeReadinessAdapter("signal", /* terminallyFailed */ false, 0));

        HealthCheckResponse response =
                AdapterReadinessCheck.evaluate(state.snapshot(), adapters);

        assertEquals(false,
                response.getData().orElseThrow()
                        .containsKey("signal" + AdapterReadinessCheck.DROPPED_INBOUND_SUFFIX),
                "no drop datum is emitted until at least one inbound message is dropped");
    }
}
