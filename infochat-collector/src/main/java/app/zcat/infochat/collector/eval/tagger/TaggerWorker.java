package app.zcat.infochat.collector.eval.tagger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import app.zcat.infochat.collector.eval.LlmJson;
import app.zcat.infochat.collector.eval.PartitionScan;
import app.zcat.infochat.collector.eval.RetryBackoff;
import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.core.util.TagNormalizer;
import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmRouter;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collector-side scheduled poller that runs Stage 3 of the eval
 * pipeline (tag assignment from the M1-008b controlled vocabulary).
 *
 * <h2>Pickup criteria</h2>
 *
 * <p>{@code status='RAW' AND stage1_done=true AND (stage1_flagged=false
 * OR stage2_done=true) AND tagger_done=false}. The
 * {@code status='RAW'} filter excludes quarantined posts: Stage 2's
 * INJECTION/MALWARE/UNKNOWN verdicts and Stage 1's watchdog fail-closed
 * branch both set {@code status='QUARANTINED'}, and the Tagger never
 * runs on them.
 *
 * <h2>Three-surface fallback chain</h2>
 *
 * <p>Per {@code docs/spec/security.md} §Failure handling "Tagger
 * failure → fall back to source.bootstrap_tags, mark the post,
 * throttled admin notify" AND {@code docs/spec/llm.md}
 * §Failure handling (recap):
 *
 * <ol>
 *   <li><b>Schema-violating</b> — JSON parse throws OR the parsed
 *       object lacks a {@code tags} array. Retry once with
 *       {@link #FALLBACK_PROMPT_RESOURCE} (a DIFFERENT, line-oriented
 *       prompt — re-issuing the same JSON-mode prompt to the same
 *       small model produces the same garbage, so the retry shape
 *       must change).</li>
 *   <li><b>Zero-valid</b> — JSON parsed, the model PROPOSED at least
 *       one tag, and ZERO of them passed the vocabulary check after
 *       partial-valid filtering. Retry once with the SAME primary
 *       prompt — vocabulary mismatch is a content issue, not a
 *       prompt-shape issue, so the same prompt may produce a
 *       different (and valid) tag set.</li>
 *   <li><b>LLM unreachable</b> — {@code provider.generate} threw or
 *       timed out. Retry once with the SAME primary prompt, after
 *       the configured {@link RetryBackoff} sleep — unreachability
 *       is transient infrastructure, unrelated to prompt shape, and
 *       an immediate retry against a rate-limited endpoint is
 *       near-certain to fail again.</li>
 * </ol>
 *
 * <p>On second failure of any path:
 * {@code post.tags = source.bootstrap_tags},
 * {@code post.tagger_fallback = true}, log WARN with canonical
 * {@code error_class='tagger.fallback_to_bootstrap'} (consumed by
 * the future T2-G throttled admin notifier).
 *
 * <h2>An empty proposal is an outcome, not a failure</h2>
 *
 * <p>{@code prompts/tagger.md} instructs the model to emit
 * {@code {"tags": []}} when nothing in the vocabulary fits, so a clean
 * empty list is the model COMPLYING, not failing: the post is written
 * with {@code tags='{}'}, {@code tagger_fallback=false}, no retry and
 * no admin notification. {@link ValidationResult#invalidCount()} is
 * what separates it from the zero-valid failure above — a zero count
 * means the model proposed nothing, a positive count means it proposed
 * only out-of-vocabulary garbage. Collapsing the two stored every
 * genuinely off-topic post under its SOURCE's topic tags and kept the
 * tagger-fallback alarm permanently lit, so a real tagger regression
 * carried no signal (M1-726, {@code docs/spec/llm.md} §Failure
 * handling). The bootstrap fallback still covers every real failure
 * mode; an untagged post remains retrievable through the retrieval
 * branches that apply no tag predicate and renders in the digest's
 * D62 Other bucket. A {@code tags} array whose elements are all
 * non-strings is schema-violating rather than an empty proposal, so
 * the invalidCount reading — zero means the model proposed nothing —
 * stays true.
 *
 * <h2>Partial-valid handling</h2>
 *
 * <p>When the JSON parses and the {@code tags} array is non-empty,
 * each parsed tag is normalized per the tag normalization rule
 * (NFC + {@code Locale.ROOT} lower-case + character class
 * {@code ^[a-z0-9][a-z0-9-]{0,47}$}) and checked against the
 * {@link TagVocabulary}. Valid tags are kept; invalid tags are
 * silently dropped per {@code docs/spec/llm.md} §Failure handling
 * "Partial-valid handling. ... the valid tags are kept and the
 * invalid tags are silently dropped". An INFO log records the
 * valid+invalid count so a future operator alert on sustained
 * high invalid rates has the data.
 *
 * <h2>Aggregate no-tags detector</h2>
 *
 * <p>Every completed outcome is also reported to {@link NoTagsRateMonitor}
 * ({@code true} only for the LLM-answered empty proposal), which fires a
 * throttled admin alert under {@code tagger.sustained_no_tags} when the
 * no-tags share of recent completions exceeds the configured threshold
 * over a minimum sample. The per-post no-tags path itself stays silent
 * (M1-726); the RATE is the signal, never the single post (M1-735).
 *
 * <h2>Persistence cursor</h2>
 *
 * <p>The {@code UPDATE post SET tags=..., tagger_done=true,
 * tagger_fallback=...} statement writes the tag array and the
 * per-stage flags atomically per Invariant 5
 * ({@code docs/spec/schema.md} §Invariants — "the per-stage flags
 * are the durable cursor"). Splitting the write into two UPDATEs
 * would create a crash window where {@code tagger_done=true} but
 * {@code tags} is empty.
 *
 * <h2>Bounded concurrency</h2>
 *
 * <p>Each tick enumerates at most
 * {@code infochat.llm.tagger.max-concurrency} posts (the {@code LIMIT}
 * in {@link #enumeratePending(int)}) and processes them in a serial
 * {@code for} loop, so at most one tagger LLM call is ever in flight
 * per {@code docs/spec/llm.md} §Bounded concurrency and observability.
 * The configured value is the per-tick batch ceiling, not a
 * parallelism degree — there is no fan-out, so no semaphore is needed
 * to bound in-flight calls. (laptop default 4 per
 * {@code docs/design/05-llm-and-embeddings.md} §5.7.)
 *
 * <h2>Idempotency</h2>
 *
 * <p>The pickup query and the cursor UPDATE are idempotent — a
 * crash between the LLM call and the UPDATE leaves
 * {@code tagger_done=false} so the next tick re-processes the
 * post. The model is stateless across calls (Tagger has no memory
 * per {@code docs/spec/llm.md} §Determinism boundary) so re-issuing
 * the same prompt is safe.
 *
 * <h2>Re-evaluation sweep (M1-736)</h2>
 *
 * <p>A {@code tags='{}'} first-pass outcome (M1-726) is terminal only
 * for the inputs that produced it: the controlled vocabulary grows
 * (TagVocabulary's refresh path) and the configured tagger model can
 * change. After the live batch each tick, the worker re-runs the SAME
 * chain ({@link #processOne}) over posts with {@code tags='{}' AND
 * tagger_done=TRUE AND tagger_fallback=FALSE} that have not yet been
 * swept for the current input generation. Live first-pass pickup always
 * wins: the sweep only ever fills the tick's leftover batch capacity
 * ({@code maxConcurrency - live}, further capped by
 * {@code infochat.llm.tagger.sweep.batch-size}), so the single-in-flight
 * LLM bound above holds unchanged and a live backlog starves the sweep
 * to zero, never the reverse.
 *
 * <p>The generation marker lives in the singleton
 * {@code tagger_sweep_state} row (V66) and bumps when a SHA-256
 * fingerprint of the tagger's cheaply-identifiable inputs — the sorted
 * normalized vocabulary names plus the configured
 * {@code infochat.llm.tagger.model} string — changes. The baseline is
 * recorded as generation 0 on the first sweep-capable tick and existing
 * rows default {@code tagger_swept_generation=0}, so a deploy alone
 * never triggers a backlog sweep; only the first real input change
 * does. Spend is bounded twice: a per-sweep batch cap
 * ({@code sweep.batch-size}, 0 disables the sweep entirely) and a
 * per-post attempt cap ({@code sweep.max-attempts}) counted across ALL
 * generations in {@code post.tagger_sweep_attempts}. A swept row's
 * outcome resolves through the normal chain — tags found are written by
 * the same atomic cursor UPDATE, still-nothing stays {@code tags='{}'},
 * and a double failure takes the same bootstrap-fallback path (which
 * also removes the row from sweep eligibility via
 * {@code tagger_fallback=TRUE}).
 */
@ApplicationScoped
public class TaggerWorker {

    /** Classpath resource path for the JSON-primary tagger prompt. */
    public static final String PRIMARY_PROMPT_RESOURCE = "prompts/tagger.md";

    /** Classpath resource path for the line-oriented fallback prompt. */
    public static final String FALLBACK_PROMPT_RESOURCE = "prompts/tagger-fallback.md";

    /** Canonical error class emitted by the bootstrap-fallback path. */
    public static final String ERROR_CLASS_TAGGER_FALLBACK = "tagger.fallback_to_bootstrap";

    /**
     * Hard upper bound on valid tags accepted from one LLM response —
     * 2x headroom over the design-intended 1–4 tags per post. This is a
     * STRUCTURAL bound on the LLM trust boundary, separate from the
     * vocabulary/validity filtering: a misbehaving or prompt-injected
     * model returning many vocabulary-valid tags must not be able to
     * inflate {@code post.tags} unboundedly and multiply the post's
     * match count across every downstream {@code tags && ARRAY[...]}
     * overlap query (M1-328).
     */
    static final int MAX_TAGS_PER_POST = 8;

    private static final Logger LOG = LoggerFactory.getLogger(TaggerWorker.class);

    /**
     * Marker that brackets the controlled-vocabulary iteration block
     * inside the prompt templates. The body between the opening and
     * closing marker is the per-tag template; {@code {name}} is the
     * per-tag substitution token.
     */
    private static final Pattern TAGS_BLOCK = Pattern.compile(
        "\\{#tags\\}(?<body>.*?)\\{/tags\\}", Pattern.DOTALL);

    @Inject
    DataSource dataSource;

    @Inject
    LlmRouter llmRouter;

    @Inject
    TagVocabulary tagVocabulary;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @Inject
    RetryBackoff retryBackoff;

    @Inject
    NoTagsRateMonitor noTagsRateMonitor;

    @Inject
    MiscShareMonitor miscShareMonitor;

    @Inject
    TagTreeResolver tagTreeResolver;

    @Inject
    PartitionScan partitionScan;

    // The scan-window floor is computed in Java from the injected Clock and
    // bound as a Timestamp (see enumeratePending), never SQL now(), so the
    // pickup window can be pinned under a fixed test clock instead of a
    // wall-clock-relative fixture that ages out (M1-448). The systemUTC()
    // initializer is what the CDI producer supplies; injection overrides it in
    // the managed bean, so it only takes effect for hand-constructed instances.
    @Inject
    Clock clock = Clock.systemUTC();

    @ConfigProperty(name = "infochat.llm.tagger.max-concurrency")
    int maxConcurrency;

    // Package-visible (not private) so the sweep IT can opt the shared bean
    // in explicitly: the test profile sets batch-size=0 so background ticks
    // never sweep (test application.properties, M1-736). The IT writes this
    // via ClientProxy.unwrap — a field write through the injected CDI client
    // proxy hits the proxy's slot, not the contextual instance's.
    @ConfigProperty(name = "infochat.llm.tagger.sweep.batch-size", defaultValue = "4")
    int sweepBatchSize;

    @ConfigProperty(name = "infochat.llm.tagger.sweep.max-attempts", defaultValue = "3")
    int sweepMaxAttempts;

    // The cheaply-identifiable half of the model-change bump trigger: the
    // configured model STRING, woven into the sweep input fingerprint. An
    // operator swapping what answers behind the same endpoint URL is not
    // detectable and deliberately out of scope (M1-736 Notes).
    @ConfigProperty(name = "infochat.llm.tagger.model", defaultValue = "")
    String taggerModel;

    @SuppressWarnings("NullAway.Init")
    private String primaryPromptTemplate;
    @SuppressWarnings("NullAway.Init")
    private String fallbackPromptTemplate;
    @SuppressWarnings("NullAway.Init")
    private ObjectMapper objectMapper;

    @PostConstruct
    void init() {
        if (maxConcurrency < 1) {
            throw new IllegalStateException(
                "TaggerWorker: infochat.llm.tagger.max-concurrency must be >= 1; got " + maxConcurrency);
        }
        if (sweepBatchSize < 0) {
            throw new IllegalStateException(
                "TaggerWorker: infochat.llm.tagger.sweep.batch-size must be >= 0 (0 disables the sweep); got "
                    + sweepBatchSize);
        }
        if (sweepMaxAttempts < 1) {
            throw new IllegalStateException(
                "TaggerWorker: infochat.llm.tagger.sweep.max-attempts must be >= 1; got " + sweepMaxAttempts);
        }
        this.primaryPromptTemplate = loadResource(PRIMARY_PROMPT_RESOURCE);
        this.fallbackPromptTemplate = loadResource(FALLBACK_PROMPT_RESOURCE);
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Scheduled tick. Picks up posts whose Stage 1 (and Stage 2 when
     * flagged) have completed, runs the tagger on each, and writes
     * the per-stage cursor advance. Live first-pass work always wins:
     * the re-evaluation sweep (M1-736) runs only on the batch capacity
     * the live pickup left unused.
     */
    @Scheduled(every = "{infochat.llm.tagger.poll-interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void onTick() {
        List<PostRow> pending;
        try {
            pending = enumeratePending(maxConcurrency);
        } catch (SQLException e) {
            // SafeLog, never the raw Throwable (docs/spec/security.md
            // §Secrets handling — User content in exceptions).
            SafeLog.warn(LOG, "TaggerWorker: failed to enumerate pending posts; skipping tick", e);
            return;
        }
        for (PostRow row : pending) {
            try {
                processOne(row);
            } catch (RuntimeException e) {
                // A processing error on one post must not abort the
                // tick. Log and keep going so the rest of the batch
                // gets its chance.
                SafeLog.warn(LOG, "TaggerWorker: processing failed for post_id=" + row.id()
                    + "; will retry next tick", e);
            }
        }
        int sweepSlots = Math.min(maxConcurrency - pending.size(), sweepBatchSize);
        if (sweepSlots > 0) {
            runSweep(sweepSlots);
        }
    }

    /**
     * The sweep tail of {@link #onTick()}: bump the generation marker if
     * the tagger's inputs changed, then re-run the normal chain on up to
     * {@code slots} eligible {@code tags='{}'} posts. Package-private so
     * the sweep IT can drive it without waiting on the scheduler clock.
     */
    void runSweep(int slots) {
        final int generation;
        try {
            generation = currentSweepGeneration();
        } catch (SQLException e) {
            SafeLog.warn(LOG,
                "TaggerWorker: failed to read/bump the sweep generation marker; skipping sweep", e);
            return;
        }
        final List<PostRow> candidates;
        try {
            candidates = enumerateSweepCandidates(generation, slots);
        } catch (SQLException e) {
            SafeLog.warn(LOG, "TaggerWorker: failed to enumerate sweep candidates; skipping sweep", e);
            return;
        }
        for (PostRow row : candidates) {
            try {
                processOne(row);
                // Bookkeeping is a separate statement from the cursor write
                // (the cursor stays the single atomic UPDATE per Invariant
                // 5). A crash between the two re-sweeps the post next tick —
                // benign, and bounded by the attempt cap.
                markSwept(row, generation);
            } catch (RuntimeException e) {
                SafeLog.warn(LOG, "TaggerWorker: sweep failed for post_id=" + row.id()
                    + "; will retry next tick", e);
            }
        }
    }

    /**
     * Process one post: run the LLM, fold the partial-valid /
     * three-surface fallback chain, write the cursor advance.
     * Package-private so the IT can invoke directly without waiting
     * on the scheduler clock.
     */
    void processOne(PostRow row) {
        TaggerOutcome outcome = invokeWithFallbackChain(row);
        persistCursor(row, outcome);
        // Record only after the cursor write succeeded: a crash between
        // the LLM call and the UPDATE re-processes the post next tick,
        // so counting before the durable completion would double-count.
        // noTags is true ONLY for the LLM-answered empty proposal — the
        // bootstrap fallback already alarms under its own error class.
        noTagsRateMonitor.record(
            outcome.outcome() == Outcome.LLM && outcome.tags().isEmpty());
        // Same post-write discipline as the no-tags rate above; a
        // bootstrap fallback counts as NOT misc (it alarms under its own
        // class). The misc share is decision 5's vocabulary-growth trigger.
        miscShareMonitor.record(
            outcome.outcome() == Outcome.LLM
                && outcome.tags().equals(List.of(MiscShareMonitor.MISC_LEAF)));
    }

    /**
     * Run the three-surface fallback chain. Returns either a
     * successfully-derived tag list or a {@link Outcome#BOOTSTRAP}
     * indicator carrying the source's bootstrap_tags as the audit-
     * fallback tag set.
     */
    private TaggerOutcome invokeWithFallbackChain(PostRow row) {
        LlmProvider provider = llmRouter.forTask(ModelTask.TAGGER, "en");

        // Attempt 1: primary JSON prompt.
        AttemptResult first = tryOnce(provider, row, primaryPromptTemplate, /* attempt */ 1);

        if (isAnswered(first.kind())) {
            return llmOutcome(first.validTags());
        }

        // Decide the retry shape from the first-attempt failure mode.
        AttemptResult second;
        switch (first.kind()) {
            case SCHEMA_VIOLATING -> {
                // Different prompt — line-oriented fallback.
                second = tryOnce(provider, row, fallbackPromptTemplate, /* attempt */ 2);
            }
            case ZERO_VALID -> {
                // Same prompt — content issue, not prompt-shape; the
                // endpoint answered, so retry immediately.
                second = tryOnce(provider, row, primaryPromptTemplate, /* attempt */ 2);
            }
            case UNREACHABLE -> {
                // Same prompt — transient infrastructure. Sleep first:
                // an immediate retry against a rate-limited endpoint
                // is near-certain to fail again. No DB connection is
                // open here, so the sleep holds nothing but this
                // worker's tick.
                retryBackoff.sleepBeforeRetry();
                second = tryOnce(provider, row, primaryPromptTemplate, /* attempt */ 2);
            }
            default -> {
                // SUCCESS and NO_TAGS handled above; the compiler
                // exhaustiveness forces this branch to exist but it is
                // not reachable on an unanswered first attempt.
                second = first;
            }
        }

        if (isAnswered(second.kind())) {
            return llmOutcome(second.validTags());
        }

        // Second failure on any path → bootstrap-fallback audit log +
        // throttled admin notification coalesced on error_class.
        LOG.warn(
            "TaggerWorker: tagger fallback to bootstrap for post_id={} "
                + "(first_kind={} second_kind={} error_class={})",
            row.id(), first.kind(), second.kind(), ERROR_CLASS_TAGGER_FALLBACK);
        throttledAdminNotifier.notifyOnce(
            ERROR_CLASS_TAGGER_FALLBACK,
            ERROR_CLASS_TAGGER_FALLBACK,
            "Tagger fallback to bootstrap_tags for post_id=" + row.id()
                + " (first=" + first.kind() + " second=" + second.kind() + ")");
        return new TaggerOutcome(Outcome.BOOTSTRAP, row.bootstrapTags(), List.of());
    }

    /** LLM terminal: resolve the validated, capped set to ONE stored leaf (identity passthrough pre-seed, M1-865 P6); losers ride the record for M1-868 — dropped until then. The bootstrap path never resolves. */
    private TaggerOutcome llmOutcome(List<String> validTags) {
        TagTreeResolver.Resolution resolution =
            tagTreeResolver.resolve(validTags, tagVocabulary.tree());
        return new TaggerOutcome(Outcome.LLM, resolution.stored(), resolution.losers());
    }

    /**
     * Whether the attempt produced a usable answer — a tag set
     * ({@link AttemptKind#SUCCESS}) or the model's deliberate "nothing
     * fits" ({@link AttemptKind#NO_TAGS}). Both terminate the chain
     * with {@link Outcome#LLM}; only the remaining kinds are failures
     * that retry and then fall back to bootstrap tags.
     */
    private static boolean isAnswered(AttemptKind kind) {
        return kind == AttemptKind.SUCCESS || kind == AttemptKind.NO_TAGS;
    }

    /**
     * One attempt: build the prompt, call the provider, parse the
     * reply, partial-valid filter. Returns the structured outcome
     * the caller uses to decide whether to retry.
     */
    private AttemptResult tryOnce(LlmProvider provider, PostRow row, String template, int attempt) {
        // Fresh UUID per individual prompt assembly per
        // docs/design/04-security.md §4.3 — the {{id}} delimiter
        // token rotates per call so untrusted content cannot mimic
        // a stable delimiter to break the wrapper.
        String delimiterId = UUID.randomUUID().toString();
        String userPrompt = renderPrompt(template, delimiterId, row);

        LlmResponse response;
        try {
            response = provider.generate(ModelTask.TAGGER, "", userPrompt);
        } catch (RuntimeException e) {
            // SafeLog, never the raw Throwable: the provider exception
            // can echo its request context, which embeds the post body
            // woven into the prompt (docs/spec/security.md §Secrets
            // handling — User content in exceptions).
            SafeLog.warn(LOG, "TaggerWorker: LLM call attempt " + attempt + " failed for post_id="
                + row.id() + " (error_class=tagger.unreachable)", e);
            return AttemptResult.unreachable();
        }

        List<String> parsed = parseTags(response.text());
        if (parsed == null) {
            return AttemptResult.schemaViolating();
        }

        ValidationResult validated = validate(parsed);
        // Partial-valid log: emit the counts on every attempt where
        // we got past parsing so observability has the data even
        // when zero passed (the zero-valid path is the most useful
        // signal for vocabulary drift).
        LOG.info(
            "TaggerWorker: post_id={} attempt={} tagger_partial_valid valid tags={} invalid={} capped={}",
            row.id(), attempt, validated.valid().size(), validated.invalidCount(),
            validated.cappedCount());

        if (validated.valid().isEmpty()) {
            // invalidCount is the whole distinction: 0 means the model
            // proposed nothing at all (the documented "none fit" reply),
            // > 0 means every tag it did propose missed the vocabulary.
            // Only the latter is a failure worth a retry and the
            // bootstrap fallback (M1-726).
            return validated.invalidCount() == 0
                ? AttemptResult.noTags()
                : AttemptResult.zeroValid();
        }
        return AttemptResult.success(validated.valid());
    }

    /**
     * Render the prompt template: substitute the controlled
     * vocabulary into the {@code {#tags}...{/tags}} block (per-entry
     * sub-template applied once per vocabulary name), then
     * substitute {@code {{id}}}, {@code {{title}}}, {@code {{body}}}.
     */
    String renderPrompt(String template, String delimiterId, PostRow row) {
        Matcher m = TAGS_BLOCK.matcher(template);
        StringBuilder out = new StringBuilder();
        int end = 0;
        while (m.find()) {
            out.append(template, end, m.start());
            String body = m.group("body");
            for (String name : tagVocabulary.names()) {
                out.append(body.replace("{name}", name));
            }
            end = m.end();
        }
        out.append(template, end, template.length());

        String rendered = out.toString();
        return rendered
            .replace("{{id}}", delimiterId)
            .replace("{{title}}", row.title() == null ? "" : row.title())
            .replace("{{body}}", row.body() == null ? "" : row.body());
    }

    /**
     * Parse the model reply as either strict JSON {@code {"tags":
     * [...]}} or the line-oriented fallback shape {@code TAGS: tag1,
     * tag2}. Returns the raw (un-normalized, un-validated) tag list,
     * or {@code null} when the reply is schema-violating. A {@code
     * tags} array that holds elements but not a single string
     * ({@code [1,2]}, {@code [{"name":"ai"}]}, {@code [null]}) is
     * schema-violating too — a wrong-shape reply, not the model's
     * deliberate empty answer (M1-726); a mixed array keeps only its
     * string elements.
     */
    @Nullable List<String> parseTags(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // Line-oriented fallback shape first — cheap text check.
        if (trimmed.startsWith("TAGS:")) {
            String payload = trimmed.substring("TAGS:".length()).trim();
            if (payload.isEmpty()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (String token : payload.split(",", -1)) {
                String t = token.trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
            return out;
        }
        // JSON shape. DeepSeek (and other providers at temperature ~1.0) wrap
        // the {"tags":[...]} object in a markdown code fence on a fraction of
        // calls (M1-586); strip a single enclosing fence before the parse so a
        // fenced-but-valid payload is recovered. A non-fenced or malformed
        // reply is returned unchanged and still fails the parse → null, so the
        // schema-violating → bootstrap-fallback path is unchanged.
        try {
            JsonNode root = objectMapper.readTree(LlmJson.stripCodeFence(trimmed));
            JsonNode tags = root.get("tags");
            if (tags == null || !tags.isArray()) {
                return null;
            }
            List<String> out = new ArrayList<>();
            tags.forEach(node -> {
                if (node.isTextual()) {
                    out.add(node.asText());
                }
            });
            if (out.isEmpty() && !tags.isEmpty()) {
                // The model proposed SOMETHING, but none of it was a
                // string — {"tags":[1,2]}, [{"name":"ai"}], [null]. That
                // is a wrong-shape reply, not the deliberate "nothing
                // fits" empty list: returning [] here would make it
                // indistinguishable from {"tags":[]} under the
                // invalidCount discriminator, skipping the retry and the
                // bootstrap fallback the schema-violation path mandates
                // (M1-726 round-1 red-team finding). A MIXED array keeps
                // partial-valid semantics — the string elements are
                // validated and the rest silently dropped.
                return null;
            }
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Normalize every parsed tag and partition into valid / invalid
     * by vocabulary membership. Duplicates after normalization are
     * dropped (the {@link LinkedHashSet} preserves first-emit order).
     * At most {@link #MAX_TAGS_PER_POST} valid tags are accepted, in
     * emission order (the {@link LinkedHashSet} order is the model's
     * relevance signal); distinct vocabulary-valid tags past the cap
     * are counted as capped, not added. Package-private so the unit
     * test can assert the cap and the capped count directly.
     */
    ValidationResult validate(List<String> parsed) {
        Set<String> valid = new LinkedHashSet<>();
        int invalid = 0;
        int capped = 0;
        for (String raw : parsed) {
            String normalized = normalizeTag(raw);
            if (normalized == null || !tagVocabulary.contains(normalized)) {
                invalid++;
                continue;
            }
            if (valid.size() < MAX_TAGS_PER_POST) {
                valid.add(normalized);
            } else if (!valid.contains(normalized)) {
                // A distinct vocab-valid tag rejected purely by the cap.
                // A duplicate of an already-accepted tag is not a drop.
                capped++;
            }
        }
        return new ValidationResult(List.copyOf(valid), invalid, capped);
    }

    /**
     * Delegates to the shared {@link TagNormalizer#normalize(String)}
     * (NFC + {@code Locale.ROOT} lower-case + character class) and
     * returns the normalized form, or {@code null} when the input
     * fails the character-class filter. The same helper normalizes the
     * loaded vocabulary so {@link TagVocabulary#contains} is byte-equal.
     */
    static @Nullable String normalizeTag(String raw) {
        return TagNormalizer.normalize(raw);
    }

    /**
     * Atomic write of tags + per-stage cursor flags. Per Invariant 5
     * this single statement is the durable cursor for the Tagger
     * boundary; splitting it would create a crash window.
     */
    private void persistCursor(PostRow row, TaggerOutcome outcome) {
        boolean fallback = outcome.outcome() == Outcome.BOOTSTRAP;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE post SET tags = ?, tagger_done = TRUE, tagger_fallback = ? "
                     + "WHERE id = ? AND fetched_at = ?")) {
            Array tagsArray = conn.createArrayOf(
                "text", outcome.tags().toArray(new String[0]));
            ps.setArray(1, tagsArray);
            ps.setBoolean(2, fallback);
            ps.setObject(3, row.id());
            ps.setTimestamp(4, Timestamp.from(row.fetchedAt()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(
                "TaggerWorker: cursor UPDATE failed for post_id=" + row.id(), e);
        }
    }

    /**
     * Enumerate the next batch of pending posts. The pickup filter
     * excludes quarantined posts ({@code status='RAW'} is the
     * load-bearing column) and posts already-processed
     * ({@code tagger_done=false}). The {@code fetched_at} floor
     * ({@link PartitionScan#scanWindowFloor(Instant)}, sampled from the
     * injected Clock) lets the planner prune partitions of the
     * RANGE(fetched_at) post table. The ORDER BY makes the pickup
     * deterministic against test fixtures.
     */
    List<PostRow> enumeratePending(int limit) throws SQLException {
        final String sql =
            "SELECT p.id, p.fetched_at, p.title, p.body, "
                + "       COALESCE(s.bootstrap_tags, '{}'::text[]) AS bootstrap_tags "
                + "  FROM post p "
                + "  JOIN source s ON s.id = p.source_id "
                + " WHERE p.status = 'RAW' "
                + "   AND p.stage1_done = TRUE "
                + "   AND (p.stage1_flagged = FALSE OR p.stage2_done = TRUE) "
                + "   AND p.tagger_done = FALSE "
                + "   AND p.fetched_at >= ? "
                + " ORDER BY p.fetched_at, p.id "
                + " LIMIT ?";
        List<PostRow> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(partitionScan.scanWindowFloor(clock.instant())));
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = (UUID) rs.getObject(1);
                    Instant fetchedAt = rs.getTimestamp(2).toInstant();
                    String title = rs.getString(3);
                    String body = rs.getString(4);
                    String[] bootstrap = (String[]) rs.getArray(5).getArray();
                    rows.add(new PostRow(id, fetchedAt, title, body, List.of(bootstrap)));
                }
            }
        }
        return rows;
    }

    /**
     * Enumerate the next batch of sweep candidates: posts whose first pass
     * ended in the M1-726 no-tags outcome ({@code tags='{}' AND
     * tagger_done=TRUE}), excluding bootstrap-fallback rows (their source
     * tags are by design), posts already swept at {@code generation}, and
     * posts that reached the per-post attempt cap
     * ({@code infochat.llm.tagger.sweep.max-attempts}, counted across ALL
     * generations). No {@code status} filter: eligibility is defined by the
     * tagger outcome, not the pipeline stage — a quarantined post never
     * passed the tagger in the first place ({@code tagger_done=FALSE}), so
     * the first-pass exclusion carries over. The {@code fetched_at} floor
     * keeps the partition pruning of {@link #enumeratePending(int)}.
     */
    List<PostRow> enumerateSweepCandidates(int generation, int limit) throws SQLException {
        final String sql =
            "SELECT p.id, p.fetched_at, p.title, p.body, "
                + "       COALESCE(s.bootstrap_tags, '{}'::text[]) AS bootstrap_tags "
                + "  FROM post p "
                + "  JOIN source s ON s.id = p.source_id "
                + " WHERE p.tagger_done = TRUE "
                + "   AND p.tagger_fallback = FALSE "
                + "   AND p.tags = '{}'::text[] "
                + "   AND p.tagger_swept_generation < ? "
                + "   AND p.tagger_sweep_attempts < ? "
                + "   AND p.fetched_at >= ? "
                + " ORDER BY p.fetched_at, p.id "
                + " LIMIT ?";
        List<PostRow> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, generation);
            ps.setInt(2, sweepMaxAttempts);
            ps.setTimestamp(3, Timestamp.from(partitionScan.scanWindowFloor(clock.instant())));
            ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = (UUID) rs.getObject(1);
                    Instant fetchedAt = rs.getTimestamp(2).toInstant();
                    String title = rs.getString(3);
                    String body = rs.getString(4);
                    String[] bootstrap = (String[]) rs.getArray(5).getArray();
                    rows.add(new PostRow(id, fetchedAt, title, body, List.of(bootstrap)));
                }
            }
        }
        return rows;
    }

    /**
     * Read the singleton generation marker, creating or bumping it as
     * needed, and return the current generation. The first call records
     * the baseline fingerprint as generation 0 — every existing row
     * defaults {@code tagger_swept_generation=0}, so nothing becomes
     * eligible until the FIRST real input change bumps the marker to 1
     * (a deploy alone never triggers a backlog sweep). The row lock
     * ({@code FOR UPDATE}) makes the read-compare-bump sequence atomic
     * against a second collector instance racing the same tick.
     */
    private int currentSweepGeneration() throws SQLException {
        String fingerprint = sweepFingerprint(tagVocabulary.names(), taggerModel);
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int generation;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT generation, input_fingerprint FROM tagger_sweep_state"
                            + " WHERE id = 1 FOR UPDATE");
                     ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        try (PreparedStatement ins = conn.prepareStatement(
                                "INSERT INTO tagger_sweep_state (generation, input_fingerprint)"
                                    + " VALUES (0, ?)")) {
                            ins.setString(1, fingerprint);
                            ins.executeUpdate();
                        }
                        conn.commit();
                        return 0;
                    }
                    generation = rs.getInt(1);
                    if (!fingerprint.equals(rs.getString(2))) {
                        try (PreparedStatement upd = conn.prepareStatement(
                                "UPDATE tagger_sweep_state SET generation = generation + 1,"
                                    + " input_fingerprint = ?, updated_at = now()"
                                    + " WHERE id = 1 RETURNING generation")) {
                            upd.setString(1, fingerprint);
                            try (ResultSet urs = upd.executeQuery()) {
                                urs.next();
                                generation = urs.getInt(1);
                            }
                        }
                        LOG.info("TaggerWorker: tagger inputs changed;"
                            + " sweep generation bumped to {}", generation);
                    }
                }
                conn.commit();
                return generation;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /**
     * Record that {@code row} was swept at {@code generation}: pin the
     * generation so the post is not re-tried until the next input change,
     * and increment the cross-generation attempt counter the cap reads.
     */
    private void markSwept(PostRow row, int generation) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE post SET tagger_swept_generation = ?,"
                     + " tagger_sweep_attempts = tagger_sweep_attempts + 1 "
                     + "WHERE id = ? AND fetched_at = ?")) {
            ps.setInt(1, generation);
            ps.setObject(2, row.id());
            ps.setTimestamp(3, Timestamp.from(row.fetchedAt()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(
                "TaggerWorker: sweep bookkeeping UPDATE failed for post_id=" + row.id(), e);
        }
    }

    /**
     * SHA-256 hex over the tagger's cheaply-identifiable inputs: the
     * configured model string, then the normalized vocabulary names SORTED
     * (the vocabulary is an unordered {@link Set}, so the fingerprint must
     * not depend on iteration order). NUL separators keep the
     * (model, names) boundary unambiguous. Package-private so the sweep
     * IT can assert the model leg changes the fingerprint.
     */
    static String sweepFingerprint(Set<String> vocabularyNames, @Nullable String model) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JRE-mandated algorithm; unreachable in practice.
            throw new IllegalStateException("TaggerWorker: SHA-256 not available", e);
        }
        digest.update((model == null ? "" : model).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        for (String name : new TreeSet<>(vocabularyNames)) {
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    // Package-private (not private) so a foreign-TCCL test can invoke it
    // directly to pin the loader choice below.
    static String loadResource(String path) {
        // Load via the class's OWN loader, never Thread.currentThread()
        // .getContextClassLoader(): in Quarkus virtual-thread / scheduler /
        // reactive dispatch contexts the TCCL can be the system loader, so a
        // stray prompts/tagger*.md on a foreign classpath entry could shadow
        // the real tagger prompt (opus-47 collector F4). The class's own loader
        // is by construction the one carrying this module's src/main/resources,
        // which is the only trustworthy source for the prompt.
        ClassLoader cl = TaggerWorker.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(
                    "TaggerWorker: prompt resource not on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                "TaggerWorker: failed to load prompt resource " + path, e);
        }
    }

    /** One pending post + the join-cached source.bootstrap_tags.
     *  title/body reflect the V7 schema: both post columns are nullable. */
    public record PostRow(UUID id, Instant fetchedAt, @Nullable String title, @Nullable String body,
                          List<String> bootstrapTags) {
    }

    /**
     * Result of the three-surface fallback chain. {@link Outcome#LLM}
     * means the LLM produced a usable tag set; {@link Outcome#BOOTSTRAP}
     * means both attempts failed and the source's bootstrap tags are
     * the audit-fallback set. {@code losers} carries the resolution's
     * losing leaves for the Tier-2 candidate array (M1-868); always
     * empty for BOOTSTRAP, whose tags never pass through the resolver.
     */
    private record TaggerOutcome(Outcome outcome, List<String> tags, List<String> losers) {
    }

    private enum Outcome { LLM, BOOTSTRAP }

    /** Per-attempt result classification driving the retry shape. */
    private record AttemptResult(AttemptKind kind, List<String> validTags) {
        static AttemptResult success(List<String> validTags) {
            return new AttemptResult(AttemptKind.SUCCESS, validTags);
        }
        static AttemptResult schemaViolating() {
            return new AttemptResult(AttemptKind.SCHEMA_VIOLATING, List.of());
        }
        static AttemptResult zeroValid() {
            return new AttemptResult(AttemptKind.ZERO_VALID, List.of());
        }
        static AttemptResult noTags() {
            return new AttemptResult(AttemptKind.NO_TAGS, List.of());
        }
        static AttemptResult unreachable() {
            return new AttemptResult(AttemptKind.UNREACHABLE, List.of());
        }
    }

    /**
     * {@code NO_TAGS} is an ANSWER, not a failure — the model parsed
     * cleanly and proposed nothing, which the prompt explicitly asks
     * for when no vocabulary entry fits. It is kept distinct from
     * {@code SUCCESS} so the log and any future metric can tell an
     * empty result from a populated one.
     */
    private enum AttemptKind { SUCCESS, NO_TAGS, SCHEMA_VIOLATING, ZERO_VALID, UNREACHABLE }

    /**
     * Partition of one parsed tag list: {@code valid} is the accepted
     * (capped) tag set, {@code invalidCount} the vocabulary/character-
     * class rejects, {@code cappedCount} the distinct vocab-valid tags
     * dropped purely by {@link #MAX_TAGS_PER_POST}. Package-private so
     * the unit test can assert the capped count.
     */
    record ValidationResult(List<String> valid, int invalidCount, int cappedCount) {
    }
}
