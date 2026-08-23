package app.zcat.infochat.provider.chat;

import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.llm.LlmOutputSanitizerCore;
import app.zcat.infochat.core.util.JsonEscaper;
import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.llm.EmbeddingResult;
import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.impl.LlmCallFailedException;
import app.zcat.infochat.llm.routing.LlmCircuitBreakerRegistry;
import app.zcat.infochat.llm.routing.LlmRouter;
import app.zcat.infochat.messaging.ScopeRef;
import app.zcat.infochat.provider.bundle.BundleKeys;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.help.CommandIntentIndex;
import app.zcat.infochat.provider.help.HelpTopicCorpus;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.messaging.HelpCommandHandler;
import app.zcat.infochat.provider.messaging.HelpCommandHandler.CallerTier;
import app.zcat.infochat.provider.messaging.HelpCommandHandler.HelpTier;
import app.zcat.infochat.provider.messaging.InboundContext;
import app.zcat.infochat.provider.translation.TranslationPipeline;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Orchestrates the chat-mode dispatch loop: in-flight gate, prompt build,
// LLM multi-turn tool loop, session persistence, output sanitize, translate.
@ApplicationScoped
public class ChatAgent {

    private static final Logger log = LoggerFactory.getLogger(ChatAgent.class);

    // Text-based tool call protocol for v1's single-string LLM SPI.
    // The system prompt instructs the LLM to emit exactly this format;
    // group(1) is the tool name and group(2) is the opening brace of the
    // JSON args. The args body is delimited by scanning for the brace's
    // balanced match (matchBrace) rather than a reluctant `\{.*?\}`, which
    // would truncate nested objects at the first inner '}'.
    static final Pattern TOOL_CALL_PATTERN = Pattern.compile(
            "TOOL_CALL:\\s*(\\w+)\\s*(\\{)");

    // Second accepted dialect: the model-native emission shape whose closer
    // is unreliable in observed data — anchor on the opener plus a balanced
    // brace scan, require no closer. Same group layout as TOOL_CALL_PATTERN.
    static final String NATIVE_TOOL_CALL_OPENER = "<|tool_call>";
    static final Pattern NATIVE_TOOL_CALL_PATTERN = Pattern.compile(
            "<\\|tool_call>\\s*call:(\\w+)\\s*(\\{)");
    // Accepted grammar minus the brace: a call attempt with no argument
    // brace never dispatches, so the strip recognizes and removes it exactly.
    static final Pattern BRACELESS_NATIVE_TOOL_CALL_PATTERN = Pattern.compile(
            "<\\|tool_call>\\s*call:\\w*");
    // Elision separator for every removed span: a deletion that joins its
    // neighbors could assemble a privileged token the redaction never saw.
    // U+2026 is neither whitespace nor a letter/digit/hyphen (M1-879).
    static final String ELISION_SEPARATOR = "\u2026";

    private static final ObjectMapper TOOL_ARGS_MAPPER = new ObjectMapper();

    static final int MAX_TOOL_ITERATIONS = 10;

    // Fixpoint loop bound: passes only delete, so a pass that removes
    // nothing is done; the cap backstops adversarial assembly chains.
    static final int MAX_STRIP_PASSES = 64;

    // Shared D21 refusal marker for final interception and live hold-back.
    static final String REFUSAL_MARKER_PREFIX = "[REFUSAL:";

    /** Finds the earliest raw tool opener for fail-closed live display. */
    static int earliestToolOpenerIndex(String text) {
        int shipped = text.indexOf("TOOL_CALL:");
        int nativeOpener = text.indexOf(NATIVE_TOOL_CALL_OPENER);
        if (shipped < 0) {
            return nativeOpener;
        }
        return nativeOpener < 0 ? shipped : Math.min(shipped, nativeOpener);
    }

    static final String TOOL_INSTRUCTIONS =
            "\n\nYou have the following tools. To call a tool, output EXACTLY "
          + "one line per call in this format (no surrounding prose on the same line):\n"
          + "TOOL_CALL: toolName {\"param\": \"value\"}\n\n"
          + "Example:\n"
          + "TOOL_CALL: searchPosts {\"tags\": [\"zcash\"], \"window\": \"P7D\"}\n\n"
          + "Tool arguments (queries, tags) are always written in English, and "
          + "tool results come back in English, whatever language the "
          + "conversation is in.\n\n"
          + "Available tools:\n"
          + ChatToolCatalog.renderInstructionTable()
          + "After receiving a tool result, you may call another tool or provide "
          + "your final answer as plain text. Do NOT call a tool and provide a "
          + "final answer in the same response.";

    // Last trusted instruction before the answer on every model-initiated
    // tool turn: the citation demand binds cited URLs verbatim to the
    // tool-returned set and forbids invention or modification.
    static final String POST_TOOL_RESULT_INSTRUCTION =
            "Please provide your response based on the tool result above. "
          + "Cite each post you rely on by its bare source URL exactly as "
          + "the tool result provided it, and never invent or modify a URL.";

    // M1-618 conversational-refinement: the similarity (1 - cosine distance)
    // a retrieved post's strongest match must reach for the grounding to
    // count as CONFIDENT. Below it the best match is only marginally related,
    // so a general assistant should ask a narrowing question rather than
    // ground a weak guess. A fixed code constant (not config) because it
    // changes reply PROSE only — never the retrieved set (D19) — so it needs
    // no per-deployment tuning knob, and a stable constant keeps the D19
    // reproducibility story simplest. It sits deliberately ABOVE the default
    // grounding floor (1 - 0.40 = 0.60, the M1-616-calibrated threshold), so
    // retrieved semantic posts span (0.60, 1.0] and the marginal band is
    // (0.60, 0.65). A much-tighter threshold override (floor above 0.65)
    // would admit only posts already past the cutoff, so the clarify path
    // simply never fires — a benign no-op, since grounding is then genuinely
    // confident. Calibrated to 0.65 by M1-619 (whole-corpus nomic-embed-text
    // sweep: on-domain groundings cluster at similarity 0.62-0.73, so the
    // original 0.75 first cut downgraded ~64% of genuine groundings to a
    // needless clarify; 0.65 keeps the lone spurious near-match out of the
    // confident band while restoring the affordance path to ~82% recall). The
    // clarifying question is non-blocking (see CLARIFY_DIRECTIVE), so a
    // false-positive still costs the user one extra line, not a wrong answer.
    static final double CONFIDENT_SIMILARITY_CUTOFF = 0.65;

    /**
     * Similarity cutoff a command-intent document must clear for the
     * deterministic delivery trigger (M1-665) to fire. Conservative
     * relative to {@code HelpLookupTool}'s 0.52: the tool answers the
     * model's question "which command matches this phrase"; the trigger
     * answers "did the CALLER ask how to do something" — a different
     * question with a higher false-positive cost (an unsolicited usage
     * block on every turn that merely mentions a topic). 0.62 keeps
     * the deliberate +0.10 offset above the tool's admit-band on the
     * scale genuine phrasings actually occupy: M1-748's
     * production-space measurement
     * (docs/measurement/retrieval-separability.md §5.4) found
     * expected-doc similarities span 0.40–0.71 (median 0.63), so the
     * inspection-calibrated 0.70 cleared only the two strongest of
     * fourteen phrasings — an effectively dead trigger. Pinned as a
     * code constant so a deployment change requires a spec amendment,
     * not a silent config tweak (D19 determinism posture; security.md
     * §LLM output sanitizer amendment M1-663 governing this delivery
     * path).
     */
    static final double INTENT_DELIVERY_SIMILARITY_THRESHOLD = 0.62;

    // M1-618 conversational-refinement directives, appended per-turn AFTER
    // the untrusted retrieval block (a trusted region — the model's own
    // instruction surface). They shape the reply; they never quote or list
    // retrieved post content, so no untrusted text escapes the
    // UNTRUSTED_CONTENT wrapper (security.md §Prompt-injection defenses,
    // acceptance item 3). The clarifying question / affordance prose the
    // model then writes is user-facing chat output and routes through the
    // normal sanitize + per-scope translate path like any other reply, so it
    // is translation-safe without a fixed bundle string.
    static final String CLARIFY_DIRECTIVE =
            "\n\nThe posts retrieved above are only weakly related to the user's "
          + "message, so you cannot confidently ground an answer in them. Do NOT "
          + "answer the question yet. Instead, ask ONE short clarifying question "
          + "that narrows what the user is looking for. Ask about their intent "
          + "only; do not quote or list the retrieved posts. If the conversation "
          + "history shows the user already answered a clarifying question, or "
          + "the user asks you to just proceed, answer with the best available "
          + "grounding instead of asking again.";

    static final String AFFORDANCE_DIRECTIVE =
            "\n\nYou can ground your answer in the posts retrieved above. After "
          + "answering, add one short line letting the user know they can see "
          + "more posts related to any one you mention by asking about it (for "
          + "example: tell me more about that first article). Keep it to a brief "
          + "offer and do not fetch anything unless the user actually asks.";

    // M1-685: when a deterministic topic/command match fires (doHandle
    // step 3c), the appended authoritative block IS the answer to the
    // user's matched question. This directive steers the model to defer
    // to it — a brief lead-in, NOT a substitute/competing answer — so the
    // user never sees two conflicting answers in one reply (the
    // live-observed "probation" defect: the model stated a false
    // substitute immediately before the correct curated answer). It takes
    // precedence over the semantic-grounding refinement directives above:
    // a clarifying question or a more-posts affordance alongside a
    // delivered authoritative answer would itself be the competing text
    // this removes. Appended to the user prompt (a trusted region, like
    // CLARIFY/AFFORDANCE_DIRECTIVE); the model's resulting lead-in is
    // user-facing chat output and routes through the normal sanitize +
    // translate path. No-match turns never see it.
    static final String DETERMINISTIC_DELIVERY_DIRECTIVE =
            "\n\nThe system appends an authoritative answer to the user's question "
          + "after your reply. Do NOT provide your own substitute or competing "
          + "answer to that question — let the appended block carry it. Keep your "
          + "text to a brief, friendly lead-in, or address only other aspects of "
          + "the user's message if any. Never contradict the answer that follows.";

    // M1-778: the reply's language is a CONTRACT, not the model's choice.
    // Two things downstream depend on this prose being English and neither
    // can verify it: doHandle step 9 hands it to the two-argument
    // TranslationPipeline.run, which DECLARES its input English on the
    // caller's behalf, and step 10 persists it as chat memory, which is
    // English-canonical. Unpinned, the model mirrors the user's language —
    // so a Czech question under /lang cs produced Czech prose declared as
    // English, and the pipeline stapled "(translation unavailable — showing
    // English)" onto it. D29 forbids inferring a language from text, so a
    // contracted channel is the only thing that can make the declaration
    // truthful. Carried on the SYSTEM prompt rather than the user prompt
    // (unlike the per-turn directives above) because it holds for every
    // turn, including the iteration-cap final call, which sees the base
    // system prompt alone.
    static final String REPLY_LANGUAGE_DIRECTIVE =
            "\n\nAlways write your reply in English, whatever language the user "
          + "writes in and whatever language the retrieved posts are in. The "
          + "user's own language is applied after you, by the translation "
          + "pipeline; never switch language to match the user.";

    // Native mode (D79): the scope's declared language is the contract and
    // no display leg follows; the retrieved context stays English — the
    // M1-844 EN-context arm — only the reply is written in the scope language.
    static String nativeReplyDirective(String scopeLanguage) {
        String name = LANGUAGE_NAMES.getOrDefault(scopeLanguage, scopeLanguage);
        return "\n\nAlways write your reply in " + name + ", whatever language the "
             + "user writes in. The retrieved posts stay in English — read them as "
             + "they are and never rewrite or translate them; only your own reply "
             + "is written in " + name + ". Never switch language to match the user.";
    }

    private static final Map<String, String> LANGUAGE_NAMES = Map.of(
            "en", "English",
            "cs", "Czech",
            "es", "Spanish",
            "ru", "Russian",
            "tr", "Turkish");

    private final InFlightTracker inFlightTracker;
    private final ChatPromptBuilder promptBuilder;
    private final ChatToolDispatcher toolDispatcher;
    private final ChatSessionRepository sessionRepository;
    private final LlmRouter llmRouter;
    private final LlmOutputSanitizer outputSanitizer;
    private final TranslationPipeline translationPipeline;
    private final BundleLoader bundleLoader;
    private final AutoCompressTrigger autoCompressTrigger;
    private final AuditLogWriter auditLogWriter;
    private final DataSource dataSource;
    private final InboundContext inboundContext;
    private final LlmCircuitBreakerRegistry breakerRegistry;
    private final EmbeddingProvider embeddingProvider;
    private final HelpCommandHandler helpHandler;
    private final CancellationService cancellationService;
    private final ChatLiveTextStreamer liveTextStreamer;

    @Inject
    public ChatAgent(InFlightTracker inFlightTracker,
                     ChatPromptBuilder promptBuilder,
                     ChatToolDispatcher toolDispatcher,
                     ChatSessionRepository sessionRepository,
                     LlmRouter llmRouter,
                     LlmOutputSanitizer outputSanitizer,
                     TranslationPipeline translationPipeline,
                     BundleLoader bundleLoader,
                     AutoCompressTrigger autoCompressTrigger,
                     AuditLogWriter auditLogWriter,
                     DataSource dataSource,
                     InboundContext inboundContext,
                     LlmCircuitBreakerRegistry breakerRegistry,
                     EmbeddingProvider embeddingProvider,
                     HelpCommandHandler helpHandler,
                     CancellationService cancellationService,
                     ChatLiveTextStreamer liveTextStreamer) {
        this.inFlightTracker = inFlightTracker;
        this.promptBuilder = promptBuilder;
        this.toolDispatcher = toolDispatcher;
        this.sessionRepository = sessionRepository;
        this.llmRouter = llmRouter;
        this.outputSanitizer = outputSanitizer;
        this.translationPipeline = translationPipeline;
        this.bundleLoader = bundleLoader;
        this.autoCompressTrigger = autoCompressTrigger;
        this.auditLogWriter = auditLogWriter;
        this.dataSource = dataSource;
        this.inboundContext = inboundContext;
        this.breakerRegistry = breakerRegistry;
        this.embeddingProvider = embeddingProvider;
        this.helpHandler = helpHandler;
        this.cancellationService = cancellationService;
        this.liveTextStreamer = liveTextStreamer;
    }

    /**
     * The deferred persistence + auto-compress step for a successfully
     * computed chat turn. Built by {@link #handleTurn} and run by the
     * router ONLY after the reply has been delivered, so a permanent
     * delivery failure leaves the context window "as if the message was
     * never generated" (spec {@code messaging.md} §Failure handling).
     * Returns the auto-compress notice to send as a follow-up message,
     * or empty when no compression fired.
     */
    @FunctionalInterface
    public interface PendingCommit {
        Optional<String> commit();
    }

    /**
     * The outcome of computing a chat turn: the reply to deliver (or
     * {@code null} when {@code /stop} cancelled the request), the
     * deferred {@link PendingCommit} to run after delivery succeeds, and
     * the retrieval-provenance notice the router appends after the reply
     * (M1-617, D58). {@code pendingCommit} is {@code null} for every
     * non-persisting outcome — a {@code /stop} cancellation, an in-flight
     * rejection, a ceiling-gated rejection, and an LLM failure all carry
     * no turn to commit. {@code provenanceNotice} is non-null for a
     * successfully computed ANSWER (grounded or general-knowledge); it is
     * {@code null} for every degrade/rejection path AND for a low-confidence
     * CLARIFY turn (M1-618) — those replies are notices or narrowing
     * questions, not answers grounded in specific posts, so a provenance
     * claim on them would misrepresent them.
     */
    public record ChatTurnResult(@Nullable String reply, @Nullable PendingCommit pendingCommit,
                                 @Nullable String provenanceNotice) {}

    /**
     * Handle a chat-mode message, computing the reply WITHOUT persisting.
     * Returns the reply text to send back to the user plus a deferred
     * {@link PendingCommit} the caller runs after delivery succeeds; on
     * permanent delivery failure the caller drops the commit so neither
     * turn is persisted and auto-compress does not run. Returns a
     * {@code null} reply (and {@code null} commit) when {@code /stop}
     * cancelled the request — the {@code /stop} handler already replied,
     * so the router must send nothing further. The in-flight slot is
     * acquired and released within this method; callers need not manage it.
     */
    public ChatTurnResult handleTurn(UUID userId, String scopeKind,
                                     UUID scopeId, String userMessage) {
        return handleTurn(userId, scopeKind, scopeId, userMessage, null);
    }

    /** Full shape carrying the router-owned address for live-text delivery. */
    public ChatTurnResult handleTurn(UUID userId, String scopeKind,
                                     UUID scopeId, String userMessage,
                                     @Nullable ScopeRef scope) {
        // Read the cached value the router resolved once at intake
        // (InboundRouter.onMessage, D43) so the contention notice and the
        // catch-all unavailable reply localize too — no second
        // scope_preferences SELECT (and no extra pool connection) per turn.
        String scopeLanguage = inboundContext.effectiveLanguage();
        InFlightTracker.CancellationHandle slot =
                inFlightTracker.tryAcquire(userId, scopeKind, scopeId);
        if (slot == null) {
            return new ChatTurnResult(
                    bundleLoader.get(BundleKeys.ERROR_CHAT_IN_FLIGHT, scopeLanguage), null, null);
        }
        try {
            // Adopted a turn /stop had already cancelled — marked while
            // QUEUED, between the stage preamble and this acquire (M1-638):
            // skip the compute entirely, before any LLM work. The router's
            // null-reply arm renders the D35 stopped terminal, the same
            // single-publisher path as a cancelled-while-running turn.
            if (slot.isCancelled()) {
                return new ChatTurnResult(null, null, null);
            }
            ChatTurnResult result = doHandle(userId, scopeKind, scopeId, userMessage,
                    scopeLanguage, scope);
            // Delivery boundary: /stop may have marked this request cancelled
            // even though the work completed — the interrupt landed after the
            // last interruptible point, or it never landed at all (a "missed
            // interrupt"). Discard the result (reply AND pending commit) so it
            // is not delivered or persisted as a second, stale turn alongside
            // the /stop acknowledgement.
            if (slot.isCancelled()) {
                return new ChatTurnResult(null, null, null);
            }
            return result;
        } catch (LlmCallFailedException e) {
            // Narrowed LLM-failure arm (M1-606): the chat/translator LLM
            // call itself failed — transport-unreachable (the typed
            // subtype, thrown classified or breaker-short-circuited) or an
            // application error. Classified here so the log attributes the
            // failure to the LLM transport, never conflating it with a
            // downstream non-LLM failure (DB error in a tool), which takes
            // the arm below. Same /stop guard and same degrade as that
            // arm: friendly error, null commit — the turn is discarded
            // with no session advance, no memory write, and no
            // model-initiated tool call. The deterministic semantic
            // pre-fetch (doHandle step 3) may have run once before the
            // failure — bounded, read-only, and SKIPPED entirely once the
            // breaker is OPEN, so an outage window pays it at most once
            // (security.md §Failure handling, "Provider-side (user-facing)
            // LLM failures").
            if (slot.isCancelled()) {
                return new ChatTurnResult(null, null, null);
            }
            boolean unreachable = e instanceof LlmCallFailedException.ProviderUnreachableException;
            SafeLog.error(log, "ChatAgent.handle: LLM call failed for userId=" + userId
                    + " (provider-unreachable=" + unreachable + ")", e);
            return new ChatTurnResult(
                    bundleLoader.get(BundleKeys.ERROR_CHAT_UNAVAILABLE, scopeLanguage), null, null);
        } catch (Exception e) {
            // A landed cancellation interrupt surfaces here as an exception.
            // When /stop marked this request the /stop handler already
            // replied — return null (no content) rather than double-replying
            // with the unavailable notice.
            if (slot.isCancelled()) {
                return new ChatTurnResult(null, null, null);
            }
            // Streamed audit failure must reach the router's failure terminal,
            // rather than becoming the ordinary unavailable response.
            if (e instanceof LlmOutputSanitizer.StreamedAuditWriteFailedException) {
                throw (LlmOutputSanitizer.StreamedAuditWriteFailedException) e;
            }
            // Any non-LLM failure → same friendly error, null commit: the
            // turn is discarded with no session advance, no memory write,
            // and no model-initiated tool call (security.md §Failure
            // handling, "Provider-side (user-facing) LLM failures").
            SafeLog.error(log, "ChatAgent.handle failed for userId=" + userId, e);
            return new ChatTurnResult(
                    bundleLoader.get(BundleKeys.ERROR_CHAT_UNAVAILABLE, scopeLanguage), null, null);
        } finally {
            inFlightTracker.release(userId, scopeKind, scopeId, slot);
            // Gate close + interrupt-status clear, LAST in the section —
            // see CancellationHandle.releaseWorker (M1-634).
            slot.releaseWorker();
        }
    }

    /**
     * Compute the reply for a chat-mode message and discard the deferred
     * commit — only the compute behaviour (prompt build, tool loop,
     * sanitize, translate) is exercised, never the turn persistence.
     * Package-private: this is a test-visibility helper for assertions on
     * the reply alone. The production router uses {@link #handleTurn}
     * instead, persisting only after a successful send.
     */
    @Nullable String handle(UUID userId, String scopeKind,
                            UUID scopeId, String userMessage) {
        return handleTurn(userId, scopeKind, scopeId, userMessage).reply();
    }

    private ChatTurnResult doHandle(UUID userId, String scopeKind, UUID scopeId,
                                    String userMessage, String scopeLanguage,
                                    @Nullable ScopeRef scope) {
        // Ceiling gate: a failed auto-compress left this session at its
        // token ceiling — reject the turn outright (no LLM call, no
        // persist) instead of silently growing past the ceiling. Clears
        // when a compress succeeds or /clear empties the session. A
        // rejected turn carries no commit (null) — nothing is persisted.
        if (autoCompressTrigger.isCeilingGated(userId, scopeKind, scopeId)) {
            return new ChatTurnResult(
                    bundleLoader.get(BundleKeys.ERROR_COMPRESS_FAILED, scopeLanguage), null, null);
        }

        // 1. Audit the chat-mode intent before any LLM-backed work. No
        // user-authored prose in the audit row — only actor + scope.
        writeAuditRow(userId, scopeKind, scopeId);

        // 3. Digest-first semantic retrieval (M1-589): dispatched
        // deterministically on EVERY turn — the D28 pre-fetch pattern — so
        // grounding never depends on the model choosing to call the tool.
        // Which posts come back, and in what order, is decided by SQL
        // inside the tool (D19); its distance threshold gates
        // grounding-vs-general-knowledge — an empty result folds nothing
        // in and the model answers from general knowledge.
        // ONE TurnContext spans the whole turn: the pre-fetch and the
        // model-initiated calls in runToolLoop share its cache and call
        // budget, so an identical semanticSearch call is served from the
        // cache instead of re-executing (no duplicate embed + probe) and
        // the per-turn execution bound stays the single fixed cap the
        // spec promises (redteam M1-589 2026-07-11, low DOS finding).
        ChatToolDispatcher.TurnContext turnContext = new ChatToolDispatcher.TurnContext();
        // Turn-wide DISTINCT union of the post UIDs the turn actually
        // retrieved — the pre-fetch below plus every model-initiated
        // post-corpus tool call in runToolLoop — feeding the provenance
        // notice (M1-617, D58). Memory/saves tools are excluded: recalling
        // a conversation memory is not feed grounding.
        Set<String> retrievedPostUids = new LinkedHashSet<>();
        // Breaker gate (M1-606), checked AFTER the step-2 audit (the
        // chat-mode intent occurred either way) and BEFORE the pre-fetch:
        // when the chat endpoint's breaker is OPEN the turn is doomed —
        // generate() below will short-circuit — so skip the pre-fetch's
        // embed round-trip and pgvector probe outright. The turn still
        // flows to the LLM call, whose typed short-circuit takes the
        // normal unavailable degrade; once the cooldown admits a probe
        // this returns false and the probe turn gets its grounding.
        SemanticPreFetch preFetch;
        if (breakerRegistry.wouldShortCircuit(ModelTask.CHAT_AGENT)) {
            preFetch = SemanticPreFetch.EMPTY;
        } else {
            preFetch = buildSemanticRetrievalBlock(
                    userId, scopeKind, scopeId, userMessage, turnContext, retrievedPostUids);
        }
        String semanticBlock = preFetch.promptBlock();

        // 3b. Conversational-refinement directive (M1-618), derived from the
        // deterministic pre-fetch signal and appended AFTER the untrusted
        // retrieval block (a trusted region). MARGINAL grounding -> instruct
        // the model to ask ONE clarifying question instead of grounding a weak
        // guess; CONFIDENT grounding -> instruct it to surface the
        // getReferences "more like this" affordance. Empty retrieval -> no
        // directive (unchanged general-knowledge path). The signal is decided
        // here in Java, never by the model (D19); the model only writes the
        // resulting question/offer. `clarifyTurn` is captured pre-loop because
        // the directive influences the loop and the provenance notice below
        // must reflect the clarify DECISION, not the post-loop retrieved set.
        boolean groundedPreFetch = !retrievedPostUids.isEmpty();
        boolean clarifyTurn = groundedPreFetch && preFetch.marginalGrounding();
        String refinementDirective;
        if (!groundedPreFetch) {
            refinementDirective = "";
        } else if (clarifyTurn) {
            refinementDirective = CLARIFY_DIRECTIVE;
        } else {
            refinementDirective = AFFORDANCE_DIRECTIVE;
        }

        // 3c. Deterministic help-delivery triggers (M1-665/D67 command
        // usage, M1-666/D69 topic answer). Whether ANY help block is
        // delivered is decided HERE from the caller's own inbound text,
        // NEVER from the model's tool elections: the r2 INJECTION
        // regression was
        // an attacker-influenced model-elected helpLookup call appending
        // a privileged command's usage after sanitize. One embed
        // round-trip serves both probes (the same per-turn 768-vector);
        // each probe is a LIMIT-1 pgvector query whose result is the sole
        // input to the post-sanitize delivery step (step 9b) — a
        // model-elected helpLookup result reaches the model context only,
        // never the delivery decision. Precedence (D69): the topic probe
        // runs FIRST and a match short-circuits the command probe — a
        // caller whose question trips both wants the explanation, not a
        // bare usage block — which makes the at-most-one-help-block cap
        // structural rather than checked. Skipped when the chat breaker
        // is OPEN for the same reason the semantic pre-fetch is: the
        // embed round-trip would be wasted compute on a doomed turn
        // (every failure mode here is the same friendly degradation — no
        // block delivered).
        String deliveredTopicSlug = null;
        String deliveredCommandName = null;
        // Echo-probe gate (docs/spec/commands.md §Chat mode): an inbound
        // carrying a closed-list command token delivers no help block —
        // both probes are skipped, so the M1-685 directive stays un-armed.
        if (!breakerRegistry.wouldShortCircuit(ModelTask.CHAT_AGENT)
                && !LlmOutputSanitizerCore.containsClosedListToken(userMessage)) {
            Optional<String> deliveryVector = embedDeliveryQueryLiteral(userMessage, userId);
            if (deliveryVector.isPresent()) {
                deliveredTopicSlug =
                        lookupTopicForDelivery(deliveryVector.get(), userId).orElse(null);
                if (deliveredTopicSlug == null) {
                    deliveredCommandName = lookupIntentForDelivery(
                            deliveryVector.get(), userId, scopeKind, scopeId).orElse(null);
                    // Privileged-tier usage is DM-only: a BOT_ADMIN or
                    // GROUP_ADMIN match in a group would disclose admin
                    // syntax to every member; USER_OR_GROUP_ADMIN is fine.
                    if (deliveredCommandName != null && "group".equals(scopeKind)
                            && privilegedTier(helpHandler.tierOf(deliveredCommandName))) {
                        deliveredCommandName = null;
                    }
                }
            }
        }

        // 3d. M1-685 deterministic-delivery steering. When a topic or
        // command match fired in step 3c, the appended block IS the
        // authoritative answer to the user's matched question, so the
        // model must defer to it (a brief lead-in, not a substitute)
        // instead of presenting a competing answer — the live-observed
        // "probation" defect had the model state a false substitute
        // immediately before the correct curated answer. The defer
        // directive takes precedence over the semantic-grounding
        // refinement directive from 3b: a clarifying question or a
        // more-posts affordance alongside a delivered authoritative
        // answer would itself be the competing text this removes.
        // No-match turns (both delivery locals null) keep
        // refinementDirective unchanged — the no-match path is
        // untouched (acceptance item 3).
        boolean deterministicDelivery = deliveredTopicSlug != null || deliveredCommandName != null;
        String turnDirective = deterministicDelivery ? DETERMINISTIC_DELIVERY_DIRECTIVE : refinementDirective;

        // 4. Resolve LLM provider for chat task
        LlmProvider provider = llmRouter.forTask(ModelTask.CHAT_AGENT, scopeLanguage);

        // The reply pipeline mode, resolved once at intake and cached on the
        // context (D79): translate pins English + display leg; native generates
        // in the scope language and skips the leg. Never flips mid-turn.
        ChatReplyMode replyMode = inboundContext.replyMode();

        // 5. Build the budgeted prompt: it runs after the semantic pre-fetch
        // and directive resolution so every part of the assembled turn is
        // sized (and, on the ladder, dropped) against the configured budget.
        String languageDirective = replyMode == ChatReplyMode.NATIVE
                ? nativeReplyDirective(scopeLanguage)
                : REPLY_LANGUAGE_DIRECTIVE;
        int systemSuffixTokens = ChatSessionRepository.estimateTokens(
                languageDirective + TOOL_INSTRUCTIONS);
        ChatPromptBuilder.BuiltPrompt prompt = promptBuilder.build(
                userId, scopeKind, scopeId, userMessage,
                semanticBlock, turnDirective, systemSuffixTokens);
        String baseSystemPrompt = prompt.systemPrompt() + languageDirective;
        String augmentedSystemPrompt = baseSystemPrompt + TOOL_INSTRUCTIONS;

        // A WHOLE-dropped retrieval block is the general-knowledge path: no
        // directive may reference posts that were not folded in. (Delivery
        // steering points at the post-reply block, so it survives.)
        boolean semanticDropped = prompt.compaction().semanticBlockDropped();
        String effectiveDirective =
                deterministicDelivery ? DETERMINISTIC_DELIVERY_DIRECTIVE
                        : (semanticDropped ? "" : refinementDirective);

        // 4b. Live-text eligibility combines transport/mode and LLM support;
        // null reveal preserves today's stage-label path.
        ChatLiveTextStreamer.LiveTextReveal reveal = null;
        if (liveTextStreamer != null && scope != null
                && liveTextStreamer.eligible(scope, scopeKind, scopeLanguage, replyMode)
                && llmRouter.streamingSupportedFor(ModelTask.CHAT_AGENT, scopeLanguage)) {
            reveal = liveTextStreamer.newReveal(scope, scopeLanguage);
        }
        // The native tool transport is single-shot: a streaming turn keeps
        // the text-protocol loop (streaming + tools is a separate decision).
        boolean nativeToolsTransport = reveal == null
                && llmRouter.toolTransportFor(ModelTask.CHAT_AGENT, scopeLanguage)
                        == LlmRouter.ToolTransport.NATIVE;

        String finalText = runToolLoop(provider, augmentedSystemPrompt, baseSystemPrompt,
                prompt.userPrompt() + (semanticDropped ? "" : semanticBlock) + effectiveDirective,
                userId, scopeKind, scopeId, turnContext, retrievedPostUids, reveal,
                nativeToolsTransport, prompt.compaction().tokenBudget());

        // 6. Sanitize first: everything below evaluates the sanitized text
        // — sanitize itself can surface a protocol token (security.md
        // §Prompt-injection defenses); persist sees the redacted text.
        // A streamed turn's audit aggregates per TURN — the final text
        // plus any token matched only in a transient live update — one
        // row-set, byte-identical output (security.md §Streamed surfaces).
        String sanitized = reveal != null
                ? outputSanitizer.sanitizeStreamed(finalText, reveal.transientMatchMaxima())
                : outputSanitizer.sanitize(finalText);

        // 7. TOOL_CALL strip on the sanitized text: sanitize can assemble
        // a fragment into a full line, so the strip runs after it
        // (stripToolCalls documents the balanced/unbalanced semantics).
        StrippedToolCalls stripped = stripToolCallsWithProvenance(sanitized);
        String approved = stripped.text();

        // 8. D21 refusal intercept on the sanitized text: sanitize can
        // assemble the marker, and firing on one is fail-closed. Prefix-only:
        // the strip's drop-through can eat the closing ']' (endsWith leaks).
        // The check looks through the strip's elision separators — a marker
        // whose preceding bytes were removed still anchors the reply start.
        String trimmedFinalText = stripped.refusalComparisonText().trim();
        if (trimmedFinalText.startsWith(REFUSAL_MARKER_PREFIX)) {
            // Degrade like the unavailable path; the refusal reason is
            // LLM-authored untrusted text and MUST NOT be logged (D37).
            log.warn("CHAT_AGENT returned refusal marker for userId={}; degrading turn", userId);
            return new ChatTurnResult(
                    bundleLoader.get(BundleKeys.ERROR_CHAT_REFUSED, scopeLanguage), null, null);
        }

        int userTokens = ChatSessionRepository.estimateTokens(userMessage);
        int assistantTokens = ChatSessionRepository.estimateTokens(approved);

        // 9. Translate mode only: the display leg translates the English reply for a
        // non-en scope; native's generated text IS the delivered text (D79). Persist
        // keeps the generated text in both modes; canonicity rides the compressor (D79).
        String reply;
        // A blank operand never reaches TranslationPipeline; only a
        // non-blank reply is a translation candidate.
        if (replyMode == ChatReplyMode.TRANSLATE
                && !"en".equals(scopeLanguage) && !approved.isBlank()) {
            reply = translationPipeline.run(approved, scopeLanguage);
        } else {
            reply = approved;
        }

        // 9b. Deterministic help-block delivery — the two authorized
        // post-sanitize accretions under the amended security.md §LLM
        // output sanitizer exemption (M1-663 path (a)): the command usage
        // block (M1-665, D67) and the topic answer block (M1-666, D69).
        // For both, the emission decision (step 3c, caller's inbound text
        // via LIMIT-1 SQL) AND the composed bytes (fixed bundle values;
        // for commands the /help <cmd> runtime body via
        // HelpCommandHandler.composeUsageBlock) are deterministic
        // end-to-end — which is what qualifies them to sit after the
        // sanitizer: a topic answer must name user-tier CLOSED_LIST
        // commands (/add-source, /follow-tag, ...) that the sanitizer
        // would redact out of model-authored text. Both blocks are
        // bundle-localized per the scope's /lang, so neither passes
        // through TranslationPipeline (D43 two-path rule); the model's
        // prose above keeps its existing sanitize→translate pipeline
        // unchanged. AT MOST ONE help block per reply: step 3c's
        // topic-first short-circuit sets at most one of the two locals,
        // and each probe's SQL is LIMIT 1.
        if (deliveredTopicSlug != null) {
            // Match-not-assert (D66, carried to topics by D68): the probe
            // returned a POINTER (the topic slug); the served bytes come
            // from the in-memory corpus's bundle key, never from
            // doc_embedding. A stale target_ref that no longer resolves
            // degrades to no block.
            Optional<HelpTopicCorpus.Topic> topic = HelpTopicCorpus.byTargetRef(deliveredTopicSlug);
            if (topic.isPresent()) {
                String header = bundleLoader.get(BundleKeys.CHAT_TOPIC_DELIVERY_HEADER, scopeLanguage);
                String answer = bundleLoader.get(topic.get().answerBundleKey(), scopeLanguage);
                reply = reply + "\n\n" + header + "\n" + answer;
            }
        } else if (deliveredCommandName != null) {
            // Defense-in-depth: composeUsageBlock re-checks tier visibility
            // before composing — a match the SQL tier filter should already
            // have excluded is caught here too (adminUsageNeverDeliveredToNonAdmin).
            CallerTier caller = helpHandler.resolveCallerTier(userId, scopeKind, scopeId);
            Optional<String> usageBlock =
                    helpHandler.composeUsageBlock(deliveredCommandName, caller, scopeLanguage);
            if (usageBlock.isPresent()) {
                String header = bundleLoader.get(BundleKeys.CHAT_HELP_DELIVERY_HEADER, scopeLanguage);
                reply = reply + "\n\n" + header + "\n" + usageBlock.get();
            }
        }

        // 9c. An emptied composed reply — no text survived the sanitizer
        // and no deterministic block matched — degrades like the refusal
        // intercept: localized unavailable string, null commit, null notice.
        if (reply.isBlank()) {
            return new ChatTurnResult(
                    bundleLoader.get(BundleKeys.ERROR_CHAT_UNAVAILABLE, scopeLanguage), null, null);
        }

        // 10. Defer persistence + auto-compress to a post-delivery commit.
        // Honoring spec messaging.md §Failure handling requires that a
        // permanent delivery failure leave the context window "as if the
        // message was never generated, and chat_memory is not written" —
        // so neither turn may be committed until the router confirms the
        // reply was delivered. The persist-then-compress order is kept
        // intact (compress reads the just-persisted session token_count);
        // only the position of that pair relative to send moves.
        PendingCommit pendingCommit = () -> {
            sessionRepository.persistTurn(userId, scopeKind, scopeId, "user", userMessage, userTokens);
            sessionRepository.persistTurn(userId, scopeKind, scopeId, "assistant", approved, assistantTokens);
            return autoCompressTrigger.checkAndCompress(userId, scopeKind, scopeId, scopeLanguage);
        };

        // 11. Retrieval-provenance notice (M1-617, D58): deterministic bot
        // prose, so it takes the bundle path in the scope language — NEVER
        // the translator (D43 two-path rule; routing it through
        // TranslationPipeline would also re-open the sanitizer bypass the
        // pipeline exists to avoid). Grounded interpolates the COUNT only:
        // uids/titles are feed-derived, and interpolating attacker-
        // influenced text into a deterministic surface is the D31 class.
        // The empty-set wording claims non-grounding, not "found nothing" —
        // it also covers the breaker-open pre-fetch skip above (M1-606).
        // A low-confidence CLARIFY turn (M1-618) ships NO notice: the reply
        // is a narrowing question, not an answer grounded in specific posts,
        // so a "based on N posts" claim would misrepresent it — the router
        // omits a null notice exactly as it does for the degrade paths.
        // When the ladder dropped the retrieval block, its uids never
        // reached the prompt: the claim must reflect what was actually
        // folded in, so only then does the grounded wording apply.
        String provenanceNotice;
        boolean groundedInPrompt = !retrievedPostUids.isEmpty() && !semanticDropped;
        if (clarifyTurn && !semanticDropped) {
            provenanceNotice = null;
        } else if (!groundedInPrompt) {
            provenanceNotice =
                    bundleLoader.get(BundleKeys.CHAT_PROVENANCE_GENERAL_KNOWLEDGE, scopeLanguage);
        } else {
            provenanceNotice = MessageFormat.format(
                    bundleLoader.get(BundleKeys.CHAT_PROVENANCE_GROUNDED, scopeLanguage),
                    retrievedPostUids.size());
        }
        return new ChatTurnResult(reply, pendingCommit, provenanceNotice);
    }

    // Mirrors infochat.chat.tool.input-max-length's default (500): the
    // dispatcher rejects longer strings at its validation boundary, and the
    // leading 500 chars of a chat message carry enough signal to embed.
    // Truncating here (rather than injecting the dispatcher's config key)
    // keeps the ChatAgent constructor stable; the two values must not
    // drift, or long messages silently lose retrieval.
    static final int SEMANTIC_QUERY_MAX_CHARS = 500;

    /**
     * The deterministic semantic pre-fetch outcome: the prompt block to fold
     * in (empty when nothing grounds), plus whether the grounding is only
     * MARGINAL (M1-618) — the signal that drives the clarifying-question
     * path. {@code marginalGrounding} is meaningful only when
     * {@code promptBlock} is non-empty; the empty case is the
     * general-knowledge path, never a clarify turn.
     */
    record SemanticPreFetch(String promptBlock, boolean marginalGrounding) {
        static final SemanticPreFetch EMPTY = new SemanticPreFetch("", false);
    }

    /**
     * Runs the semanticSearch tool through the dispatcher — the same
     * validation, caps, and allowlist a model-initiated call gets — on the
     * turn's shared {@code TurnContext}, so the result is cached for the
     * tool loop (an identical model-initiated call re-uses it rather than
     * re-executing) and the pre-fetch consumes a slot of the same per-turn
     * call budget. Wraps a non-empty result in the SAME UNTRUSTED_CONTENT
     * delimiters {@link #runToolLoop} applies to tool results: retrieved
     * titles/URLs are attacker-influenced content, a prompt-injection
     * surface. Returns {@link SemanticPreFetch#EMPTY} whenever there is
     * nothing to ground on — no candidate under the distance threshold, a
     * validation rejection, or an embedding/retrieval failure — so every
     * failure mode degrades to the general-knowledge path instead of
     * aborting the turn (design 05 §5.4.6: tool failures are not
     * catastrophic). A folded result also feeds {@code retrievedPostUids}
     * for the provenance notice (M1-617) and carries the marginal-grounding
     * confidence signal for the clarifying-question path (M1-618).
     */
    private SemanticPreFetch buildSemanticRetrievalBlock(UUID userId, String scopeKind,
                                               UUID scopeId, String userMessage,
                                               ChatToolDispatcher.TurnContext turnContext,
                                               Set<String> retrievedPostUids) {
        String query = userMessage.length() > SEMANTIC_QUERY_MAX_CHARS
                ? userMessage.substring(0, SEMANTIC_QUERY_MAX_CHARS) : userMessage;
        ChatToolDispatcher.ToolResult result;
        try {
            result = toolDispatcher.dispatch("semanticSearch",
                    Map.of("query", query), userId, scopeKind, scopeId, turnContext);
        } catch (RuntimeException e) {
            // Embedding-backend or DB failure. The exception may wrap
            // LLM/DB internals but never user prose — safe to log (D37).
            SafeLog.error(log, "semanticSearch pre-fetch failed for userId=" + userId
                    + "; answering without retrieval", e);
            return SemanticPreFetch.EMPTY;
        }
        if (!(result instanceof ChatToolDispatcher.ToolResult.Success success)
                || "[]".equals(success.content())) {
            return SemanticPreFetch.EMPTY;
        }
        collectPostUids("semanticSearch", success.content(), retrievedPostUids);
        boolean marginal = isMarginalGrounding(success.content(), CONFIDENT_SIMILARITY_CUTOFF);
        String marker = UUID.randomUUID().toString();
        String block = "\n\nPosts from the user's subscribed feed semantically related "
                + "to their message:\n"
                + String.format(ChatPromptBuilder.UNTRUSTED_CONTENT_OPEN_FORMAT, marker)
                + "\n" + success.content() + "\n"
                + String.format(ChatPromptBuilder.UNTRUSTED_CONTENT_CLOSE_FORMAT, marker);
        return new SemanticPreFetch(block, marginal);
    }

    /**
     * Deterministic retrieval-confidence signal (M1-618): TRUE when the
     * strongest semantic match in a non-empty pre-fetch result only
     * marginally clears the grounding floor — the case where a general
     * assistant should ask ONE narrowing question rather than ground a weak
     * guess. Computed in Java from the {@code similarity} (= 1 - cosine
     * distance) each fused result already carries; the LLM never invents
     * "confidence" (D19 — this is a read over the SQL-decided set, it does
     * not change it). A result whose posts are ALL lexical-arm-only
     * (similarity JSON {@code null} — a keyword hit with no semantic support)
     * has no semantic best and is treated as marginal too. An unparsable or
     * non-array payload degrades to NOT-marginal (answer normally), matching
     * the pre-fetch's own fail-open posture — the folded content is JSON our
     * own tool built, so this is boundary-tolerant parsing, not a paranoid
     * internal guard.
     */
    static boolean isMarginalGrounding(String semanticResultJson, double confidentCutoff) {
        JsonNode root;
        try {
            root = TOOL_ARGS_MAPPER.readTree(semanticResultJson);
        } catch (JsonProcessingException e) {
            return false;
        }
        if (root == null || !root.isArray() || root.isEmpty()) {
            return false;
        }
        double bestSimilarity = Double.NEGATIVE_INFINITY;
        for (JsonNode element : root) {
            JsonNode similarity = element.path("similarity");
            if (similarity.isNumber()) {
                bestSimilarity = Math.max(bestSimilarity, similarity.asDouble());
            }
        }
        // No numeric similarity anywhere → the whole result is lexical-only,
        // no semantic support → treat as marginal (ask to narrow).
        if (bestSimilarity == Double.NEGATIVE_INFINITY) {
            return true;
        }
        return bestSimilarity < confidentCutoff;
    }

    /**
     * Multi-turn tool loop. Calls the LLM, parses for tool calls, executes
     * tools, feeds results back, repeats until no tool calls remain or the
     * iteration cap is reached; a non-null reveal observes streamed deltas.
     * The native transport reads the reply's structured tool calls; the
     * text transport parses the reply — both funnel the SAME dispatch
     * boundary with the shared TurnContext. Whole requests stay under
     * {@code promptTokenBudget}: fold-backs admit WHOLE JSON-array entries
     * only; an over-budget iteration routes to the final call instead.
     */
    String runToolLoop(LlmProvider provider, String systemPrompt,
                       String baseSystemPrompt, String userPrompt,
                       UUID userId, String scopeKind, UUID scopeId,
                       ChatToolDispatcher.TurnContext turnContext,
                       Set<String> retrievedPostUids,
                       ChatLiveTextStreamer.@Nullable LiveTextReveal reveal,
                       boolean nativeToolsTransport, int promptTokenBudget) {
        StringBuilder conversation = new StringBuilder(userPrompt);
        int systemTokens = ChatSessionRepository.estimateTokens(systemPrompt);

        for (int i = 0; i < MAX_TOOL_ITERATIONS; i++) {
            if (systemTokens + ChatSessionRepository.estimateTokens(conversation.toString())
                    > promptTokenBudget) {
                break;
            }
            LlmResponse response;
            if (reveal != null) {
                response = provider.generateStreaming(ModelTask.CHAT_AGENT, systemPrompt,
                        conversation.toString(), reveal::onChunk);
            } else if (nativeToolsTransport) {
                response = provider.generateWithTools(ModelTask.CHAT_AGENT, systemPrompt,
                        conversation.toString(), ChatToolCatalog.wireDeclarations());
            } else {
                response = provider.generate(
                        ModelTask.CHAT_AGENT, systemPrompt, conversation.toString());
            }
            String text = response.text();

            String toolName;
            String argsJson;
            if (nativeToolsTransport) {
                // Structured emission: the first call this reply carries is
                // this iteration's; an empty list is the final answer.
                List<LlmResponse.ToolCallRequest> calls = response.toolCalls();
                if (calls == null || calls.isEmpty()) {
                    return text;
                }
                toolName = calls.get(0).name();
                argsJson = calls.get(0).argumentsJson();
            } else {
                Matcher matcher = earliestToolCallMatch(text);
                if (matcher == null) {
                    return text;
                }
                // Extract and execute the tool call. group(2) is the opening
                // brace; scan for its balanced match so nested objects survive
                // intact. An unbalanced fragment falls back to the tail of the
                // text, which Jackson then rejects (→ empty args).
                toolName = matcher.group(1);
                int braceStart = matcher.start(2);
                int braceEnd = matchBrace(text, braceStart);
                argsJson = braceEnd >= 0
                        ? text.substring(braceStart, braceEnd + 1)
                        : text.substring(braceStart);
            }
            // Tool iterations reset the reveal and restore the GENERATING label.
            if (reveal != null) {
                reveal.onIterationToolCall();
            }

            Map<String, Object> args = parseToolArgs(argsJson);

            ChatToolDispatcher.ToolResult result =
                    toolDispatcher.dispatch(toolName, args, userId, scopeKind, scopeId, turnContext);

            String resultText = switch (result) {
                case ChatToolDispatcher.ToolResult.Success s -> {
                    collectPostUids(toolName, s.content(), retrievedPostUids);
                    yield s.content();
                }
                case ChatToolDispatcher.ToolResult.ValidationError v -> "Error: " + v.reason();
            };

            // Same injection defense as ChatPromptBuilder's blocks; the
            // fold-back fits the next request in the budget with WHOLE
            // entries only (prefix order), never a mid-entry cut.
            String resultMarker = UUID.randomUUID().toString();
            String scaffoldHead = "\n\nAssistant: " + text
                    + "\n\nTool result for " + toolName + ":\n"
                    + String.format(ChatPromptBuilder.UNTRUSTED_CONTENT_OPEN_FORMAT,
                            resultMarker) + "\n";
            String scaffoldTail = "\n"
                    + String.format(ChatPromptBuilder.UNTRUSTED_CONTENT_CLOSE_FORMAT,
                            resultMarker)
                    + "\n\n" + POST_TOOL_RESULT_INSTRUCTION;
            String fittedResultText = fitWithinBudget(
                    conversation.toString(), scaffoldHead, resultText, scaffoldTail,
                    systemTokens, promptTokenBudget);

            conversation.append(scaffoldHead).append(fittedResultText).append(scaffoldTail);
        }

        // Exceeded iteration cap — final call uses the base system prompt
        // (without tool instructions) so the LLM is not prompted toward the
        // shipped format; spontaneous native-dialect emissions can still
        // occur here and are removed by the post-sanitize strip (step 7).
        LlmResponse finalResponse = reveal != null
                ? provider.generateStreaming(ModelTask.CHAT_AGENT, baseSystemPrompt,
                        conversation.toString(), reveal::onChunk)
                : provider.generate(
                        ModelTask.CHAT_AGENT, baseSystemPrompt, conversation.toString());
        return finalResponse.text();
    }

    /** Entry-granular fit: JSON arrays admit a byte-exact prefix of whole
     * entries (uid/url intact); anything else folds WHOLE or not at all. */
    static String fitWithinBudget(String conversationSoFar, String scaffoldHead,
                                  String resultText, String scaffoldTail,
                                  int systemTokens, int promptTokenBudget) {
        if (foldedRequestTokens(conversationSoFar, scaffoldHead + resultText + scaffoldTail,
                systemTokens) <= promptTokenBudget) {
            return resultText;
        }
        List<String> entries = topLevelJsonEntries(resultText);
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder candidate = new StringBuilder("[");
        String best = "";
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                candidate.append(',');
            }
            candidate.append(entries.get(i));
            String withClose = candidate + "]";
            if (foldedRequestTokens(conversationSoFar, scaffoldHead + withClose + scaffoldTail,
                    systemTokens) > promptTokenBudget) {
                return best;
            }
            best = withClose;
        }
        return best;
    }

    private static int foldedRequestTokens(String conversationSoFar, String appended,
                                           int systemTokens) {
        return systemTokens + ChatSessionRepository.estimateTokens(conversationSoFar + appended);
    }

    /** Splits a top-level JSON array into byte-exact element slices; other
     * shapes yield nothing to fold. Our own tools emit this JSON — trusted
     * producer, not an internal defensive guard. */
    static List<String> topLevelJsonEntries(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 1; i < trimmed.length() - 1; i++) {
            char c = trimmed.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{', '[' -> {
                    if (depth++ == 0) {
                        start = i;
                    }
                }
                case '}', ']' -> depth--;
                case ',' -> {
                    if (depth == 0 && start >= 0) {
                        entries.add(trimmed.substring(start, i).trim());
                        start = -1;
                    }
                }
                default -> {
                    if (depth == 0 && start < 0 && !Character.isWhitespace(c)) {
                        start = i;
                    }
                }
            }
        }
        if (start >= 0) {
            entries.add(trimmed.substring(start, trimmed.length() - 1).trim());
        }
        return entries;
    }

    /**
     * Embeds the caller's inbound text ONCE for both deterministic
     * help-delivery probes (topic, command intent) and returns it as a
     * pgvector text literal. One embed round-trip serves the two LIMIT-1
     * probes in step 3c — the M1-666 topic probe adds an indexed query,
     * not a second embed.
     *
     * <p><b>Failure mode.</b> Embedding-backend failure degrades to
     * empty (no probe runs, no help block delivered) — the same
     * friendly-degradation posture {@code buildSemanticRetrievalBlock}
     * applies. The caller's turn still completes normally. Logged with
     * userId only (D37 — no user prose).
     */
    private Optional<String> embedDeliveryQueryLiteral(String userMessage, UUID userId) {
        String query = userMessage.length() > SEMANTIC_QUERY_MAX_CHARS
                ? userMessage.substring(0, SEMANTIC_QUERY_MAX_CHARS) : userMessage;
        try {
            List<EmbeddingResult> embedded = embeddingProvider.embed(List.of(query));
            return Optional.of(CommandIntentIndex.toVectorLiteral(embedded.get(0).vector()));
        } catch (RuntimeException e) {
            SafeLog.error(log, "help-delivery embed failed for userId=" + userId
                    + "; no help block will be delivered", e);
            return Optional.empty();
        }
    }

    /**
     * Deterministic topic lookup that drives topic-answer delivery
     * (M1-666, D69). Probes the {@code doc_kind='topic'} corpus via
     * {@link CommandIntentIndex#lookupTopic} and returns the matched
     * topic slug or {@link Optional#empty()}. No tier filter: topics are
     * tier-flat by construction (D68). The threshold is the
     * M1-649-pinned {@link CommandIntentIndex#TOPIC_SIMILARITY_THRESHOLD}
     * — unlike the command trigger there is no lower-threshold tool path
     * to be conservative against (this trigger is the corpus's ONLY
     * consumer), so the pinned starting value applies directly, with the
     * same recalibrate-as-follow-up posture (the M1-619 pattern).
     *
     * <p><b>Failure mode.</b> DB failure degrades to no match (no block
     * delivered); the turn completes normally. Logged with userId only
     * (D37 — no user prose).
     *
     * <p><b>Test seam.</b> Package-private and non-final so
     * {@code ChatAgentTest}'s {@code TestChatAgent} subclass can return
     * a canned match (or empty) without wiring DevServices Postgres.
     * The SQL shape is covered by {@code CommandIntentIndexTest} and the
     * M1-649 corpus tests.
     */
    Optional<String> lookupTopicForDelivery(String vectorLiteral, UUID userId) {
        try (Connection conn = dataSource.getConnection()) {
            // Same borrow shape as lookupIntentForDelivery below: no
            // armToolConnection (the trigger runs before the tool loop);
            // the profile-driven statement_timeout bounds the probe in
            // time and flips autocommit off, so lookupTopic's SET LOCAL
            // arming joins a live transaction (M1-660).
            cancellationService.applyStatementTimeout(conn);
            return CommandIntentIndex.lookupTopic(
                    conn, vectorLiteral, CommandIntentIndex.TOPIC_SIMILARITY_THRESHOLD);
        } catch (SQLException e) {
            SafeLog.error(log, "topic-trigger lookup failed for userId=" + userId
                    + "; no topic block will be delivered", e);
            return Optional.empty();
        }
    }

    /** Whether the matched command's catalogue tier is BOT_ADMIN or
     * GROUP_ADMIN — privileged usage a group scope must not receive. */
    private static boolean privilegedTier(Optional<HelpTier> tier) {
        return tier.map(t -> t == HelpTier.BOT_ADMIN || t == HelpTier.GROUP_ADMIN).orElse(false);
    }

    /**
     * Deterministic command-intent lookup that drives delivery (M1-665, D67).
     * Queries the shared {@link CommandIntentIndex#lookupCommand} entry
     * point with the caller's visible-command-name set
     * (tier-filter-before-return, same predicate {@code /help} applies)
     * and returns the matched command name or {@link Optional#empty()}.
     * The query vector is the step-3c shared embed
     * ({@link #embedDeliveryQueryLiteral}); the model's tool elections
     * have no influence on this path — it runs before the LLM is called
     * and never reads tool-loop state.
     *
     * <p><b>Failure mode.</b> DB failure degrades to no match (no block
     * delivered) — the same friendly-degradation posture
     * {@code buildSemanticRetrievalBlock} applies. The caller's turn
     * still completes normally; only the optional usage block is
     * dropped. Logged at WARN with userId only (D37 — no user prose).
     *
     * <p><b>Test seam.</b> Package-private and non-final so
     * {@code ChatAgentTest}'s {@code TestChatAgent} subclass can override
     * and return a canned match (or empty) without wiring DevServices
     * Postgres. The SQL and tier-filter behaviour is covered at the
     * right level by {@code HelpLookupToolIT} (same shared SQL) and
     * {@code HelpCommandHandlerTest}/{@code composeUsageBlock} (defense-
     * in-depth visibility check).
     */
    Optional<String> lookupIntentForDelivery(String vectorLiteral, UUID userId,
                                             String scopeKind, UUID scopeId) {
        CallerTier caller = helpHandler.resolveCallerTier(userId, scopeKind, scopeId);
        List<String> visibleTargets = helpHandler.visibleCommandNames(caller);
        try (Connection conn = dataSource.getConnection()) {
            // This borrow has no armToolConnection (the trigger runs before
            // the tool loop), so nothing else caps the probe — and the
            // strict_order iterative scan lookupCommand arms can walk up to
            // hnsw.max_scan_tuples when no row clears the threshold. The
            // profile-driven statement_timeout bounds this path in time
            // like the tool path's; it also flips autocommit off, so the
            // SET LOCAL arming below joins a live transaction caller-side
            // (redteam-multi 2026-07-20, M1-660).
            cancellationService.applyStatementTimeout(conn);
            return CommandIntentIndex.lookupCommand(
                    conn, vectorLiteral, visibleTargets, INTENT_DELIVERY_SIMILARITY_THRESHOLD);
        } catch (SQLException e) {
            SafeLog.error(log, "intent-trigger lookup failed for userId=" + userId
                    + "; no usage block will be delivered", e);
            return Optional.empty();
        }
    }

    /**
     * The tools whose Success results ground a reply in feed posts for the
     * provenance signal (M1-617). recallMemory and listSaves are excluded:
     * a conversation memory or a bookmark list is user-scoped state, not
     * feed-post grounding.
     */
    private static final Set<String> POST_CORPUS_TOOLS =
            Set.of("searchPosts", "semanticSearch", "getPost", "getReferences");

    /**
     * Folds the DISTINCT {@code "uid"} values of a post-corpus tool's
     * Success JSON (an array of post objects, or getPost's single object)
     * into the turn-wide set feeding the provenance notice. Non-post-corpus
     * tools are ignored. Tool output is JSON our own tools built, but an
     * unparsable or uid-less payload is simply skipped — the signal then
     * degrades toward general-knowledge rather than aborting the turn,
     * matching the pre-fetch's own failure posture.
     */
    static void collectPostUids(String toolName, String content, Set<String> sink) {
        if (!POST_CORPUS_TOOLS.contains(toolName)) {
            return;
        }
        JsonNode root;
        try {
            root = TOOL_ARGS_MAPPER.readTree(content);
        } catch (JsonProcessingException e) {
            return;
        }
        if (root == null) {
            return;
        }
        if (root.isArray()) {
            for (JsonNode element : root) {
                collectUidField(element, sink);
            }
        } else {
            collectUidField(root, sink);
        }
    }

    private static void collectUidField(JsonNode node, Set<String> sink) {
        JsonNode uid = node.path("uid");
        if (uid.isTextual()) {
            sink.add(uid.asText());
        }
    }

    /**
     * Parses the JSON args of a text-based tool call into a map of plain
     * JDK values. Array values become {@code List<String>}, nested objects
     * become {@code Map<String, Object>}, integers in {@code int} range
     * become {@code Integer}, and string values stay {@code String} — the
     * runtime types every consuming tool casts to. Malformed JSON yields an
     * empty map (no throw): the loop continues and the tool runs with no
     * args rather than aborting the whole turn. The signature is kept
     * {@code static Map<String, Object>(String)} so callers and the
     * existing unit tests are unaffected by the Jackson rewrite.
     */
    static Map<String, Object> parseToolArgs(String json) {
        Map<String, Object> args = new HashMap<>();
        if (json == null || json.isBlank()) return args;

        JsonNode root;
        try {
            root = TOOL_ARGS_MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return args;
        }
        if (root == null || !root.isObject()) return args;

        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            args.put(field.getKey(), toJavaValue(field.getValue()));
        }
        return args;
    }

    // Converts a JsonNode to the plain JDK type the tool consumers cast to:
    // arrays → List<String>, objects → Map<String, Object>, in-range
    // integers → Integer (so `(Number) args.get("limit")` and the
    // `assertEquals(10, ...)` in tests both hold), other scalars → their
    // natural Java value.
    private static Object toJavaValue(JsonNode node) {
        return switch (node.getNodeType()) {
            case ARRAY -> {
                List<String> list = new ArrayList<>(node.size());
                for (JsonNode element : node) {
                    list.add(element.asText());
                }
                yield list;
            }
            case OBJECT -> {
                Map<String, Object> map = new HashMap<>();
                Iterator<Map.Entry<String, JsonNode>> it = node.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    map.put(entry.getKey(), toJavaValue(entry.getValue()));
                }
                yield map;
            }
            case NUMBER -> node.canConvertToInt()
                    ? (Object) node.intValue()
                    : node.isIntegralNumber()
                            ? (Object) node.longValue()
                            : (Object) node.doubleValue();
            case BOOLEAN -> node.booleanValue();
            default -> node.asText();
        };
    }

    // Dual-dialect precedence: the earliest match position across both
    // accepted grammars wins; null when neither matches, and the caller
    // returns the text byte-identical.
    private static @Nullable Matcher earliestToolCallMatch(String text) {
        Matcher shipped = TOOL_CALL_PATTERN.matcher(text);
        Matcher nativeDialect = NATIVE_TOOL_CALL_PATTERN.matcher(text);
        boolean hasShipped = shipped.find();
        boolean hasNative = nativeDialect.find();
        if (hasShipped && hasNative) {
            return shipped.start() <= nativeDialect.start() ? shipped : nativeDialect;
        }
        if (hasShipped) {
            return shipped;
        }
        return hasNative ? nativeDialect : null;
    }

    /**
     * Strips residual tool-call fragments of both accepted dialects: balanced
     * removed exactly, unbalanced through end-of-text; a brace-less native
     * opener+call:+name token stripped exactly with following prose
     * preserved; a bare opener stays quoted prose. Every removed span is
     * replaced by an elision separator no token can re-form across (M1-879);
     * runs to a fixpoint across {@value #MAX_STRIP_PASSES} passes.
     */
    static String stripToolCalls(String text) {
        return stripToolCallsWithProvenance(text).text();
    }

    private static StrippedToolCalls stripToolCallsWithProvenance(String text) {
        String current = text;
        BitSet generatedElisions = new BitSet();
        Set<Integer> protectedOpeners = Set.of();
        int passes = 0;
        while (passes < MAX_STRIP_PASSES) {
            StripPass pass = stripToolCallsSinglePass(current, generatedElisions, protectedOpeners);
            // Deletion-only: a pass that removes nothing returns its input
            // byte-identical, so equal length means the fixpoint is reached.
            if (pass.text().length() == current.length()) {
                return collapseSeparatorOnly(current, generatedElisions);
            }
            current = pass.text();
            generatedElisions = pass.generatedElisions();
            protectedOpeners = new HashSet<>(pass.keptOpenerPositions());
            passes++;
        }
        return collapseSeparatorOnly(current, generatedElisions);
    }

    // Separator-only output counts as empty: the step-9c isBlank() degrade
    // must fire for a markers-only reply, never a delivered bare separator.
    private static StrippedToolCalls collapseSeparatorOnly(String text, BitSet generatedElisions) {
        return text.replace(ELISION_SEPARATOR, "").isBlank()
                ? new StrippedToolCalls("", new BitSet())
                : new StrippedToolCalls(text, generatedElisions);
    }

    /** One pass's output, the output positions of the U+2026 separators that
     *  pass inserted, plus the kept bare openers' output positions. */
    record StripPass(String text, BitSet generatedElisions, List<Integer> keptOpenerPositions) {}

    /** Strip output plus provenance: a set bit in generatedElisions means "the
     *  strip inserted this U+2026", not the model. refusalComparisonText drops
     *  exactly those strip-inserted separators; a literal U+2026 from the model
     *  stays in the comparison view, so a reply starting with one is not
     *  reclassified as a refusal (docs/spec/security.md §LLM output sanitizer). */
    record StrippedToolCalls(String text, BitSet generatedElisions) {
        String refusalComparisonText() {
            StringBuilder result = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                if (!generatedElisions.get(i)) {
                    result.append(text.charAt(i));
                }
            }
            return result.toString();
        }
    }

    // Single left-to-right scan over the ORIGINAL text: a deletion-join is
    // invisible to the pass that makes it, so the wrapper re-scans the
    // output while a kept bare opener carries its prose ruling forward.
    private static StripPass stripToolCallsSinglePass(String text, BitSet generatedElisions,
            Set<Integer> protectedOpeners) {
        StringBuilder result = new StringBuilder();
        BitSet resultGeneratedElisions = new BitSet();
        List<Integer> keptOpeners = new ArrayList<>();
        int cursor = 0;
        while (cursor < text.length()) {
            int shipped = text.indexOf("TOOL_CALL:", cursor);
            int nativeOpener = text.indexOf(NATIVE_TOOL_CALL_OPENER, cursor);
            int marker;
            boolean nativeDialect;
            if (shipped < 0) {
                marker = nativeOpener;
                nativeDialect = true;
            } else if (nativeOpener < 0) {
                marker = shipped;
                nativeDialect = false;
            } else {
                nativeDialect = nativeOpener < shipped;
                marker = nativeDialect ? nativeOpener : shipped;
            }
            if (marker < 0) {
                appendWithProvenance(result, resultGeneratedElisions, text, generatedElisions,
                        cursor, text.length());
                break;
            }
            appendWithProvenance(result, resultGeneratedElisions, text, generatedElisions,
                    cursor, marker);

            if (nativeDialect) {
                if (protectedOpeners.contains(marker)) {
                    // A bare opener ruled prose in an earlier pass: the
                    // ruling survives the re-scan (M1-875 over-strip pin).
                    keptOpeners.add(result.length());
                    appendWithProvenance(result, resultGeneratedElisions, text, generatedElisions,
                            marker, marker + NATIVE_TOOL_CALL_OPENER.length());
                    cursor = marker + NATIVE_TOOL_CALL_OPENER.length();
                    continue;
                }
                // The args brace counts only where the grammar admits it —
                // after the name, whitespace only. A brace outside the window
                // does not start a fragment (M1-870 P6).
                Matcher full = NATIVE_TOOL_CALL_PATTERN.matcher(text);
                if (full.find(marker) && full.start() == marker) {
                    int close = matchBrace(text, full.start(2));
                    if (close >= 0) {
                        resultGeneratedElisions.set(result.length());
                        result.append(ELISION_SEPARATOR);
                        cursor = close + 1;
                        continue;
                    }
                    // Unbalanced fragment: drop through end-of-text.
                    return new StripPass(result.toString(), resultGeneratedElisions, keptOpeners);
                }
                // Brace-less call attempt: strip exactly the opener plus
                // 'call:' plus the name, then scan on.
                Matcher braceless = BRACELESS_NATIVE_TOOL_CALL_PATTERN.matcher(text);
                if (braceless.find(marker) && braceless.start() == marker) {
                    resultGeneratedElisions.set(result.length());
                    result.append(ELISION_SEPARATOR);
                    cursor = braceless.end();
                    continue;
                }
                // Opener with neither an args brace nor 'call:' after it:
                // prose quoting the dialect — keep it and scan on.
                keptOpeners.add(result.length());
                appendWithProvenance(result, resultGeneratedElisions, text, generatedElisions,
                        marker, marker + NATIVE_TOOL_CALL_OPENER.length());
                cursor = marker + NATIVE_TOOL_CALL_OPENER.length();
                continue;
            }
            int lineEnd = text.indexOf('\n', marker);
            int brace = text.indexOf('{', marker);
            if (brace >= 0 && (lineEnd < 0 || brace < lineEnd)) {
                int close = matchBrace(text, brace);
                if (close >= 0) {
                    resultGeneratedElisions.set(result.length());
                    result.append(ELISION_SEPARATOR);
                    cursor = close + 1;
                    continue;
                }
                // Partial shipped fragment: drop through end-of-text.
                return new StripPass(result.toString(), resultGeneratedElisions, keptOpeners);
            }
            return new StripPass(result.toString(), resultGeneratedElisions, keptOpeners);
        }
        return new StripPass(result.toString(), resultGeneratedElisions, keptOpeners);
    }

    private static void appendWithProvenance(StringBuilder result, BitSet resultGeneratedElisions,
            String text, BitSet generatedElisions, int start, int end) {
        int resultStart = result.length();
        result.append(text, start, end);
        for (int index = generatedElisions.nextSetBit(start);
                index >= 0 && index < end;
                index = generatedElisions.nextSetBit(index + 1)) {
            resultGeneratedElisions.set(resultStart + index - start);
        }
    }

    // Returns the index of the '}' that balances the '{' at openIndex, or
    // -1 if the braces never balance. Quoted strings (and their escaped
    // characters) are skipped so braces inside a JSON string value and an
    // escaped quote (\") do not corrupt the depth count.
    private static int matchBrace(String text, int openIndex) {
        int depth = 0;
        boolean inQuote = false;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuote) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inQuote = false;
                }
            } else if (c == '"') {
                inQuote = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    // Package-private so ChatAgentTest can override with a no-op.
    // target_kind is "user" (the actor); scope_kind ("dm"/"group") goes
    // into details_json so the audit row passes the V5 CHECK constraint
    // (allowed: user, group, source, post, invite, quarantine, asset,
    // memory, system).
    void writeAuditRow(UUID userId, String scopeKind, UUID scopeId) {
        try (Connection conn = dataSource.getConnection()) {
            RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                    .actorUserId(userId)
                    .actorContactId(inboundContext.senderContactId())
                    .actorAdapter(inboundContext.adapterName())
                    .action(AuditAction.CHAT_MODE)
                    .targetKind(TargetKind.USER)
                    .targetId(userId.toString())
                    .detailsJson("{\"scope_kind\":\"" + JsonEscaper.escape(scopeKind)
                            + "\",\"scope_id\":\"" + JsonEscaper.escape(scopeId.toString()) + "\"}")
                    .build();
            auditLogWriter.write(conn, row);
        } catch (SQLException e) {
            throw new IllegalStateException("ChatAgent.writeAuditRow failed", e);
        }
    }
}
