package app.zcat.infochat.llm.routing;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.impl.OpenAiCompatibleProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resolves {@code (ModelTask, scope_language) → LlmProvider} per
 * {@code docs/spec/llm.md} §Per-task routing rules. The resolution
 * priority is fixed at three layers:
 *
 * <ol>
 *   <li><b>Explicit per-task override property</b>, e.g.
 *       {@code infochat.llm.security.provider=openai-compatible}.
 *       Highest priority — when set, the operator's wish is final.</li>
 *   <li><b>Language-aware capability check</b> — only consulted for
 *       {@link ModelTask#SUMMARIZER} and {@link ModelTask#TRANSLATOR}
 *       when {@code scope_language != "en"}. The router picks the
 *       first registered provider that declares the language via
 *       {@link Entry#supportedLanguages()}. {@link ModelTask#SECURITY_JUDGE}
 *       is language-agnostic by spec and skips this branch.</li>
 *   <li><b>Profile default</b> — the registered provider whose name
 *       matches {@code infochat.llm.default.provider} (defaulting to
 *       {@link OpenAiCompatibleProvider#PROVIDER_NAME} in v1, where
 *       only one concrete impl ships).</li>
 * </ol>
 *
 * <h2>No fallback chain</h2>
 * <p>{@link #forTask(ModelTask, String)} returns EXACTLY ONE
 * {@link LlmProvider} per call, never a list or {@code Optional},
 * per {@code docs/spec/llm.md} §Per-task routing rules ("No fallback
 * chain in v1. The router resolves ... to exactly one LlmProvider;
 * an unreachable provider degrades that task to its task-specific
 * failure path ... and does NOT silently switch to a different
 * configured provider"). The unreachability check (e.g. Stage 2's
 * retry-once-then-fallback) lives at the call site, NOT in the
 * router. The router resolves and returns; the caller handles
 * failure.
 *
 * <h2>Test seam</h2>
 * <p>The bean exposes two constructors: the {@link Inject}-annotated
 * CDI constructor (used in production by Quarkus ArC) and a
 * dependency-free constructor that takes a hand-rolled
 * {@link Entry} list + {@link ConfigReader} (used by
 * {@code LlmRouterTest} for plain-JUnit5 resolution tests without
 * spinning up Quarkus). Both delegate to the same priority chain so
 * the test exercises the production code path.
 *
 * <h2>Local-only conflict</h2>
 * <p>The router itself does NOT enforce the {@code local-only}
 * conflict — that's {@link LlmRouterStartupGuard}'s @Startup-time
 * concern. Decoupling the two keeps the per-call path simple
 * (every {@code forTask} would otherwise re-validate the same
 * config) and matches {@code docs/spec/llm.md} §Per-task routing
 * rules "This is checked once at startup, not per call."
 */
@ApplicationScoped
public class LlmRouter {

    private static final Logger LOG = Logger.getLogger(LlmRouter.class);

    /**
     * Configuration key that names the default provider when no
     * per-task override is set. Operators rarely change this in v1
     * (only one provider ships); future tickets that introduce
     * additional providers may flip the default per profile.
     */
    public static final String CONFIG_KEY_DEFAULT_PROVIDER = "infochat.llm.default.provider";

    private final List<Entry> entries;
    private final Map<String, Entry> entriesByName;
    private final ConfigReader config;

    /**
     * One-shot guard for the priority-3 unknown-default-provider WARN
     * (M1-042). Set true on the first {@link #forTask} call that
     * observes an operator-configured {@link #CONFIG_KEY_DEFAULT_PROVIDER}
     * naming a provider that resolves to no registered entry. The
     * audit-loud-fallback posture is documented in {@link #forTask}'s
     * priority-3 branch.
     */
    private final AtomicBoolean warnedUnknownDefault = new AtomicBoolean(false);

    /**
     * Test-friendly constructor: hand-supplied entries + config
     * reader. Bypasses CDI entirely so plain-JUnit5 unit tests can
     * exercise the priority chain without booting Quarkus. Production
     * code uses the {@link Inject}-annotated overload below.
     */
    public LlmRouter(List<Entry> entries, ConfigReader config) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException(
                "LlmRouter: at least one provider entry must be registered");
        }
        if (config == null) {
            throw new IllegalArgumentException("LlmRouter: ConfigReader must be non-null");
        }
        this.entries = List.copyOf(entries);
        this.config = config;
        Map<String, Entry> byName = new LinkedHashMap<>();
        for (Entry e : this.entries) {
            byName.put(e.name(), e);
        }
        this.entriesByName = Map.copyOf(byName);
    }

    /**
     * CDI-friendly delegating constructor. Builds the production
     * entry list from the live {@link LlmProvider} beans + the
     * MicroProfile {@link Config}. This is the only @Inject ctor;
     * the test constructor above is unmarked so ArC picks this one
     * for bean instantiation.
     */
    @Inject
    public LlmRouter(Instance<LlmProvider> providers, Config mpConfig) {
        this(buildFromCdi(providers, mpConfig), new MicroProfileConfigReader(mpConfig));
    }

    /**
     * Resolve a provider for the given task + scope language. Returns
     * exactly one {@link LlmProvider}. Throws if no provider is
     * registered for the resolved name.
     */
    public LlmProvider forTask(@NonNull ModelTask task, @Nullable String scopeLanguage) {
        if (task == null) {
            throw new IllegalArgumentException("LlmRouter.forTask: task must be non-null");
        }
        String lang = scopeLanguage == null ? "en" : scopeLanguage.toLowerCase(Locale.ROOT);

        // Priority 1: per-task override property.
        String overrideKey = perTaskOverrideKey(task);
        Optional<String> overrideName = config.get(overrideKey);
        if (overrideName.isPresent() && !overrideName.get().isEmpty()) {
            Entry entry = entriesByName.get(overrideName.get());
            if (entry == null) {
                throw new IllegalStateException(
                    "LlmRouter: per-task override " + overrideKey + "=" + overrideName.get()
                        + " names no registered provider (registered: " + entriesByName.keySet() + ")");
            }
            return entry.provider();
        }

        // Priority 2: language-aware capability check (SUMMARIZER /
        // TRANSLATOR only; SECURITY_JUDGE is language-agnostic per
        // docs/spec/llm.md §Per-task routing rules).
        if (isLanguageAwareTask(task) && !"en".equalsIgnoreCase(lang)) {
            for (Entry e : entries) {
                // supportedLanguages() is @Nullable per the Entry component
                // contract; a null reads as "no declared language" and skips
                // the entry, matching the compact constructor's null→empty
                // normalization.
                Set<String> supported = e.supportedLanguages();
                if (supported != null && supported.contains(lang)) {
                    return e.provider();
                }
            }
        }

        // Priority 3: profile default (or the configured default
        // provider name).
        Optional<String> configuredDefault = config.get(CONFIG_KEY_DEFAULT_PROVIDER)
            .filter(s -> !s.isEmpty());
        String defaultName = configuredDefault.orElse(OpenAiCompatibleProvider.PROVIDER_NAME);
        Entry defaultEntry = entriesByName.get(defaultName);
        if (defaultEntry == null) {
            // M1-042 audit-loud-fallback posture: when the operator
            // EXPLICITLY configured infochat.llm.default.provider but
            // the named provider resolves to no registered LlmProvider,
            // emit a one-shot WARN (per JVM) naming the configured
            // value AND the registered provider set AND the fallback,
            // then proceed with entries.get(0). Replaces M1-033's
            // silent fall-back which would route SECURITY_JUDGE (and
            // every other task with no per-task override) to whatever
            // bean CDI discovery happened to list first — a typo would
            // silently re-route every Stage 2 call.
            //
            // Fail-loud-fallback (not fail-startup) is the chosen
            // posture because (1) M1-042 §out_of_scope forbids touching
            // LlmRouterStartupGuard, where a startup-time guard would
            // most naturally live, and (2) existing test fixtures (e.g.
            // Stage2WorkerIT.TestStubLlmProvider, registered under its
            // class simple-name) rely on the legacy silent-fallback
            // behavior when CONFIG_KEY_DEFAULT_PROVIDER defaults to
            // OpenAiCompatibleProvider.PROVIDER_NAME (absent from the
            // test classpath). The unconfigured-default case (operator
            // never set the key) does NOT emit the WARN — only an
            // EXPLICIT operator-set value that fails to resolve is
            // treated as misconfiguration.
            if (configuredDefault.isPresent()
                    && warnedUnknownDefault.compareAndSet(false, true)) {
                LOG.warnf(
                    "LlmRouter: %s='%s' is an unknown default provider "
                        + "(registered providers: %s); falling back to first "
                        + "registered entry '%s'. This warning is logged once "
                        + "per JVM; correct the operator config to silence it.",
                    CONFIG_KEY_DEFAULT_PROVIDER, defaultName,
                    entriesByName.keySet(), entries.get(0).name());
            }
            defaultEntry = entries.get(0);
        }
        return defaultEntry.provider();
    }

    /**
     * Startup-time assertion that every {@link ModelTask} resolves to a
     * registered provider under the current config. A per-task provider
     * override naming a provider with no registered {@link Entry} throws
     * {@link IllegalStateException} from {@link #forTask} here — at
     * startup — instead of at the first Stage 2 / digest call that
     * routes that task. Tasks with no override fall through to the
     * priority-3 default and never throw, so the scan surfaces exactly
     * the misroute case. Driven by {@link LlmRouterStartupGuard}.
     */
    public void assertAllTasksResolve() {
        for (ModelTask task : ModelTask.values()) {
            forTask(task, "en");
        }
    }

    /**
     * Returns whether the given task is one whose downstream output
     * has a target language that the language-aware capability
     * branch should consider. {@link ModelTask#SECURITY_JUDGE} is
     * intentionally not in this set — the judge's 4-token label
     * vocabulary is the same regardless of scope language.
     */
    private static boolean isLanguageAwareTask(ModelTask task) {
        return switch (task) {
            case SUMMARIZER, TRANSLATOR -> true;
            case SECURITY_JUDGE, TAGGER, ENTITY, CHAT_AGENT -> false;
        };
    }

    /**
     * The per-task override property key shape:
     * {@code infochat.llm.<task-lowercase>.provider}. Stage 2's
     * security judge resolves to
     * {@code infochat.llm.security.provider} per the design's
     * abbreviation of {@code SECURITY_JUDGE} → {@code security}.
     */
    private static String perTaskOverrideKey(ModelTask task) {
        return "infochat.llm." + task.keySegment() + ".provider";
    }

    /**
     * Build the production entry list from the live {@link LlmProvider}
     * beans Quarkus discovered. In v1, only
     * {@link OpenAiCompatibleProvider} is registered; future ticket
     * authors add their providers here with capability sets that
     * reflect what their backend can produce. Capability assignment
     * is router-internal because the SPI surface is frozen
     * (M1-007b) and adding a {@code capabilities()} method would
     * violate this ticket's out-of-scope list.
     *
     * <p>A test {@code @Alternative} provider (e.g.
     * {@code Stage2WorkerIT.TestStubLlmProvider}) is registered
     * under its class simple-name so the router has at least one
     * resolvable entry; the priority-3 fallback returns it even
     * when {@link #CONFIG_KEY_DEFAULT_PROVIDER} names a different
     * provider that isn't on the test classpath.
     */
    private static List<Entry> buildFromCdi(Instance<LlmProvider> providers, Config mpConfig) {
        List<Entry> out = new ArrayList<>();
        for (LlmProvider p : providers) {
            String name = p.providerName();
            Set<String> langs = supportedLanguagesFor(p, mpConfig);
            out.add(new Entry(name, p, langs));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException(
                "LlmRouter: no LlmProvider beans discovered via CDI; "
                    + "at least OpenAiCompatibleProvider must be on the classpath");
        }
        return out;
    }

    /**
     * Config-driven capability set for a CDI-discovered provider.
     * Reads {@code infochat.llm.<providerName>.languages} (comma-
     * separated ISO 639-1 codes, e.g. {@code en,cs}); defaults to
     * {@code Set.of("en")} when the key is absent or empty so
     * existing deployments that do not configure the key are
     * byte-identical to the pre-config-driven behavior.
     *
     * <p>Package-private (not private) so {@link LlmRouterTest} can
     * exercise the helper directly with a hand-rolled {@link Config}.
     */
    static Set<String> supportedLanguagesFor(LlmProvider p, Config config) {
        String key = "infochat.llm." + p.providerName() + ".languages";
        Optional<String> raw = config.getOptionalValue(key, String.class);
        if (raw.isEmpty() || raw.get().isBlank()) {
            return Set.of("en");
        }
        Set<String> langs = new LinkedHashSet<>();
        for (String part : raw.get().split(",")) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                langs.add(trimmed);
            }
        }
        return langs.isEmpty() ? Set.of("en") : Set.copyOf(langs);
    }

    /**
     * One named, capability-tagged provider registration. Inlined as
     * a nested record so the router compiles as a self-contained
     * unit and the file-budget stays at 13.
     *
     * @param name                stable, operator-visible provider id.
     * @param provider            the live {@link LlmProvider} instance.
     * @param supportedLanguages  ISO 639-1 codes the provider can
     *                            emit. Empty means "any" — the
     *                            language-aware branch skips empty
     *                            sets so a generic provider doesn't
     *                            front-run a capability-declaring one.
     */
    public record Entry(@NonNull String name, @NonNull LlmProvider provider, @Nullable Set<String> supportedLanguages) {
        public Entry {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Entry.name must be non-empty");
            }
            if (provider == null) {
                throw new IllegalArgumentException("Entry.provider must be non-null");
            }
            supportedLanguages = supportedLanguages == null
                ? Set.of()
                : Set.copyOf(supportedLanguages);
        }
    }

    /**
     * Minimal config-key reader. Production wraps MicroProfile
     * {@link Config}; tests pass a {@link Map}-backed lambda. The
     * abstraction exists so the test constructor doesn't depend on
     * Quarkus's config infrastructure.
     */
    @FunctionalInterface
    public interface ConfigReader {
        Optional<String> get(@NonNull String key);

        static ConfigReader fromMap(@NonNull Map<String, String> map) {
            Map<String, String> snap = Map.copyOf(map);
            return key -> Optional.ofNullable(snap.get(key));
        }
    }

    /** Adapter from MicroProfile {@link Config} to {@link ConfigReader}. */
    private static final class MicroProfileConfigReader implements ConfigReader {
        private final Config delegate;

        MicroProfileConfigReader(Config delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<String> get(@NonNull String key) {
            // The literal string "null" (case-insensitive) is normalized
            // to empty so it reads as "no override set". Some config
            // sources stringify an explicitly-unset/null value as the
            // four-character text "null" (e.g. an env var exported as
            // `=null`, or a YAML null serialized through a String
            // converter); without this, forTask would look up a provider
            // named "null", find no Entry, and throw — surprising an
            // operator who meant "leave it unset". This is config-boundary
            // normalization, not internal defensive code.
            return delegate.getOptionalValue(key, String.class)
                .map(s -> s.trim())
                .map(s -> s.toLowerCase(Locale.ROOT).equals("null") ? "" : s);
        }
    }
}
