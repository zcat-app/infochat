package app.zcat.infochat.llm.impl;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.ModelTask;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the contract that {@link AnthropicProvider} and
 * {@link OpenAiCompatibleProvider} share ONE response-cap/clamp and
 * non-2xx failure-surface path — the {@link LlmHttpSupport#executeJsonCall}
 * hoist. The response cap and the failure surface are a robustness
 * contract that must stay identical across the two HTTP providers; this
 * test drives both through a non-2xx reply and an over-cap reply and
 * asserts each surfaces the same {@link LlmCallFailedException} shape, so
 * the two cannot drift if one provider's call site is later edited in
 * isolation.
 *
 * <p>Each provider POSTs to its own endpoint ({@code /messages} vs
 * {@code /chat/completions}); both contexts return the same canned reply
 * so the only variable is which provider issued the call.
 */
class HttpProviderSharedPipelineTest {

    private static final String MODEL = "test-model";
    /** Pin the cap to the 1 MiB clamp floor so a 2 MiB reply overflows it. */
    private static final long CAP_BYTES = 1024 * 1024;

    private HttpServer mockServer;
    private Map<String, LlmProvider> providers;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        String baseUrl = "http://localhost:" + mockServer.getAddress().getPort();

        String seg = ModelTask.SUMMARIZER.keySegment();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("infochat.llm." + seg + ".base-url", baseUrl);
        values.put("infochat.llm." + seg + ".api-key", "");
        values.put("infochat.llm." + seg + ".model", MODEL);
        values.put("infochat.llm." + seg + ".timeout-ms", "5000");
        // Required by AnthropicProvider.configFor; ignored by the OpenAI sibling.
        values.put("infochat.llm." + seg + ".max-tokens", "1024");
        values.put("infochat.llm.max-response-bytes", Long.toString(CAP_BYTES));
        Config config = new StubConfig(values);

        // LinkedHashMap so the assertion failure message names a stable order.
        providers = new LinkedHashMap<>();
        providers.put("AnthropicProvider", new AnthropicProvider(config));
        providers.put("OpenAiCompatibleProvider", new OpenAiCompatibleProvider(config));
    }

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    @Test
    void bothProvidersSurfaceNon2xxThroughTheSharedThrowSite() {
        byte[] body = "{\"error\":\"overloaded\"}".getBytes(StandardCharsets.UTF_8);
        respondToBothEndpoints(503, body);

        for (Map.Entry<String, LlmProvider> entry : providers.entrySet()) {
            String name = entry.getKey();
            LlmProvider provider = entry.getValue();
            LlmCallFailedException ex = assertThrows(LlmCallFailedException.class,
                () -> provider.generate(ModelTask.SUMMARIZER, "sys", "usr"),
                name + " must surface a non-2xx as LlmCallFailedException");
            // "non-2xx status <code>" is the shared executeJsonCall throw
            // site's wording — both providers reaching it proves the path
            // is single-sourced.
            assertTrue(ex.getMessage().contains("non-2xx status 503"),
                name + " must report the shared non-2xx wording + status; got: " + ex.getMessage());
        }
    }

    @Test
    void bothProvidersAbortOnOverCapResponseThroughTheSharedClamp() {
        // 2 MiB reply against the 1 MiB clamp floor: the bounded body read
        // must abort and wrap the cap-overflow IOException identically for
        // both providers.
        byte[] huge = new byte[2 * 1024 * 1024];
        java.util.Arrays.fill(huge, (byte) 'x');
        respondToBothEndpoints(200, huge);

        for (Map.Entry<String, LlmProvider> entry : providers.entrySet()) {
            String name = entry.getKey();
            LlmProvider provider = entry.getValue();
            LlmCallFailedException ex = assertThrows(LlmCallFailedException.class,
                () -> provider.generate(ModelTask.SUMMARIZER, "sys", "usr"),
                name + " must abort an over-cap reply with LlmCallFailedException");
            assertNotNull(ex.getCause(),
                name + " must wrap the cap-overflow IOException as the cause");
            assertTrue(ex.getCause().getMessage().contains("cap"),
                name + " wrapped cause must name the byte cap; got: " + ex.getCause().getMessage());
        }
    }

    @Test
    void bothProvidersFailStartupScanOnMalformedBaseUrlNamingTheProperty() {
        // U-28: a malformed per-task base-url must fail the startup config
        // scan (assertTaskConfigResolvable, driven by
        // LlmRouter.assertAllTasksResolve) for BOTH chat providers, naming
        // the offending property — rather than throwing from the per-call
        // URI.create where the worker's catch absorbs it as a transient
        // outage. A bad scheme stands in for the general malformed case.
        String seg = ModelTask.SUMMARIZER.keySegment();
        String property = "infochat.llm." + seg + ".base-url";
        Map<String, String> values = new LinkedHashMap<>();
        values.put(property, "ftp://example.com");
        values.put("infochat.llm." + seg + ".model", MODEL);
        // Required by AnthropicProvider.configFor; ignored by the OpenAI sibling.
        values.put("infochat.llm." + seg + ".max-tokens", "1024");
        Config config = new StubConfig(values);

        Map<String, LlmProvider> malformed = new LinkedHashMap<>();
        malformed.put("AnthropicProvider", new AnthropicProvider(config));
        malformed.put("OpenAiCompatibleProvider", new OpenAiCompatibleProvider(config));

        for (Map.Entry<String, LlmProvider> entry : malformed.entrySet()) {
            String name = entry.getKey();
            LlmProvider provider = entry.getValue();
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> provider.assertTaskConfigResolvable(ModelTask.SUMMARIZER),
                name + " must fail the startup scan on a malformed base-url");
            assertTrue(ex.getMessage().contains(property),
                name + " failure must name the offending property; got: " + ex.getMessage());
        }
    }

    /** Serve {@code (status, body)} on both providers' endpoints, then start. */
    private void respondToBothEndpoints(int status, byte[] body) {
        for (String path : List.of("/messages", "/chat/completions")) {
            mockServer.createContext(path, exchange -> {
                exchange.sendResponseHeaders(status, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                } catch (java.io.IOException ignored) {
                    // The client cancels mid-stream once the cap is crossed; a
                    // broken-pipe on the server write is expected, not a failure.
                }
            });
        }
        mockServer.start();
    }
}
