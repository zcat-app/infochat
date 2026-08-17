package app.zcat.infochat.llm.impl;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.metrics.LlmMetrics;
import app.zcat.infochat.llm.metrics.MeteredLlmProvider;
import app.zcat.infochat.llm.routing.LlmRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tools-bearing wire leg of {@link OpenAiCompatibleProvider} (design 05 §5.4.6); mock posture as {@link OpenAiCompatibleProviderTest}.
 */
class OpenAiCompatibleProviderToolCallTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<LlmProvider.ToolDeclaration> TOOLS = List.of(
        new LlmProvider.ToolDeclaration("searchPosts", "search posts by tags within a time window",
            "{\"type\":\"object\",\"properties\":{\"tags\":{\"type\":\"array\","
                + "\"items\":{\"type\":\"string\"}},\"window\":{\"type\":\"string\"}},"
                + "\"required\":[]}"),
        new LlmProvider.ToolDeclaration("recallMemory", "recall conversation memories by keyword",
            "{\"type\":\"object\",\"properties\":{\"keywords\":{\"type\":\"array\","
                + "\"items\":{\"type\":\"string\"}}}}"));

    private HttpServer mockServer;
    private String baseUrl;
    private List<String> receivedBodies;
    private volatile String responseBody;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        baseUrl = "http://localhost:" + mockServer.getAddress().getPort();
        receivedBodies = new CopyOnWriteArrayList<>();
        responseBody = "{\"choices\":[{\"message\":{},\"finish_reason\":\"stop\"}]}";
        mockServer.createContext("/chat/completions", exchange -> {
            receivedBodies.add(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        mockServer.start();
    }

    @AfterEach
    void tearDown() {
        mockServer.stop(0);
    }

    private Map<String, String> chatConfig() {
        Map<String, String> values = new HashMap<>();
        values.put("infochat.llm.chat.base-url", baseUrl);
        values.put("infochat.llm.chat.api-key", "");
        values.put("infochat.llm.chat.model", "chat-model");
        values.put("infochat.llm.chat.max-tokens", "256");
        return values;
    }

    @Test
    void aToolsBearingCallParsesStructuredToolCalls() throws Exception {
        responseBody = "{\"model\":\"wire-model\",\"choices\":[{\"message\":{"
            + "\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":"
            + "{\"name\":\"searchPosts\",\"arguments\":\"{\\\"tags\\\":[\\\"zcash\\\"]}\"}}]},"
            + "\"finish_reason\":\"tool_calls\"}],"
            + "\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":30}}";
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(new StubConfig(chatConfig()));

        LlmResponse response = provider.generateWithTools(
            ModelTask.CHAT_AGENT, "sys", "usr", TOOLS);

        assertNotNull(response.toolCalls());
        assertEquals(1, response.toolCalls().size());
        assertEquals("searchPosts", response.toolCalls().get(0).name());
        assertEquals("{\"tags\":[\"zcash\"]}", response.toolCalls().get(0).argumentsJson());
        assertEquals("tool_calls", response.finishReason());
        assertEquals(20, response.usage().inputTokens());
        assertEquals(30, response.usage().outputTokens());

        JsonNode body = JSON.readTree(receivedBodies.get(0));
        JsonNode tools = body.path("tools");
        assertTrue(tools.isArray());
        assertEquals(2, tools.size());
        assertEquals("function", tools.get(0).path("type").asText());
        assertEquals("searchPosts", tools.get(0).path("function").path("name").asText());
        assertEquals("search posts by tags within a time window",
            tools.get(0).path("function").path("description").asText());
        JsonNode parameters = tools.get(0).path("function").path("parameters");
        assertEquals("object", parameters.path("type").asText());
        assertTrue(parameters.path("properties").path("tags").path("items").has("type"));
        assertEquals("auto", body.path("tool_choice").asText());
    }

    @Test
    void singleStringBodyStaysByteIdenticalWithoutTheToolsFields() throws Exception {
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(new StubConfig(chatConfig()));

        LlmResponse response = provider.generate(ModelTask.CHAT_AGENT, "sys", "usr");

        assertEquals("ok", response.text());
        assertNull(response.toolCalls());
        // Byte-identical to the pre-tools body: exactly the three fields the
        // single-string shape always carried, in the assembled order.
        assertEquals("{\"model\":\"chat-model\",\"max_tokens\":256,"
                + "\"messages\":[{\"role\":\"system\",\"content\":\"sys\"},"
                + "{\"role\":\"user\",\"content\":\"usr\"}]}",
            receivedBodies.get(0));
    }

    @Test
    void contentAbsentWithToolCallsDoesNotThrow() {
        // The absent-content throw is narrowed to absent-content-AND-no-tool-calls:
        // a tools-bearing reply legitimately carries no message.content.
        responseBody = "{\"choices\":[{\"message\":{\"tool_calls\":["
            + "{\"type\":\"function\",\"function\":{\"name\":\"recallMemory\","
            + "\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}";
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(new StubConfig(chatConfig()));

        LlmResponse response = provider.generateWithTools(
            ModelTask.CHAT_AGENT, "sys", "usr", TOOLS);

        assertEquals("", response.text());
        assertEquals("recallMemory", response.toolCalls().get(0).name());
    }

    @Test
    void malformedToolCallsFailTheCallNeverASyntheticParse() {
        // A tools-accepting endpoint returning a tool_calls entry without a
        // function name is an application failure — no partial/synthetic list.
        responseBody = "{\"choices\":[{\"message\":{\"tool_calls\":"
            + "[{\"type\":\"function\",\"function\":{\"arguments\":\"{}\"}}]}}]}";
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(new StubConfig(chatConfig()));

        assertThrows(LlmCallFailedException.class,
            () -> provider.generateWithTools(ModelTask.CHAT_AGENT, "sys", "usr", TOOLS));
    }

    @Test
    void deepSeekProviderInheritsTheToolsLegAndParsesTheSameShape() throws Exception {
        responseBody = "{\"choices\":[{\"message\":{\"tool_calls\":[{\"type\":\"function\","
            + "\"function\":{\"name\":\"searchPosts\",\"arguments\":\"{}\"}}]},"
            + "\"finish_reason\":\"tool_calls\"}]}";
        Map<String, String> values = chatConfig();
        values.put("infochat.llm.chat.provider", "deepseek");
        DeepSeekProvider provider = new DeepSeekProvider(new StubConfig(values));

        assertTrue(provider.supportsToolCalls(ModelTask.CHAT_AGENT));
        LlmResponse response = provider.generateWithTools(
            ModelTask.CHAT_AGENT, "sys", "usr", TOOLS);

        assertEquals("searchPosts", response.toolCalls().get(0).name());
        // The subclass's thinking toggle rides the same body alongside tools.
        JsonNode body = JSON.readTree(receivedBodies.get(0));
        assertEquals("disabled", body.path("thinking").path("type").asText());
        assertTrue(body.path("tools").isArray());
    }

    @Test
    void overCapBodyIsDiscardedOnTheToolsShape() {
        StringBuilder huge = new StringBuilder("{\"choices\":[{\"message\":{\"content\":\"");
        huge.append("x".repeat(1_200_000));
        huge.append("\"}}]}");
        responseBody = huge.toString();
        Map<String, String> values = chatConfig();
        values.put("infochat.llm.max-response-bytes", "1048576");
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(new StubConfig(values));

        // The plain application-failure family, NOT the unreachable subtype —
        // an over-cap body proves the endpoint answered (trust boundary 9).
        LlmCallFailedException failure = assertThrows(LlmCallFailedException.class,
            () -> provider.generateWithTools(ModelTask.CHAT_AGENT, "sys", "usr", TOOLS));
        assertFalse(failure instanceof LlmCallFailedException.ProviderUnreachableException);
    }

    @Test
    void impossibleUsageIsDiscardedWholeAndNoLabelIsWireDerived() {
        // The response-body cap is floored at 1 MiB, so the usage boundary is
        // exercised through the metered decorator this call routes through in
        // production: an impossible report (negative input, over-cap output)
        // discards the whole record, and the model label reads operator config
        // even though the wire reports a different model id.
        responseBody = "{\"model\":\"wire-evil-model\",\"choices\":[{\"message\":"
            + "{\"tool_calls\":[{\"type\":\"function\",\"function\":{\"name\":\"searchPosts\","
            + "\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}],"
            + "\"usage\":{\"prompt_tokens\":-5,\"completion_tokens\":9999}}";
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredLlmProvider metered = new MeteredLlmProvider(
            new OpenAiCompatibleProvider(new StubConfig(chatConfig())),
            new LlmMetrics(registry),
            LlmRouter.ConfigReader.fromMap(Map.of(
                "infochat.llm.chat.model", "operator-model",
                "infochat.llm.chat.max-tokens", "256")));

        LlmResponse response = metered.generateWithTools(
            ModelTask.CHAT_AGENT, "sys", "usr", TOOLS);

        assertEquals("searchPosts", response.toolCalls().get(0).name());
        assertEquals(1.0, registry.get("llm.calls.total")
            .tags("task", "chat", "model", "operator-model", "outcome", "ok")
            .counter().count());
        // The whole impossible record is gone: no token counter moved.
        assertNull(registry.find("llm.tokens.in").tags("task", "chat").counter());
        assertNull(registry.find("llm.tokens.out").tags("task", "chat").counter());
        // No meter anywhere carries the wire-derived model id.
        assertTrue(registry.getMeters().stream().noneMatch(meter -> meter.getId().getTags()
            .stream().anyMatch(tag -> "wire-evil-model".equals(tag.getValue()))));
    }
}
