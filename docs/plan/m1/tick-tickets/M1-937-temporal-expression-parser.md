---
id: M1-937
title: "Deterministic temporal-expression parser"
status: pending
created: 2026-08-26
last_updated: 2026-08-26
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
  deterministic derives a window from the text. Intended tests
  `to-be-written` (child of a 2+ decomposition, analysis
  docs/plan/m1/tick-analysis/temporal-parse-windowing.md; /tick start
  converts the markers: write the tests, run them RED — the class does not
  compile-exist yet, so the compile failure IS the red state, workflow §0):
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
round_cap: 2
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
  - "Clamp parity, single source (analysis P7): TemporalExpressionParserTest.clampsToTheSearchPostsWindowBounds — \"last 30 minutes\" clamps UP to SearchPostsTool.WINDOW_MIN (PT1H), \"last 45 days\" and \"last 8 weeks\" clamp DOWN to SearchPostsTool.WINDOW_MAX (PT720H); the assertions reference the SHARED constants (widened to package-private in this diff), so a drift in either direction fails compilation or the test — one window vocabulary across surfaces (commands.md §Content, What the window measures)."
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
reviews: []
overrides: []
aborted_attempts: []
reopens: []
clarity_check: {}
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
