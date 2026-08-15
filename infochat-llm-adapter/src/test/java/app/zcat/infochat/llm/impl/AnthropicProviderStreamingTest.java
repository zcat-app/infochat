package app.zcat.infochat.llm.impl;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streaming-shape tests for {@link AnthropicProvider}'s SSE dialect
 * against a fake endpoint — the Anthropic event types (message_start /
 * content_block_delta / message_delta / message_stop) parsed under the
 * same consumer contract as the OpenAI-compatible dialect, with the
 * hostile-frame coverage: a malformed event frame fails the call and
 * never emits a synthetic chunk.
 */
class AnthropicProviderStreamingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SseMockServer mockServer;

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.close();
        }
    }

    private AnthropicProvider providerFor(String baseUrl) {
        return new AnthropicProvider(new StubConfig(Map.of(
            "infochat.llm.chat.base-url", baseUrl,
            "infochat.llm.chat.api-key", "",
            "infochat.llm.chat.model", "claude-model",
            "infochat.llm.chat.max-tokens", "1024",
            "infochat.llm.chat.timeout-ms", "5000")));
    }

    @Test
    void streamsTextDeltasInWireOrderWithTerminalUsage() throws Exception {
        mockServer = new SseMockServer(os -> os.write((
            "event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"usage\":"
                + "{\"input_tokens\":25,\"cache_read_input_tokens\":5,\"output_tokens\":1}}}\n\n"
                + "event: content_block_start\n"
                + "data: {\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Dobr\"}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"ý den\"}}\n\n"
                + "event: content_block_stop\n"
                + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                + "event: message_delta\n"
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                + "\"usage\":{\"output_tokens\":12}}\n\n"
                + "event: message_stop\n"
                + "data: {\"type\":\"message_stop\"}\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        List<String> chunks = new CopyOnWriteArrayList<>();

        LlmResponse response = providerFor(mockServer.baseUrl())
            .generateStreaming(ModelTask.CHAT_AGENT, "sys", "usr", chunks::add);

        assertEquals(List.of("Dobr", "ý den"), chunks,
            "text_delta frames must reach the consumer in wire order");
        assertEquals("Dobrý den", response.text(), "the final text is the assembled deltas");
        assertNotNull(response.usage(), "the terminal usage halves must combine");
        assertEquals(30, response.usage().inputTokens(),
            "input usage folds cache-read tokens in, as on the single-string parse");
        assertEquals(12, response.usage().outputTokens());
        assertTrue(JSON.readTree(mockServer.receivedBodies().get(0)).path("stream").asBoolean(),
            "the streaming request must carry stream:true on the wire body");
    }

    @Test
    void unknownEventTypeFailsTheCallNeverEmitsASyntheticChunk() throws Exception {
        mockServer = new SseMockServer(os -> os.write(SseMockServer.sseFrames(
            "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"real\"}}",
            "{\"type\":\"not_an_anthropic_event\"}")));
        List<String> chunks = new CopyOnWriteArrayList<>();

        LlmCallFailedException failure = assertThrows(LlmCallFailedException.class,
            () -> providerFor(mockServer.baseUrl())
                .generateStreaming(ModelTask.CHAT_AGENT, "sys", "usr", chunks::add));

        assertFalse(failure instanceof LlmCallFailedException.ProviderUnreachableException,
            "a frame the endpoint did send and the parser rejects is an application failure");
        assertEquals(List.of("real"), chunks,
            "only real deltas reach the consumer — the malformed frame emits nothing synthetic");
    }

    @Test
    void unparseableFrameFailsTheCall() throws Exception {
        mockServer = new SseMockServer(os -> os.write(SseMockServer.sseFrames(
            "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"x\"}}",
            "this is not json")));
        List<String> chunks = new CopyOnWriteArrayList<>();

        assertThrows(LlmCallFailedException.class,
            () -> providerFor(mockServer.baseUrl())
                .generateStreaming(ModelTask.CHAT_AGENT, "sys", "usr", chunks::add));
        assertEquals(List.of("x"), chunks);
    }

    @Test
    void frameWithoutTypeDiscriminatorFailsTheCall() throws Exception {
        mockServer = new SseMockServer(os -> os.write(SseMockServer.sseFrames(
            "{\"choices\":[{\"delta\":{\"content\":\"wrong dialect\"}}]}")));
        List<String> chunks = new CopyOnWriteArrayList<>();

        assertThrows(LlmCallFailedException.class,
            () -> providerFor(mockServer.baseUrl())
                .generateStreaming(ModelTask.CHAT_AGENT, "sys", "usr", chunks::add));
        assertEquals(List.of(), chunks,
            "a typeless frame is malformed for this dialect — no chunk, no synthetic text");
    }

    @Test
    void supportsStreamingIsTrueForEveryTask() {
        mockServer = null;
        AnthropicProvider provider = providerFor("http://localhost:9");
        for (ModelTask task : ModelTask.values()) {
            assertTrue(provider.supportsStreaming(task),
                "the Anthropic SSE dialect serves " + task);
        }
    }
}
