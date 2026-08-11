package app.zcat.infochat.provider.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.zcat.infochat.provider.help.CommandIntentIndex;
import app.zcat.infochat.provider.help.HelpTopicCorpus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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

    @Test
    void readinessAggregateCarriesExactlyTheMessagingAdaptersDatasourceAndHelpCorporaChecks() throws Exception {
        // The unauthenticated /q/health/ready response aggregates EVERY
        // registered readiness check, not just the messaging-adapters one
        // whose data map ReadinessPayloadShapeTest pins. Pinning the exact
        // check-name set here makes aggregate-level widening loud: any
        // dependency that auto-contributes a readiness check (the way
        // quarkus-jdbc-postgresql contributes the Agroal datasource check)
        // grows an unauthenticated, network-reachable payload and must be
        // a deliberate, reviewed change (docs/design/07-deployment.md
        // §7.12.1). The help-corpora check is that deliberate widening — the
        // M1-818 boot-time corpus-build-outcome surface, authorized by the
        // ticket's acceptance item 5 per engineering-rules §8.
        int port = ConfigProvider.getConfig()
                .getValue("quarkus.http.test-port", Integer.class);
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/q/health/ready"))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonObject root = Json.createReader(new StringReader(response.body())).readObject();
            Set<String> checkNames = root.getJsonArray("checks").stream()
                    .map(JsonValue::asJsonObject)
                    .map(check -> check.getString("name"))
                    .collect(Collectors.toSet());
            assertEquals(
                    Set.of("messaging-adapters", "Database connections health check", "help-corpora"),
                    checkNames,
                    "the readiness aggregate must carry exactly the adapter check,"
                            + " the datasource check, and the help-corpora check —"
                            + " any other check widens the unauthenticated payload;"
                            + " body: " + response.body());
        }
    }

    /** HTTP-boundary proof: after a %test boot the ready payload carries both corpora
     * built (StubEmbeddingProvider, priorities 150/151); pinning at the endpoint also
     * guards the boot-ordering rule (design 01-architecture.md §1.4.3). */
    @Test
    void readyPayloadCarriesHelpCorpusBuildOutcomesAfterBoot() throws Exception {
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
                    "readiness must be UP after a boot whose corpus builds"
                            + " succeeded; body: " + response.body());
            JsonObject root = Json.createReader(new StringReader(response.body())).readObject();
            JsonObject helpCorpora = root.getJsonArray("checks").stream()
                    .map(JsonValue::asJsonObject)
                    .filter(check -> "help-corpora".equals(check.getString("name")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "the help-corpora check must be part of the ready"
                                    + " payload; body: " + response.body()));
            assertEquals("UP", helpCorpora.getString("status"),
                    "the help-corpora check stays UP — the corpus-build outcome"
                            + " is informational; body: " + response.body());
            JsonObject data = helpCorpora.getJsonObject("data");
            assertTrue(data.getBoolean(CommandIntentIndex.DOC_KIND),
                    "the %test boot built the command-intent corpus via"
                            + " StubEmbeddingProvider; body: " + response.body());
            assertTrue(data.getBoolean(HelpTopicCorpus.DOC_KIND),
                    "the %test boot built the topic corpus via"
                            + " StubEmbeddingProvider; body: " + response.body());
        }
    }

    @Test
    void baseConfigBindsHttpListenerToLoopback() throws Exception {
        // Same raw main-resources read as the password-shape test above:
        // the shipped bind default is the thing under test, and the test
        // classpath's own application.properties would shadow it.
        List<String> lines = Files.readAllLines(
                Path.of("src/main/resources/application.properties"));

        List<String> httpHostLines = lines.stream()
                .filter(line -> !line.startsWith("#"))
                .filter(line -> line.contains("quarkus.http.host="))
                .toList();

        assertEquals(List.of("quarkus.http.host=127.0.0.1"), httpHostLines,
                "the shipped default must bind the HTTP listener (health"
                        + " probes) to loopback, with no profile override widening"
                        + " it — widening is an explicit operator action");
    }
}
