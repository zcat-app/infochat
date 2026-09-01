---
id: M1-961
title: "Extend temporal grammar for digit-less day-scale phrases"
status: done
created: 2026-08-31
last_updated: 2026-09-01
flow: tick
reproduction: >-
  TemporalExpressionParserTest#dayScalePhrasesParseToARollingDayWindow
  (written and run RED at /tick start 2026-09-01 before any fix code, the
  M1-953 marker discipline: module-scoped unfiltered provider suite, 2119
  tests, exactly this method failing — "expected a match for <security
  news from the past day>", Optional.empty as predicted; worktree log
  .scratch/tick-red-M1-961.log). Observed wrong behavior (mechanical trace of the
  merged grammar, re-verified 2026-08-31):
  TemporalExpressionParser.parse("security news from the past day",
  ZoneOffset.UTC, Instant.parse("2026-08-26T09:00:00Z")) returns
  Optional.empty — COUNTED requires a digit between keyword and unit
  ((\d+), TemporalExpressionParser.java:27) and no calendar token matches
  (:31-36 — today/yesterday/this week/this month/last week/last month), so
  a chat user asking "security news from the past day" or "environment news
  from the past day" gets an UNWINDOWED deterministic pre-fetch
  (ChatAgent.java:940-942 dispatches exactly {query} on a parse miss) and
  the landed M1-938 windowing never composes for the query. Evidence
  in-tree: RetrievalGoldenSetTest#activeTemporalRowsParseToPinnedWindowsAtTheWorldNow
  pins exactly these two rows as grammar misses against the PRODUCTION
  parser at both worlds' pinned nows (t24-4 at :963, fam-t24-3 at :972,
  both pinned null), and the 2026-08-31 window-armed owner runs disclose
  both rows ran unwindowed (docs/measurement/retrieval-eval-two-leg.md
  :389-391, :437-439, :456-461). Expected: Window[PT24H, "the past day"] —
  a ROLLING day, parity with the counted "past 24 hours" (the fixture rows'
  labels were adjudicated against a trailing-24h window — Root cause).
  Origin: M1-957 round-1 review RECOMMENDED-NEW-TICKET
  (.scratch/tick-review-M1-957-r1.txt: "a grammar widening is a production
  behavior change needing its own analysis and decision ... Filing is the
  user's call"); the user's filing discussion fixed the boundaries this
  ticket implements (no catch-all fallback; a TARGETED day-scale family with
  pinned semantics; the translation-variance judgment; pin updates land with
  the change; the spec-promise and hint-surface judgments).
analysis_ref: self
blocked_by: []
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/chat/tool/TemporalExpressionParser.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/TemporalExpressionParserTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalGoldenSetTest.java
  - docs/measurement/retrieval-eval-two-leg.md
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY catch-all fallback default window for unparseable temporal phrasings
    — boundary 1 fixed by the user: an inferred window silently HIDES posts
    the user did not bound, worse than the no-window outcome. A parse miss
    stays byte-identical to today (ChatAgent.java:942's {query}-only
    dispatch; the M1-927 not-time-filtered fold header). The user's worked
    counter-example stays structurally impossible here: no fallback exists,
    and "last month" already parses to PT720H via the calendar arm
    (TemporalExpressionParser.java:36, :41) — never through a default.
  - >-
    ANY grammar beyond the named day-scale family: plural forms ("the past
    days", "the last days") carry no definite count and stay non-matches
    (P1 — the P8 hazard alive at the family edge); vague recency
    ("recent"/"latest"/"top"/"new"), year-scale ("this year"), absolute
    dates, and number words stay the recorded non-matches
    (TemporalExpressionParserTest.parsesExplicitRelativeExpressionsAndNothingElse
    :76-86, untouched); no change to COUNTED's digit path (:26-29), to the
    calendar tokens (:31-36), to the clamp (:120-124), or to the
    narrowest-then-first comparator (:43-44); no Unicode-aware edge
    constructs (the M1-953 P5 parity ruling stands — the new arm uses the
    calendar arm's exact \b construct, nothing stronger).
  - >-
    ANY ChatAgent, SemanticSearchTool, ChatToolDispatcher, catalog,
    instruction-table, wire-schema, or spec/design edit — the dispatch,
    windowed fold header, and hint interpolate ANY parse generically
    (verified for the new shape, Root cause judgment 4/5), and the spec's
    windowing promise does not move (verified: commands.md §Chat mode
    :1852-1857 stays true — Root cause judgment 5). Probes: git diff
    --name-only names no ChatAgent.java, SemanticSearchTool.java,
    docs/spec/**, docs/design/**, ChatToolCatalog.java, or
    RetrievalEvalRunnerIT.java hunk.
  - >-
    ANY eval-runner or golden-set-fixture edit — the runner's arm is
    parse-GATED through the production parser (RetrievalEvalRunnerIT.java
    :249-260) and arms the two rows automatically; the golden sets are
    consumed read-only (labels describe shipped retrieval; the flip is a
    grammar fact the parse-map leg RECORDS). The only eval-lane file touched
    is the parse-map leg's pin (the §8-authorized flip, acceptance item 4).
  - >-
    The width-32 lever (M1-959's lane): no infochat.chat.semantic-limit
    change, no scorer change; the pairing interaction is recorded as P7,
    never resolved here.
acceptance:
  - "REPRODUCTION closed: TemporalExpressionParserTest.dayScalePhrasesParseToARollingDayWindow passes — the family table at the fixed NOW (2026-08-26T09:00:00Z): parse(\"security news from the past day\", UTC) and parse(\"environment news from the past day\", UTC) each yield Window[PT24H, \"the past day\"] (the two pinned miss rows' exact queries); \"past day\" (start-of-string) yields Window[PT24H, \"past day\"]; \"over the last day\" yields Window[PT24H, \"over the last day\"]; \"in the previous day\" yields Window[PT24H, \"in the previous day\"]; \"FROM THE PAST DAY\" yields Window[PT24H, \"THE PAST DAY\"] (case-insensitivity parity). RED today on every arm (Optional.empty). Non-vacuity: a mutation removing the new arm fails every positive arm; a mutation to calendar-day semantics (since-midnight Duration) fails the fixed-PT24H arms at NOW (PT9H ≠ PT24H) — the window is a ROLLING day, zone-independent (P2)."
  - "FAILURE-MODE family edges (P1 — hostile/edge inputs fed to this diff's own production pattern; the P8 doctrine's no-false-positive axis): the SAME new test asserts NO match for \"the past days\" (plural — no definite count), \"in the last days\" (plural), \"a day ago\" (no keyword), \"this day\" (no keyword — \"today\" is the calendar token, untouched), and \"next day\" (no keyword); a mutation plural-tolerant (days?) or keyword-loosening fails one of these arms. The coexistence arms pin the digit path untouched: parse(\"past 1 day\", UTC) yields Window[PT24H, \"past 1 day\"] via COUNTED (the new arm cannot match it — a digit intervenes) and parse(\"in the last 24 hours\", UTC) yields Window[PT24H, \"in the last 24 hours\"]; the composition arms pin the existing comparator over the new candidate: parse(\"what happened today and the past day\", UTC) yields Window[PT9H, \"today\"] (narrowest wins) and parse(\"the past day and the last 24 hours\", UTC) yields Window[PT24H, \"the past day\"] (equal windows resolve first-mentioned)."
  - "Green-table identity (the M1-953 additions-only discipline): all FIVE existing TemporalExpressionParserTest methods (parsesExplicitRelativeExpressionsAndNothingElse, clampsToTheSearchPostsWindowBounds, calendarExpressionsAnchorToTheScopeZone, narrowestExpressionWinsWhenSeveralMatch, countedFormRequiresALeftWordBoundary) pass UNMODIFIED — the new match set is disjoint from every existing fixture (verified at analysis time: no existing fixture carries a digit-less last/past/previous + day; grep over infochat-provider/src for 'past day|last day|previous day' returns only the two golden-set rows and the parse-map comment). Probe: git diff over TemporalExpressionParserTest.java shows ADDITIONS ONLY (one new @Test method), no hunk inside the existing five methods (engineering-rules §8: no pre-existing parser-test modification, so no authorization needed there)."
  - "§8-AUTHORIZED pre-existing-test modification (engineering-rules §8; this ticket authorizes exactly this change, in plain language): RetrievalGoldenSetTest.activeTemporalRowsParseToPinnedWindowsAtTheWorldNow — this ticket changes the PRODUCTION grammar, which flips the two pinned grammar misses into PT24H parse hits: the pinned map's t24-4 entry (:963) and fam-t24-3 entry (:972) change from null to \"PT24H\", and the method comment (:954-956) is reworded to match. EVERY OTHER pin stays byte-identical (the 13 per-leg hits: tt-*/fam-tt-* since-midnight durations at each world's pinned now, t2h-*/fam-t2h-* PT2H, the remaining t24-*/fam-t24-* PT24H) and the leg's exact-pin discriminator role is preserved — after the flip it reds on ANY future grammar or fixture drift in EITHER direction. No weakening: removing or loosening the pins is a TEST-INTEGRITY violation, not this authorization. The leg passes after the flip (it is the build-time record of the production change)."
  - "OWNER-RUN paired delta on both legs (the M1-957 creditor posture; verification ceiling — no unit test can stand in for the armed runner; phrased owner-run with a recorded outcome, never claimed as a unit result): after landing, the owner runs BOTH legs of the window-armed runner (two invocations each — the determinism legs), and docs/measurement/retrieval-eval-two-leg.md gains ONE appended dated section carrying: per-leg pins byte-equal to the 2026-08-31 window-armed reading (fingerprint, golden_set_sha256, world_embedding_coverage, threshold/limit) plus the window_arm/window_zone/world_now keys; the disclosure that t24-4 (tech) and fam-t24-3 (fam) are now ARMED with _window=PT24H (the parse-gated arm, zero runner change) while every other row's dispatch is byte-identical to the 2026-08-31 runs; per-row movement for exactly those two rows vs the 2026-08-31 readings (t24-4: 0.125 unwindowed; fam-t24-3: 0.3333 unwindowed) as a paired INSTRUMENT delta with the single variable = this grammar extension; the PRE-REGISTERED statement that the movement is DESCRIPTIVE and below rule T1's floor of 6 one-directional discordant queries BY CONSTRUCTION (exactly one newly-armed row per leg — never a result); determinism legs restated (per-query uid identity across the two invocations, harness-asserted); M1-957's parse-miss disclosure restated as superseded-in-current-state by this section (append-only — the 2026-08-31 section's text is never edited); and the do-not-settle restatement (the width-32 lever remains NOT product-decided). Probes: git diff over the record is pure additions; grep -n 'PT24H' over the new section returns the armed rows; no percentage-point phrasing (reviewer read); no URL/instance name/user-derived data in the section (P8)."
  - "Scope fence + full suite (engineering-rules §1/§5): git diff --stat names exactly the four files_scope paths (no ChatAgent/SemanticSearchTool/dispatcher/catalog/runner/spec/design hunk — the out_of_scope probes); mvn verify from the repo root is green with the eval stack ABSENT (the parse-map leg and the new parser method run in the default suite; the runner/characterization ITs stay CI-excluded — the M1-957 verify-log probe)."
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/TemporalExpressionParserTest.java
      — dayScalePhrasesParseToARollingDayWindow (plain JUnit): the positive
      family table, the failure-mode family-edge negatives, the coexistence
      and composition arms of acceptance items 1-2.
  modifies:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalGoldenSetTest.java
      — activeTemporalRowsParseToPinnedWindowsAtTheWorldNow: the two null
      pins flip to "PT24H" and the method comment syncs (§8-authorized,
      acceptance item 4; nothing else in the file moves).
    - >-
      docs/measurement/retrieval-eval-two-leg.md — ONE appended dated
      section (acceptance item 5; append-only, owner-run).
  preserves:
    - >-
      all tests currently green on main — explicitly the five existing
      TemporalExpressionParserTest methods, every other
      RetrievalGoldenSetTest leg (schema/floors/xling/fingerprint/corrections),
      ChatAgentTest's temporal arms (temporalTurnWindowsThePreFetchAndHandsTheModelAWindowHint,
      nonTemporalTurnIsByteIdenticalToThePreChangeShape, the anchoring/breaker/
      timezone arms — no fixture carries a day-scale phrase, verified),
      ChatToolDispatcherTest's malformed-_window arm, the
      SemanticSearchToolIT windowed arms, RetrievalWorldPredicateIT, and
      every runner fence, unmodified.
spec_refs: []
decision_refs:
  - D19
  - D58
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
    date: 2026-09-01
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS: PASS; SECURITY: PASS; TEST-ADEQUACY: PASS; MAINTAINABILITY: PASS; SCOPE: PASS — 4 falsification candidates dropped with citations (the 'last day of August' calendar-named read defeated by the recorded narrows-grounding trade-off at TemporalExpressionParserTest.java:72-73 + the class javadoc's negation-blind residual; record §13 defeated by the pins being byte-equal restatements of already-committed values, grep-proven against the c7f58dee base; the record mtime postdating the verify log defeated by the no-test-reads-the-record probe + the four green eval-leg builds over the same tree; the calendar()-helper hosting a rolling pattern defeated by LAST_WEEK/LAST_MONTH already riding it). Reviewer verified the RED log, regex semantics by hand, the compose path (dispatch/header/hint/clamp), the additions-only and fence probes, the two-pin flip's byte-identity, and the owner-run arithmetic. Verdict: .scratch/tick-review-M1-961-r1.txt"
    diff_stats: "6 files, +205/-17 (TemporalExpressionParser.java +12 the new arm; TemporalExpressionParserTest.java +41 one new @Test additions-only; RetrievalGoldenSetTest.java +8/-4 the §8 two-pin flip + comment sync; retrieval-eval-two-leg.md +115 append-only dated section; ticket frontmatter bookkeeping; board regen)"
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  checked: 2026-09-01
  result: >-
    Self-check clean, no blocking question. Lint 1 WARN 0 BLOCKERs (the
    legal spec_refs-empty defect-ticket posture). Citations spot-checked
    true against c7f58dee: COUNTED's mandatory (\d+) and the six calendar
    tokens (TemporalExpressionParser.java:26-36); the two null pins at
    RetrievalGoldenSetTest.java:963/:972 with the :954-956 comment;
    SearchPostsTool :41/:43-44 clamp source; SemanticSearchTool's _window
    gate + clamp + clock.instant().minus(window) cutoff; the runner's
    parse-gated arm (RetrievalEvalRunnerIT.java:249-260 area); the two-leg
    record's :389-391/:437-439/:456-461 disclosures with the 0.125/0.3333
    unwindowed baselines. ChatAgent line drift only (parse call now :944,
    windowHint :995 vs cited :937/:988 — post-filing commits; every claim
    holds). Census re-ran clean (all six grep sites have rows).
    Disjointness probe: grep 'past day|last day|previous day' over
    infochat-provider/src returns the two golden rows, the parse-map
    comment, AND two case-insensitive prose hits in DigestWorker.java:417
    / DigestWorkerTest.java:354 ("PREVIOUS day's evening slot") — digest
    scheduling comments, never parser input; no fixture carries the
    family, so the additions-only claim stands. --parallel module check:
    no tick ticket is in-progress/in-review and no other worktree exists
    — infochat-provider is uncontended.
escalation_reason:
---

# M1-961: Extend temporal grammar for digit-less day-scale phrases

## Context

"security news from the past day" and "environment news from the past day"
are day-scale temporal asks a chat user actually types — and the production
temporal grammar misses both: COUNTED requires a digit
(TemporalExpressionParser.java:27) and no calendar token matches (:31-36),
so `parse` returns `Optional.empty`, the dispatch layer sends exactly
`{query}` (ChatAgent.java:940-942), and the user gets an UNWINDOWED
pre-fetch — the M1-938 windowing that landed for every digit-bearing and
calendar phrasing never composes for this one. The eval lane's M1-957
window arm pins exactly these two rows as grammar misses
(RetrievalGoldenSetTest.java:963, :972) and the 2026-08-31 window-armed
owner runs disclose both ran unwindowed
(docs/measurement/retrieval-eval-two-leg.md:389-391, :437-439, :456-461).
Origin: M1-957's round-1 review RECOMMENDED-NEW-TICKET; the user filed with
fixed boundaries (no catch-all fallback window; a TARGETED extension for the
day-scale "past/last day" family with pinned semantics; the change and its
pin updates land together). Single-ticket analysis: `analysis_ref: self` —
this body is the analysis. Decomposition is one ticket by construction: the
grammar change without the pin flip fails the build, the pin flip without
the grammar change is a test-only lie, and the owner-run delta section is
writable only against the landed change — no half is independently landable.

## Root cause

Verified end to end against the current checkout (all line numbers
re-checked 2026-08-31):

- **The miss mechanism.** `TemporalExpressionParser.parse`
  (TemporalExpressionParser.java:49-58) collects candidates from two
  surfaces: COUNTED (:26-29 —
  `\b(?:(?:in|within|over|during)\s+)?(?:the\s+)?(?:last|past|previous)\s+(\d+)\s*(hours?|hrs?|h|days?|d|weeks?|w|months?|mo|minutes?|mins?)\b`,
  the `(\d+)` group mandatory) and six calendar tokens (:31-36). "… news
  from the past day" carries no digit and no calendar token → zero
  candidates → `Optional.empty`. ChatAgent's parse-keyed dispatch
  (ChatAgent.java:935-942) then sends `Map.of("query", query)` with no
  `_window`, the fused SQL runs without the ready_at predicate
  (SemanticSearchTool.java:128-138 gates on `_window`; :170 derives the
  cutoff), and the fold carries the M1-927 not-time-filtered header
  (ChatAgent.java:965-972's miss branch) — day-scale asks ground on
  posts of any age under a header that says so, while the hint that would
  steer `searchPosts` to a window is never emitted.
- **The pinned semantics is a ROLLING PT24H, not a calendar day.** Both
  fixture rows are `temporal-24h`-class en rows whose labels were
  adjudicated against a trailing-24h window — the fixture rationales say so
  (golden-set.jsonl t24-4: "Window = trailing 24h before corpus max
  ready_at"; golden-set-fam.jsonl fam-t24-3: "window = trailing 24h before
  the replica max ready_at") — and the same class's sibling rows pin PT24H
  in the parse-map leg (t24-1/2/3, fam-t24-1/2/4 → "PT24H",
  RetrievalGoldenSetTest.java:962, :970-971). "The past day" is the
  digit-less sibling of "the past 24 hours"; a calendar-day (since-midnight)
  reading would make the window zone-dependent and, at the tech world's
  pinned now, PT16H57M — contradicting the labels the rows carry.
- **Prior art P8 falsified-or-upheld (the on-record rationale, binding).**
  The grammar boundary this brief challenges is analysis P8
  (docs/plan/m1/tick-analysis/temporal-parse-windowing.md: "an inferred
  window would silently hide posts the user did not bound") and the class
  javadoc restating it (TemporalExpressionParser.java:17 — vague recency,
  year-scale, absolute dates, and number words are deliberate non-matches).
  Judgment: the rationale is UPHELD as the boundary rule and FALSIFIED as
  applied to the named family. "The past day" STATES a bound — a day scale —
  exactly as "the past 24 hours" does; a PT24H window hides only posts
  older than the bound the user themselves stated, which is what the user
  asked to exclude. The hiding hazard P8 names applies where the phrase
  carries NO definite scale ("recent", "latest" — any window would be
  invented; the user's "last month + 24h fallback" counter-example) or
  where the scale is ambiguous (plural "the past days" — how many days?).
  Those stay non-matches; no fallback window exists for anything (boundary
  1). The javadoc's boundary sentence remains TRUE after the change — the
  day-scale family is an explicit relative expression, and the enumerated
  non-match classes (vague recency, year-scale, absolute dates, number
  words) are untouched — so no javadoc hunk is needed (§1).
- **Judgment 3 (translation variance, brief boundary 3 — verified,
  accepted with disclosure).** The parse runs over the ANCHORED query
  (ChatAgent.java:935-936), and greedy anchoring is not byte-stable across
  JVM boots (the M1-952-disclosed fam-xl-economy-cs divergence,
  retrieval-eval-two-leg.md:226-246). The extension DOES widen the class of
  anchored phrasings whose window depends on the boot's translation — but
  ONLY for non-en scopes and ONLY by the named family: a cs day-scale ask
  whose greedy rendering lands in the family ("in the past day", "over the
  last day", …) now windows; a rendering that paraphrases away ("recently")
  misses, as every digit-bearing temporal phrasing already can today. The
  cost for run-to-run determinism optics is real but bounded and
  already-open-class: (a) en scopes are a strict no-op anchor (D58) — the
  parse is a pure function of the raw bytes, zero boot dependence — and
  BOTH golden rows and ALL active temporal rows are en (pinned by
  RetrievalGoldenSetTest.activeTemporalRowsAreEnScoped), so the entire eval
  lane is untouched by this variance; (b) within one boot the translation
  cache makes repeats identical; (c) both outcomes are honest — the windowed
  outcome windows by exactly the stated day scale with the windowed fold
  header and hint, the unwindowed outcome is today's behavior under the
  M1-927 disclosure header ("matched by topic similarity only, not filtered
  by time") — the user is never told a window that was not derived from
  their words; (d) the determinism boundary (docs/spec/llm.md §Determinism
  boundary :479-484) guarantees same-DB-state → same rows for a given
  retrieval input, and the anchored text is an LLM output UPSTREAM of
  retrieval — the two-leg record's own posture on fam-xl-economy-cs. No new
  translator leg is created (the parse adds no LLM call; the
  security.md:1919-area leg enumeration is untouched).
- **Judgment 4 (hint surface, brief boundary 5 — verified, unaffected).**
  The windowed fold header and the hint interpolate ANY parse generically:
  `"…matched by topic similarity within " + phraseBody(parsed.phrase()) +
  " (posts that became readable in that window):"` (ChatAgent.java:965-969)
  and `TEMPORAL_WINDOW_HINT_FORMAT` naming `window=%s` with the clamped ISO
  duration (:983-991). For the new shape: phrase `"the past day"` →
  header "…within the past day (posts that became readable in that
  window):", hint "use window=PT24H" (Duration.ofDays(1).toString() —
  the same ISO string the counted "past 24 hours" already emits, and
  SemanticSearchTool.java:131 parses it back identically inside the shared
  clamp). `phraseBody` (:996-1003) strips exactly the four prepositions the
  new arm's prefix alternation reuses (in/within/over/during — the COUNTED
  set, TemporalExpressionParser.java:27), so prefixed matches compose
  ("over the past day" → "the past day"); "from" is deliberately NOT in the
  alternation (the match starts at "the past day", keeping the phrase
  grammatical inside both skeletons and requiring zero change to
  phraseBody's closed strip list).
- **Judgment 5 (spec promise, brief boundary 5 — verified, does not move).**
  commands.md §Chat mode :1852-1857 states: "The dispatch layer
  deterministically parses explicit relative time expressions from the
  English-anchored query (regex + java.time, no model — D19); a parse hit
  windows the pre-fetch to the same ready_at rule and appends a
  deterministic window hint steering searchPosts; a parse miss changes
  nothing, and vague recency never infers a window." Every clause stays
  TRUE: the day-scale family is an explicit relative expression (a stated
  scale, not vague recency), the window binds to ready_at under the shared
  clamp (commands.md §Content, What the window measures, :513-532), the
  parse-miss byte identity is preserved, and vague recency still never
  infers. design 05 §5.4.6 :636-647 describes the parse at class level
  ("explicit relative time expression", "treats vague recency as a
  non-match") and also stays true. NO spec or design amendment is needed —
  hence `spec_refs: []`: this is a defect ticket whose contract is its
  reproduction (the M1-953 precedent for a grammar edit — its spec_refs
  carried the same rationale).
- **Regex safety note.** The new pattern has no nested quantifiers
  (optional non-capturing groups + `\s+` junctions) over input bounded to
  SEMANTIC_QUERY_MAX_CHARS = 500 chars (ChatAgent.java:930-931) — the
  M1-953 reviewer's regex-DoS falsification applies verbatim; the one
  changed premise (the parser then had zero production callers; M1-938
  landed one) does not create a vector on a linear pattern over bounded
  text.

## Pitfalls

- P1: **The family edge IS the P8 line.** The extension must match the named
  singular day-scale family and NOTHING more: plural "the past days"/"the
  last days" carry no definite count — windowing them at 24h would hide
  posts from the second-plus day the plural may mean, which is precisely the
  P8 hazard (an inferred window silently hides posts the user did not
  bound); and NO fallback window may appear for any miss (boundary 1 — the
  user's counter-example: "last month" + 24h fallback drops ~27 of 30 days;
  the WINDOW_MIN/MAX clamp catches the absurd, not the semantic mismatch).
  Bites as a review FAIL on over-inference or as silent post-hiding in
  production; caught by the negative family-edge arms.
- P2: **Calendar-day semantics trap.** "The past day" must be rolling PT24H,
  never "since local midnight": the fixture labels were adjudicated against
  a trailing-24h window (Root cause), the same-class siblings pin PT24H, a
  calendar-day window would be zone-dependent (the P5 hazard class all over)
  and would diverge from "past 24 hours" — one conversation, one window
  vocabulary (commands.md §Content :528-532, M1-689). Caught by the
  fixed-PT24H arms at a NOW where since-midnight ≠ 24h.
- P3: **The §8 pin flip.** The parse-map leg
  (RetrievalGoldenSetTest.java:952-996) pins the two misses AS DESIGNED; the
  grammar change reds it unless the pins flip WITH the behavior — and
  un-authorized, the reviewer fails TEST-INTEGRITY-CHECK; weakening
  (deleting/loosening pins) destroys the exact-pin discriminator M1-957
  built (its P4). The authorization names exactly the two entries and the
  comment; nothing else in the file moves.
- P4: **COUNTED/calendar regression (the M1-953 group-numbering lesson
  inverted).** The new arm must be a SEPARATE pattern constant riding the
  existing `collect()` path — never an edit to COUNTED's digit grammar
  (whose group(1)/group(2) reads at :70-74 and `durationOf` would break on
  a digit-less capture) and never a calendar-token edit. Every existing test
  fixture must stay disjoint from the new match set (verified at analysis
  time; the additions-only probe is the fence).
- P5: **Translation-variance disclosure (D58; judgment 3).** The widening of
  the boot-dependent class is real for non-en day-scale asks; it must be
  DISCLOSED in the record section, not discovered later as an unexplained
  windowed/unwindowed flip. The fence (no ChatAgent/QueryAnchorTranslator
  hunk) proves no anchoring machinery moved — the variance is a property of
  the existing anchor leg meeting a wider match set, never of this diff's
  own.
- P6: **Hint/header/spec over-reach.** The compose path is generic and
  verified (judgment 4); the promise does not move (judgment 5). The trap is
  "helpfully" editing ChatAgent's skeletons, phraseBody's strip list, the
  spec sentence, or design 05 — each is scope drift (§1) and each risks
  breaking the byte-pinned miss branch ("matched by topic similarity only,
  not filtered by time", which M1-938's nonTemporalTurnIsByteIdentical…
  pins). The fence probe catches it.
- P7: **Eval-runner auto-arm and record pairing.** The runner's arm is
  parse-gated (RetrievalEvalRunnerIT.java:249-260) and needs ZERO change —
  it arms t24-4/fam-t24-3 automatically; anyone "fixing" the runner or
  class-gating the new rows recreates the M1-957 P1 fiction. The delta's
  single variable is the grammar extension (everything else pinned byte-equal
  to the 2026-08-31 runs); the movement is below T1's floor BY CONSTRUCTION
  (one newly-armed row per leg) and must be pre-registered as descriptive,
  never a result (TL3/N1). M1-959 interaction: M1-959 (pending) pairs its
  32-side against "the POST-M1-957 16-side runs"; if THIS ticket lands
  before M1-959's owner runs, M1-959's 16-side must be a fresh post-M1-961
  armed run (the single-variable rule governs the PAIRED runs, not history).
  Recorded here; the driver resolves the ordering — nothing in this ticket
  blocks or edits M1-959.
- P8: **§13 placement.** The record section restates pins already committed
  in the two-leg record (byte-equal — the M1-957 reviewer's §13 defeat
  rationale: the pin class is committed fixture-world material, and no NEW
  class of deployment-identifying material is introduced); it carries no
  instance name, URL, or user-derived data; run artifacts live
  operator-local under .bench/, never in files-to-touch.

## Approach

Defect ticket: `spec_refs:` is legally empty — the template's own rule
("legally EMPTY on a defect ticket, whose contract is its `reproduction:`",
docs/process/tick-ticket-template.md) — because judgment 5 verified that no
spec text enumerates the grammar and the windowing promise (commands.md
§Chat mode :1852-1857, §Content :513-532) stays true unamended; the
governing contracts are the merged grammar's own disciplines (M1-937's clamp
+ narrowest rule, M1-953's boundary construct) and the P8 doctrine as
falsified for the named family above. D19: the derivation stays regex +
java.time, no model, no config knob.

- **Files to touch:** exactly `files_scope` — the parser (one pattern
  constant + one window constant + one collect call + one comment), the
  parser test (one new @Test method), the parse-map leg (the §8-authorized
  two-pin flip), the two-leg record (one appended dated section, owner-run).
- **Pre-decided shapes (implementation is execution):**
  1. **The new arm** in TemporalExpressionParser.java, beside the
     LAST_WEEK/LAST_MONTH rolling precedent (:35-36, :40-41, :104-105):
     `private static final Pattern DAY_SCALE = calendar("\\b(?:(?:in|within|over|during)\\s+)?(?:the\\s+)?(?:last|past|previous)\\s+day\\b");`
     under the same `calendar()` CASE_INSENSITIVE helper;
     `private static final Duration DAY_SCALE_WINDOW = Duration.ofDays(1);`
     with a brief comment in the :38-41 style stating the rolling-24h
     semantics ("past/last day" reads as rolling 24h — parity with the
     counted "past 24 hours"; NOT a calendar day — that reading belongs to
     today/yesterday) (§11: it guards the P2 trap); and
     `collect(DAY_SCALE, text, DAY_SCALE_WINDOW, out);` in
     `collectCalendars` after the LAST_MONTH collect (:105). Every group is
     non-capturing; `collect()` reads only `m.group()` and `m.start()`
     (:108-114), so no group-numbering surface moves (P4); the result rides
     the EXISTING `clamp()` (:112) against the shared SearchPostsTool
     constants (PT24H is inside [1h, 30d] — no clamp movement, one window
     vocabulary) and joins the same candidate list under the existing
     NARROWEST_THEN_FIRST_MENTIONED comparator (:43-44, :56). The shape
     mirrors M1-953's landed COUNTED exactly minus the digit/unit: the
     leading `\b` + the prefix's own `\s+` junction kill the blast/inlast
     classes by the same mechanism M1-953 pinned (its P1 trace); the prefix
     alternation reuses COUNTED's closed list so `phraseBody`'s strip list
     (ChatAgent.java:997) already covers every prefixed phrase (judgment 4);
     "from" is excluded (Root cause judgment 4). `Duration.ofDays(1)` renders
     "PT24H" — byte-identical to the counted "past 24 hours" ISO string the
     tool already parses (SemanticSearchTool.java:131).
  2. **The parser table** — one new @Test method
     `dayScalePhrasesParseToARollingDayWindow` (plain JUnit, the existing
     assertWindow/assertNoMatch helpers): the positive family table, the
     failure-mode negatives, the coexistence arms, and the two composition
     arms of acceptance items 1-2. ADDITIONS ONLY (P4's fence).
  3. **The pin flip** — RetrievalGoldenSetTest: the t24-4/fam-t24-3 entries
     move from null to "PT24H" (e.g. each joins its leg's PT24H `List.of`
     row) and the method comment syncs; nothing else (§8 item 4, P3).
  4. **The owner-run delta** — both legs, two invocations each, per the
     acceptance item 5 shape (P7/P8): pins byte-equal to 2026-08-31, the two
     newly-armed rows named, per-row movement with the below-floor
     pre-registration, determinism legs, supersession disclosure,
     do-not-settle. ONE appended dated section, pure additions.
- **Steps, in implementation order:** (1) write
  `dayScalePhrasesParseToARollingDayWindow` and run it RED (every positive
  arm fails today by returning Optional.empty — assertion failure, not
  compile error; the M1-953 RED shape); (2) land the three-token parser
  change (pattern + constant + collect call + comment); (3) flip the two
  pins (the §8 authorization item 4 names them); (4) `mvn verify` from the
  repo root; (5) owner runs both legs (two invocations each) and appends the
  record section. Order rationale: RED first is workflow §0; the pin flip
  after the parser keeps the leg red-on-wrong-behavior until the behavior
  lands; the record last because it measures the landed change.
- **Prior art evaluated — falsified or adopted, never copied:**
  1. *M1-937 (the parser's creation ticket):* ADOPTED — the shared
     SearchPostsTool.WINDOW_MIN/MAX clamp source (:43-44) and the
     narrowest-then-first multi-match rule are inherited unchanged by the
     new arm (it flows through the same `clamp()` and comparator); its
     acceptance table's negative classes stay green (item 3). FALSIFIED as a
     boundary for this family: its P8-derived out_of_scope ("vague-recency
     inference … deliberately NON-matches") never named the day-scale family
     — the family fell outside both the match set and the enumerated
     non-match list, and the Root cause judgment falsifies P8 as applied to
     it while upholding it everywhere else.
  2. *temporal-parse-windowing.md P8 (the on-record rationale):* UPHELD as
     the boundary rule (no fallback, no vague/plural/year-scale inference —
     P1), FALSIFIED as applied to the named family (the user STATES the day
     bound; the window hides only what the user excluded) — Root cause.
  3. *M1-938 (the wiring ticket):* ADOPTED as the verified-unchanged seam —
     dispatch, windowed header, and hint interpolate any parse generically
     (judgment 4 cites the exact lines); its out_of_scope "grammar
     extension … stays non-matches" referred to the M1-937 boundary classes,
     which this ticket does not touch. Its spec amendments (commands.md
     :1852-1857) stay true (judgment 5).
  4. *M1-953 (the last grammar edit — the working precedent):* ADOPTED as
     shape — a separate pattern constant, the calendar arm's exact `\b`
     construct (nothing stronger — its P5 parity ruling), additions-only
     tests, no §8 authorization needed for the parser table, the
     surviving-form/negative-arm pairing, and the `spec_refs: []`
     defect-ticket rationale; its `\s+`-inside-the-optional-prefix junction
     rule is reused verbatim (the inlast-class kill).
  5. *M1-927 (the honesty doctrine):* ADOPTED as the miss-path contract —
     a parse miss keeps the dated, not-time-filtered fold; the extension
     never widens silently and never retries unwindowed (the P10 doctrine,
     untouched).
  6. *M1-916/M1-917 + tool-routing-temporal-queries.md (routing + window
     mechanics upstream):* UNTOUCHED — the catalog steering strings and the
     similarity-order ruling are orthogonal to a grammar match-set addition;
     M1-917's owner-rejected recency ordering is not reopened (the window
     changes set MEMBERSHIP on parse-hit turns only, never introduces a time
     ordering — the M1-938 amendment states this).
  7. *M1-957 (the direct origin) + retrieval-campaign-followups.md P4 +
     .scratch/tick-review-M1-957-r1.txt:* ADOPTED — the parse-map leg is
     this ticket's discriminator (its exact pins flip, item 4); the runner's
     parse-gated arm needs zero change (judgment verified at
     RetrievalEvalRunnerIT.java:249-260); the record's append-only
     discipline and TL3/N1 vocabulary shape item 5; the review's
     RECOMMENDED-NEW-TICKET is this ticket's origin, and its "grammar
     widening is a production behavior change needing its own analysis"
     condition is met by this body.
  8. *The 2026-08-31 window-armed reading (docs/measurement/
     retrieval-eval-two-leg.md §Window-armed reading):* ADOPTED as the
     paired baseline — its disclosures (:456-461) are superseded-in-current-
     state by item 5's section, never edited.
  9. *Redteam corpus:* docs/plan/m1/redteam/ returns no file (glob verified
     2026-08-31 — the directory is absent in this checkout, as M1-953's
     census noted); no temporal-grammar finding exists to carry or retire.
- **Controls to preserve (engineering-rules §10):** the change reroutes
  NOTHING — it adds candidates inside a pure function. Enumerated controls
  that must survive verbatim: the shared clamp constants
  (SearchPostsTool.java:43-44, package-private, M1-689 one-window rule) and
  the `clamp()` path every candidate rides; the
  NARROWEST_THEN_FIRST_MENTIONED comparator (D19 same-message-same-window);
  COUNTED's magnitude/zero-strip guard (TemporalExpressionParser.java
  :67-76) and `durationOf` (:79-89) — untouched; the calendar tokens
  (:31-36) and their zone-anchored windows (:93-105) — untouched; the class
  javadoc's boundary sentence (still true — Root cause); ChatAgent's
  dispatch/header/hint/phraseBody/zoneFor (:926-1029) — untouched;
  SemanticSearchTool's `_window` gate + cutoff (:128-138, :170) — untouched;
  the dispatcher boundary; the runner's fences; the M1-927 miss-path header
  bytes. Pinning tests: the five existing parser methods, the whole
  RetrievalGoldenSetTest suite beyond the two pins, ChatAgentTest's temporal
  arms, ChatToolDispatcherTest's malformed-_window arm, the
  SemanticSearchToolIT windowed arms — all green unmodified except the
  enumerated authorization.
- **Pitfall→mitigation:** P1→item 2's negative arms + out_of_scope's
  no-fallback clause; P2→item 1's fixed-PT24H arms; P3→item 4's
  plain-language authorization + reviewer diff against it; P4→item 3's
  additions-only probe + the five unmodified methods; P5→item 5's disclosure
  clause + the no-ChatAgent-hunk fence; P6→item 6's fence probes; P7→item 5's
  pre-registered below-floor shape + the M1-959 note in P7's text;
  P8→item 5's §13 probes.
- **Alternatives considered (rejected, recorded for the commit message):**
  (B) making COUNTED's digit optional across ALL units — digit-less "last
  hour"/"last minute" would clamp up to WINDOW_MIN and "last week"/"last
  month" would double-match their calendar arms; a unit-table-wide heuristic
  violates the targeted-family boundary (boundary 2). (C) calendar-day
  semantics ("since local midnight") — P2: contradicts the adjudicated
  labels, zone-dependent, diverges from "past 24 hours". (D) a catch-all
  fallback default window — boundary 1, user-rejected with the worked
  counter-example. (E) admitting plural "past/last days" — P1: no definite
  count. (F) parsing the RAW (pre-anchor) query for non-en scopes so the
  window is translation-stable — forks the D58 contract (one grammar over
  the anchored string; ChatAgent parses what the tool embeds — a split would
  window the pre-fetch by a text different from what retrieval consumes) and
  would need five language-specific date-phrase tables (the M1-937
  one-grammar rationale). (G) a rides-the-diff spec/design amendment
  recording the family — falsified by judgment 5: the promise does not move,
  so there is nothing to record (§12: amendments record, they do not
  decorate).

## Definition of done

`dayScalePhrasesParseToARollingDayWindow` passes: the day-scale family
yields rolling PT24H windows with the exact phrases (both golden queries'
phrases pinned verbatim), the family edges stay non-matches, the digit path
and the comparator compose unchanged. All five existing parser methods pass
UNMODIFIED (additions-only diff). The parse-map leg passes with t24-4 and
fam-t24-3 flipped to PT24H and every other pin byte-identical (§8
authorization item 4). The owner runs both legs (two invocations each) and
the record gains one appended dated section per item 5's shape — pins
byte-equal to 2026-08-31, the two armed rows named, below-floor
pre-registration, determinism legs, supersession disclosure, do-not-settle.
The diff names exactly the four files_scope paths; no ChatAgent/tool/spec/
design/catalog/runner hunk; `mvn verify` green from the repo root with the
eval stack absent.

## Verification

- Reproduction → acceptance item 1 (every positive arm RED today —
  Optional.empty; green after; names its two mutations: dropped arm,
  calendar-day semantics).
- P1 → item 2's negative arms ("the past days", "in the last days", "a day
  ago", "this day", "next day" fed to the new production pattern; a
  plural-tolerant or keyword-loose mutation fails one) + the no-fallback
  out_of_scope clause (miss path pinned byte-identical by
  ChatAgentTest.nonTemporalTurnIsByteIdenticalToThePreChangeShape passing
  UNMODIFIED).
- P2 → item 1's fixed-PT24H arms at NOW (a since-midnight mutation computes
  PT9H at 2026-08-26T09:00Z and fails every arm).
- P3 → item 4 (the reviewer diffs the two-pin flip + comment against the
  plain-language authorization; any other hunk in the file fails the fence).
- P4 → item 3 (five unmodified methods green — the over-extension detector;
  additions-only probe) + the coexistence arms (COUNTED's "past 1 day"
  unchanged, the digit-less arm cannot match through a digit).
- P5 → item 5's disclosure clause + item 6's fence (no ChatAgent/translator
  hunk — the widening is the anchor leg meeting a wider match set, never a
  machinery change); judgment recorded in Root cause.
- P6 → item 6's probes (no spec/design/ChatAgent diff) + the preserved
  suites list (ChatAgentTest's temporal arms pin the compose path the
  judgment verified).
- P7 → item 5's pre-registered below-floor statement (one newly-armed row
  per leg — the delta can never clear floor 6, so no result is claimable) +
  the M1-959 ordering note (recorded, driver-owned).
- P8 → item 5's probes (pure additions; no URL/instance/user tokens; pins
  byte-equal restatements of committed fixture-world pins).
- FAILURE-MODE coverage beyond the reproduction → item 2 is the
  failure-mode item (hostile/edge inputs — plural, keyword-less, and
  prefix-fused-by-absence forms fed to the diff's own production pattern,
  asserting the protected no-match behavior), plus the pin-flip leg as the
  bidirectional drift catcher (any future grammar or fixture drift reds at
  build time — the M1-957 discriminator, now flipped).
- acceptance item 6 → git-diff fence + mvn verify from the repo root.

## Out-of-scope

Named in `out_of_scope:` — no fallback window (boundary 1, with the user's
counter-example recorded); no grammar beyond the named singular family
(plurals, vague recency, year-scale, absolute dates, number words stay the
recorded non-matches; COUNTED, the calendar tokens, the clamp, and the
comparator untouched); no ChatAgent/SemanticSearchTool/dispatcher/catalog/
instruction-table/wire-schema/spec/design edit (judgments 4/5 — the compose
path is generic and verified, the promise does not move); no eval-runner or
golden-set-fixture edit (the arm is parse-gated and auto-arms; the sets are
read-only); the width-32 lever is M1-959's lane (P7 records the pairing
interaction without resolving it). This ticket modifies EXACTLY ONE
pre-existing test — RetrievalGoldenSetTest.activeTemporalRowsParseToPinnedWindowsAtTheWorldNow,
§8-authorized in acceptance item 4 with the new expected behavior stated in
plain language (the two null pins become "PT24H"; every other pin
byte-identical); every other pre-existing suite must pass unmodified.

## Census

Class census — **every site that consumes or pins the production temporal
parse** (the grammar change flows to all of them). Re-runnable probe:
`grep -rn 'TemporalExpressionParser' infochat-provider/src/` (verified
2026-08-31; every returned path has a row):

| Site | Disposition |
|---|---|
| `TemporalExpressionParser.java` (the grammar) | **FIX — this ticket** (the new arm; nothing else in the file moves) |
| `ChatAgent.java` :30/:937/:988 (dispatch + hint) | **Unchanged** — generic over any parse; judgment 4 verified the day-scale phrase + PT24H compose through header/hint/phraseBody untouched |
| `SearchPostsTool.java` :41 (clamp-source comment) | **Unchanged** — comment-only; the constants :43-44 are consumed, not edited |
| `TemporalExpressionParserTest.java` | **Additions only** — one new @Test method (item 3's fence) |
| `RetrievalEvalRunnerIT.java` :255 (the window arm) | **Unchanged** — parse-gated by construction; auto-arms t24-4/fam-t24-3 post-landing (P7) |
| `RetrievalGoldenSetTest.java` :987 (the parse-map leg) | **Authorized edit** — the two-pin flip (item 4) |

**Placement classification (engineering-rules §13, decided at analysis
time):** all four files-to-touch are committed and instance-free — the
parser and tests are pure code; the record section restates pins ALREADY
committed in the two-leg record byte-equal (the M1-957 reviewer's §13
defeat rationale: frozen fixture-world pins, no new class of
deployment-identifying material) and names no instance/URL/user-derived
data (P8's probes). The owner-run artifacts (manifests, scores, queries)
land operator-local under `.bench/retrieval-eval/`, named by STORE class in
the section, never entering files-to-touch.

## Pre-flight self-check (author-side)

Run before filing and before `/tick start M1-961`:

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-961-temporal-day-scale-grammar-extension.md
```

The lint gate is the mechanical half of readiness; `start` refuses on a
BLOCKER. Expected lint posture: 0 BLOCKERs; the `spec_refs` empty WARN is
legal for a defect ticket whose contract is its reproduction (the M1-953
precedent; judgment 5 records why no spec text moves); the `reproduction:`
marker is the `to-be-written` form over an existing test class (the M1-953
marker discipline). Full check table: `docs/process/tick-workflow.md` §1.
