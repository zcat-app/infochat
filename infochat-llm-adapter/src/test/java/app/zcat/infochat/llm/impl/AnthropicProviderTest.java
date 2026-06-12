package app.zcat.infochat.llm.impl;

import app.zcat.infochat.llm.LlmProvider;
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
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit5 tests for {@link AnthropicProvider}. Uses JDK's
 * {@code com.sun.net.httpserver.HttpServer} as a local mock to
 * capture outbound requests and return canned responses — no
 * Quarkus boot, no WireMock dependency.
 */
class AnthropicProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MODEL = "claude-sonnet-4-20250514";
    private static final String API_KEY = "sk-test-key-12345";

    private HttpServer mockServer;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        baseUrl = "http://localhost:" + mockServer.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    @Test
    void generatePostsCorrectWireFormat() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        mockServer.createContext("/messages", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = successResponse("test reply").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        mockServer.start();

        AnthropicProvider provider = providerFor(ModelTask.SUMMARIZER, API_KEY);
        provider.generate(ModelTask.SUMMARIZER, "You are a summarizer.", "Summarize this.");

        JsonNode root = JSON.readTree(capturedBody.get());
        assertEquals(MODEL, root.get("model").asText());
        assertEquals(1024, root.get("max_tokens").asInt());

        // system is a top-level array, NOT a messages entry
        assertTrue(root.get("system").isArray(), "system must be a top-level array");
        JsonNode systemBlock = root.get("system").get(0);
        assertEquals("text", systemBlock.get("type").asText());
        assertEquals("You are a summarizer.", systemBlock.get("text").asText());

        // messages array with a single user-role entry
        assertTrue(root.get("messages").isArray());
        assertEquals(1, root.get("messages").size());
        JsonNode userMsg = root.get("messages").get(0);
        assertEquals("user", userMsg.get("role").asText());
        assertEquals("Summarize this.", userMsg.get("content").asText());
    }

    @Test
    void generateIncludesCacheControlOnSystemPrompt() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        mockServer.createContext("/messages", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = successResponse("ok").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        mockServer.start();

        AnthropicProvider provider = providerFor(ModelTask.SUMMARIZER, API_KEY);
        provider.generate(ModelTask.SUMMARIZER, "system prompt", "user prompt");

        JsonNode root = JSON.readTree(capturedBody.get());
        JsonNode cacheControl = root.get("system").get(0).get("cache_control");
        assertNotNull(cacheControl, "system block must carry cache_control");
        assertEquals("ephemeral", cacheControl.get("type").asText());
    }

    @Test
    void generateSendsAuthHeaders() throws Exception {
        AtomicReference<Map<String, List<String>>> capturedHeaders = new AtomicReference<>();
        mockServer.createContext("/messages", exchange -> {
            capturedHeaders.set(exchange.getRequestHeaders());
            byte[] resp = successResponse("ok").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        mockServer.start();

        AnthropicProvider provider = providerFor(ModelTask.SUMMARIZER, API_KEY);
        provider.generate(ModelTask.SUMMARIZER, "sys", "usr");

        Map<String, List<String>> headers = capturedHeaders.get();
        assertEquals("2023-06-01", headers.get("Anthropic-version").get(0));
        assertEquals(API_KEY, headers.get("X-api-key").get(0));
    }

    @Test
    void generateOmitsApiKeyHeaderWhenEmpty() throws Exception {
        AtomicReference<Map<String, List<String>>> capturedHeaders = new AtomicReference<>();
        mockServer.createContext("/messages", exchange -> {
            capturedHeaders.set(exchange.getRequestHeaders());
            byte[] resp = successResponse("ok").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        mockServer.start();

        AnthropicProvider provider = providerFor(ModelTask.SUMMARIZER, "");
        provider.generate(ModelTask.SUMMARIZER, "sys", "usr");

        Map<String, List<String>> headers = capturedHeaders.get();
        assertFalse(headers.containsKey("X-api-key"),
            "x-api-key header must be omitted when api-key config is empty");
        assertNotNull(headers.get("Anthropic-version"),
            "anthropic-version header must still be present");
    }

    @Test
    void generateParsesContentResponse() throws Exception {
        mockServer.createContext("/messages", exchange -> {
            byte[] resp = successResponse("The answer is 42.").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        mockServer.start();

        AnthropicProvider provider = providerFor(ModelTask.SUMMARIZER, API_KEY);
        LlmResponse response = provider.generate(ModelTask.SUMMARIZER, "sys", "usr");

        assertEquals("The answer is 42.", response.text());
    }

    @Test
    void generateThrowsOnNon2xx() throws Exception {
        String errorBody = """
            {"type":"error","error":{"type":"invalid_request_error","message":"max_tokens: must be positive"}}""";
        mockServer.createContext("/messages", exchange -> {
            byte[] resp = errorBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        mockServer.start();

        AnthropicProvider provider = providerFor(ModelTask.SUMMARIZER, API_KEY);

        LlmCallFailedException ex = assertThrows(LlmCallFailedException.class,
            () -> provider.generate(ModelTask.SUMMARIZER, "sys", "usr"));
        assertTrue(ex.getMessage().contains("400"), "exception must include status code");
        // U-13: the provider error body can echo request fragments or user
        // content, so it is no longer surfaced — only status and host.
        assertFalse(ex.getMessage().contains("max_tokens: must be positive"),
            "exception must NOT echo the provider error body; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("localhost"),
            "exception must name the host for triage; got: " + ex.getMessage());
    }

    @Test
    void generateThrowsOnIoError() {
        // Point at a port with no server listening
        mockServer.stop(0);
        AnthropicProvider provider = providerFor(ModelTask.SUMMARIZER, API_KEY);

        assertThrows(LlmCallFailedException.class,
            () -> provider.generate(ModelTask.SUMMARIZER, "sys", "usr"));
    }

    @Test
    void generateThrowsWhenResponseBodyExceedsCap() throws Exception {
        // providerFor pins the cap to 1 MiB (the clamp floor); a 2 MiB
        // reply must abort the bounded read rather than buffer it whole.
        byte[] huge = new byte[2 * 1024 * 1024];
        java.util.Arrays.fill(huge, (byte) 'x');
        mockServer.createContext("/messages", exchange -> {
            exchange.sendResponseHeaders(200, huge.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(huge);
            } catch (java.io.IOException ignored) {
                // Client cancels mid-stream once the cap is crossed; a
                // broken-pipe on the server write is expected, not a failure.
            }
        });
        mockServer.start();

        AnthropicProvider provider = providerFor(ModelTask.SUMMARIZER, API_KEY);

        LlmCallFailedException ex = assertThrows(LlmCallFailedException.class,
            () -> provider.generate(ModelTask.SUMMARIZER, "sys", "usr"));
        assertNotNull(ex.getCause(), "body-cap overflow must surface as a wrapped IOException");
        assertTrue(ex.getCause().getMessage().contains("cap"),
            "wrapped cause must name the byte cap; got: " + ex.getCause().getMessage());
    }

    @Test
    void generateThrowsOn429RegardlessOfRetryAfterHeader() throws Exception {
        // The Retry-After machinery was deleted (no consumer ever slept on
        // it). Surviving behavior: a rate-limited 429 — header or not — is
        // a plain LlmCallFailedException naming the status; no retry
        // advice is parsed or surfaced.
        String errorBody = """
            {"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}""";
        mockServer.createContext("/messages", exchange -> {
            byte[] resp = errorBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Retry-After", "2");
            exchange.sendResponseHeaders(429, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        mockServer.start();

        AnthropicProvider provider = providerFor(ModelTask.SUMMARIZER, API_KEY);

        LlmCallFailedException ex = assertThrows(LlmCallFailedException.class,
            () -> provider.generate(ModelTask.SUMMARIZER, "sys", "usr"));
        assertTrue(ex.getMessage().contains("429"),
            "exception must name the rate-limited status; got: " + ex.getMessage());
    }

    @Test
    void remoteLlmShapedConfigResolvesChatAgentAndSummarizer() throws Exception {
        // Mirrors the %remote-llm property block (base-url, max-tokens,
        // model — api-key arrives via environment in production,
        // timeout-ms falls back to its default): per-task resolution for
        // CHAT_AGENT and SUMMARIZER must succeed end-to-end with each
        // task's own model on the wire.
        AtomicReference<String> lastModel = new AtomicReference<>();
        mockServer.createContext("/messages", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastModel.set(JSON.readTree(body).get("model").asText());
            byte[] resp = successResponse("ok").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        mockServer.start();

        Config cfg = new StubConfig(Map.of(
            "infochat.llm.chat.base-url", baseUrl,
            "infochat.llm.chat.max-tokens", "2048",
            "infochat.llm.chat.model", "claude-sonnet-4-6",
            "infochat.llm.chat.api-key", API_KEY,
            "infochat.llm.summarizer.base-url", baseUrl,
            "infochat.llm.summarizer.max-tokens", "4096",
            "infochat.llm.summarizer.model", "claude-opus-4-8",
            "infochat.llm.summarizer.api-key", API_KEY
        ));
        AnthropicProvider provider = new AnthropicProvider(cfg, HttpClient.newHttpClient());

        LlmResponse chatReply = assertDoesNotThrow(
            () -> provider.generate(ModelTask.CHAT_AGENT, "sys", "usr"),
            "CHAT_AGENT config resolution under remote-llm-shaped properties must succeed");
        assertEquals("ok", chatReply.text());
        assertEquals("claude-sonnet-4-6", lastModel.get(),
            "CHAT_AGENT must resolve the chat model key");

        LlmResponse summaryReply = assertDoesNotThrow(
            () -> provider.generate(ModelTask.SUMMARIZER, "sys", "usr"),
            "SUMMARIZER config resolution under remote-llm-shaped properties must succeed");
        assertEquals("ok", summaryReply.text());
        assertEquals("claude-opus-4-8", lastModel.get(),
            "SUMMARIZER must resolve the summarizer model key");
    }

    @Test
    void blankSystemPromptOmitsSystemField() throws Exception {
        // The Messages API rejects an empty system text block; a blank
        // system prompt (the translation call shape) must omit the
        // field entirely, not serialize an empty text block.
        AtomicReference<String> capturedBody = new AtomicReference<>();
        mockServer.createContext("/messages", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = successResponse("ok").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        mockServer.start();

        AnthropicProvider provider = providerFor(ModelTask.SUMMARIZER, API_KEY);
        provider.generate(ModelTask.SUMMARIZER, "", "user prompt");

        JsonNode root = JSON.readTree(capturedBody.get());
        assertNull(root.get("system"),
            "a blank system prompt must omit the top-level system field entirely; got: "
                + root.get("system"));
        assertEquals("user prompt", root.get("messages").get(0).get("content").asText(),
            "the user message must be unaffected by the system-field omission");
    }

    @Test
    void remoteLlmShapedConfigResolvesTranslatorForCzech() {
        // Mirrors the %remote-llm translator block (base-url, max-tokens,
        // model, provider override; api-key arrives via environment in
        // production): the router must resolve TRANSLATOR for "cs" to the
        // anthropic provider, whose TRANSLATOR config must resolve without
        // throwing on missing keys — at startup, not at the first /lang
        // translation.
        Config cfg = new StubConfig(Map.of(
            "infochat.llm.translator.base-url", "https://api.anthropic.com/v1",
            "infochat.llm.translator.max-tokens", "4096",
            "infochat.llm.translator.model", "claude-sonnet-4-6"));
        AnthropicProvider anthropic = new AnthropicProvider(cfg, HttpClient.newHttpClient());
        LlmRouter router = new LlmRouter(
            List.of(
                new LlmRouter.Entry(OpenAiCompatibleProvider.PROVIDER_NAME,
                    new OpenAiCompatibleProvider(cfg), Set.of("en")),
                new LlmRouter.Entry(AnthropicProvider.PROVIDER_NAME,
                    anthropic, Set.of("en", "cs"))),
            LlmRouter.ConfigReader.fromMap(Map.of(
                "infochat.llm.translator.provider", AnthropicProvider.PROVIDER_NAME)));

        LlmProvider resolved = router.forTask(ModelTask.TRANSLATOR, "cs");

        assertSame(anthropic, resolved,
            "remote-llm-shaped config must route TRANSLATOR/cs to the anthropic provider");
        assertDoesNotThrow(() -> resolved.assertTaskConfigResolvable(ModelTask.TRANSLATOR),
            "the resolved provider's TRANSLATOR config must not throw on missing keys");
    }

    private AnthropicProvider providerFor(ModelTask task, String apiKey) {
        String seg = task.keySegment();
        Config cfg = new StubConfig(Map.of(
            "infochat.llm." + seg + ".base-url", baseUrl,
            "infochat.llm." + seg + ".api-key", apiKey,
            "infochat.llm." + seg + ".model", MODEL,
            "infochat.llm." + seg + ".timeout-ms", "5000",
            "infochat.llm." + seg + ".max-tokens", "1024",
            // Pin the body cap to the 1 MiB clamp floor so the body-cap
            // test can overflow it with a 2 MiB reply instead of 8 MiB.
            "infochat.llm.max-response-bytes", "1048576"
        ));
        return new AnthropicProvider(cfg, HttpClient.newHttpClient());
    }

    private static String successResponse(String text) {
        return """
            {"id":"msg_test","type":"message","role":"assistant",\
            "content":[{"type":"text","text":"%s"}],\
            "model":"claude-sonnet-4-20250514","stop_reason":"end_turn"}""".formatted(text);
    }

}
