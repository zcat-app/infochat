package app.zcat.infochat.collector.eval.stage1;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The seven prompt-injection patterns Stage 1 detects on every
 * upstream feed body, locked at design-tier per
 * {@code docs/design/04-security.md} §4.2 step 3.
 *
 * <h2>Pattern set is closed</h2>
 * <p>The set is a closed spec-level commitment. Operator-tunable
 * regex catalogues, per-source / per-post-kind overrides, and
 * pattern enrichment as a defense layer are out of v1 (Big-picture
 * notes: "Stage 1 is a coarse filter, not a complete defense …
 * Adding more regex patterns to Stage 1 buys very little once the
 * chat output sanitizer is in place"). Changing the set is a
 * spec-amendment-class change, not an implementation tweak.
 *
 * <h2>Stable {@code rule_id} per pattern</h2>
 * <p>Each pattern carries a stable string id that becomes
 * {@code quarantine.rule_id} when the pattern matches. Stability
 * across builds matters: admin reviewers correlate quarantine rows
 * by {@code rule_id} when triaging false positives or confirming
 * true positives, and a rename here would orphan prior rows from
 * their pattern explanation. Renames are spec-amendment-class.
 *
 * <h2>CASE_INSENSITIVE compilation</h2>
 * <p>Every pattern compiles with {@link Pattern#CASE_INSENSITIVE}
 * because feed bodies arrive in arbitrary case and the verb /
 * keyword sets the pattern matches against are ASCII-only. Unicode
 * compatibility folding has already happened at this point
 * ({@link Stage1Pipeline} runs NFKC + bidi-strip + zero-width-strip
 * BEFORE invoking this set), so {@link Pattern#UNICODE_CASE} would
 * add no detection power.
 *
 * <h2>Bounded {@code .{0,N}} segments and the watchdog</h2>
 * <p>Several patterns use {@code .{0,40}} interstitial spans to
 * tolerate filler tokens between key verbs (e.g.
 * "ignore <filler> previous <filler> instructions"). The bounded
 * form caps the patterns' backtracking under
 * {@link java.util.regex.Matcher}'s NFA engine; the per-input
 * wall-clock watchdog in {@link Stage1Pipeline} bounds the worst-
 * case anyway, so a pathological input that escapes the {@code .{0,40}}
 * bound is caught by the watchdog rather than by tighter regex shape.
 * The {@code docs/spec/security.md} §Ingest pipeline "Regex engine
 * commitment (v1)" pins this strategy to {@code java.util.regex} +
 * watchdog explicitly so an RE2/J swap is a v2 amendment, not a
 * silent design tweak.
 */
public final class Stage1RegexSet {

    /**
     * Rule id for the canonical "ignore/disregard/forget previous
     * instructions" injection family. The first regex_timeout
     * adversarial input usually targets this pattern's
     * {@code .{0,40}} interstitials.
     */
    public static final String RULE_IGNORE_PREVIOUS_INSTRUCTIONS =
        "stage1.ignore_previous_instructions";

    /** Role redefinition attempting to elevate to admin/root/system/developer. */
    public static final String RULE_ROLE_REDEFINITION = "stage1.role_redefinition";

    /** System / assistant impersonation prefix at the start of a line. */
    public static final String RULE_IMPERSONATION_PREFIX = "stage1.impersonation_prefix";

    /** Secrets-exfiltration request (reveal/leak/print/output system prompt/api key/password). */
    public static final String RULE_SECRETS_LEAK = "stage1.secrets_leak";

    /** HTML comment hide — {@code <!-- ... -->} smuggling. */
    public static final String RULE_HTML_COMMENT_HIDE = "stage1.html_comment_hide";

    /**
     * Delimiter injection — attacker tries to forge the
     * {@code <<<UNTRUSTED>>>} markers or a role tag that survives
     * the Stage-1 strip. This rule catches pre-existing
     * {@code [REDACTED:...]}-shaped placeholders is NOT this rule's
     * job; per-row placeholder randomization in
     * {@link PlaceholderIds#next()} is what stops that vector.
     */
    public static final String RULE_DELIMITER_INJECTION = "stage1.delimiter_injection";

    /** Tool-call simulation: {@code function_call:(...)} or {@code tool:(...)}. */
    public static final String RULE_TOOL_CALL_SIMULATION = "stage1.tool_call_simulation";

    /**
     * The compiled pattern set, one {@link Rule} per
     * {@code rule_id}. Iteration order is stable (the order matches
     * the design-tier spec layout in §4.2 step 3) so a deterministic
     * scan-and-replace produces deterministic
     * {@code rule_id} assignment on a multi-match input.
     */
    public static final List<Rule> RULES = List.of(
        // 1. ignore/disregard/forget … previous/prior/above/all/earlier
        //    … instruction(s)/prompt(s)/rule(s)/directive(s)
        new Rule(
            RULE_IGNORE_PREVIOUS_INSTRUCTIONS,
            Pattern.compile(
                "\\b(?:ignore|disregard|forget|override|skip)\\b"
                    + ".{0,40}"
                    + "\\b(?:previous|prior|above|all|earlier|preceding)\\b"
                    + ".{0,40}"
                    + "\\b(?:instructions?|prompts?|rules?|directives?|commands?)\\b",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL)),
        // 2. role redefinition with admin/root/system/developer
        new Rule(
            RULE_ROLE_REDEFINITION,
            Pattern.compile(
                "\\b(?:you\\s+are|act\\s+as|pretend\\s+to\\s+be|behave\\s+like|"
                    + "from\\s+now\\s+on\\s+you(?:\\s+are|'re))\\b"
                    + ".{0,40}"
                    + "\\b(?:admin(?:istrator)?|root|system|developer|sudo|"
                    + "superuser|owner|maintainer)\\b",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL)),
        // 3. system|assistant impersonation prefix at line start
        new Rule(
            RULE_IMPERSONATION_PREFIX,
            Pattern.compile(
                "(?m)^\\s*(?:system|assistant|user)\\s*[:>\\]]",
                Pattern.CASE_INSENSITIVE)),
        // 4. secrets-leak (reveal/leak/print/output … system prompt /
        //    instructions / api key / password)
        new Rule(
            RULE_SECRETS_LEAK,
            Pattern.compile(
                "\\b(?:reveal|leak|print|output|show|expose|disclose|dump)\\b"
                    + ".{0,40}"
                    + "\\b(?:system\\s+prompt|system\\s+instructions|"
                    + "api[\\s_-]?key|secret\\s+key|password|credentials?|"
                    + "tokens?|hidden\\s+prompt)\\b",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL)),
        // 5. HTML comment hide
        new Rule(
            RULE_HTML_COMMENT_HIDE,
            Pattern.compile("<!--.*?-->", Pattern.DOTALL)),
        // 6. delimiter injection — <<<UNTRUSTED>>>, </UNTRUSTED>,
        //    triple-backtick role names, </?(system|user|assistant)>
        new Rule(
            RULE_DELIMITER_INJECTION,
            Pattern.compile(
                "<<<\\s*untrusted[^>]*>>>"
                    + "|</?\\s*untrusted\\s*>"
                    + "|```\\s*(?:system|user|assistant)"
                    + "|</?\\s*(?:system|user|assistant)\\s*>",
                Pattern.CASE_INSENSITIVE)),
        // 7. tool-call simulation
        new Rule(
            RULE_TOOL_CALL_SIMULATION,
            Pattern.compile(
                "\\b(?:function[_-]?call|tool[_-]?call|tool|function)\\s*[:(]",
                Pattern.CASE_INSENSITIVE)));

    private Stage1RegexSet() {
        // utility-class constant holder
    }

    /**
     * One compiled pattern + its stable {@code rule_id}. The rule_id
     * is the audit key in {@code quarantine.rule_id}.
     */
    public record Rule(String ruleId, Pattern pattern) {
    }
}
