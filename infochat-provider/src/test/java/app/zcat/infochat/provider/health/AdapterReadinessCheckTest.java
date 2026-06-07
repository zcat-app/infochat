package app.zcat.infochat.provider.health;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the deployment-spec readiness rule ("ready when at
 * least one enabled adapter is connected; not-ready when zero adapters
 * are connected") against every reachable state of
 * {@link AdapterConnectionState}. The endpoint-level UP path is
 * exercised by {@code ProviderReadinessEndpointIT}; the DOWN states
 * cannot be produced in a booted container (the startup gates require
 * a working adapter), so they live here.
 */
class AdapterReadinessCheckTest {

    private static AdapterReadinessCheck newCheck(AdapterConnectionState state) {
        AdapterReadinessCheck check = new AdapterReadinessCheck();
        check.connectionState = state;
        return check;
    }

    @Test
    void readinessIsDownWithZeroConnectedAdapters() {
        AdapterConnectionState state = new AdapterConnectionState();

        HealthCheckResponse response = newCheck(state).call();

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus(),
                "zero connected adapters must report not-ready");
    }

    @Test
    void readinessIsDownWhenEveryAdapterFailedToStart() {
        AdapterConnectionState state = new AdapterConnectionState();
        state.reportFailed("simplex");
        state.reportFailed("signal");

        HealthCheckResponse response = newCheck(state).call();

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus(),
                "activated-but-unconnected adapters must not count toward readiness");
    }

    @Test
    void readinessIsUpWithOneOfTwoConnected() {
        AdapterConnectionState state = new AdapterConnectionState();
        state.reportFailed("simplex");
        state.reportStarted("signal");

        HealthCheckResponse response = newCheck(state).call();

        assertEquals(HealthCheckResponse.Status.UP, response.getStatus(),
                "one connected adapter of two must report ready (at-least-one rule)");
    }

    @Test
    void resetDropsPriorOutcomes() {
        AdapterConnectionState state = new AdapterConnectionState();
        state.reportStarted("simplex");
        state.reset();

        HealthCheckResponse response = newCheck(state).call();

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus(),
                "an idempotent adapter-start re-run must not inherit stale connected flags");
    }
}
