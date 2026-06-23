package app.zcat.infochat.llm.impl;

import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link AnthropicProvider}'s cache-token accounting: because the
 * provider enables {@code cache_control: ephemeral}, a cache hit reports
 * the cached prefix under {@code cache_read_input_tokens} (and a cache
 * write under {@code cache_creation_input_tokens}) instead of
 * {@code input_tokens}. The reported {@code TokenUsage.inputTokens()}
 * must fold those in so {@code llm.tokens.in} does not undercount; a
 * response with no cache fields must stay byte-identical to the
 * pre-fold behaviour. Same local mock-server pattern as
 * {@link AnthropicProviderMultiBlockContentTest} — no Quarkus boot.
 */
class AnthropicProviderCacheTokenTest {

    private static final String MODEL = "claude-sonnet-4-20250514";

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
    void cacheReadAndCreationTokensAreFoldedIntoReportedInput() {
        serveResponse("""
            {"id":"msg_test","type":"message","role":"assistant",\
            "content":[{"type":"text","text":"ok"}],\
            "model":"%s","stop_reason":"end_turn",\
            "usage":{"input_tokens":10,"cache_read_input_tokens":40,\
            "cache_creation_input_tokens":7,"output_tokens":5}}""".formatted(MODEL));

        LlmResponse response = provider().generate(ModelTask.SUMMARIZER, "sys", "usr");

        assertEquals(57, response.usage().inputTokens(),
            "cache_read + cache_creation tokens must fold into the reported input (10+40+7)");
        assertEquals(5, response.usage().outputTokens(),
            "output tokens are unchanged by the cache-token fold");
    }

    @Test
    void responseWithoutCacheFieldsReportsRawInputUnchanged() {
        serveResponse("""
            {"id":"msg_test","type":"message","role":"assistant",\
            "content":[{"type":"text","text":"ok"}],\
            "model":"%s","stop_reason":"end_turn",\
            "usage":{"input_tokens":10,"output_tokens":5}}""".formatted(MODEL));

        LlmResponse response = provider().generate(ModelTask.SUMMARIZER, "sys", "usr");

        assertEquals(10, response.usage().inputTokens(),
            "with no cache fields the reported input must equal input_tokens, as before the fold");
        assertEquals(5, response.usage().outputTokens());
    }

    private void serveResponse(String body) {
        mockServer.createContext("/messages", exchange -> {
            byte[] resp = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        mockServer.start();
    }

    private AnthropicProvider provider() {
        String seg = ModelTask.SUMMARIZER.keySegment();
        Config cfg = new StubConfig(Map.of(
            "infochat.llm." + seg + ".base-url", baseUrl,
            "infochat.llm." + seg + ".api-key", "sk-test-key",
            "infochat.llm." + seg + ".model", MODEL,
            "infochat.llm." + seg + ".timeout-ms", "5000",
            "infochat.llm." + seg + ".max-tokens", "1024"
        ));
        return new AnthropicProvider(cfg, HttpClient.newHttpClient());
    }
}
