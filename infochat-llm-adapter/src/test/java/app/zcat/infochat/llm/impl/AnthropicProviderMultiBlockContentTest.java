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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link AnthropicProvider}'s tolerance for multi-block
 * {@code content[]} responses: the Messages API may lead with a
 * non-text block (e.g. thinking) or split the reply across several
 * text blocks. The chosen policy is concatenation of every text-typed
 * block, so neither shape throws or silently truncates. Same local
 * mock-server pattern as {@link AnthropicProviderTest} — no Quarkus
 * boot, no WireMock.
 */
class AnthropicProviderMultiBlockContentTest {

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
    void thinkingBlockFirstResponseStillYieldsText() {
        serveResponse("""
            {"id":"msg_test","type":"message","role":"assistant",\
            "content":[{"type":"thinking","thinking":"let me reason about this"},\
            {"type":"text","text":"The answer is 42."}],\
            "model":"%s","stop_reason":"end_turn"}""".formatted(MODEL));

        LlmResponse response = provider().generate(ModelTask.SUMMARIZER, "sys", "usr");

        assertEquals("The answer is 42.", response.text(),
            "a leading non-text block must not prevent reading the text block");
    }

    @Test
    void multiTextBlockResponseConcatenatesAllTextBlocks() {
        serveResponse("""
            {"id":"msg_test","type":"message","role":"assistant",\
            "content":[{"type":"text","text":"part one, "},\
            {"type":"text","text":"part two."}],\
            "model":"%s","stop_reason":"end_turn"}""".formatted(MODEL));

        LlmResponse response = provider().generate(ModelTask.SUMMARIZER, "sys", "usr");

        assertEquals("part one, part two.", response.text(),
            "text spanning multiple blocks must be concatenated, not truncated to block 0");
    }

    @Test
    void responseWithNoTextBlockThrows() {
        serveResponse("""
            {"id":"msg_test","type":"message","role":"assistant",\
            "content":[{"type":"thinking","thinking":"only thoughts, no reply"}],\
            "model":"%s","stop_reason":"end_turn"}""".formatted(MODEL));

        LlmCallFailedException ex = assertThrows(LlmCallFailedException.class,
            () -> provider().generate(ModelTask.SUMMARIZER, "sys", "usr"));
        assertTrue(ex.getMessage().contains("no text content block"),
            "exception must name the missing-text-block failure; got: " + ex.getMessage());
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
