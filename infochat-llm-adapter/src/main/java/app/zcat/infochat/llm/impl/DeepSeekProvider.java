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
     * the same startup-scan posture the parent uses for {@code max-tokens}.
     */
    @Override
    public void assertTaskConfigResolvable(ModelTask task) {
        super.assertTaskConfigResolvable(task);
        resolveReasoningDepth(task);
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
