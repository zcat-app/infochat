package app.zcat.infochat.llm.routing;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.impl.OpenAiCompatibleProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tool-transport resolution against the real wire leg and HttpServer fakes — posture as {@link LlmRouterTest}.
 */
class LlmRouterToolTransportTest {

    private HttpServer mockServer;
    private String baseUrl;
    private List<String> receivedBodies;
    private volatile int status;
    private volatile String responseBody;
    private Logger jul;
    private CapturingHandler capturer;

    @BeforeEach
    void attachLogHandler() {
        jul = Logger.getLogger(LlmRouter.class.getName());
        capturer = new CapturingHandler();
        capturer.setLevel(Level.ALL);
        jul.addHandler(capturer);
        jul.setLevel(Level.ALL);
    }

    @AfterEach
    void detachLogHandler() {
        jul.removeHandler(capturer);
    }

    @BeforeEach
    void setUp() throws Exception {
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        baseUrl = "http://localhost:" + mockServer.getAddress().getPort();
        receivedBodies = new CopyOnWriteArrayList<>();
        status = 200;
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"probe-ok\"}}]}";
        mockServer.createContext("/chat/completions", exchange -> {
            receivedBodies.add(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 200 ? resp.length : -1);
            try (OutputStream os = exchange.getResponseBody()) {
                if (status == 200) {
                    os.write(resp);
                }
            }
        });
        mockServer.start();
    }

    @AfterEach
    void tearDown() {
        mockServer.stop(0);
    }

    private OpenAiCompatibleProvider provider(String baseUrl) {
        return new OpenAiCompatibleProvider(new StubConfig(Map.of(
            "infochat.llm.chat.base-url", baseUrl,
            "infochat.llm.chat.api-key", "",
            "infochat.llm.chat.model", "chat-model")));
    }

    private LlmRouter router(Set<String> clearedModels) {
        return new LlmRouter(
            List.of(new LlmRouter.Entry("openai-compatible", provider(baseUrl), Set.of("en"))),
            LlmRouter.ConfigReader.fromMap(Map.of(
                "infochat.llm.chat.model", "chat-model",
                "infochat.llm.chat.base-url", baseUrl)),
            clearedModels);
    }

    @Test
    void emptyClearedSetMeansTextEverywhere() {
        LlmRouter router = router(Set.of());

        assertEquals(LlmRouter.ToolTransport.TEXT,
            router.toolTransportFor(ModelTask.CHAT_AGENT, "en"),
            "the shipped empty cleared-set resolves TEXT for every endpoint");
        assertTrue(receivedBodies.isEmpty(),
            "an empty cleared-set disarms the probe — no request is issued "
                + "against a tools-ACCEPTING endpoint; production stays byte-identical");
    }

    @Test
    void resolutionIsFailSafeAndSticky() {
        LlmRouter router = router(Set.of("chat-model"));

        assertEquals(LlmRouter.ToolTransport.NATIVE,
            router.toolTransportFor(ModelTask.CHAT_AGENT, "en"),
            "an accepting endpoint with the model cleared resolves NATIVE");
        assertEquals(1, receivedBodies.size(),
            "exactly one probe");
        assertTrue(receivedBodies.get(0).contains("\"tools\""),
            "the probe is a tools-bearing request");

        assertEquals(LlmRouter.ToolTransport.NATIVE,
            router.toolTransportFor(ModelTask.CHAT_AGENT, "en"),
            "the resolution is sticky — no second probe");
        assertEquals(1, receivedBodies.size(),
            "the sticky re-query must not re-resolve — a per-call "
                + "resolution would fire a second probe");

        // 4xx rejecting the tools field: any doubt downgrades to TEXT.
        LlmRouter rejecting = router(Set.of("chat-model"));
        status = 400;
        assertEquals(LlmRouter.ToolTransport.TEXT,
            rejecting.toolTransportFor(ModelTask.CHAT_AGENT, "en"),
            "a 4xx rejecting the tools field resolves TEXT");

        // Unparseable 2xx body: a failed probe, never a synthetic verdict.
        LlmRouter unparseable = router(Set.of("chat-model"));
        responseBody = "not-json{";
        assertEquals(LlmRouter.ToolTransport.TEXT,
            unparseable.toolTransportFor(ModelTask.CHAT_AGENT, "en"),
            "an unparseable probe response resolves TEXT");

        // Unreachable endpoint: the transport-class failure resolves TEXT.
        LlmRouter unreachable = new LlmRouter(
            List.of(new LlmRouter.Entry("openai-compatible",
                provider("http://localhost:" + findClosedPort()), Set.of("en"))),
            LlmRouter.ConfigReader.fromMap(Map.of(
                "infochat.llm.chat.model", "chat-model")),
            Set.of("chat-model"));
        assertEquals(LlmRouter.ToolTransport.TEXT,
            unreachable.toolTransportFor(ModelTask.CHAT_AGENT, "en"),
            "an unreachable endpoint resolves TEXT — the chat turn degrades, never breaks");
    }

    @Test
    void resolutionLogsTaskEndpointAndOutcome() {
        // The shipped empty cleared-set — the acceptance-item-4 promise
        // ("logged naming task/endpoint/outcome") must hold on the
        // TEXT resolution every endpoint gets at startup.
        LlmRouter router = router(Set.of());

        assertEquals(LlmRouter.ToolTransport.TEXT,
            router.toolTransportFor(ModelTask.CHAT_AGENT, "en"),
            "the shipped empty cleared-set resolves TEXT");

        List<LogRecord> info = capturer.recordsAtLevel(Level.INFO);
        assertEquals(1, info.size(),
            "one resolution log line per resolution; captured: "
                + capturer.formattedAll());
        String message = CapturingHandler.formatMessage(info.get(0));
        assertTrue(message.contains("task chat"),
            "the resolution log must name the task; got: " + message);
        assertTrue(message.contains("endpoint localhost:" + mockServer.getAddress().getPort()),
            "the resolution log must name the endpoint host:port; got: " + message);
        assertTrue(message.contains("resolved TEXT"),
            "the resolution log must name the outcome; got: " + message);
    }

    /** A provider that reports cannot-serve short of any probe. */
    @Test
    void cannotServeProviderResolvesTextWithoutProbing() {
        LlmProvider refusing = new LlmProvider() {
            @Override
            public String providerName() {
                return "openai-compatible";
            }

            @Override
            public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
                return new LlmResponse("unused");
            }

            @Override
            public LlmResponse generateWithTools(ModelTask task, String systemPrompt,
                                                 String userPrompt,
                                                 List<LlmProvider.ToolDeclaration> tools) {
                throw new AssertionError("a cannot-serve provider is never probed");
            }
        };
        LlmRouter router = new LlmRouter(
            List.of(new LlmRouter.Entry("openai-compatible", refusing, Set.of("en"))),
            LlmRouter.ConfigReader.fromMap(Map.of(
                "infochat.llm.chat.model", "chat-model")),
            Set.of("chat-model"));

        assertEquals(LlmRouter.ToolTransport.TEXT,
            router.toolTransportFor(ModelTask.CHAT_AGENT, "en"),
            "the honest cannot-serve signal resolves TEXT before the probe");
        assertTrue(receivedBodies.isEmpty());
    }

    private static int findClosedPort() {
        // Bind-and-release: the OS picks a free port, then closing it
        // leaves (with near-certainty) nothing listening — the probe's
        // connection fails as transport-unreachable.
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException("no free port", e);
        }
    }

    /** Map-backed MicroProfile Config — the routing package's own copy. */
    @SuppressWarnings("unchecked")
    private static final class StubConfig implements org.eclipse.microprofile.config.Config {
        private final Map<String, String> values;

        StubConfig(Map<String, String> values) {
            this.values = Map.copyOf(values);
        }

        @Override
        public <T> T getValue(String propertyName, Class<T> propertyType) {
            String raw = values.get(propertyName);
            if (raw == null) {
                throw new NoSuchElementException(propertyName);
            }
            return (T) raw;
        }

        @Override
        public org.eclipse.microprofile.config.ConfigValue getConfigValue(String propertyName) {
            throw new UnsupportedOperationException("getConfigValue not stubbed");
        }

        @Override
        public <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
            return (Optional<T>) Optional.ofNullable(values.get(propertyName));
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
        public Iterable<org.eclipse.microprofile.config.spi.ConfigSource> getConfigSources() {
            return List.of();
        }

        @Override
        public <T> Optional<org.eclipse.microprofile.config.spi.Converter<T>> getConverter(
                Class<T> forType) {
            return Optional.empty();
        }

        @Override
        public <T> T unwrap(Class<T> type) {
            throw new UnsupportedOperationException("unwrap not stubbed");
        }
    }
}
