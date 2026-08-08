package app.zcat.infochat.core.llm;

import app.zcat.infochat.core.ingest.IngestTextNormalizer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure text transform half of the LLM output sanitizer, extracted from
 * the Provider's {@code LlmOutputSanitizer} bean (M1-749) so the SAME
 * sanitization pipeline runs on both surfaces that store or emit
 * LLM-authored text:
 *
 * <ul>
 *   <li>the Provider bean (delegates here; API, behaviour, WARN stream
 *       and {@code LLM_OUTPUT_SANITIZED} audit rows unchanged) — model
 *       output leaving the bot; and</li>
 *   <li>the Collector's {@code IngestTranslationWorker} — translator
 *       output re-entering the corpus as {@code post.title_en} /
 *       {@code post.body_en} (docs/spec/security.md: model output
 *       derived from upstream-untrusted input inherits every control
 *       the raw body had). A collector-side copy of this control would
 *       be a fork the spec-parity CI test
 *       ({@code LlmOutputSanitizerTest.matchSetEqualsSpecClosedList})
 *       does not cover, so both consumers call this one class.</li>
 * </ul>
 *
 * <p>The class is deliberately PURE: text in, text plus match-metadata
 * out. It emits no log lines and writes no audit rows itself —
 * observability stays with the caller, and BOTH callers emit the same
 * shape: the Provider bean (aggregated WARN + {@code LLM_OUTPUT_SANITIZED}
 * audit rows on the outbound surface) and the Collector's
 * {@code IngestTranslationWorker} (the same aggregated WARN + audit rows
 * on the ingest-translation surface — a surface that takes the strip
 * takes the audit). Enforced invariants, per docs/spec/security.md §LLM
 * output sanitizer and docs/spec/commands.md §Surface conventions:
 *
 * <ol>
 *   <li><b>No prompt scaffolding.</b> A line carrying an echoed wrapper
 *       marker is {@linkplain #applyScaffoldingMarkerStrip dropped
 *       wholesale} — extracting the marker could assemble a new one.</li>
 *   <li><b>Plain-text only.</b> Markdown link syntax {@code [text](url)}
 *       is rewritten to {@code text (url)} so the rendered prose carries
 *       both the visible label and the bare URL. Runs AHEAD of the
 *       closed-list pass so a
 *       hostile {@code [Click for admin](/grant-admin)} flattens to
 *       {@code Click for admin (/grant-admin)} BEFORE the closed-list
 *       pass sees it, and runs AGAIN inside the closed-list pass over
 *       the canonical form, which NFKC can fold fullwidth brackets
 *       into. Output never contains {@code ](}: what the
 *       flatten regex cannot parse is
 *       {@linkplain #neutralizeResidualLinkSyntax neutralized} instead,
 *       so the guarantee does not inherit the regex's limits.</li>
 *   <li><b>Markdown downgraded for the plain-text surface.</b> The
 *       {@linkplain #applyPlainTextDowngrade downgrade} removes emphasis,
 *       drops thematic breaks, rewrites list markers to {@code · }.</li>
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
 * <p>Every character-deleting pass runs BEFORE the closed-list strip —
 * see {@link #applyScaffoldingMarkerStrip} for why.
 *
 * <p>{@link #CLOSED_LIST} is hand-maintained in code to mirror
 * docs/spec/commands.md §Permission model §Closed list of
 * privileged-tier commands. The CI completeness {@code @Test}
 * {@code LlmOutputSanitizerTest.matchSetEqualsSpecClosedList} reads the
 * spec markdown at TEST tier and asserts equality (through the Provider
 * bean's alias of this list); a spec-side
 * addition without a corresponding CLOSED_LIST update fails CI, and a
 * CLOSED_LIST entry that no longer corresponds to a listed command
 * also fails CI.
 */
public final class LlmOutputSanitizerCore {

    private LlmOutputSanitizerCore() {
        // Static transform surface only; no instances.
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
     * loop keeps the per-entry observability promise (one aggregated
     * log line per matched token, emitted by the CALLER from the
     * returned match list).
     */
    public static final List<String> CLOSED_LIST = List.of(
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
     * pattern on each call — the markdown pass's
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
    public static final List<Pattern> CLOSED_LIST_PATTERNS = CLOSED_LIST.stream()
            .map(LlmOutputSanitizerCore::compileClosedListPattern)
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
     * {@value #REDACTED_COMMAND_REPLACEMENT}; one {@code matches} entry
     * is recorded per occurrence, feeding the same per-token
     * aggregation (WARN + audit row at the caller) as the regex path.
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
     * <p><b>Why it mirrors the parser exactly.</b> The parser tokenizes
     * the body it is handed with {@code split("\\s+")}, so the separator
     * set here is exactly the ASCII {@code \s} set. It stays that wide
     * across line boundaries even though M1-772 made a command occupy
     * one line — the router now rejects a multi-line slash body unparsed
     * ({@code docs/spec/commands.md} §Surface conventions), so an
     * argument run can no longer span lines, but this scan reads bot
     * output and feed text rather than command bodies and cannot rely on
     * that rejection having happened. The detector staying wider than
     * the dispatcher costs only redaction breadth; narrowing it is a
     * separate change that must be argued on its own evidence
     * ({@code docs/spec/security.md} §LLM output sanitizer). Concretely:
     * the command word must be followed
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
     * {@code split("\\s+")} sees. The line boundaries in it stay
     * separators here even though a multi-line slash body is now
     * rejected before any parser (M1-772): this scan reads bot output
     * and feed text, where no such rejection applies. See
     * {@link #redactFlagEntry} for the full argument.
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
     * Markdown-link strip pass. The regex preserves both the link text
     * AND the bare URL ({@code $1 ($2)}) so the user-visible content is
     * not lost. Runs FIRST so a hostile {@code [Click](/grant-admin)}
     * cannot hide an admin command inside link syntax.
     *
     * <p>Parsing alone cannot carry the no-link guarantee, so
     * {@link #neutralizeResidualLinkSyntax} finishes the job on whatever
     * the regex could not match — see that method.
     */
    public static String applyMarkdownLinkStrip(String input) {
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
        return breakLinkAdjacency(text);
    }

    /**
     * The `](` adjacency break, as a reusable operation: insert one space
     * between the two characters so no renderer resolves them as a link.
     * The single declaration of the mechanism — see
     * {@link #neutralizeResidualLinkSyntax} for why the property is stated
     * over two adjacent characters rather than over parsed markdown.
     *
     * <p>Two callers, deliberately at different altitudes. The sanitizer
     * passes call it <i>within</i> the transform so a redaction or a
     * canonicalization cannot manufacture link syntax it then emits
     * (M1-676 rounds 1–3). {@code OutboundDelivery} calls it (through the
     * Provider bean's delegate) on every outbound body, because the
     * guarantee is a property of the DELIVERED MESSAGE, not of any one
     * sanitized field: a render path that joins sanitizer output with
     * operands the sanitizer never saw — a source display name, a bare feed
     * URL — would otherwise ship `](` while every individual sanitize call
     * was correct (M1-691).
     *
     * <p>Idempotent ({@code "] ("} contains no {@code "]("}) and
     * character-preserving, so the two call sites stack without interfering
     * and a bare URL stays readable (D30).
     */
    public static String breakLinkAdjacency(String text) {
        return text.replace("](", "] (");
    }

    /** The wrapper markers as the model may echo them; the id is optional
        and loosely matched, but never spans a {@code /} — see
        {@link #applyScaffoldingMarkerStripWithMatches}. */
    private static final Pattern SCAFFOLDING_MARKER = Pattern.compile(
            "<<<(?:UNTRUSTED_CONTENT|END)(?:\\s+id=\"[^\"/]*\")?>>>");

    /** Carrier for the scaffolding strip: the rewritten text plus the
        closed-list tokens found on dropped lines (audit-on-drop). */
    public record ScaffoldingStripResult(String rewritten, List<String> matches) {}

    /** Scaffolding-marker strip — the rewrite half of
        {@link #applyScaffoldingMarkerStripWithMatches(String)}. */
    public static String applyScaffoldingMarkerStrip(String input) {
        return applyScaffoldingMarkerStripWithMatches(input).rewritten();
    }

    /** Strip echoed wrapper markers, line-scope: a marker-bearing line is
        dropped wholesale (excising and rejoining could assemble a new
        marker), first matched canonically so a closed-list token on it is
        rowed, not lost. Contract: docs/spec/security.md §LLM output sanitizer. */
    public static ScaffoldingStripResult applyScaffoldingMarkerStripWithMatches(String input) {
        // Cheap out: no "<<<" means no marker, so no allocation.
        if (input.indexOf("<<<") < 0) {
            return new ScaffoldingStripResult(input, List.of());
        }
        int length = input.length();
        StringBuilder out = new StringBuilder(length);
        List<String> matches = new ArrayList<>();
        Matcher matcher = SCAFFOLDING_MARKER.matcher(input);
        boolean anyKept = false;
        int lineStart = 0;
        while (lineStart <= length) {
            int newline = input.indexOf('\n', lineStart);
            int lineEnd = newline < 0 ? length : newline;
            matcher.region(lineStart, lineEnd);
            if (matcher.find()) {
                // Audit-on-drop: a closed-list token on the dropped line is
                // still rowed (canonical-form match, reusing the machinery).
                matches.addAll(applyClosedListStripWithMatches(
                        input.substring(lineStart, lineEnd)).matches());
            } else {
                if (anyKept) {
                    out.append('\n');
                }
                anyKept = true;
                out.append(input, lineStart, lineEnd);
            }
            if (newline < 0) {
                break;
            }
            lineStart = newline + 1;
        }
        return new ScaffoldingStripResult(out.toString(), matches);
    }

    /** A bare URL, scheme-case-insensitive (RFC 3986 schemes are): the
        verbatim span the emphasis deletion must never rewrite. */
    private static final Pattern BARE_URL_SPAN = Pattern.compile("(?i:https?)://\\S+");

    /** Bound on unmatched emphasis openers carried per line: a reply of
        nothing but openers stays linear; emphasis past the cap stays
        undowngraded — cosmetic, never a deleted control. */
    private static final int MAX_PENDING_EMPHASIS_OPENERS = 1024;

    /** Plain-text downgrade for the D30 surface: emphasis removed per
        CommonMark flanking, thematic-break lines dropped, list markers
        become {@code · }. Contract: docs/spec/security.md §LLM output sanitizer. */
    public static String applyPlainTextDowngrade(String input) {
        int length = input.length();
        StringBuilder out = new StringBuilder(length);
        boolean inFence = false;
        boolean anyKept = false;
        int lineStart = 0;
        while (lineStart <= length) {
            int newline = input.indexOf('\n', lineStart);
            int lineEnd = newline < 0 ? length : newline;
            boolean fenceLine = isFenceDelimiterLine(input, lineStart, lineEnd);
            if (fenceLine) {
                inFence = !inFence;
            }
            if (fenceLine || inFence) {
                if (anyKept) {
                    out.append('\n');
                }
                anyKept = true;
                out.append(input, lineStart, lineEnd);
            } else if (!isThematicBreakLine(input, lineStart, lineEnd)) {
                if (anyKept) {
                    out.append('\n');
                }
                anyKept = true;
                appendDowngradedLine(out, input, lineStart, lineEnd);
            }
            if (newline < 0) {
                break;
            }
            lineStart = newline + 1;
        }
        return out.toString();
    }

    /** A fence delimiter line: leading spaces/tabs then a run of three or
        more backticks (an opening fence may carry an info string). By this
        pass a fence is opened AND closed by this shape alone. */
    private static boolean isFenceDelimiterLine(String input, int lineStart, int lineEnd) {
        int i = lineStart;
        while (i < lineEnd && (input.charAt(i) == ' ' || input.charAt(i) == '\t')) {
            i++;
        }
        int runStart = i;
        while (i < lineEnd && input.charAt(i) == '`') {
            i++;
        }
        return i - runStart >= 3;
    }

    /** A thematic-break line: three or more of ONE of {@code - * _} with
        only spaces/tabs between and an optional trailing CR; checked before
        the list-marker rewrite, so {@code - - -} drops. */
    private static boolean isThematicBreakLine(String input, int lineStart, int lineEnd) {
        int contentEnd = lineEnd > lineStart && input.charAt(lineEnd - 1) == '\r'
                ? lineEnd - 1 : lineEnd;
        int i = lineStart;
        while (i < contentEnd && (input.charAt(i) == ' ' || input.charAt(i) == '\t')) {
            i++;
        }
        if (i >= contentEnd) {
            return false;
        }
        char marker = input.charAt(i);
        if (marker != '-' && marker != '*' && marker != '_') {
            return false;
        }
        int count = 0;
        for (int j = i; j < contentEnd; j++) {
            char c = input.charAt(j);
            if (c == marker) {
                count++;
            } else if (c != ' ' && c != '\t') {
                return false;
            }
        }
        return count >= 3;
    }

    /** One kept line: a leading list marker becomes {@code · }; the rest
        of the line — or the whole line — gets the emphasis downgrade. */
    private static void appendDowngradedLine(StringBuilder out, String input,
                                             int lineStart, int lineEnd) {
        int i = lineStart;
        while (i < lineEnd && (input.charAt(i) == ' ' || input.charAt(i) == '\t')) {
            i++;
        }
        int markerEnd = listMarkerEnd(input, i, lineEnd);
        if (markerEnd < 0) {
            appendEmphasisDowngraded(out, input, lineStart, lineEnd);
            return;
        }
        out.append(input, lineStart, i).append('·');
        appendEmphasisDowngraded(out, input, markerEnd, lineEnd);
    }

    /** End index of the list marker starting at {@code i} (past the bullet
        or the ordered marker's {@code .}/{@code )}), or -1 when there is
        none; a marker requires a following space or tab. */
    private static int listMarkerEnd(String input, int i, int lineEnd) {
        if (i >= lineEnd) {
            return -1;
        }
        char c = input.charAt(i);
        int markerEnd;
        if (c == '-' || c == '*' || c == '+') {
            markerEnd = i + 1;
        } else if (c >= '0' && c <= '9') {
            int j = i + 1;
            while (j < lineEnd && j - i < 9 && input.charAt(j) >= '0' && input.charAt(j) <= '9') {
                j++;
            }
            if (j >= lineEnd || (input.charAt(j) != '.' && input.charAt(j) != ')')) {
                return -1;
            }
            markerEnd = j + 1;
        } else {
            return -1;
        }
        if (markerEnd >= lineEnd
                || (input.charAt(markerEnd) != ' ' && input.charAt(markerEnd) != '\t')) {
            return -1;
        }
        return markerEnd;
    }

    /** Append {@code [from, to)} with paired emphasis delimiters deleted;
        verbatim spans pass through untouched and shield their delimiters.
        Deletion joins characters, so this runs BEFORE the closed-list strip. */
    private static void appendEmphasisDowngraded(StringBuilder out, String input,
                                                 int from, int to) {
        boolean hasEmphasisCharacter = false;
        for (int i = from; i < to; i++) {
            char c = input.charAt(i);
            if (c == '*' || c == '_') {
                hasEmphasisCharacter = true;
                break;
            }
        }
        if (!hasEmphasisCharacter) {
            out.append(input, from, to);
            return;
        }
        List<int[]> spans = verbatimSpans(input, from, to);
        List<int[]> runs = delimiterRuns(input, from, to, spans);
        pairEmphasis(runs);
        int cursor = from;
        for (int[] run : runs) {
            int start = run[0];
            int end = run[1];
            int consumedFromStart = run[2];
            int consumedFromEnd = run[3];
            if (consumedFromStart > 0) {
                out.append(input, cursor, start);
                cursor = start + consumedFromStart;
            }
            if (consumedFromEnd > 0) {
                out.append(input, cursor, end - consumedFromEnd);
                cursor = end;
            }
        }
        out.append(input, cursor, to);
    }

    /** The line's verbatim spans as {@code [start, end)} pairs in input
        order: single-backtick code spans, then bare URLs in the gaps. */
    private static List<int[]> verbatimSpans(String input, int from, int to) {
        List<int[]> codeSpans = new ArrayList<>();
        int i = from;
        while (i < to) {
            if (input.charAt(i) != '`') {
                i++;
                continue;
            }
            int close = -1;
            for (int j = i + 1; j < to; j++) {
                if (input.charAt(j) == '`') {
                    close = j;
                    break;
                }
            }
            if (close < 0) {
                break;
            }
            codeSpans.add(new int[] { i, close + 1 });
            i = close + 1;
        }
        Matcher url = BARE_URL_SPAN.matcher(input);
        url.region(from, to);
        // Merge the two input-ordered, self-disjoint families in one walk:
        // a code span wins over an overlapping URL span.
        List<int[]> spans = new ArrayList<>();
        int codeIndex = 0;
        int firstLiveCodeSpan = 0;
        while (url.find()) {
            while (codeIndex < codeSpans.size()
                    && codeSpans.get(codeIndex)[0] < url.start()) {
                spans.add(codeSpans.get(codeIndex));
                codeIndex++;
            }
            while (firstLiveCodeSpan < codeSpans.size()
                    && codeSpans.get(firstLiveCodeSpan)[1] <= url.start()) {
                firstLiveCodeSpan++;
            }
            boolean overlapsCode = firstLiveCodeSpan < codeSpans.size()
                    && codeSpans.get(firstLiveCodeSpan)[0] < url.end();
            if (!overlapsCode) {
                spans.add(new int[] { url.start(), url.end() });
            }
        }
        while (codeIndex < codeSpans.size()) {
            spans.add(codeSpans.get(codeIndex));
            codeIndex++;
        }
        return spans;
    }

    /** Delimiter runs of {@code *} and {@code _} outside the verbatim
        spans, flanking-classified so arithmetic and identifiers survive;
        each is {@code [start, end, fromStart, fromEnd, open, close, marker]}. */
    private static List<int[]> delimiterRuns(String input, int from, int to, List<int[]> spans) {
        List<int[]> runs = new ArrayList<>();
        int spanIndex = 0;
        int i = from;
        while (i < to) {
            if (spanIndex < spans.size() && i >= spans.get(spanIndex)[0]) {
                i = spans.get(spanIndex)[1];
                spanIndex++;
                continue;
            }
            char c = input.charAt(i);
            if (c != '*' && c != '_') {
                i++;
                continue;
            }
            int runEnd = i;
            while (runEnd < to && input.charAt(runEnd) == c) {
                runEnd++;
            }
            char before = i > from ? input.charAt(i - 1) : ' ';
            char after = runEnd < to ? input.charAt(runEnd) : ' ';
            boolean beforeWhitespace = Character.isWhitespace(before);
            boolean afterWhitespace = Character.isWhitespace(after);
            boolean beforePunctuation = isEmphasisPunctuation(before);
            boolean afterPunctuation = isEmphasisPunctuation(after);
            boolean leftFlanking = !afterWhitespace
                    && (!afterPunctuation || beforeWhitespace || beforePunctuation);
            boolean rightFlanking = !beforeWhitespace
                    && (!beforePunctuation || afterWhitespace || afterPunctuation);
            boolean canOpen = c == '*'
                    ? leftFlanking
                    : leftFlanking && (!rightFlanking || beforePunctuation);
            boolean canClose = c == '*'
                    ? rightFlanking
                    : rightFlanking && (!leftFlanking || afterPunctuation);
            runs.add(new int[] { i, runEnd, 0, 0, canOpen ? 1 : 0, canClose ? 1 : 0, c });
            i = runEnd;
        }
        return runs;
    }

    /** ASCII punctuation plus every non-ASCII non-letter/digit — the
        flanking rules' punctuation class, approximated for this pass. */
    private static boolean isEmphasisPunctuation(char c) {
        return (c >= 0x21 && c <= 0x2F) || (c >= 0x3A && c <= 0x40)
                || (c >= 0x5B && c <= 0x60) || (c >= 0x7B && c <= 0x7E)
                || (c >= 0x80 && !Character.isLetterOrDigit(c));
    }

    /** Pair closers to the nearest compatible opener on a capped stack,
        recording the consumed delimiter characters on both runs; the cap
        keeps a reply of nothing but openers linear. */
    private static void pairEmphasis(List<int[]> runs) {
        List<int[]> openers = new ArrayList<>();
        for (int[] run : runs) {
            if (run[5] == 1) {
                for (int openerIndex = openers.size() - 1;
                     openerIndex >= 0 && remaining(run) > 0; openerIndex--) {
                    int[] opener = openers.get(openerIndex);
                    if (opener[6] != run[6] || multipleOfThreeBlocked(opener, run)) {
                        continue;
                    }
                    int paired = Math.min(remaining(opener), remaining(run));
                    opener[3] += paired;
                    run[2] += paired;
                    if (remaining(opener) == 0) {
                        openers.remove(openerIndex);
                    }
                }
            }
            if (run[4] == 1 && remaining(run) > 0) {
                openers.add(run);
                if (openers.size() > MAX_PENDING_EMPHASIS_OPENERS) {
                    openers.remove(0);
                }
            }
        }
    }

    private static int remaining(int[] run) {
        return (run[1] - run[0]) - run[2] - run[3];
    }

    /** CommonMark's rule: when both runs can open AND close, they match
        only if the sum of their lengths is not a multiple of 3, or both
        are. */
    private static boolean multipleOfThreeBlocked(int[] opener, int[] closer) {
        int openerLength = opener[1] - opener[0];
        int closerLength = closer[1] - closer[0];
        return (openerLength + closerLength) % 3 == 0
                && openerLength % 3 != 0 && closerLength % 3 != 0;
    }

    /**
     * Closed-list strip pass. Each {@link #CLOSED_LIST} entry is matched
     * via its precompiled {@link #CLOSED_LIST_PATTERNS} pattern (literal
     * words, internal whitespace as {@code \s+}), or, for a flag-bearing
     * entry whose pattern slot is the {@link #FLAG_ENTRY_TOKENIZED}
     * sentinel, via {@link #redactFlagEntry}; every occurrence is replaced with
     * {@value #REDACTED_COMMAND_REPLACEMENT}. Emits no log lines itself —
     * the caller decides observability (the Provider bean emits one
     * aggregated WARN per distinct token via
     * {@link #applyClosedListStripWithMatches(String)}'s match list; tests
     * that want the rewrite without driving any emission use this method).
     */
    public static String applyClosedListStrip(String input) {
        return applyClosedListStripWithMatches(input).rewritten();
    }

    /**
     * Carrier for the closed-list pass: the rewritten text plus the
     * list of matched tokens (one entry per occurrence, in input
     * order). The Provider bean aggregates {@link #matches()} per
     * distinct token and writes one {@code audit_log} row per token
     * carrying the exact occurrence count — the spec's
     * counted-never-throttled promise.
     */
    public record ClosedListStripResult(String rewritten, List<String> matches) {}

    /**
     * Closed-list strip pass that ALSO records the matched tokens for
     * downstream aggregated observability. PURE: emits nothing — the
     * Provider bean emits the WARN log lines from the returned match
     * list, one per distinct matched token carrying the exact occurrence
     * count (so the WARN-vs-row count stays 1:1).
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
    public static ClosedListStripResult applyClosedListStripWithMatches(String input) {
        String current = applyMarkdownLinkStrip(canonicalizeForMatching(input));
        List<String> matches = new ArrayList<>();
        for (int i = 0; i < CLOSED_LIST.size(); i++) {
            String token = CLOSED_LIST.get(i);
            Pattern pattern = CLOSED_LIST_PATTERNS.get(i);
            if (pattern == FLAG_ENTRY_TOKENIZED) {
                // Flag-bearing entry: matched by the parser-mirroring
                // tokenizer.
                current = redactFlagEntry(current, token, matches);
                continue;
            }
            Matcher m = pattern.matcher(current);
            StringBuilder rewritten = null;
            while (m.find()) {
                if (rewritten == null) {
                    rewritten = new StringBuilder();
                }
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
    public static String canonicalizeForMatching(String input) {
        return IngestTextNormalizer.stripBidiAndZeroWidth(
                Normalizer.normalize(input, Normalizer.Form.NFKC));
    }

    /**
     * Aggregate the per-occurrence match list into per-token counts,
     * preserving first-seen order so the WARN stream and the audit
     * rows enumerate tokens in the order they first matched.
     * Aggregation is the DOS bound from docs/spec/security.md §LLM
     * output sanitizer: a field of N repeated tokens costs one WARN
     * line and one audit row, while the exact count keeps the audit
     * signal lossless — no occurrence is suppressed or capped.
     */
    public static LinkedHashMap<String, Integer> aggregateMatchCounts(List<String> matches) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (String token : matches) {
            counts.merge(token, 1, Integer::sum);
        }
        return counts;
    }

}
