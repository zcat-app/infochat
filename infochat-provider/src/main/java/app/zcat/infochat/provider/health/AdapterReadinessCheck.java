package app.zcat.infochat.provider.health;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
 * <p>Deliberately deferred to the verification/observability backlog
 * (no ticket filed yet): the second half of the spec's readiness rule
 * — "Per-adapter connection state is exposed separately via metrics"
 * — is a separate micrometer lift, and the LLM probe ("a failing
 * provider surfaces as a degraded readiness signal but does not fail
 * readiness outright") is deferred with it. Until then, the
 * per-adapter data entries on this check's response are the only
 * signal distinguishing "fully healthy" from "degraded — one adapter
 * down".</p>
 */
@Readiness
@ApplicationScoped
public class AdapterReadinessCheck implements HealthCheck {

    @Inject
    AdapterConnectionState connectionState;

    @Override
    public HealthCheckResponse call() {
        Map<String, Boolean> snapshot = connectionState.snapshot();
        HealthCheckResponseBuilder response = HealthCheckResponse
                .named("messaging-adapters")
                .status(snapshot.containsValue(true));
        snapshot.forEach(response::withData);
        return response.build();
    }
}
