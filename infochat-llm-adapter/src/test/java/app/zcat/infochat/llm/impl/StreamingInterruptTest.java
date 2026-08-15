package app.zcat.infochat.llm.impl;

import app.zcat.infochat.llm.ModelTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interrupt posture on the streaming read (the M1-763 lesson: armed
 * interrupts against blocking I/O are a live hazard on virtual
 * threads). A stream read blocked on a stalled endpoint must abort
 * when its thread is interrupted — no hang, the failure surfaces as
 * the call-failed family, and the interrupt flag stays armed for the
 * cancellation machinery that owns it.
 */
class StreamingInterruptTest {

    private SseMockServer mockServer;

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.close();
        }
    }

    @Test
    void interruptedVirtualThreadAbortsTheReadWithoutHanging() throws Exception {
        mockServer = new SseMockServer(os -> {
            os.write(SseMockServer.sseFrames(
                "{\"choices\":[{\"delta\":{\"content\":\"stalled\"}}]}"));
            os.flush();
            Thread.sleep(60_000);
        });
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(new StubConfig(Map.of(
            "infochat.llm.chat.base-url", mockServer.baseUrl(),
            "infochat.llm.chat.api-key", "",
            "infochat.llm.chat.model", "model-chat",
            "infochat.llm.chat.timeout-ms", "30000")));
        AtomicReference<Throwable> caught = new AtomicReference<>();
        AtomicBoolean flagStillArmed = new AtomicBoolean(false);
        List<String> chunks = new CopyOnWriteArrayList<>();

        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                provider.generateStreaming(ModelTask.CHAT_AGENT, "sys", "usr", chunks::add);
            } catch (Throwable t) {
                caught.set(t);
                flagStillArmed.set(Thread.currentThread().isInterrupted());
            }
        });
        Thread.sleep(500);
        assertTrue(worker.isAlive(), "the read must be blocked on the stalled stream");
        worker.interrupt();
        worker.join(5_000);

        assertFalse(worker.isAlive(), "the interrupted read must abort, not hang the worker");
        assertInstanceOf(LlmCallFailedException.class, caught.get(),
            "the abort surfaces as the call-failed family");
        assertTrue(caught.get().getMessage().contains("interrupted"),
            "the failure names the interrupt; got: " + caught.get().getMessage());
        assertTrue(flagStillArmed.get(),
            "the interrupt flag must stay armed after the abort — clearing it here would "
                + "re-arm the spend the cancellation exists to stop");
        assertEquals(List.of("stalled"), chunks);
    }
}
