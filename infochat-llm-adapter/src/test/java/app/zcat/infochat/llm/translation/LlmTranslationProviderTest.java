package app.zcat.infochat.llm.translation;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 (Shape A) tests for {@link LlmTranslationProvider}.
 * No {@code @QuarkusTest}; the provider's collaborators (LlmRouter,
 * LlmProvider) are stub-constructed via the router's test-friendly
 * constructor. The prompt resource is loaded from the production
 * classpath via {@code @PostConstruct}.
 */
class LlmTranslationProviderTest {

    private static final String PROVIDER_NAME = "test-translator";

    private RecordingLlmProvider stubProvider;
    private LlmTranslationProvider translator;

    @BeforeEach
    void setUp() {
        stubProvider = new RecordingLlmProvider("translated output");

        LlmRouter router = new LlmRouter(
                List.of(new LlmRouter.Entry(PROVIDER_NAME, stubProvider, Set.of("en", "cs"))),
                LlmRouter.ConfigReader.fromMap(Map.of(
                        LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER, PROVIDER_NAME)));

        translator = new LlmTranslationProvider();
        translator.llmRouter = router;
        translator.loadPromptTemplate();
    }

    @Test
    void translateReturnsTextUnchangedWhenFromEqualsTo() {
        String input = "Hello world";

        String result = translator.translate(input, Locale.ENGLISH, Locale.ENGLISH);

        assertSame(input, result, "en->en short-circuit returns the same String instance");
        assertEquals(0, stubProvider.callCount(),
                "en->en short-circuit must NOT invoke the LLM provider");
    }

    @Test
    void translateRoutesViaLlmRouterForTaskTRANSLATOR() {
        translator.translate("some text", Locale.ENGLISH, Locale.forLanguageTag("cs"));

        assertEquals(1, stubProvider.callCount(), "exactly one LLM call");
        assertEquals(ModelTask.TRANSLATOR, stubProvider.lastTask(),
                "the router must be consulted with ModelTask.TRANSLATOR");
    }

    @Test
    void translateInvokesLlmProviderWithPromptWrappedInUntrustedDelimiter() {
        String input = "This is the text to translate";

        translator.translate(input, Locale.ENGLISH, Locale.forLanguageTag("cs"));

        String prompt = stubProvider.lastUserPrompt();
        assertTrue(prompt.contains("<<<UNTRUSTED_CONTENT id=\""),
                "prompt must contain the UNTRUSTED_CONTENT open wrapper. Got: " + prompt);
        assertTrue(prompt.contains("<<<END id=\""),
                "prompt must contain the END close wrapper. Got: " + prompt);
        assertTrue(prompt.contains(input),
                "prompt must contain the source text. Got: " + prompt);

        // The open and close delimiter ids must match (same per-call UUID).
        int openStart = prompt.indexOf("<<<UNTRUSTED_CONTENT id=\"") + "<<<UNTRUSTED_CONTENT id=\"".length();
        int openEnd = prompt.indexOf("\">>>", openStart);
        String openId = prompt.substring(openStart, openEnd);

        int closeStart = prompt.indexOf("<<<END id=\"") + "<<<END id=\"".length();
        int closeEnd = prompt.indexOf("\">>>", closeStart);
        String closeId = prompt.substring(closeStart, closeEnd);

        assertEquals(openId, closeId, "open and close delimiter UUIDs must match");
    }

    @Test
    void translatePromptIncludesPreserveBackticksAndUidsInstruction() {
        translator.translate("some text", Locale.ENGLISH, Locale.forLanguageTag("cs"));

        String prompt = stubProvider.lastUserPrompt();
        assertTrue(prompt.contains("backtick"),
                "prompt must instruct to preserve backticks. Got: " + prompt);
        assertTrue(prompt.contains("p-"),
                "prompt must mention post UID pattern p-. Got: " + prompt);
        assertTrue(prompt.contains("t-"),
                "prompt must mention post UID pattern t-. Got: " + prompt);
        assertTrue(prompt.contains("URL"),
                "prompt must instruct to preserve URLs. Got: " + prompt);
    }

    @Test
    void translateReturnsLlmProviderResponseBodyVerbatim() {
        String expectedResponse = "Přeložený text s <speciálními> znaky & symboly";
        stubProvider.setResponseText(expectedResponse);

        String result = translator.translate("text to translate",
                Locale.ENGLISH, Locale.forLanguageTag("cs"));

        assertEquals(expectedResponse, result,
                "translate must return the LLM response body verbatim — no sanitizer pass at this layer");
    }

    // -- test stubs --

    /**
     * Records calls to {@link LlmProvider#generate} so test scenarios
     * can assert routing, prompt shape, and call count.
     */
    static final class RecordingLlmProvider implements LlmProvider {
        private final AtomicInteger callCount = new AtomicInteger();
        private final AtomicReference<ModelTask> lastTask = new AtomicReference<>();
        private final AtomicReference<String> lastUserPrompt = new AtomicReference<>();
        private volatile String responseText;

        RecordingLlmProvider(String responseText) {
            this.responseText = responseText;
        }

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            callCount.incrementAndGet();
            lastTask.set(task);
            lastUserPrompt.set(userPrompt);
            return new LlmResponse(responseText);
        }

        void setResponseText(String text) {
            this.responseText = text;
        }

        int callCount() {
            return callCount.get();
        }

        ModelTask lastTask() {
            return lastTask.get();
        }

        String lastUserPrompt() {
            return lastUserPrompt.get();
        }
    }
}
