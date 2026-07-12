package app.zcat.infochat.provider.testing;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared test seam for the {@link LlmProvider} contract. Mirrors
 * the collector-side {@code StubLlmProvider} pattern:
 * {@code @Alternative @Priority(Integer.MAX_VALUE)} globally enables
 * this bean as THE active {@link LlmProvider} across every
 * {@code @QuarkusTest} on the Provider test classpath, suppressing
 * the production {@code OpenAiCompatibleProvider}.
 *
 * <p><b>All mutable state is exposed via methods, not public fields.</b>
 * {@code @ApplicationScoped} beans are accessed through Quarkus ArC
 * client proxies; the proxy is a SUBCLASS of this class whose own
 * {@code final} fields are initialized by {@code super()} and shadow
 * the bean's. Reading {@code mock.callCount} via a proxy reference
 * returns the proxy subclass's (always-zero) field, not the bean's.
 * Reading via {@link #callCount()} delegates to the bean and returns
 * the real value. Same rule applies to writers like
 * {@link #setResponseText(String)} — direct {@code mock.responseText.set(...)}
 * would update the proxy's field, and the bean would keep returning
 * its own default.
 */
@Alternative
@Priority(Integer.MAX_VALUE)
@ApplicationScoped
public class TestLlmProvider implements LlmProvider {

    private final AtomicInteger callCount = new AtomicInteger();
    private final AtomicReference<String> responseText =
            new AtomicReference<>("default test summary");
    // Optional per-TRANSLATOR-task override. Null (the default) means "fall
    // through to responseText", so every existing caller keeps its single-
    // response behavior. A test that exercises the translation pipeline sets
    // this so the translator's output differs from the summarizer prose fed
    // into it — otherwise the two collapse to one sentinel and the pipeline's
    // byte-identical-output sanity check (M1-437 condition b) treats the
    // result as a fallback.
    private final AtomicReference<String> translatorResponseText =
            new AtomicReference<>(null);
    private final AtomicBoolean throwOnCall = new AtomicBoolean(false);
    // Optional mid-turn action, run on every generate() call before the
    // canned response is returned. Lets an IT mutate transport state at the
    // one point that is guaranteed to sit between the D31 placeholder
    // acquisition and the terminal finalize of a chat turn (M1-607) — e.g.
    // killing the adapter's message handles to force a PERMANENT finalize
    // failure, simulating "the channel died while the LLM was generating".
    private final AtomicReference<Runnable> onGenerate = new AtomicReference<>(null);

    @Override
    public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
        callCount.incrementAndGet();
        Runnable midTurnAction = onGenerate.get();
        if (midTurnAction != null) {
            midTurnAction.run();
        }
        if (throwOnCall.get()) {
            throw new RuntimeException("LLM unreachable (TestLlmProvider stub)");
        }
        if (task == ModelTask.TRANSLATOR) {
            String translatorOverride = translatorResponseText.get();
            if (translatorOverride != null) {
                return new LlmResponse(translatorOverride);
            }
        }
        return new LlmResponse(responseText.get());
    }

    public int callCount() {
        return callCount.get();
    }

    public void setResponseText(String text) {
        responseText.set(text);
    }

    /**
     * Set the response returned for {@link ModelTask#TRANSLATOR} calls only.
     * Leaving it unset (the reset default) makes translator calls fall
     * through to {@link #setResponseText(String)}, preserving the single-
     * response behavior every other caller relies on.
     */
    public void setTranslatorResponseText(String text) {
        translatorResponseText.set(text);
    }

    public void setThrowOnCall(boolean shouldThrow) {
        throwOnCall.set(shouldThrow);
    }

    /**
     * Set an action to run inside every {@code generate()} call, before the
     * canned response is returned. Leaving it unset (the reset default)
     * keeps generate() side-effect-free for every other caller.
     */
    public void setOnGenerate(Runnable action) {
        onGenerate.set(action);
    }

    public void reset() {
        callCount.set(0);
        responseText.set("default test summary");
        translatorResponseText.set(null);
        throwOnCall.set(false);
        onGenerate.set(null);
    }
}
