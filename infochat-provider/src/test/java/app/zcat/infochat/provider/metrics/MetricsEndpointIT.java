package app.zcat.infochat.provider.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * observability: per-adapter connection state "is exposed separately
 * via metrics", which requires {@code /q/metrics} to actually serve
 * the registry. Found broken live (F-live-7): every module shipped
 * {@code quarkus-micrometer} (meters registered) but no registry
 * exporter extension, so the endpoint was a 404 and CI stayed green
 * because nothing asserted it existed. This IT pins the endpoint so
 * the exporter dependency cannot silently drop out again.
 */
@QuarkusTest
class MetricsEndpointIT {

    @Test
    void metricsEndpointServesPrometheusTextWithAdapterConnectionStatus() throws Exception {
        // quarkus.http.test-port=0 (test application.properties) binds an
        // ephemeral port; Quarkus writes the resolved port back into this
        // config key after binding, so this read returns the actual port.
        // The shipped defaults enable no separate management interface, so
        // /q/metrics rides the main HTTP port — same interface the
        // readiness ITs assert against.
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
            assertTrue(response.body().contains("# TYPE "),
                    "the body must be Prometheus exposition text (# TYPE"
                            + " metadata lines), not an error page; body: "
                            + response.body());
            // adapter.connection.status is registered by
            // AdapterMetrics.bindAdapter during %test adapter activation
            // (the inmemory adapter); Prometheus rendering dot-to-underscores
            // the name. The §7.14 runbook's first diagnostic reads this gauge.
            assertTrue(response.body().contains("adapter_connection_status"),
                    "the per-adapter connection gauge must be exported —"
                            + " spec deployment.md §Health and observability"
                            + " promises it as the healthy-vs-degraded signal;"
                            + " body: " + response.body());
        }
    }
}
