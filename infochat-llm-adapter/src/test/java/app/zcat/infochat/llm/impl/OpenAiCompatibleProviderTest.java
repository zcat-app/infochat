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
     * Authorization header of each request, in order ({@code null} entries
     * are represented as {@code ""} — CopyOnWriteArrayList rejects null).
     */
    private List<String> receivedAuthHeaders;
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
        receivedAuthHeaders = new CopyOnWriteArrayList<>();
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
        mockServer.createContext("/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            receivedBodies.add(body);
            receivedModels.add(JSON.readTree(body).path("model").asText());
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            receivedAuthHeaders.add(auth == null ? "" : auth);
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
    void sharedDefaultKeysAloneRouteEveryTaskToTheDefaultEndpoint() {
        // The D56 shape: ONLY infochat.llm.default.{base-url,api-key} plus
        // per-task models — no per-task base-url/api-key anywhere. Every
        // task must inherit the shared endpoint AND the shared key (sent as
        // Authorization: Bearer). This is the config a new ModelTask lands
        // in automatically, closing the M1-597 silent-fallback class.
        Map<String, String> values = new HashMap<>();
        values.put(LlmRouter.CONFIG_KEY_DEFAULT_BASE_URL, baseUrl);
        values.put(LlmRouter.CONFIG_KEY_DEFAULT_API_KEY, "shared-secret-key");
        for (ModelTask task : ModelTask.values()) {
            values.put("infochat.llm." + task.keySegment() + ".model", "model-" + task.keySegment());
        }
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(new StubConfig(values));

        for (ModelTask task : ModelTask.values()) {
            assertDoesNotThrow(() -> provider.generate(task, "sys", "usr"),
                "with only the shared default keys set, " + task + " must resolve and call");
        }
        assertEquals(ModelTask.values().length, receivedModels.size(),
            "every task's call must land on the shared default endpoint");
        for (String auth : receivedAuthHeaders) {
            assertEquals("Bearer shared-secret-key", auth,
                "every call must carry the inherited default api-key as Bearer");
        }
    }

    @Test
    void perTaskBaseUrlAndApiKeyBeatTheSharedDefault() {
        // Precedence: a per-task override must win over the shared default.
        // The default points at a connection-refused port — if resolution
        // wrongly preferred the default, the call itself would fail.
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(new StubConfig(Map.of(
            LlmRouter.CONFIG_KEY_DEFAULT_BASE_URL, "http://localhost:9",
            LlmRouter.CONFIG_KEY_DEFAULT_API_KEY, "default-key",
            "infochat.llm.chat.base-url", baseUrl,
            "infochat.llm.chat.api-key", "per-task-key",
            "infochat.llm.chat.model", "model-chat")));

        LlmResponse response = assertDoesNotThrow(
            () -> provider.generate(ModelTask.CHAT_AGENT, "sys", "usr"),
            "the per-task base-url must win over the shared default");
        assertEquals("ok", response.text(), "the call must land on the per-task endpoint");
        assertEquals(List.of("Bearer per-task-key"), receivedAuthHeaders,
            "the per-task api-key must win over the shared default key");
    }

    @Test
    void perTaskBaseUrlPinDoesNotInheritTheSharedApiKey() {
        // The coupled-axes rule (redteam 2026-07-11): the default credential
        // travels ONLY to the default endpoint. A task whose base-url is
        // pinned per-task — with no per-task api-key — must send NO
        // credential at all, not the deployment-wide key, which the pinned
        // endpoint was never minted for.
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(new StubConfig(Map.of(
            LlmRouter.CONFIG_KEY_DEFAULT_BASE_URL, "http://localhost:9",
            LlmRouter.CONFIG_KEY_DEFAULT_API_KEY, "default-key",
            "infochat.llm.chat.base-url", baseUrl,
            "infochat.llm.chat.model", "model-chat")));

        LlmResponse response = assertDoesNotThrow(
            () -> provider.generate(ModelTask.CHAT_AGENT, "sys", "usr"),
            "a pinned base-url without a per-task key must still call (keyless)");
        assertEquals("ok", response.text(), "the call must land on the pinned endpoint");
        assertEquals(List.of(""), receivedAuthHeaders,
            "NO Authorization header may accompany a per-task base-url pin without "
                + "a per-task api-key — the shared default key must not follow the pin");
    }

    @Test
    void missingBaseUrlOnBothAxesFailsStartupScanNamingBothKeys() {
        // Neither infochat.llm.chat.base-url nor the shared default is set:
        // the startup scan must refuse with a message naming BOTH keys the
        // operator can set — the loud replacement for the M1-597 shape
        // where a task with no usable route booted clean and failed 100%
        // of calls silently.
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(new StubConfig(Map.of(
            "infochat.llm.chat.model", "model-chat")));

        LlmProvider.TaskConfigUnresolvableException ex = assertThrows(
            LlmProvider.TaskConfigUnresolvableException.class,
            () -> provider.assertTaskConfigResolvable(ModelTask.CHAT_AGENT),
            "a task with no effective base-url must fail the startup scan");
        assertTrue(ex.getMessage().contains("infochat.llm.chat.base-url"),
            "the failure must name the per-task key; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(LlmRouter.CONFIG_KEY_DEFAULT_BASE_URL),
            "the failure must name the shared default key; got: " + ex.getMessage());
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
