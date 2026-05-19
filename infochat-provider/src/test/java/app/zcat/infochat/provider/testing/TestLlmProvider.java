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
    private final AtomicBoolean throwOnCall = new AtomicBoolean(false);

    @Override
    public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
        callCount.incrementAndGet();
        if (throwOnCall.get()) {
            throw new RuntimeException("LLM unreachable (TestLlmProvider stub)");
        }
        return new LlmResponse(responseText.get());
    }

    public int callCount() {
        return callCount.get();
    }

    public void setResponseText(String text) {
        responseText.set(text);
    }

    public void setThrowOnCall(boolean shouldThrow) {
        throwOnCall.set(shouldThrow);
    }

    public void reset() {
        callCount.set(0);
        responseText.set("default test summary");
        throwOnCall.set(false);
    }
}
