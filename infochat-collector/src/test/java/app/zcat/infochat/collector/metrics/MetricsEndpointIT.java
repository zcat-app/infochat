package app.zcat.infochat.collector.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * Metrics export IT per {@code docs/spec/deployment.md} §Health and
 * observability and {@code docs/spec/llm.md} §Bounded concurrency and
 * observability. Found broken live (F-live-7): the collector shipped
 * {@code quarkus-micrometer} (eval counters and llm_* meters register)
 * but no registry exporter extension, so {@code /q/metrics} was a 404
 * and CI stayed green because nothing asserted it existed. The llm_*
 * meters are lazily registered per call, so this IT pins the endpoint
 * and the exposition format, not a specific meter.
 */
@QuarkusTest
class MetricsEndpointIT {

    @Test
    void metricsEndpointServesNonEmptyPrometheusText() throws Exception {
        // quarkus.http.test-port=0 (test application.properties) binds an
        // ephemeral port; Quarkus writes the resolved port back into this
        // config key after binding, so this read returns the actual port.
        // The shipped defaults enable no separate management interface, so
        // /q/metrics rides the main HTTP port — same interface
        // CollectorReadinessIT asserts against.
        int port = ConfigProvider.getConfig()
                .getValue("quarkus.http.test-port", Integer.class);
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/q/metrics"))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode(),
                    "/q/metrics must be served once the prometheus registry"
                            + " extension is on the classpath (F-live-7: it was a"
                            + " 404 with quarkus-micrometer alone); body: "
                            + response.body());
            assertFalse(response.body().isBlank(),
                    "the metrics payload must be non-empty — the registry"
                            + " always carries at least the Quarkus-bound JVM"
                            + " and HTTP meters");
            assertTrue(response.body().contains("# TYPE "),
                    "the body must be Prometheus exposition text (# TYPE"
                            + " metadata lines), not an error page; body: "
                            + response.body());
        }
    }
}
