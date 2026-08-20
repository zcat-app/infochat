package app.zcat.infochat.provider.chat;

import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.llm.EmbeddingResult;
import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmCircuitBreakerRegistry;
import app.zcat.infochat.llm.routing.LlmRouter;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.messaging.HelpCommandHandler;
import app.zcat.infochat.provider.messaging.HelpCommandHandler.CallerTier;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.testsupport.SanitizerTestDoubles;
import app.zcat.infochat.provider.translation.TranslationPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reply-mode switch (decision D79, M1-848): translate mode is
 * today's behavior exactly (English-pinned generation + display leg);
 * native mode generates in the scope's declared /lang language, skips
 * the display leg, and persists the assistant turn raw. The mode
 * arrives on {@link InboundContext#replyMode()} resolved once at
 * intake — this class pins ChatAgent's mode-conditional behaviour
 * given that resolution.
 */
class ChatAgentReplyModeTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SCOPE_ID = UUID.randomUUID();
    private static final String SCOPE_KIND = "dm";
    private static final String CZECH_REPLY = "Dobrý den, jak vám mohu pomoci?";

    private InFlightTracker inFlightTracker;
    private StubLlmProvider llmProvider;
    private int translationCalls;
    private String translationLastLanguage;
    private final List<String> persistedRoles = new ArrayList<>();
    private final List<String> persistedTexts = new ArrayList<>();
    private int semanticSearchCalls;
    private String triggerTopicMatch;
    private String triggerIntentMatch;
    private Optional<String> composeUsageBlockResult = Optional.empty();
    private String sanitizerOverride;
    private TestChatAgent agent;

    @BeforeEach
    void setUp() {
        inFlightTracker = new InFlightTracker();
        llmProvider = new StubLlmProvider();
        translationCalls = 0;
        persistedRoles.clear();
        persistedTexts.clear();
        semanticSearchCalls = 0;
        triggerTopicMatch = null;
        triggerIntentMatch = null;
        composeUsageBlockResult = Optional.empty();
        sanitizerOverride = null;
    }

    @Test
    void aNativeScopeSkipsTheDisplayLegAndPersistsTheTurnRaw() {
        agent = buildAgent("cs", ChatReplyMode.NATIVE);
        llmProvider.responses.add(new LlmResponse(CZECH_REPLY));

        ChatAgent.ChatTurnResult result = agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "ahoj");
        result.pendingCommit().commit();

        assertFalse(llmProvider.lastSystemPrompt.contains(ChatAgent.REPLY_LANGUAGE_DIRECTIVE),
                "native mode replaces the English pin with the declared-language directive");
        assertEquals(0, translationCalls,
                "native mode skips the display leg — the generated text IS the delivered text");
        assertEquals(CZECH_REPLY, result.reply(),
                "the delivered reply is the generated scope-language text, untranslated");
        assertEquals(List.of("user", "assistant"), persistedRoles);
        assertEquals("ahoj", persistedTexts.get(0), "the user turn persists raw, as today");
        assertEquals(CZECH_REPLY, persistedTexts.get(1),
                "the assistant turn persists RAW in the scope language (D79 window-raw)");
    }

    @Test
    void aTranslateScopeKeepsTodaysBehaviourExactly() {
        agent = buildAgent("cs", ChatReplyMode.TRANSLATE);
        llmProvider.responses.add(new LlmResponse("Hello"));

        ChatAgent.ChatTurnResult result = agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "ahoj");
        result.pendingCommit().commit();

        assertTrue(llmProvider.lastSystemPrompt.contains(ChatAgent.REPLY_LANGUAGE_DIRECTIVE),
                "translate mode keeps the English pin");
        assertEquals(1, translationCalls, "translate mode runs the display leg");
        assertEquals("cs", translationLastLanguage);
        assertEquals("translated:Hello", result.reply());
        assertEquals("Hello", persistedTexts.get(1),
                "the persisted assistant turn stays the English original");
    }

    @Test
    void queryAnchoringStillRunsInNativeMode() {
        agent = buildAgent("cs", ChatReplyMode.NATIVE);
        llmProvider.responses.add(new LlmResponse(CZECH_REPLY));

        agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "ahoj");

        assertEquals(1, semanticSearchCalls,
                "the D58 pre-fetch path runs in native mode exactly as in translate");
    }

    @Test
    void configuredNativeModeIsDecisiveForAnyModelAndLanguage() {
        // Decisive means the configured values are the ONLY inputs — the
        // signature takes no model or language to gate on (decision D79).
        ChatReplyModeResolver resolver = new ChatReplyModeResolver("native");

        assertEquals(ChatReplyMode.NATIVE, resolver.resolve("native"));
        assertEquals(ChatReplyMode.NATIVE, resolver.resolve(null),
                "an unset scope inherits the configured native default");
        assertEquals(ChatReplyMode.NATIVE,
                new ChatReplyModeResolver("translate").resolve("native"),
                "a native override beats a translate deployment default");
        assertEquals(ChatReplyMode.TRANSLATE,
                new ChatReplyModeResolver("translate").resolve(null),
                "an unset scope inherits the translate deployment default");
    }

    @Test
    void nativeTurnAppendsHelpBlockAndProvenanceLikeTranslate() {
        agent = buildAgent("cs", ChatReplyMode.NATIVE);
        triggerIntentMatch = "lang";
        composeUsageBlockResult = Optional.of("USAGE-BLOCK");
        llmProvider.responses.add(new LlmResponse(CZECH_REPLY));

        ChatAgent.ChatTurnResult result = agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "ahoj");

        assertEquals(0, translationCalls, "the help block rides the bundle path, never the translator");
        assertTrue(result.reply().startsWith(CZECH_REPLY),
                "the native reply leads");
        assertTrue(result.reply().contains(BundleKeys.CHAT_HELP_DELIVERY_HEADER),
                "the bundle-localized help-block header is appended (D43 two-path rule)");
        assertTrue(result.reply().endsWith("USAGE-BLOCK"));
        assertEquals(BundleKeys.CHAT_PROVENANCE_GENERAL_KNOWLEDGE, result.provenanceNotice(),
                "the provenance notice is appended exactly as in translate mode");
    }

    @Test
    void anEmptiedNativeReplyDegradesLikeTranslate() {
        agent = buildAgent("cs", ChatReplyMode.NATIVE);
        sanitizerOverride = "";
        llmProvider.responses.add(new LlmResponse(CZECH_REPLY));

        ChatAgent.ChatTurnResult result = agent.handleTurn(USER_ID, SCOPE_KIND, SCOPE_ID, "ahoj");

        assertEquals(BundleKeys.ERROR_CHAT_UNAVAILABLE, result.reply(),
                "a markers-only reply degrades to the localized unavailable string in native mode");
        assertNull(result.pendingCommit(), "the degraded turn carries no commit");
        assertEquals(0, translationCalls);
    }

    @Test
    void aNativeWindowCompressesToAnEnglishCheckpoint() throws Exception {
        // A Czech session window (native turns persist raw); the compressor
        // must summarize it under an English-declaring prompt — the D79
        // checkpoint half of canonicity.
        String englishSummary = "SUMMARY: The user greeted the bot.\n"
                + "KEYWORDS: greeting\nREFERENCES: NONE";
        CapturingCompressLlm compressLlm = new CapturingCompressLlm(englishSummary);
        CompressCheckpointRig rig = new CompressCheckpointRig(
                List.of(new String[] {"user", "ahoj"},
                        new String[] {"assistant", CZECH_REPLY}));

        app.zcat.infochat.provider.command.CompressCommandHandler handler =
                new app.zcat.infochat.provider.command.CompressCommandHandler();
        setField(handler, "dataSource", rig.dataSource());
        setField(handler, "llmRouter", compressRouter(compressLlm));

        app.zcat.infochat.provider.command.CompressCommandHandler.CompressResult result =
                handler.compress(USER_ID, SCOPE_KIND, SCOPE_ID, "cs");

        assertInstanceOf(app.zcat.infochat.provider.command.CompressCommandHandler.CompressResult.Success.class,
                result);
        assertTrue(compressLlm.lastSystemPrompt.contains("in English"),
                "the compression prompt declares the English checkpoint output");
        assertTrue(compressLlm.lastUserPrompt.contains(CZECH_REPLY),
                "the native (Czech) window is what gets summarized");
        assertEquals("The user greeted the bot.", rig.insertedSummary,
                "the checkpoint row carries the English summary");
    }

    // --- test infrastructure (ChatAgentTest-shaped) ---

    private TestChatAgent buildAgent(String language, ChatReplyMode replyMode) {
        ChatPromptBuilder promptBuilder = new ChatPromptBuilder(
                new ChatMemoryPreFetcher() {
                    @Override
                    public List<ChatMemoryPreFetcher.MemoryHit> preFetch(
                            UUID u, String sk, UUID si, String q) {
                        return List.of();
                    }
                },
                new ChatSessionRepository(null),
                16384,
                1024) {
            @Override
            public BuiltPrompt build(UUID u, String sk, UUID si, String msg) {
                return new BuiltPrompt("system", msg, "marker");
            }
        };

        ChatSessionRepository sessionRepo = new ChatSessionRepository(null) {
            @Override
            public int persistTurn(UUID u, String sk, UUID si,
                                    String role, String content, int tokens) {
                persistedRoles.add(role);
                persistedTexts.add(content);
                return persistedRoles.size() - 1;
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

        LlmOutputSanitizer sanitizer = new LlmOutputSanitizer(
                SanitizerTestDoubles.noOpAuditLogWriter(),
                SanitizerTestDoubles.noOpDataSource()) {
            @Override
            public String sanitize(String input) {
                return sanitizerOverride != null ? sanitizerOverride : input;
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

        HelpCommandHandler helpHandler = new HelpCommandHandler() {
            @Override
            public CallerTier resolveCallerTier(UUID userId, String scopeKind, UUID scopeId) {
                return new CallerTier(false, false, false, "group".equals(scopeKind));
            }
            @Override
            public Optional<String> composeUsageBlock(String commandName, CallerTier caller, String language) {
                return composeUsageBlockResult;
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
                if ("semanticSearch".equals(toolName)) {
                    semanticSearchCalls++;
                }
                return new ToolResult.Success("[]");
            }
        };

        EmbeddingProvider embeddingProvider =
                texts -> List.of(new EmbeddingResult(new float[] {1f}));

        AutoCompressTrigger noopTrigger = new AutoCompressTrigger(
                Integer.MAX_VALUE, bundle, null, null) {
            @Override
            public java.util.Optional<String> checkAndCompress(
                    UUID u, String sk, UUID si, String sl) {
                return java.util.Optional.empty();
            }
            @Override
            public boolean isCeilingGated(UUID u, String sk, UUID si) {
                return false;
            }
        };

        LlmCircuitBreakerRegistry breakerRegistry = new LlmCircuitBreakerRegistry(
                3, 30_000, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                key -> Optional.empty());

        return new TestChatAgent(
                inFlightTracker, promptBuilder, dispatcher, sessionRepo,
                router, sanitizer, pipeline, bundle, noopTrigger,
                language, replyMode, breakerRegistry, embeddingProvider, helpHandler);
    }

    private static InboundContext inboundContextWith(String language, ChatReplyMode replyMode) {
        InboundContext context = new InboundContext();
        context.setEffectiveLanguage(language);
        context.setReplyMode(replyMode);
        return context;
    }

    class TestChatAgent extends ChatAgent {

        TestChatAgent(InFlightTracker tracker, ChatPromptBuilder builder,
                      ChatToolDispatcher dispatcher, ChatSessionRepository repo,
                      LlmRouter router, LlmOutputSanitizer sanitizer,
                      TranslationPipeline pipeline, BundleLoader bundle,
                      AutoCompressTrigger autoCompressTrigger,
                      String language, ChatReplyMode replyMode,
                      LlmCircuitBreakerRegistry breakerRegistry,
                      EmbeddingProvider embeddingProvider, HelpCommandHandler helpHandler) {
            super(tracker, builder, dispatcher, repo, router,
                    sanitizer, pipeline, bundle, autoCompressTrigger, null, null,
                    inboundContextWith(language, replyMode), breakerRegistry,
                    embeddingProvider, helpHandler, null, null);
        }

        @Override
        void writeAuditRow(UUID userId, String scopeKind, UUID scopeId) {
            // No JDBC in unit tests; the audit row's CHAT_MODE action is
            // pinned by ChatAgentTest.
        }

        @Override
        Optional<String> lookupTopicForDelivery(String vectorLiteral, UUID userId) {
            return Optional.ofNullable(triggerTopicMatch);
        }

        @Override
        Optional<String> lookupIntentForDelivery(String vectorLiteral, UUID userId,
                                                 String scopeKind, UUID scopeId) {
            return Optional.ofNullable(triggerIntentMatch);
        }
    }

    static class StubLlmProvider implements LlmProvider {
        final List<LlmResponse> responses = new ArrayList<>();
        int callCount;
        String lastSystemPrompt;

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            callCount++;
            lastSystemPrompt = systemPrompt;
            if (callCount <= responses.size()) {
                return responses.get(callCount - 1);
            }
            return new LlmResponse("default response");
        }
    }

    static class CapturingCompressLlm implements LlmProvider {
        final String responseText;
        String lastSystemPrompt;
        String lastUserPrompt;

        CapturingCompressLlm(String responseText) {
            this.responseText = responseText;
        }

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            lastSystemPrompt = systemPrompt;
            lastUserPrompt = userPrompt;
            return new LlmResponse(responseText);
        }
    }

    private static LlmRouter compressRouter(LlmProvider provider) {
        return new LlmRouter(
                List.of(new LlmRouter.Entry("test", provider, Set.of("en"))),
                key -> Optional.empty()) {
            @Override
            public LlmProvider forTask(ModelTask task, String lang) {
                return provider;
            }
        };
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** Minimal JDBC rig for {@code CompressCommandHandler.compress}: seeded window rows in, captured summary out. */
    static class CompressCheckpointRig {
        private final List<String[]> rows;
        String insertedSummary;

        CompressCheckpointRig(List<String[]> rows) {
            this.rows = rows;
        }

        DataSource dataSource() {
            return (DataSource) Proxy.newProxyInstance(
                    DataSource.class.getClassLoader(),
                    new Class<?>[] {DataSource.class},
                    (proxy, method, args) -> {
                        if ("getConnection".equals(method.getName())) {
                            return connection();
                        }
                        return null;
                    });
        }

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> preparedStatement((String) args[0]);
                        case "createArrayOf" -> arrayOf();
                        case "setAutoCommit", "commit", "rollback", "close" -> null;
                        default -> null;
                    });
        }

        private Array arrayOf() {
            return (Array) Proxy.newProxyInstance(
                    Array.class.getClassLoader(),
                    new Class<?>[] {Array.class},
                    (proxy, method, args) -> null);
        }

        private PreparedStatement preparedStatement(String sql) {
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[] {PreparedStatement.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "setString" -> {
                            if (sql.startsWith("INSERT INTO chat_memory")
                                    && (Integer) args[0] == 4) {
                                insertedSummary = (String) args[1];
                            }
                            yield null;
                        }
                        case "setObject", "setInt", "setArray" -> null;
                        case "executeQuery" -> resultSet();
                        case "executeUpdate" -> rows.size();
                        case "close" -> null;
                        default -> null;
                    });
        }

        private ResultSet resultSet() {
            final int[] cursor = {-1};
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[] {ResultSet.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> {
                            cursor[0]++;
                            yield cursor[0] < rows.size();
                        }
                        case "getInt" -> cursor[0] + 1;
                        case "getString" -> rows.get(cursor[0])["role".equals(args[0]) ? 0 : 1];
                        case "close" -> null;
                        default -> null;
                    });
        }
    }

}
