package app.zcat.infochat.llm.metrics;

import app.zcat.infochat.llm.LlmCallBudget;
import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.impl.LlmCallFailedException;
import app.zcat.infochat.llm.routing.LlmCircuitBreakerRegistry;
import app.zcat.infochat.llm.routing.LlmRouter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decorator chain over the tools-bearing shape — composed as {@link BudgetedLlmProviderTest}.
 */
class LlmProviderToolCallDecoratorTest {

    private static final List<LlmProvider.ToolDeclaration> DECLARATIONS = List.of(
        new LlmProvider.ToolDeclaration("searchPosts", "search posts",
            "{\"type\":\"object\",\"properties\":{\"tags\":{\"type\":\"array\","
                + "\"items\":{\"type\":\"string\"}}}}"));

    private SimpleMeterRegistry registry;
    private ToolsStubProvider stub;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        stub = new ToolsStubProvider();
    }

    private MeteredLlmProvider metered() {
        return new MeteredLlmProvider(stub, new LlmMetrics(registry),
            LlmRouter.ConfigReader.fromMap(Map.of(
                ModelTask.CHAT_AGENT.configPrefix() + "model", "operator-model")));
    }

    @Test
    void meteredForwardsTheShapeAndRecordsWithTheOperatorModelLabel() {
        stub.response = new LlmResponse("", null,
            new LlmResponse.TokenUsage(10, 5),
            List.of(new LlmResponse.ToolCallRequest("searchPosts", "{\"tags\":[]}")),
            "tool_calls");

        LlmResponse response = metered().generateWithTools(
            ModelTask.CHAT_AGENT, "sys", "usr", DECLARATIONS);

        assertSame(stub.response, response);
        assertEquals(1, stub.toolCalls, "the shape must reach the delegate");
        assertSame(DECLARATIONS, stub.lastDeclarations, "declarations forward verbatim");
        assertEquals(1.0, registry.get("llm.calls.total")
            .tags("task", "chat", "provider", "tools-stub", "model", "operator-model",
                "outcome", "ok")
            .counter().count());
        assertEquals(10.0, registry.get("llm.tokens.in")
            .tags("task", "chat", "model", "operator-model").counter().count());
        assertEquals(5.0, registry.get("llm.tokens.out")
            .tags("task", "chat", "model", "operator-model").counter().count());
    }

    @Test
    void meteredFailsTheOutcomeWhenTheDelegateThrows() {
        stub.failure = new LlmCallFailedException("endpoint rejected the tools field");

        assertThrows(LlmCallFailedException.class,
            () -> metered().generateWithTools(ModelTask.CHAT_AGENT, "sys", "usr", DECLARATIONS));

        assertEquals(1.0, registry.get("llm.calls.total")
            .tags("task", "chat", "provider", "tools-stub", "model", "unknown",
                "outcome", "fail")
            .counter().count());
    }

    @Test
    void breakerClassifiesTransportVersusApplicationOnTheToolsShape() {
        LlmCircuitBreakerRegistry breakers = new LlmCircuitBreakerRegistry(1, 60_000,
            Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC),
            LlmRouter.ConfigReader.fromMap(Map.of(
                ModelTask.CHAT_AGENT.baseUrlKey(), "http://tools-endpoint")));

        stub.failure = new LlmCallFailedException("4xx rejecting the tools field");
        CircuitBreakingLlmProvider breaker = new CircuitBreakingLlmProvider(stub, breakers);
        assertThrows(LlmCallFailedException.class,
            () -> breaker.generateWithTools(ModelTask.CHAT_AGENT, "sys", "usr", DECLARATIONS));
        assertFalse(breakers.wouldShortCircuit(ModelTask.CHAT_AGENT),
            "an endpoint that answered 4xx proved reachability — no trip");

        stub.failure = new LlmCallFailedException.ProviderUnreachableException("unreachable");
        stub.toolCalls = 0;
        assertThrows(LlmCallFailedException.ProviderUnreachableException.class,
            () -> breaker.generateWithTools(ModelTask.CHAT_AGENT, "sys", "usr", DECLARATIONS));
        assertTrue(breakers.wouldShortCircuit(ModelTask.CHAT_AGENT),
            "a transport failure on the tools shape trips the breaker");

        stub.failure = null;
        stub.toolCalls = 0;
        assertThrows(LlmCallFailedException.ProviderUnreachableException.class,
            () -> breaker.generateWithTools(ModelTask.CHAT_AGENT, "sys", "usr", DECLARATIONS),
            "the OPEN breaker short-circuits the tools shape with the typed signal");
        assertEquals(0, stub.toolCalls,
            "the short-circuit issues no tools-bearing attempt");
    }

    @Test
    void budgetRefusesTheToolsShapeBeforeTheAttempt() {
        BudgetedLlmProvider budgeted = new BudgetedLlmProvider(stub);
        CountingBudget budget = new CountingBudget();
        budget.exhausted = true;

        LlmCallBudget.callWith(budget, () -> assertThrows(
            LlmCallBudget.RefusedException.class,
            () -> budgeted.generateWithTools(ModelTask.CHAT_AGENT, "sys", "usr", DECLARATIONS)));
        assertEquals(0, stub.toolCalls,
            "a refused draw never reaches the delegate");

        budgeted.generateWithTools(ModelTask.CHAT_AGENT, "sys", "usr", DECLARATIONS);
        assertEquals(1, stub.toolCalls,
            "with nothing bound the decorator is a pass-through");
    }

    @Test
    void supportsToolCallsForwardsAndTheDefaultRefusesLoudly() {
        assertTrue(metered().supportsToolCalls(ModelTask.CHAT_AGENT),
            "the signal forwards through the chain");
        assertFalse(new MeteredLlmProvider(new PlainProvider(), new LlmMetrics(registry),
            key -> java.util.Optional.empty()).supportsToolCalls(ModelTask.CHAT_AGENT));
        UnsupportedOperationException refusal = assertThrows(
            UnsupportedOperationException.class,
            () -> new MeteredLlmProvider(new PlainProvider(), new LlmMetrics(registry),
                key -> java.util.Optional.empty())
                .generateWithTools(ModelTask.CHAT_AGENT, "sys", "usr", DECLARATIONS));
        assertTrue(refusal.getMessage().contains("does not support tool calls"),
            "the refusal names the posture; got: " + refusal.getMessage());
    }

    private static final class ToolsStubProvider implements LlmProvider {
        LlmResponse response = new LlmResponse("done");
        RuntimeException failure;
        int toolCalls;
        List<LlmProvider.ToolDeclaration> lastDeclarations;

        @Override
        public String providerName() {
            return "tools-stub";
        }

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            return new LlmResponse("unused");
        }

        @Override
        public boolean supportsToolCalls(ModelTask task) {
            return true;
        }

        @Override
        public LlmResponse generateWithTools(ModelTask task, String systemPrompt,
                                              String userPrompt,
                                              List<LlmProvider.ToolDeclaration> tools) {
            toolCalls++;
            lastDeclarations = tools;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }

    private static final class PlainProvider implements LlmProvider {
        @Override
        public String providerName() {
            return "plain";
        }

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            return new LlmResponse("ok");
        }
    }

    private static final class CountingBudget implements LlmCallBudget {
        boolean exhausted;
        int draws;

        @Override
        public boolean tryDraw() {
            if (exhausted) {
                return false;
            }
            draws++;
            return true;
        }
    }
}
