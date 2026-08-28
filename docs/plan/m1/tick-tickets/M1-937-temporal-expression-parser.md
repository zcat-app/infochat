---
id: M1-937
title: "Deterministic temporal-expression parser"
status: done
created: 2026-08-26
last_updated: 2026-08-28
flow: tick
reproduction: >-
  Probe (new-class ticket; no test can exist before the parser does — the
  M1-844/M1-859/M1-928 posture): `grep -rn 'TemporalExpressionParser'
  infochat-provider/src/` returns NOTHING, and no temporal-intent parse
  exists anywhere in production — `grep -rn 'today\|yesterday\|Duration.parse'
  infochat-provider/src/main/java/app/zcat/infochat/provider/chat/` returns
  hits only in SearchPostsTool's window clamp (the MODEL-supplied window
  arg), never a query-text parse (verified 2026-08-26). Observed wrong
  behavior (the live instance is M1-927's recorded reproduction): a turn
  asking "in the last 2 hours" grounds on week-old posts because nothing
  deterministic derives a window from the text. Tests landed at start
  (child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/temporal-parse-windowing.md; the /tick start
  marker conversion ran 2026-08-28 — written, run RED as the pinned
  compile failure, then green with the class):
  TemporalExpressionParserTest#parsesExplicitRelativeExpressionsAndNothingElse,
  #clampsToTheSearchPostsWindowBounds,
  #calendarExpressionsAnchorToTheScopeZone,
  #narrowestExpressionWinsWhenSeveralMatch.
analysis_ref: docs/plan/m1/tick-analysis/temporal-parse-windowing.md
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/TemporalExpressionParser.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SearchPostsTool.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/TemporalExpressionParserTest.java
complexity: low
risk: low
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY caller — ChatAgent, SemanticSearchTool, and the pre-fetch are
    M1-938's (this ticket lands a pure class nothing invokes; the grammar
    table it pins is that sibling's contract). No prompt, header, hint,
    SQL, spec, or design file moves here.
  - >-
    Vague-recency inference — "recent", "latest", "top", "new" are
    deliberately NON-matches (analysis P8: over-inference silently hides
    posts; they stay on the M1-916 steering path). "This year" /
    "last year", absolute dates ("on August 20", "since Monday"), and
    number words ("a couple of hours") are non-matches too — recorded
    grammar boundary, not TODOs.
  - >-
    Timezone RESOLUTION — the parser takes a ZoneId PARAMETER; the
    groups.timezone / default-timezone lookup wiring is M1-938's (analysis
    P5). No DB access, no CDI, no config read in this class.
  - >-
    Any LLM in the loop (D19 by construction — regex + java.time only) and
    any change to SearchPostsTool beyond widening WINDOW_MIN/WINDOW_MAX to
    package-private (single clamp source, analysis P7).
acceptance:
  - "REPRODUCTION closed (the class exists and the grammar table passes): TemporalExpressionParserTest.parsesExplicitRelativeExpressionsAndNothingElse — anchored-form inputs \"today\", \"yesterday\", \"this week\", \"this month\", \"last week\", \"last month\", \"what happened in tech news in the last 2 hours?\", \"in the past 3 days\", \"past 24h\", \"over the last 12 hours\", \"world news in last 2h\" each yield a Window with the expected clamped Duration and the matched phrase; the NEGATIVE table — \"recent news\", \"latest headlines\", \"top news\", \"new posts\", \"what happened with qwen\", \"\", \"this year\", \"on August 20\" — yields NO match (analysis P3/P8; a mutation matching vague recency or a year-scale expression fails the negative arm)."
  - "Clamp parity, single source (analysis P7): TemporalExpressionParserTest.clampsToTheSearchPostsWindowBounds — \"last 30 minutes\" clamps UP to SearchPostsTool.WINDOW_MIN (PT1H), \"last 45 days\" and \"last 8 weeks\" clamp DOWN to SearchPostsTool.WINDOW_MAX (PT720H); the assertions reference the SHARED constants (widened to package-private in this diff), so a drift in either direction fails compilation or the test — one window vocabulary across surfaces (commands.md §Content, What the window measures). Round-2 refine (user, 2026-08-28): oversized counts are bounded by VALUE after stripping leading zeros — \"in the last 0000000002 hours\" yields PT2H (not the ceiling), \"last 0000000000000 hours\" clamps up to WINDOW_MIN, unpadded >9-digit runs still take WINDOW_MAX, and parse never throws on any digit run."
  - "Calendar anchoring discriminates the zone (analysis P5): TemporalExpressionParserTest.calendarExpressionsAnchorToTheScopeZone — fixed instant 2026-08-26T09:00:00Z: \"today\" with Zone Europe/Prague yields PT11H (local midnight = 22:00Z prior day) while Zone UTC yields PT9H; \"yesterday\" (Prague) yields the bounding window PT35H (since start of yesterday); \"this week\" anchors at the ISO-Monday week start and \"this month\" at the zone's month start in the same zone — a UTC-hardcoded mutation fails the Prague arm."
  - "Multi-match determinism (analysis P3): TemporalExpressionParserTest.narrowestExpressionWinsWhenSeveralMatch — \"what happened today in the last 2 hours\" yields PT2H (the NARROWEST matched window), not the today window; two expressions yielding EQUAL windows resolve to the first-mentioned (pinned); the rule is code-fixed, never configuration."
  - "Match discipline: case-insensitive with word boundaries — \"Today\", \"TODAY\", and \"today's news\" match; \"hotdays\" does not (TemporalExpressionParserTest cases); the negation limitation (\"not today\" still matches) is asserted as DESIGNED behavior with a comment naming the recorded trade-off (analysis P8: a false positive narrows grounding, never answers wrongly; a false negative is the original defect)."
  - "Purity: the class is a static utility + record result — plain JUnit, no container, no DB, no CDI, no Clock instantiation (now is a PARAMETER; the injected-Clock wiring is M1-938's, engineering-rules §9), no logging of query text (D37) — probe: the file's imports contain no jakarta.enterprise, javax.sql, or org.slf4j token."
  - "Scope fence: git diff --stat names exactly the files_scope paths (the SearchPostsTool hunk is the two-constant visibility widen plus its comment, nothing else) — probe: `git diff infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/SearchPostsTool.java` shows no behavioral change; mvn verify from repo root is green (engineering-rules §5)."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/TemporalExpressionParserTest.java
      — the grammar/clamp/zone/narrowest/boundary tables above (pure JUnit).
  preserves:
    - >-
      all tests currently green on main — SearchPostsToolTest's window-clamp
      tests and every chat suite, unmodified (the visibility widen changes
      no behavior).
spec_refs:
  - docs/spec/commands.md §Content
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D19
  - D29
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
    verdict: REWORK
    checks: "SPEC-TRUTHNESS: PASS; SECURITY: FAIL; TEST-ADEQUACY: WARN; MAINTAINABILITY: PASS; SCOPE: PASS"
    diff_stats: "5 files, +282/-17 (parser +117 new, test +133 new, SearchPostsTool +5/-2, ticket +30/-7, board +12/-12)"
    notes: >-
      1 REWORK item (low, SECURITY): unbounded counted digit runs —
      NumberFormatException past Long.MAX_VALUE, ArithmeticException in
      Duration.ofDays, and 7*n/30*n long wrap landing a corrupted negative
      window that clamp() lifts to WINDOW_MIN; must clamp to WINDOW_MAX and
      never throw (M1-938 feeds raw 500-char user text verbatim). Reviewer
      falsified-and-dropped the @Nullable signature nit (D48: green build
      is the proof) and the minutes-unit divergence (acceptance item 2 is
      the contract — the under-clamp arm is unsatisfiable without
      minutes). 1 RECOMMENDED-NEW-TICKET carried DECIDE-BEFORE: M1-938
      (counted-form leading word boundary — "blast 3 days" matches) —
      relayed to the user. Verdict: .scratch/tick-review-M1-937-r1.txt
  - round: 2
    date: 2026-08-28
    verdict: REWORK
    checks: "SPEC-TRUTHNESS: PASS; SECURITY: PASS; TEST-ADEQUACY: FAIL; MAINTAINABILITY: WARN; SCOPE: PASS"
    diff_stats: "round-2 fix hunks: 3 files, +61/-3 (parser +9/-1, test +10, ticket +42 bookkeeping) vs round-1 baseline 5 files +282/-17 — every dimension shrank"
    notes: >-
      Round-1 REWORK item dispositioned SATISFIED (guard present,
      three named probes green in-suite, log of record newer than every
      staged file). New low FINDING (TEST-ADEQUACY): the >9-DIGIT-LENGTH
      shortcut counts characters, not value — zero-padded small counts
      ("in the last 0000000002 hours", 10 chars, value 2) regress to
      WINDOW_MAX where the round-1 code correctly returned PT2H; fix is
      strip-leading-zeros before the length test plus one assertion.
      Reviewer falsified-and-dropped: 9-digit overflow risk (worst case
      30n days ~2.6e15 s, far under Long.MAX), vacuous test arms (each
      catches a distinct mutation), comment-cap and diff-growth concerns.
      Round cap 2 reached on REWORK → escalated (round-cap). Verdict:
      .scratch/tick-review-M1-937-r2.txt
  - round: 3
    date: 2026-08-28
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS: PASS; SECURITY: PASS; TEST-ADEQUACY: PASS; MAINTAINABILITY: PASS; SCOPE: PASS"
    diff_stats: "round-3 fix hunks: 4 files, +59/-8 (parser +12/-5 zero-strip + comment, test +8 two arms, ticket +45 bookkeeping, board +1/-1); full branch 5 files vs merge-base"
    notes: >-
      Post-refine round (user ruling 2026-08-28, round_cap 3). Round-2
      item dispositioned SATISFIED (strip runs before the length test,
      comment states the value premise, PT2H arm beside the intact
      oversized arms, in-suite green, log of record newer than both code
      files). Reviewer falsified-and-dropped five candidates (all-zero
      non-match vs the refine-amended acceptance; char-count regression
      claim vs strip ordering; 9-digit month overflow arithmetic; minutes
      unit vs the every-clamp-bound wording; must-shrink vs the stats).
      The word-boundary RECOMMENDED-NEW-TICKET was restated carrying the
      standing user ruling (route through /tick analyze before M1-938) —
      no new decision requested. No MAINTAINABILITY rename suggestions.
      Verdict: .scratch/tick-review-M1-937-r3.txt
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  result: pass
  checked: 2026-08-28
  notes: >-
    Lint 0/0. Reproduction probes re-run clean (no TemporalExpressionParser
    hit; Duration.parse on the chat path only at SearchPostsTool:69 and
    ListSavesTool:79 — model-supplied window args, never a query-text
    parse; the ticket's "hits only in SearchPostsTool" enumeration is
    loose about ListSavesTool but the load-bearing claim holds). Spec
    citations verified (commands.md "What the window measures" names the
    chat post-search tool; llm.md Determinism boundary). Pitfalls P3/P5/
    P7/P8 all present. Calendar expectations recomputed at
    2026-08-26T09:00Z (Wednesday, Prague CEST): today PT11H/PT9H,
    yesterday PT35H, this-week PT59H, this-month PT611H. No blocked_by,
    no replaces, no in-flight overlap.
escalation_reason:
---

# M1-937: Deterministic temporal-expression parser

## Context

"What happened in Czech today?" / "world news in last 2h" ground on week-old
posts because nothing deterministic derives a time window from the query text
— the always-on semantic pre-fetch is time-blind and temporal routing rests
on the M1-916 steering strings alone (the failure M1-927 dated-and-disclosed
but did not fix; the user has re-opened deterministic temporal parsing as
binding). This ticket is the pure half of the fix: the parser class that
turns an English-anchored query string into a window `Duration` under the
searchPosts clamp. The wiring (parse site, pre-fetch windowing, model hint)
is M1-938's. Shared analysis: `analysis_ref:` (Ground truth, Pitfalls
P3/P5/P7/P8).

## Root cause

Verified: no temporal-parse code exists anywhere under
`infochat-provider/src/main` (the reproduction probe); the only
`Duration.parse` on the chat path is SearchPostsTool reading the
MODEL-supplied `window` arg (SearchPostsTool.java:68-69) — the query text is
never examined for time expressions. The grammar therefore has to be built,
and it must be built ENGLISH-ONLY: it will consume the anchored query
(M1-746/D58 — translation is language-only per D58 (d), so "dnes"/"últimas 2
horas" arrive as "today"/"last 2 hours"), giving one grammar instead of five
language-specific date-phrase tables.

## Pitfalls

Numbered per the analysis document; this ticket carries P3, P5 (the
parameter half), P7, P8.

- P3: D19 determinism — regex + java.time only, no model, no config knob for
  the tie rule; the multi-match resolution (narrowest wins) must be a fixed
  code rule or "same message → same window" breaks.
- P5: the zone is a PARAMETER here — the parser must not resolve, guess, or
  default it (DM/group resolution is M1-938's); calendar anchoring must be
  tested across a zone boundary or a UTC-hardcoded mutation passes.
- P7: clamp parity — the bounds come from SearchPostsTool's constants
  (widened to package-private), never re-typed literals; one conversation
  must hold one window vocabulary (commands.md §Content, M1-689).
- P8: over-inference — vague recency and year-scale expressions are
  NON-matches by design (they would silently hide posts the user did not
  bound, or mislabel a clamp); the negation blindness ("not today") is an
  accepted, recorded trade-off.

## Approach

Derived from `spec_refs:` — commands.md §Content "What the window measures"
(:512-531) fixes what the produced window MEANS (a ready_at bound, the same
rule as /summary, the digest, and the chat search tool); llm.md
§Determinism boundary (:463-480) fixes HOW it may be derived (deterministic
code; the LLM never picks the set — and windowed SQL is that section's own
determinism exemplar).

- **Files to touch:** `files_scope` — the new parser, the two-constant
  visibility widen in SearchPostsTool, the plain-JUnit table.
- **Pre-decided shapes (implementation is execution):**
  1. `TemporalExpressionParser` (chat/tool/, package-private static utility;
     javadoc citing D19 and the analysis path):
     `static Optional<Window> parse(String anchoredQuery, ZoneId zone,
     Instant now)`; `record Window(Duration window, String phrase)` —
     `window` is the CLAMPED duration, `phrase` the matched expression text
     (M1-938 interpolates it into the fold header and hint). Blank/null-safe
     no-op miss.
  2. Grammar (case-insensitive, word-boundary, first-table-wins on equal
     windows, narrowest-window-wins overall):
     - counted: `(?:in|within|over|during)?\s*(?:the\s+)?(?:last|past|previous)\s+(\d+)\s*(hours?|hrs?|h|days?|d|weeks?|w|months?|mo)\b`
       — N ≥ 1 digits only; unit → Duration.ofHours/Days/Weeks/Months
       (months = 30 days, matching the clamp vocabulary);
     - calendar: `\btoday\b` → Duration.between(startOfDay(zone), now);
       `\byesterday\b` → Duration.between(startOfDay(now-1d), now) (the
       bounding window — over-inclusive by design, the hint phrases it
       "since yesterday"); `\bthis week\b` → since ISO-Monday week start in
       zone; `\bthis month\b` → since month start in zone; `\blast week\b` →
       PT7D rolling; `\blast month\b` → PT30D rolling (colloquial reading,
       equals the clamp max).
     - clamp every result to `[SearchPostsTool.WINDOW_MIN,
       SearchPostsTool.WINDOW_MAX]`.
  3. SearchPostsTool: `WINDOW_MIN`/`WINDOW_MAX` private → package-private
     (comment names the shared-source rule, analysis P7); NOTHING else in
     the file moves.
- **Steps:** (1) write the four `to-be-written` tests + the boundary cases
  (RED: the class does not exist); (2) land the parser + the visibility
  widen; (3) full `mvn verify`.
- **Controls to preserve (§10):** nothing is rerouted yet — this diff ADDS a
  class and widens two constants; SearchPostsToolTest's clamp tests and all
  chat suites must pass unmodified (the fence probe).
- **Pitfall→mitigation:** P3→the narrowest/equal-window rule + the pure-shape
  probe; P5→zone-as-parameter + the Prague/UTC discrimination; P7→shared
  constants referenced in the assertions; P8→the negative table + the
  recorded "not today" case.

## Definition of done

The grammar/clamp/zone/narrowest/boundary tables pass (hits, negatives,
both clamp directions against the SHARED constants, Prague-vs-UTC calendar
anchoring, the narrowest rule, case/boundary discipline with the negation
limitation recorded); the parser is pure (no CDI/DB/Clock-instantiation —
the import probe); the SearchPostsTool diff is exactly the visibility widen;
the diff names exactly files_scope; `mvn verify` green from the repo root.

## Verification

- Reproduction → acceptance item 1 (the positive+negative grammar table; a
  vague-recency or year-scale mutation fails the negative arm).
- P3 → items 1 and 4 (the narrowest rule and equal-window tie are pinned;
  a first-match-only mutation fails "today in the last 2 hours").
- P5 → item 3 (Prague 09:00Z-local-midnight discrimination; a UTC-hardcode
  fails).
- P7 → item 2 (assertions reference the shared constants; a re-typed literal
  drift fails when either constant moves).
- P8 → item 1's negative table and item 5's boundary cases (including the
  asserted-as-designed "not today" limitation).
- Purity → item 6's import probe.
- Scope fence → item 7's git-diff probe + mvn verify.
- FAILURE-MODE coverage → the clamp arms (under/over), the negative grammar
  arm, the boundary-token arm ("hotdays"), and the blank/empty input arm —
  each feeds hostile/edge input to this diff's own production code.

## Out-of-scope

Named in `out_of_scope:` — no caller (M1-938 owns ChatAgent,
SemanticSearchTool, the header/hint, and every spec/design edit); no
vague-recency, year-scale, absolute-date, or number-word grammar (recorded
boundary, analysis P8); no timezone resolution (parameter only, P5); no LLM
in the loop (D19); nothing in SearchPostsTool beyond the visibility widen.
No pre-existing test is modified.

## Census

Not class-scoped: one new pure class, no existing site of the shape to
enumerate (the reproduction probe is the census — zero temporal-parse sites
exist).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-937-temporal-expression-parser.md
```

## Round 1 rework

Verbatim from the round-1 verdict (`.scratch/tick-review-M1-937-r1.txt`):

1. FINDING 1: bound the counted magnitude in TemporalExpressionParser.collectCounted
   /durationOf (TemporalExpressionParser.java:61-79) so oversized digit runs
   and over-max counts clamp to SearchPostsTool.WINDOW_MAX instead of throwing
   (NumberFormatException at :64, ArithmeticException inside Duration.ofDays)
   or wrapping (7 * n / 30 * n long overflow at :73/:76), evaluated via
   TemporalExpressionParserTest: parse("in the last 99999999999999999999 hours"),
   parse("last 999999999999999 days"), and parse("last 2635249153387078802 weeks")
   each return a present Window with window() == SearchPostsTool.WINDOW_MAX
   (the third must not return WINDOW_MIN), no exception, mvn verify green.

## Review observations

- Round 1 RECOMMENDED-NEW-TICKET (counted-form leading word boundary:
  "blast 3 days" / "inlast 2 hours" match inside larger words; the calendar
  arm already carries the `\b` discipline) carried `DECIDE-BEFORE: M1-938`.
  Relayed to the user 2026-08-28; ruling: route through `/tick analyze`
  (compare with existing in-tree boundary methods) before M1-938 starts —
  no ticket filed yet. Checked against the 2026-08-27/28 night handoff
  (M1-945 embedding-measurement campaign): surfaces are disjoint (eval
  lane + frozen test DB vs a plain-JUnit regex change) — no sequencing
  interaction with the pending shadow-eval or the M1-946/947 window.

## Round 2 rework

Verbatim from the round-2 verdict (`.scratch/tick-review-M1-937-r2.txt`):

1. FINDING 1: in TemporalExpressionParser.collectCounted, strip leading zeros
   from the captured digit run before the >9-digit shortcut
   (TemporalExpressionParser.java:67) and amend the comment at :64-66 to
   match, evaluated via TemporalExpressionParserTest.
   clampsToTheSearchPostsWindowBounds asserting parse("in the last 0000000002
   hours") returns PT2H with the three existing oversized arms still
   asserting SearchPostsTool.WINDOW_MAX, and a green mvn verify.

## Round 2 refine (user)

Escalation ruling 2026-08-28, refine arm: the zero-padding regression was
INTRODUCED by this ticket's round-2 fix (the round-1 value-based code read
the number correctly), so the strip-leading-zeros fix belongs in THIS
ticket rather than a successor — the corner case is improbable
(zero-padded counts, extreme magnitudes) but the fix is two production
lines plus assertions. Amendments: acceptance item 2 extended (value-based
bound after zero-strip); round_cap 2 → 3 with this authorization recorded
here. The commit message carries the refine reason.
