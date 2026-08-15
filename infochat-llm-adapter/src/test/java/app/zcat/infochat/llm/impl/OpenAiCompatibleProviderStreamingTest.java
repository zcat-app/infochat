package app.zcat.infochat.llm.impl;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streaming-shape tests for {@link OpenAiCompatibleProvider} against a
 * fake SSE endpoint. Pins the SPI contract only — chunk delivery
 * order, assembled final text, terminal usage frame, and the
 * transport-vs-application failure classes — nothing about the
 * consumer that will later be built on it.
 */
class OpenAiCompatibleProviderStreamingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SseMockServer mockServer;

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.close();
        }
    }

    private LlmProvider providerFor(String baseUrl) {
        return new OpenAiCompatibleProvider(new StubConfig(Map.of(
            "infochat.llm.chat.base-url", baseUrl,
            "infochat.llm.chat.api-key", "",
            "infochat.llm.chat.model", "model-chat",
            "infochat.llm.chat.timeout-ms", "5000")));
    }

    @Test
    void streamsChunksInOrderToTheConsumer() throws Exception {
        mockServer = new SseMockServer(os -> os.write(SseMockServer.sseFrames(
            "{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}",
            "{\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}",
            "{\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}",
            "{\"choices\":[{\"delta\":{\"content\":\"!\"}}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":3}}",
            "[DONE]")));
        List<String> chunks = new CopyOnWriteArrayList<>();

        LlmResponse response = providerFor(mockServer.baseUrl())
            .generateStreaming(ModelTask.CHAT_AGENT, "sys", "usr", chunks::add);

        assertEquals(List.of("Hel", "lo", "!"), chunks,
            "the SSE delta chunks must reach the consumer in wire order");
        assertEquals("Hello!", response.text(),
            "the final text is the assembled chunks");
        assertNotNull(response.usage(), "the terminal usage frame must populate usage");
        assertEquals(11, response.usage().inputTokens());
        assertEquals(3, response.usage().outputTokens());
        assertTrue(JSON.readTree(mockServer.receivedBodies().get(0)).path("stream").asBoolean(),
            "the streaming request must carry stream:true on the wire body");
    }

    @Test
    void aStreamingRequestCarriesTheDecidedUsageOptIn() throws Exception {
        // The OBSERVED with-flag shape (OpenAI, docs/measurement/
        // streaming-usage-optin.md §3): the finish frame carries
        // usage:null, then a terminal empty-choices usage frame, [DONE].
        mockServer = new SseMockServer(os -> os.write(SseMockServer.sseFrames(
            "{\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}",
            "{\"choices\":[{\"delta\":{\"content\":\"lo\"}}],\"finish_reason\":\"stop\",\"usage\":null}",
            "{\"choices\":[],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":9,\"total_tokens\":29}}",
            "[DONE]")));
        List<String> chunks = new CopyOnWriteArrayList<>();

        LlmResponse response = providerFor(mockServer.baseUrl())
            .generateStreaming(ModelTask.CHAT_AGENT, "sys", "usr", chunks::add);

        assertEquals(List.of("Hel", "lo"), chunks,
            "the content deltas must reach the consumer in wire order");
        assertEquals("Hello", response.text(),
            "the final text is the assembled chunks");
        assertNotNull(response.usage(),
            "the terminal empty-choices usage frame must populate usage");
        assertEquals(20, response.usage().inputTokens());
        assertEquals(9, response.usage().outputTokens());
        var body = JSON.readTree(mockServer.receivedBodies().get(0));
        assertTrue(body.path("stream").asBoolean(),
            "the streaming request must carry stream:true on the wire body");
        assertTrue(body.path("stream_options").path("include_usage").asBoolean(false),
            "the streaming request must carry the decided usage opt-in on the wire "
                + "body (docs/measurement/streaming-usage-optin.md §5)");
    }

    @Test
    void deepSeekInheritsTheStreamingShape() throws Exception {
        // DeepSeekProvider is an OpenAiCompatibleProvider subclass speaking
        // the same /chat/completions SSE wire shape — the inherited
        // generateStreaming must serve it unchanged, with the subclass's
        // thinking-disabled body field still on the request.
        mockServer = new SseMockServer(os -> os.write(SseMockServer.sseFrames(
            "{\"model\":\"deepseek-v4-flash\",\"choices\":[{\"delta\":{\"content\":\"Ahoj\"}}]}",
            "{\"model\":\"deepseek-v4-flash\",\"choices\":[{\"delta\":{\"content\":\"!\"}}],"
                + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2}}",
            "[DONE]")));
        List<String> chunks = new CopyOnWriteArrayList<>();
        LlmProvider deepSeek = new DeepSeekProvider(new StubConfig(Map.of(
            "infochat.llm.chat.base-url", mockServer.baseUrl(),
            "infochat.llm.chat.api-key", "",
            "infochat.llm.chat.model", "deepseek-v4-flash",
            "infochat.llm.chat.timeout-ms", "5000")));

        LlmResponse response = deepSeek.generateStreaming(
            ModelTask.CHAT_AGENT, "sys", "usr", chunks::add);

        assertEquals(List.of("Ahoj", "!"), chunks,
            "a DeepSeek-flavored fake stream must deliver chunks through the inherited shape");
        assertEquals("Ahoj!", response.text());
        assertNotNull(response.usage(), "the terminal usage frame must populate usage");
        assertEquals(2, response.usage().outputTokens());
        var body = JSON.readTree(mockServer.receivedBodies().get(0));
        assertTrue(body.path("stream").asBoolean(),
            "the DeepSeek streaming request must carry stream:true");
        assertEquals("disabled", body.path("thinking").path("type").asText(),
            "the subclass body seam must still ride the streaming request");
        assertTrue(body.path("stream_options").path("include_usage").asBoolean(false),
            "the inherited usage opt-in must ride the DeepSeek streaming request "
                + "alongside the thinking field");
    }

    @Test
    void streamWithNoUsageFrameCompletesWithNullUsage() throws Exception {
        // The OBSERVED without-flag shape (Ollama/llama.cpp/OpenAI, docs/
        // measurement/streaming-usage-optin.md §3): a finish frame, no
        // usage block anywhere, then [DONE].
        mockServer = new SseMockServer(os -> os.write(SseMockServer.sseFrames(
            "{\"choices\":[{\"delta\":{\"content\":\"Ahoj\"}}]}",
            "{\"choices\":[{\"delta\":{\"content\":\"!\"}}],\"finish_reason\":\"stop\"}",
            "[DONE]")));
        List<String> chunks = new CopyOnWriteArrayList<>();

        LlmResponse response = providerFor(mockServer.baseUrl())
            .generateStreaming(ModelTask.CHAT_AGENT, "sys", "usr", chunks::add);

        assertEquals(List.of("Ahoj", "!"), chunks,
            "every chunk of a usage-less stream must still reach the consumer");
        assertEquals("Ahoj!", response.text(),
            "the text assembles normally without a usage report");
        assertNull(response.usage(),
            "a stream with no usage frame reports no usage — nothing synthesizes "
                + "a figure (llm.md: the call counts as reporting no usage)");
    }

    @Test
    void midStreamConnectionDropClassifiesAsTransport() throws Exception {
        // A declared length the writer undercuts: the connection ends
        // mid-body, so the read must fail under the unreachable subtype
        // (the class the circuit breaker trips on) — never silently
        // complete, never a plain application failure.
        mockServer = new SseMockServer(200, 10_000, os -> os.write(SseMockServer.sseFrames(
            "{\"choices\":[{\"delta\":{\"content\":\"part\"}}]}")));
        List<String> chunks = new CopyOnWriteArrayList<>();

        LlmCallFailedException failure = assertThrows(LlmCallFailedException.class,
            () -> providerFor(mockServer.baseUrl())
                .generateStreaming(ModelTask.CHAT_AGENT, "sys", "usr", chunks::add));

        assertInstanceOf(LlmCallFailedException.ProviderUnreachableException.class, failure,
            "a mid-stream connection drop is transport-class evidence");
        assertEquals(List.of("part"), chunks,
            "chunks delivered before the drop stay delivered — nothing synthetic after it");
    }

    @Test
    void streamEndingWithoutDoneTerminatorFailsTheCall() throws Exception {
        // A server that closes cleanly before [DONE] truncated the
        // stream; the call must fail rather than return a partial text
        // as if the model had finished.
        mockServer = new SseMockServer(os -> os.write(SseMockServer.sseFrames(
            "{\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}")));
        List<String> chunks = new CopyOnWriteArrayList<>();

        LlmCallFailedException failure = assertThrows(LlmCallFailedException.class,
            () -> providerFor(mockServer.baseUrl())
                .generateStreaming(ModelTask.CHAT_AGENT, "sys", "usr", chunks::add));

        assertFalse(failure instanceof LlmCallFailedException.ProviderUnreachableException,
            "the endpoint answered — a clean early end is an application failure");
        assertEquals(List.of("Hel"), chunks);
    }

    @Test
    void nonTwoxxStatusFailsTheCallWithNoChunkDelivered() throws Exception {
        mockServer = new SseMockServer(500, 0, os -> {
            // A non-2xx body never reaches the SSE loop; the writer runs
            // only to let the exchange complete.
        });
        List<String> chunks = new CopyOnWriteArrayList<>();

        LlmCallFailedException failure = assertThrows(LlmCallFailedException.class,
            () -> providerFor(mockServer.baseUrl())
                .generateStreaming(ModelTask.CHAT_AGENT, "sys", "usr", chunks::add));

        assertFalse(failure instanceof LlmCallFailedException.ProviderUnreachableException,
            "a status line proves the endpoint answered — application class");
        assertEquals(List.of(), chunks, "no chunk may be delivered for a non-2xx response");
    }

    @Test
    void supportsStreamingIsTrueForEveryTask() {
        mockServer = null;
        LlmProvider provider = providerFor("http://localhost:9");
        for (ModelTask task : ModelTask.values()) {
            assertTrue(provider.supportsStreaming(task),
                "the OpenAI-compatible SSE dialect serves " + task);
        }
    }
}
