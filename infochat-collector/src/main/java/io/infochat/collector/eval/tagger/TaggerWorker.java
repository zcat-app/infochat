package io.infochat.collector.eval.tagger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.infochat.llm.LlmProvider;
import io.infochat.llm.LlmResponse;
import io.infochat.llm.ModelTask;
import io.infochat.llm.routing.LlmRouter;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

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
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
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
 *   <li><b>Zero-valid</b> — JSON parsed but ZERO tags passed the
 *       vocabulary check after partial-valid filtering. Retry once
 *       with the SAME primary prompt — vocabulary mismatch is a
 *       content issue, not a prompt-shape issue, so the same prompt
 *       may produce a different (and valid) tag set.</li>
 *   <li><b>LLM unreachable</b> — {@code provider.generate} threw or
 *       timed out. Retry once with the SAME primary prompt —
 *       unreachability is transient infrastructure, unrelated to
 *       prompt shape.</li>
 * </ol>
 *
 * <p>On second failure of any path:
 * {@code post.tags = source.bootstrap_tags},
 * {@code post.tagger_fallback = true}, log WARN with canonical
 * {@code error_class='tagger.fallback_to_bootstrap'} (consumed by
 * the future T2-G throttled admin notifier).
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
 * <p>The {@link Semaphore} bounds in-flight tagger LLM calls per
 * {@code docs/spec/llm.md} §Bounded concurrency and observability.
 * The permit count is read from
 * {@code infochat.llm.tagger.max-concurrency} (laptop default 4
 * per {@code docs/design/05-llm-and-embeddings.md} §5.7) — the
 * same shape M1-033's Stage2Worker uses.
 *
 * <h2>Idempotency</h2>
 *
 * <p>The pickup query and the cursor UPDATE are idempotent — a
 * crash between the LLM call and the UPDATE leaves
 * {@code tagger_done=false} so the next tick re-processes the
 * post. The model is stateless across calls (Tagger has no memory
 * per {@code docs/spec/llm.md} §Determinism boundary) so re-issuing
 * the same prompt is safe.
 */
@ApplicationScoped
public class TaggerWorker {

    /** Classpath resource path for the JSON-primary tagger prompt. */
    public static final String PRIMARY_PROMPT_RESOURCE = "prompts/tagger.md";

    /** Classpath resource path for the line-oriented fallback prompt. */
    public static final String FALLBACK_PROMPT_RESOURCE = "prompts/tagger-fallback.md";

    /** Canonical error class emitted by the bootstrap-fallback path. */
    public static final String ERROR_CLASS_TAGGER_FALLBACK = "tagger.fallback_to_bootstrap";

    private static final Logger LOG = Logger.getLogger(TaggerWorker.class);

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

    @ConfigProperty(name = "infochat.llm.tagger.max-concurrency")
    int maxConcurrency;

    private Semaphore concurrencyPermits;
    private String primaryPromptTemplate;
    private String fallbackPromptTemplate;
    private ObjectMapper objectMapper;

    @PostConstruct
    void init() {
        if (maxConcurrency < 1) {
            throw new IllegalStateException(
                "TaggerWorker: infochat.llm.tagger.max-concurrency must be >= 1; got " + maxConcurrency);
        }
        this.concurrencyPermits = new Semaphore(maxConcurrency);
        this.primaryPromptTemplate = loadResource(PRIMARY_PROMPT_RESOURCE);
        this.fallbackPromptTemplate = loadResource(FALLBACK_PROMPT_RESOURCE);
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Scheduled tick. Picks up posts whose Stage 1 (and Stage 2 when
     * flagged) have completed, runs the tagger on each, and writes
     * the per-stage cursor advance.
     */
    @Scheduled(every = "{infochat.llm.tagger.poll-interval}")
    public void onTick() {
        List<PostRow> pending;
        try {
            pending = enumeratePending(maxConcurrency);
        } catch (SQLException e) {
            LOG.warn("TaggerWorker: failed to enumerate pending posts; skipping tick", e);
            return;
        }
        for (PostRow row : pending) {
            try {
                processOne(row);
            } catch (RuntimeException e) {
                // A processing error on one post must not abort the
                // tick. Log and keep going so the rest of the batch
                // gets its chance.
                LOG.warnf(e,
                    "TaggerWorker: processing failed for post_id=%s; will retry next tick",
                    row.id());
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
        concurrencyPermits.acquireUninterruptibly();
        try {
            TaggerOutcome outcome = invokeWithFallbackChain(row);
            persistCursor(row, outcome);
        } finally {
            concurrencyPermits.release();
        }
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

        if (first.kind() == AttemptKind.SUCCESS) {
            return new TaggerOutcome(Outcome.LLM, first.validTags());
        }

        // Decide the retry shape from the first-attempt failure mode.
        AttemptResult second;
        switch (first.kind()) {
            case SCHEMA_VIOLATING -> {
                // Different prompt — line-oriented fallback.
                second = tryOnce(provider, row, fallbackPromptTemplate, /* attempt */ 2);
            }
            case ZERO_VALID, UNREACHABLE -> {
                // Same prompt — content/infrastructure issue, not
                // prompt-shape.
                second = tryOnce(provider, row, primaryPromptTemplate, /* attempt */ 2);
            }
            default -> {
                // SUCCESS handled above; the compiler exhaustiveness
                // forces this branch to exist but it is not reachable
                // on a non-SUCCESS first attempt.
                second = first;
            }
        }

        if (second.kind() == AttemptKind.SUCCESS) {
            return new TaggerOutcome(Outcome.LLM, second.validTags());
        }

        // Second failure on any path → bootstrap-fallback audit log.
        LOG.warnf(
            "TaggerWorker: tagger fallback to bootstrap for post_id=%s "
                + "(first_kind=%s second_kind=%s error_class=%s)",
            row.id(), first.kind(), second.kind(), ERROR_CLASS_TAGGER_FALLBACK);
        return new TaggerOutcome(Outcome.BOOTSTRAP, row.bootstrapTags());
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
            LOG.warnf(e,
                "TaggerWorker: LLM call attempt %d failed for post_id=%s (error_class=tagger.unreachable)",
                attempt, row.id());
            return AttemptResult.unreachable();
        }

        String text = response == null ? null : response.text();
        List<String> parsed = parseTags(text);
        if (parsed == null) {
            return AttemptResult.schemaViolating();
        }

        ValidationResult validated = validate(parsed);
        // Partial-valid log: emit the counts on every attempt where
        // we got past parsing so observability has the data even
        // when zero passed (the zero-valid path is the most useful
        // signal for vocabulary drift).
        LOG.infof(
            "TaggerWorker: post_id=%s attempt=%d tagger_partial_valid valid tags=%d invalid=%d",
            row.id(), attempt, validated.valid().size(), validated.invalidCount());

        if (validated.valid().isEmpty()) {
            return AttemptResult.zeroValid();
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
     * or {@code null} when the reply is schema-violating.
     */
    List<String> parseTags(String text) {
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
            for (String token : payload.split(",")) {
                String t = token.trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
            return out;
        }
        // JSON shape.
        try {
            JsonNode root = objectMapper.readTree(trimmed);
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
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Normalize every parsed tag and partition into valid / invalid
     * by vocabulary membership. Duplicates after normalization are
     * dropped (the {@link LinkedHashSet} preserves first-emit order).
     */
    private ValidationResult validate(List<String> parsed) {
        Set<String> valid = new LinkedHashSet<>();
        int invalid = 0;
        for (String raw : parsed) {
            String normalized = normalizeTag(raw);
            if (normalized != null && tagVocabulary.contains(normalized)) {
                valid.add(normalized);
            } else {
                invalid++;
            }
        }
        return new ValidationResult(List.copyOf(valid), invalid);
    }

    /**
     * Apply the tag normalization rule (NFC + {@code Locale.ROOT}
     * lower-case + character class {@code ^[a-z0-9][a-z0-9-]{0,47}$})
     * and return the normalized form, or {@code null} when the input
     * fails the character-class filter. Same rule
     * {@link TagVocabulary#normalize} applies to the loaded vocabulary
     * so {@link TagVocabulary#contains} is byte-equal.
     */
    // TODO(T1-D): move to TagNormalizer helper alongside
    // BootstrapLoader.normalizeTag and TagVocabulary.normalize.
    static String normalizeTag(String raw) {
        if (raw == null) {
            return null;
        }
        String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
        String lower = nfc.toLowerCase(Locale.ROOT);
        return TagVocabulary.TAG_NAME_PATTERN.matcher(lower).matches() ? lower : null;
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
     * ({@code tagger_done=false}). The ORDER BY makes the pickup
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
                + " ORDER BY p.fetched_at, p.id "
                + " LIMIT ?";
        List<PostRow> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
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

    private static String loadResource(String path) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = TaggerWorker.class.getClassLoader();
        }
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

    /** One pending post + the join-cached source.bootstrap_tags. */
    public record PostRow(UUID id, Instant fetchedAt, String title, String body,
                          List<String> bootstrapTags) {
    }

    /**
     * Result of the three-surface fallback chain. {@link Outcome#LLM}
     * means the LLM produced a usable tag set; {@link Outcome#BOOTSTRAP}
     * means both attempts failed and the source's bootstrap tags are
     * the audit-fallback set.
     */
    private record TaggerOutcome(Outcome outcome, List<String> tags) {
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
        static AttemptResult unreachable() {
            return new AttemptResult(AttemptKind.UNREACHABLE, List.of());
        }
    }

    private enum AttemptKind { SUCCESS, SCHEMA_VIOLATING, ZERO_VALID, UNREACHABLE }

    private record ValidationResult(List<String> valid, int invalidCount) {
    }
}
