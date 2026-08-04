package app.zcat.infochat.provider.llm;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.llm.LlmOutputSanitizerCore;
import app.zcat.infochat.core.util.JsonEscaper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Sanitizer applied to LLM-authored output before it lands in an
 * outbound reply. Enforces two invariants from docs/spec/security.md
 * §LLM output sanitizer and docs/spec/commands.md §Surface conventions:
 *
 * <ol>
 *   <li><b>Plain-text only.</b> Markdown link syntax {@code [text](url)}
 *       is rewritten to {@code text (url)} so the rendered prose carries
 *       both the visible label and the bare URL.</li>
 *   <li><b>Closed-list strip.</b> Every privileged-tier command token
 *       from {@link #CLOSED_LIST} is replaced with the literal
 *       {@value #REDACTED_COMMAND_REPLACEMENT}. The match runs on the
 *       canonical form of the output, not its raw bytes.</li>
 * </ol>
 *
 * <p>The pure text transform itself lives in
 * {@link LlmOutputSanitizerCore} (infochat-core, M1-749) so the SAME
 * pipeline also sanitizes the Collector's ingest-translator output
 * before it is stored as {@code post.title_en}/{@code post.body_en} —
 * model output re-entering the corpus inherits every control the raw
 * body had. Every static surface here ({@link #CLOSED_LIST},
 * {@link #CLOSED_LIST_PATTERNS}, {@link #applyMarkdownLinkStrip},
 * {@link #applyClosedListStrip}, {@link #applyClosedListStripWithMatches},
 * {@link #canonicalizeForMatching}, {@link #aggregateMatchCounts},
 * {@link #breakLinkAdjacency}) is a behaviour-identical delegate kept so
 * the bean's API and its existing tests are unchanged; the transforms'
 * javadoc (the why of each pass) lives on the core declarations.
 *
 * <p>Every match is audit-logged by the CALLER, with emission AGGREGATED per
 * distinct token per {@code sanitize()} call — the docs/spec/security.md
 * counted-never-throttled commitment. On this (outbound) surface the
 * caller is this bean: N occurrences of the same
 * closed-list token in one call land ONE structured WARN line and ONE
 * {@code audit_log} row with action {@code LLM_OUTPUT_SANITIZED} whose
 * {@code details_json.match_count} is exactly N; distinct tokens still
 * get one row each. The audit row's {@code details_json} carries the
 * matched token under {@code match_kind} (the closed-list entry, e.g.
 * {@code /ban}); the user-visible LLM output text is never copied into
 * the row. (The ingest-translation surface emits the same rows from its
 * own caller, {@code IngestTranslationWorker} — a surface that takes
 * the strip takes the audit.)
 *
 * <p>{@link #CLOSED_LIST} (declared in {@link LlmOutputSanitizerCore})
 * is hand-maintained in code to mirror
 * docs/spec/commands.md §Permission model §Closed list of
 * privileged-tier commands. The CI completeness {@code @Test}
 * {@code LlmOutputSanitizerTest.matchSetEqualsSpecClosedList} reads the
 * spec markdown at TEST tier and asserts equality; a spec-side
 * addition without a corresponding CLOSED_LIST update fails CI, and a
 * CLOSED_LIST entry that no longer corresponds to a listed command
 * also fails CI.
 */
@ApplicationScoped
public class LlmOutputSanitizer {

    private static final Logger LOG = Logger.getLogger(LlmOutputSanitizer.class);

    // The audit-log target_id for sanitizer rows. Sanitizer hits are not
    // tied to a user/post/group entity, so the target_id is a fixed
    // marker; the audit-row identity is the (action, created_at, details
    // _json) triple plus the row's BIGSERIAL id.
    private static final String AUDIT_TARGET_ID = "sanitizer-output";

    private final AuditLogWriter auditLogWriter;
    private final DataSource dataSource;

    /**
     * Sole constructor. Both audit collaborators are non-null and final, so
     * {@link #sanitize(String)} ALWAYS emits the aggregated
     * {@code LLM_OUTPUT_SANITIZED} rows the spec commits to — there is no
     * constructor that can build an audit-bypassing instance, so the
     * durability commitment is structural rather than discipline-enforced.
     */
    @Inject
    public LlmOutputSanitizer(AuditLogWriter auditLogWriter, DataSource dataSource) {
        this.auditLogWriter = auditLogWriter;
        this.dataSource = dataSource;
    }

    /** Literal that replaces every {@link #CLOSED_LIST} match in the output. */
    public static final String REDACTED_COMMAND_REPLACEMENT =
            LlmOutputSanitizerCore.REDACTED_COMMAND_REPLACEMENT;

    /**
     * The closed set of privileged-tier command tokens that must be
     * stripped from LLM output — the single declaration is
     * {@link LlmOutputSanitizerCore#CLOSED_LIST}; this alias keeps the
     * bean's API (and the spec-parity CI test that reads it) unchanged
     * while the list lives in exactly one place.
     */
    static final List<String> CLOSED_LIST = LlmOutputSanitizerCore.CLOSED_LIST;

    /**
     * Precompiled match patterns for {@link #CLOSED_LIST}, index-aligned —
     * the single declaration is {@link LlmOutputSanitizerCore#CLOSED_LIST_PATTERNS}.
     */
    static final List<Pattern> CLOSED_LIST_PATTERNS = LlmOutputSanitizerCore.CLOSED_LIST_PATTERNS;

    /**
     * Markdown-link strip pass — delegate of
     * {@link LlmOutputSanitizerCore#applyMarkdownLinkStrip(String)}; see
     * there for the why. Runs FIRST so a hostile {@code [Click](/grant-admin)}
     * cannot hide an admin command inside link syntax.
     */
    static String applyMarkdownLinkStrip(String input) {
        return LlmOutputSanitizerCore.applyMarkdownLinkStrip(input);
    }

    /**
     * The `](` adjacency break — delegate of
     * {@link LlmOutputSanitizerCore#breakLinkAdjacency(String)}; see
     * there for the why. {@code OutboundDelivery} calls this on every
     * outbound body: the guarantee is a property of the DELIVERED
     * MESSAGE, not of any one sanitized field (M1-691).
     */
    public static String breakLinkAdjacency(String text) {
        return LlmOutputSanitizerCore.breakLinkAdjacency(text);
    }

    /**
     * Closed-list strip pass — the rewrite half of
     * {@link #applyClosedListStripWithMatches(String)}. Used by tests
     * that want the rewrite without driving the audit emission; the
     * production path is {@link #sanitize(String)}.
     */
    static String applyClosedListStrip(String input) {
        return applyClosedListStripWithMatches(input).rewritten();
    }

    /**
     * Carrier for the closed-list pass: the rewritten text plus the
     * list of matched tokens (one entry per occurrence, in input
     * order). {@link #sanitize(String)} aggregates {@link #matches()}
     * per distinct token and writes one {@code audit_log} row per token
     * carrying the exact occurrence count — the spec's
     * counted-never-throttled promise.
     */
    record ClosedListStripResult(String rewritten, List<String> matches) {}

    /**
     * Closed-list strip pass that ALSO records the matched tokens for
     * downstream aggregated audit emission. The transform itself is
     * {@link LlmOutputSanitizerCore#applyClosedListStripWithMatches(String)};
     * this wrapper emits the WARN log lines, one per distinct matched
     * token carrying the exact occurrence count (so the WARN-vs-row
     * count stays 1:1).
     */
    static ClosedListStripResult applyClosedListStripWithMatches(String input) {
        LlmOutputSanitizerCore.ClosedListStripResult core =
                LlmOutputSanitizerCore.applyClosedListStripWithMatches(input);
        // One WARN per distinct token per call, carrying the exact
        // occurrence count — counted, never throttled. N occurrences of
        // one token cost one log line.
        for (Map.Entry<String, Integer> aggregated : aggregateMatchCounts(core.matches()).entrySet()) {
            LOG.warnf("LLM_OUTPUT_SANITIZED token=%s count=%d",
                    aggregated.getKey(), aggregated.getValue());
        }
        return new ClosedListStripResult(core.rewritten(), core.matches());
    }

    /**
     * The representation the closed-list pass matches against — delegate
     * of {@link LlmOutputSanitizerCore#canonicalizeForMatching(String)};
     * see there for why the match must see the same representation the
     * command parser consumes.
     */
    static String canonicalizeForMatching(String input) {
        return LlmOutputSanitizerCore.canonicalizeForMatching(input);
    }

    /**
     * Aggregate the per-occurrence match list into per-token counts —
     * delegate of {@link LlmOutputSanitizerCore#aggregateMatchCounts(List)}.
     */
    static LinkedHashMap<String, Integer> aggregateMatchCounts(List<String> matches) {
        return LlmOutputSanitizerCore.aggregateMatchCounts(matches);
    }

    /**
     * Run both passes in order. The output is plain text, with
     * privileged commands replaced by {@value #REDACTED_COMMAND_REPLACEMENT}
     * and markdown links flattened to {@code text (url)}. Every
     * closed-list match is audit-logged; the {@code audit_log} emission
     * aggregates per distinct token per call (one row carrying the
     * exact occurrence count) with action {@code LLM_OUTPUT_SANITIZED}.
     *
     * @throws IllegalStateException if the audit-row INSERT fails
     *         (DB outage, lock contention, role grant revoked, etc.).
     *         The spec's per-occurrence durability commitment requires
     *         the caller to NOT emit the sanitized reply when the
     *         audit trail cannot be written.
     */
    public String sanitize(String llmOutput) {
        if (llmOutput == null || llmOutput.isEmpty()) {
            return "";
        }
        String afterMarkdown = applyMarkdownLinkStrip(llmOutput);
        ClosedListStripResult result = applyClosedListStripWithMatches(afterMarkdown);
        emitAuditRows(result.matches());
        return result.rewritten();
    }

    /**
     * Write one {@code audit_log} row per distinct matched token via
     * {@link AuditLogWriter}, all in a single transaction, the row's
     * {@code details_json.match_count} carrying the exact occurrence
     * count. The spec §LLM output sanitizer commits to durability —
     * "Every match is audit-logged" — so a partial
     * write must NOT leave the caller free to send the sanitized
     * reply. Either every row commits or none do and the method
     * throws; the caller's response build aborts.
     *
     * @throws IllegalStateException if any audit-row INSERT fails;
     *         the underlying {@link SQLException} is the cause.
     */
    private void emitAuditRows(List<String> matches) {
        if (matches.isEmpty()) {
            return;
        }
        // Park an already-armed interrupt across the write, restore it after
        // (M1-763). DigestWorker cancels a digest render that overruns its slot
        // window by interrupting the render's VIRTUAL thread, and that render
        // goes on to sanitize prose it generated before the interrupt landed.
        // On a virtual thread an armed interrupt flag makes JDBC socket I/O
        // fail with "Closed by interrupt" (a platform thread is unaffected,
        // which is why this is invisible to any test that does not use a
        // virtual thread) — so without this park the row below is never
        // written and the durability commitment documented above silently
        // becomes best-effort under cancellation.
        //
        // Restoring the flag is the other half of the contract: clearing it
        // permanently would let the cancelled render resume full-speed LLM
        // calls, undoing the spend ceiling the cancellation exists to impose.
        // An interrupt arriving DURING the write is still a genuine failure
        // and stays on the fail-loud path below; only a pre-armed flag, which
        // says nothing about this connection's health, is parked.
        boolean callerWasInterrupted = Thread.interrupted();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            for (Map.Entry<String, Integer> aggregated : aggregateMatchCounts(matches).entrySet()) {
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
            // Spec §LLM output sanitizer is a durability commitment:
            // "Every match is audit-logged." A partial write or a
            // failed INSERT means the user-visible reply must NOT be
            // emitted with the closed-list tokens stripped — the audit
            // trail is the load-bearing operator signal for those
            // events.
            // try-with-resources closes the connection; PgConnection
            // rolls back an active transaction on close, so the
            // partial-write case leaves audit_log unchanged.
            throw new IllegalStateException(
                    "LlmOutputSanitizer: failed to durably audit-log sanitizer hits", e);
        } finally {
            if (callerWasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

}
