package app.zcat.infochat.llm.impl;

import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit5 tests for {@link OpenAiCompatibleProvider}'s per-task
 * config resolution. Uses the JDK's
 * {@code com.sun.net.httpserver.HttpServer} as a local mock to capture
 * outbound requests and return canned {@code /chat/completions} replies
 * — no Quarkus boot, no WireMock dependency (same shape as
 * {@link AnthropicProviderTest}).
 */
class OpenAiCompatibleProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer mockServer;
    private String baseUrl;
    /** Model field of each request body the mock server received, in order. */
    private List<String> receivedModels;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        baseUrl = "http://localhost:" + mockServer.getAddress().getPort();
        receivedModels = new CopyOnWriteArrayList<>();
        mockServer.createContext("/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            receivedModels.add(JSON.readTree(body).path("model").asText());
            byte[] resp = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        mockServer.start();
    }

    @AfterEach
    void tearDown() {
        mockServer.stop(0);
    }

    @Test
    void generateServesAllSixModelTasksWithoutUnsupportedOperation() {
        // One per-task property block per ModelTask, each declaring a
        // distinct model so the assertion proves the matching block (not
        // some shared fallback) drove each call.
        Map<String, String> values = new HashMap<>();
        for (ModelTask task : ModelTask.values()) {
            String prefix = "infochat.llm." + task.keySegment() + ".";
            values.put(prefix + "base-url", baseUrl);
            values.put(prefix + "api-key", "");
            values.put(prefix + "model", "model-" + task.keySegment());
            values.put(prefix + "timeout-ms", "5000");
        }
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(new StubConfig(values));

        for (ModelTask task : ModelTask.values()) {
            LlmResponse response = assertDoesNotThrow(
                () -> provider.generate(task, "sys", "usr"),
                "generate() must serve " + task + " without UnsupportedOperationException");
            assertEquals("ok", response.text(), "canned reply must round-trip for " + task);
        }
        for (ModelTask task : ModelTask.values()) {
            assertTrue(receivedModels.contains("model-" + task.keySegment()),
                "the " + task + " call must carry its own per-task model; got: " + receivedModels);
        }
    }

    @Test
    void taggerAndEntityConfigKeysDriveTheCallEndpoint() {
        // The exact key names shipped in collector application.properties
        // (infochat.llm.tagger.*, infochat.llm.entity.*) must route the
        // call to the configured endpoint with the configured model.
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(new StubConfig(Map.of(
            "infochat.llm.tagger.base-url", baseUrl,
            "infochat.llm.tagger.api-key", "",
            "infochat.llm.tagger.model", "llama3.1:8b",
            "infochat.llm.entity.base-url", baseUrl,
            "infochat.llm.entity.api-key", "",
            "infochat.llm.entity.model", "llama3.1:8b"
        )));

        provider.generate(ModelTask.TAGGER, "tag this", "post body");
        provider.generate(ModelTask.ENTITY, "extract entities", "post body");

        assertEquals(2, receivedModels.size(),
            "both calls must land on the endpoint the per-task base-url names");
        assertEquals(List.of("llama3.1:8b", "llama3.1:8b"), receivedModels,
            "both calls must carry the configured per-task model");
    }

    @Test
    void assertAllTasksResolveThrowsAtStartupWhenTaskModelKeyMissing() {
        // The typo scenario: every task block is complete except one
        // absent model key. Lazy per-call resolution would boot cleanly
        // and surface only at the first live call of the broken task —
        // where the workers' retry-then-fallback catch converts the
        // permanent misconfiguration into an indefinite silent fallback.
        // The eager startup scan must throw instead.
        Map<String, String> values = new HashMap<>();
        for (ModelTask task : ModelTask.values()) {
            String prefix = "infochat.llm." + task.keySegment() + ".";
            values.put(prefix + "base-url", baseUrl);
            values.put(prefix + "model", "model-" + task.keySegment());
        }
        values.remove("infochat.llm.security.model");
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(new StubConfig(values));
        LlmRouter router = new LlmRouter(
            List.of(new LlmRouter.Entry(
                OpenAiCompatibleProvider.PROVIDER_NAME, provider, Set.of("en"))),
            LlmRouter.ConfigReader.fromMap(Map.of()));

        assertThrows(NoSuchElementException.class, router::assertAllTasksResolve,
            "the startup scan must surface a missing per-task model key instead of booting");
    }

}
