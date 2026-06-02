package app.zcat.infochat.llm.impl;

import app.zcat.infochat.llm.EmbeddingResult;
import app.zcat.infochat.llm.impl.OpenAiCompatibleEmbeddingProvider.EmbeddingCallFailedException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit5 tests for {@link OpenAiCompatibleEmbeddingProvider}. Uses
 * the JDK's {@code com.sun.net.httpserver.HttpServer} as a local mock to
 * return canned {@code /embeddings} replies — no Quarkus boot, no
 * WireMock dependency (same shape as {@code AnthropicProviderTest}). The
 * package-private {@link org.eclipse.microprofile.config.inject.ConfigProperty}
 * fields are set directly because the test lives in the same package.
 */
class OpenAiCompatibleEmbeddingProviderTest {

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
    void embedThrowsWhenResponseSizeDiffersFromInput() {
        // Two inputs, one embedding back — the provider must treat the
        // size divergence as a batch failure, not return a short list.
        respondWith("{\"data\":[{\"embedding\":[0.1,0.2]}]}");

        OpenAiCompatibleEmbeddingProvider provider = provider();

        EmbeddingCallFailedException ex = assertThrows(EmbeddingCallFailedException.class,
            () -> provider.embed(List.of("first text", "second text")));
        assertTrue(ex.getMessage().contains("shape mismatch"),
            "exception message must name the shape mismatch");
        assertTrue(ex.getMessage().contains("expected 2") && ex.getMessage().contains("got 1"),
            "exception message must report expected and actual counts");
    }

    @Test
    void embedReturnsOneResultPerInputWhenSizesMatch() {
        respondWith("{\"data\":[{\"embedding\":[0.1,0.2]},{\"embedding\":[0.3,0.4]}]}");

        OpenAiCompatibleEmbeddingProvider provider = provider();

        List<EmbeddingResult> results = provider.embed(List.of("first text", "second text"));

        assertEquals(2, results.size(), "one result per input when the reply size matches");
        assertEquals(new EmbeddingResult(new float[] {0.1f, 0.2f}), results.get(0));
        assertEquals(new EmbeddingResult(new float[] {0.3f, 0.4f}), results.get(1));
    }

    @Test
    void embedParsesRetryAfterHeaderOn503() {
        mockServer.createContext("/embeddings", exchange -> {
            byte[] resp = "{\"error\":\"unavailable\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Retry-After", "3");
            exchange.sendResponseHeaders(503, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        mockServer.start();

        OpenAiCompatibleEmbeddingProvider provider = provider();

        EmbeddingCallFailedException ex = assertThrows(EmbeddingCallFailedException.class,
            () -> provider.embed(List.of("text")));
        assertEquals(3000L, ex.retryAfterMs(),
            "Retry-After: 3 on a 503 must surface as 3000ms on the exception");
    }

    private void respondWith(String json) {
        mockServer.createContext("/embeddings", exchange -> {
            byte[] resp = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        mockServer.start();
    }

    private OpenAiCompatibleEmbeddingProvider provider() {
        OpenAiCompatibleEmbeddingProvider provider = new OpenAiCompatibleEmbeddingProvider();
        provider.baseUrl = baseUrl;
        provider.apiKey = Optional.empty();
        provider.model = "test-embed-model";
        provider.timeoutMs = 5000;
        return provider;
    }
}
