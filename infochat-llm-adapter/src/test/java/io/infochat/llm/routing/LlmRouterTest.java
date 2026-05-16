package io.infochat.llm.routing;

import io.infochat.llm.LlmProvider;
import io.infochat.llm.LlmResponse;
import io.infochat.llm.ModelTask;
import io.infochat.llm.impl.OpenAiCompatibleProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Plain JUnit5 unit tests for {@link LlmRouter}. Bypasses Quarkus
 * entirely — the router's test-friendly constructor takes a
 * hand-rolled {@link LlmRouter.Entry} list + {@link LlmRouter.ConfigReader},
 * so the priority chain can be exercised in isolation.
 *
 * <p>Four per-behavior tests, one method per behavior, name-pinned
 * so the M1-033 acceptance items 26a-26d are mechanically checkable
 * via grep over this file's method names without a fragile aggregate
 * "@Test count" assertion.
 */
class LlmRouterTest {

    private static final String NAME_DEFAULT = OpenAiCompatibleProvider.PROVIDER_NAME;
    private static final String NAME_ALTERNATE = "alternate-provider";
    private static final String NAME_CZECH = "czech-capable";
    private static final String NAME_ENGLISH = "english-only";

    /**
     * 26a — profile-default resolution for SECURITY_JUDGE. With no
     * per-task override property set, the router resolves to the
     * default provider (the only one registered in v1, named
     * {@code openai-compatible}).
     */
    @Test
    void forTaskReturnsConfiguredProviderForSecurityJudgeWithProfileDefault() {
        StubProvider defaultProvider = new StubProvider();
        LlmRouter router = new LlmRouter(
            List.of(new LlmRouter.Entry(NAME_DEFAULT, defaultProvider, Set.of("en"))),
            LlmRouter.ConfigReader.fromMap(Map.of()));

        LlmProvider resolved = router.forTask(ModelTask.SECURITY_JUDGE, "en");

        assertSame(defaultProvider, resolved,
            "profile-default resolution must return the only registered provider");
    }

    /**
     * 26b — per-task override takes priority over the profile
     * default. With {@code infochat.llm.security.provider=
     * alternate-provider} set, the router returns the alternate
     * provider even though the default is still registered.
     */
    @Test
    void perTaskOverridePropertyTakesPriorityOverProfileDefault() {
        StubProvider defaultProvider = new StubProvider();
        StubProvider alternateProvider = new StubProvider();
        LlmRouter router = new LlmRouter(
            List.of(
                new LlmRouter.Entry(NAME_DEFAULT, defaultProvider, Set.of("en")),
                new LlmRouter.Entry(NAME_ALTERNATE, alternateProvider, Set.of("en"))),
            LlmRouter.ConfigReader.fromMap(Map.of(
                "infochat.llm.security.provider", NAME_ALTERNATE)));

        LlmProvider resolved = router.forTask(ModelTask.SECURITY_JUDGE, "en");

        assertSame(alternateProvider, resolved,
            "per-task override must win over the profile default");
    }

    /**
     * 26c — singular return type. The router's return type is
     * {@link LlmProvider} (not {@code List<LlmProvider>}, not
     * {@code Optional<LlmProvider>}). The load-bearing part is the
     * compile-time signature: the assignment {@code LlmProvider p
     * = router.forTask(...)} only compiles if the return type is
     * assignable to {@link LlmProvider}. The runtime non-null
     * assertion is a sanity check that the resolver did not return
     * a null reference.
     */
    @Test
    void forTaskReturnsExactlyOneProviderNotAList() {
        StubProvider defaultProvider = new StubProvider();
        LlmRouter router = new LlmRouter(
            List.of(new LlmRouter.Entry(NAME_DEFAULT, defaultProvider, Set.of("en"))),
            LlmRouter.ConfigReader.fromMap(Map.of()));

        // Compile-time witness: this line only compiles if
        // forTask returns LlmProvider (singular). A change to
        // List<LlmProvider> or Optional<LlmProvider> would break
        // the build here.
        LlmProvider p = router.forTask(ModelTask.SECURITY_JUDGE, "en");

        assertNotNull(p, "singular forTask return must be non-null");
    }

    /**
     * 26d — language-aware capability check. Register two SUMMARIZER
     * candidates: one declaring Czech support, one English-only.
     * Resolving {@code forTask(SUMMARIZER, "cs")} must return the
     * Czech-capable provider even when no per-task override is set
     * (priority 2 wins over priority 3 here).
     */
    @Test
    void summarizerWithCzechScopeLanguagePrefersProviderWithSupportsLanguageCsCapability() {
        StubProvider englishProvider = new StubProvider();
        StubProvider czechProvider = new StubProvider();
        LlmRouter router = new LlmRouter(
            List.of(
                new LlmRouter.Entry(NAME_ENGLISH, englishProvider, Set.of("en")),
                new LlmRouter.Entry(NAME_CZECH, czechProvider, Set.of("cs"))),
            LlmRouter.ConfigReader.fromMap(Map.of(
                LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER, NAME_ENGLISH)));

        LlmProvider resolved = router.forTask(ModelTask.SUMMARIZER, "cs");

        assertSame(czechProvider, resolved,
            "language-aware branch must pick the Czech-capable provider over the default");
    }

    /**
     * Lightweight test stub: implements {@link LlmProvider} so the
     * router's resolution chain can be exercised end-to-end without
     * pulling Quarkus or constructing an
     * {@link OpenAiCompatibleProvider} (which would attempt to read
     * @ConfigProperty values that aren't available in plain JUnit5).
     * The stub's {@link #generate} is never invoked by the router
     * tests — resolution returns the provider, the call site is the
     * Stage 2 worker, exercised by Stage2WorkerIT in the Collector.
     */
    private static final class StubProvider implements LlmProvider {
        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            throw new UnsupportedOperationException(
                "StubProvider.generate must not be invoked by router-resolution tests");
        }
    }
}
