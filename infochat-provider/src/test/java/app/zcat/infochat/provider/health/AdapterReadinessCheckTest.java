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
 * (no live supervisor to consult); the terminal-failure and drop-counter
 * legs pass a {@link FakeReadinessAdapter}.
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
