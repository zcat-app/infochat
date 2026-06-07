package app.zcat.infochat.llm.routing;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link LlmRouter.Entry}'s construction contract: the canonical
 * constructor accepts a null {@code supportedLanguages} ("no declared
 * languages") and normalizes it to the empty set, so the accessor —
 * and therefore the router's language-capability read — never sees
 * null.
 */
class LlmRouterEntryTest {

    @Test
    void entryConstructedWithNullSupportedLanguagesYieldsEmptySet() {
        LlmRouter.Entry entry = new LlmRouter.Entry("stub", new StubProvider(), null);

        assertEquals(Set.of(), entry.supportedLanguages(),
            "null supportedLanguages must normalize to the empty set");
    }

    private static final class StubProvider implements LlmProvider {
        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            throw new UnsupportedOperationException(
                "StubProvider.generate must not be invoked by Entry construction tests");
        }
    }
}
