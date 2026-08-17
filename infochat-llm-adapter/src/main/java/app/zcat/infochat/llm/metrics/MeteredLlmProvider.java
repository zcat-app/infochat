package app.zcat.infochat.llm.metrics;


import app.zcat.infochat.llm.LlmCallContext;
import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmRouter;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CDI decorator wrapping every {@link LlmProvider} bean with the
 * per-call observability surface (M1-321): constructs the per-call
 * {@link LlmCallContext} (derived from the ambient one, fresh trace id
 * when none is bound), binds it around the delegate call so it is
 * observable inside the provider impl, and emits the §5.9 LLM metrics
 * through {@link LlmMetrics}. A decorator keeps metric emission out of
 * each provider impl and applies uniformly to all of them — including
 * test alternatives — with no router or call-site change.
 *
 * <p>Outcome classification here is {@code ok} (delegate returned) vs
 * {@code fail} (delegate threw, exception rethrown unchanged). The
 * {@code model} label reads the operator-configured per-task model id
 * ({@code infochat.llm.<task>.model}) and deliberately NOT the
 * provider-reported {@link LlmResponse#model()} it once carried
 * (M1-673): a Micrometer registry retains one meter per distinct tag
 * value — the tag string included — for the JVM lifetime, so a
 * wire-derived label hands a hostile or compromised endpoint a
 * persistent memory-amplification channel that the providers' bounded
 * body read does not close (that cap bounds only the transient read).
 * Operator config is the cardinality-bounded source. A task with no
 * configured model (the stub-provider test topologies) and a failed
 * call are both labeled {@code unknown}. The trace-id log line carries
 * ids, labels, and durations only — never prompt or response content
 * (log-hygiene rule, docs/spec/security.md).</p>
 *
 * <p>Recorded token <em>values</em> are constrained here for the same
 * reason (M1-677): the reply chooses them too, and both parsers accept
 * any integral value the wire carries. {@link #plausibleUsage} is the
 * one place that constraint sits, because every provider — HTTP,
 * local, or stub — reaches the metric surface through this decorator,
 * so siting it per parser would duplicate it and leave the next
 * provider unguarded. The decorator can bound both directions because
 * it holds both sides of the call: the request's generation cap for
 * the output count, the prompt it was given for the input count.</p>
 */
@Decorator
@Priority(Interceptor.Priority.APPLICATION)
public class MeteredLlmProvider implements LlmProvider {

    private static final Logger LOG = Logger.getLogger(MeteredLlmProvider.class);

    private static final String UNKNOWN_MODEL = "unknown";

    /**
     * The {@code max_tokens} an OpenAI-compatible request carries when
     * the per-task key is unset — {@code OpenAiCompatibleProvider}
     * resolves an absent {@code max-tokens} to this and sends it, so it
     * is a real cap on the reply, not an "unset" sentinel
     * (docs/design/05-llm-and-embeddings.md: "The default is a cap, not
     * absent-means-uncapped"). Duplicated here for the same reason
     * {@code DeepSeekProvider.PARENT_DEFAULT_MAX_TOKENS} duplicates it —
     * a reader outside the provider needs the effective value — and it
     * must track the parent's default if that ever changes.
     */
    private static final long DEFAULT_MAX_TOKENS = 1024;

    /**
     * Worst-case UTF-8 bytes per Java {@code char}, the ceiling used to
     * bound a reported input count against the prompt actually sent. A
     * char encodes to at most 3 UTF-8 bytes (a surrogate pair is 2 chars
     * → 4 bytes, so 3/char still bounds it), and no tokenizer emits more
     * than one token per byte, so 3 × prompt-chars can only overstate
     * the honest count.
     */
    private static final long MAX_UTF8_BYTES_PER_CHAR = 3;

    /**
     * Slack added to the prompt-derived input bound for what the
     * provider appends to the request but the decorator cannot see:
     * chat-template role markers and sentinels — tens of tokens; the
     * tools-bearing leg adds the declarations' own char-derived term.
     * Sized to stay honest in both directions: large enough that no
     * real reply is ever discarded, small enough that the accepted
     * residual is the one the spec describes rather than a blanket
     * allowance an endpoint could hide phantom usage inside.
     */
    private static final long INPUT_OVERHEAD_SLACK_TOKENS = 1024;

    private final LlmProvider delegate;
    private final LlmMetrics metrics;
    private final LlmRouter.ConfigReader config;

    /**
     * Seam constructor: hand-supplied config reader, for plain-JUnit
     * tests (map-backed config, no Quarkus boot) — the same two-ctor
     * shape as {@link LlmRouter} and {@code LlmCircuitBreakerRegistry}.
     */
    public MeteredLlmProvider(LlmProvider delegate, LlmMetrics metrics,
                              LlmRouter.ConfigReader config) {
        this.delegate = delegate;
        this.metrics = metrics;
        this.config = config;
    }

    /**
     * CDI constructor. This is the only {@link Inject} one, so ArC picks
     * it — and the delegate injection point — for the decorator.
     */
    @Inject
    public MeteredLlmProvider(@Delegate @Any LlmProvider delegate, LlmMetrics metrics,
                              Config mpConfig) {
        this(delegate, metrics, key -> mpConfig.getOptionalValue(key, String.class));
    }

    @Override
    public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
        LlmCallContext context = LlmCallContext.currentOrFresh().withTask(task);
        String provider = delegate.providerName();
        AtomicInteger inflight = metrics.llmInflight(task, provider);
        long startNanos = System.nanoTime();
        inflight.incrementAndGet();
        try {
            LlmResponse response =
                LlmCallContext.callWith(context, () -> delegate.generate(task, systemPrompt, userPrompt));
            Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);
            String model = configuredModel(task);
            metrics.recordLlmCall(task, provider, model, LlmMetrics.Outcome.OK, latency,
                plausibleUsage(task, provider, systemPrompt, userPrompt, null, response.usage()));
            LOG.debugf("llm call ok: trace=%s task=%s provider=%s model=%s latencyMs=%d",
                context.traceId(), task.keySegment(), provider, model, latency.toMillis());
            return response;
        } catch (RuntimeException e) {
            Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);
            metrics.recordLlmCall(task, provider, UNKNOWN_MODEL, LlmMetrics.Outcome.FAIL, latency, null);
            LOG.debugf("llm call fail: trace=%s task=%s provider=%s latencyMs=%d",
                context.traceId(), task.keySegment(), provider, latency.toMillis());
            throw e;
        } finally {
            inflight.decrementAndGet();
        }
    }

    @Override
    public boolean supportsStreaming(ModelTask task) {
        return delegate.supportsStreaming(task);
    }

    @Override
    public boolean supportsToolCalls(ModelTask task) {
        return delegate.supportsToolCalls(task);
    }

    /**
     * The tools-bearing mirror of {@link #generate}: same per-call
     * metrics, same operator-configured model label; the input bound
     * gains the rendered declarations' own char-derived term.
     */
    @Override
    public LlmResponse generateWithTools(ModelTask task, String systemPrompt, String userPrompt,
                                         List<LlmProvider.ToolDeclaration> tools) {
        LlmCallContext context = LlmCallContext.currentOrFresh().withTask(task);
        String provider = delegate.providerName();
        AtomicInteger inflight = metrics.llmInflight(task, provider);
        long startNanos = System.nanoTime();
        inflight.incrementAndGet();
        try {
            LlmResponse response = LlmCallContext.callWith(context, () ->
                delegate.generateWithTools(task, systemPrompt, userPrompt, tools));
            Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);
            String model = configuredModel(task);
            metrics.recordLlmCall(task, provider, model, LlmMetrics.Outcome.OK, latency,
                plausibleUsage(task, provider, systemPrompt, userPrompt, tools, response.usage()));
            LOG.debugf("llm tools call ok: trace=%s task=%s provider=%s model=%s latencyMs=%d",
                context.traceId(), task.keySegment(), provider, model, latency.toMillis());
            return response;
        } catch (RuntimeException e) {
            Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);
            metrics.recordLlmCall(task, provider, UNKNOWN_MODEL, LlmMetrics.Outcome.FAIL, latency, null);
            LOG.debugf("llm tools call fail: trace=%s task=%s provider=%s latencyMs=%d",
                context.traceId(), task.keySegment(), provider, latency.toMillis());
            throw e;
        } finally {
            inflight.decrementAndGet();
        }
    }

    /**
     * The streaming mirror of {@link #generate}: one call is ONE
     * metric event — latency, outcome, and the terminal usage frame
     * record once when the whole stream completes, never per chunk,
     * with the model label from operator config exactly as on the
     * single-string path.
     */
    @Override
    public LlmResponse generateStreaming(ModelTask task, String systemPrompt, String userPrompt,
                                         java.util.function.Consumer<String> chunkConsumer) {
        LlmCallContext context = LlmCallContext.currentOrFresh().withTask(task);
        String provider = delegate.providerName();
        AtomicInteger inflight = metrics.llmInflight(task, provider);
        long startNanos = System.nanoTime();
        inflight.incrementAndGet();
        try {
            LlmResponse response = LlmCallContext.callWith(context, () ->
                delegate.generateStreaming(task, systemPrompt, userPrompt, chunkConsumer));
            Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);
            String model = configuredModel(task);
            metrics.recordLlmCall(task, provider, model, LlmMetrics.Outcome.OK, latency,
                plausibleUsage(task, provider, systemPrompt, userPrompt, null, response.usage()));
            LOG.debugf("llm stream ok: trace=%s task=%s provider=%s model=%s latencyMs=%d",
                context.traceId(), task.keySegment(), provider, model, latency.toMillis());
            return response;
        } catch (RuntimeException e) {
            Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);
            metrics.recordLlmCall(task, provider, UNKNOWN_MODEL, LlmMetrics.Outcome.FAIL, latency, null);
            LOG.debugf("llm stream fail: trace=%s task=%s provider=%s latencyMs=%d",
                context.traceId(), task.keySegment(), provider, latency.toMillis());
            throw e;
        } finally {
            inflight.decrementAndGet();
        }
    }

    /**
     * The operator-configured model id for {@code task}, read per call
     * from {@code infochat.llm.<task>.model} — the same key, spelled the
     * same way, the concrete providers' {@code configFor} reads. Per-call
     * rather than cached for the reason those readers state: a map lookup
     * in microseconds against an LLM call in seconds, and caching would
     * freeze a value the rest of the config surface treats as
     * runtime-resolvable. Absent or empty (the stub-provider test
     * topologies configure no model) degrades to {@code unknown} so the
     * decorator never fails a call the undecorated bean would survive.
     */
    private String configuredModel(ModelTask task) {
        return config.get(task.configPrefix() + "model")
            .filter(model -> !model.isEmpty())
            .orElse(UNKNOWN_MODEL);
    }

    /**
     * The reported token counts when this call could actually have
     * produced them, else {@code null} — which {@link LlmMetrics} and
     * {@link LlmResponse}'s {@code usage} contract already read as "the
     * provider reported no usage", the state the fail path and every
     * non-reporting provider produce.
     *
     * <p>The counts are endpoint-chosen input (docs/spec/security.md
     * §Trust boundaries entry 9): both HTTP parsers admit any integral
     * value the reply carries. A negative one would decrement a
     * Micrometer counter, and a counter that moves backwards reads
     * downstream as a process restart — every {@code rate()} over the
     * series silently mis-reports rather than showing an obvious spike.
     * An oversized one is worse in a quieter way: counters are
     * monotonic, so a single count near {@link Long#MAX_VALUE} makes
     * every later honest increment invisible for the JVM lifetime.
     * Both directions are bounded here — output against the cap the
     * request carried, input against the prompt that was sent.</p>
     *
     * <p>Discarding the record rather than clamping it into range is the
     * deliberate half of the rule: a clamped figure is indistinguishable
     * from an honestly-reported one, so it buries the tampering under a
     * plausible number, whereas a discarded one surfaces as
     * {@code llm.calls.total} outrunning the token counters. That gap is
     * also a shape consumers must already handle — providers reporting
     * no usage at all produce it — so it adds no third state to reason
     * about, and it invents no figure that a future cost-weighted rate
     * cap (future-features §E7) could charge a sender for.</p>
     *
     * <p>Both bounds are one-sided by design. They reject the impossible,
     * not the merely wrong: a reply understating its usage, or overstating
     * it within the bounds, is indistinguishable from an honest one and
     * stays a documented residual.</p>
     */
    private LlmResponse.@Nullable TokenUsage plausibleUsage(
            ModelTask task, String provider, String systemPrompt, String userPrompt,
            @Nullable List<LlmProvider.ToolDeclaration> tools,
            LlmResponse.@Nullable TokenUsage usage) {
        if (usage == null) {
            return null;
        }
        long outputBound = effectiveMaxTokens(task);
        long inputBound = tools == null
            ? promptDerivedInputBound(systemPrompt, userPrompt)
            : promptDerivedInputBound(systemPrompt, userPrompt, tools);
        if (usage.inputTokens() >= 0 && usage.outputTokens() >= 0
                && usage.outputTokens() <= outputBound
                && usage.inputTokens() <= inputBound) {
            return usage;
        }
        LOG.debugf("llm usage discarded as impossible: task=%s provider=%s in=%d out=%d "
                + "inBound=%d outBound=%d",
            task.keySegment(), provider, usage.inputTokens(), usage.outputTokens(),
            inputBound, outputBound);
        return null;
    }

    /**
     * The largest input count the prompt actually sent could have
     * tokenized to. Derived from the prompt strings this decorator
     * already holds rather than from config: no per-task key states a
     * prompt-size ceiling, and {@code infochat.context-window} is a
     * Provider-module property that resolves to absent inside the
     * Collector — where the tasks most exposed to this run.
     *
     * <p>Loose by construction (see {@link #MAX_UTF8_BYTES_PER_CHAR} and
     * {@link #INPUT_OVERHEAD_SLACK_TOKENS}): real tokenizers emit
     * roughly a quarter of this, so the bound rejects only counts that
     * no tokenization of this prompt could produce.</p>
     */
    private long promptDerivedInputBound(String systemPrompt, String userPrompt) {
        long promptChars = (long) systemPrompt.length() + userPrompt.length();
        return promptChars * MAX_UTF8_BYTES_PER_CHAR + INPUT_OVERHEAD_SLACK_TOKENS;
    }

    /**
     * The tools-bearing bound: the declarations ride the same request,
     * so their chars bound their tokens exactly as the prompt's do.
     */
    private long promptDerivedInputBound(String systemPrompt, String userPrompt,
                                         List<LlmProvider.ToolDeclaration> tools) {
        long declarationChars = 0;
        for (LlmProvider.ToolDeclaration tool : tools) {
            declarationChars += tool.name().length() + tool.description().length()
                + tool.parametersJson().length();
        }
        return promptDerivedInputBound(systemPrompt, userPrompt)
            + declarationChars * MAX_UTF8_BYTES_PER_CHAR;
    }

    /**
     * The generation cap the request carried, and so the largest output
     * count a server obeying it can report: the per-task
     * {@code infochat.llm.<task>.max-tokens} when set, else
     * {@link #DEFAULT_MAX_TOKENS}.
     *
     * <p>Falling back to the default rather than to "no bound" is the
     * whole point of the check. No properties file in the repo sets the
     * key and the setup wizard writes it for chat and summarizer only,
     * so an absent-means-unbounded reading would leave the security,
     * tagger, entity, classifier and translator routes unchecked in
     * every shipped deployment while their requests still carried a
     * 1024 cap. An absent key cannot mean "uncapped" for the other
     * provider either: {@code AnthropicProvider} reads it with
     * {@code config.getValue} and no default, so a missing key fails its
     * startup scan long before a call reaches here.</p>
     *
     * <p>An unparseable or non-positive value falls back the same way.
     * Those are rejected by the providers' own startup scan
     * ({@code LlmHttpSupport.requirePositiveMaxTokens}), so they cannot
     * reach a booted deployment; resolving them here rather than
     * throwing keeps the posture {@link #configuredModel} states — the
     * decorator never fails a call the undecorated bean would have
     * survived.</p>
     */
    private long effectiveMaxTokens(ModelTask task) {
        Optional<String> configured = config.get(task.configPrefix() + "max-tokens");
        if (configured.isEmpty()) {
            return DEFAULT_MAX_TOKENS;
        }
        try {
            long configuredCap = Long.parseLong(configured.get().trim());
            return configuredCap > 0 ? configuredCap : DEFAULT_MAX_TOKENS;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_TOKENS;
        }
    }

    @Override
    public void assertTaskConfigResolvable(ModelTask task) {
        delegate.assertTaskConfigResolvable(task);
    }

    /**
     * Must forward: the interface default walks {@code getClass()},
     * which on the decorator would yield the decorator's own name and
     * break the router's name-based provider resolution.
     */
    @Override
    public String providerName() {
        return delegate.providerName();
    }
}
