package app.zcat.infochat.llm.impl;

import app.zcat.infochat.llm.LlmResponse;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit5 tests for the shared {@link LlmHttpSupport} request
 * helpers used by all three provider impls.
 */
class LlmHttpSupportTest {

    @Test
    void joinPathInsertsSingleSlashWhenBaseHasNoTrailingSlash() {
        assertEquals("http://localhost:11434/v1/messages",
            LlmHttpSupport.joinPath("http://localhost:11434/v1", "/messages"));
    }

    @Test
    void joinPathCollapsesTrailingSlashOnBase() {
        assertEquals("http://localhost:11434/v1/messages",
            LlmHttpSupport.joinPath("http://localhost:11434/v1/", "/messages"));
    }

    /** Non-2xx on BOTH the unary and streaming seams throws the status-carrying subtype with the redacted message shape — the canary in the fake body must never reach the message. */
    @Test
    void non2xxCarriesTypedStatusAndRedactedMessage() throws Exception {
        String canary = "leak-secret-context-echo";
        byte[] body = ("{\"error\":\"" + canary + "\"}").getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.sendResponseHeaders(400, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            HttpClient http = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/v1/chat/completions"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

            Config config = new StubConfig(Map.of(
                "infochat.llm.max-response-bytes", Long.toString(1024 * 1024)));
            LlmCallFailedException.ProviderRequestRejectedException unary =
                assertThrows(LlmCallFailedException.ProviderRequestRejectedException.class,
                    () -> LlmHttpSupport.executeJsonCall(http, config, request,
                        "test-provider", (responseBody, uri) -> new LlmResponse("")),
                    "executeJsonCall's non-2xx path must throw the typed rejection");
            assertTypedRedacted(unary);

            LlmCallFailedException.ProviderRequestRejectedException streaming =
                assertThrows(LlmCallFailedException.ProviderRequestRejectedException.class,
                    () -> LlmHttpSupport.executeStreamingCall(http, config, request,
                        "test-provider", 5000,
                        new LlmHttpSupport.StreamingResponseParser() {
                            @Override
                            public boolean onFrame(String data) {
                                return false;
                            }

                            @Override
                            public LlmResponse result() {
                                return new LlmResponse("");
                            }
                        }),
                    "executeStreamingCall's non-2xx path must throw the typed rejection");
            assertTypedRedacted(streaming);
        } finally {
            server.stop(0);
        }
    }

    private void assertTypedRedacted(
            LlmCallFailedException.ProviderRequestRejectedException exception) {
        assertEquals(400, exception.httpStatus(),
            "the typed carrier must expose the rejected status");
        assertFalse(exception.getMessage().contains("leak-secret-context-echo"),
            "the provider error body must never reach the message; got: "
                + exception.getMessage());
        assertTrue(exception.getMessage().contains("non-2xx status 400"),
            "the redacted message shape names the status; got: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("localhost"),
            "the redacted message shape names the host; got: " + exception.getMessage());
        assertTrue(exception.getMessage().startsWith("test-provider"),
            "the redacted message shape names the provider; got: " + exception.getMessage());
    }
}
