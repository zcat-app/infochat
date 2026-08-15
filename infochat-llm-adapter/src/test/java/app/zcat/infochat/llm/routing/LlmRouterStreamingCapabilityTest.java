package app.zcat.infochat.llm.routing;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The router's streaming-capability surface: the resolved provider's
 * explicit signal is exposed (at minimum for CHAT_AGENT, the task whose
 * consumer will gate on it), a provider that cannot stream reports
 * exactly that, and the startup scan evaluates the signal through the
 * live resolution path so a broken one fails boot, not the first call.
 */
class LlmRouterStreamingCapabilityTest {

    private static LlmRouter routerFor(LlmProvider provider) {
        return new LlmRouter(
            List.of(new LlmRouter.Entry(provider.providerName(), provider, Set.of("en"))),
            LlmRouter.ConfigReader.fromMap(java.util.Map.of()));
    }

    @Test
    void resolvedStreamingProviderCapabilityIsExposed() {
        LlmRouter router = routerFor(new StreamingStubProvider(true, null));

        assertTrue(router.streamingSupportedFor(ModelTask.CHAT_AGENT, null),
            "a provider that streams reports it through the router");
    }

    @Test
    void providerThatCannotStreamReportsItExplicitly() {
        // Default signal: a provider that never overrides the streaming
        // members reports cannot-stream.
        assertFalse(routerFor(new PlainProvider()).streamingSupportedFor(ModelTask.CHAT_AGENT, null),
            "a provider without streaming support reports cannot-stream by its default signal");
        // Overridden signal: a provider that declines one task.
        assertFalse(routerFor(new StreamingStubProvider(false, null))
            .streamingSupportedFor(ModelTask.CHAT_AGENT, null),
            "a provider may explicitly decline streaming for a task");
    }

    @Test
    void startupScanEvaluatesTheChatStreamingSignalThroughTheResolutionPath() {
        // A broken capability signal (one that throws) must fail boot at
        // the scan, exactly as a per-task config resolution failure does —
        // not surface at the first live streaming call.
        LlmRouter router = routerFor(new StreamingStubProvider(true,
            new IllegalStateException("broken capability signal")));

        RuntimeException failure = assertThrows(RuntimeException.class, router::assertAllTasksResolve);
        assertEquals("broken capability signal", failure.getMessage(),
            "the scan must surface the broken signal itself, not a wrapper");
    }

    @Test
    void startupScanAcceptsANonStreamingChatProvider() {
        // Cannot-stream is a supported posture, not a boot failure: the
        // deployment degrades to non-streaming for that task.
        LlmRouter router = routerFor(new PlainProvider());

        assertDoesNotThrow(router::assertAllTasksResolve);
        assertFalse(router.streamingSupportedFor(ModelTask.CHAT_AGENT, null));
    }

    private static final class StreamingStubProvider implements LlmProvider {
        private final boolean streams;
        private final RuntimeException capabilityFailure;

        StreamingStubProvider(boolean streams, RuntimeException capabilityFailure) {
            this.streams = streams;
            this.capabilityFailure = capabilityFailure;
        }

        @Override
        public String providerName() {
            return "streaming-stub";
        }

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            return new LlmResponse("unused");
        }

        @Override
        public boolean supportsStreaming(ModelTask task) {
            if (capabilityFailure != null) {
                throw capabilityFailure;
            }
            return streams;
        }

        @Override
        public LlmResponse generateStreaming(ModelTask task, String systemPrompt, String userPrompt,
                                             Consumer<String> chunkConsumer) {
            chunkConsumer.accept("unused");
            return new LlmResponse("unused");
        }
    }

    private static final class PlainProvider implements LlmProvider {
        @Override
        public String providerName() {
            return "plain-stub";
        }

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            return new LlmResponse("ok");
        }
    }
}
