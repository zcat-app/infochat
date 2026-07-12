package app.zcat.infochat.llm.impl;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit5 tests for {@link DeepSeekProvider}: the reasoning-toggle body
 * assembly, provider registration, and the parent seam's no-op contract. Uses
 * the same local {@code com.sun.net.httpserver.HttpServer} mock as
 * {@link OpenAiCompatibleProviderTest} (no Quarkus boot, no network) — the mock
 * captures the outbound request body so each test asserts exactly what the
 * provider assembled.
 */
class DeepSeekProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer mockServer;
    private String baseUrl;
    /** Raw request bodies the mock server received, in order. */
    private List<String> receivedBodies;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        baseUrl = "http://localhost:" + mockServer.getAddress().getPort();
        receivedBodies = new CopyOnWriteArrayList<>();
        mockServer.createContext("/chat/completions", exchange -> {
            receivedBodies.add(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
    void reasoningUnsetDisablesThinkingInTheAssembledBody() throws Exception {
        // The default for every task: no reasoning-effort key -> the body must
        // carry the confirmed off-switch "thinking":{"type":"disabled"} so
        // v4-flash's thinking-on default cannot spend the max_tokens budget on
        // reasoning and truncate a security-judge verdict (acceptance item 3).
        DeepSeekProvider provider = new DeepSeekProvider(new StubConfig(Map.of(
            "infochat.llm.security.base-url", baseUrl,
            "infochat.llm.security.api-key", "",
            "infochat.llm.security.model", "deepseek-v4-flash")));

        provider.generate(ModelTask.SECURITY_JUDGE, "sys", "usr");

        JsonNode body = JSON.readTree(receivedBodies.get(0));
        assertEquals("disabled", body.path("thinking").path("type").asText(),
            "reasoning-effort unset must disable thinking; got: " + receivedBodies.get(0));
        assertFalse(body.has("reasoning_effort"),
            "the OFF body must not carry reasoning_effort; got: " + receivedBodies.get(0));
    }

    @Test
    void reasoningDepthEnablesThinkingAtThatDepth() throws Exception {
        DeepSeekProvider provider = new DeepSeekProvider(new StubConfig(Map.of(
            "infochat.llm.security.base-url", baseUrl,
            "infochat.llm.security.api-key", "",
            "infochat.llm.security.model", "deepseek-v4-flash",
            "infochat.llm.security.reasoning-effort", "high")));

        provider.generate(ModelTask.SECURITY_JUDGE, "sys", "usr");

        JsonNode body = JSON.readTree(receivedBodies.get(0));
        assertEquals("high", body.path("reasoning_effort").asText(),
            "a configured depth must set reasoning_effort; got: " + receivedBodies.get(0));
        assertFalse("disabled".equals(body.path("thinking").path("type").asText()),
            "an enabled body must not disable thinking; got: " + receivedBodies.get(0));
    }

    @Test
    void requestStructureParityWithTheLiveApi() throws Exception {
        // Body shape verified against the live API 2026-07-12: {model,
        // max_tokens, messages:[{role,content}]} + thinking, POST to
        // <base>/chat/completions, response parsed as choices[0].message.content.
        DeepSeekProvider provider = new DeepSeekProvider(new StubConfig(Map.of(
            "infochat.llm.security.base-url", baseUrl,
            "infochat.llm.security.api-key", "",
            "infochat.llm.security.model", "deepseek-v4-flash",
            "infochat.llm.security.max-tokens", "500")));

        LlmResponse response = provider.generate(ModelTask.SECURITY_JUDGE, "sys", "usr");

        assertEquals("ok", response.text(),
            "the response must parse as choices[0].message.content");
        JsonNode body = JSON.readTree(receivedBodies.get(0));
        assertEquals("deepseek-v4-flash", body.path("model").asText(), "carries the model");
        assertEquals(500, body.path("max_tokens").asInt(), "carries max_tokens");
        assertEquals("system", body.path("messages").get(0).path("role").asText());
        assertEquals("sys", body.path("messages").get(0).path("content").asText());
        assertEquals("user", body.path("messages").get(1).path("role").asText());
        assertEquals("usr", body.path("messages").get(1).path("content").asText());
    }

    @Test
    void parentSeamIsNoOpForTheGenericProvider() throws Exception {
        // The parent gains ONLY a no-op seam: a bare OpenAiCompatibleProvider
        // must assemble NO thinking field, so the generic OpenAI/Ollama path is
        // byte-identical to its pre-seam behaviour (the out_of_scope guarantee).
        OpenAiCompatibleProvider generic = new OpenAiCompatibleProvider(new StubConfig(Map.of(
            "infochat.llm.security.base-url", baseUrl,
            "infochat.llm.security.api-key", "",
            "infochat.llm.security.model", "some-model")));

        generic.generate(ModelTask.SECURITY_JUDGE, "sys", "usr");

        JsonNode body = JSON.readTree(receivedBodies.get(0));
        assertFalse(body.has("thinking"),
            "the generic provider must send no thinking field; got: " + receivedBodies.get(0));
        assertFalse(body.has("reasoning_effort"),
            "the generic provider must send no reasoning_effort; got: " + receivedBodies.get(0));
    }

    @Test
    void unrecognizedReasoningEffortFailsStartupScanNamingTheProperty() {
        // An operator typo must fail the startup scan naming the property — the
        // same fail-loud posture the parent uses for a non-positive max-tokens —
        // never silently degrade or fail-open the first live judge call.
        String property = "infochat.llm.security.reasoning-effort";
        DeepSeekProvider provider = new DeepSeekProvider(new StubConfig(Map.of(
            "infochat.llm.security.base-url", baseUrl,
            "infochat.llm.security.model", "deepseek-v4-flash",
            property, "hihg")));

        LlmProvider.TaskConfigUnresolvableException ex = assertThrows(
            LlmProvider.TaskConfigUnresolvableException.class,
            () -> provider.assertTaskConfigResolvable(ModelTask.SECURITY_JUDGE),
            "an unrecognized reasoning-effort must fail the startup scan");
        assertTrue(ex.getMessage().contains(property),
            "the failure must name the offending property; got: " + ex.getMessage());
    }

    @Test
    void reasoningEnabledWithUnraisedMaxTokensFailsStartupScanNamingTaskAndBothProperties() {
        // M1-610 coupling guard: enabling reasoning while leaving max-tokens at
        // the parent default (1024, below the 4000 floor) must FAIL the startup
        // scan — reasoning tokens share the completion budget, so an unraised
        // max-tokens truncates the verdict and fail-opens the Stage 2 boundary.
        // Red-before/green-after: before the guard existed, assertTaskConfigResolvable
        // validated only the reasoning-effort VALUE and this config booted clean.
        String reasoningProperty = "infochat.llm.security.reasoning-effort";
        String maxTokensProperty = "infochat.llm.security.max-tokens";
        DeepSeekProvider provider = new DeepSeekProvider(new StubConfig(Map.of(
            "infochat.llm.security.base-url", baseUrl,
            "infochat.llm.security.model", "deepseek-v4-flash",
            reasoningProperty, "high")));  // max-tokens unset -> parent default 1024

        LlmProvider.TaskConfigUnresolvableException ex = assertThrows(
            LlmProvider.TaskConfigUnresolvableException.class,
            () -> provider.assertTaskConfigResolvable(ModelTask.SECURITY_JUDGE),
            "reasoning-on with an unraised max-tokens must fail the startup scan");
        assertTrue(ex.getMessage().contains("SECURITY_JUDGE"),
            "the failure must name the offending task; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(reasoningProperty),
            "the failure must name the reasoning-effort property; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(maxTokensProperty),
            "the failure must name the max-tokens property; got: " + ex.getMessage());
    }

    @Test
    void reasoningOffWithTinyMaxTokensPassesStartupScan() {
        // The guard fires ONLY when reasoning is enabled. Reasoning OFF (the
        // default) with a max-tokens far below the reasoning floor must still
        // boot clean — the disabled body spends 0 reasoning tokens, so there is
        // nothing to crowd out the verdict (the M1-608 default-OFF guarantee).
        DeepSeekProvider provider = new DeepSeekProvider(new StubConfig(Map.of(
            "infochat.llm.security.base-url", baseUrl,
            "infochat.llm.security.model", "deepseek-v4-flash",
            "infochat.llm.security.max-tokens", "100")));  // no reasoning-effort -> OFF

        assertDoesNotThrow(
            () -> provider.assertTaskConfigResolvable(ModelTask.SECURITY_JUDGE),
            "reasoning-off must be unaffected by the coupling floor");
    }

    @Test
    void reasoningEnabledWithRaisedMaxTokensPassesStartupScan() {
        // With reasoning enabled AND max-tokens at the floor, the coupling is
        // satisfied: reasoning has room to run without truncating the verdict.
        DeepSeekProvider provider = new DeepSeekProvider(new StubConfig(Map.of(
            "infochat.llm.security.base-url", baseUrl,
            "infochat.llm.security.model", "deepseek-v4-flash",
            "infochat.llm.security.reasoning-effort", "high",
            "infochat.llm.security.max-tokens",
            Integer.toString(DeepSeekProvider.REASONING_MIN_MAX_TOKENS))));

        assertDoesNotThrow(
            () -> provider.assertTaskConfigResolvable(ModelTask.SECURITY_JUDGE),
            "reasoning-on with max-tokens at the floor must boot clean");
    }

    @Test
    void providerDeepseekResolvesToDeepSeekProvider() {
        // Registration: a per-task provider=deepseek route resolves to
        // DeepSeekProvider through LlmRouter.forTask, exactly as
        // provider=openai-compatible resolves to OpenAiCompatibleProvider.
        DeepSeekProvider deepseek = new DeepSeekProvider(new StubConfig(Map.of()));
        OpenAiCompatibleProvider generic = new OpenAiCompatibleProvider(new StubConfig(Map.of()));
        LlmRouter router = new LlmRouter(
            List.of(
                new LlmRouter.Entry(DeepSeekProvider.PROVIDER_NAME, deepseek, Set.of("en")),
                new LlmRouter.Entry(OpenAiCompatibleProvider.PROVIDER_NAME, generic, Set.of("en"))),
            LlmRouter.ConfigReader.fromMap(Map.of(
                "infochat.llm.security.provider", DeepSeekProvider.PROVIDER_NAME)));

        assertSame(deepseek, router.forTask(ModelTask.SECURITY_JUDGE, "en"),
            "provider=deepseek must resolve to DeepSeekProvider");
    }

    @Test
    void providerNameIsDeepseek() {
        assertEquals("deepseek",
            new DeepSeekProvider(new StubConfig(Map.of())).providerName(),
            "the bean name the router registers must be 'deepseek'");
    }
}
