package app.zcat.infochat.provider.chat.tool;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmCircuitBreakerRegistry;
import app.zcat.infochat.llm.routing.LlmRouter;
import app.zcat.infochat.provider.translation.QueryTranslationCache;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link QueryAnchorTranslator} — the D58 (b)/(c)/(d)
 * conditions and the fallback posture, against a stub {@link LlmProvider}
 * behind a real {@link LlmRouter} + real {@link QueryTranslationCache} +
 * never-open breaker (all plain-JUnit constructible; no Quarkus). The
 * D58 (a) greedy-decoding condition is asserted at the wire level in the
 * provider tests (OpenAiCompatibleProviderTest / AnthropicProviderTest),
 * because temperature 0 lives on the HTTP request the provider receives.
 */
class QueryAnchorTranslatorTest {

    /** Scope identity for the cache-partition tests: two DISTINCT scopes. */
    private static final String SCOPE_A = "dm";
    private static final UUID UUID_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final String SCOPE_B = "group";
    private static final UUID UUID_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    static final class StubLlmProvider implements LlmProvider {
        final List<ModelTask> tasks = new ArrayList<>();
        final List<String> userPrompts = new ArrayList<>();
        String canned = "canned translation";
        RuntimeException failure;

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            tasks.add(task);
            userPrompts.add(userPrompt);
            if (failure != null) {
                throw failure;
            }
            return new LlmResponse(canned);
        }
    }

    static final class BreakerStub extends LlmCircuitBreakerRegistry {
        final boolean open;

        BreakerStub(boolean open) {
            super(3, 30_000, Clock.systemUTC(), LlmRouter.ConfigReader.fromMap(Map.of()));
            this.open = open;
        }

        @Override
        public boolean wouldShortCircuit(ModelTask task) {
            return open;
        }
    }

    private final StubLlmProvider provider = new StubLlmProvider();

    /** Mirrors the dispatcher's input-max-length default (the cap's config source). */
    private static final int INPUT_MAX_LENGTH = 500;

    private QueryAnchorTranslator translator(BreakerStub breaker) {
        return translator(breaker, INPUT_MAX_LENGTH);
    }

    private QueryAnchorTranslator translator(BreakerStub breaker, int inputMaxLength) {
        return translator(breaker, new QueryTranslationCache(), inputMaxLength);
    }

    private QueryAnchorTranslator translator(BreakerStub breaker, QueryTranslationCache cache,
                                             int inputMaxLength) {
        return new QueryAnchorTranslator(
                new LlmRouter(
                        List.of(new LlmRouter.Entry("stub", provider, Set.of())),
                        LlmRouter.ConfigReader.fromMap(Map.of())),
                cache, breaker, inputMaxLength);
    }

    // M1-746 acceptance a1: an en-declared scope is a strict no-op —
    // no call, no cache interaction, byte-identical input. A regression
    // here would put an LLM call in front of every search.
    @Test
    void enScopeIssuesNoCallAndReturnsInputUnchanged() {
        QueryAnchorTranslator t = translator(new BreakerStub(false));

        String out = t.translate("quantum router exploit", "en", SCOPE_A, UUID_A);

        assertSame("quantum router exploit", out,
                "an en scope must return the input unchanged");
        assertTrue(provider.tasks.isEmpty(),
                "an en scope must never reach the provider");
    }

    // M1-746 acceptance a2: a non-English scope translates once; the
    // translated string is what both arms consume.
    @Test
    void nonEnglishScopeTranslates() {
        QueryAnchorTranslator t = translator(new BreakerStub(false));
        provider.canned = "kvantový router zneužití";

        String out = t.translate("quantum router exploit", "cs", SCOPE_A, UUID_A);

        assertEquals(provider.canned, out,
                "the translated string must be returned verbatim");
        assertEquals(List.of(ModelTask.TRANSLATOR), provider.tasks,
                "the call must be issued under the shared ModelTask.TRANSLATOR key");
    }

    // M1-746 acceptance a4 (D58 (b) CACHED): a repeated query issues NO
    // second translator call and returns the identical string — the
    // load-bearing determinism property (D19), by construction.
    @Test
    void repeatedQueryHitsTheCacheWithoutASecondProviderCall() {
        QueryAnchorTranslator t = translator(new BreakerStub(false));

        String first = t.translate("kvantový router exploit", "cs", SCOPE_A, UUID_A);
        String second = t.translate("kvantový router exploit", "cs", SCOPE_A, UUID_A);

        assertEquals(first, second,
                "the cached and the fresh translation must be identical");
        assertEquals(1, provider.tasks.size(),
                "a repeated query must not issue a second translator call");
    }

    // M1-746 acceptance a7: a translator failure falls back to the
    // original query text — degraded retrieval beats no retrieval. The
    // failure must NOT be cached: a later call gets a fresh attempt.
    @Test
    void translatorFailureFallsBackToOriginalTextAndIsNotCached() {
        QueryAnchorTranslator t = translator(new BreakerStub(false));
        provider.failure = new IllegalStateException("backend down");

        assertEquals("kvantový router exploit", t.translate("kvantový router exploit", "cs", SCOPE_A, UUID_A),
                "a translator failure must fall back to the original query text");

        provider.failure = null;
        assertEquals(provider.canned, t.translate("kvantový router exploit", "cs", SCOPE_A, UUID_A),
                "a failed translation must not be cached — the next call retries");
        assertEquals(2, provider.tasks.size(),
                "the failed attempt must not have been memoised");
    }

    // M1-746 acceptance a7 (breaker leg): an open circuit breaker falls
    // back to the original query text WITHOUT issuing a call.
    @Test
    void openBreakerFallsBackToOriginalTextWithoutCalling() {
        QueryAnchorTranslator t = translator(new BreakerStub(true));

        assertEquals("kvantový router exploit", t.translate("kvantový router exploit", "cs", SCOPE_A, UUID_A),
                "an open breaker must fall back to the original query text");
        assertTrue(provider.tasks.isEmpty(),
                "a short-circuited call must never reach the provider");
    }

    // M1-746 acceptance a5 (D58 (c) DECLARED): the source language is the
    // declared scope value — the prompt must be rendered FROM it, never
    // inferred from the query's own script. A Latin-script query under a
    // declared 'cs' scope still translates FROM Czech.
    @Test
    void sourceLanguageComesFromTheDeclaredScopeValueNotTheQueryScript() {
        QueryAnchorTranslator t = translator(new BreakerStub(false));

        t.translate("router", "cs", SCOPE_A, UUID_A);

        assertTrue(provider.userPrompts.get(0).contains("from cs to English"),
                "the prompt must name the declared source language; got: "
                        + provider.userPrompts.get(0));
        assertFalse(provider.userPrompts.get(0).contains("from en"),
                "the source language must never be inferred as English from the query script");
    }

    // M1-746 acceptance a6 (D58 (d) LANGUAGE-ONLY): the prompt instructs
    // language conversion only — no expansion, no disambiguation, no added
    // terms — and the query is wrapped as untrusted data (injection
    // posture), while the pipeline uses the provider's output verbatim.
    @Test
    void languageOnlyPromptPinsNoExpansionAndOutputIsUsedVerbatim() {
        QueryAnchorTranslator t = translator(new BreakerStub(false));

        String out = t.translate("kvantový router exploit", "cs", SCOPE_A, UUID_A);
        String prompt = provider.userPrompts.get(0);

        assertEquals(provider.canned, out,
                "the provider's output must reach the arms verbatim — the code never adds terms");
        assertTrue(prompt.contains("Change ONLY the language"),
                "the prompt must instruct language-only conversion (D58 (d))");
        assertTrue(prompt.contains("do not add terms"),
                "the prompt must forbid added terms explicitly");
        assertTrue(prompt.contains("do not expand"),
                "the prompt must forbid expansion explicitly");
        assertTrue(prompt.contains("UNTRUSTED_CONTENT"),
                "the query must be wrapped as untrusted data, not instructions");
    }

    // M1-746 acceptance R4 (redteam r5, 2026-08-03, INJECTION high): the
    // query must actually BE in the prompt, inside a CONSTRUCTED
    // delimiter block. The r5 audit found the template carried a literal
    // "..." between the markers and no query placeholder at all, so the
    // model received a query-less instruction and its reply — anchored to
    // nothing the user typed — became the search text for both retrieval
    // arms and was cached under the real query's hash. The
    // contains("UNTRUSTED_CONTENT") assertion above passes on the
    // instruction prose alone, which is exactly how the gap survived four
    // audit rounds; this test pins the data flow instead.
    @Test
    void theQueryIsWrappedInsideAConstructedUntrustedContentBlock() {
        QueryAnchorTranslator t = translator(new BreakerStub(false));

        t.translate("kvantový router exploit", "cs", SCOPE_A, UUID_A);
        String prompt = provider.userPrompts.get(0);

        Matcher block = Pattern.compile(
                "<<<UNTRUSTED_CONTENT id=\"([0-9a-f-]{36})\">>>\\n(.*?)\\n<<<END id=\"([0-9a-f-]{36})\">>>",
                Pattern.DOTALL).matcher(prompt);

        assertTrue(block.find(),
                "the prompt must carry a CONSTRUCTED open/close delimiter pair around the "
                        + "query, not prose describing one; got: " + prompt);
        assertEquals("kvantový router exploit", block.group(2),
                "the delimited block must contain the caller's query verbatim");
        assertEquals(block.group(1), block.group(3),
                "the closing marker must carry the same per-call id as the opener — an "
                        + "unmatched marker inside the query must not be able to close the block");

        // Redteam r6: exactly ONE marker pair may carry the live id. An
        // illustrative pair in the instruction prose rendered with the same
        // substituted id makes "the content between the id=X markers"
        // resolve to the sample text instead of the query.
        String liveOpen = "<<<UNTRUSTED_CONTENT id=\"" + block.group(1) + "\">>>";
        String liveClose = "<<<END id=\"" + block.group(1) + "\">>>";
        assertEquals(1, prompt.split(Pattern.quote(liveOpen), -1).length - 1,
                "the per-call opening marker must appear exactly once; got: " + prompt);
        assertEquals(1, prompt.split(Pattern.quote(liveClose), -1).length - 1,
                "the per-call closing marker must appear exactly once; got: " + prompt);
    }

    // M1-746 acceptance R4, substitution order: the query goes in LAST, so
    // a query that happens to contain a placeholder token reaches the
    // model as the user typed it rather than triggering a second round of
    // substitution.
    @Test
    void aQueryContainingAPlaceholderTokenIsNotReSubstituted() {
        QueryAnchorTranslator t = translator(new BreakerStub(false));

        t.translate("{{SOURCE_LANGUAGE}} exploit", "cs", SCOPE_A, UUID_A);

        assertTrue(provider.userPrompts.get(0).contains("{{SOURCE_LANGUAGE}} exploit"),
                "a query containing a placeholder token must reach the model verbatim; got: "
                        + provider.userPrompts.get(0));
    }

    // Redteam R1 (2026-08-03, DOS high): an over-cap translation must
    // fall back to the original query and must NOT be cached — otherwise
    // a hostile endpoint's up-to-8-MiB response would be retained (and
    // the 10,000-entry cache would amplify it into the heap), defeating
    // the transport cap's memory-protection purpose. The cap is the
    // tool's CONFIGURED input-max-length (redteam re-audit r2): a
    // non-default config must tighten the accepted translation bound.
    @Test
    void overCapTranslationFallsBackToOriginalAndIsNotCached() {
        QueryAnchorTranslator t = translator(new BreakerStub(false));
        provider.canned = "x".repeat(INPUT_MAX_LENGTH + 1);

        assertEquals("kvantový router exploit", t.translate("kvantový router exploit", "cs", SCOPE_A, UUID_A),
                "an over-cap translation must fall back to the original query text");

        provider.canned = "canned translation";
        assertEquals(provider.canned, t.translate("kvantový router exploit", "cs", SCOPE_A, UUID_A),
                "an over-cap translation must not be cached — the next call retries");
        assertEquals(2, provider.tasks.size(),
                "the over-cap result must not have been memoised");
    }

    // Redteam re-audit r2: the cap follows the tool's configured
    // input-max-length — a LOWER operator config tightens the accepted
    // translation bound (the anchored string may never exceed what the
    // raw path permits at any config).
    @Test
    void overCapTranslationBoundFollowsTheConfiguredInputMaxLength() {
        QueryAnchorTranslator t = translator(new BreakerStub(false), 200);
        provider.canned = "x".repeat(201);

        assertEquals("kvantový router exploit", t.translate("kvantový router exploit", "cs", SCOPE_A, UUID_A),
                "a 201-char translation must be rejected when the tool's input cap is 200");
    }

    // Redteam re-audit r3: the RETENTION belt — a translation within the
    // functional input cap but over the cache ceiling is SERVED for the
    // call but never cached, so an operator raising input-max-length
    // cannot resurrect the R1 heap-amplification (10,000 retained
    // entries x up-to-cap values).
    @Test
    void overCeilingTranslationIsServedButNeverCached() {
        QueryAnchorTranslator t = translator(new BreakerStub(false), 5000);
        provider.canned = "x".repeat(QueryAnchorTranslator.MAX_CACHED_TRANSLATION_LENGTH + 1);

        assertEquals(provider.canned,
                t.translate("kvantový router exploit", "cs", SCOPE_A, UUID_A),
                "a translation within the input cap but over the cache ceiling must still "
                        + "be served for this call");

        t.translate("kvantový router exploit", "cs", SCOPE_A, UUID_A);
        assertEquals(2, provider.tasks.size(),
                "an over-ceiling translation must not be cached — a repeat re-translates");
    }

    // Redteam r4: the length cap is re-validated on the cache-HIT path —
    // a value cached under a higher input-max-length must never be served
    // once the cap drops ("anchored <= what the raw path permits" holds
    // on both paths, not just the miss path).
    @Test
    void cachedValueOverTheCurrentCapIsNotServed() {
        QueryTranslationCache shared = new QueryTranslationCache();
        QueryAnchorTranslator high = translator(new BreakerStub(false), shared, 500);
        QueryAnchorTranslator low = translator(new BreakerStub(false), shared, 200);
        provider.canned = "x".repeat(300);

        assertEquals(provider.canned, high.translate("q", "cs", SCOPE_A, UUID_A),
                "a 300-char translation must be cached under a 500-char cap");

        provider.canned = "y".repeat(100);
        assertEquals(provider.canned, low.translate("q", "cs", SCOPE_A, UUID_A),
                "the 300-char cached value must NOT be served when the current cap is 200 — "
                        + "the hit path must fall through to re-translation");
        assertEquals(2, provider.tasks.size(),
                "the over-current-cap cached value must not short-circuit a fresh call");
    }

    // Redteam R2 (2026-08-03, INFO-LEAK medium): the cache is
    // scope-partitioned — a translation produced from one scope's query
    // is never served to another scope's search, so cache state (and the
    // hit/miss latency oracle) cannot cross scopes. D58 (b) still holds
    // within a scope (asserted by repeatedQueryHitsTheCache...).
    @Test
    void differentScopesDoNotShareCachedTranslations() {
        QueryAnchorTranslator t = translator(new BreakerStub(false));

        t.translate("kvantový router exploit", "cs", SCOPE_A, UUID_A);
        t.translate("kvantový router exploit", "cs", SCOPE_B, UUID_B);

        assertEquals(2, provider.tasks.size(),
                "the same text searched from two different scopes must each issue its own "
                        + "translator call — no cross-scope cache sharing");
    }
}
