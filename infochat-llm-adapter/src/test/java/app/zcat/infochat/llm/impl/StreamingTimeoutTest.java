package app.zcat.infochat.llm.impl;

import app.zcat.infochat.llm.ModelTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-task timeout bounds the WHOLE streaming call: not just the
 * exchange up to headers (the request-level timeout) but every
 * inter-chunk read after them. A stall anywhere fires the read
 * timeout as the transport class the breaker trips on.
 */
class StreamingTimeoutTest {

    private SseMockServer mockServer;

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.close();
        }
    }

    @Test
    void interChunkStallTripsTheReadTimeoutAsTransport() throws Exception {
        mockServer = new SseMockServer(os -> {
            os.write(SseMockServer.sseFrames(
                "{\"choices\":[{\"delta\":{\"content\":\"first\"}}]}"));
            os.flush();
            // Stall: never another byte. The client's whole-call deadline
            // must fire while blocked waiting for the next line.
            Thread.sleep(60_000);
        });
        List<String> chunks = new CopyOnWriteArrayList<>();
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(new StubConfig(Map.of(
            "infochat.llm.chat.base-url", mockServer.baseUrl(),
            "infochat.llm.chat.api-key", "",
            "infochat.llm.chat.model", "model-chat",
            "infochat.llm.chat.timeout-ms", "600")));
        long start = System.nanoTime();

        LlmCallFailedException failure = assertThrows(LlmCallFailedException.class,
            () -> provider.generateStreaming(ModelTask.CHAT_AGENT, "sys", "usr", chunks::add));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 5_000,
            "the whole-call deadline must fire near the configured timeout; took " + elapsedMs + " ms");
        assertInstanceOf(LlmCallFailedException.ProviderUnreachableException.class, failure,
            "a read timeout is transport-class evidence the breaker trips on");
        assertInstanceOf(java.net.http.HttpTimeoutException.class, failure.getCause(),
            "the failure carries the read-timeout cause");
        assertEquals(List.of("first"), chunks,
            "the pre-stall chunk stays delivered; nothing synthetic follows the timeout");
    }
}
