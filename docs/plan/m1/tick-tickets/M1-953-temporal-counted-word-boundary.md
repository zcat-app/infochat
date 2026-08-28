---
id: M1-953
title: "Counted temporal form requires a left word boundary"
status: done
created: 2026-08-28
last_updated: 2026-08-28
flow: tick
reproduction: >-
  TemporalExpressionParserTest#countedFormRequiresALeftWordBoundary
  (`to-be-written`: /tick start writes it and runs it RED against the merged
  pattern before any fix code — plain JUnit; the test class exists).
  Observed wrong behavior (mechanical trace of the merged COUNTED pattern,
  TemporalExpressionParser.java:23-26, re-verified 2026-08-28 — the pattern
  has no left-edge boundary and its optional prefix joins with `\s*`, which
  admits zero whitespace, so Matcher.find() (:63) anchors matches at any
  substring offset):
  TemporalExpressionParser.parse("blast 3 days", ZoneOffset.UTC,
  Instant.parse("2026-08-26T09:00:00Z")) returns
  Optional[Window[PT72H, "last 3 days"]] — the keyword "last" matches
  INSIDE the larger word "blast"; and parse("inlast 2 hours", UTC, <same
  now>) returns Optional[Window[PT2H, "inlast 2 hours"]] — the prefix "in"
  fuses with the keyword through the zero-whitespace junction. Expected:
  NO match for either — the counted form requires a word boundary on its
  left edge, exactly like the calendar expressions in the same file
  (\btoday\b; "hotdays" does not match, pinned today by
  TemporalExpressionParserTest.parsesExplicitRelativeExpressionsAndNothingElse,
  test line :70). Origin: M1-937's round-1 and round-3 review verdicts both
  carried this as RECOMMENDED-NEW-TICKET (DECIDE-BEFORE: M1-938); user
  ruling 2026-08-28 (M1-937 §Review observations): land BEFORE M1-938
  starts — its fixtures pin this grammar table as their contract.
analysis_ref: self
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/TemporalExpressionParser.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/TemporalExpressionParserTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY caller, spec, design, or catalog edit — ChatAgent, the pre-fetch
    windowing, the hint, the security.md/commands.md amendments are
    M1-938's lane; the parser has ZERO production callers today (grep
    'TemporalExpressionParser' over the repo returns only the class, its
    test, the SearchPostsTool.java:33 comment, and the M1-937/M1-938
    tickets — verified 2026-08-28). No prompt, header, hint, SQL, or
    eval-lane file moves here.
  - >-
    Grammar extension of any kind: no new forms, no negation reading
    (P8 recorded trade-off), no change to the calendar arm (it already
    carries the \b discipline — touching it is scope drift, §1), and no
    Unicode-aware edge constructs (a `(?<![\p{L}\p{Nd}])` lookbehind for
    one arm alone would make the grammar internally inconsistent; if ever
    wanted it is a uniform both-arms decision for a follow-up ticket —
    see P5).
  - >-
    Anything inside collectCounted's magnitude/zero-strip guard
    (TemporalExpressionParser.java:64-71) or the clamp (:117-121): the
    pattern change must leave group(1)=digits and group(2)=unit identical,
    so the oversized-count and zero-padded arms of
    clampsToTheSearchPostsWindowBounds stay green untouched.
  - >-
    The eval lane (M1-928 fixtures / M1-929 harness / M1-930 baseline):
    the harness drives SemanticSearchTool.execute({query}) on the fused
    path only (M1-932's ticket records M1-929's runner "executes the
    semanticSearch fused path only"); no eval fixture names the parser or
    its grammar — no interaction, verified by grep over docs/plan/m1.
  - >-
    M1-938's files and its blocked_by/board state: this ticket lands
    BEFORE M1-938 starts (driver-enforced sequencing; this ticket edits
    nothing but its own two files).
acceptance:
  - "REPRODUCTION closed: TemporalExpressionParserTest.countedFormRequiresALeftWordBoundary — parse(\"blast 3 days\", UTC, NOW) and parse(\"inlast 2 hours\", UTC, NOW) each return NO match (Optional.empty()); both arms are RED today (each returns a Window — the trace in reproduction:). FAILURE-MODE coverage: these are hostile mid-word and fused-prefix forms fed to this diff's own production pattern; a mutation dropping the leading \\b fails the blast arm, a mutation reverting the prefix junction to the zero-whitespace-admitting \\s* shape fails the inlast arm."
  - "Surviving-form parity (the tighten removes substring matches ONLY — the P8 doctrine inverted: a grammar that over-tightens recreates the original defect class, a false negative), pinned by TemporalExpressionParserTest.countedFormRequiresALeftWordBoundary: parse(\"sin the last 2 hours\", UTC, NOW) yields Window[PT2H, \"the last 2 hours\"] (the match starts at the standalone `the`, NOT at the `in` inside `sin` — pins that the match start moved to the word boundary; today the returned phrase is \"in the last 2 hours\" starting inside `sin`) and parse(\"thin last 3 days\", UTC, NOW) yields Window[PT72H, \"last 3 days\"] (a word merely ending in the prefix letters `in` never suppresses the standalone keyword; today the returned phrase is \"in last 3 days\")."
  - "Green-table identity: all four existing TemporalExpressionParserTest methods (parsesExplicitRelativeExpressionsAndNothingElse, clampsToTheSearchPostsWindowBounds, calendarExpressionsAnchorToTheScopeZone, narrowestExpressionWinsWhenSeveralMatch) pass UNMODIFIED — no currently-matching input's window or phrase changes (the new pattern's match set is a strict subset of the old; enumerated arm-by-arm in Root cause); probe: git diff over TemporalExpressionParserTest.java shows ADDITIONS ONLY (the new @Test method), no hunk inside the existing four methods (engineering-rules §8: no pre-existing test is modified, so no test-modification authorization is needed)."
  - "Grammar groups unchanged: every group the new COUNTED adds is non-capturing — collectCounted still reads group(1)=digits (:67, :71) and group(2)=unit (durationOf), pinned by TemporalExpressionParserTest.clampsToTheSearchPostsWindowBounds passing UNCHANGED: its oversized-count arms (:101-106) and zero-padded arms (:111-114) read the digit and unit groups through the new pattern, so a capturing-group mutation fails them or compilation, proving the magnitude guard and clamp paths are untouched (P4)."
  - "Scope fence: git diff --stat names exactly the two files_scope paths (no calendar-arm hunk, no caller, no eval path — P5); mvn verify from the repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/TemporalExpressionParserTest.java
      — countedFormRequiresALeftWordBoundary (plain JUnit): the two
      no-match arms (blast/inlast) plus the two surviving-form arms
      (sin/thin) of acceptance items 1-2.
  preserves:
    - >-
      all tests currently green on main — the four existing
      TemporalExpressionParserTest methods, SearchPostsToolTest's clamp
      arms, and every chat suite (the parser has no caller, so nothing
      downstream can shift), unmodified.
spec_refs: []
decision_refs:
  - D19
decomposed_from:
replaces:
replaced_by:
deferred_on:
deferred_reason:
abandoned_reason:
spec_amend_for:
spec_amend_parent:
remediates:
reviews:
  - round: 1
    date: 2026-08-28
    verdict: APPROVE
    checks: SPEC-TRUTHNESS-CHECK PASS, SECURITY-CHECK PASS, TEST-ADEQUACY-CHECK PASS, MAINTAINABILITY-CHECK PASS, SCOPE-CHECK PASS
    diff_stats: 4 files, +453/-5 (parser +4/-1 pattern+comment, test +17 additions-only new @Test, ticket +428 new, board +4/-4 regen)
    notes: >-
      0 rework, 0 critical/high; reviewer falsified-and-dropped 3
      candidate findings (Unicode \b residual — defeated by the P5
      parity ruling; surviving-arm phrase change — in-scope, acceptance
      item 2's contract; regex DoS watchdog — no nested quantifiers,
      zero production callers); RED log verified (assertion failure,
      not compile error); verdict artifact
      .scratch/tick-review-M1-953-r1.txt; no naming suggestions. Gate
      spawn deviation recorded in .scratch/tick-mech-M1-953-r1.txt
      (nested `opencode run` cannot start a server from inside the
      interactive session; Task-tool routing with the standard stub,
      per harness-mapping's documented fallback).
overrides: []
aborted_attempts: []
reopens: []
clarity_check: >-
  start 2026-08-28: lint 0 BLOCKERs (1 WARN: spec_refs empty — legal for a
  defect ticket whose contract is its reproduction). All file:line
  citations re-verified by read: COUNTED :23-26, calendar arm :28-33,
  find() :63, guard :64-71, clamp :117-121, hotdays test :70, clamp arms
  :94-96/:101-106/:111-114, multi-match :142-149, SearchPostsTool
  WINDOW_MIN/MAX :35-36 package-private with the :33 comment. Census
  re-ran clean (every Pattern.compile site in infochat-*/src/main has a
  row, incl. FetchScheduler:777 URL shape and the four ^-anchored
  WINDOW_PATTERN parses). Caller grep re-verified: class + test +
  SearchPostsTool.java:33 comment only. No replaces:, no superseded
  worktree for this surface, blocked_by empty. No ambiguity.
escalation_reason:
---

# M1-953: Counted temporal form requires a left word boundary

## Context

M1-937 (merged) landed the deterministic temporal-expression parser; both
its round-1 and round-3 review verdicts carried the same
RECOMMENDED-NEW-TICKET: the COUNTED grammar has no left word boundary, so
`parse("blast 3 days", …)` returns `Window[PT72H, "last 3 days"]` (the
keyword matches inside a larger word) and `parse("inlast 2 hours", …)`
returns `Window[PT2H, "inlast 2 hours"]` (the optional prefix fuses with
the keyword — the pattern's `\s*` after the prefix admits zero
whitespace). The calendar expressions in the same file already carry the
discipline (`\btoday\b`; "hotdays" is pinned as a non-match). The user
ruled 2026-08-28: analyze against existing in-tree boundary methods and
land the tighten BEFORE M1-938 starts — M1-938 (pending, not started,
`reviews: []`) pins its fixtures against this grammar table as its
contract, so tightening after M1-938 means re-touching its fixtures. This
ticket is that analysis and that tighten: single change, `analysis_ref:
self`. The defect is invisible to users today only because the parser has
zero production callers; M1-938 feeds adapter-inbound user text (≤500
chars, English-anchored) into it verbatim.

## Root cause

Verified against the merged code (all line numbers current checkout):

- **The pattern.** `TemporalExpressionParser.COUNTED`
  (TemporalExpressionParser.java:23-26) is
  `(?:in|within|over|during)?\s*(?:the\s+)?(?:last|past|previous)\s+(\d+)\s*(hours?|hrs?|h|days?|d|weeks?|w|months?|mo|minutes?|mins?)\b`
  under `CASE_INSENSITIVE`. Two boundary defects, one internal
  inconsistency: (a) NO `\b` on the left edge, while the calendar arm
  (:28-33) compiles `\btoday\b` etc. through the same `calendar()` helper;
  (b) the optional prefix joins with `\s*` — zero whitespace admitted —
  while the `the` junction in the SAME pattern already demands real
  whitespace (`the\s+`). `collectCounted` matches with `m.find()` (:63),
  which anchors at any substring offset, so both defects are reachable.
- **The traces.** "blast 3 days": find() succeeds starting at index 1 —
  prefix empty, `\s*` empty, `the`-group empty, keyword "last" matches
  inside "blast", digits "3", unit "days" + `\b` → `Window[PT72H,
  "last 3 days"]`. "inlast 2 hours": prefix "in", `\s*` empty, keyword
  "last" fused on → `Window[PT2H, "inlast 2 hours"]`. These reproduce the
  verdicts' WRONG outputs exactly (verified by trace, not execution — the
  analyst writes no code; the RED test at start is the executable proof).
- **Why a leading `\b` ALONE is insufficient (the brief's first question,
  falsified).** `\b(?:in|within|over|during)?\s*(?:the\s+)?…` on "inlast 2
  hours": the `\b` holds at the string start (before `i`), the prefix
  matches "in", the zero-whitespace `\s*` matches empty, "last" matches —
  the fused input STILL matches. The prefix must carry its own
  real-separator requirement (see Approach). The brief's suggested shape
  `\b(?:in|within|over|during)?\s*…` is therefore falsified by mechanism.
- **No green input regresses (the brief's third question, enumerated).**
  Every currently-matching counted arm in
  `TemporalExpressionParserTest` is whitespace-separated English prose:
  "in the last 2 hours" (:55-56), "in the past 3 days" (:57-58),
  "past 24h" (:59), "over the last 12 hours" (:60-61), "world news in
  last 2h" (:62-63), the clamp arms (:94-96, :101-106, :111-114), and the
  multi-match arms (:142-143, :148-149 — "the past 7 days" matches through
  the `the\s+` junction, unchanged). None has a prefix or keyword
  immediately preceded by a word character, and none relies on zero
  whitespace between prefix and keyword. The new pattern's match set is a
  STRICT subset of the old (it only adds constraints: a zero-width `\b`
  and moving the prefix's `\s*` to `\s+` inside the optional group — any
  span the new pattern matches, the old matched too), so every existing
  non-match stays a non-match and no surviving match's span, phrase, or
  window changes. Note "thelast 3 days" is ALREADY a non-match today
  (`the\s+`), which is the internal-consistency argument for the prefix
  fix: the grammar's own `the` junction states the intended rule.
- **Single ticket is right (the brief's fourth question).** The parser has
  no production caller (grep cited in `out_of_scope`); the eval lane never
  invokes it (M1-929's runner drives the semanticSearch fused path only);
  M1-932 (pending) touches searchPosts's text param and the catalog, not
  the parser. No sibling surface constrains this change — decomposition
  would be one ticket plus an empty one.

## Pitfalls

- P1: **A leading `\b` alone does not fix the fused-prefix class.**
  "inlast 2 hours" survives `\b(?:in|…)?\s*…` (Root cause trace). The
  prefix must carry `\s+` INSIDE the optional group. Why it bites: the
  obvious one-token fix greens the blast arm while the inlast arm stays
  red, and the round is burned on a partially-correct shape.
- P2: **Over-tightening recreates the original defect (the P8 doctrine
  inverted — a false negative is the defect M1-937 exists to fix).**
  Concrete over-tight shapes, each falsified: a whitespace lookbehind
  `(?<=\s)` instead of `\b` breaks start-of-string "past 24h" and any
  punctuation-adjacent form ("(in the last 2 hours)"); making the prefix
  or the `the` mandatory breaks "past 24h" and "world news in last 2h";
  anchoring `^` breaks mid-sentence matches ("world news in last 2h"
  pins that expressions occur mid-sentence). The contract is PARITY with
  the calendar arm's `\b` construct — nothing stronger.
- P3: **Sequencing / fixture calibration (the M1-785 lesson applied
  forward).** M1-938 will pin its fixtures against the grammar table this
  ticket leaves behind. This ticket must land BEFORE M1-938 starts, and
  its negative rows are the END-state rows M1-938 inherits
  ("blast"/"inlast"-class inputs are currently "matching" but their
  expected end-state is non-match). Landing after M1-938 means re-touching
  its fixtures.
- P4: **Group-numbering trap.** The re-grouped prefix must stay
  NON-CAPTURING: a capturing paren around the prefix shifts digits/unit to
  groups 2/3 and silently breaks `collectCounted` (:67, :71) and
  `durationOf`'s unit read — symptomless until the clamp table runs. Same
  family: the match span (`m.group()`) of every surviving input must be
  byte-identical (strict subset only — no span shifts on backtracking).
- P5: **Do not "improve" beyond parity.** Whether java.util.regex `\b`
  treats non-ASCII letters as word characters is not pinned by any
  in-tree test, and this ticket deliberately does not pin it: the contract
  is the calendar arm's exact construct, so whatever `\b` does for
  `\btoday\b` it now does for the counted form identically — one uniform
  property across the grammar. The in-tree precedent for a stronger,
  Unicode-aware left edge exists (`LlmOutputSanitizerCore.CONFIG_KEY_TOKEN`'s
  `(?<![\\p{L}\\p{Nd}])` lookbehind, LlmOutputSanitizerCore.java:872-873)
  and is recorded here as the FOLLOW-UP precedent for a uniform both-arms
  decision — adopting it for one arm here would make the grammar
  internally inconsistent (scope drift, §1; grammar extension,
  `out_of_scope`).
- P6: **§8 fence: additions only.** The new arms go in a NEW @Test method;
  do not append rows to the existing four methods' tables. A pre-existing
  test edit needs plain-language §8 authorization this ticket avoids
  needing entirely — and the unmodified green table is itself the
  over-tightening detector (P2), so editing it would also weaken the
  fence.

## Approach

Defect ticket: `spec_refs:` is legally empty — the template's own rule
("legally EMPTY on a defect ticket, whose contract is its
`reproduction:`", docs/process/tick-ticket-template.md `spec_refs`) — and
no spec text describes the parser yet (the temporal-parse spec sentences
are M1-938's amendments; commands.md §Content "What the window measures"
(:512-531) defines what a MATCHED window means and is untouched by a
match-set shrink). The
governing contracts are M1-937's merged grammar table (its acceptance
item 5 pins the `\b` match discipline for the calendar forms; nothing in
it pins any counted substring match — the tighten violates no merged
acceptance), the analysis P8 doctrine
(docs/plan/m1/tick-analysis/temporal-parse-windowing.md), and D19: the
derivation stays regex + java.time, deterministic.

- **Files to touch:** exactly `files_scope` — the pattern constant in
  `TemporalExpressionParser.java`, one new @Test method in
  `TemporalExpressionParserTest.java`.
- **Pre-decided shape (implementation is execution):** COUNTED becomes
  `\\b(?:(?:in|within|over|during)\\s+)?(?:the\\s+)?(?:last|past|previous)\\s+(\\d+)\\s*"
  + "(hours?|hrs?|h|days?|d|weeks?|w|months?|mo|minutes?|mins?)\\b"`
  (`CASE_INSENSITIVE` unchanged). Two deltas: (a) a leading `\\b` before
  the whole optional-prefix cluster — kills the mid-word keyword class
  ("blast 3 days") AND the mid-word prefix class (old behavior also
  matched "in the last 2 hours" starting inside "sin the last 2 hours";
  new behavior matches "the last 2 hours" from the standalone `the`);
  (b) the optional prefix carries its own `\\s+` INSIDE the group — kills
  the fused-prefix class ("inlast 2 hours": the prefix alternative can
  only match before real whitespace, and once it backtracks to empty the
  leading `\\b` forbids the keyword from starting mid-word). Every added
  group is non-capturing (P4). The trailing unit `\\b`, the digits/unit
  groups, the clamp, and `collectCounted`'s magnitude/zero-strip guard are
  untouched. Add ONE brief comment above the pattern stating the junction
  rule (the prefix joins with real whitespace, mirroring `the\\s+`, so a
  fused prefix cannot match — §11: it guards a real trap; do not retell
  history).
- **Prior art evaluated — falsified or adopted, never copied (the brief's
  binding comparison):**
  1. *Calendar arm in the same file* (`\btoday\b`, :28-33): ADOPTED as the
     edge construct (parity is the contract); FALSIFIED as sufficient
     alone for the prefix cluster (P1 trace).
  2. *LlmOutputSanitizer word-boundary command matching (M1-073 lineage;
     commit 385a74e5 itself unreadable in this analysis session — no git
     tool — so it was evaluated through its in-tree ticket
     (docs/plan/m1/tickets/M1-073-sanitizer-word-boundary.md, read this
     session: the origin was the INVERSE failure class — false-positive
     redaction of "/bandwidth" inside benign output — fixed by a trailing
     lookahead) and its living descendant `LlmOutputSanitizerCore.java`,
     read end to end with its recorded rationale, which cites the M1-676/
     M1-680 red-team rounds):* its closed-list patterns use a TRAILING
     lookahead `(?=$|[^a-zA-Z0-9\\-])` with deliberately NO leading
     boundary — because `/` is the natural copy-paste start and redaction
     BREADTH is preferred (missing a strip is the security failure;
     `foo/list-sources --all` IS redacted). FALSIFIED as a transfer: the
     temporal parser detects user INTENT, where a fused "inlast" is not a
     recency phrase at all and the user ruling demands non-match; there is
     no "natural start symbol" analog. Its hyphen-including token class
     serves flag tokens (`--all`) no temporal token has. What IS adopted
     from it: the multi-word JUNCTION rule — closed-list multi-word
     entries join with `\\s+` precisely so fused forms cannot match
     (compileClosedListPattern, LlmOutputSanitizerCore.java:247-251) —
     the exact rule COUNTED's prefix was missing; and its
     parser-mirroring-tokenizer shape (redactFlagEntry) is rejected here
     as over-engineering (its linear-time/evasion argument is about
     unbounded lazy regex re-anchoring on attacker-influenced output; a
     bounded pattern over ≤500-char text has no such vector — §7 spirit).
  3. *Stage1RegexSet* (infochat-collector, :100-151): `\\b`-delimited
     ASCII keyword clusters over feed text — corroborates `\\b`-edged
     keyword clusters as the house style for user/feed-text matchers; no
     optional-prefix cluster exists there to copy.
  4. *CONFIG_KEY_TOKEN's Unicode lookbehind* — recorded as the follow-up
     precedent (P5), not adopted.
- **Steps, in implementation order:** (1) write
  `countedFormRequiresALeftWordBoundary` and run it RED (both hostile arms
  FAIL today by returning Windows — assertion failure, not compile error);
  (2) apply the two-delta pattern change + the one-line junction comment;
  (3) `mvn verify` from the repo root. Order rationale: the RED test is
  the reproduction's executable form; the pattern change is two tokens.
- **Controls to preserve (engineering-rules §10):** the change reroutes
  nothing — it removes matches inside a pure, caller-less function. The
  controls that must survive verbatim: the shared clamp constants
  (SearchPostsTool.java:35-36, package-private — M1-937/M1-689 one-window
  rule), `collectCounted`'s magnitude/zero-strip guard on group(1)
  (:64-71), the calendar arm untouched (:28-33), and the four existing
  test methods unmodified (they pin the clamp, zone, narrowest, and
  boundary disciplines). Pinning tests: the item-preserves list.
- **Alternatives considered (rejected, recorded for the commit message):**
  leading `\\b` only — falsified by "inlast" (P1); `(?<=\\s|^)`
  lookbehind edge — over-tight, breaks "past 24h" at start-of-string and
  punctuation-adjacent forms (P2); Unicode lookbehind edge for the counted
  arm — grammar-internal inconsistency, follow-up precedent only (P5);
  tokenizer rewrite a la redactFlagEntry — over-engineering, no
  linear-time argument applies; anchoring `^` — breaks mid-sentence
  expressions pinned green.

## Definition of done

`countedFormRequiresALeftWordBoundary` passes: "blast 3 days" and
"inlast 2 hours" yield NO match; "sin the last 2 hours" yields
`Window[PT2H, "the last 2 hours"]` and "thin last 3 days" yields
`Window[PT72H, "last 3 days"]`. All four existing test methods pass
UNMODIFIED (additions-only diff on the test file; every oversized-count
and zero-padded arm green — the groups and guard are intact). The
production diff is the pattern constant plus one comment line; git diff
--stat names exactly the two files_scope paths. `mvn verify` green from
the repo root. The ticket lands before M1-938 starts (driver-enforced;
its negative rows are M1-938's inherited contract).

## Verification

- Reproduction → acceptance item 1 (both hostile arms RED today, green
  after; each names the mutation it catches — dropped `\\b`, reverted
  junction).
- P1 → the inlast arm (a leading-`\\b`-only fix leaves it red).
- P2 → acceptance item 2's surviving arms (sin/thin) + item 3's unmodified
  green table ("past 24h" start-of-string, "world news in last 2h"
  mid-sentence prefix-less/prefix-ful forms — any over-tight shape fails
  one of them).
- P8 (the shared analysis's over-inference doctrine, invoked by P2) → BOTH
  its axes pinned by named arms of
  TemporalExpressionParserTest.countedFormRequiresALeftWordBoundary: the
  blast/inlast arms are the no-false-POSITIVE axis (a mid-word or fused
  form never windows — the tighten), the sin/thin arms the
  no-false-NEGATIVE axis (a standalone expression still matches at its
  word boundary — the doctrine's false-negative clause is the original
  defect class, so the surviving-form arms are as load-bearing as the
  non-match arms).
- P3 → the sequencing note + driver/board discipline; the ticket's own
  negative rows are the END-state grammar M1-938's fixtures pin against.
- P4 → acceptance item 4 (the existing clamp/zero-strip arms green
  unchanged prove group(1)/group(2) intact; a capturing-group mutation
  fails them or compilation).
- P5 → acceptance item 5's fence probe (no calendar-arm hunk, no
  lookbehind) — the Unicode edge is a RECORDED residual, deliberately
  unpinned (parity with the calendar arm's construct).
- P6 → acceptance item 3's additions-only probe (§8-clean by
  construction).
- FAILURE-MODE coverage → items 1 and 2 feed the diff's own production
  pattern hostile inputs (mid-word keyword, fused prefix, prefix inside an
  unrelated word) and assert the protected behavior (non-match /
  correctly-sited match); the existing negative table (vague recency,
  year-scale, absolute dates) stays green via item 3.

## Out-of-scope

Named in `out_of_scope:` — no caller/spec/design/catalog/eval-lane edit
(all M1-938's lane or later; the parser is caller-less); no grammar
extension (new forms, negation reading, Unicode-aware edges, calendar-arm
changes); no touch to the magnitude/zero-strip guard or clamp; no M1-938
file or board edit beyond this ticket's own landing. This ticket modifies
NO pre-existing test — coverage is added as one new @Test method
(additions-only, §8-clean).

## Census

Class census (the defect class: word-token keyword matching over free
user/feed/model text without a left-edge discipline). Re-runnable probe:
`grep -rn 'Pattern\.compile' --include='*.java' infochat-*/src/main`,
keeping the word-token matchers; every returned site disposed:

| Site | Disposition |
|---|---|
| `TemporalExpressionParser.COUNTED` (:23-26) | **FIX — this ticket** (the only site in the class) |
| `TemporalExpressionParser` calendar arm (:28-33) | Already `\\b`-edged both sides — the parity target, untouched |
| `LlmOutputSanitizerCore` closed-list patterns (`compileClosedListPattern`, :240-252) | Deliberate discipline: no leading boundary + trailing lookahead `(?=$|[^a-zA-Z0-9\\-])` — redaction breadth (M1-676/M1-680 history in its javadoc); correct for its surface, not a defect; its `\\s+` junction rule is the one thing adopted here |
| `LlmOutputSanitizerCore.CONFIG_KEY_TOKEN` (:872-873) | Unicode lookbehind leading edge — redaction-grade; recorded as the uniform-grammar follow-up precedent (P5), not transferred |
| `LlmOutputSanitizerCore` SCAFFOLDING_MARKER / MARKDOWN_LINK / BARE_URL_SPAN / flag tokenizer | Literal/syntax-anchored or parser-mirroring token scans — not word-token keyword matching; no boundary discipline applicable |
| `Stage1RegexSet` (collector eval, :100-151) | `\\b`-delimited ASCII keyword clusters — already disciplined |
| All other `Pattern.compile` sites (Redactor secret shapes; UUID/URL/JSON-key/markup/tag-slug shapes in codecs, listeners, TagNormalizer×2, FetchScheduler, DisplayHeadline, Stage1BodyRemediationJob, TaggerWorker, AssetEnableCommandHandler alias shape; `^`-anchored WINDOW_PATTERN arg parses ×4; LlmTranslationProvider prompt slot; TranslationPipeline language-code shape; ChatAgent tool-call syntax markers) | Shape/syntax/validation-anchored, not word-token semantics — out of the class |

Discrepancy/honesty notes: `docs/plan/m1/redteam/` (cited by the
prior-art block as "no temporal/parser findings") does not exist in this
checkout — the none-found conclusion holds a fortiori; commit 385a74e5
was evaluated through its living descendant (see Approach); the WRONG
outputs are trace-verified, with the RED test as the executable
confirmation.

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-953-temporal-counted-word-boundary.md
```
