package app.zcat.infochat.provider.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * Readiness endpoint IT per {@code docs/spec/deployment.md} §Health
 * and observability ("Readiness — service is fully bootstrapped:
 * Flyway done, all required startup beans up") plus the config-shape
 * leg of the no-dev-password-fallback hardening. Boots the standard
 * %test Provider (inmemory adapter), so the adapter readiness leg is
 * UP via the at-least-one-connected rule.
 */
@QuarkusTest
class ProviderReadinessEndpointIT {

    @Test
    void readinessEndpointReportsUpOnceBootstrapCompletes() throws Exception {
        // quarkus.http.test-port=0 (test application.properties) binds an
        // ephemeral port; Quarkus writes the resolved port back into this
        // config key after binding, so this read returns the actual port —
        // never 0, and never a hardcoded fallback that could mask a
        // cross-worktree collision.
        int port = ConfigProvider.getConfig()
                .getValue("quarkus.http.test-port", Integer.class);
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/q/health/ready"))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode(),
                    "readiness must be UP once the container booted (Flyway done,"
                            + " startup beans up); body: " + response.body());
            assertTrue(response.body().contains("\"UP\""),
                    "overall readiness status must be UP; body: " + response.body());
            assertTrue(response.body().contains("messaging-adapters"),
                    "the adapter readiness leg must be part of the readiness payload;"
                            + " body: " + response.body());
            assertTrue(response.body().contains("inmemory"),
                    "the connected %test adapter must appear as per-adapter data;"
                            + " body: " + response.body());
        }
    }

    @Test
    void baseProfileDatasourcePasswordResolvesOnlyFromEnvironment() throws Exception {
        // Config-shape assertion against the raw main-resources file (the
        // test classpath carries its own application.properties, so the
        // classloader view would not see the production-shaped keys).
        List<String> lines = Files.readAllLines(
                Path.of("src/main/resources/application.properties"));

        List<String> baseProfilePasswordKeys = lines.stream()
                .filter(line -> !line.startsWith("%") && !line.startsWith("#"))
                .filter(line -> line.contains("=")
                        && line.substring(0, line.indexOf('=')).endsWith(".password"))
                .toList();

        assertEquals(List.of("quarkus.datasource.password=${INFOCHAT_PROVIDER_PASSWORD}"),
                baseProfilePasswordKeys,
                "every base-profile password key must resolve only from the"
                        + " environment — no ${VAR:fallback} default, no literal");
    }
}
