package app.zcat.infochat.llm.impl;

import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.impl.OpenAiCompatibleProvider.LlmCallFailedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertTrue(ex.getMessage().contains("max_tokens: must be positive"),
            "exception must include Anthropic error message");
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

    @SuppressWarnings("unchecked")
    private static final class StubConfig implements Config {
        private final Map<String, String> values;

        StubConfig(Map<String, String> values) {
            this.values = Map.copyOf(values);
        }

        @Override
        public <T> T getValue(String propertyName, Class<T> propertyType) {
            String raw = values.get(propertyName);
            if (raw == null) {
                throw new java.util.NoSuchElementException(
                    "StubConfig: no value for " + propertyName);
            }
            return convert(raw, propertyType);
        }

        @Override
        public ConfigValue getConfigValue(String propertyName) {
            throw new UnsupportedOperationException("getConfigValue not stubbed");
        }

        @Override
        public <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
            String raw = values.get(propertyName);
            if (raw == null || raw.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(convert(raw, propertyType));
        }

        @Override
        public <T> List<T> getValues(String propertyName, Class<T> propertyType) {
            throw new UnsupportedOperationException("getValues not stubbed");
        }

        @Override
        public <T> Optional<List<T>> getOptionalValues(String propertyName, Class<T> propertyType) {
            throw new UnsupportedOperationException("getOptionalValues not stubbed");
        }

        @Override
        public Iterable<String> getPropertyNames() {
            return values.keySet();
        }

        @Override
        public Iterable<ConfigSource> getConfigSources() {
            return List.of();
        }

        @Override
        public <T> Optional<Converter<T>> getConverter(Class<T> forType) {
            return Optional.empty();
        }

        @Override
        public <T> T unwrap(Class<T> type) {
            throw new UnsupportedOperationException("unwrap not stubbed");
        }

        private static <T> T convert(String raw, Class<T> type) {
            if (type == String.class) {
                return type.cast(raw);
            }
            if (type == Long.class || type == long.class) {
                return type.cast(Long.parseLong(raw));
            }
            if (type == Integer.class || type == int.class) {
                return type.cast(Integer.parseInt(raw));
            }
            throw new UnsupportedOperationException("StubConfig: unsupported type " + type);
        }
    }
}
