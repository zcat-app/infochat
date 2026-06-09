package app.zcat.infochat.provider.command;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;

/**
 * {@link LlmProvider} test double for compress tests. The succeeding
 * variant runs a callback while the LLM call is "in flight" so a test
 * can simulate concurrent activity (e.g. another worker persisting a
 * chat turn); the failing variant throws, exercising the compression
 * failure path deterministically.
 */
class StubCompressLlmProvider implements LlmProvider {

    private final Runnable onGenerate;
    private final boolean failOnGenerate;
    private final String responseText;

    static StubCompressLlmProvider failing() {
        return new StubCompressLlmProvider(() -> { }, true, "");
    }

    static StubCompressLlmProvider succeeding(String responseText, Runnable onGenerate) {
        return new StubCompressLlmProvider(onGenerate, false, responseText);
    }

    private StubCompressLlmProvider(Runnable onGenerate, boolean failOnGenerate,
                                    String responseText) {
        this.onGenerate = onGenerate;
        this.failOnGenerate = failOnGenerate;
        this.responseText = responseText;
    }

    @Override
    public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
        if (failOnGenerate) {
            throw new RuntimeException("LLM unreachable (StubCompressLlmProvider)");
        }
        onGenerate.run();
        return new LlmResponse(responseText);
    }
}
