package app.zcat.infochat.collector.eval.classifier;

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
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Collector-side scheduled poller that runs the classification step of
 * the eval pipeline. Sits AFTER the Tagger and runs in PARALLEL with the
 * {@link app.zcat.infochat.collector.eval.entity.EntityExtractorWorker}
 * and the {@code EmbeddingWorker} — all three gate on
 * {@code tagger_done=TRUE} and none gates the others. The classification
 * label set is a FIXED closed enum shown per-post in {@code /summary}
 * (the render side lands in M1-598).
 *
 * <h2>Why ingest-time, not summarizer-authored (D19/D36)</h2>
 *
 * <p>Classification is an evaluation, computed once at ingest and stored
 * on the post, NOT a query-time summarizer output. Reading it from the
 * DB at {@code /summary} time keeps the reply byte-identical on
 * {@code /retry} replay (docs/spec/llm.md §Determinism boundary).
 *
 * <h2>Pickup criteria</h2>
 *
 * <p>{@code status='RAW' AND tagger_done=TRUE AND classifier_done=FALSE}.
 * The {@code status='RAW'} filter mechanically excludes quarantined
 * posts. The pickup deliberately does NOT reference {@code entity_done}
 * or {@code embedding_done}: the three post-tagger stages are
 * independent and must not gate each other. {@code ReadyPromoter} is the
 * sole synchronization point that waits for all of them (M1-597 added
 * {@code classifier_done} to that gate — load-bearing: if a post
 * promoted RAW→READY before classification, this worker's own
 * {@code status='RAW'} pickup would exclude it forever and it would stay
 * {@code {unknown}}).
 *
 * <h2>Closed enum + {@code unknown} semantics</h2>
 *
 * <p>{@link #SUBSTANTIVE_LABELS} are the five substantive labels; a post
 * gets 1..{@link #MAX_LABELS_PER_POST} of them, OR the single fallback
 * {@link #UNKNOWN}. {@code unknown} is NEVER combined with a substantive
 * label: the parse keeps only substantive labels, and an empty
 * substantive set resolves to {@code [unknown]}. Out-of-enum labels are
 * dropped in Java BEFORE the write, so one bad label never trips the V57
 * closed-set CHECK (mirrors {@code post_entity.entity_type}). The result
 * is always non-empty and CHECK-valid, matching the {@code NOT NULL
 * DEFAULT ARRAY['unknown']} column.
 *
 * <h2>Failure policy</h2>
 *
 * <p>One retry on a failed attempt, then release as {@code {unknown}}
 * (mirrors the EntityExtractorWorker's "release without entities"). A
 * failed attempt is either an exception from {@link LlmProvider#generate}
 * (UNREACHABLE — sleep the configured {@link RetryBackoff} before the
 * single retry) or a reply that does not parse as the classification
 * object (SCHEMA_VIOLATING — retry immediately). On the second
 * consecutive failure the post advances {@code classifier_done=TRUE}
 * with {@code classification={unknown}} and a throttled admin
 * notification fires. An empty-after-filter parse is NOT a failure — it
 * is the model choosing {@code unknown} and resolves to {@code {unknown}}
 * with no notification.
 *
 * <h2>Persistence cursor</h2>
 *
 * <p>Per Invariant 5 the classification + {@code classifier_done} write
 * is a single atomic UPDATE keyed {@code WHERE id=? AND fetched_at=?}
 * (the partitioned-PK shape). Unlike the entity extractor there is no
 * separate table, so no multi-statement transaction is needed: a crash
 * before the UPDATE leaves {@code classifier_done=FALSE} and the next
 * tick re-picks the post.
 *
 * <h2>Prompt injection</h2>
 *
 * <p>The classifier runs untrusted post content through an LLM. The
 * prompt is a classpath resource ({@link #PROMPT_RESOURCE}) wrapping the
 * post in a per-call rotating {@code {{id}}} delimiter
 * (docs/design/04-security.md §4.3), loaded via the class's own loader
 * (never the TCCL) so a stray {@code prompts/classifier.md} on a foreign
 * classpath entry cannot shadow the real prompt.
 *
 * <h2>Bounded concurrency</h2>
 *
 * <p>Each tick enumerates at most
 * {@code infochat.llm.classifier.max-concurrency} posts and processes
 * them in a serial loop, so at most one classification LLM call is ever
 * in flight (docs/spec/llm.md §Bounded concurrency).
 */
@ApplicationScoped
public class ClassifierWorker {

    /** Classpath resource path for the classifier prompt. */
    public static final String PROMPT_RESOURCE = "prompts/classifier.md";

    /** Canonical error class emitted on the release-as-{unknown} path. */
    public static final String ERROR_CLASS_CLASSIFICATION_FAILURE = "classifier.classification_failure";

    /** The single fallback label; first-class value in the V57 closed set. */
    public static final String UNKNOWN = "unknown";

    /**
     * The five substantive labels — the V57 closed set minus
     * {@link #UNKNOWN}. The worker keeps only these from a reply;
     * anything else (out-of-enum, or the literal {@code unknown}) is not
     * added to the substantive set, and an empty substantive set
     * resolves to {@code [unknown]}.
     */
    static final Set<String> SUBSTANTIVE_LABELS =
        Set.of("factual", "opinion", "technical", "urgent", "ongoing");

    /**
     * Cap on substantive labels accepted from one reply — the design
     * cardinality (1..3) carried over from the retired §5.4.4 summarizer
     * prompt. A structural bound on the LLM trust boundary: a misbehaving
     * or prompt-injected model cannot inflate {@code post.classification}
     * past three substantive labels.
     */
    static final int MAX_LABELS_PER_POST = 3;

    /** The single-element {@code {unknown}} result, reused for every fallback. */
    private static final ClassificationResult UNKNOWN_RESULT =
        new ClassificationResult(List.of(UNKNOWN));

    private static final Logger LOG = LoggerFactory.getLogger(ClassifierWorker.class);

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

    @ConfigProperty(name = "infochat.llm.classifier.max-concurrency")
    int maxConcurrency;

    @SuppressWarnings("NullAway.Init")
    private String promptTemplate;
    @SuppressWarnings("NullAway.Init")
    private ObjectMapper objectMapper;

    @PostConstruct
    void init() {
        if (maxConcurrency < 1) {
            throw new IllegalStateException(
                "ClassifierWorker: infochat.llm.classifier.max-concurrency must be >= 1; got " + maxConcurrency);
        }
        this.promptTemplate = loadResource(PROMPT_RESOURCE);
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Scheduled tick. Enumerates pending posts and classifies each. A
     * processing error on one post does not abort the tick — the post
     * stays {@code classifier_done=FALSE} and the next tick re-picks it.
     */
    @Scheduled(every = "{infochat.llm.classifier.poll-interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void onTick() {
        List<PostRow> pending;
        try {
            pending = enumeratePending(maxConcurrency);
        } catch (SQLException e) {
            // SafeLog, never the raw Throwable (docs/spec/security.md
            // §Secrets handling — User content in exceptions).
            SafeLog.warn(LOG, "ClassifierWorker: failed to enumerate pending posts; skipping tick", e);
            return;
        }
        for (PostRow row : pending) {
            try {
                processOne(row);
            } catch (RuntimeException e) {
                SafeLog.warn(LOG, "ClassifierWorker: processing failed for post_id="
                    + row.id() + "; will retry next tick", e);
            }
        }
    }

    /**
     * Process one post: run the classification (one retry on a failed
     * attempt), then persist the labels + advance the cursor, or release
     * as {@code {unknown}} + notify. Package-private so the IT can invoke
     * directly without waiting on the scheduler clock.
     */
    void processOne(PostRow row) {
        LlmProvider provider = llmRouter.forTask(ModelTask.CLASSIFIER, "en");

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

        ClassificationResult result = chosen.result();
        if (result != null) {
            // PARSED — 1..3 substantive labels, or [unknown] when the
            // model chose unknown / nothing substantive survived the
            // filter (empty-after-filter is a valid outcome, not a
            // failure — no notification).
            persistClassification(row, result.labels());
        } else {
            releaseAsUnknown(row, first.kind(), chosen.kind());
        }
    }

    /**
     * One classification attempt: assemble the prompt, call the provider,
     * parse the reply. Returns a {@link AttemptKind#PARSED} result or a
     * failure classification driving the single retry.
     */
    private AttemptResult tryOnce(LlmProvider provider, PostRow row, int attempt) {
        String delimiterId = UUID.randomUUID().toString();
        String userPrompt = renderPrompt(delimiterId, row);

        LlmResponse response;
        try {
            response = provider.generate(ModelTask.CLASSIFIER, "", userPrompt);
        } catch (RuntimeException e) {
            // SafeLog, never the raw Throwable: the provider exception
            // can echo its request context, which embeds the post body
            // woven into the prompt (docs/spec/security.md §Secrets
            // handling — User content in exceptions).
            SafeLog.warn(LOG, "ClassifierWorker: LLM call attempt " + attempt
                + " failed for post_id=" + row.id()
                + " (error_class=" + ERROR_CLASS_CLASSIFICATION_FAILURE + ")", e);
            return AttemptResult.unreachable();
        }

        ClassificationResult parsed = parseClassification(response.text());
        if (parsed == null) {
            LOG.warn(
                "ClassifierWorker: schema-violating response on attempt {} for post_id={}",
                attempt, row.id());
            return AttemptResult.schemaViolating();
        }
        LOG.info(
            "ClassifierWorker: post_id={} attempt={} classification={}",
            row.id(), attempt, parsed.labels());
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
     * Parse the model reply as a JSON object {@code {"classification":
     * [...]}}. Normalizes each label, drops out-of-enum labels, caps the
     * substantive set at {@link #MAX_LABELS_PER_POST}, and applies the
     * {@code unknown}-mutual-exclusion rule. Returns a non-empty result
     * (1..3 substantive labels, or {@code [unknown]} when no substantive
     * label survived), or {@code null} when the reply is schema-violating
     * (not parseable as the expected object shape).
     */
    @Nullable ClassificationResult parseClassification(@Nullable String text) {
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
        JsonNode labels = root.get("classification");
        if (labels == null || !labels.isArray()) {
            return null;
        }
        // Keep only substantive labels, de-duped, in emission order, capped.
        // The literal "unknown" and any out-of-enum label are NOT added: an
        // empty substantive set resolves to [unknown] below, so unknown is
        // never combined with a substantive label.
        Set<String> substantive = new LinkedHashSet<>();
        for (JsonNode node : labels) {
            if (!node.isTextual()) {
                continue;
            }
            String label = node.asText().strip().toLowerCase(Locale.ROOT);
            if (!SUBSTANTIVE_LABELS.contains(label)) {
                continue;
            }
            if (substantive.size() < MAX_LABELS_PER_POST) {
                substantive.add(label);
            }
        }
        if (substantive.isEmpty()) {
            return UNKNOWN_RESULT;
        }
        return new ClassificationResult(List.copyOf(substantive));
    }

    /**
     * Release path: advance {@code classifier_done=TRUE} with
     * {@code classification={unknown}} after two failed attempts
     * (schema-violating and/or unreachable) and fire the throttled admin
     * notification. The post still reaches READY.
     */
    private void releaseAsUnknown(PostRow row, AttemptKind first, AttemptKind second) {
        LOG.warn(
            "ClassifierWorker: releasing post_id={} as {unknown} after two failed attempts "
                + "(error_class={} first={} second={})",
            row.id(), ERROR_CLASS_CLASSIFICATION_FAILURE, first, second);
        persistClassification(row, UNKNOWN_RESULT.labels());
        throttledAdminNotifier.notifyOnce(
            ERROR_CLASS_CLASSIFICATION_FAILURE,
            ERROR_CLASS_CLASSIFICATION_FAILURE,
            "Classification released as {unknown} for post_id=" + row.id()
                + " (first=" + first + " second=" + second + ")");
    }

    /**
     * Atomic write of {@code classification} + the {@code classifier_done}
     * cursor. Per Invariant 5 this single statement is the durable cursor
     * for the classifier boundary; the (id, fetched_at) WHERE clause
     * matches the partitioned-PK shape so the UPDATE plans on the right
     * partition.
     */
    private void persistClassification(PostRow row, List<String> labels) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE post SET classification = ?, classifier_done = TRUE "
                     + "WHERE id = ? AND fetched_at = ?")) {
            Array labelsArray = conn.createArrayOf("text", labels.toArray(new String[0]));
            ps.setArray(1, labelsArray);
            ps.setObject(2, row.id());
            ps.setTimestamp(3, Timestamp.from(row.fetchedAt()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(
                "ClassifierWorker: cursor UPDATE failed for post_id=" + row.id(), e);
        }
    }

    /**
     * Enumerate the next batch of posts awaiting classification. The
     * pickup filter excludes quarantined posts ({@code status='RAW'}) and
     * already-processed posts ({@code classifier_done=FALSE}); it does
     * NOT reference {@code entity_done}/{@code embedding_done}
     * (parallel-stage independence). The {@code fetched_at} floor
     * ({@link PartitionScan#scanWindowFloor(Instant)}, sampled from the
     * injected Clock) lets the planner prune partitions of the
     * RANGE(fetched_at) post table. The ORDER BY makes the pickup
     * deterministic against test fixtures.
     */
    List<PostRow> enumeratePending(int limit) throws SQLException {
        final String sql =
            "SELECT id, fetched_at, title, body "
                + "  FROM post "
                + " WHERE status = 'RAW' "
                + "   AND tagger_done = TRUE "
                + "   AND classifier_done = FALSE "
                + "   AND fetched_at >= ? "
                + " ORDER BY fetched_at, id "
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
        // stray prompts/classifier.md on a foreign classpath entry could
        // shadow the real prompt (the TaggerWorker/Stage2Worker precedent).
        // The class's own loader is by construction the one carrying this
        // module's src/main/resources, the only trustworthy source.
        ClassLoader cl = ClassifierWorker.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(
                    "ClassifierWorker: prompt resource not on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                "ClassifierWorker: failed to load prompt resource " + path, e);
        }
    }

    /** One pending post, populated by {@link #enumeratePending}. */
    public record PostRow(UUID id, Instant fetchedAt,
                          @Nullable String title, @Nullable String body) {
    }

    /** Per-attempt result classification driving the single retry. */
    private record AttemptResult(AttemptKind kind, @Nullable ClassificationResult result) {
        static AttemptResult parsed(ClassificationResult result) {
            return new AttemptResult(AttemptKind.PARSED, result);
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
