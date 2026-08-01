package app.zcat.infochat.collector.eval.summary;

import app.zcat.infochat.collector.eval.LlmJson;
import app.zcat.infochat.collector.eval.PartitionScan;
import app.zcat.infochat.collector.eval.RetryBackoff;
import app.zcat.infochat.core.log.SafeLog;
import app.zcat.infochat.core.notifier.ThrottledAdminNotifier;
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
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Collector-side scheduled poller that writes {@code post.body_summary}
 * — an LLM abstract of the body — for posts whose body exceeds
 * {@code infochat.summarizer.threshold-chars}. Sits AFTER the Tagger and
 * BEFORE the Embedding stage and the ReadyPromoter: those two gate on
 * {@code (summary_done OR length(body) <= threshold)}, so an
 * over-threshold post is embedded and promoted only after this worker
 * has written its abstract (or released it NULL after double failure —
 * the first-800-chars embedding fallback stays the failure path by
 * construction). Under-threshold posts never reach the LLM and never
 * wait (M1-715 decision, docs/design/05-llm-and-embeddings.md §5.5
 * Input-text decision).
 *
 * <h2>Why a cursor flag, and why embedding must wait</h2>
 *
 * <p>Both this worker and the EmbeddingWorker poll on seconds-scale
 * schedules, but this worker pays an LLM call per post while embedding
 * batches — without the gate, embedding would win the race every time
 * and the feature would be dead on arrival. {@code summary_done} is the
 * V28 {@code entity_done} / V57 {@code classifier_done} per-stage cursor
 * shape: V71 backfills it TRUE for every pre-existing tagger-passed row,
 * so the prefix-embedded corpus is never re-summarized (roll-forward; a
 * re-embed is explicitly out of scope) and in-flight RAW posts do not
 * wedge on the new gates.
 *
 * <h2>Pickup criteria</h2>
 *
 * <p>{@code status='RAW' AND tagger_done=TRUE AND summary_done=FALSE AND
 * length(body) > threshold}. The {@code status='RAW'} filter mechanically
 * excludes quarantined posts. The {@code length(body) > threshold}
 * predicate is also what keeps a NULL body out (NULL length fails the
 * comparison), and it means the worker never spends an LLM call on a
 * body the first-800 fallback already covers.
 *
 * <h2>Failure policy</h2>
 *
 * <p>One retry on a failed attempt, then release NULL: a failed attempt
 * is either an exception from {@link LlmProvider#generate} (UNREACHABLE —
 * sleep the configured {@link RetryBackoff} before the single retry) or
 * a reply that does not parse as the summary object (SCHEMA_VIOLATING —
 * retry immediately). On the second consecutive failure the post
 * advances {@code summary_done=TRUE} with {@code body_summary} left NULL
 * and a throttled admin notification fires; embedding then proceeds from
 * the first-800 fallback exactly as it always has. A VALID empty summary
 * ({@code {"summary": ""}} — the model judging the body carries no
 * substance) is not a failure: it persists NULL with no notification.
 *
 * <h2>Persistence cursor</h2>
 *
 * <p>Per Invariant 5 the {@code body_summary} + {@code summary_done}
 * write is a single atomic UPDATE keyed {@code WHERE id=? AND
 * fetched_at=?} (the partitioned-PK shape). A crash before the UPDATE
 * leaves {@code summary_done=FALSE} and the next tick re-picks the post.
 *
 * <h2>Prompt injection</h2>
 *
 * <p>The worker runs untrusted post content through an LLM. The prompt
 * is a classpath resource ({@link #PROMPT_RESOURCE}) wrapping the post
 * in a per-call rotating {@code {{id}}} delimiter
 * (docs/design/04-security.md §4.3), loaded via the class's own loader
 * (never the TCCL) so a stray {@code prompts/body-summary.md} on a
 * foreign classpath entry cannot shadow the real prompt. The prompt
 * carries the §4.3 structured-refusal convention: a content-side action
 * request is answered with the {@link #REFUSAL_MARKER} value, which
 * {@link #parseSummary} branches on — the marker never reaches
 * {@code post.body_summary}; the post releases NULL and notifies under
 * the refusal error class. The stored abstract is hard-capped at
 * {@code infochat.summarizer.max-chars}: the value lands in
 * {@code post.body_summary} and thereby in every future embedding
 * input, so a verbose or injected reply cannot inflate it past the cap.
 *
 * <h2>Bounded concurrency</h2>
 *
 * <p>Each tick enumerates at most
 * {@code infochat.llm.summarizer.max-concurrency} posts and processes
 * them in a serial loop, so at most one summarization LLM call is ever
 * in flight (docs/spec/llm.md §Bounded concurrency).
 */
@ApplicationScoped
public class BodySummaryWorker {

    /** Classpath resource path for the body-summary prompt. */
    public static final String PROMPT_RESOURCE = "prompts/body-summary.md";

    /** Canonical error class emitted on the release-NULL path. */
    public static final String ERROR_CLASS_SUMMARY_FAILURE = "summarizer.summary_failure";

    /** Canonical error class emitted when the model answers with the refusal marker. */
    public static final String ERROR_CLASS_SUMMARY_REFUSAL = "summarizer.refusal";

    /**
     * The structured refusal marker (docs/design/04-security.md §4.3
     * convention): the prompt instructs the model to reply
     * {@code {"summary": "[refused-action]"}} when the wrapped content asks
     * for any action. The worker matches it only as a LEADING token of the
     * reply value — mid-text occurrences are content, not refusals.
     */
    static final String REFUSAL_MARKER = "[refused-action]";

    private static final Logger LOG = LoggerFactory.getLogger(BodySummaryWorker.class);

    @Inject
    DataSource dataSource;

    @Inject
    LlmRouter llmRouter;

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

    @ConfigProperty(name = "infochat.llm.summarizer.max-concurrency")
    int maxConcurrency;

    @ConfigProperty(name = "infochat.summarizer.threshold-chars")
    int thresholdChars;

    @ConfigProperty(name = "infochat.summarizer.max-chars")
    int maxChars;

    @SuppressWarnings("NullAway.Init")
    private String promptTemplate;
    @SuppressWarnings("NullAway.Init")
    private ObjectMapper objectMapper;

    @PostConstruct
    void init() {
        if (maxConcurrency < 1) {
            throw new IllegalStateException(
                "BodySummaryWorker: infochat.llm.summarizer.max-concurrency must be >= 1; got " + maxConcurrency);
        }
        if (thresholdChars < 1) {
            throw new IllegalStateException(
                "BodySummaryWorker: infochat.summarizer.threshold-chars must be >= 1; got " + thresholdChars);
        }
        if (maxChars < 1) {
            throw new IllegalStateException(
                "BodySummaryWorker: infochat.summarizer.max-chars must be >= 1; got " + maxChars);
        }
        this.promptTemplate = loadResource(PROMPT_RESOURCE);
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Scheduled tick. Enumerates pending posts and summarizes each. A
     * processing error on one post does not abort the tick — the post
     * stays {@code summary_done=FALSE} and the next tick re-picks it.
     */
    @Scheduled(every = "{infochat.llm.summarizer.poll-interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void onTick() {
        List<PostRow> pending;
        try {
            pending = enumeratePending(maxConcurrency);
        } catch (SQLException e) {
            // SafeLog, never the raw Throwable (docs/spec/security.md
            // §Secrets handling — User content in exceptions).
            SafeLog.warn(LOG, "BodySummaryWorker: failed to enumerate pending posts; skipping tick", e);
            return;
        }
        for (PostRow row : pending) {
            try {
                processOne(row);
            } catch (RuntimeException e) {
                SafeLog.warn(LOG, "BodySummaryWorker: processing failed for post_id="
                    + row.id() + "; will retry next tick", e);
            }
        }
    }

    /**
     * Process one post: run the summarization (one retry on a failed
     * attempt), then persist the abstract + advance the cursor, or
     * release NULL + notify. Package-private so the IT can invoke
     * directly without waiting on the scheduler clock.
     */
    void processOne(PostRow row) {
        LlmProvider provider = llmRouter.forTask(ModelTask.SUMMARIZER, "en");

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
                // SUMMARY — the abstract (possibly truncated to the cap).
                case SUMMARY -> persistSummary(row, outcome.summary());
                // EMPTY — a valid empty summary (the model judged the body
                // carries no substance): persist NULL with no notification,
                // embedding falls back to the first-800 input.
                case EMPTY -> persistSummary(row, null);
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
     * One summarization attempt: assemble the prompt, call the provider,
     * parse the reply. Returns a {@link AttemptKind#PARSED} result or a
     * failure classification driving the single retry.
     */
    private AttemptResult tryOnce(LlmProvider provider, PostRow row, int attempt) {
        String delimiterId = UUID.randomUUID().toString();
        String userPrompt = renderPrompt(delimiterId, row);

        LlmResponse response;
        try {
            response = provider.generate(ModelTask.SUMMARIZER, "", userPrompt);
        } catch (RuntimeException e) {
            // SafeLog, never the raw Throwable: the provider exception
            // can echo its request context, which embeds the post body
            // woven into the prompt (docs/spec/security.md §Secrets
            // handling — User content in exceptions).
            SafeLog.warn(LOG, "BodySummaryWorker: LLM call attempt " + attempt
                + " failed for post_id=" + row.id()
                + " (error_class=" + ERROR_CLASS_SUMMARY_FAILURE + ")", e);
            return AttemptResult.unreachable();
        }

        ParseOutcome parsed = parseSummary(response.text());
        if (parsed == null) {
            LOG.warn(
                "BodySummaryWorker: schema-violating response on attempt {} for post_id={}",
                attempt, row.id());
            return AttemptResult.schemaViolating();
        }
        LOG.info(
            "BodySummaryWorker: post_id={} attempt={} summary_chars={}",
            row.id(), attempt, parsed.summary() == null ? 0 : parsed.summary().length());
        return AttemptResult.parsed(parsed);
    }

    /**
     * Substitute the rotating delimiter and the post title/body into the
     * prompt template.
     */
    String renderPrompt(String delimiterId, PostRow row) {
        String title = row.title() == null ? "" : row.title();
        String body = row.body() == null ? "" : row.body();
        return promptTemplate
            .replace("{{id}}", delimiterId)
            .replace("{{title}}", title)
            .replace("{{body}}", body);
    }

    /**
     * Parse the model reply as a JSON object {@code {"summary": "..."}}.
     * Returns {@code null} when the reply is schema-violating (not
     * parseable as the expected object shape — drives the retry); an
     * EMPTY outcome when the model returned a valid empty summary (no
     * substance — persists NULL, no notification); a REFUSAL outcome when
     * the value leads with the {@link #REFUSAL_MARKER} (the model
     * reporting an in-wrapper action request per the prompt's refusal
     * rule — persists NULL and notifies under the refusal class); or a
     * SUMMARY outcome carrying the stripped abstract, hard-capped at
     * {@link #maxChars} code points.
     */
    @Nullable ParseOutcome parseSummary(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // Some providers wrap the JSON object in a markdown code fence on a
        // fraction of calls (M1-586); strip a single enclosing fence before
        // the strict parse so a fenced-but-valid payload is recovered. A
        // non-fenced or malformed reply is returned unchanged and still fails
        // the parse below → null, so the schema-violating path is unchanged.
        String unfenced = LlmJson.stripCodeFence(trimmed);
        JsonNode root;
        try {
            root = objectMapper.readTree(unfenced);
        } catch (IOException e) {
            return null;
        }
        JsonNode summary = root.get("summary");
        if (summary == null || !summary.isTextual()) {
            return null;
        }
        String value = summary.asText().strip();
        if (value.isEmpty()) {
            return new ParseOutcome(ParseOutcome.Kind.EMPTY, null);
        }
        if (value.startsWith(REFUSAL_MARKER)) {
            return new ParseOutcome(ParseOutcome.Kind.REFUSAL, null);
        }
        return new ParseOutcome(ParseOutcome.Kind.SUMMARY, truncateToCap(value));
    }

    /**
     * Hard cap on the stored abstract, in chars, truncated on a code-point
     * boundary so a surrogate pair is never split (the cap applies AFTER
     * stripping, so whitespace games cannot push real content past it).
     */
    String truncateToCap(String value) {
        if (value.length() <= maxChars) {
            return value;
        }
        int end = maxChars;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * Release path for the structured-refusal arm: advance
     * {@code summary_done=TRUE} with {@code body_summary} left NULL and
     * fire the throttled admin notification under the refusal error class
     * (distinct from {@link #ERROR_CLASS_SUMMARY_FAILURE} so an injection
     * attempt is visible as its own signal, not folded into LLM-failure
     * noise). The post is then embedded from the first-800-chars
     * fallback and still reaches READY.
     */
    private void releaseRefused(PostRow row) {
        LOG.warn(
            "BodySummaryWorker: model returned " + REFUSAL_MARKER + " for post_id={} "
                + "(error_class={})",
            row.id(), ERROR_CLASS_SUMMARY_REFUSAL);
        persistSummary(row, null);
        throttledAdminNotifier.notifyOnce(
            ERROR_CLASS_SUMMARY_REFUSAL,
            ERROR_CLASS_SUMMARY_REFUSAL,
            "Body summary refused by the model (" + REFUSAL_MARKER + ") for post_id=" + row.id());
    }

    /**
     * Release path: advance {@code summary_done=TRUE} with
     * {@code body_summary} left NULL after two failed attempts
     * (schema-violating and/or unreachable) and fire the throttled admin
     * notification. The post is then embedded from the first-800-chars
     * fallback and still reaches READY.
     */
    private void releaseNull(PostRow row, AttemptKind first, AttemptKind second) {
        LOG.warn(
            "BodySummaryWorker: releasing post_id={} with NULL body_summary after two failed attempts "
                + "(error_class={} first={} second={})",
            row.id(), ERROR_CLASS_SUMMARY_FAILURE, first, second);
        persistSummary(row, null);
        throttledAdminNotifier.notifyOnce(
            ERROR_CLASS_SUMMARY_FAILURE,
            ERROR_CLASS_SUMMARY_FAILURE,
            "Body summary released NULL for post_id=" + row.id()
                + " (first=" + first + " second=" + second + ")");
    }

    /**
     * Atomic write of {@code body_summary} + the {@code summary_done}
     * cursor. Per Invariant 5 this single statement is the durable cursor
     * for the summarizer boundary; the (id, fetched_at) WHERE clause
     * matches the partitioned-PK shape so the UPDATE plans on the right
     * partition. A {@code null} summary writes SQL NULL (the valid-empty
     * and release paths share this statement).
     */
    private void persistSummary(PostRow row, @Nullable String summary) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE post SET body_summary = ?, summary_done = TRUE "
                     + "WHERE id = ? AND fetched_at = ?")) {
            if (summary == null) {
                ps.setNull(1, Types.VARCHAR);
            } else {
                ps.setString(1, summary);
            }
            ps.setObject(2, row.id());
            ps.setTimestamp(3, Timestamp.from(row.fetchedAt()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(
                "BodySummaryWorker: cursor UPDATE failed for post_id=" + row.id(), e);
        }
    }

    /**
     * Enumerate the next batch of posts awaiting summarization. The
     * pickup filter excludes quarantined posts ({@code status='RAW'}),
     * already-processed posts ({@code summary_done=FALSE}), and
     * under-threshold bodies ({@code length(body) > threshold} — the
     * same threshold the EmbeddingWorker/ReadyPromoter gates escape
     * with, so an under-threshold post never waits on this worker). The
     * {@code fetched_at} floor ({@link PartitionScan#scanWindowFloor(Instant)},
     * sampled from the injected Clock) lets the planner prune partitions
     * of the RANGE(fetched_at) post table. The ORDER BY makes the pickup
     * deterministic against test fixtures.
     */
    List<PostRow> enumeratePending(int limit) throws SQLException {
        final String sql =
            "SELECT id, fetched_at, title, body "
                + "  FROM post "
                + " WHERE status = 'RAW' "
                + "   AND tagger_done = TRUE "
                + "   AND summary_done = FALSE "
                + "   AND length(body) > ? "
                + "   AND fetched_at >= ? "
                + " ORDER BY fetched_at, id "
                + " LIMIT ?";
        List<PostRow> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, thresholdChars);
            ps.setTimestamp(2, Timestamp.from(partitionScan.scanWindowFloor(clock.instant())));
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = (UUID) rs.getObject(1);
                    Instant fetchedAt = rs.getTimestamp(2).toInstant();
                    String title = rs.getString(3);
                    String body = rs.getString(4);
                    rows.add(new PostRow(id, fetchedAt, title, body));
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
        // stray prompts/body-summary.md on a foreign classpath entry could
        // shadow the real prompt (the ClassifierWorker precedent).
        // The class's own loader is by construction the one carrying this
        // module's src/main/resources, the only trustworthy source.
        ClassLoader cl = BodySummaryWorker.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(
                    "BodySummaryWorker: prompt resource not on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                "BodySummaryWorker: failed to load prompt resource " + path, e);
        }
    }

    /** One pending post, populated by {@link #enumeratePending}. */
    public record PostRow(UUID id, Instant fetchedAt,
                          @Nullable String title, @Nullable String body) {
    }

    /**
     * Outcome of one reply parse: SUMMARY carries the (capped) abstract;
     * EMPTY is a valid empty summary (persist NULL, no notification);
     * REFUSAL is the structured-marker arm (persist NULL, notify under
     * {@link #ERROR_CLASS_SUMMARY_REFUSAL}).
     */
    record ParseOutcome(Kind kind, @Nullable String summary) {
        enum Kind { SUMMARY, EMPTY, REFUSAL }
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
