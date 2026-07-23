package app.zcat.infochat.provider.llm;

import app.zcat.infochat.core.audit.AuditAction;
import app.zcat.infochat.core.audit.TargetKind;
import app.zcat.infochat.core.audit.AuditLogWriter;
import app.zcat.infochat.core.audit.RedactionHook;
import app.zcat.infochat.core.ingest.IngestTextNormalizer;
import app.zcat.infochat.core.util.JsonEscaper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
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
 *       pass sees it, and runs AGAIN inside the closed-list pass over
 *       the canonical form, which NFKC can fold fullwidth brackets
 *       into. Delivered output never contains {@code ](}: what the
 *       flatten regex cannot parse is
 *       {@linkplain #neutralizeResidualLinkSyntax neutralized} instead,
 *       so the guarantee does not inherit the regex's limits.</li>
 *   <li><b>Closed-list strip.</b> Every privileged-tier command token
 *       from {@link #CLOSED_LIST} is replaced with the literal
 *       {@value #REDACTED_COMMAND_REPLACEMENT}. Replacement is
 *       uniform: every CLOSED_LIST entry is treated identically; the
 *       reader gets the surrounding prose minus the matched string,
 *       not an empty/failed reply. The match runs on the
 *       {@linkplain #canonicalizeForMatching canonical} form of the
 *       output, not its raw bytes — see that method for why.</li>
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
            "/invite pending-contacts",
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
     * Sentinel occupying the {@link #CLOSED_LIST_PATTERNS} slot of a
     * flag-bearing entry. It is compared by identity, never used as a
     * matcher — {@link #applyClosedListStripWithMatches} routes those
     * entries to {@link #redactFlagEntry}. It keeps the pattern list
     * index-aligned and null-free (the codebase's NullAway does not model
     * {@code List<@Nullable Pattern>}, so a real non-null marker is used
     * rather than a null slot). Its body never matches, so the identity
     * check is the only thing that ever distinguishes it.
     *
     * <p>Declared BEFORE {@link #CLOSED_LIST_PATTERNS} on purpose: static
     * fields initialize in textual order, and the pattern list's
     * initializer calls {@link #compileClosedListPattern}, which returns
     * this sentinel for flag entries — so it must already be non-null when
     * the list is built, or every flag slot would capture a {@code null}.
     */
    private static final Pattern FLAG_ENTRY_TOKENIZED = Pattern.compile("(?!)");

    /**
     * Precompiled match patterns for {@link #CLOSED_LIST}, index-aligned
     * (pattern {@code i} matches entry {@code i}). Compiled once at class
     * load so the closed-list pass does not re-compile every entry's
     * pattern on each {@link #sanitize(String)} call — the markdown pass's
     * {@link #MARKDOWN_LINK} is already static. Each word is quoted
     * literally; in multi-word entries the separating space matches as
     * {@code \s+} so extra internal whitespace ({@code /invite  create})
     * cannot evade the strip. The trailing lookahead is the word-boundary
     * contract: a match must end the string or be followed by a non-token
     * character.
     *
     * <p>The entry at index {@code i} is the {@link #FLAG_ENTRY_TOKENIZED}
     * sentinel exactly when that entry is <b>flag-bearing</b> (a later
     * word starts with {@code --}). A flag can appear at any position in
     * the command's argument run, and no regex matches that in linear time
     * without re-opening an evasion — see {@link #compileClosedListPattern}
     * — so flag entries are matched by the {@linkplain #redactFlagEntry
     * parser-mirroring tokenizer} instead. The list stays index-
     * aligned (and every element non-null) so
     * {@code CLOSED_LIST_PATTERNS.get(i)} still selects the mechanism for
     * entry {@code i}.
     */
    static final List<Pattern> CLOSED_LIST_PATTERNS = CLOSED_LIST.stream()
            .map(LlmOutputSanitizer::compileClosedListPattern)
            .toList();

    /**
     * Compile one entry's pattern. Case sensitivity is decided PER TOKEN,
     * mirroring what the parser does with that token — the same
     * match-what-the-consumer-sees rule {@link #canonicalizeForMatching}
     * applies to representation:
     *
     * <ul>
     *   <li><b>Command name (first word) — exact.</b>
     *       {@code InboundRouter.handleSlash} resolves it with
     *       {@code handler.name().equals(commandName)}, so
     *       {@code /Invite create} never dispatches and folding it would
     *       redact legitimate prose for no gain.</li>
     *   <li><b>Subcommand (a later word not starting with {@code --}) —
     *       folded.</b> The handlers lower-case that token before
     *       switching on it ({@code InviteCommandHandler} and
     *       {@code QuarantineCommandHandler} both do
     *       {@code split[1].toLowerCase(Locale.ROOT)}), so
     *       {@code /invite CREATE} DOES dispatch. Matching it
     *       case-sensitively left 8 of the closed list's entries evadable
     *       by changing one word's case — silently, since a non-match
     *       emits no WARN and no audit row. (M1-676 red-team finding,
     *       docs/plan/m1/redteam/M1-676-2026-07-23.md.)</li>
     *   <li><b>Flag (a later word starting with {@code --}) — not a
     *       regex at all; returns {@link #FLAG_ENTRY_TOKENIZED}.</b> A
     *       flag can appear at ANY position in the command's argument run:
     *       {@code ListSourcesArgs.parse} loops over every token from
     *       index 1 and ignores unknown ones, so {@code /list-sources
     *       --page 1 --all} dispatches the admin-only global listing
     *       identically to the adjacent form. Matching that with a regex
     *       has no good shape — a bounded run re-opens an evasion for a
     *       flag placed past the bound (which the parser still
     *       dispatches), and an unbounded lazy run is super-linear under
     *       {@link Matcher#find()} because it re-anchors at every command
     *       occurrence and rescans to end-of-input (a DoS on
     *       attacker-influenced output). A single left-to-right token
     *       scan ({@link #redactFlagEntry}) is both linear and
     *       evasion-free, so flag entries are handled there and this
     *       method returns the {@link #FLAG_ENTRY_TOKENIZED} sentinel
     *       instead of compiling a pattern. (M1-680 red-team rounds 1–2,
     *       docs/plan/m1/redteam/M1-680-2026-07-23-r2.md.)</li>
     * </ul>
     *
     * <p>ASCII-only folding is deliberate and sufficient: the pattern is
     * matched against the canonical form, where NFKC has already folded
     * fullwidth letters ({@code ＣＲＥＡＴＥ}) down to ASCII.
     *
     * @return the entry's pattern, or {@link #FLAG_ENTRY_TOKENIZED} for a
     *         flag-bearing entry (matched by {@link #redactFlagEntry})
     */
    private static Pattern compileClosedListPattern(String token) {
        String[] words = token.split(" ");
        for (int i = 1; i < words.length; i++) {
            if (words[i].startsWith("--")) {
                return FLAG_ENTRY_TOKENIZED;
            }
        }
        StringBuilder pattern = new StringBuilder(Pattern.quote(words[0]));
        for (int i = 1; i < words.length; i++) {
            pattern.append("\\s+(?i:").append(Pattern.quote(words[i])).append(")");
        }
        return Pattern.compile(pattern.append("(?=$|[^a-zA-Z0-9\\-])").toString());
    }

    /**
     * Redact every occurrence of a flag-bearing closed-list entry
     * ({@code token} = {@code "<command> <flag>"}, e.g.
     * {@code "/list-sources --all"}) from {@code input}, mirroring exactly
     * what {@code ListSourcesArgs.parse} dispatches: the command word,
     * followed anywhere later in the message by the flag as a
     * whitespace-delimited token. Each redaction spans the command word
     * through the flag and is replaced with
     * {@value #REDACTED_COMMAND_REPLACEMENT}; one WARN and one
     * {@code matches} entry are emitted per occurrence, matching the
     * regex path's per-occurrence audit semantics.
     *
     * <p><b>Why code and not a regex.</b> See
     * {@link #compileClosedListPattern}: no regex matches a flag at any
     * argument position both linearly and without an evasion. This scan is
     * a single left-to-right pass over the whole input — the
     * command-search and flag-search cursors only advance — so a reply of
     * P command-words is O(input length), not the O(P × input length)
     * that {@link Matcher#find()} re-anchoring costs. A hostile
     * endpoint's in-cap reply therefore cannot pin a worker thread.
     * (M1-680 red-team DOS finding,
     * docs/plan/m1/redteam/M1-680-2026-07-23-r2.md.)
     *
     * <p><b>Why it mirrors the parser exactly.</b> The router passes the
     * whole, possibly multi-line, message to the handler, and the parser
     * tokenizes it with {@code split("\\s+")} — and Java {@code \s}
     * includes {@code \n} — so the argument run spans every line, not
     * just the command word's own. The separator set here is therefore
     * exactly the ASCII {@code \s} set: the command word must be followed
     * by a separator; the flag must be a separator-delimited token equal
     * to the flag, with a trailing boundary admitting following
     * punctuation (so a copy-paste that drops a sentence-final {@code .}
     * still dispatches and is still redacted). No leading boundary is
     * required on the command word because {@code /} is the natural
     * copy-paste start, so {@code foo/list-sources --all} is redacted
     * while {@code /list-sourcesX --all} (no separator after the command
     * word) is not. (M1-680 red-team rounds 1–2; the round-3 high finding
     * was the cross-line evasion this paragraph pins.)
     */
    private static String redactFlagEntry(String input, String token, List<String> matches) {
        int space = token.indexOf(' ');
        String commandWord = token.substring(0, space);
        String flag = token.substring(space + 1);
        int commandLength = commandWord.length();
        int flagLength = flag.length();
        int length = input.length();
        StringBuilder out = new StringBuilder(length);
        int appended = 0;              // next unappended index (monotonic)
        int commandSearch = 0;         // monotonic over the whole input
        int flagSearch = 0;            // monotonic over the whole input
        while (true) {
            int command = findCommandToken(input, commandWord, commandLength, commandSearch);
            if (command < 0) {
                break;
            }
            int afterCommand = command + commandLength;
            if (flagSearch < afterCommand) {
                flagSearch = afterCommand;
            }
            int flagEnd = findFlagToken(input, flag, flagLength, flagSearch);
            if (flagEnd < 0) {
                // No flag token later in the message: this command
                // occurrence does not dispatch the flag. The search
                // reached end-of-input, so later command occurrences
                // short-circuit rather than rescan.
                flagSearch = length;
                commandSearch = afterCommand;
                continue;
            }
            out.append(input, appended, command).append(REDACTED_COMMAND_REPLACEMENT);
            LOG.warnf("LLM_OUTPUT_SANITIZED token=%s position=%d", token, command);
            matches.add(token);
            appended = flagEnd;
            commandSearch = flagEnd;
            flagSearch = flagEnd;
        }
        out.append(input, appended, length);
        return out.toString();
    }

    /**
     * First index {@code >= from} where {@code commandWord} occurs
     * followed by a token separator, or {@code -1}. The
     * following-separator requirement is the command word's trailing
     * token boundary ({@code /list-sourcesX} does not dispatch); no
     * leading boundary is checked, because {@code /} is the copy-paste
     * start.
     */
    private static int findCommandToken(String input, String commandWord, int commandLength,
            int from) {
        int candidate = from;
        while (true) {
            candidate = input.indexOf(commandWord, candidate);
            if (candidate < 0 || candidate + commandLength >= input.length()) {
                return -1;
            }
            if (isTokenSeparator(input.charAt(candidate + commandLength))) {
                return candidate;
            }
            candidate++;
        }
    }

    /**
     * Exclusive end index of the first {@code flag} occurring as a
     * separator-delimited token at or after {@code from}, or {@code -1}.
     * The token must be preceded by a separator (leading boundary) and
     * followed by the end of input or a non-{@code [A-Za-z0-9-]}
     * character (trailing boundary, matching the regex path's
     * {@code (?=$|[^a-zA-Z0-9\-])} so {@code --all.} still matches but
     * {@code --allx} does not).
     */
    private static int findFlagToken(String input, String flag, int flagLength,
            int from) {
        int candidate = from;
        while (true) {
            candidate = input.indexOf(flag, candidate);
            if (candidate < 0) {
                return -1;
            }
            boolean leadingBoundary = candidate > 0
                    && isTokenSeparator(input.charAt(candidate - 1));
            int after = candidate + flagLength;
            boolean trailingBoundary = after >= input.length() || !isFlagBodyCharacter(input.charAt(after));
            if (leadingBoundary && trailingBoundary) {
                return after;
            }
            candidate++;
        }
    }

    /**
     * The ASCII {@code \s} set — the token separators the parser's
     * {@code split("\\s+")} sees. {@code \n} is a separator like any
     * other: the router preserves internal newlines in the body it hands
     * the handler, so the parser's argument run spans lines.
     */
    private static boolean isTokenSeparator(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\u000B';
    }

    /** A character that extends a flag token: {@code [A-Za-z0-9-]}. */
    private static boolean isFlagBodyCharacter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '-';
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
     *
     * <p>Parsing alone cannot carry the no-link guarantee, so
     * {@link #neutralizeResidualLinkSyntax} finishes the job on whatever
     * the regex could not match — see that method.
     */
    static String applyMarkdownLinkStrip(String input) {
        Matcher m = MARKDOWN_LINK.matcher(input);
        return neutralizeResidualLinkSyntax(m.replaceAll(matchResult -> Matcher.quoteReplacement(
                matchResult.group(1) + " (" + matchResult.group(2) + ")")));
    }

    /**
     * Break the {@code ](} adjacency on any link syntax the flatten pass
     * could not parse, by inserting one space. Both characters survive, so
     * the label and the bare URL stay visible to the reader — the same
     * outcome the flatten aims for, reached without parsing.
     *
     * <p><b>Why parsing alone is not enough.</b> {@link #MARKDOWN_LINK}'s
     * label group {@code [^\]]+} cannot span a nested {@code ]}, but
     * CommonMark permits balanced brackets in a label, so
     * {@code [Read [the] report](url)} is a real link the regex will never
     * match. Balanced-bracket matching is not expressible as a regular
     * expression at all, so no amount of tightening the pattern closes
     * this; only a scanner or this adjacency break does. The break is
     * chosen because the property that matters is about TWO ADJACENT
     * CHARACTERS, not about parsing markdown — which is what lets the
     * guarantee be stated absolutely without inheriting the regex's
     * limits.
     *
     * <p><b>Why it must also run after canonicalization.</b> NFKC folds
     * the fullwidth brackets U+FF3B/U+FF3D/U+FF08/U+FF09 down to
     * {@code []()}. Text that arrived as {@code [Read [the] report ］（url）}
     * — not link syntax, and not rendered as a link by any client — has a
     * real {@code ](} after canonicalization. Delivering the canonical
     * form on a match would therefore MANUFACTURE a working link out of
     * text that was not one, which is the inverse of this pass's purpose.
     *
     * <p>Also covers the {@value #REDACTED_COMMAND_REPLACEMENT} marker
     * landing against a following {@code (}: the marker carries its own
     * brackets and the closed-list match's word-boundary lookahead
     * deliberately admits {@code (}, so replacing the token in
     * {@code /ban(url)} would otherwise emit {@code [redacted command](url)}
     * — link syntax the sanitizer built itself, after the last flatten had
     * already run. (M1-676 red-team rounds 1–3,
     * docs/plan/m1/redteam/M1-676-2026-07-23-r3.md.)
     */
    private static String neutralizeResidualLinkSyntax(String text) {
        return text.replace("](", "] (");
    }

    /**
     * Closed-list strip pass. Each {@link #CLOSED_LIST} entry is matched
     * via its precompiled {@link #CLOSED_LIST_PATTERNS} pattern (literal
     * words, internal whitespace as {@code \s+}), or, for a flag-bearing
     * entry whose pattern slot is the {@link #FLAG_ENTRY_TOKENIZED}
     * sentinel, via {@link #redactFlagEntry}; every occurrence is replaced with
     * {@value #REDACTED_COMMAND_REPLACEMENT}. Emits one WARN log line per
     * match. Used by tests that want the rewrite without driving the
     * audit emission; the production path is
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
     *
     * <p>Matching runs on the {@linkplain #canonicalizeForMatching
     * canonical} form. On zero matches the caller's ORIGINAL bytes are
     * returned, so a canonicalization that changed nothing security-
     * relevant never reaches the user: only a match may change the
     * output's representation, and a match means the text carried a
     * token that canonicalizes into a privileged command.
     *
     * <p>The markdown pass is re-applied to the canonical form before
     * matching. NFKC folds the fullwidth brackets U+FF3B, U+FF3D, U+FF08
     * and U+FF09 down to {@code []()}, so canonicalization can
     * SYNTHESIZE markdown link syntax that {@link #MARKDOWN_LINK} —
     * ASCII-bracket-only, and running on the raw bytes — could not have
     * seen. Without this the sanitizer would manufacture, on the match
     * path, exactly the label-hiding link syntax its first pass exists to
     * remove. Running it BEFORE the replacement is what keeps the
     * {@value #REDACTED_COMMAND_REPLACEMENT} marker's own brackets from
     * being treated as link text; the post-replacement neutralization
     * below then covers the marker itself, which is created after both
     * invocations have run. (M1-676 red-team rounds 1–3,
     * docs/plan/m1/redteam/M1-676-2026-07-23-r3.md.)
     */
    static ClosedListStripResult applyClosedListStripWithMatches(String input) {
        String current = applyMarkdownLinkStrip(canonicalizeForMatching(input));
        List<String> matches = new ArrayList<>();
        for (int i = 0; i < CLOSED_LIST.size(); i++) {
            String token = CLOSED_LIST.get(i);
            Pattern pattern = CLOSED_LIST_PATTERNS.get(i);
            if (pattern == FLAG_ENTRY_TOKENIZED) {
                // Flag-bearing entry: matched by the parser-mirroring
                // tokenizer, which emits its own WARN + matches entries.
                current = redactFlagEntry(current, token, matches);
                continue;
            }
            Matcher m = pattern.matcher(current);
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
        if (matches.isEmpty()) {
            return new ClosedListStripResult(input, matches);
        }
        // Re-run after the replacement, not just before it: the marker is
        // created here, so it is the one piece of bracketed text neither
        // flatten invocation could have seen.
        return new ClosedListStripResult(neutralizeResidualLinkSyntax(current), matches);
    }

    /**
     * The representation the closed-list pass matches against: NFKC,
     * then the bidi-control and zero-width strip. This MUST stay the
     * same transformation the chat intake applies per non-fenced line
     * (docs/spec/security.md §Message intake step 1.7,
     * {@code InboundRouter.appendNormalized}), because that is the
     * representation the command dispatcher actually sees. Matching raw
     * bytes instead leaves a representation asymmetry the sanitizer is
     * blind to: {@code ／grant-admin} (U+FF0F), an all-fullwidth token,
     * a ZWSP- or bidi-embedded token, and a U+3000-joined multi-word
     * entry all survive a raw-byte match verbatim, yet each parses as a
     * privileged command once a reader copy-pastes the bot's line back
     * in. (M1-676; the strip half is
     * {@link IngestTextNormalizer#stripBidiAndZeroWidth}, the single
     * declaration of that codepoint set.)
     *
     * <p>Case is deliberately NOT folded here, because it is not a
     * property of the representation — the parser folds some tokens and
     * not others, so the decision belongs per token, in
     * {@link #compileClosedListPattern}, not in a blanket pass over the
     * whole string.
     */
    static String canonicalizeForMatching(String input) {
        return IngestTextNormalizer.stripBidiAndZeroWidth(
                Normalizer.normalize(input, Normalizer.Form.NFKC));
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
