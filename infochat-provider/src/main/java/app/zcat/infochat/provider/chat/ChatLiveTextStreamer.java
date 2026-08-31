package app.zcat.infochat.provider.chat;

import app.zcat.infochat.core.llm.LlmOutputSanitizerCore;
import app.zcat.infochat.messaging.MessagingAdapter;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.AdapterRegistry;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.messaging.StageProgressNotifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The live-text eligibility gate and per-turn reveal factory
 * (messaging.md §Progress notifications "Live-text publisher mode",
 * the M1-846 amendment). Eligibility — every condition must hold:
 * the operator enable key {@code infochat.chat.live-text} is on
 * (default off, a D73-precedent capability gate — off is today's
 * behavior exactly and stays a supported deployment posture); the
 * bound adapter declares {@code supportsLiveText}; the scope is a DM
 * (group fan-out economics, P10); and the reply's generated language
 * is its delivered language — every {@code en} scope, and non-
 * {@code en} scopes resolved to native mode, so no streamed prefix is
 * later discarded by the display-translation leg. The LLM-side
 * streaming capability ({@code LlmRouter.streamingSupportedFor}) is
 * the caller's final conjunct — it owns the router.
 */
@ApplicationScoped
public class ChatLiveTextStreamer {

    @Inject
    StageProgressNotifier progressNotifier;

    @Inject
    AdapterRegistry adapterRegistry;

    @Inject
    InboundContext inboundContext;

    @Inject
    BundleLoader bundleLoader;

    @ConfigProperty(name = "infochat.chat.live-text", defaultValue = "false")
    boolean enabled;

    /**
     * The four transport/mode eligibility conjuncts (see class javadoc).
     * The caller supplies the scope it will reveal into; a null scope
     * (a caller that cannot address the placeholder) is never eligible.
     */
    public boolean eligible(@Nullable ScopeRef scope, String scopeKind,
                            String scopeLanguage, ChatReplyMode replyMode) {
        if (!enabled || scope == null || !"dm".equals(scopeKind)) {
            return false;
        }
        if (replyMode != ChatReplyMode.NATIVE && !"en".equals(scopeLanguage)) {
            return false;
        }
        MessagingAdapter adapter = resolveBoundAdapter();
        return adapter != null && adapter.capabilities().supportsLiveText();
    }

    /** Open the per-turn reveal state machine for an eligible scope. */
    public LiveTextReveal newReveal(ScopeRef scope, String scopeLanguage) {
        return new LiveTextReveal(progressNotifier, bundleLoader, scope, scopeLanguage);
    }

    private @Nullable MessagingAdapter resolveBoundAdapter() {
        String adapterName = inboundContext.adapterName();
        for (MessagingAdapter adapter : adapterRegistry.activatedAdapters()) {
            if (adapter.name().equals(adapterName)) {
                return adapter;
            }
        }
        return null;
    }

    /**
     * One turn's live-text reveal: the streaming SPI's chunk consumer.
     * Security regime per security.md §Streamed surfaces — every
     * transmitted update is the sanitizer's PURE output over the FULL
     * generated prefix (never a delta, so chunk-boundary assembly and
     * pass-ordering coverage hold by construction); the first publish
     * is held back until the trimmed prefix's refusal-marker question
     * is decidable (fail-closed — a refusal-prefixed stream never
     * publishes); tool-protocol openers never transmit (the display
     * truncates at the earliest opener and reverts to the localized
     * GENERATING label, and the next iteration streams from empty).
     * Match evidence from the per-update passes accumulates as
     * per-token maxima for the terminal {@code sanitizeStreamed}
     * row-set — the audit never fires per update.
     */
    public static final class LiveTextReveal {

        private final StageProgressNotifier notifier;
        private final BundleLoader bundleLoader;
        private final ScopeRef scope;
        private final String language;
        private final StringBuilder prefix = new StringBuilder();
        private final Map<String, Integer> transientMatchMaxima = new HashMap<>();
        private boolean refusalPending;
        private boolean refusalDecided;
        private boolean toolCallSeen;
        private boolean labelShown;

        LiveTextReveal(StageProgressNotifier notifier, BundleLoader bundleLoader,
                       ScopeRef scope, String language) {
            this.notifier = notifier;
            this.bundleLoader = bundleLoader;
            this.scope = scope;
            this.language = language;
        }

        /** The streaming SPI's chunk consumer — one model text delta. */
        public void onChunk(String delta) {
            prefix.append(delta);
            String raw = prefix.toString();
            LlmOutputSanitizerCore.FullSanitizeResult pass =
                    LlmOutputSanitizerCore.sanitizeWithMatches(raw);
            mergeMaxima(pass.matches());
            String sanitized = pass.rewritten();
            if (toolCallSeen) {
                return;
            }
            int opener = ChatAgent.earliestToolOpenerIndex(sanitized);
            if (opener >= 0) {
                // Protocol text never transmits: hold this iteration's
                // display at the stage label from the first opener
                // sighting on, including a partial opener assembled at a
                // chunk boundary; the batch finalizer strips at the opener.
                toolCallSeen = true;
                showGeneratingLabel();
                return;
            }
            if (!refusalDecided) {
                String trimmed = sanitized.trim();
                if (trimmed.startsWith(ChatAgent.REFUSAL_MARKER_PREFIX)) {
                    refusalPending = true;
                    return;
                }
                if (trimmed.length() < ChatAgent.REFUSAL_MARKER_PREFIX.length()) {
                    return;
                }
                refusalDecided = true;
            }
            if (refusalPending) {
                return;
            }
            if (sanitized.isBlank()) {
                return;
            }
            // Live text just replaced whatever the placeholder held, so a
            // later tool-call revert must be able to re-show the label.
            labelShown = false;
            notifier.publishLiveText(scope, sanitized);
        }

        /**
         * The tool loop dispatched this iteration's call: drop the
         * iteration's prefix, re-arm the hold-backs, and leave the
         * placeholder on the localized GENERATING label (the G3 rule —
         * the next iteration streams from empty).
         */
        public void onIterationToolCall() {
            prefix.setLength(0);
            toolCallSeen = false;
            refusalPending = false;
            refusalDecided = false;
            showGeneratingLabel();
        }

        /** Per-token transient match maxima for the terminal audit row-set. */
        public Map<String, Integer> transientMatchMaxima() {
            return transientMatchMaxima;
        }

        private void showGeneratingLabel() {
            if (labelShown) {
                return;
            }
            labelShown = true;
            // Forced: the revert must not be coalesced away while live text
            // is displayed (security.md §Streamed surfaces, M1-958).
            notifier.publishStageTextForced(scope,
                    bundleLoader.get(BundleKeys.PROGRESS_GENERATING, language));
        }

        private void mergeMaxima(java.util.List<String> matches) {
            for (Map.Entry<String, Integer> count
                    : LlmOutputSanitizerCore.aggregateMatchCounts(matches).entrySet()) {
                transientMatchMaxima.merge(count.getKey(), count.getValue(), Math::max);
            }
        }
    }
}
