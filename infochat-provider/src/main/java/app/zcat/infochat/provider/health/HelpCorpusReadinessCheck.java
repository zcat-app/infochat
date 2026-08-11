package app.zcat.infochat.provider.health;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/** Boot-time corpus-build outcome on readiness: one boolean per corpus,
 * never exception text; ALWAYS UP — an absent embedding backend is a supported
 * degraded mode, not a readiness failure (deployment.md §Bootstrap behavior on startup). */
@Readiness
@ApplicationScoped
public class HelpCorpusReadinessCheck implements HealthCheck {

    @Inject
    HelpCorpusBuildState buildState;

    @Override
    public HealthCheckResponse call() {
        return evaluate(buildState.snapshot());
    }

    /** Pure evaluation, factored for unit testing (the AdapterReadinessCheck.evaluate shape):
     * one boolean datum per reported corpus, UP regardless; an empty snapshot contributes
     * no data — readiness stays 503 until startup completes (design 01-architecture.md §1.4.3). */
    static HealthCheckResponse evaluate(Map<String, Boolean> builtByCorpus) {
        HealthCheckResponseBuilder response = HealthCheckResponse.named("help-corpora");
        for (Map.Entry<String, Boolean> entry : builtByCorpus.entrySet()) {
            response.withData(entry.getKey(), entry.getValue());
        }
        return response.up().build();
    }
}
