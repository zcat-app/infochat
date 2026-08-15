package app.zcat.infochat.llm.impl;

import app.zcat.infochat.llm.ModelTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The accumulated-body cap on the streaming path (trust boundary 9:
 * everything the endpoint returns is endpoint-chosen input). A stream
 * past the operator-configured cap is cut at the cap and the partial
 * body discarded — the call fails, no assembled result ships, and the
 * failure is application-class: an over-cap body proves the endpoint
 * answered, so the breaker must not trip.
 */
class StreamingBodyCapTest {

    private SseMockServer mockServer;

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.close();
        }
    }

    @Test
    void overCapStreamIsCutAtTheCapAndDiscarded() throws Exception {
        // 1 MiB is the clamp floor; the fake endpoint streams past it.
        mockServer = new SseMockServer(os -> {
            byte[] frame = SseMockServer.sseFrames(
                "{\"choices\":[{\"delta\":{\"content\":\"0123456789\"}}]}");
            // ~53 bytes/frame: 30_000 frames is ~1.6 MiB, past the 1 MiB cap.
            for (int i = 0; i < 30_000; i++) {
                os.write(frame);
            }
            os.write(SseMockServer.sseFrames("[DONE]"));
        });
        List<String> chunks = new CopyOnWriteArrayList<>();
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(new StubConfig(Map.of(
            "infochat.llm.chat.base-url", mockServer.baseUrl(),
            "infochat.llm.chat.api-key", "",
            "infochat.llm.chat.model", "model-chat",
            "infochat.llm.chat.timeout-ms", "30000",
            "infochat.llm.max-response-bytes", "1048576")));

        LlmCallFailedException failure = assertThrows(LlmCallFailedException.class,
            () -> provider.generateStreaming(ModelTask.CHAT_AGENT, "sys", "usr", chunks::add));

        assertFalse(failure instanceof LlmCallFailedException.ProviderUnreachableException,
            "an over-cap body proves the endpoint answered — application class, breaker untipped");
        assertTrue(failure.getMessage().contains("cap"),
            "the failure must name the cap; got: " + failure.getMessage());
        long deliveredBytes = chunks.size() * 10L;
        assertTrue(deliveredBytes < 1024 * 1024,
            "the consumer holds only the pre-cap prefix, never the whole body");
    }

    @Test
    void streamUnderTheCapCompletesNormally() throws Exception {
        mockServer = new SseMockServer(os -> os.write(SseMockServer.sseFrames(
            "{\"choices\":[{\"delta\":{\"content\":\"fits\"}}]}",
            "[DONE]")));
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(new StubConfig(Map.of(
            "infochat.llm.chat.base-url", mockServer.baseUrl(),
            "infochat.llm.chat.api-key", "",
            "infochat.llm.chat.model", "model-chat",
            "infochat.llm.chat.timeout-ms", "5000",
            "infochat.llm.max-response-bytes", "1048576")));
        List<String> chunks = new CopyOnWriteArrayList<>();

        String text = provider.generateStreaming(ModelTask.CHAT_AGENT, "sys", "usr", chunks::add).text();

        assertEquals("fits", text);
        assertEquals(List.of("fits"), chunks);
        byte[] oneFrame = SseMockServer.sseFrames(
            "{\"choices\":[{\"delta\":{\"content\":\"fits\"}}]}");
        assertTrue(oneFrame.length < 1024 * 1024);
        assertEquals(1, chunks.size(), "sanity: the fixture really is under the cap");
        assertTrue(new String(oneFrame, StandardCharsets.UTF_8).startsWith("data: "));
    }
}
