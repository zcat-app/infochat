package app.zcat.infochat.provider.llm;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.util.JsonEscaper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Sanitizer applied to LLM-authored output before it lands in an
 * outbound reply. Enforces two invariants from docs/spec/security.md
 * §LLM output sanitizer and docs/spec/commands.md §Surface conventions:
 *
 * <ol>
 *   <li><b>Plain-text only.</b> Markdown link syntax {@code [text](url)}
 *       is rewritten to {@code text (url)} so the rendered prose carries
 *       both the visible label and the bare URL. Runs FIRST so a
 *       hostile {@code [Click for admin](/grant-admin)} flattens to
 *       {@code Click for admin (/grant-admin)} BEFORE the closed-list
 *       pass sees it.</li>
 *   <li><b>Closed-list strip.</b> Every privileged-tier command token
 *       from {@link #CLOSED_LIST} is replaced with the literal
 *       {@value #REDACTED_COMMAND_REPLACEMENT}. Replacement is
 *       uniform: every CLOSED_LIST entry is treated identically; the
 *       reader gets the surrounding prose minus the matched string,
 *       not an empty/failed reply.</li>
 * </ol>
 *
 * <p>Every match emits one structured log line at level WARN AND
 * one {@code audit_log} row with action {@code LLM_OUTPUT_SANITIZED} —
 * the docs/spec/security.md per-occurrence (not throttled) commitment.
 * Two hits in one {@code sanitize()} call land two audit rows, not
 * one coalesced row. The audit row's {@code details_json} carries the
 * matched token under {@code match_kind} (the closed-list entry, e.g.
 * {@code /ban}) plus {@code match_count = 1} per row; the user-visible
 * LLM output text is never copied into the row.
 *
 * <p>{@link #CLOSED_LIST} is hand-maintained in code to mirror
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
     * {@link #sanitize(String)} ALWAYS emits the per-occurrence
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
    public static final String REDACTED_COMMAND_REPLACEMENT = "[redacted command]";

    /**
     * The closed set of privileged-tier command tokens that must be
     * stripped from LLM output. Mirrors docs/spec/commands.md §Closed
     * list of privileged-tier commands verbatim. Order is the spec's
     * order; the {@link #applyClosedListStrip(String)} pass iterates
     * the list and replaces every occurrence per entry — a single
     * matcher pass over the union via alternation would be
     * indistinguishable from this loop semantically, but a per-entry
     * loop keeps the per-entry observability promise (one log line
     * per matched token).
     */
    static final List<String> CLOSED_LIST = List.of(
            // Bot-admin only:
            "/grant-admin",
            "/revoke-admin",
            "/ban",
            "/unban",
            "/promote",
            "/demote",
            "/vouch",
            "/invite create",
            "/invite list",
            "/invite revoke",
            "/invite bot-contact",
            "/quarantine list",
            "/quarantine approve",
            "/quarantine reject",
            "/audit",
            "/pending",
            "/remove-source",
            "/source-enable",
            "/source-disable",
            "/list-sources --all",
            "/list-sources --include-deleted",
            "/approve-group",
            "/reject-group",
            "/list-groups",
            "/recover-pool",
            // Group-admin (or bot admin acting in the group):
            "/add-source",
            "/unfollow-source",
            "/follow-all-sources",
            "/lang",
            "/group-timezone",
            "/digest",
            "/follow-tag",
            "/unfollow-tag"
    );

    /**
     * Precompiled match patterns for {@link #CLOSED_LIST}, index-aligned
     * (pattern {@code i} matches entry {@code i}). Compiled once at class
     * load so the closed-list pass does not re-compile every entry's
     * pattern on each {@link #sanitize(String)} call — the markdown pass's
     * {@link #MARKDOWN_LINK} is already static. Each word is quoted
     * literally; in multi-word entries the separating space matches as
     * {@code \s+} so extra internal whitespace ({@code /invite  create})
     * cannot evade the strip. The trailing lookahead is the word-boundary
     * contract: a match must end the string or be followed by a
     * non-token character.
     */
    static final List<Pattern> CLOSED_LIST_PATTERNS = CLOSED_LIST.stream()
            .map(LlmOutputSanitizer::compileClosedListPattern)
            .toList();

    private static Pattern compileClosedListPattern(String token) {
        String quotedWords = Arrays.stream(token.split(" "))
                .map(Pattern::quote)
                .collect(Collectors.joining("\\s+"));
        return Pattern.compile(quotedWords + "(?=$|[^a-zA-Z0-9\\-])");
    }

    /** {@code [text](url)} → {@code text (url)} per acceptance item 14. */
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");

    /**
     * Run both passes in order. The output is plain text, with
     * privileged commands replaced by {@value #REDACTED_COMMAND_REPLACEMENT}
     * and markdown links flattened to {@code text (url)}. Every
     * closed-list match emits one {@code audit_log} row with action
     * {@code LLM_OUTPUT_SANITIZED}.
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
     * Markdown-link strip pass. The regex preserves both the link text
     * AND the bare URL ({@code $1 ($2)}) so the user-visible content is
     * not lost. Runs FIRST so a hostile {@code [Click](/grant-admin)}
     * cannot hide an admin command inside link syntax.
     */
    static String applyMarkdownLinkStrip(String input) {
        Matcher m = MARKDOWN_LINK.matcher(input);
        return m.replaceAll(matchResult -> Matcher.quoteReplacement(
                matchResult.group(1) + " (" + matchResult.group(2) + ")"));
    }

    /**
     * Closed-list strip pass. Each {@link #CLOSED_LIST} entry is
     * matched via its precompiled {@link #CLOSED_LIST_PATTERNS} pattern
     * (literal words, internal whitespace as {@code \s+}) and every
     * occurrence is replaced with {@value #REDACTED_COMMAND_REPLACEMENT}. Emits one
     * WARN log line per match. Used by tests that want the rewrite
     * without driving the audit emission; the production path is
     * {@link #applyClosedListStripWithMatches(String)} which also
     * captures the match list for per-occurrence audit rows.
     */
    static String applyClosedListStrip(String input) {
        return applyClosedListStripWithMatches(input).rewritten();
    }

    /**
     * Carrier for the closed-list pass: the rewritten text plus the
     * list of matched tokens (one entry per occurrence, in input
     * order). The instance {@link #sanitize(String)} writes one
     * {@code audit_log} row per element of {@link #matches()} —
     * the spec's per-occurrence promise.
     */
    record ClosedListStripResult(String rewritten, List<String> matches) {}

    /**
     * Closed-list strip pass that ALSO records the matched tokens for
     * downstream per-occurrence audit emission. Emits the WARN log
     * line per match here (so the WARN-vs-row count stays 1:1).
     */
    static ClosedListStripResult applyClosedListStripWithMatches(String input) {
        String current = input;
        List<String> matches = new ArrayList<>();
        for (int i = 0; i < CLOSED_LIST.size(); i++) {
            String token = CLOSED_LIST.get(i);
            Matcher m = CLOSED_LIST_PATTERNS.get(i).matcher(current);
            StringBuilder rewritten = null;
            while (m.find()) {
                if (rewritten == null) {
                    rewritten = new StringBuilder();
                }
                // Emit one WARN per match — per-occurrence, not throttled.
                LOG.warnf("LLM_OUTPUT_SANITIZED token=%s position=%d", token, m.start());
                matches.add(token);
                m.appendReplacement(rewritten, Matcher.quoteReplacement(REDACTED_COMMAND_REPLACEMENT));
            }
            if (rewritten != null) {
                m.appendTail(rewritten);
                current = rewritten.toString();
            }
        }
        return new ClosedListStripResult(current, matches);
    }

    /**
     * Write one {@code audit_log} row per matched token via
     * {@link AuditLogWriter}, all in a single transaction. The spec
     * §LLM output sanitizer commits to durability — "Every match is
     * audit-logged (per-occurrence, not throttled)" — so a partial
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
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            for (String token : matches) {
                String detailsJson = "{\"match_count\":1,\"match_kind\":\""
                        + JsonEscaper.escape(token) + "\"}";
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
            // "Every match is audit-logged (per-occurrence, not
            // throttled)." A partial write or a failed INSERT means
            // the user-visible reply must NOT be emitted with the
            // closed-list tokens stripped — the audit trail is the
            // load-bearing operator signal for those events.
            // try-with-resources closes the connection; PgConnection
            // rolls back an active transaction on close, so the
            // partial-write case leaves audit_log unchanged.
            throw new IllegalStateException(
                    "LlmOutputSanitizer: failed to durably audit-log sanitizer hits", e);
        }
    }

}
