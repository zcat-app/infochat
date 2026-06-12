package app.zcat.infochat.provider.health;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.zcat.infochat.messaging.MessagingAdapter;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

/**
 * Pins the shipped shape of the unauthenticated readiness payload
 * (docs/design/07-deployment.md §7.12.1): the {@code messaging-adapters}
 * check's data map carries exactly one boolean per activated adapter
 * name, plus a {@code <name>.dropped-inbound} count when (and only when)
 * that adapter has dropped inbound messages — nothing else. The adapter
 * names stay in the payload deliberately (per-adapter up/down is the v1
 * status surface; the exposure lever is binding the health port to
 * loopback), so any key added here widens an unauthenticated,
 * network-reachable surface and must be a deliberate, reviewed change.
 */
class ReadinessPayloadShapeTest {

    @Test
    void payloadCarriesExactlyAdapterNamesAndDropCounters() {
        AdapterConnectionState state = new AdapterConnectionState();
        state.reportStarted("simplex");
        state.reportStarted("signal");
        Map<String, MessagingAdapter> adapters = Map.of(
                "simplex", new FakeReadinessAdapter("simplex", /* terminallyFailed */ false, 0),
                "signal", new FakeReadinessAdapter("signal", /* terminallyFailed */ false, 7));

        HealthCheckResponse response =
                AdapterReadinessCheck.evaluate(state.snapshot(), adapters);

        assertEquals("messaging-adapters", response.getName(),
                "the readiness check name is part of the shipped payload shape");
        Map<String, Object> data = response.getData().orElseThrow();
        assertEquals(
                Set.of("simplex", "signal",
                        "signal" + AdapterReadinessCheck.DROPPED_INBOUND_SUFFIX),
                data.keySet(),
                "the data map must carry exactly the adapter names plus the"
                        + " drop counter of the adapter that dropped — no other keys");
        assertEquals(Boolean.TRUE, data.get("simplex"),
                "each adapter name maps to its effective up/down boolean");
        assertEquals(Boolean.TRUE, data.get("signal"),
                "each adapter name maps to its effective up/down boolean");
        assertEquals(7L, data.get("signal" + AdapterReadinessCheck.DROPPED_INBOUND_SUFFIX),
                "the drop counter datum carries the cumulative dropped-inbound count");
    }
}
