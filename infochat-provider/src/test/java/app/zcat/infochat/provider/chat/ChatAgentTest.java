package app.zcat.infochat.provider.chat;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmRouter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.translation.TranslationPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatAgentTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SCOPE_ID = UUID.randomUUID();
    private static final String SCOPE_KIND = "dm";

    private InFlightTracker inFlightTracker;
    private StubLlmProvider llmProvider;
    private int promptBuilderCalls;
    private int sanitizerCalls;
    private String sanitizerLastInput;
    private String sanitizerOutput;
    private int translationCalls;
    private String translationLastLanguage;
    private int sessionPersistCalls;
    private final List<String> persistedRoles = new ArrayList<>();
    private int dispatcherCalls;
    private String dispatcherLastToolName;
    private TestChatAgent agent;

    @BeforeEach
    void setUp() {
        inFlightTracker = new InFlightTracker();
        llmProvider = new StubLlmProvider();
        promptBuilderCalls = 0;
        sanitizerCalls = 0;
        sanitizerOutput = null;
        translationCalls = 0;
        sessionPersistCalls = 0;
        persistedRoles.clear();
        dispatcherCalls = 0;

        agent = buildAgent("en");
    }

    @Test
    void orchestrationSequenceIsCorrect() {
        llmProvider.responses.add(new LlmResponse("Hello, how can I help?"));

        String reply = agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, "hi");

        assertEquals(1, promptBuilderCalls, "prompt builder should be called once");
        assertEquals(1, llmProvider.callCount, "LLM should be called once");
        assertEquals(0, dispatcherCalls, "no tool calls in response");
        assertEquals(2, sessionPersistCalls, "user + assistant turns persisted");
        assertEquals("user", persistedRoles.get(0));
        assertEquals("assistant", persistedRoles.get(1));
        assertEquals(1, sanitizerCalls, "sanitizer should be called once");
        assertEquals(0, translationCalls, "no translation for en scope");
        assertEquals("Hello, how can I help?", reply);
    }

    @Test
    void llmUnreachableReturnsFriendlyError() {
        llmProvider.throwOnGenerate = true;

        String reply = agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, "hi");

        assertEquals(BundleKeys.ERROR_CHAT_UNAVAILABLE, reply);
        assertEquals(0, sessionPersistCalls, "no session persistence on LLM failure");
        assertEquals(0, dispatcherCalls, "no tool dispatch on LLM failure");
        assertFalse(inFlightTracker.isInFlight(USER_ID, SCOPE_KIND, SCOPE_ID),
                "in-flight slot must be released");
    }

    @Test
    void outputPassesThroughSanitizer() {
        sanitizerOutput = "[redacted command]";
        llmProvider.responses.add(new LlmResponse("Try /ban user123"));

        String reply = agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, "help me");

        assertEquals(1, sanitizerCalls);
        assertEquals("Try /ban user123", sanitizerLastInput);
        assertEquals("[redacted command]", reply);
    }

    @Test
    void translationRunsWhenScopeLanguageIsNonEn() {
        agent = buildAgent("cs");
        llmProvider.responses.add(new LlmResponse("Hello"));

        String reply = agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, "ahoj");

        assertEquals(1, translationCalls);
        assertEquals("cs", translationLastLanguage);
        assertEquals("translated:Hello", reply);
    }

    @Test
    void translationSkippedWhenScopeLanguageIsEn() {
        llmProvider.responses.add(new LlmResponse("Hello"));

        agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, "hi");

        assertEquals(0, translationCalls);
    }

    @Test
    void inFlightRejectionReturnsBundleError() {
        inFlightTracker.tryAcquire(USER_ID, SCOPE_KIND, SCOPE_ID);

        String reply = agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, "hi");

        assertEquals(BundleKeys.ERROR_CHAT_IN_FLIGHT, reply);
        assertEquals(0, llmProvider.callCount);
    }

    @Test
    void toolCallDispatchesAndLoops() {
        llmProvider.responses.add(
                new LlmResponse("TOOL_CALL: searchPosts {\"query\": \"bitcoin\", \"limit\": 5}"));
        llmProvider.responses.add(
                new LlmResponse("I found 3 posts about bitcoin."));

        String reply = agent.handle(USER_ID, SCOPE_KIND, SCOPE_ID, "find bitcoin posts");

        assertEquals(1, dispatcherCalls);
        assertEquals("searchPosts", dispatcherLastToolName);
        assertEquals(2, llmProvider.callCount);
        assertEquals("I found 3 posts about bitcoin.", reply);
    }

    @Test
    void parseToolArgsHandlesSimpleJson() {
        var args = ChatAgent.parseToolArgs("{\"query\": \"test\", \"limit\": 10}");
        assertEquals("test", args.get("query"));
        assertEquals(10, args.get("limit"));
    }

    @Test
    void parseToolArgsHandlesEmptyJson() {
        var args = ChatAgent.parseToolArgs("{}");
        assertTrue(args.isEmpty());
    }

    // --- factory + test subclass ---

    private TestChatAgent buildAgent(String language) {
        ChatPromptBuilder promptBuilder = new ChatPromptBuilder(
                new ChatMemoryPreFetcher() {
                    @Override
                    public List<ChatMemoryPreFetcher.MemoryHit> preFetch(
                            UUID u, String sk, UUID si, String q) {
                        return List.of();
                    }
                }) {
            @Override
            public BuiltPrompt build(UUID u, String sk, UUID si, String msg) {
                promptBuilderCalls++;
                return new BuiltPrompt("system", msg, "marker");
            }
        };

        Map<String, ChatToolRegistry.ChatTool> noOpTools = new HashMap<>();
        for (String name : new ChatToolRegistry().toolNames()) {
            noOpTools.put(name, (u, sk, si, a) -> "[]");
        }
        ChatToolDispatcher dispatcher = new ChatToolDispatcher(
                new ChatToolRegistry(), noOpTools, 500, 200, 20) {
            @Override
            public ToolResult dispatch(String toolName, Map<String, Object> args,
                                        UUID userId, String scopeKind, UUID scopeId,
                                        TurnContext turn) {
                dispatcherCalls++;
                dispatcherLastToolName = toolName;
                return new ToolResult.Success("[{\"title\": \"test\"}]");
            }
        };

        ChatSessionRepository sessionRepo = new ChatSessionRepository(null) {
            @Override
            public int persistTurn(UUID u, String sk, UUID si,
                                    String role, String content, int tokens) {
                sessionPersistCalls++;
                persistedRoles.add(role);
                return sessionPersistCalls - 1;
            }
        };

        LlmRouter router = new LlmRouter(
                List.of(new LlmRouter.Entry("test", llmProvider, Set.of("en"))),
                key -> Optional.empty()) {
            @Override
            public LlmProvider forTask(ModelTask task, String lang) {
                return llmProvider;
            }
        };

        LlmOutputSanitizer sanitizer = new LlmOutputSanitizer() {
            @Override
            public String sanitize(String input) {
                sanitizerCalls++;
                sanitizerLastInput = input;
                return sanitizerOutput != null ? sanitizerOutput : input;
            }
        };

        TranslationPipeline pipeline = new TranslationPipeline() {
            @Override
            public String run(String text, String scopeLanguage) {
                translationCalls++;
                translationLastLanguage = scopeLanguage;
                return "translated:" + text;
            }
        };

        BundleLoader bundle = new BundleLoader() {
            @Override public String get(String key) { return key; }
            @Override public String get(String key, String langCode) { return key; }
        };

        // No-op trigger that never fires (threshold unreachable)
        AutoCompressTrigger noopTrigger = new AutoCompressTrigger(
                Integer.MAX_VALUE, bundle, null, null) {
            @Override
            public java.util.Optional<String> checkAndCompress(
                    UUID u, String sk, UUID si, String sl) {
                return java.util.Optional.empty();
            }
        };

        return new TestChatAgent(
                inFlightTracker, promptBuilder, dispatcher, sessionRepo,
                router, sanitizer, pipeline, bundle, noopTrigger, language);
    }

    // Subclass that overrides readScopeLanguage so no JDBC is needed
    static class TestChatAgent extends ChatAgent {
        private final String language;

        TestChatAgent(InFlightTracker tracker, ChatPromptBuilder builder,
                      ChatToolDispatcher dispatcher, ChatSessionRepository repo,
                      LlmRouter router, LlmOutputSanitizer sanitizer,
                      TranslationPipeline pipeline, BundleLoader bundle,
                      AutoCompressTrigger autoCompressTrigger,
                      String language) {
            super(tracker, builder, dispatcher, repo, router,
                    sanitizer, pipeline, bundle, autoCompressTrigger, null);
            this.language = language;
        }

        @Override
        String readScopeLanguage(String scopeKind, UUID scopeId) {
            return language;
        }
    }

    static class StubLlmProvider implements LlmProvider {
        final List<LlmResponse> responses = new ArrayList<>();
        int callCount;
        boolean throwOnGenerate;

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            if (throwOnGenerate) {
                throw new RuntimeException("LLM unreachable");
            }
            callCount++;
            if (callCount <= responses.size()) {
                return responses.get(callCount - 1);
            }
            return new LlmResponse("default response");
        }
    }
}
