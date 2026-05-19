package app.zcat.infochat.provider.llm;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>Every match emits one structured log line at level WARN — the
 * docs/spec/security.md per-occurrence (not throttled) commitment.
 * The persistent {@code audit_log} row INSERT is deferred to a T2
 * follow-up that lands the {@code LLM_OUTPUT_SANITIZED} verb +
 * {@code AuditLogWriter} class + the coordinated M1-008a verb-count
 * test update. In v1 the observable is the log emission.
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
            "/quarantine list",
            "/quarantine approve",
            "/quarantine reject",
            "/audit",
            "/remove-source",
            "/source-enable",
            "/source-disable",
            "/list-sources --all",
            "/list-sources --include-deleted",
            // Group-admin (or bot admin acting in the group):
            "/add-source",
            "/unfollow-source",
            "/lang",
            "/group-timezone",
            "/follow-tag",
            "/unfollow-tag"
    );

    /** {@code [text](url)} → {@code text (url)} per acceptance item 14. */
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");

    /**
     * Run both passes in order. The output is plain text, with
     * privileged commands replaced by {@value #REDACTED_COMMAND_REPLACEMENT}
     * and markdown links flattened to {@code text (url)}.
     */
    public String sanitize(String llmOutput) {
        if (llmOutput == null || llmOutput.isEmpty()) {
            return "";
        }
        String afterMarkdown = applyMarkdownLinkStrip(llmOutput);
        return applyClosedListStrip(afterMarkdown);
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
     * matched literally (Pattern.quote) and every occurrence is
     * replaced with {@value #REDACTED_COMMAND_REPLACEMENT}. Emits one
     * WARN log line per match.
     */
    static String applyClosedListStrip(String input) {
        String current = input;
        for (String token : CLOSED_LIST) {
            Pattern p = Pattern.compile(Pattern.quote(token));
            Matcher m = p.matcher(current);
            StringBuilder rewritten = null;
            while (m.find()) {
                if (rewritten == null) {
                    rewritten = new StringBuilder();
                }
                // Emit one WARN per match — per-occurrence, not throttled.
                LOG.warnf("LLM_OUTPUT_SANITIZED token=%s position=%d", token, m.start());
                m.appendReplacement(rewritten, Matcher.quoteReplacement(REDACTED_COMMAND_REPLACEMENT));
            }
            if (rewritten != null) {
                m.appendTail(rewritten);
                current = rewritten.toString();
            }
        }
        return current;
    }
}
