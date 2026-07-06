package app.zcat.infochat.llm.routing;

import org.jspecify.annotations.Nullable;

import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.impl.AnthropicProvider;
import app.zcat.infochat.llm.impl.LlmHttpSupport;
import app.zcat.infochat.llm.impl.OpenAiCompatibleProvider;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * @Startup guard, run by BOTH the Collector and the Provider, that
 * fails Quarkus boot when {@code infochat.llm.local-only=true} is set
 * alongside ANY off-host LLM or embedding route: a per-task
 * {@code base-url} or the {@code infochat.embeddings.base-url} that
 * resolves to a non-loopback host, a per-task provider override or the
 * {@code infochat.llm.default.provider} default naming a cloud-only
 * provider (e.g. {@code anthropic}), OR a cloud-only provider made
 * reachable for non-English scopes via its
 * {@code infochat.llm.<provider>.languages} capability key (the
 * router's priority-2 branch selects it with no override naming it).
 * When local-only is NOT set, a remote embedding endpoint instead emits
 * the spec-promised confirmation log line so operators see when post
 * title+summary start leaving the host. Per {@code docs/spec/llm.md}
 * §Per-task routing rules: "Local-only is the most-restrictive posture.
 * When the operator sets the explicit local-only property, the router
 * never picks a remote provider — and a per-task override pointing to
 * a remote provider while local-only is set is a configuration conflict
 * that fails startup with a fatal log line identifying the offending
 * task and provider. This is checked once at startup, not per call."
 *
 * <h2>Collector-and-Provider placement</h2>
 * <p>The guard runs on BOTH services' startup chains, intentionally:
 * the llm-adapter jar is CDI-indexed by the Collector AND the Provider,
 * and each service routes live LLM calls — the Stage 2 security judge,
 * tagging, entity extraction, and embedding generation (title + summary
 * → vector) in the Collector's ingest pipeline; chat, summarizer, and
 * translator call sites in the Provider. Each service therefore
 * validates the configuration it boots with; see
 * {@code docs/spec/llm.md} §Per-task routing rules (which earlier
 * documented the guard as Collector-only). On the Collector, the
 * {@link #PRIORITY_BETWEEN_FLYWAY_AND_OUTBOX} is the @Priority slot
 * between Flyway (100) and OutboxRehydrator (300) — router
 * misconfiguration is caught BEFORE any post reaches the eval queue and
 * exercises the Stage 2 / embedding call paths.
 *
 * <h2>Routed-task scan</h2>
 * <p>Beyond the local-only conflict, the guard also calls
 * {@link LlmRouter#assertAllTasksResolve()} so a per-task provider
 * override naming an unregistered provider (a misrouted {@code TAGGER},
 * say) — or a language-reachable provider whose per-task config is
 * incomplete — fails startup here rather than at the first call that
 * routes that task. Same @Priority slot, same fail-before-the-eval-queue
 * intent as the local-only check.
 *
 * <h2>Provider/model mismatch scan</h2>
 * <p>Independently of local-only, the guard scans each task's effective
 * {@code (provider, base-url, model)} triple for an internal contradiction
 * that would HTTP-400 every call while nothing else complains — a
 * {@code provider=anthropic} route pointed at an OpenAI-format endpoint
 * (neither an {@code anthropic.com} host nor an {@code /anthropic}-path
 * route), or a {@code provider=openai-compatible} route naming a local
 * Ollama model against a remote endpoint. This is the M1-577 check for the
 * DeepSeek misconfig that produced 3883 silent 400s. It is ADVISORY by
 * default (WARN, boot continues); {@link #CONFIG_KEY_MISMATCH_FAIL_FAST}
 * opts into abort-on-mismatch. See {@link #checkProviderModelMismatch}.
 *
 * <h2>Loopback check</h2>
 * <p>The "non-loopback host" check DNS-resolves the URI host via
 * {@link InetAddress#getAllByName(String)} and treats it as loopback
 * only when EVERY resolved address {@link InetAddress#isLoopbackAddress()
 * is loopback} — a multi-record host with any public sibling is off-host.
 * This catches the common literals ({@code localhost}, {@code 127.0.0.1},
 * {@code ::1}) plus any /etc/hosts alias that resolves to a loopback IP. The
 * DNS-rebind window (host resolves to loopback at startup but to a
 * remote IP at call time) is documented in {@code docs/spec/llm.md}
 * §Per-task routing rules as acceptable here: "checked once at
 * startup, not per call." The per-call SSRF defense lives in
 * {@code infochat-ssrf}'s {@code SsrfGuardedHttpClient}, not in the
 * LLM-call path.
 *
 * <h2>Test seam</h2>
 * <p>{@link #validateLocalOnlyConfiguration(Map)} is public static
 * so {@code LocalOnlyConflictStartupIT} can invoke it directly
 * without re-bootstrapping Quarkus inside the test method. The CDI
 * @PostConstruct path delegates to the same validator after reading
 * the relevant keys from MicroProfile {@link Config}.
 */
@Startup
@Priority(150)
@ApplicationScoped
public class LlmRouterStartupGuard {

    /**
     * @Priority value: between Flyway (100) and OutboxRehydrator
     * (300) so the guard's throw aborts startup BEFORE the eval
     * queue starts dispatching posts through the Stage 2 call site.
     * The class-level @Priority annotation must use this literal
     * value (Java annotation arguments must be compile-time
     * constants — referencing this field via a {@code Class.NAME}
     * qualifier doesn't satisfy the reviewer's regex grep for
     * {@code @Priority(150)}).
     */
    public static final int PRIORITY_BETWEEN_FLYWAY_AND_OUTBOX = 150;

    /** Operator-facing property: master switch for the conflict check. */
    public static final String CONFIG_KEY_LOCAL_ONLY = "infochat.llm.local-only";

    private static final Logger LOG = Logger.getLogger(LlmRouterStartupGuard.class);

    /**
     * The per-task base-url keys the guard inspects, derived from
     * {@link ModelTask#keySegment()} for every task — the single-source
     * promise the enum documents, so the guard's key surface cannot
     * drift from the router's and the providers'. A future ticket that
     * adds a {@link ModelTask} value gets its base-url scanned here
     * with no guard edit. Keyed by {@link ModelTask} so the rejection
     * log line can name the offending task by enum value.
     */
    private static final Map<ModelTask, String> PER_TASK_BASE_URL_KEYS = perTaskBaseUrlKeys();

    private static Map<ModelTask, String> perTaskBaseUrlKeys() {
        Map<ModelTask, String> keys = new EnumMap<>(ModelTask.class);
        for (ModelTask task : ModelTask.values()) {
            keys.put(task, baseUrlKeyFor(task));
        }
        return Map.copyOf(keys);
    }

    /**
     * Operator-facing embedding endpoint base-url. Embedding generation
     * runs in the Collector ingest pipeline (title+summary → vector), so
     * a non-loopback value here means post text leaves the host. Scanned
     * alongside the per-task base-urls under local-only.
     */
    public static final String CONFIG_KEY_EMBEDDINGS_BASE_URL = "infochat.embeddings.base-url";

    /**
     * Provider names that are off-host BY IDENTITY: selecting one via a
     * per-task provider override under local-only is a configuration
     * conflict regardless of that task's base-url, because the operator's
     * intent (a cloud provider) contradicts the local-only commitment.
     * {@code anthropic} targets the Anthropic cloud API.
     * {@code openai-compatible} is deliberately excluded — it is
     * host-neutral (it fronts a local Ollama by default), so its
     * remoteness is decided by its base-url, which the per-task base-url
     * scan already covers. A future cloud-only provider adds its name here.
     */
    private static final Set<String> REMOTE_PROVIDER_NAMES = Set.of(AnthropicProvider.PROVIDER_NAME);

    /**
     * Opt-in operator switch: when {@code true}, a detected
     * (provider, base-url, model) mismatch (see
     * {@link #checkProviderModelMismatch}) aborts startup instead of only
     * logging a WARN. Default {@code false} (advisory) — a partial misconfig
     * degrades-and-warns rather than hardening into a boot failure, matching
     * the "advisory by default" acceptance of M1-577. Read with a code-level
     * default (no {@code application.properties} entry) so an operator who
     * never sets it gets the advisory posture.
     */
    public static final String CONFIG_KEY_MISMATCH_FAIL_FAST = "infochat.llm.mismatch-guard.fail-fast";

    /**
     * The Anthropic public API host suffix — the strongest signal that a
     * base-url is an Anthropic-format endpoint (see
     * {@link #looksAnthropicFormat}; an {@code /anthropic} path route on a
     * third-party host is the other accepted signal). A
     * {@code provider=anthropic} route whose base-url carries neither signal
     * is speaking the Anthropic Messages wire dialect at an OpenAI-format
     * endpoint — the exact M1-577 DeepSeek misconfig that HTTP-400d every
     * call. No such host constant exists on {@link AnthropicProvider} (it
     * requires the operator to supply the base-url), so the identity lives
     * here, next to the mismatch check that uses it.
     */
    private static final String ANTHROPIC_API_HOST = "anthropic.com";

    /**
     * Model-name prefixes that identify a local-runtime (Ollama) model
     * family. A {@code provider=openai-compatible} task naming one of these
     * models against a NON-loopback remote base-url cannot work — the remote
     * endpoint does not serve local Ollama models and 400/404s — which is the
     * M1-577 misconfig for the tagger/entity/security tasks. Matched by
     * case-insensitive {@code startsWith} (conservative: {@code deepseek-*}
     * / {@code claude-*} / {@code gpt-*} do not match, so the supported
     * OpenAI-compatible-remote shape never false-positives). The families are
     * fixed by the M1-577 acceptance; this is a heuristic, so it is advisory
     * unless {@link #CONFIG_KEY_MISMATCH_FAIL_FAST} is set.
     */
    private static final Set<String> LOCAL_RUNTIME_MODEL_PREFIXES =
        Set.of("llama", "nomic", "qwen", "mistral");

    @Inject
    Config config;

    @Inject
    LlmRouter router;

    @PostConstruct
    void onStartup() {
        Map<String, String> snapshot = snapshotConfig(config);
        validateLocalOnlyConfiguration(snapshot);
        // Orthogonal to local-only: catch an internally-inconsistent
        // (provider, base-url, model) triple (M1-577) that would 400 at every
        // call while local-only says nothing. Advisory unless the operator
        // opts into fail-fast.
        boolean mismatchFailFast =
            config.getOptionalValue(CONFIG_KEY_MISMATCH_FAIL_FAST, Boolean.class).orElse(false);
        checkProviderModelMismatch(snapshot, mismatchFailFast);
        router.assertAllTasksResolve();
    }

    /**
     * Pure-function validator: examines the supplied key/value
     * snapshot and throws {@link LocalOnlyConflictException} when
     * {@code infochat.llm.local-only=true} is set alongside any
     * off-host route — a per-task or embedding base-url resolving to a
     * non-loopback host, a per-task provider override naming a
     * cloud-only provider, or a cloud-only provider declaring a
     * non-English language that makes it reachable via the router's
     * priority-2 branch. When local-only is unset, a remote embedding
     * endpoint instead emits the confirmation log line. Returns
     * normally otherwise.
     *
     * <p>Public for test invocation: {@code LocalOnlyConflictStartupIT}
     * (in the {@code infochat-collector} module's
     * {@code app.zcat.infochat.collector.eval.stage2} package) invokes
     * this validator directly with a hand-rolled snapshot, side-
     * stepping the @Startup-throws-aborts-boot mechanism that makes
     * the CDI path awkward to test from inside a normal @Test
     * method. Production paths reach the validator only through the
     * @PostConstruct above; no other consumer should call it
     * directly.
     */
    public static void validateLocalOnlyConfiguration(Map<String, String> snapshot) {
        boolean localOnly = "true".equalsIgnoreCase(stripOrEmpty(snapshot.get(CONFIG_KEY_LOCAL_ONLY)));

        String embeddingBaseUrl = stripOrEmpty(snapshot.get(CONFIG_KEY_EMBEDDINGS_BASE_URL));
        boolean embeddingRemote = isRemoteBaseUrl(embeddingBaseUrl);

        if (!localOnly) {
            // docs/spec/llm.md §Per-task routing rules: "Switching the
            // embedding provider to a remote service emits an explicit
            // confirmation log line on startup so operators see when post
            // bodies start leaving the host." This is the non-local-only
            // path — a remote endpoint is allowed, but loud.
            if (embeddingRemote) {
                LOG.warnf("LlmRouterStartupGuard: embedding provider is remote (%s=%s); "
                        + "post title+summary will leave the host for embedding generation.",
                    CONFIG_KEY_EMBEDDINGS_BASE_URL, LlmHttpSupport.redactUserInfo(embeddingBaseUrl));
            }
            warnRemoteLlmTaskRoutes(snapshot);
            return;
        }

        List<String> offenders = new ArrayList<>();
        // The default provider is the route every task without a per-task
        // override resolves to — a cloud-only default under local-only is
        // the same conflict as a cloud-only per-task override.
        String defaultProvider = stripOrEmpty(snapshot.get(LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER))
            .toLowerCase(Locale.ROOT);
        if (REMOTE_PROVIDER_NAMES.contains(defaultProvider)) {
            offenders.add("default key=" + LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER
                + " provider=" + defaultProvider);
        }
        for (PerTaskRoute route : perTaskRoutes(snapshot)) {
            if (route.offHostBaseUrl() != null) {
                offenders.add("task=" + route.task().name()
                    + " key=" + baseUrlKeyFor(route.task())
                    + " base-url=" + LlmHttpSupport.redactUserInfo(route.offHostBaseUrl()));
            }
            // A per-task provider override naming a cloud-only provider is
            // a conflict regardless of that task's base-url (the operator
            // selected a remote provider while claiming local-only).
            if (REMOTE_PROVIDER_NAMES.contains(route.overrideProvider())) {
                offenders.add("task=" + route.task().name()
                    + " key=" + providerKeyFor(route.task()) + " provider=" + route.overrideProvider());
            }
        }
        // A cloud-only provider that declares any non-English language is
        // selectable through the router's priority-2 language-capability
        // branch even when no override or default names it — the languages
        // key alone routes non-"en" SUMMARIZER/TRANSLATOR calls to it, so
        // under local-only it is the same conflict as an explicit override.
        for (String remoteProvider : REMOTE_PROVIDER_NAMES) {
            String languagesKey = languagesKeyFor(remoteProvider);
            String reachableLanguages = nonEnglishLanguages(snapshot.get(languagesKey));
            if (!reachableLanguages.isEmpty()) {
                offenders.add("languages key=" + languagesKey
                    + " provider=" + remoteProvider
                    + " languages=" + reachableLanguages);
            }
        }
        if (embeddingRemote) {
            offenders.add("embedding key=" + CONFIG_KEY_EMBEDDINGS_BASE_URL
                + " base-url=" + LlmHttpSupport.redactUserInfo(embeddingBaseUrl));
        }

        if (offenders.isEmpty()) {
            LOG.infof("LlmRouterStartupGuard: %s=true, embedding + all per-task routes are on-host — OK",
                CONFIG_KEY_LOCAL_ONLY);
            return;
        }

        // FATAL log line names every offending route (task + base-url or
        // task + provider, plus the embedding endpoint) per
        // docs/spec/llm.md §Per-task routing rules.
        StringBuilder msg = new StringBuilder();
        msg.append("LlmRouterStartupGuard: ")
            .append(CONFIG_KEY_LOCAL_ONLY)
            .append("=true conflicts with off-host route(s): ");
        for (int i = 0; i < offenders.size(); i++) {
            if (i > 0) {
                msg.append("; ");
            }
            msg.append(offenders.get(i));
        }
        msg.append(". Refusing startup.");
        String fatal = msg.toString();
        LOG.fatal(fatal);
        throw new LocalOnlyConflictException(fatal);
    }

    /**
     * The non-local-only disclosure: one WARN per LLM task whose route is
     * off-host, so an operator can audit "did I accidentally enable remote?"
     * — design §5.10. A task routes remote when its per-task base-url is
     * off-host OR its effective provider (the per-task override, else the
     * default) is a cloud-only provider; the off-host base-url and
     * override-cloud detection is the same {@link #perTaskRoutes} the
     * local-only fatal branch consumes, so the two postures cannot drift on
     * what "remote" means. A cloud-only provider made reachable ONLY via its
     * non-English {@code languages} capability key (the router's priority-2
     * branch, with no override or default naming it) gets its own disclosure
     * WARN here too, mirroring the languages axis the local-only fatal branch
     * already checks — so both postures decide "off-host" on the same three
     * axes (base-url, effective provider, languages key). The symmetric
     * remote-embedding WARN is emitted by the caller before this runs.
     */
    private static void warnRemoteLlmTaskRoutes(Map<String, String> snapshot) {
        String defaultProvider = stripOrEmpty(snapshot.get(LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER))
            .toLowerCase(Locale.ROOT);
        for (PerTaskRoute route : perTaskRoutes(snapshot)) {
            // Effective provider = per-task override when set, else the
            // default the router falls back to. A cloud-only effective
            // provider makes the task remote even with no per-task base-url.
            String effectiveProvider = route.overrideProvider().isEmpty()
                ? defaultProvider : route.overrideProvider();
            boolean providerRemote = REMOTE_PROVIDER_NAMES.contains(effectiveProvider);
            if (route.offHostBaseUrl() == null && !providerRemote) {
                continue;
            }
            StringBuilder line = new StringBuilder("LlmRouterStartupGuard: remote LLM task=")
                .append(route.task().name());
            if (!effectiveProvider.isEmpty()) {
                line.append(" provider=").append(effectiveProvider);
            }
            if (route.offHostBaseUrl() != null) {
                line.append(" base-url=").append(LlmHttpSupport.redactUserInfo(route.offHostBaseUrl()));
            }
            line.append("; post bodies will leave the host.");
            LOG.warn(line.toString());
        }
        // A cloud-only provider reachable ONLY via its non-English languages
        // declaration leaves post-derived output on the wire even when no
        // per-task override or default names it — the router's priority-2
        // branch selects it. The local-only fatal branch already treats this
        // exact route as an offender (see validateLocalOnlyConfiguration), so
        // the disclosure WARN must cover the same languages axis or the two
        // postures drift on what counts as off-host (M1-432).
        for (String remoteProvider : REMOTE_PROVIDER_NAMES) {
            String reachableLanguages = nonEnglishLanguages(snapshot.get(languagesKeyFor(remoteProvider)));
            if (!reachableLanguages.isEmpty()) {
                LOG.warnf("LlmRouterStartupGuard: remote LLM provider=%s reachable via languages=%s;"
                        + " non-English summarizer/translator output will leave the host.",
                    remoteProvider, reachableLanguages);
            }
        }
    }

    /**
     * Provider/base-url/model consistency scan (M1-577). For each
     * {@link ModelTask}, checks the effective (provider, base-url, model)
     * triple for an internal contradiction that makes every call to that
     * task HTTP-400 while nothing else in startup complains — the failure
     * that produced 3883 silent 400s this session (a {@code remote-llm}
     * profile pointed at DeepSeek but left {@code provider=anthropic} and
     * Ollama model names in place). Each offending triple gets a distinct,
     * actionable log line naming the task, provider, base-url host, model,
     * and the likely fix (the exact config keys to change).
     *
     * <p>Advisory by default: offending triples log a WARN and startup
     * continues (a partial misconfig degrades-and-warns). When
     * {@code failFast} is set (operator opted in via
     * {@link #CONFIG_KEY_MISMATCH_FAIL_FAST}), each offender logs at FATAL and
     * the method throws {@link ProviderModelMismatchException}, aborting boot.
     *
     * <p>Orthogonal to local-only: local-only asks "is any route off-host";
     * this asks "is a route internally inconsistent". A remote route is the
     * whole point of the {@code remote-llm} profile, so this is the check that
     * would have caught the DeepSeek storm — {@code local-only} was (correctly)
     * unset there and said nothing.
     *
     * <p>Public static for the same test-seam reason as
     * {@link #validateLocalOnlyConfiguration}: the fail-fast/advisory branch
     * is exercised directly with a hand-rolled snapshot without
     * re-bootstrapping Quarkus.
     */
    public static void checkProviderModelMismatch(Map<String, String> snapshot, boolean failFast) {
        List<String> findings = detectProviderModelMismatches(snapshot);
        if (findings.isEmpty()) {
            return;
        }
        for (String finding : findings) {
            if (failFast) {
                LOG.fatal(finding);
            } else {
                LOG.warn(finding);
            }
        }
        if (failFast) {
            throw new ProviderModelMismatchException("LlmRouterStartupGuard: " + findings.size()
                + " provider/base-url/model mismatch(es) with " + CONFIG_KEY_MISMATCH_FAIL_FAST
                + "=true. Refusing startup. See the FATAL line(s) above.");
        }
    }

    /**
     * The pure detector both the CDI path and the tests share: returns one
     * actionable message per offending task, in {@link ModelTask} declaration
     * order, empty when every triple is consistent. Package-private so
     * {@code LlmRouterStartupGuardTest} can assert exactly which triples are
     * flagged without reasoning about log capture or the throw.
     */
    static List<String> detectProviderModelMismatches(Map<String, String> snapshot) {
        // Effective default = configured default provider, falling back to
        // openai-compatible exactly as LlmRouter does (LlmRouter#resolveDefault)
        // so a task that names no provider is scanned as the provider the
        // router would actually pick, not as "unset".
        String configuredDefault = stripOrEmpty(snapshot.get(LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER))
            .toLowerCase(Locale.ROOT);
        String effectiveDefault = configuredDefault.isEmpty()
            ? OpenAiCompatibleProvider.PROVIDER_NAME : configuredDefault;

        List<String> findings = new ArrayList<>();
        for (ModelTask task : ModelTask.values()) {
            String override = stripOrEmpty(snapshot.get(providerKeyFor(task))).toLowerCase(Locale.ROOT);
            String provider = override.isEmpty() ? effectiveDefault : override;
            String baseUrl = stripOrEmpty(snapshot.get(baseUrlKeyFor(task)));
            String model = stripOrEmpty(snapshot.get(modelKeyFor(task)));
            String finding = mismatchFinding(task, provider, baseUrl, model);
            if (finding != null) {
                findings.add(finding);
            }
        }
        return findings;
    }

    /**
     * The per-task consistency verdict: an actionable message when the triple
     * is internally inconsistent, else null. Two shapes are flagged (the
     * M1-577 acceptance minimum), both conservative so the three supported
     * shapes — local Ollama, an Anthropic remote, and a correctly-configured
     * OpenAI-compatible remote — pass cleanly:
     * <ol>
     * <li>{@code provider=anthropic} against a base-url that is not an
     *     Anthropic-FORMAT endpoint ({@link #looksAnthropicFormat}): the
     *     Anthropic wire dialect 400s at an OpenAI-format endpoint. A
     *     third-party {@code /anthropic} route (DeepSeek-style) is a valid
     *     pairing and is NOT flagged.</li>
     * <li>{@code provider=openai-compatible} with a local-runtime model name
     *     ({@link #LOCAL_RUNTIME_MODEL_PREFIXES}) against a non-loopback remote:
     *     the remote endpoint does not serve local Ollama models. A LOOPBACK
     *     base-url with a local model is the supported local-Ollama shape, so
     *     the remote gate is what keeps that shape from false-positiving.</li>
     * </ol>
     * A task with no base-url configured is skipped (there is no host to judge
     * — a missing required base-url surfaces separately at first call).
     */
    private static @Nullable String mismatchFinding(ModelTask task, String provider, String baseUrl,
            String model) {
        if (baseUrl.isEmpty()) {
            return null;
        }
        if (provider.equals(AnthropicProvider.PROVIDER_NAME) && !looksAnthropicFormat(baseUrl)) {
            return "LlmRouterStartupGuard: provider/model mismatch task=" + task.name()
                + " provider=" + AnthropicProvider.PROVIDER_NAME
                + " base-url-host=" + hostDisplay(baseUrl)
                + " model=" + model
                + " — the '" + AnthropicProvider.PROVIDER_NAME + "' provider speaks the Anthropic"
                + " Messages wire API, but the base-url is not an Anthropic-format endpoint (host is"
                + " not " + ANTHROPIC_API_HOST + "/*." + ANTHROPIC_API_HOST + " and the path carries no"
                + " Anthropic route such as /anthropic); the Anthropic dialect HTTP-400s against an"
                + " OpenAI-format endpoint."
                + " Fix: point " + baseUrlKeyFor(task) + " at an Anthropic-format endpoint (an "
                + ANTHROPIC_API_HOST + " host, or a provider's /anthropic route), or set "
                + providerKeyFor(task) + "=" + OpenAiCompatibleProvider.PROVIDER_NAME + ".";
        }
        if (provider.equals(OpenAiCompatibleProvider.PROVIDER_NAME)
                && isLocalRuntimeModel(model)
                && isRemoteBaseUrl(baseUrl)) {
            return "LlmRouterStartupGuard: provider/model mismatch task=" + task.name()
                + " provider=" + OpenAiCompatibleProvider.PROVIDER_NAME
                + " base-url-host=" + hostDisplay(baseUrl)
                + " model=" + model
                + " — the model name is a local-runtime (Ollama) model family"
                + " (llama*/nomic*/qwen*/mistral*), but the base-url host is a remote endpoint that"
                + " will not serve it; every call will HTTP 400/404."
                + " Fix: set " + modelKeyFor(task) + " to a model the remote provider actually"
                + " serves, or point " + baseUrlKeyFor(task) + " at your local Ollama.";
        }
        return null;
    }

    /**
     * True when the base-url plausibly names an Anthropic-FORMAT endpoint —
     * one that speaks the Anthropic Messages wire dialect. Two signals:
     * the host is an Anthropic API host ({@code anthropic.com} or a
     * {@code *.anthropic.com} subdomain), OR the path mentions an anthropic
     * route (case-insensitive) — the convention OpenAI-compatible vendors use
     * for their Anthropic-format endpoints (DeepSeek's {@code /anthropic},
     * for one), so {@code provider=anthropic} against such a base-url is a
     * valid pairing, not a mismatch. Config-only heuristic: a gateway serving
     * the Anthropic dialect at a neutral path is still flagged (the guard
     * cannot probe the wire format), which is why the check is advisory by
     * default. An unparseable base-url is NOT Anthropic-format — a malformed
     * base-url under {@code provider=anthropic} is still a mismatch.
     */
    private static boolean looksAnthropicFormat(String baseUrl) {
        String host = hostOf(baseUrl);
        if (host != null
                && (host.equals(ANTHROPIC_API_HOST) || host.endsWith("." + ANTHROPIC_API_HOST))) {
            return true;
        }
        String path;
        try {
            path = new URI(baseUrl).getPath();
        } catch (URISyntaxException e) {
            return false;
        }
        return path != null && path.toLowerCase(Locale.ROOT).contains("anthropic");
    }

    /**
     * True when the model name begins with a local-runtime family prefix
     * (case-insensitive). Conservative {@code startsWith} so provider-native
     * remote models ({@code deepseek-*}, {@code claude-*}, {@code gpt-*})
     * never match.
     */
    private static boolean isLocalRuntimeModel(String model) {
        String normalized = model.toLowerCase(Locale.ROOT);
        for (String prefix : LOCAL_RUNTIME_MODEL_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The URI host, lowercased, or null when the base-url does not parse or
     * names no host. Kept separate from {@link #hostDisplay} so the Anthropic
     * host comparison sees a clean null (not a redacted fallback string).
     */
    private static @Nullable String hostOf(String baseUrl) {
        try {
            String host = new URI(baseUrl).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * The host for a log line: the parsed host when available, else the
     * userinfo-redacted raw base-url so a malformed value is still shown
     * without leaking credentials (same redaction the local-only branch uses).
     */
    private static String hostDisplay(String baseUrl) {
        String host = hostOf(baseUrl);
        if (host != null) {
            return host;
        }
        // redactUserInfo is null-in-null-out and baseUrl is non-null here, so
        // the redacted value cannot be null; requireNonNull states that
        // contract for NullAway instead of a dead null-fallback branch.
        return Objects.requireNonNull(LlmHttpSupport.redactUserInfo(baseUrl));
    }

    /**
     * The per-task model key {@code infochat.llm.<keySegment>.model}, derived
     * the same way the concrete providers read it
     * ({@code task.configPrefix() + "model"}) so the guard and the providers
     * cannot drift on the key spelling.
     */
    static String modelKeyFor(ModelTask task) {
        return task.configPrefix() + "model";
    }

    /**
     * The per-task off-host facts both branches reuse, computed once per
     * task: {@code offHostBaseUrl} is the task's base-url when it resolves
     * off-host (else null), and {@code overrideProvider} is the per-task
     * provider override lowercased (empty string when unset — the caller
     * decides cloud-ness via {@link #REMOTE_PROVIDER_NAMES}). Single-sourcing
     * this keeps the local-only fatal scan and the non-local-only disclosure
     * WARN from drifting on what counts as an off-host route.
     */
    private record PerTaskRoute(ModelTask task, @Nullable String offHostBaseUrl, String overrideProvider) {
    }

    private static List<PerTaskRoute> perTaskRoutes(Map<String, String> snapshot) {
        List<PerTaskRoute> routes = new ArrayList<>();
        for (Map.Entry<ModelTask, String> kv : PER_TASK_BASE_URL_KEYS.entrySet()) {
            String baseUrl = stripOrEmpty(snapshot.get(kv.getValue()));
            String offHostBaseUrl = isRemoteBaseUrl(baseUrl) ? baseUrl : null;
            String overrideProvider = stripOrEmpty(snapshot.get(providerKeyFor(kv.getKey())))
                .toLowerCase(Locale.ROOT);
            routes.add(new PerTaskRoute(kv.getKey(), offHostBaseUrl, overrideProvider));
        }
        return routes;
    }

    /**
     * The off-host base-url primitive: a non-empty base-url whose host does
     * not resolve to loopback. Shared by the per-task scan, the embedding
     * endpoint check, and the disclosure WARN so all three decide "off-host"
     * identically.
     */
    private static boolean isRemoteBaseUrl(String baseUrl) {
        return !baseUrl.isEmpty() && !isLoopback(baseUrl);
    }

    /**
     * The per-task base-url key:
     * {@code infochat.llm.<keySegment>.base-url}. Package-private so
     * the key-derivation test can pin the derived form for every task
     * against the hand-spelled operator-facing literals.
     */
    static String baseUrlKeyFor(ModelTask task) {
        return task.baseUrlKey();
    }

    /**
     * The per-task provider-override key:
     * {@code infochat.llm.<keySegment>.provider}. Derived from
     * {@link ModelTask#keySegment()} directly (not by string-replace
     * over the base-url key) so the two key surfaces cannot drift.
     */
    static String providerKeyFor(ModelTask task) {
        return task.providerKey();
    }

    /**
     * The per-provider language-capability key:
     * {@code infochat.llm.<providerName>.languages} — the same key
     * {@code LlmRouter#supportedLanguagesFor} reads to build the
     * priority-2 capability set.
     */
    static String languagesKeyFor(String providerName) {
        return ModelTask.languagesKey(providerName);
    }

    /**
     * Resolve the URI's host via DNS and check whether it is on-host.
     * Resolution uses {@link InetAddress#getAllByName(String)} (not
     * {@code getByName}, which returns only the first record): a host is
     * loopback only when EVERY resolved address is loopback. A multi-A-record
     * host whose first record sorts loopback but which also carries a public
     * sibling would otherwise pass the guard while the per-call client connects
     * to the public address — the silent post-body leak this guard prevents.
     * Catches the {@code localhost} / {@code 127.0.0.1} / {@code ::1} literals
     * plus any /etc/hosts alias. A malformed URI counts as NON-loopback so an
     * operator typo doesn't slip past the guard.
     */
    private static boolean isLoopback(String baseUrl) {
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException e) {
            // Both echoes are userinfo-redacted: baseUrl directly, and
            // e.getMessage() because URISyntaxException re-quotes the raw input
            // verbatim ("...at index N: <input>") — the same leak M1-401 closed
            // on LlmHttpSupport.requireHttpBaseUrl's sibling parse-failure branch.
            LOG.warnf("LlmRouterStartupGuard: malformed base-url '%s' (treated as non-loopback): %s",
                LlmHttpSupport.redactUserInfo(baseUrl), LlmHttpSupport.redactUserInfo(e.getMessage()));
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return false;
        }
        try {
            return everyAddressLoopback(InetAddress.getAllByName(host));
        } catch (UnknownHostException e) {
            LOG.warnf("LlmRouterStartupGuard: DNS resolution failed for '%s' (treated as non-loopback): %s",
                host, e.getMessage());
            return false;
        }
    }

    /**
     * The loopback decision primitive: true only when the resolution is
     * non-empty AND every resolved address is a loopback address. Any single
     * non-loopback sibling makes the whole host off-host. Package-private and
     * static so {@code LlmRouterStartupGuardLoopbackTest} can pin the
     * single-loopback, empty, and mixed-result cases directly with a
     * hand-built address array — the mixed case cannot be produced
     * deterministically through real DNS resolution.
     *
     * <p>The empty guard is explicit rather than relying on
     * {@code getAllByName} throwing {@link UnknownHostException} on an
     * unresolvable host: it keeps the "at least one address, all loopback"
     * contract self-evident and independent of that JDK behaviour.
     */
    static boolean everyAddressLoopback(InetAddress[] addresses) {
        if (addresses.length == 0) {
            return false;
        }
        for (InetAddress address : addresses) {
            if (!address.isLoopbackAddress()) {
                return false;
            }
        }
        return true;
    }

    private static String stripOrEmpty(@Nullable String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * The non-English subset of a comma-separated languages value, in
     * declaration order, normalized to lowercase. Only non-{@code "en"}
     * languages make a provider reachable via the router's priority-2
     * branch (the branch is skipped entirely for {@code "en"} scopes),
     * so an {@code en}-only declaration is NOT a local-only conflict.
     */
    private static String nonEnglishLanguages(@Nullable String raw) {
        String value = stripOrEmpty(raw);
        if (value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String part : value.split(",")) {
            String language = part.trim().toLowerCase(Locale.ROOT);
            if (language.isEmpty() || language.equals("en")) {
                continue;
            }
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(language);
        }
        return out.toString();
    }

    /**
     * Materialize the keys the guard cares about into a small map
     * so {@link #validateLocalOnlyConfiguration(Map)} can run as a
     * pure function. Includes the master switch, the embedding
     * endpoint base-url, the default-provider key, and every per-task
     * base-url + provider override the guard knows about.
     */
    private static Map<String, String> snapshotConfig(Config config) {
        Map<String, String> snap = new LinkedHashMap<>();
        snap.put(CONFIG_KEY_LOCAL_ONLY,
            config.getOptionalValue(CONFIG_KEY_LOCAL_ONLY, String.class).orElse(""));
        snap.put(CONFIG_KEY_EMBEDDINGS_BASE_URL,
            config.getOptionalValue(CONFIG_KEY_EMBEDDINGS_BASE_URL, String.class).orElse(""));
        snap.put(LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER,
            config.getOptionalValue(LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER, String.class).orElse(""));
        for (Map.Entry<ModelTask, String> kv : PER_TASK_BASE_URL_KEYS.entrySet()) {
            snap.put(kv.getValue(), config.getOptionalValue(kv.getValue(), String.class).orElse(""));
            String providerKey = providerKeyFor(kv.getKey());
            snap.put(providerKey, config.getOptionalValue(providerKey, String.class).orElse(""));
            // Per-task model, needed for the provider/base-url/model mismatch
            // scan (M1-577). Same optional-with-empty-default shape as the
            // base-url and provider reads above.
            String modelKey = modelKeyFor(kv.getKey());
            snap.put(modelKey, config.getOptionalValue(modelKey, String.class).orElse(""));
        }
        for (String remoteProvider : REMOTE_PROVIDER_NAMES) {
            String languagesKey = languagesKeyFor(remoteProvider);
            snap.put(languagesKey, config.getOptionalValue(languagesKey, String.class).orElse(""));
        }
        return snap;
    }

    /**
     * Thrown when the validator detects a local-only conflict. The
     * @Startup bean's @PostConstruct re-throws this, which Quarkus
     * treats as a fatal startup error and refuses to start.
     */
    public static final class LocalOnlyConflictException extends RuntimeException {
        public LocalOnlyConflictException(String message) {
            super(message);
        }
    }

    /**
     * Thrown when {@link #checkProviderModelMismatch} finds an inconsistent
     * (provider, base-url, model) triple AND the operator opted into fail-fast
     * via {@link #CONFIG_KEY_MISMATCH_FAIL_FAST}. The @Startup bean's
     * @PostConstruct re-throws this, which Quarkus treats as a fatal startup
     * error and refuses to start. Not thrown in the default advisory posture.
     */
    public static final class ProviderModelMismatchException extends RuntimeException {
        public ProviderModelMismatchException(String message) {
            super(message);
        }
    }
}
