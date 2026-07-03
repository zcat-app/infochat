package app.zcat.infochat.llm.impl;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    /** Raw request bodies the mock server received, in order. */
    private List<String> receivedBodies;
    /**
     * Canned reply the mock server returns for every call. Defaults to a
     * minimal text-only reply (no root {@code model}, no {@code usage} block);
     * a test that needs those fields overwrites it before calling generate().
     */
    private volatile String responseBody;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        baseUrl = "http://localhost:" + mockServer.getAddress().getPort();
        receivedModels = new CopyOnWriteArrayList<>();
        receivedBodies = new CopyOnWriteArrayList<>();
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
        mockServer.createContext("/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            receivedBodies.add(body);
            receivedModels.add(JSON.readTree(body).path("model").asText());
            byte[] resp = responseBody.getBytes(StandardCharsets.UTF_8);
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

        assertThrows(LlmProvider.TaskConfigUnresolvableException.class, router::assertAllTasksResolve,
            "the startup scan must surface a missing per-task model key as the "
                + "SPI-owned type, not the config system's NoSuchElementException");
    }

    @Test
    void usageAndModelFieldsParseFromResponse() {
        // A canned reply WITH the optional root `model` and `usage` block —
        // both absent from the default reply, so this is the only test that
        // pins parseChoiceText's usage/model parse (M1-498, 26#F1). Without it
        // a regression that dropped response.usage()/model() would ship green.
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
            + "\"model\":\"served-model-7\","
            + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}";
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(new StubConfig(Map.of(
            "infochat.llm.tagger.base-url", baseUrl,
            "infochat.llm.tagger.api-key", "",
            "infochat.llm.tagger.model", "model-tagger")));

        LlmResponse response = provider.generate(ModelTask.TAGGER, "sys", "usr");

        assertEquals("ok", response.text(), "the message content still round-trips");
        assertEquals("served-model-7", response.model(),
            "the root `model` field must populate response.model()");
        assertNotNull(response.usage(), "the usage block must populate response.usage()");
        assertEquals(11, response.usage().inputTokens(),
            "usage.prompt_tokens maps to TokenUsage.inputTokens");
        assertEquals(7, response.usage().outputTokens(),
            "usage.completion_tokens maps to TokenUsage.outputTokens");
    }

    @Test
    void generateSendsConfiguredMaxTokensInRequestBody() throws Exception {
        // F-live-6: the request body must carry max_tokens so a local
        // llama.cpp/Ollama backend stops generating instead of running
        // until the client timeout cancels a finishable reply.
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(new StubConfig(Map.of(
            "infochat.llm.chat.base-url", baseUrl,
            "infochat.llm.chat.api-key", "",
            "infochat.llm.chat.model", "model-chat",
            "infochat.llm.chat.max-tokens", "600")));

        provider.generate(ModelTask.CHAT_AGENT, "sys", "usr");

        assertEquals(600, JSON.readTree(receivedBodies.get(0)).path("max_tokens").asInt(),
            "the configured per-task max-tokens must be sent as max_tokens");
    }

    @Test
    void absentMaxTokensDefaultsTo1024InRequestBody() throws Exception {
        // Defaulted, not uncapped: with the key unset the body still carries
        // a cap — an absent-means-uncapped default would re-create the
        // F-live-6 failure mode on every deployment that doesn't set the key.
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(new StubConfig(Map.of(
            "infochat.llm.chat.base-url", baseUrl,
            "infochat.llm.chat.api-key", "",
            "infochat.llm.chat.model", "model-chat")));

        provider.generate(ModelTask.CHAT_AGENT, "sys", "usr");

        assertEquals(1024, JSON.readTree(receivedBodies.get(0)).path("max_tokens").asInt(),
            "an absent max-tokens key must default to 1024 in the request body");
    }

    @Test
    void failsStartupScanOnNonPositiveMaxTokensNamingTheProperty() {
        // Sibling of AnthropicProviderTest's guard (M1-412 pattern): a
        // non-positive explicit max-tokens must fail the startup scan
        // naming the offending property, not reach the backend on the
        // first live call.
        String property = "infochat.llm.chat.max-tokens";
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(new StubConfig(Map.of(
            "infochat.llm.chat.base-url", baseUrl,
            "infochat.llm.chat.model", "model-chat",
            property, "0")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> provider.assertTaskConfigResolvable(ModelTask.CHAT_AGENT),
            "the startup scan must fail on a non-positive max-tokens");
        assertTrue(ex.getMessage().contains(property),
            "failure must name the offending property; got: " + ex.getMessage());
    }

    @Test
    void httpClientSeamCtorUsesSuppliedClient() {
        // Symmetric with AnthropicProvider's HttpClient-accepting seam ctor:
        // the supplied client must be the one the provider holds.
        HttpClient supplied = HttpClient.newHttpClient();
        OpenAiCompatibleProvider provider =
            new OpenAiCompatibleProvider(new StubConfig(Map.of()), supplied);

        assertSame(supplied, provider.httpClient(),
            "the package-private seam ctor must use the caller-supplied HttpClient");
    }

}
