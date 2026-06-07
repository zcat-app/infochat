package app.zcat.infochat.llm.impl;

import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.impl.OpenAiCompatibleProvider.LlmCallFailedException;
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
