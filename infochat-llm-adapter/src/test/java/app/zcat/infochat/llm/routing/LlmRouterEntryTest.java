package app.zcat.infochat.llm.routing;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link LlmRouter.Entry}'s honest construction contract:
 * {@code supportedLanguages} is non-null (the empty set is the
 * legitimate "no declared languages" value) and is defensively
 * copied, so the accessor exposes exactly the codes the caller
 * supplied and the component stays immutable.
 */
class LlmRouterEntryTest {

    @Test
    void entryExposesSuppliedSupportedLanguages() {
        LlmRouter.Entry entry = new LlmRouter.Entry("stub", new StubProvider(), Set.of("en", "cs"));

        assertEquals(Set.of("en", "cs"), entry.supportedLanguages(),
            "the accessor must expose exactly the supplied language codes");
    }

    @Test
    void entryAcceptsEmptySupportedLanguagesAsNoDeclaredLanguages() {
        LlmRouter.Entry entry = new LlmRouter.Entry("stub", new StubProvider(), Set.of());

        assertEquals(Set.of(), entry.supportedLanguages(),
            "an empty set is the legitimate 'no declared languages' value");
    }

    private static final class StubProvider implements LlmProvider {
        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            throw new UnsupportedOperationException(
                "StubProvider.generate must not be invoked by Entry construction tests");
        }
    }
}
