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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 *
 * <p>The class also pins the third shared-pipeline property that has no
 * per-provider variant: an already-armed caller interrupt costs zero
 * outbound requests and survives the call — see
 * {@link #interruptedCallerSendsNoRequestAndKeepsTheInterruptArmed}.
 */
class HttpProviderSharedPipelineTest {

    private static final String MODEL = "test-model";
    /** Pin the cap to the 1 MiB clamp floor so a 2 MiB reply overflows it. */
    private static final long CAP_BYTES = 1024 * 1024;

    private HttpServer mockServer;
    private Map<String, LlmProvider> providers;
    /** Requests the mock server actually handled, so "no request was sent" can be asserted. */
    private AtomicInteger requestsServed;

    @BeforeEach
    void setUp() throws Exception {
        requestsServed = new AtomicInteger();
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
    void non2xxOmitsResponseBodyButKeepsHostAndStatus() {
        // U-13: provider error bodies can echo request fragments or user
        // content, so they must never reach the exception message — only
        // the provider, status, and host. A distinctive marker in the body
        // proves the body is dropped; "localhost" proves the host survives.
        String marker = "leak-secret-echoed-from-request-body";
        byte[] body = ("{\"error\":\"" + marker + "\"}").getBytes(StandardCharsets.UTF_8);
        respondToBothEndpoints(500, body);

        for (Map.Entry<String, LlmProvider> entry : providers.entrySet()) {
            String name = entry.getKey();
            LlmProvider provider = entry.getValue();
            LlmCallFailedException ex = assertThrows(LlmCallFailedException.class,
                () -> provider.generate(ModelTask.SUMMARIZER, "sys", "usr"),
                name + " must surface a non-2xx as LlmCallFailedException");
            assertFalse(ex.getMessage().contains(marker),
                name + " must not echo the provider error body into the exception; got: "
                    + ex.getMessage());
            assertTrue(ex.getMessage().contains("non-2xx status 500"),
                name + " must report the status; got: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("localhost"),
                name + " must name the host for triage; got: " + ex.getMessage());
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

    @Test
    void bothProvidersFailStartupScanOnNonPositiveTimeoutNamingTheProperty() {
        // M1-409: a non-positive per-task timeout-ms must fail the startup
        // config scan (assertTaskConfigResolvable) for BOTH chat providers,
        // naming the offending property — the sibling guard to the base-url
        // check above. Without it the value reaches HttpRequest.Builder.timeout
        // on the first live call, where the Stage 2 worker's catch absorbs the
        // IllegalArgumentException as a recurring transient outage.
        String seg = ModelTask.SUMMARIZER.keySegment();
        String property = "infochat.llm." + seg + ".timeout-ms";
        Map<String, String> values = new LinkedHashMap<>();
        values.put("infochat.llm." + seg + ".base-url", "http://localhost:9");
        values.put("infochat.llm." + seg + ".model", MODEL);
        values.put(property, "0");
        // Required by AnthropicProvider.configFor; ignored by the OpenAI sibling.
        values.put("infochat.llm." + seg + ".max-tokens", "1024");
        Config config = new StubConfig(values);

        Map<String, LlmProvider> badTimeout = new LinkedHashMap<>();
        badTimeout.put("AnthropicProvider", new AnthropicProvider(config));
        badTimeout.put("OpenAiCompatibleProvider", new OpenAiCompatibleProvider(config));

        for (Map.Entry<String, LlmProvider> entry : badTimeout.entrySet()) {
            String name = entry.getKey();
            LlmProvider provider = entry.getValue();
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> provider.assertTaskConfigResolvable(ModelTask.SUMMARIZER),
                name + " must fail the startup scan on a non-positive timeout-ms");
            assertTrue(ex.getMessage().contains(property),
                name + " failure must name the offending property; got: " + ex.getMessage());
        }
    }

    @Test
    void interruptedCallerSendsNoRequestAndKeepsTheInterruptArmed() throws Exception {
        // M1-763 made a timed-out digest render stop spending LLM calls by
        // interrupting the render thread. That works only because of a
        // two-part contract at the transport floor which nothing else in
        // the suite exercises: HttpClient.send fails fast when the caller's
        // interrupt flag is ALREADY armed — throwing InterruptedException
        // without opening a socket, and clearing the flag as it goes — and
        // LlmHttpSupport.sendForBody's catch re-arms the flag before
        // rethrowing, so the NEXT call in that render is a no-op too.
        // Break either half and an orphaned render silently resumes
        // full-speed spend with nothing failing: DigestWorkerTest asserts
        // on a stub rather than the transport, and docs/spec/security.md
        // §Rate limiting has no aggregate LLM budget to catch it.
        //
        // Both thread types run, because the shape production uses is the
        // VIRTUAL one — DigestWorker submits the render to
        // newVirtualThreadPerTaskExecutor() and cancels it with cancel(true).
        // Parity measures identical today (the entry check fires before any
        // socket work), but a characterization test earns its keep by
        // catching a FUTURE JDK change, which would land on the virtual side
        // where a platform-only leg is not looking. This codebase already
        // records one interrupt behaviour that does diverge by thread type:
        // LlmOutputSanitizerAuditRowIT's in-flight socket abort.
        byte[] body = "{\"error\":\"overloaded\"}".getBytes(StandardCharsets.UTF_8);
        respondToBothEndpoints(503, body);
        LlmProvider provider = providers.get("AnthropicProvider");

        assertArmedInterruptCostsNoRequest("platform", Thread.ofPlatform(), provider);
        assertArmedInterruptCostsNoRequest("virtual", Thread.ofVirtual(), provider);

        // Control leg: the same call on a non-interrupted thread DOES reach
        // the server. Without it a counter that could never increment would
        // satisfy the zero-request assertion above vacuously.
        LlmCallFailedException served = assertThrows(LlmCallFailedException.class,
            () -> provider.generate(ModelTask.SUMMARIZER, "sys", "usr"),
            "the control call must surface the canned non-2xx");
        assertTrue(served.getMessage().contains("non-2xx status 503"),
            "the control call must reach the server, not fail earlier; got: " + served.getMessage());
        assertEquals(1, requestsServed.get(),
            "the control call must be counted, proving the counter can increment");
    }

    /**
     * One leg of {@link #interruptedCallerSendsNoRequestAndKeepsTheInterruptArmed}:
     * arm the interrupt on a fresh thread of the given kind, call {@code provider},
     * and assert the call throws, leaves the flag armed, and sends nothing.
     *
     * <p>Running on a fresh thread rather than the caller's is what keeps the
     * armed flag out of JUnit's thread — the leg's thread dies carrying it, so
     * no later test in this JVM can inherit it. Assertions raise on that thread,
     * where JUnit cannot see them, so the throwable is carried back and rethrown
     * here; without that hand-off a failing leg would pass silently.
     */
    private void assertArmedInterruptCostsNoRequest(String label, Thread.Builder builder,
                                                    LlmProvider provider) throws InterruptedException {
        int before = requestsServed.get();
        AtomicReference<Throwable> legFailure = new AtomicReference<>();
        Thread leg = builder.unstarted(() -> {
            try {
                Thread.currentThread().interrupt();
                LlmCallFailedException ex = assertThrows(LlmCallFailedException.class,
                    () -> provider.generate(ModelTask.SUMMARIZER, "sys", "usr"),
                    label + ": an already-armed interrupt must surface as LlmCallFailedException");
                assertTrue(Thread.currentThread().isInterrupted(),
                    label + ": sendForBody must re-arm the flag HttpClient.send cleared; got: "
                        + ex.getMessage());
                assertEquals(before, requestsServed.get(),
                    label + ": an already-armed interrupt must cost zero outbound requests");
            } catch (Throwable t) {
                legFailure.set(t);
            }
        });
        leg.start();
        leg.join();

        Throwable failed = legFailure.get();
        if (failed != null) {
            throw new AssertionError("interrupt contract failed on the " + label + " thread", failed);
        }
    }

    /** Serve {@code (status, body)} on both providers' endpoints, then start. */
    private void respondToBothEndpoints(int status, byte[] body) {
        for (String path : List.of("/messages", "/chat/completions")) {
            mockServer.createContext(path, exchange -> {
                requestsServed.incrementAndGet();
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
