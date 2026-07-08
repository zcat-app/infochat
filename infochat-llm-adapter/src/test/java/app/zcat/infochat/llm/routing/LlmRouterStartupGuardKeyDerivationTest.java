package app.zcat.infochat.llm.routing;

import app.zcat.infochat.llm.ModelTask;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the guard's {@link ModelTask#keySegment()}-derived config-key
 * surface against the hand-spelled operator-facing literals for every
 * task, so a {@code keySegment} change cannot silently rename a key
 * the local-only scan inspects.
 */
class LlmRouterStartupGuardKeyDerivationTest {

    private static final Map<ModelTask, String> EXPECTED_SEGMENTS = Map.of(
        ModelTask.SECURITY_JUDGE, "security",
        ModelTask.TAGGER, "tagger",
        ModelTask.ENTITY, "entity",
        ModelTask.CLASSIFIER, "classifier",
        ModelTask.SUMMARIZER, "summarizer",
        ModelTask.CHAT_AGENT, "chat",
        ModelTask.TRANSLATOR, "translator");

    @Test
    void derivedPerTaskKeysMatchKeySegmentForEveryModelTask() {
        assertEquals(EXPECTED_SEGMENTS.size(), ModelTask.values().length,
            "every ModelTask must have an expected key segment pinned here");
        for (ModelTask task : ModelTask.values()) {
            String segment = EXPECTED_SEGMENTS.get(task);
            assertEquals("infochat.llm." + segment + ".base-url",
                LlmRouterStartupGuard.baseUrlKeyFor(task),
                "derived base-url key must match the operator-facing literal for " + task);
            assertEquals("infochat.llm." + segment + ".provider",
                LlmRouterStartupGuard.providerKeyFor(task),
                "derived provider key must match the operator-facing literal for " + task);
        }
    }

    @Test
    void languagesKeyMatchesPerProviderLiteral() {
        assertEquals("infochat.llm.anthropic.languages",
            LlmRouterStartupGuard.languagesKeyFor("anthropic"),
            "derived languages key must match the operator-facing per-provider literal");
    }
}
