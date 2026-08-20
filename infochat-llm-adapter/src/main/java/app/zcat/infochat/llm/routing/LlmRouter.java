package app.zcat.infochat.llm.routing;

import org.jspecify.annotations.Nullable;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.impl.OpenAiCompatibleProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 *       {@link OpenAiCompatibleProvider#PROVIDER_NAME} in v1).</li>
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
     * per-task override is set. Operators rarely change this in v1;
     * it defaults to {@link OpenAiCompatibleProvider#PROVIDER_NAME}
     * even though more than one provider now ships.
     */
    public static final String CONFIG_KEY_DEFAULT_PROVIDER = "infochat.llm.default.provider";

    /**
     * Configuration key for the shared default endpoint every
     * {@link ModelTask} inherits when its per-task
     * {@code infochat.llm.<task>.base-url} is unset (per-task always
     * wins). One deployment runs one LLM service in practice (D56), so
     * the endpoint lives here once instead of being fanned out per
     * task; a task with NEITHER key refuses startup rather than
     * inheriting a baked address that may not be served on this host
     * (the M1-597 classifier incident).
     */
    public static final String CONFIG_KEY_DEFAULT_BASE_URL = "infochat.llm.default.base-url";

    /**
     * Companion shared default for the per-task
     * {@code infochat.llm.<task>.api-key}: per-task wins; else this key
     * is inherited ONLY when the task's base-url also resolved from
     * {@link #CONFIG_KEY_DEFAULT_BASE_URL}; else empty (local backends
     * need no key). The coupling is a security property (redteam
     * 2026-07-11, M1-603): the default credential travels only to the
     * default endpoint — a task whose base-url is pinned per-task never
     * receives the deployment-wide key implicitly, because the pinned
     * endpoint is a party that key was not minted for. A pinned route
     * that needs the key restates it via the per-task api-key.
     */
    public static final String CONFIG_KEY_DEFAULT_API_KEY = "infochat.llm.default.api-key";

    /**
     * Single message for the "no provider registered" misconfiguration,
     * shared by both detection sites — the test-seam constructor's
     * {@code entries.isEmpty()} check and the CDI factory's
     * {@code out.isEmpty()} check — so both throw an identical
     * {@link IllegalStateException}. The router uses
     * {@code IllegalStateException} for every config-shape misconfiguration
     * (unknown override / default provider); an empty provider set is the
     * same class of fault, so it no longer throws the odd-one-out
     * {@code IllegalArgumentException} it once did from the seam ctor. (M1-357)
     */
    static final String NO_PROVIDERS_REGISTERED_MESSAGE =
        "LlmRouter: at least one LlmProvider must be registered";

    /**
     * Models cleared for the native tool transport — the measured
     * (model, transport) pairs a committed bar-clearing record vouches
     * for. EMPTY at landing: no measured pair exists, every endpoint
     * resolves TEXT, and the transport probe never fires.
     */
    private static final Set<String> NATIVE_TOOL_TRANSPORT_CLEARED_MODELS = Set.of();

    /**
     * The minimal tools-bearing body the transport probe sends: one
     * declaration, no real tool — the probe measures the endpoint's
     * acceptance of the SHAPE, not any answer.
     */
    private static final List<LlmProvider.ToolDeclaration> TRANSPORT_PROBE_DECLARATIONS =
        List.of(new LlmProvider.ToolDeclaration("transport_probe", "transport probe",
            "{\"type\":\"object\",\"properties\":{}}"));

    /** The user-prompt sentinel of the transport probe request. */
    public static final String TRANSPORT_PROBE_PROMPT = "transport probe";

    /** The tool-call transport a resolved endpoint serves. */
    public enum ToolTransport {
        TEXT, NATIVE
    }

    private final List<Entry> entries;
    private final Map<String, Entry> entriesByName;
    private final ConfigReader config;
    private final Set<String> nativeToolTransportClearedModels;

    /**
     * Sticky per-task tool-transport resolutions (one resolution per
     * task and endpoint, never a per-call fallback chain).
     */
    private final ConcurrentHashMap<ModelTask, ToolTransport> toolTransportResolutions =
        new ConcurrentHashMap<>();

    /**
     * One-shot guard for the priority-3 unknown-default-provider WARN.
     * Set true on the first {@link #forTask} call that
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
        this(entries, config, NATIVE_TOOL_TRANSPORT_CLEARED_MODELS);
    }

    /** Test seam: arms the cleared-set with measured (model) pairs. */
    public LlmRouter(List<Entry> entries, ConfigReader config,
              Set<String> nativeToolTransportClearedModels) {
        if (entries.isEmpty()) {
            throw new IllegalStateException(NO_PROVIDERS_REGISTERED_MESSAGE);
        }
        this.entries = List.copyOf(entries);
        this.config = config;
        this.nativeToolTransportClearedModels = Set.copyOf(nativeToolTransportClearedModels);
        // Key by the lower-cased provider name so lookups are
        // case-insensitive, agreeing with LlmRouterStartupGuard (which
        // lower-cases the operator-supplied name before matching the
        // remote-provider set). Without this, a mixed-case
        // default.provider / <task>.provider value that the guard treats
        // as a known provider would resolve to no Entry here.
        Map<String, Entry> byName = new LinkedHashMap<>();
        for (Entry e : this.entries) {
            byName.put(e.name().toLowerCase(Locale.ROOT), e);
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
    public LlmProvider forTask(ModelTask task, @Nullable String scopeLanguage) {
        String lang = scopeLanguage == null ? "en" : scopeLanguage.toLowerCase(Locale.ROOT);

        // Priority 1: per-task override property.
        String overrideKey = perTaskOverrideKey(task);
        Optional<String> overrideName = config.get(overrideKey);
        if (overrideName.isPresent() && !overrideName.get().isEmpty()) {
            Entry entry = entriesByName.get(overrideName.get().toLowerCase(Locale.ROOT));
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
                if (e.supportedLanguages().contains(lang)) {
                    return e.provider();
                }
            }
        }

        // Priority 3: profile default (or the configured default
        // provider name).
        Optional<String> configuredDefault = config.get(CONFIG_KEY_DEFAULT_PROVIDER)
            .filter(s -> !s.isEmpty());
        String defaultName = configuredDefault.orElse(OpenAiCompatibleProvider.PROVIDER_NAME);
        Entry defaultEntry = entriesByName.get(defaultName.toLowerCase(Locale.ROOT));
        if (defaultEntry == null) {
            // Audit-loud-fallback posture: when the operator
            // EXPLICITLY configured infochat.llm.default.provider but
            // the named provider resolves to no registered LlmProvider,
            // emit a one-shot WARN (per JVM) naming the configured
            // value AND the registered provider set AND the fallback,
            // then proceed with entries.get(0). The earlier silent
            // fall-back would route SECURITY_JUDGE (and every other
            // task with no per-task override) to whatever bean CDI
            // discovery happened to list first — a typo would silently
            // re-route every Stage 2 call.
            //
            // Fail-loud-fallback (not fail-startup) is the chosen
            // posture because existing test fixtures (e.g.
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
     * Whether the provider {@link #forTask} resolves for {@code task}
     * reports streaming support — the explicit capability signal of
     * {@link LlmProvider#supportsStreaming}, surfaced through the same
     * resolution chain (and decorator stack) a live call uses. A
     * provider that cannot stream reports exactly that
     * ({@code false}); nothing in the chain assumes streaming.
     */
    public boolean streamingSupportedFor(ModelTask task, @Nullable String scopeLanguage) {
        return forTask(task, scopeLanguage).supportsStreaming(task);
    }

    /**
     * The tool-call transport the resolved endpoint serves for
     * {@code task}: NATIVE only on cleared-set membership AND a
     * successful bounded probe (docs/spec/llm.md §Tool-call
     * transport); any doubt resolves TEXT. Sticky per task.
     */
    public ToolTransport toolTransportFor(ModelTask task, @Nullable String scopeLanguage) {
        return toolTransportResolutions.computeIfAbsent(task,
            t -> resolveToolTransport(t, scopeLanguage));
    }

    private ToolTransport resolveToolTransport(ModelTask task, @Nullable String scopeLanguage) {
        String model = config.get(task.configPrefix() + "model").orElse("");
        ToolTransport resolved;
        if (!nativeToolTransportClearedModels.contains(model)) {
            // Cleared-set miss: TEXT, provider untouched.
            resolved = ToolTransport.TEXT;
        } else {
            LlmProvider provider = forTask(task, scopeLanguage);
            if (!provider.supportsToolCalls(task)) {
                resolved = ToolTransport.TEXT;
            } else {
                boolean probeAccepted;
                try {
                    provider.generateWithTools(task, "", TRANSPORT_PROBE_PROMPT,
                        TRANSPORT_PROBE_DECLARATIONS);
                    probeAccepted = true;
                } catch (RuntimeException e) {
                    // Any doubt downgrades to TEXT — no endpoint error-string
                    // matching (the serving-stack assumption stays non-load-bearing).
                    probeAccepted = false;
                }
                resolved = probeAccepted ? ToolTransport.NATIVE : ToolTransport.TEXT;
            }
        }
        // One resolution log per (task, endpoint), naming the effective
        // endpoint (per-task base-url, else the shared default) so an
        // operator with per-task overrides sees WHICH endpoint resolved.
        LOG.infof("LlmRouter: tool transport for task %s resolved %s (endpoint %s)",
            task.keySegment(), resolved, endpointLabelFor(task));
        return resolved;
    }

    /**
     * {@code host:port} of the task's effective base-url (per-task key,
     * else the shared default — the providers' own resolution), for the
     * resolution log. Never throws; unset/unparseable renders a placeholder.
     */
    private String endpointLabelFor(ModelTask task) {
        String baseUrl = config.get(task.configPrefix() + "base-url")
            .or(() -> config.get(CONFIG_KEY_DEFAULT_BASE_URL))
            .orElse("");
        if (baseUrl.isEmpty()) {
            return "<unset>";
        }
        try {
            URI uri = new URI(baseUrl);
            if (uri.getHost() == null || uri.getHost().isEmpty()) {
                return "<no-host>";
            }
            return uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
        } catch (URISyntaxException e) {
            return "<unparseable>";
        }
    }

    /**
     * Startup-time assertion that every {@link ModelTask} resolves to a
     * registered provider under the current config, AND that the
     * resolved provider's per-task config resolves. A per-task provider
     * override naming a provider with no registered {@link Entry} throws
     * {@link IllegalStateException} from {@link #forTask} here — at
     * startup — instead of at the first Stage 2 / digest call that
     * routes that task. The
     * {@link LlmProvider#assertTaskConfigResolvable} leg makes a
     * missing or typoed required per-task property (e.g. an absent
     * {@code infochat.llm.security.model}) throw here too; without it,
     * the lazy per-call config read would surface only at the first
     * live call, where workers' retry-then-fallback catch converts the
     * permanent misconfiguration into an indefinite silent fallback.
     * Driven by {@link LlmRouterStartupGuard}.
     *
     * <p>Beyond the {@code "en"} sweep, every language-aware task is
     * also probed for each non-English language any registered entry
     * declares — the exact pairs the priority-2 capability branch can
     * resolve at runtime. A deployment whose languages config routes,
     * say, TRANSLATOR to a provider with an incomplete per-task config
     * fails here at startup instead of at the first non-English call.
     * Only configured languages are probed: enumerating languages no
     * entry declares would validate providers no route can reach.
     */
    public void assertAllTasksResolve() {
        // An explicitly configured default provider naming no registered
        // entry is an operator typo that forTask's priority-3 absorbs as an
        // audit-loud fallback to entries.get(0) for the JVM lifetime —
        // silently rerouting every task with no per-task override. Fail boot
        // here instead. The implicit default (key absent) is intentionally
        // NOT failed: test fixtures register a single stub under a name that
        // need not match the v1 default, and rely on the silent fallback.
        config.get(CONFIG_KEY_DEFAULT_PROVIDER)
            .filter(name -> !name.isEmpty())
            .ifPresent(name -> {
                if (!entriesByName.containsKey(name.toLowerCase(Locale.ROOT))) {
                    throw new IllegalStateException(
                        "LlmRouter: " + CONFIG_KEY_DEFAULT_PROVIDER + "='" + name
                            + "' names no registered provider (registered: "
                            + entriesByName.keySet() + ")");
                }
            });
        Set<String> configuredLanguages = new LinkedHashSet<>();
        for (Entry e : entries) {
            configuredLanguages.addAll(e.supportedLanguages());
        }
        configuredLanguages.remove("en");
        for (ModelTask task : ModelTask.values()) {
            forTask(task, "en").assertTaskConfigResolvable(task);
            if (!isLanguageAwareTask(task)) {
                continue;
            }
            for (String language : configuredLanguages) {
                forTask(task, language).assertTaskConfigResolvable(task);
            }
        }
        // Streaming-capability coherence, same posture as the sweep
        // above: the CHAT_AGENT capability signal is evaluated through
        // the exact resolution path a live streaming call gates on, so
        // a broken signal (one that throws) fails boot here instead of
        // the first live call. A false verdict is the supported
        // cannot-stream posture and must NOT fail boot — the caller
        // degrades to non-streaming, and the per-task config the
        // streaming call resolves is already asserted above.
        streamingSupportedFor(ModelTask.CHAT_AGENT, "en");
        // Tool-transport resolution, same startup posture: resolved once
        // here and fail-safe to TEXT; the probe stays disarmed until a
        // measured (model, transport) pair enters the cleared-set.
        toolTransportFor(ModelTask.CHAT_AGENT, "en");
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
            case SECURITY_JUDGE, TAGGER, ENTITY, CLASSIFIER, CHAT_AGENT -> false;
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
        return task.providerKey();
    }

    /**
     * Build the production entry list from the live {@link LlmProvider}
     * beans Quarkus discovered ({@link OpenAiCompatibleProvider} and
     * {@code AnthropicProvider} in v1). A new provider is added here
     * with a capability set that reflects what its backend can produce.
     * Capability assignment is router-internal because the LlmProvider
     * SPI surface is deliberately kept minimal — a {@code capabilities()}
     * method on the SPI is intentionally avoided.
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
            throw new IllegalStateException(NO_PROVIDERS_REGISTERED_MESSAGE);
        }
        // Deterministic order: CDI bean-discovery order is not stable across
        // services or restarts, yet both the priority-2 language-tie
        // first-match and the priority-3 entries.get(0) fallback read
        // positional order. Sorting by provider name makes the same config
        // route identically everywhere — a project determinism pillar.
        out.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
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
        String key = ModelTask.languagesKey(p.providerName());
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
     *                            emit. Must be non-null; empty means
     *                            "no declared language", which the
     *                            language-aware branch skips so a
     *                            generic provider doesn't front-run a
     *                            capability-declaring one.
     */
    public record Entry(String name, LlmProvider provider, Set<String> supportedLanguages) {
        /**
         * Compact canonical constructor enforcing the real contract:
         * {@code name} non-empty and {@code supportedLanguages} non-null
         * (an empty set is the legitimate "no declared languages" value).
         * The defensive copy keeps the component immutable, and
         * {@link Set#copyOf} rejects a null argument — so a null
         * {@code supportedLanguages} is a programming error, not a
         * tolerated input.
         */
        public Entry {
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Entry.name must be non-empty");
            }
            supportedLanguages = Set.copyOf(supportedLanguages);
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
        Optional<String> get(String key);

        static ConfigReader fromMap(Map<String, String> map) {
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
        public Optional<String> get(String key) {
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
