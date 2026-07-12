package app.zcat.infochat.llm.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.ModelTask;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.Config;

import java.util.Locale;
import java.util.Set;

/**
 * DeepSeek chat-completion provider: an {@link OpenAiCompatibleProvider}
 * subclass that speaks the same OpenAI {@code /chat/completions} wire shape
 * against {@code api.deepseek.com} but controls DeepSeek's thinking mode.
 *
 * <h2>Why a subclass</h2>
 * <p>DeepSeek is retiring {@code deepseek-chat} / {@code deepseek-reasoner} on
 * 2026-07-24, folding both into {@code deepseek-v4-flash} — one model with a
 * thinking-mode switch that DEFAULTS THINKING ON. Reasoning tokens share the
 * {@code max_tokens} completion budget, so an undisabled call can truncate its
 * answer (for the security judge a truncated/empty verdict fail-opens). The
 * only confirmed off-switch is the DeepSeek-specific body field
 * {@code "thinking":{"type":"disabled"}} (a boolean {@code thinking:false} is a
 * 400; {@code reasoning_effort} only tunes depth and cannot turn thinking off).
 * That field must NOT be sent to a real OpenAI / Ollama endpoint (unknown field
 * → 400), so the control is scoped to this subclass via the parent's
 * {@link OpenAiCompatibleProvider#customizeRequestBody} seam rather than added
 * to the shared body. (M1-608, D56.)
 *
 * <h2>Per-task reasoning toggle</h2>
 * <p>Optional per-task key {@code infochat.llm.<task>.reasoning-effort}:
 * <ul>
 *   <li>UNSET (the default for every task) or {@code off} → the assembled body
 *       carries {@code "thinking":{"type":"disabled"}} (confirmed non-thinking,
 *       0 reasoning tokens). This preserves the current {@code deepseek-chat}
 *       non-thinking behaviour and token cost after the model-alias sunset, so
 *       no task's effective behaviour changes when this lands.</li>
 *   <li>One of {@code high|low|medium|max|xhigh} → thinking is ENABLED at that
 *       depth via {@code reasoning_effort} (v4-flash defaults thinking on, so
 *       the disable field is omitted and the depth is set). Enabling it for any
 *       task — notably the security judge — is gated on a separate eval AND on
 *       raising that task's {@code max_tokens} so reasoning cannot crowd out the
 *       verdict; no v1 task sets this key.</li>
 * </ul>
 * An unrecognized non-empty value fails the startup config scan naming the
 * property, the same way the parent guards a non-positive {@code max-tokens} —
 * so an operator typo never silently degrades a judge call.
 *
 * <p><b>Scope</b>: {@code @Singleton}, not {@code @ApplicationScoped} like the
 * sibling providers. A normal-scoped bean needs a client proxy, and ArC cannot
 * synthesize the proxy's no-arg constructor for a subclass whose superclass
 * ({@link OpenAiCompatibleProvider}) exposes only arg-taking constructors over
 * final fields. {@code @Singleton} is a pseudo-scope (no proxy), so a stateless
 * provider discovered only through {@code Instance<LlmProvider>} is served
 * correctly without one.
 */
@Singleton
public class DeepSeekProvider extends OpenAiCompatibleProvider {

    /**
     * Stable bean name the router resolves {@code provider=deepseek} routes to,
     * and the identity the startup guard treats as remote
     * ({@code LlmRouterStartupGuard.REMOTE_PROVIDER_NAMES}).
     */
    public static final String PROVIDER_NAME = "deepseek";

    /** Per-task config leaf: {@code infochat.llm.<task>.reasoning-effort}. */
    private static final String REASONING_EFFORT_LEAF = "reasoning-effort";

    /** Explicit OFF value; UNSET also means OFF (the default for every task). */
    private static final String REASONING_OFF = "off";

    /**
     * The depth values DeepSeek's {@code reasoning_effort} accepts. There is no
     * "off" among them — thinking is disabled via the {@code thinking} field,
     * not this key — so an unset/off {@code reasoning-effort} maps to the
     * thinking-disabled body and any of these maps to thinking-on at that depth.
     */
    private static final Set<String> REASONING_DEPTHS =
        Set.of("high", "low", "medium", "max", "xhigh");

    /** Per-task config leaf: {@code infochat.llm.<task>.max-tokens}. */
    private static final String MAX_TOKENS_LEAF = "max-tokens";

    /**
     * The parent's default {@code max-tokens} when the key is unset
     * ({@link OpenAiCompatibleProvider} resolves an absent {@code max-tokens} to
     * this). Duplicated here rather than shared as a parent constant to keep the
     * coupling guard inside the single production file M1-610 modifies; if the
     * parent's default ever changes, this must track it (the reasoning floor
     * below only bites when the effective {@code max-tokens} — including this
     * default — is under-provisioned).
     */
    private static final int PARENT_DEFAULT_MAX_TOKENS = 1024;

    /**
     * Minimum {@code max-tokens} a reasoning-ENABLED task must provision.
     * Reasoning tokens share the completion budget, so an under-sized
     * {@code max-tokens} lets reasoning crowd out the verdict; the M1-610 eval
     * measured the deepest depth ({@code reasoning_effort=max}) consuming up to
     * ~2063 completion tokens on real judge prompts and TRUNCATING a MALWARE
     * verdict at the parent's default 1024 cap (a measured fail-open). The floor
     * is set to ~2x that worst case so a reasoning-on call cannot truncate its
     * verdict. See {@code docs/plan/m1/spikes/M1-610-judge-reasoning.md}. (M1-610.)
     */
    static final int REASONING_MIN_MAX_TOKENS = 4000;

    private final Config config;

    @Inject
    public DeepSeekProvider(Config config) {
        super(config);
        this.config = config;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    /**
     * Validates the base per-task config (via the parent) AND this provider's
     * {@code reasoning-effort} key at the startup scan, so an unrecognized value
     * fails boot naming the property instead of failing the first live call —
     * the same startup-scan posture the parent uses for {@code max-tokens}. When
     * reasoning is ENABLED for the task, also enforces the reasoning/max-tokens
     * coupling so a reasoning-on judge cannot boot with an under-provisioned
     * {@code max-tokens} that would truncate its verdict and fail-open.
     */
    @Override
    public void assertTaskConfigResolvable(ModelTask task) {
        super.assertTaskConfigResolvable(task);
        String depth = resolveReasoningDepth(task);
        if (depth != null) {
            requireMaxTokensAboveReasoningFloor(task, depth);
        }
    }

    /**
     * Code-enforces the reasoning/max-tokens coupling (M1-610). Reasoning tokens
     * share the {@code max_tokens} completion budget, so enabling reasoning on a
     * task whose {@code max-tokens} is below {@link #REASONING_MIN_MAX_TOKENS}
     * lets reasoning crowd out the answer: the reply truncates to empty/partial,
     * and for the security judge that unparseable verdict routes to the Stage 2
     * infra-failure path whose default releases the post as READY — a silent
     * fail-open of the actual security boundary (the M1-608 redteam OUT-OF-MODEL
     * item; {@code docs/spec/security.md} §Failure handling). This turns that
     * process-promise into a boot-time invariant: a reasoning-on task with an
     * unraised {@code max-tokens} fails the startup scan naming the task and BOTH
     * properties, mirroring the parent's non-positive-{@code max-tokens} guard.
     * Reasoning OFF (the default) never reaches this method, so no shipped task
     * is affected.
     */
    private void requireMaxTokensAboveReasoningFloor(ModelTask task, String depth) {
        String maxTokensProperty = task.configPrefix() + MAX_TOKENS_LEAF;
        int maxTokens = config.getOptionalValue(maxTokensProperty, Integer.class)
            .orElse(PARENT_DEFAULT_MAX_TOKENS);
        if (maxTokens < REASONING_MIN_MAX_TOKENS) {
            throw new LlmProvider.TaskConfigUnresolvableException(
                "DeepSeekProvider: reasoning is enabled for " + task + " ("
                    + task.configPrefix() + REASONING_EFFORT_LEAF + "='" + depth + "') but "
                    + maxTokensProperty + "=" + maxTokens + " is below the "
                    + REASONING_MIN_MAX_TOKENS + "-token floor needed for reasoning plus the"
                    + " verdict — reasoning tokens share the completion budget, so raise "
                    + maxTokensProperty + " to at least " + REASONING_MIN_MAX_TOKENS
                    + " (so reasoning cannot truncate the verdict and fail-open), or unset "
                    + task.configPrefix() + REASONING_EFFORT_LEAF + " to keep thinking disabled.");
        }
    }

    /**
     * DeepSeek thinking control (M1-608). OFF (unset/off) disables thinking so
     * v4-flash's thinking-on default cannot spend the {@code max_tokens} budget
     * on reasoning and truncate the answer; a depth enables thinking via
     * {@code reasoning_effort}. The confirmed off-switch is
     * {@code "thinking":{"type":"disabled"}}.
     */
    @Override
    protected void customizeRequestBody(ObjectNode root, ModelTask task) {
        String depth = resolveReasoningDepth(task);
        if (depth == null) {
            root.putObject("thinking").put("type", "disabled");
            return;
        }
        root.put("reasoning_effort", depth);
    }

    /**
     * Resolves the per-task {@code reasoning-effort}: {@code null} for OFF
     * (unset or {@code off} — the default), else the validated depth. An
     * unrecognized non-empty value throws
     * {@link LlmProvider.TaskConfigUnresolvableException} naming the property,
     * mirroring the parent's non-positive-{@code max-tokens} startup guard.
     */
    private @Nullable String resolveReasoningDepth(ModelTask task) {
        String property = task.configPrefix() + REASONING_EFFORT_LEAF;
        String raw = config.getOptionalValue(property, String.class).map(String::trim).orElse("");
        if (raw.isEmpty() || raw.equalsIgnoreCase(REASONING_OFF)) {
            return null;
        }
        String depth = raw.toLowerCase(Locale.ROOT);
        if (!REASONING_DEPTHS.contains(depth)) {
            throw new LlmProvider.TaskConfigUnresolvableException(
                "DeepSeekProvider: " + property + "='" + raw + "' is not a valid reasoning-effort"
                    + " — use one of " + REASONING_DEPTHS + " to enable thinking, or leave it unset"
                    + " (or '" + REASONING_OFF + "') to disable thinking.");
        }
        return depth;
    }
}
