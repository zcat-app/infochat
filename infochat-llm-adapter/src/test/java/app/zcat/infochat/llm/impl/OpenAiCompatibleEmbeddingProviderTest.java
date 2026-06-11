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
import java.util.concurrent.atomic.AtomicInteger;

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
    void embedThrowsOn503RegardlessOfRetryAfterHeader() {
        // The Retry-After machinery was deleted (no consumer ever slept on
        // it). Surviving behavior: an unavailable 503 — header or not — is
        // a plain EmbeddingCallFailedException naming the status; no retry
        // advice is parsed or surfaced.
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
        assertTrue(ex.getMessage().contains("503"),
            "exception must name the unavailable status; got: " + ex.getMessage());
    }

    @Test
    void embedRoutesNon2xxAndOkThroughSharedPipeline() {
        // T19: the embedding provider now delegates its send / non-2xx surface
        // to LlmHttpSupport.sendForBody — the same pipeline the chat providers
        // use — rather than a private duplicate. One server answers two
        // sequential calls: first a 500 (non-2xx), then a valid 2xx body.
        //
        // Non-2xx half: the thrown EmbeddingCallFailedException must carry the
        // shared throw-site wording ("non-2xx status <code>") AND the appended
        // body preview. The pre-migration copy threw a status-only message with
        // no preview, so the preview substring is what proves the call now
        // flows through the shared path. 2xx half: a well-formed reply still
        // parses to one EmbeddingResult per input, unchanged.
        AtomicInteger callCount = new AtomicInteger();
        mockServer.createContext("/embeddings", exchange -> {
            boolean firstCall = callCount.getAndIncrement() == 0;
            String json = firstCall
                ? "{\"error\":\"server boom\"}"
                : "{\"data\":[{\"embedding\":[0.5,0.6]}]}";
            byte[] resp = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(firstCall ? 500 : 200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        mockServer.start();

        OpenAiCompatibleEmbeddingProvider provider = provider();

        EmbeddingCallFailedException ex = assertThrows(EmbeddingCallFailedException.class,
            () -> provider.embed(List.of("text")));
        assertTrue(ex.getMessage().contains("non-2xx status 500"),
            "non-2xx must carry the shared LlmHttpSupport throw-site wording + status; got: "
                + ex.getMessage());
        assertTrue(ex.getMessage().contains("server boom"),
            "non-2xx must append the shared body preview, proving it routes through the shared "
                + "path rather than the old status-only copy; got: " + ex.getMessage());

        List<EmbeddingResult> results = provider.embed(List.of("text"));
        assertEquals(1, results.size(), "a 2xx reply still parses to one EmbeddingResult per input");
        assertEquals(new EmbeddingResult(new float[] {0.5f, 0.6f}), results.get(0));
    }

    @Test
    void embedThrowsWhenEmbeddingElementNonNumeric() {
        // A non-numeric coordinate (string or JSON null) must become a batch
        // failure at the seam — the same EmbeddingCallFailedException a
        // missing-data[]/size-mismatch reply throws — rather than coercing to
        // 0.0 and persisting a silently corrupt vector. One server answers
        // three sequential calls: a string element, a JSON null element, then
        // a well-formed numeric reply that must still parse unchanged.
        AtomicInteger callCount = new AtomicInteger();
        mockServer.createContext("/embeddings", exchange -> {
            String json = switch (callCount.getAndIncrement()) {
                case 0 -> "{\"data\":[{\"embedding\":[\"x\",\"y\",\"z\"]}]}";
                case 1 -> "{\"data\":[{\"embedding\":[0.1,null,0.3]}]}";
                default -> "{\"data\":[{\"embedding\":[0.1,0.2]}]}";
            };
            byte[] resp = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        mockServer.start();

        OpenAiCompatibleEmbeddingProvider provider = provider();

        EmbeddingCallFailedException stringEx = assertThrows(EmbeddingCallFailedException.class,
            () -> provider.embed(List.of("text")));
        assertTrue(stringEx.getMessage().contains("not numeric"),
            "a string embedding element must throw naming the non-numeric coordinate; got: "
                + stringEx.getMessage());

        EmbeddingCallFailedException nullEx = assertThrows(EmbeddingCallFailedException.class,
            () -> provider.embed(List.of("text")));
        assertTrue(nullEx.getMessage().contains("not numeric"),
            "a JSON null embedding element must throw naming the non-numeric coordinate; got: "
                + nullEx.getMessage());

        List<EmbeddingResult> results = provider.embed(List.of("text"));
        assertEquals(1, results.size(), "a well-formed numeric reply still parses to one result");
        assertEquals(new EmbeddingResult(new float[] {0.1f, 0.2f}), results.get(0),
            "a well-formed numeric reply parses to the same float[] as before");
    }

    @Test
    void validateBaseUrlFailsOnMalformedBaseUrlNamingTheProperty() {
        // U-28: the @PostConstruct base-url check (run at Collector startup,
        // where the @Startup EmbeddingMetadataStartupGuard drives this bean)
        // must reject a malformed infochat.embeddings.base-url naming the
        // property, rather than deferring the failure to the per-call
        // URI.create inside the EmbeddingWorker's batch-failure catch.
        OpenAiCompatibleEmbeddingProvider provider = new OpenAiCompatibleEmbeddingProvider();
        provider.baseUrl = "ftp://example.com";

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            provider::validateBaseUrl,
            "a malformed embeddings base-url must fail the startup check");
        assertTrue(ex.getMessage().contains("infochat.embeddings.base-url"),
            "the failure must name the embeddings base-url property; got: " + ex.getMessage());
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
