package app.zcat.infochat.collector.eval.translation;

import app.zcat.infochat.collector.eval.LlmJson;
import app.zcat.infochat.collector.eval.PartitionScan;
import app.zcat.infochat.collector.eval.RetryBackoff;
import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.core.ingest.IngestTextNormalizer;
import app.zcat.infochat.core.llm.LlmOutputSanitizerCore;
import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
import app.zcat.infochat.core.util.JsonEscaper;
import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Collector-side scheduled poller that writes {@code post.title_en} /
 * {@code post.body_en} — the English ANCHOR fields retrieval runs against
 * (D29 amended, M1-749). Sits AFTER the security/tagging stages and
 * BEFORE the EmbeddingWorker: embedding's pickup gates on
 * {@code translation_done = TRUE}, and this worker is the only writer
 * that flips that cursor. Without the gate, embedding would win the
 * seconds-scale pickup race and permanently embed a non-English post
 * from non-English text ({@code embedding_done} never re-fires).
 *
 * <h2>Why a cursor flag, and the two default states</h2>
 *
 * <p>{@code translation_done} is the V28 {@code entity_done} /
 * V57 {@code classifier_done} per-stage cursor shape. V74's two-step
 * default dance makes every PRE-V74 row read TRUE (the current corpus is
 * 100% English; those rows must never wait on this worker) while every
 * post-V74 insert defaults FALSE and is gated. A post whose
 * {@code source.language = 'en'} is flipped TRUE with NO translator
 * dispatch — its {@code *_en} fields stay NULL and every reader falls
 * back through {@code coalesce(x_en, x)}, so the English corpus is
 * byte-identical in behaviour to before V74.
 *
 * <h2>Pickup criteria</h2>
 *
 * <p>{@code status='RAW' AND tagger_done=TRUE AND translation_done=FALSE},
 * joined to {@code source} for the DECLARED language (never inferred over
 * the body — language detection is explicitly out of scope).
 * {@code tagger_done=TRUE} implies Stage 1 (and Stage 2 where flagged)
 * already passed, so translation ALWAYS runs after security evaluation,
 * never before: a translator that paraphrases an injection attempt must
 * not be able to launder it past Stage 1's regex scan of the raw
 * normalized body. The {@code status='RAW'} filter mechanically excludes
 * quarantined posts.
 *
 * <h2>Failure policy</h2>
 *
 * <p>One retry on a failed attempt, then release: a failed attempt is
 * either an exception from {@link LlmProvider#generate} (UNREACHABLE —
 * sleep the configured {@link RetryBackoff} before the single retry) or
 * a reply that does not parse as the translation object
 * (SCHEMA_VIOLATING — retry immediately). On the second consecutive
 * failure the post advances {@code translation_done=TRUE} with
 * {@code title_en}/{@code body_en} left NULL and a throttled admin
 * notification fires: the post degrades to embedding-from-original and
 * stays retrievable through the {@code coalesce} fallback rather than
 * wedging out of READY forever (ReadyPromoter requires
 * {@code embedding_done}, which a permanent FALSE here would block).
 *
 * <h2>Re-drive ladder (M1-760)</h2>
 *
 * <p>That release is correct but permanent, and a transient failure — the
 * route down, a rate limit, one garbage reply — would otherwise become a
 * permanent per-post defect: the post competes on original-language text
 * inside an English-anchored index forever. {@link #onRedriveTick} tries
 * again on a bounded, slow ladder, then leaves the post alone for good.
 *
 * <p>Membership is a DURABLE STAMP ({@code post.next_translation_redrive_at},
 * V77) written by {@link #releaseNull} and by nothing else. It cannot be a
 * predicate over the post's columns: {@code releaseNull} and
 * {@link #releaseRefused} write byte-identical rows, so any derived
 * predicate would re-feed action-request content to the model on a
 * schedule, against the standing decision in {@link #processOne} that a
 * structured refusal is never retried. The English short-circuit and the
 * TRANSLATED arm likewise never stamp.
 *
 * <p>The re-drive runs on its OWN {@code @Scheduled} method with its own
 * poll interval and its own batch cap, so a re-drive backlog can never
 * consume first-pass batch capacity (the {@code ReprobeScheduler}
 * separate-scheduling-path shape, M1-754). Two controls are carried onto
 * the new path rather than inherited: the first pass excludes quarantined
 * posts through its {@code status='RAW'} filter, which a re-drive — running
 * on posts that have long since left RAW — must restate explicitly
 * ({@code status IN ('RAW','READY')}, so a post re-hidden to QUARANTINED or
 * NEEDS_REVIEW by {@code ReEvaluationJob} is skipped); and the
 * en-never-dispatched property is re-asserted at the dispatch boundary in
 * {@link #redriveOne}, since a source's declared language can change after
 * the stamp was written.
 *
 * <p>The whole ladder must finish inside the partition scan window
 * ({@link PartitionScan#scanWindowFloor}) — 16 days on {@code %pi}, the
 * tightest profile — because a rung scheduled past that floor is silently
 * unreachable. {@link #ladderSpan} computes the span from the configured
 * values and the unit test pins it under every profile's window.
 *
 * <h2>Persistence cursor</h2>
 *
 * <p>Per Invariant 5 the {@code title_en}/{@code body_en} +
 * {@code translation_done} write is a single atomic UPDATE keyed
 * {@code WHERE id=? AND fetched_at=?} (the partitioned-PK shape).
 * {@code post.title} and {@code post.body} are NEVER written — the
 * original text is what the user is shown, and the IT asserts it
 * byte-identical. A crash before the UPDATE leaves
 * {@code translation_done=FALSE} and the next tick re-picks the post;
 * a re-delivered post is simply translated again (idempotent).
 *
 * <h2>Controls carried across onto LLM-authored text</h2>
 *
 * <p>{@code title_en}/{@code body_en} are LLM-authored text derived from
 * upstream-untrusted input, so the translator's OUTPUT passes, before
 * storage, (a) {@link IngestTextNormalizer} — unconditionally, with no
 * fenced-code carve-out (docs/spec/security.md §Ingest pipeline; the
 * carve-out is chat-intake only), composed with NFKC exactly as Stage
 * 1's body strip / PostPersister's title boundary compose it — and (b)
 * the SAME sanitization pipeline as the Provider's
 * {@code LlmOutputSanitizer}, via the shared
 * {@link LlmOutputSanitizerCore} pure transform (markdown-link flatten +
 * closed-list strip on the canonical form). The collector-side
 * application emits the SAME observability as the provider bean: one
 * WARN per distinct token AND one {@code LLM_OUTPUT_SANITIZED}
 * {@code audit_log} row per distinct token per sanitize call, carrying
 * the exact occurrence count (counted, never throttled) — a surface
 * that takes the strip takes the audit (the spec attaches the row to
 * every match; the {@code RE_EVAL_RELEASED} precedent is the same
 * ingest-side posture). The audit write runs BEFORE storage and a
 * failed write fails the translation attempt — nothing is stored
 * un-audited (the provider bean's durability posture, mirrored
 * fail-closed).
 *
 * <h2>Prompt injection</h2>
 *
 * <p>The worker runs untrusted post content through an LLM. The prompt
 * is a classpath resource ({@link #PROMPT_RESOURCE}) wrapping the post
 * in a per-call rotating {@code {{id}}} delimiter
 * (docs/design/04-security.md §4.3), loaded via the class's own loader
 * (never the TCCL) so a stray {@code prompts/ingest-translator.md} on a
 * foreign classpath entry cannot shadow the real prompt. The prompt
 * carries the §4.3 structured-refusal convention: a content-side action
 * request is answered with the {@link #REFUSAL_MARKER} value, which
 * {@link #parseTranslation} branches on — the marker never reaches
 * {@code post.title_en}; the post releases NULL and notifies under the
 * refusal error class.
 *
 * <h2>Bounded concurrency</h2>
 *
 * <p>Each tick enumerates at most
 * {@code infochat.llm.translator.max-concurrency} posts and processes
 * them in a serial loop, so at most one translation LLM call is ever in
 * flight (docs/spec/llm.md §Bounded concurrency).
 */
@ApplicationScoped
public class IngestTranslationWorker {

    /** Classpath resource path for the ingest-translation prompt. */
    public static final String PROMPT_RESOURCE = "prompts/ingest-translator.md";

    /** Canonical error class emitted on the release-NULL path. */
    public static final String ERROR_CLASS_TRANSLATION_FAILURE = "translator.translation_failure";

    /** Canonical error class emitted when the model answers with the refusal marker. */
    public static final String ERROR_CLASS_TRANSLATION_REFUSAL = "translator.refusal";

    /**
     * The structured refusal marker (docs/design/04-security.md §4.3
     * convention): the prompt instructs the model to reply with
     * {@code [refused-action]} as both values when the wrapped content asks
     * for any action. The worker matches it only as a LEADING token of the
     * reply value — mid-text occurrences are content, not refusals.
     */
    static final String REFUSAL_MARKER = "[refused-action]";

    private static final Logger LOG = LoggerFactory.getLogger(IngestTranslationWorker.class);

    // The audit-log target_id for this surface's sanitizer rows. Sanitizer
    // hits are not tied to a user/post/group entity, so the target_id is a
    // fixed marker distinguishing the ingest-translation surface from the
    // provider bean's "sanitizer-output" rows.
    private static final String AUDIT_TARGET_ID = "ingest-translator-output";

    @Inject
    DataSource dataSource;

    @Inject
    LlmRouter llmRouter;

    @Inject
    AuditLogWriter auditLogWriter;

    @Inject
    ThrottledAdminNotifier throttledAdminNotifier;

    @Inject
    RetryBackoff retryBackoff;

    @Inject
    PartitionScan partitionScan;

    // The scan-window floor is computed in Java from the injected Clock and
    // bound as a Timestamp (see enumeratePending), never SQL now(), so the
    // pickup window can be pinned under a fixed test clock instead of a
    // wall-clock-relative fixture that ages out (engineering-rules §9). The
    // systemUTC() initializer is what the CDI producer supplies; injection
    // overrides it in the managed bean, so it only takes effect for
    // hand-constructed instances.
    @Inject
    Clock clock = Clock.systemUTC();

    @ConfigProperty(name = "infochat.llm.translator.max-concurrency")
    int maxConcurrency;

    @ConfigProperty(name = "infochat.llm.translator.redrive.first-delay")
    Duration redriveFirstDelay;

    @ConfigProperty(name = "infochat.llm.translator.redrive.backoff-factor")
    double redriveBackoffFactor;

    @ConfigProperty(name = "infochat.llm.translator.redrive.backoff-ceiling")
    Duration redriveBackoffCeiling;

    @ConfigProperty(name = "infochat.llm.translator.redrive.cap")
    int redriveCap;

    @ConfigProperty(name = "infochat.llm.translator.redrive.max-per-tick")
    int redriveMaxPerTick;

    @SuppressWarnings("NullAway.Init")
    private String promptTemplate;
    @SuppressWarnings("NullAway.Init")
    private ObjectMapper objectMapper;

    @PostConstruct
    void init() {
        if (maxConcurrency < 1) {
            throw new IllegalStateException(
                "IngestTranslationWorker: infochat.llm.translator.max-concurrency must be >= 1; got "
                    + maxConcurrency);
        }
        this.promptTemplate = loadResource(PROMPT_RESOURCE);
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Scheduled tick. Enumerates pending posts and translates each. A
     * processing error on one post does not abort the tick — the post
     * stays {@code translation_done=FALSE} and the next tick re-picks it.
     */
    @Scheduled(every = "{infochat.llm.translator.poll-interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void onTick() {
        List<PostRow> pending;
        try {
            pending = enumeratePending(maxConcurrency);
        } catch (SQLException e) {
            // SafeLog, never the raw Throwable (docs/spec/security.md
            // §Secrets handling — User content in exceptions).
            SafeLog.warn(LOG, "IngestTranslationWorker: failed to enumerate pending posts; skipping tick", e);
            return;
        }
        for (PostRow row : pending) {
            try {
                processOne(row);
            } catch (RuntimeException e) {
                SafeLog.warn(LOG, "IngestTranslationWorker: processing failed for post_id="
                    + row.id() + "; will retry next tick", e);
            }
        }
    }

    /**
     * Re-drive tick, a scheduling path SEPARATE from {@link #onTick} with
     * its own poll interval and its own batch cap: a re-drive backlog can
     * never delay a first-pass translation. Package-private so tests can
     * drive a sweep deterministically without waiting on the scheduler.
     */
    @Scheduled(every = "{infochat.llm.translator.redrive.poll-interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void onRedriveTick() {
        // One clock sample feeds the scan-window floor, the dueness
        // comparison and every rung this tick schedules, so a single tick's
        // decisions cannot straddle two instants (engineering-rules §9; the
        // ReprobeScheduler discipline). This component writes the stamp and
        // reads it back, so both sides are the same injected Clock.
        Instant now = clock.instant();
        int awaiting;
        List<RedriveCandidate> due;
        try {
            awaiting = countAwaitingRedrive(now);
            due = enumerateDueRedrives(redriveMaxPerTick, now);
        } catch (SQLException e) {
            SafeLog.warn(LOG, "IngestTranslationWorker: failed to enumerate re-drive candidates; "
                + "skipping tick", e);
            return;
        }
        if (awaiting > 0) {
            // A recurring non-empty set is the operator's signal that the
            // translator route itself is misconfigured, rather than
            // something to be inferred from missing translations.
            LOG.info("IngestTranslationWorker: {} post(s) awaiting an English anchor after "
                + "exhausted translation; {} due for re-drive this tick", awaiting, due.size());
        }
        for (RedriveCandidate candidate : due) {
            try {
                redriveOne(candidate, now);
            } catch (RuntimeException e) {
                SafeLog.warn(LOG, "IngestTranslationWorker: re-drive failed for post_id="
                    + candidate.post().id() + "; the ladder rung is spent", e);
            }
        }
    }

    /**
     * One re-drive: spend a rung, then re-run the EXISTING translation path
     * on the post. A success writes the anchor through the same
     * {@link #persistTranslation} statement a first-pass translation uses,
     * so it lands sanitized and atomic with no second write path. A failure
     * simply leaves the post for the next rung; the model answering with
     * the refusal marker ends the ladder immediately, because the standing
     * decision (see {@link #processOne}) is that a structured refusal is
     * never retried. Package-private for the IT.
     */
    void redriveOne(RedriveCandidate candidate, Instant now) {
        PostRow row = candidate.post();
        if ("en".equals(row.language())) {
            // The source's declared language changed to English after the
            // stamp was written. Nothing to translate, so end the ladder —
            // the en-never-dispatched property is enforced HERE, at the
            // dispatch boundary, exactly as it is on the first pass.
            clearRedriveLadder(row);
            return;
        }

        // Spend the rung BEFORE the call: the cap counts attempts, so a
        // crash mid-attempt must not hand the post a free retry (the
        // ReprobeScheduler record-attempt-first discipline).
        int attemptNumber = advanceRedriveLadder(candidate, now);

        LlmProvider provider = llmRouter.forTask(ModelTask.TRANSLATOR, row.language());
        AttemptResult first = tryOnce(provider, row, 1);
        AttemptResult chosen = first;
        if (first.kind() != AttemptKind.PARSED) {
            if (first.kind() == AttemptKind.UNREACHABLE) {
                retryBackoff.sleepBeforeRetry();
            }
            chosen = tryOnce(provider, row, 2);
        }

        ParseOutcome outcome = chosen.outcome();
        if (outcome == null) {
            LOG.warn("IngestTranslationWorker: re-drive attempt {} of {} produced no anchor for "
                + "post_id={} (error_class={} first={} second={})",
                attemptNumber, redriveCap, row.id(), ERROR_CLASS_TRANSLATION_FAILURE,
                first.kind(), chosen.kind());
            return;
        }
        switch (outcome.kind()) {
            case TRANSLATED -> {
                persistTranslation(row,
                    outcome.title() == null ? null : cleanTitle(row, outcome.title()),
                    outcome.body() == null ? null : cleanBody(row, outcome.body()));
                LOG.info("IngestTranslationWorker: re-drive attempt {} of {} anchored post_id={}",
                    attemptNumber, redriveCap, row.id());
            }
            case REFUSAL -> {
                LOG.warn("IngestTranslationWorker: model returned " + REFUSAL_MARKER
                    + " on re-drive attempt {} for post_id={}; ending the ladder "
                    + "(error_class={})", attemptNumber, row.id(), ERROR_CLASS_TRANSLATION_REFUSAL);
                clearRedriveLadder(row);
            }
        }
    }

    /**
     * Process one post. An English-source post is released with NO
     * translator dispatch — the en-never-dispatched property is enforced
     * HERE, at the dispatch boundary, not assumed from pickup. A
     * non-English post runs the translation (one retry on a failed
     * attempt), then persists the English fields + advances the cursor,
     * or releases NULL + notifies. Package-private so tests can invoke
     * directly without waiting on the scheduler clock.
     */
    void processOne(PostRow row) {
        if ("en".equals(row.language())) {
            // Declared-English source: nothing to translate. Flip the
            // cursor with title_en/body_en left NULL — every reader falls
            // back through coalesce(x_en, x), so the post is searchable and
            // embeddable from its original (English) text exactly as
            // before V74. The LLM is never resolved for this arm.
            persistTranslation(row, null, null);
            return;
        }

        LlmProvider provider = llmRouter.forTask(ModelTask.TRANSLATOR, row.language());

        AttemptResult first = tryOnce(provider, row, 1);
        AttemptResult chosen = first;
        if (first.kind() != AttemptKind.PARSED) {
            if (first.kind() == AttemptKind.UNREACHABLE) {
                // Transient infrastructure — sleep before the single
                // retry: an immediate retry against a rate-limited
                // endpoint is near-certain to fail again.
                retryBackoff.sleepBeforeRetry();
            }
            chosen = tryOnce(provider, row, 2);
        }

        ParseOutcome outcome = chosen.outcome();
        if (outcome != null) {
            switch (outcome.kind()) {
                // TRANSLATED — normalize + sanitize the model output, then
                // persist (a NULL body value means the post had no body).
                case TRANSLATED -> persistTranslation(row,
                    outcome.title() == null ? null : cleanTitle(row, outcome.title()),
                    outcome.body() == null ? null : cleanBody(row, outcome.body()));
                // REFUSAL — the model reported an action request with the
                // structured marker: persist NULL + notify under the refusal
                // error class. Not retried — the content asked for action;
                // a retry would buy the same refusal at twice the tokens.
                case REFUSAL -> releaseRefused(row);
            }
        } else {
            releaseNull(row, first.kind(), chosen.kind());
        }
    }

    /**
     * One translation attempt: assemble the prompt, call the provider,
     * parse the reply. Returns a {@link AttemptKind#PARSED} result or a
     * failure classification driving the single retry.
     */
    private AttemptResult tryOnce(LlmProvider provider, PostRow row, int attempt) {
        String delimiterId = UUID.randomUUID().toString();
        String userPrompt = renderPrompt(delimiterId, row);

        LlmResponse response;
        try {
            response = provider.generate(ModelTask.TRANSLATOR, "", userPrompt);
        } catch (RuntimeException e) {
            // SafeLog, never the raw Throwable: the provider exception
            // can echo its request context, which embeds the post body
            // woven into the prompt (docs/spec/security.md §Secrets
            // handling — User content in exceptions).
            SafeLog.warn(LOG, "IngestTranslationWorker: LLM call attempt " + attempt
                + " failed for post_id=" + row.id()
                + " (error_class=" + ERROR_CLASS_TRANSLATION_FAILURE + ")", e);
            return AttemptResult.unreachable();
        }

        ParseOutcome parsed = parseTranslation(response.text());
        if (parsed == null) {
            LOG.warn(
                "IngestTranslationWorker: schema-violating response on attempt {} for post_id={}",
                attempt, row.id());
            return AttemptResult.schemaViolating();
        }
        LOG.info(
            "IngestTranslationWorker: post_id={} attempt={} lang={} title_en_chars={} body_en_chars={}",
            row.id(), attempt, row.language(),
            parsed.title() == null ? 0 : parsed.title().length(),
            parsed.body() == null ? 0 : parsed.body().length());
        return AttemptResult.parsed(parsed);
    }

    /**
     * Substitute the rotating delimiter, the declared source language,
     * and the post title/body into the prompt template. {@code {{body}}}
     * is replaced BEFORE {@code {{title}}}: {@code String.replace} hits
     * every occurrence, so splicing the upstream-controlled title first
     * would let a title containing the literal {@code {{body}}} pull the
     * full body into the Title line — untrusted bytes reinterpreted as
     * template syntax (M1-749 red-team round 1). With this order a
     * {@code {{body}}} in the title survives as literal text; the
     * symmetric {@code {{title}}}-in-body case can only duplicate the
     * (short, already-present) title into the body line, never the
     * reverse.
     */
    String renderPrompt(String delimiterId, PostRow row) {
        String title = row.title() == null ? "" : row.title();
        String body = row.body() == null ? "" : row.body();
        return promptTemplate
            .replace("{{id}}", delimiterId)
            .replace("{{SOURCE_LANGUAGE}}", row.language())
            .replace("{{body}}", body)
            .replace("{{title}}", title);
    }

    /**
     * Parse the model reply as a JSON object
     * {@code {"title": "...", "body": "..."}}. Returns {@code null} when
     * the reply is schema-violating (not parseable as the expected object
     * shape, or the title value is empty/missing — the prompt pins a
     * non-empty title — driving the retry); a REFUSAL outcome when a
     * value leads with the {@link #REFUSAL_MARKER}; or a TRANSLATED
     * outcome carrying the stripped values, the body mapped to
     * {@code null} when the model returned an empty body (a post without
     * body text stores NULL {@code body_en}).
     */
    @Nullable ParseOutcome parseTranslation(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // Some providers wrap the JSON object in a markdown code fence on a
        // fraction of calls (M1-586); strip a single enclosing fence before
        // the strict parse so a fenced-but-valid payload is recovered.
        String unfenced = LlmJson.stripCodeFence(trimmed);
        JsonNode root;
        try {
            root = objectMapper.readTree(unfenced);
        } catch (IOException e) {
            return null;
        }
        JsonNode title = root.get("title");
        JsonNode body = root.get("body");
        if (title == null || !title.isTextual() || body == null || !body.isTextual()) {
            return null;
        }
        String titleValue = title.asText().strip();
        String bodyValue = body.asText().strip();
        if (titleValue.startsWith(REFUSAL_MARKER) || bodyValue.startsWith(REFUSAL_MARKER)) {
            return new ParseOutcome(ParseOutcome.Kind.REFUSAL, null, null);
        }
        if (titleValue.isEmpty()) {
            // The prompt pins a non-empty title; an empty one is a schema
            // violation, not a translatable result.
            return null;
        }
        return new ParseOutcome(ParseOutcome.Kind.TRANSLATED,
            titleValue, bodyValue.isEmpty() ? null : bodyValue);
    }

    /**
     * Control (a)+(b) for the title field: NFKC + the metadata-field
     * strip (bidi/zero-width AND ISO control — the same boundary
     * PostPersister applies to an original title), then the shared
     * sanitizer pipeline. Unconditional, no fenced-code carve-out.
     */
    private String cleanTitle(PostRow row, String translated) {
        String normalized = IngestTextNormalizer.stripMetadataField(
            Normalizer.normalize(translated, Normalizer.Form.NFKC));
        return sanitize(row, normalized);
    }

    /**
     * Control (a)+(b) for the body field: NFKC + the bidi/zero-width
     * strip (the same composition as Stage 1's
     * {@code Stage1Pipeline.unicodeNormalize} — control characters stay,
     * a body legitimately spans lines), then the shared sanitizer
     * pipeline. Unconditional, no fenced-code carve-out.
     */
    private String cleanBody(PostRow row, String translated) {
        String normalized = IngestTextNormalizer.stripBidiAndZeroWidth(
            Normalizer.normalize(translated, Normalizer.Form.NFKC));
        return sanitize(row, normalized);
    }

    /**
     * Control (b): the translator's output passes the SAME sanitization
     * pipeline as {@code LlmOutputSanitizer.sanitize} — markdown-link
     * flatten, then the closed-list strip on the canonical form — via
     * the shared {@link LlmOutputSanitizerCore} transform. Observability
     * matches the provider bean: one WARN per distinct token AND one
     * aggregated {@code LLM_OUTPUT_SANITIZED} audit row per distinct
     * token per call, counted, never throttled. The audit write runs
     * before storage and is fail-closed: a failed write throws, so
     * nothing is stored un-audited (the post stays
     * {@code translation_done=FALSE} and is retried next tick).
     */
    private String sanitize(PostRow row, String text) {
        String afterMarkdown = LlmOutputSanitizerCore.applyMarkdownLinkStrip(text);
        LlmOutputSanitizerCore.ClosedListStripResult result =
            LlmOutputSanitizerCore.applyClosedListStripWithMatches(afterMarkdown);
        for (Map.Entry<String, Integer> aggregated
                : LlmOutputSanitizerCore.aggregateMatchCounts(result.matches()).entrySet()) {
            LOG.warn(
                "IngestTranslationWorker: translator output sanitized token={} count={} post_id={}",
                aggregated.getKey(), aggregated.getValue(), row.id());
        }
        emitAuditRows(result.matches());
        return result.rewritten();
    }

    /**
     * Write one {@code audit_log} row per distinct matched token via
     * {@link AuditLogWriter}, all in a single transaction, the row's
     * {@code details_json.match_count} carrying the exact occurrence
     * count — the spec's counted-never-throttled promise, on this
     * surface exactly as on the provider bean's. The user-visible text
     * is never copied into a row.
     *
     * @throws IllegalStateException if any audit-row INSERT fails (the
     *         underlying {@link SQLException} is the cause): the
     *         sanitized text must NOT be stored without its audit row,
     *         so the translation attempt fails and the post is retried
     *         next tick. try-with-resources closes the connection;
     *         PgConnection rolls back an active transaction on close, so
     *         the partial-write case leaves audit_log unchanged.
     */
    private void emitAuditRows(List<String> matches) {
        if (matches.isEmpty()) {
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            for (Map.Entry<String, Integer> aggregated
                    : LlmOutputSanitizerCore.aggregateMatchCounts(matches).entrySet()) {
                String detailsJson = "{\"match_count\":" + aggregated.getValue()
                        + ",\"match_kind\":\"" + JsonEscaper.escape(aggregated.getKey()) + "\"}";
                RedactionHook.AuditRow row = RedactionHook.AuditRow.builder()
                        .action(AuditAction.LLM_OUTPUT_SANITIZED)
                        .targetKind(TargetKind.SYSTEM)
                        .targetId(AUDIT_TARGET_ID)
                        .detailsJson(detailsJson)
                        .build();
                auditLogWriter.write(conn, row);
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException(
                "IngestTranslationWorker: failed to durably audit-log sanitizer hits", e);
        }
    }

    /**
     * Release path for the structured-refusal arm: advance
     * {@code translation_done=TRUE} with {@code title_en}/{@code body_en}
     * left NULL and fire the throttled admin notification under the
     * refusal error class (distinct from
     * {@link #ERROR_CLASS_TRANSLATION_FAILURE} so an injection attempt is
     * visible as its own signal, not folded into LLM-failure noise). The
     * post is then embedded from its original text and still reaches
     * READY through the {@code coalesce} fallback.
     */
    private void releaseRefused(PostRow row) {
        LOG.warn(
            "IngestTranslationWorker: model returned " + REFUSAL_MARKER + " for post_id={} "
                + "(error_class={})",
            row.id(), ERROR_CLASS_TRANSLATION_REFUSAL);
        persistTranslation(row, null, null);
        throttledAdminNotifier.notifyOnce(
            ERROR_CLASS_TRANSLATION_REFUSAL,
            ERROR_CLASS_TRANSLATION_REFUSAL,
            "Ingest translation refused by the model (" + REFUSAL_MARKER + ") for post_id=" + row.id());
    }

    /**
     * Release path: advance {@code translation_done=TRUE} with
     * {@code title_en}/{@code body_en} left NULL after two failed attempts
     * (schema-violating and/or unreachable) and fire the throttled admin
     * notification. The post degrades to embedding-from-original and stays
     * retrievable through the {@code coalesce} fallback rather than
     * wedging out of READY forever.
     *
     * <p>This is also the ONLY path that stamps the re-drive ladder
     * (M1-760): exhausted-attempts is the one anchorless state worth
     * retrying, and it is indistinguishable on disk from the refusal and
     * never-attempted states, so the discriminator has to be written here
     * by the path that knows. The stamp is a second statement rather than a
     * column on the {@link #persistTranslation} write, so the three callers
     * that must NOT stamp keep sharing that statement untouched; a crash
     * between the two leaves the post released but unstamped, which is
     * exactly today's behaviour.
     */
    private void releaseNull(PostRow row, AttemptKind first, AttemptKind second) {
        LOG.warn(
            "IngestTranslationWorker: releasing post_id={} with NULL title_en/body_en "
                + "after two failed attempts (error_class={} first={} second={})",
            row.id(), ERROR_CLASS_TRANSLATION_FAILURE, first, second);
        persistTranslation(row, null, null);
        stampRedriveLadder(row, clock.instant().plus(redriveFirstDelay));
        throttledAdminNotifier.notifyOnce(
            ERROR_CLASS_TRANSLATION_FAILURE,
            ERROR_CLASS_TRANSLATION_FAILURE,
            "Ingest translation released NULL for post_id=" + row.id()
                + " (first=" + first + " second=" + second + ")");
    }

    /**
     * Atomic write of {@code title_en}/{@code body_en} + the
     * {@code translation_done} cursor. Per Invariant 5 this single
     * statement is the durable cursor for the translation boundary; the
     * (id, fetched_at) WHERE clause matches the partitioned-PK shape so
     * the UPDATE plans on the right partition. {@code post.title} and
     * {@code post.body} are NEVER touched — the original text stays
     * byte-identical (D29). A {@code null} field writes SQL NULL (the
     * English-source, no-body, refusal, and release paths share this
     * statement).
     *
     * <p>The write also clears {@code next_translation_redrive_at}, which
     * is what takes a successfully re-driven post out of the ladder set
     * (M1-760). Clearing it here rather than in a follow-up statement is
     * what lets a re-drive reuse this path verbatim — the anchor lands
     * sanitized and atomic, and leaving the set is part of the same
     * commit. On the four first-pass callers the column is already NULL, so
     * the assignment is inert.
     */
    private void persistTranslation(PostRow row, @Nullable String titleEn, @Nullable String bodyEn) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE post SET title_en = ?, body_en = ?, translation_done = TRUE, "
                     + "next_translation_redrive_at = NULL "
                     + "WHERE id = ? AND fetched_at = ?")) {
            if (titleEn == null) {
                ps.setNull(1, Types.VARCHAR);
            } else {
                ps.setString(1, titleEn);
            }
            if (bodyEn == null) {
                ps.setNull(2, Types.VARCHAR);
            } else {
                ps.setString(2, bodyEn);
            }
            ps.setObject(3, row.id());
            ps.setTimestamp(4, Timestamp.from(row.fetchedAt()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(
                "IngestTranslationWorker: cursor UPDATE failed for post_id=" + row.id(), e);
        }
    }

    /**
     * Write the durable re-drive stamp: the post enters the ladder set,
     * due at {@code dueAt}. Keyed on the partitioned-PK shape like every
     * other write here. {@code translation_redrive_attempts} is left at its
     * V77 default of 0 — a post can be stamped only once, because
     * {@code translation_done} never returns to FALSE.
     */
    private void stampRedriveLadder(PostRow row, Instant dueAt) {
        updatePostRedriveState(row, dueAt, null,
            "stamp re-drive ladder for post_id=" + row.id());
    }

    /**
     * End the ladder permanently: the post leaves the re-drive set with its
     * spent-attempt count intact. Used for the two outcomes that make
     * further re-drives pointless rather than merely unsuccessful — the
     * source turning out to be English, and a structured refusal.
     */
    private void clearRedriveLadder(PostRow row) {
        updatePostRedriveState(row, null, null,
            "clear re-drive ladder for post_id=" + row.id());
    }

    /**
     * Spend one rung: increment the attempt count and schedule the next
     * one, or drop the post out of the set for good once the cap is
     * reached. Returns the number of the attempt just spent, for the log
     * lines that report progress against the cap.
     */
    private int advanceRedriveLadder(RedriveCandidate candidate, Instant now) {
        int attemptNumber = candidate.attempts() + 1;
        Instant nextDueAt = attemptNumber >= redriveCap
            ? null
            : now.plus(backoffAfter(attemptNumber));
        updatePostRedriveState(candidate.post(), nextDueAt, attemptNumber,
            "advance re-drive ladder for post_id=" + candidate.post().id());
        return attemptNumber;
    }

    // The one statement behind the three ladder writes above, keyed on the
    // partitioned-PK shape like every other write here. A null dueAt writes
    // SQL NULL, which IS the "not in the set" state; a null attempts leaves
    // the spent-rung count as it was.
    private void updatePostRedriveState(PostRow row, @Nullable Instant dueAt,
                                        @Nullable Integer attempts, String what) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE post SET next_translation_redrive_at = ?, "
                     + "translation_redrive_attempts = coalesce(?, translation_redrive_attempts) "
                     + "WHERE id = ? AND fetched_at = ?")) {
            if (dueAt == null) {
                ps.setNull(1, Types.TIMESTAMP);
            } else {
                ps.setTimestamp(1, Timestamp.from(dueAt));
            }
            if (attempts == null) {
                ps.setNull(2, Types.INTEGER);
            } else {
                ps.setInt(2, attempts);
            }
            ps.setObject(3, row.id());
            ps.setTimestamp(4, Timestamp.from(row.fetchedAt()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("IngestTranslationWorker: failed to " + what, e);
        }
    }

    /**
     * How many posts are sitting in the re-drive set inside the scan
     * window, due or not — the per-tick observability number. A set that
     * stays non-empty across ticks is the signal that the translator route
     * itself is broken, which is otherwise only visible as translations
     * quietly failing to appear.
     */
    int countAwaitingRedrive(Instant now) throws SQLException {
        final String sql =
            "SELECT count(*) FROM post "
                + " WHERE next_translation_redrive_at IS NOT NULL "
                + "   AND fetched_at >= ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(partitionScan.scanWindowFloor(now)));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Enumerate the re-drive candidates due at {@code now}. Membership is
     * the durable stamp, never a predicate over the post's other columns —
     * see the class javadoc for why no such predicate exists.
     *
     * <p>The {@code status IN ('RAW','READY')} conjunct is the control the
     * first pass gets for free from its {@code status='RAW'} filter,
     * restated here because a re-drive candidate has long since left RAW: a
     * post re-hidden to QUARANTINED or advanced to NEEDS_REVIEW by
     * {@code ReEvaluationJob} must not be fed back to the translator. A post
     * whose status later returns to a releasable state becomes eligible
     * again on its remaining rungs, since the stamp outlives the detour.
     * The {@code fetched_at} floor is the same partition-pruning bound the
     * first-pass pickup uses, and the ladder is configured to finish inside
     * it (see {@link #ladderSpan}).
     */
    List<RedriveCandidate> enumerateDueRedrives(int limit, Instant now) throws SQLException {
        final String sql =
            "SELECT p.id, p.fetched_at, p.title, p.body, s.language, "
                + "       p.translation_redrive_attempts "
                + "  FROM post p "
                + "  JOIN source s ON s.id = p.source_id "
                + " WHERE p.next_translation_redrive_at IS NOT NULL "
                + "   AND p.next_translation_redrive_at <= ? "
                + "   AND p.status IN ('RAW', 'READY') "
                + "   AND p.fetched_at >= ? "
                + " ORDER BY p.next_translation_redrive_at, p.id "
                + " LIMIT ?";
        List<RedriveCandidate> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setTimestamp(2, Timestamp.from(partitionScan.scanWindowFloor(now)));
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = (UUID) rs.getObject(1);
                    Instant fetchedAt = rs.getTimestamp(2).toInstant();
                    String title = rs.getString(3);
                    String body = rs.getString(4);
                    String language = rs.getString(5);
                    int attempts = rs.getInt(6);
                    rows.add(new RedriveCandidate(
                        new PostRow(id, fetchedAt, title, body, language), attempts));
                }
            }
        }
        return rows;
    }

    // The k-th attempt schedules rung k+1 at first-delay * factor^k, capped
    // at the ceiling — ReprobeScheduler's ladder, same formula. Double math
    // cannot overflow before the min() the way long multiplication would.
    private Duration backoffAfter(int attemptsMade) {
        return backoffAfter(redriveFirstDelay, redriveBackoffFactor, redriveBackoffCeiling,
            attemptsMade);
    }

    static Duration backoffAfter(Duration firstDelay, double factor, Duration ceiling,
                                 int attemptsMade) {
        double scaledMillis = firstDelay.toMillis() * Math.pow(factor, attemptsMade);
        return Duration.ofMillis((long) Math.min(scaledMillis, (double) ceiling.toMillis()));
    }

    /**
     * Wall-clock span from the stamp to the LAST rung of a full ladder: the
     * first delay plus every backoff between the {@code cap} attempts. The
     * whole span has to fit inside the partition scan window
     * ({@code infochat.partitions.retention-days.post} widened by
     * {@link PartitionScan#PARTITION_SCAN_SLACK}) on EVERY profile — 16 days
     * on {@code %pi}, the tightest — because a rung scheduled past that
     * floor is silently unreachable: the post drops out of the enumeration
     * with its stamp still set and no signal anywhere. Static and
     * package-private so the unit test can assert the shipped configuration
     * against each profile's window without a running container.
     */
    static Duration ladderSpan(Duration firstDelay, double factor, Duration ceiling, int cap) {
        Duration span = firstDelay;
        for (int attemptsMade = 1; attemptsMade < cap; attemptsMade++) {
            span = span.plus(backoffAfter(firstDelay, factor, ceiling, attemptsMade));
        }
        return span;
    }

    /**
     * Enumerate the next batch of posts awaiting translation. The pickup
     * filter excludes quarantined posts ({@code status='RAW'}),
     * security-incomplete posts ({@code tagger_done=TRUE} — translation
     * ALWAYS runs after Stage 1/Stage 2, never before), and
     * already-processed posts ({@code translation_done=FALSE}), and joins
     * {@code source} for the DECLARED language (never inferred over the
     * body). English-source rows are deliberately NOT filtered out here:
     * the en-never-dispatched property is enforced at the dispatch
     * boundary in {@link #processOne}, where a test can assert it, not
     * assumed from a SQL predicate. The {@code fetched_at} floor
     * ({@link PartitionScan#scanWindowFloor(Instant)}, sampled from the
     * injected Clock) lets the planner prune partitions of the
     * RANGE(fetched_at) post table. The ORDER BY makes the pickup
     * deterministic against test fixtures.
     */
    List<PostRow> enumeratePending(int limit) throws SQLException {
        final String sql =
            "SELECT p.id, p.fetched_at, p.title, p.body, s.language "
                + "  FROM post p "
                + "  JOIN source s ON s.id = p.source_id "
                + " WHERE p.status = 'RAW' "
                + "   AND p.tagger_done = TRUE "
                + "   AND p.translation_done = FALSE "
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
                    String language = rs.getString(5);
                    rows.add(new PostRow(id, fetchedAt, title, body, language));
                }
            }
        }
        return rows;
    }

    // Package-private (not private) so a foreign-TCCL test can invoke it
    // directly to pin the loader choice below.
    static String loadResource(String path) {
        // Load via the class's OWN loader, never Thread.currentThread()
        // .getContextClassLoader(): in Quarkus virtual-thread / scheduler /
        // reactive dispatch contexts the TCCL can be the system loader, so a
        // stray prompts/ingest-translator.md on a foreign classpath entry
        // could shadow the real prompt (the ClassifierWorker precedent).
        // The class's own loader is by construction the one carrying this
        // module's src/main/resources, the only trustworthy source.
        ClassLoader cl = IngestTranslationWorker.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(
                    "IngestTranslationWorker: prompt resource not on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                "IngestTranslationWorker: failed to load prompt resource " + path, e);
        }
    }

    /**
     * One pending post with its source's declared language, populated by
     * {@link #enumeratePending}. {@code language} is non-null by schema
     * ({@code source.language TEXT NOT NULL DEFAULT 'en'}, V74).
     */
    public record PostRow(UUID id, Instant fetchedAt,
                          @Nullable String title, @Nullable String body,
                          String language) {
    }

    /**
     * One due re-drive candidate: the post plus the rungs already spent on
     * it. The count rides alongside {@link PostRow} rather than inside it
     * because it belongs to the ladder, not to the post's translation
     * inputs — the first-pass path has no use for it.
     */
    public record RedriveCandidate(PostRow post, int attempts) {
    }

    /**
     * Outcome of one reply parse: TRANSLATED carries the English title
     * (non-null) and body (null when the post has no body text); REFUSAL
     * is the structured-marker arm (persist NULL, notify under
     * {@link #ERROR_CLASS_TRANSLATION_REFUSAL}).
     */
    record ParseOutcome(Kind kind, @Nullable String title, @Nullable String body) {
        enum Kind { TRANSLATED, REFUSAL }
    }

    /** Per-attempt result classification driving the single retry. */
    private record AttemptResult(AttemptKind kind, @Nullable ParseOutcome outcome) {
        static AttemptResult parsed(ParseOutcome outcome) {
            return new AttemptResult(AttemptKind.PARSED, outcome);
        }
        static AttemptResult schemaViolating() {
            return new AttemptResult(AttemptKind.SCHEMA_VIOLATING, null);
        }
        static AttemptResult unreachable() {
            return new AttemptResult(AttemptKind.UNREACHABLE, null);
        }
    }

    private enum AttemptKind { PARSED, SCHEMA_VIOLATING, UNREACHABLE }
}
