package app.zcat.infochat.collector.startup;

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
 * leg of the no-dev-password-fallback hardening. The Collector uses
 * the SmallRye Health extension defaults: the automatic Agroal
 * datasource check (both datasources) on top of the boot itself
 * (Flyway and the @Startup chain must have completed for the test to
 * run at all).
 */
@QuarkusTest
class CollectorReadinessIT {

    @Test
    void readinessEndpointReportsUpOnceBootstrapCompletes() throws Exception {
        int port = ConfigProvider.getConfig()
                .getOptionalValue("quarkus.http.test-port", Integer.class)
                .orElse(8081);
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
            assertTrue(response.body().contains("Database connections health check"),
                    "readiness must reflect live datasource state, not a static"
                            + " 200; body: " + response.body());
        }
    }

    @Test
    void baseProfileDatasourcePasswordsResolveOnlyFromEnvironment() throws Exception {
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

        assertEquals(
                List.of(
                        "quarkus.datasource.password=${INFOCHAT_COLLECTOR_PASSWORD}",
                        "quarkus.datasource.owner.password=${INFOCHAT_DB_PASSWORD}"),
                baseProfilePasswordKeys,
                "every base-profile password key must resolve only from the"
                        + " environment — no ${VAR:fallback} default, no literal");
    }
}
