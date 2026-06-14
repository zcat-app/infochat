package app.zcat.infochat.llm.routing;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.impl.AnthropicProvider;
import app.zcat.infochat.llm.impl.OpenAiCompatibleProvider;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    private static final String NAME_ANTHROPIC = AnthropicProvider.PROVIDER_NAME;
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
     * M1-059 scenario 1: TRANSLATOR task with a provider that declares
     * Czech support resolves to that provider via the language-aware
     * capability branch (priority 2).
     */
    @Test
    void forTaskTRANSLATORResolvesProviderWithConfiguredCsLanguage() {
        StubProvider czechProvider = new StubProvider();
        LlmRouter router = new LlmRouter(
            List.of(new LlmRouter.Entry(NAME_DEFAULT, czechProvider, Set.of("en", "cs"))),
            LlmRouter.ConfigReader.fromMap(Map.of(
                LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER, NAME_DEFAULT)));

        LlmProvider resolved = router.forTask(ModelTask.TRANSLATOR, "cs");

        assertSame(czechProvider, resolved,
            "TRANSLATOR with cs scope must resolve the provider declaring Czech support");
    }

    /**
     * M1-059 scenario 2: TRANSLATOR task with no Czech-capable provider
     * still returns a non-null provider via the priority-3 default
     * branch — the router never returns null.
     */
    @Test
    void forTaskTRANSLATORWithoutLanguageConfigStillFallsBackToDefaultProvider() {
        StubProvider defaultProvider = new StubProvider();
        LlmRouter router = new LlmRouter(
            List.of(new LlmRouter.Entry(NAME_DEFAULT, defaultProvider, Set.of("en"))),
            LlmRouter.ConfigReader.fromMap(Map.of(
                LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER, NAME_DEFAULT)));

        LlmProvider resolved = router.forTask(ModelTask.TRANSLATOR, "cs");

        assertSame(defaultProvider, resolved,
            "TRANSLATOR without a Czech-capable provider must fall back to the default");
    }

    /**
     * M1-059 scenario 3: the static helper
     * {@link LlmRouter#supportedLanguagesFor(LlmProvider, Config)}
     * defaults to {@code Set.of("en")} when the per-provider
     * {@code infochat.llm.<name>.languages} key is unset.
     */
    @Test
    void supportedLanguagesForDefaultsToEnglishOnlyWhenLanguagesConfigUnset() {
        StubProvider provider = new StubProvider();
        Config emptyConfig = new StubConfig(Map.of());

        Set<String> langs = LlmRouter.supportedLanguagesFor(provider, emptyConfig);

        assertEquals(Set.of("en"), langs,
            "unset languages config must default to English-only");
    }

    /**
     * M1-071: uppercase scopeLanguage is normalized to lowercase before
     * the language-aware capability branch runs its {@code contains}
     * check. Without normalization, "CS" would miss the set {"cs"}
     * and fall through to the profile default instead.
     */
    @Test
    void caseInsensitiveLanguageLookup() {
        StubProvider englishProvider = new StubProvider();
        StubProvider czechProvider = new StubProvider();
        LlmRouter router = new LlmRouter(
            List.of(
                new LlmRouter.Entry(NAME_ENGLISH, englishProvider, Set.of("en")),
                new LlmRouter.Entry(NAME_CZECH, czechProvider, Set.of("cs"))),
            LlmRouter.ConfigReader.fromMap(Map.of(
                LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER, NAME_ENGLISH)));

        LlmProvider resolved = router.forTask(ModelTask.SUMMARIZER, "CS");

        assertSame(czechProvider, resolved,
            "uppercase 'CS' must resolve to the Czech-capable provider after case normalization");
    }

    /**
     * M1-085: per-task override property set to "anthropic" routes
     * SUMMARIZER to the AnthropicProvider entry. Exercises the
     * priority-1 override branch with the new provider name.
     */
    @Test
    void perTaskOverrideAnthropicRoutesToAnthropicProvider() {
        StubProvider defaultProvider = new StubProvider();
        StubProvider anthropicProvider = new StubProvider();
        LlmRouter router = new LlmRouter(
            List.of(
                new LlmRouter.Entry(NAME_DEFAULT, defaultProvider, Set.of("en")),
                new LlmRouter.Entry(NAME_ANTHROPIC, anthropicProvider, Set.of("en", "cs"))),
            LlmRouter.ConfigReader.fromMap(Map.of(
                "infochat.llm.summarizer.provider", NAME_ANTHROPIC)));

        LlmProvider resolved = router.forTask(ModelTask.SUMMARIZER, "en");

        assertSame(anthropicProvider, resolved,
            "per-task override 'anthropic' must route to the anthropic provider entry");
    }

    /**
     * M1-085: AnthropicProvider registered with Czech language support
     * routes for the SUMMARIZER task when scope language is "cs" (via
     * priority-2 language-aware capability branch).
     */
    @Test
    void anthropicProviderWithCzechLanguageRoutesForCzechSummarizer() {
        StubProvider defaultProvider = new StubProvider();
        StubProvider anthropicProvider = new StubProvider();
        LlmRouter router = new LlmRouter(
            List.of(
                new LlmRouter.Entry(NAME_DEFAULT, defaultProvider, Set.of("en")),
                new LlmRouter.Entry(NAME_ANTHROPIC, anthropicProvider, Set.of("en", "cs"))),
            LlmRouter.ConfigReader.fromMap(Map.of(
                LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER, NAME_DEFAULT)));

        LlmProvider resolved = router.forTask(ModelTask.SUMMARIZER, "cs");

        assertSame(anthropicProvider, resolved,
            "language-aware branch must pick the Anthropic provider for Czech summarizer");
    }

    /**
     * Acceptance item 4 (startup scan): a per-task provider override
     * naming a provider with no registered entry must throw from
     * {@link LlmRouter#assertAllTasksResolve()} — surfacing the misroute
     * at startup rather than at the first call that routes that task.
     */
    @Test
    void assertAllTasksResolveThrowsWhenPerTaskOverrideNamesUnregisteredProvider() {
        StubProvider registered = new StubProvider();
        LlmRouter router = new LlmRouter(
            List.of(new LlmRouter.Entry(NAME_DEFAULT, registered, Set.of("en"))),
            LlmRouter.ConfigReader.fromMap(Map.of(
                "infochat.llm.tagger.provider", "no-such-provider")));

        assertThrows(IllegalStateException.class, router::assertAllTasksResolve,
            "a TAGGER override naming an unregistered provider must fail the startup scan");
    }

    /**
     * Acceptance item 4 (startup scan): with no per-task overrides every
     * task falls through to the priority-3 default, which resolves, so
     * the scan passes without throwing.
     */
    @Test
    void assertAllTasksResolvePassesWhenEveryTaskResolves() {
        StubProvider registered = new StubProvider();
        LlmRouter router = new LlmRouter(
            List.of(new LlmRouter.Entry(NAME_DEFAULT, registered, Set.of("en"))),
            LlmRouter.ConfigReader.fromMap(Map.of()));

        assertDoesNotThrow(router::assertAllTasksResolve,
            "every task resolves to the default provider when no override is set");
    }

    /**
     * U-27: an explicitly configured {@code infochat.llm.default.provider}
     * that names no registered entry fails the startup scan — a typo that
     * forTask's priority-3 would otherwise absorb as a silent reroute of
     * every override-less task for the JVM lifetime. The message names the
     * property and the bad value so the operator can find the typo.
     */
    @Test
    void assertAllTasksResolveFailsWhenExplicitDefaultProviderUnknown() {
        LlmRouter router = new LlmRouter(
            List.of(new LlmRouter.Entry(NAME_DEFAULT, new StubProvider(), Set.of("en"))),
            LlmRouter.ConfigReader.fromMap(Map.of(
                LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER, "no-such-provider")));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            router::assertAllTasksResolve,
            "an explicit unknown default provider must fail the startup scan");
        assertTrue(ex.getMessage().contains(LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER),
            "the failure must name the default-provider property; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("no-such-provider"),
            "the failure must name the unknown configured value; got: " + ex.getMessage());
    }

    /**
     * U-27 complement: when {@code infochat.llm.default.provider} is UNSET,
     * the implicit default ({@code OpenAiCompatibleProvider.PROVIDER_NAME})
     * may name no registered entry in a stub-only fixture — that must NOT
     * fail the startup scan. forTask's priority-3 silently falls back to the
     * first entry, the path test fixtures rely on.
     */
    @Test
    void assertAllTasksResolveToleratesUnknownImplicitDefault() {
        LlmRouter router = new LlmRouter(
            List.of(new LlmRouter.Entry(NAME_ALTERNATE, new StubProvider(), Set.of("en"))),
            LlmRouter.ConfigReader.fromMap(Map.of()));

        assertDoesNotThrow(router::assertAllTasksResolve,
            "an unknown IMPLICIT default must not fail boot — the silent "
                + "fallback is the test-fixture path");
    }

    /**
     * The startup scan must also validate the priority-2 language
     * branch: a provider reachable only through its declared languages
     * (no per-task override, not the default) must have its per-task
     * config asserted at startup. Before the language sweep, the
     * broken TRANSLATOR config below surfaced only at the first
     * non-English call.
     */
    @Test
    void assertAllTasksResolveValidatesLanguageBranchPerTaskConfig() {
        LlmRouter router = new LlmRouter(
            List.of(
                new LlmRouter.Entry(NAME_DEFAULT, new StubProvider(), Set.of("en")),
                new LlmRouter.Entry(NAME_CZECH,
                    new UnresolvableTaskConfigStubProvider(ModelTask.TRANSLATOR),
                    Set.of("cs"))),
            LlmRouter.ConfigReader.fromMap(Map.of(
                LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER, NAME_DEFAULT)));

        assertThrows(LlmProvider.TaskConfigUnresolvableException.class, router::assertAllTasksResolve,
            "the cs-reachable provider's unresolvable TRANSLATOR config must fail "
                + "the startup scan via the language-branch sweep");
    }

    /**
     * The language sweep probes only languages some registered entry
     * declares. The same broken-TRANSLATOR provider with an empty
     * language set is unreachable via priority 2, so the scan must
     * pass — over-probing would fail deployments for providers no
     * route can select.
     */
    @Test
    void assertAllTasksResolveSkipsLanguagesNoEntryDeclares() {
        LlmRouter router = new LlmRouter(
            List.of(
                new LlmRouter.Entry(NAME_DEFAULT, new StubProvider(), Set.of("en")),
                new LlmRouter.Entry(NAME_CZECH,
                    new UnresolvableTaskConfigStubProvider(ModelTask.TRANSLATOR),
                    Set.of())),
            LlmRouter.ConfigReader.fromMap(Map.of(
                LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER, NAME_DEFAULT)));

        assertDoesNotThrow(router::assertAllTasksResolve,
            "a provider declaring no languages is unreachable via priority 2 "
                + "and must not be probed by the language sweep");
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

    /**
     * Stub whose per-task config check fails for one designated task,
     * mirroring a provider with a missing required property (the real
     * providers throw {@link LlmProvider.TaskConfigUnresolvableException}
     * from the config read). Lets the scan tests prove a given
     * (task, language) pair is actually probed.
     */
    private static final class UnresolvableTaskConfigStubProvider implements LlmProvider {
        private final ModelTask unresolvableTask;

        UnresolvableTaskConfigStubProvider(ModelTask unresolvableTask) {
            this.unresolvableTask = unresolvableTask;
        }

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            throw new UnsupportedOperationException(
                "UnresolvableTaskConfigStubProvider.generate must not be invoked "
                    + "by router-resolution tests");
        }

        @Override
        public void assertTaskConfigResolvable(ModelTask task) {
            if (task == unresolvableTask) {
                throw new LlmProvider.TaskConfigUnresolvableException(
                    "stub: no per-task config for " + task);
            }
        }
    }

    /**
     * A provider that does not override the default no-op
     * {@code assertTaskConfigResolvable} must pass the startup scan —
     * test stubs (e.g. Stage2WorkerIT's) have no per-task config
     * requirements and must keep booting their host modules.
     */
    @Test
    void assertAllTasksResolvePassesWhenProviderDoesNotOverrideConfigCheck() {
        LlmRouter router = new LlmRouter(
            List.of(new LlmRouter.Entry(NAME_DEFAULT, new StubProvider(), Set.of("en"))),
            LlmRouter.ConfigReader.fromMap(Map.of()));

        assertDoesNotThrow(router::assertAllTasksResolve,
            "the default no-op config check must keep config-free providers bootable");
    }

    /**
     * M1-261: provider-name resolution is case-insensitive, agreeing
     * with the already-case-insensitive {@link LlmRouterStartupGuard}.
     * A mixed-case per-task override ("Anthropic") must resolve to the
     * SAME registered Entry as its lower-case form ("anthropic") — the
     * two collaborators must not reason about the same operator string
     * under different normalization.
     */
    @Test
    void mixedCaseProviderNameResolvesSameEntryAsLowerCase() {
        StubProvider defaultProvider = new StubProvider();
        StubProvider anthropicProvider = new StubProvider();
        List<LlmRouter.Entry> entries = List.of(
            new LlmRouter.Entry(NAME_DEFAULT, defaultProvider, Set.of("en")),
            new LlmRouter.Entry(NAME_ANTHROPIC, anthropicProvider, Set.of("en")));

        LlmRouter lowerCase = new LlmRouter(entries,
            LlmRouter.ConfigReader.fromMap(Map.of(
                "infochat.llm.security.provider", "anthropic")));
        LlmRouter mixedCase = new LlmRouter(entries,
            LlmRouter.ConfigReader.fromMap(Map.of(
                "infochat.llm.security.provider", "Anthropic")));

        assertSame(anthropicProvider, lowerCase.forTask(ModelTask.SECURITY_JUDGE, "en"),
            "lower-case 'anthropic' override resolves the anthropic entry (baseline)");
        assertSame(anthropicProvider, mixedCase.forTask(ModelTask.SECURITY_JUDGE, "en"),
            "mixed-case 'Anthropic' override must resolve the SAME entry as "
            + "'anthropic', agreeing with the case-insensitive startup guard");
    }

    /**
     * Minimal MicroProfile {@link Config} stub backed by a fixed map.
     * Only {@link #getOptionalValue(String, Class)} is implemented —
     * the router's {@code supportedLanguagesFor} helper uses that
     * single method.
     */
    @SuppressWarnings("unchecked")
    private static final class StubConfig implements Config {
        private final Map<String, String> values;

        StubConfig(Map<String, String> values) {
            this.values = Map.copyOf(values);
        }

        @Override
        public <T> T getValue(String propertyName, Class<T> propertyType) {
            throw new UnsupportedOperationException("getValue not stubbed");
        }

        @Override
        public ConfigValue getConfigValue(String propertyName) {
            throw new UnsupportedOperationException("getConfigValue not stubbed");
        }

        @Override
        public <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
            if (propertyType != String.class) {
                throw new UnsupportedOperationException("only String type supported in stub");
            }
            return (Optional<T>) Optional.ofNullable(values.get(propertyName));
        }

        @Override
        public <T> List<T> getValues(String propertyName, Class<T> propertyType) {
            throw new UnsupportedOperationException("getValues not stubbed");
        }

        @Override
        public <T> Optional<List<T>> getOptionalValues(String propertyName, Class<T> propertyType) {
            throw new UnsupportedOperationException("getOptionalValues not stubbed");
        }

        @Override
        public Iterable<String> getPropertyNames() {
            return values.keySet();
        }

        @Override
        public Iterable<ConfigSource> getConfigSources() {
            return List.of();
        }

        @Override
        public <T> Optional<Converter<T>> getConverter(Class<T> forType) {
            return Optional.empty();
        }

        @Override
        public <T> T unwrap(Class<T> type) {
            throw new UnsupportedOperationException("unwrap not stubbed");
        }
    }
}
