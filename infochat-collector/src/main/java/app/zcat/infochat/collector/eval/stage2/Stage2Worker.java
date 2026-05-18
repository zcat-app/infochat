package app.zcat.infochat.collector.eval.stage2;

import app.zcat.infochat.collector.eval.stage1.Stage1Pipeline;
import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmRouter;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * Stage 2 of the eval pipeline: LLM judge. Invoked by
 * {@link app.zcat.infochat.collector.eval.stage1.Stage1Worker} on
 * Stage-1-flagged posts (non-watchdog branch only). Per
 * {@code docs/spec/security.md} §Ingest pipeline: "Stage 2 — LLM
 * judge. Only invoked when Stage 1 flagged something. The judge
 * sees the original (pre-redaction) content inside an
 * untrusted-content wrapper."
 *
 * <h2>Per-call flow</h2>
 * <ol>
 *   <li>Acquire one permit from the per-provider concurrency
 *       semaphore (sized by
 *       {@code infochat.llm.security.max-concurrency} per profile).
 *       Permit release happens in a {@code finally} clause so any
 *       throw downstream cannot leak permits.</li>
 *   <li>Build the prompt: fresh {@link UUID} woven into
 *       {@code {{id}}}, {@link Stage1Pipeline.Stage1Result#originalBody()}
 *       woven into {@code {{content}}}. The system+user split is
 *       a no-op: the security-judge prompt is a single block sent
 *       as the user message; the system slot stays empty so the
 *       wrapper-and-instructions live in one place per
 *       {@code docs/spec/llm.md}
 *       §Prompt-injection-aware prompt shape.</li>
 *   <li>Invoke {@link LlmRouter#forTask(ModelTask, String)
 *       LlmRouter.forTask(SECURITY_JUDGE, "en")} and call
 *       {@link LlmProvider#generate(ModelTask, String, String)}.
 *       Stage 2 is language-agnostic — {@code "en"} is the
 *       scope-default per
 *       {@code docs/spec/llm.md} §Per-task routing rules.</li>
 *   <li>Parse the reply: trim, then EXACT MATCH against the 4-token
 *       closed set {@code (BENIGN, INJECTION, MALWARE, UNKNOWN)}.
 *       Anything else (extra tokens, different case, empty) is
 *       treated as unparseable per
 *       {@code docs/spec/security.md} §Failure handling.</li>
 *   <li>On unparseable/exception/timeout, retry exactly ONCE with
 *       the SAME prompt (no fallback prompt for Stage 2 — that
 *       exists only for the Tagger in M1-034). After the retry
 *       exhausts, the outcome is
 *       {@link Stage2VerdictHandler.Verdict#INFRA_FAILURE}.</li>
 *   <li>Dispatch to {@link Stage2VerdictHandler}.</li>
 * </ol>
 *
 * <h2>Idempotency</h2>
 * <p>The {@link app.zcat.infochat.collector.outbox.OutboxRehydrator} may
 * re-enqueue a post after a crash between Stage 1's transaction
 * commit and Stage 2 starting. Stage1Worker's
 * {@code stage1_done} short-circuit prevents Stage 1 from re-running,
 * but a re-enqueue still flows through to this worker. The
 * verdict-handler SQL is idempotent — repeating the same UPDATE on
 * the same row is harmless; the
 * {@code WHERE flagged_by='stage1' AND status='PENDING'} predicate
 * on the quarantine transition is the second idempotency guard.
 * Re-running the LLM call is acceptable because the judge is
 * stateless (Stage 2 has no memory across calls per
 * {@code docs/spec/llm.md} §Determinism boundary).
 *
 * <h2>Bounded concurrency</h2>
 * <p>The {@link Semaphore} bounds in-flight Stage 2 calls per
 * {@code docs/spec/llm.md} §Bounded concurrency and observability:
 * "Per-provider concurrency is bounded so a slow provider applies
 * back-pressure to the eval queue rather than exhausting threads."
 * The permit count is read from
 * {@code infochat.llm.security.max-concurrency} (laptop 4 / vps 2
 * / pi 1 / remote-llm 8 per design §5.7). Acquisition is
 * uninterruptible so a virtual-thread park survives JDK 25's
 * thread-interrupt mechanics; a {@code finally} block guarantees
 * release.
 */
@ApplicationScoped
public class Stage2Worker {

    /** Classpath resource path for the security-judge prompt template. */
    public static final String PROMPT_RESOURCE = "prompts/security-judge.md";

    private static final Logger LOG = Logger.getLogger(Stage2Worker.class);

    @Inject
    LlmRouter llmRouter;

    @Inject
    Stage2VerdictHandler verdictHandler;

    @ConfigProperty(name = "infochat.llm.security.max-concurrency")
    int maxConcurrency;

    private Semaphore concurrencyPermits;
    private String promptTemplate;

    @PostConstruct
    void init() {
        if (maxConcurrency < 1) {
            throw new IllegalStateException(
                "Stage2Worker: infochat.llm.security.max-concurrency must be >= 1; got " + maxConcurrency);
        }
        this.concurrencyPermits = new Semaphore(maxConcurrency);
        this.promptTemplate = loadPromptTemplate();
    }

    /**
     * Run Stage 2 on one Stage-1-flagged post. The caller must
     * supply the {@link Stage1Pipeline.Stage1Result} returned from
     * Stage 1's {@code process(...)} so this worker reads the
     * ORIGINAL (pre-redaction) body for the judge prompt — the
     * redacted body in {@code post.body} would deny the judge the
     * context it needs to classify the original payload.
     *
     * <p>Precondition: {@code stage1Result != null &&
     * stage1Result.flagged() && !stage1Result.quarantinedByWatchdog()}
     * per docs/spec/security.md §Ingest pipeline (Stage 2 runs ONLY
     * when Stage 1 flagged something AND the watchdog did not
     * already QUARANTINE the post). Enforced by the sole caller
     * {@code Stage1Worker}; this method does not re-validate
     * (engineering-rules-verbatim.md §7).
     */
    public void judge(UUID postId, Instant postFetchedAt, Stage1Pipeline.Stage1Result stage1Result) {
        concurrencyPermits.acquireUninterruptibly();
        try {
            Stage2VerdictHandler.Verdict outcome = invokeWithRetryOnce(postId, stage1Result.originalBody());
            verdictHandler.apply(postId, postFetchedAt, outcome);
        } finally {
            concurrencyPermits.release();
        }
    }

    /**
     * Invoke the LLM judge with retry-once-then-fallback per
     * {@code docs/spec/security.md} §Failure handling. The SAME
     * prompt is used on both attempts; on second exhaustion the
     * outcome is {@link Stage2VerdictHandler.Verdict#INFRA_FAILURE}.
     */
    private Stage2VerdictHandler.Verdict invokeWithRetryOnce(UUID postId, String originalBody) {
        Stage2VerdictHandler.Verdict first = tryOnce(postId, originalBody, /* attempt */ 1);
        if (first != null) {
            return first;
        }
        Stage2VerdictHandler.Verdict retry = tryOnce(postId, originalBody, /* attempt */ 2);
        if (retry != null) {
            return retry;
        }
        return Stage2VerdictHandler.Verdict.INFRA_FAILURE;
    }

    /**
     * One attempt: build prompt with a FRESH per-call UUID, call
     * the provider, parse the reply. Returns the parsed verdict, or
     * {@code null} on exception / unparseable reply so the caller
     * can decide whether to retry.
     */
    private Stage2VerdictHandler.Verdict tryOnce(UUID postId, String originalBody, int attempt) {
        // Fresh UUID per individual prompt assembly per
        // docs/design/04-security.md §4.3 "The {uuid} is a fresh
        // UUID.randomUUID() per call (not per process, not per
        // post — per individual prompt assembly)."
        String delimiterId = UUID.randomUUID().toString();
        String userPrompt = promptTemplate
            .replace("{{id}}", delimiterId)
            .replace("{{content}}", originalBody == null ? "" : originalBody);

        try {
            LlmProvider provider = llmRouter.forTask(ModelTask.SECURITY_JUDGE, "en");
            LlmResponse response = provider.generate(ModelTask.SECURITY_JUDGE, "", userPrompt);
            return parseVerdict(response == null ? null : response.text());
        } catch (RuntimeException e) {
            LOG.warnf(e, "Stage 2 LLM call attempt %d failed for post_id=%s (error_class=%s)",
                attempt, postId, Stage2VerdictHandler.ERROR_CLASS_STAGE2_INFRA_FAILURE);
            return null;
        }
    }

    /**
     * Exact-match parse against the closed 4-token label set.
     * {@code .trim()} tolerates surrounding whitespace per the
     * design (single-token reply with no trailing punctuation);
     * anything else returns {@code null} which the caller treats
     * as unparseable.
     */
    static Stage2VerdictHandler.Verdict parseVerdict(String reply) {
        if (reply == null) {
            return null;
        }
        String trimmed = reply.trim();
        return switch (trimmed) {
            case "BENIGN" -> Stage2VerdictHandler.Verdict.BENIGN;
            case "INJECTION" -> Stage2VerdictHandler.Verdict.INJECTION;
            case "MALWARE" -> Stage2VerdictHandler.Verdict.MALWARE;
            case "UNKNOWN" -> Stage2VerdictHandler.Verdict.UNKNOWN;
            default -> null;
        };
    }

    /**
     * Load {@code prompts/security-judge.md} from the classpath at
     * @PostConstruct. The prompt is module-versioned (lives under
     * {@code infochat-llm-adapter/src/main/resources/prompts/}) and
     * never changes at runtime, so reading once at startup is the
     * cheap path.
     */
    private static String loadPromptTemplate() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = Stage2Worker.class.getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream(PROMPT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                    "Stage2Worker: prompt resource not on classpath: " + PROMPT_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                "Stage2Worker: failed to load prompt resource " + PROMPT_RESOURCE, e);
        }
    }
}
