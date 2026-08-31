---
id: M1-957
title: "Arm the eval lane's temporal window + widening guard"
status: done
created: 2026-08-30
last_updated: 2026-08-31
flow: tick
reproduction: >-
  Child of a 2+ decomposition (analysis
  docs/plan/m1/tick-analysis/retrieval-campaign-followups.md); the RED leg is
  `to-be-written` per workflow §0 — only this child can make it writable (no
  window-arm seam exists to compile against; the M1-950 marker precedent).
  The wrong behavior: the eval runner executes every golden row with EXACTLY
  Map.of("query", row.query()) (RetrievalEvalRunnerIT.java:239) — grep for
  `_window|TemporalExpressionParser` over
  infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/
  returns ZERO matches — so temporal rows run UNWINDOWED and M1-938's landed
  windowing is invisible to the instrument: temporal-today/2h scored
  0.100/0.100 byte-identically across M1-938's landing (the 2026-08-27
  re-baseline, docs/measurement/retrieval-eval-baseline.md:333-334, vs the
  2026-08-30 two-leg tech reading, docs/measurement/
  retrieval-eval-two-leg.md tech table :184-185). M1-938's acceptance item
  15   records the owed extension verbatim ("a post-landing harness re-run
  whose temporal arm passes the window — the harness extension is the eval
  lane's own"; its P16: "no eval fixture/harness/record edits; the flip is
  the owner-run delta"). Entry converted at start 2026-08-31 (the M1-950
  marker discipline):
  RetrievalGoldenSetTest#activeTemporalRowsParseToPinnedWindowsAtTheWorldNow
  — a default-suite unit leg driving the PRODUCTION
  TemporalExpressionParser.parse over every active temporal row of BOTH
  golden sets at (ZoneOffset.UTC, the world's pinned now) and asserting the
  pinned per-row window map (26 parse hits, 2 grammar misses named:
  t24-4 "security news from the past day", fam-t24-3 "environment news
  from the past day"). RED conversion record: the parse-map leg compiles
  against the EXISTING seams (production parser + world seam), so the
  draft's compile-RED prediction did not hold for THAT leg — it pins
  production truth and greens immediately (the exact-pin discriminator reds
  on any future grammar/fixture drift); the family's compile-RED instead
  came from the widening-guard leg written in the same step —
  .scratch/tick-red-M1-957.log: test-compile fails
  `pairsWithSiblings(GoldenSet,byte[]) has private access in
  RetrievalEvalCharacterizationIT` (the M1-946 r1 aborting guard,
  unreachable at build time before the widen). Mutation check
  .scratch/tick-guard-red-M1-957.log: removing the top-chips canonical
  entry reds the guard leg in the DEFAULT suite with the M1-946 r1 abort
  message.
analysis_ref: docs/plan/m1/tick-analysis/retrieval-campaign-followups.md
blocked_by: []
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalEvalRunnerIT.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalGoldenSetTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/chat/tool/eval/RetrievalEvalCharacterizationIT.java
  - docs/measurement/retrieval-eval-two-leg.md
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production / main-source change — the arm mirrors the dispatch layer
    through the PRODUCTION parser and the PRODUCTION tool bean; probe:
    git diff --name-only names no src/main path.
  - >-
    ANY TemporalExpressionParser grammar change — the two "from the past day"
    parse misses are a PRODUCTION observation (the chat dispatch layer misses
    the same phrasings), flagged to the driver here, never fixed in the eval
    lane; probe: git diff names no TemporalExpressionParser.java hunk.
  - >-
    ANY spec/design edit, ANY golden-set edit (labels describe shipped
    retrieval), the POM tag mechanics, and the %eval stub exclusion — all
    byte-identical.
  - >-
    ANY infochat.chat.semantic-limit / semantic-threshold change — the
    width-32 lever is M1-959's and stays undecided (analysis P17).
  - >-
    Any mutation of either world's search space (backfill, re-embed sweep,
    restore) — the frozen fingerprints and coverage pins are the delta's
    comparability (analysis P18); live fam and prod containers are never
    targets (postgres + embedder + translator only, the M1-950 posture).
acceptance:
  - "REPRODUCTION closed: RetrievalGoldenSetTest.activeTemporalRowsParseToPinnedWindowsAtTheWorldNow passes — the pinned per-row map over BOTH committed golden sets at (ZoneOffset.UTC, the world's pinned now): 13 parse hits per leg's temporal classes (today -> since-UTC-midnight durations at each leg's own world now; 'last/past N hours' -> PT N H) and the two grammar misses (t24-4, fam-t24-3) pinned as MISSES — a grammar drift or a fixture edit that changes any temporal row's parse outcome reds the leg (the exact-pin discriminator)."
  - "The arm is parse-gated, never class-gated (analysis P1): RetrievalEvalRunnerIT.executeGolden derives per-row dispatch args by running the PRODUCTION TemporalExpressionParser.parse on every EN row's raw query at (ZoneOffset.UTC, worldNow) — parse hit -> Map.of(\"query\", q, \"_window\", duration.toString()); parse miss -> Map.of(\"query\", q) EXACTLY (byte-identical dispatch to today); non-en rows keep exactly {query} (analysis P5, en anchoring is the D58 no-op so the arm uses no LLM). Probes: grep -n 'TemporalExpressionParser' over the eval package returns the runner's arm and the unit legs only; git diff --name-only names no src/main path."
  - "Determinism of the arm (analysis P2/P3; docs/spec/llm.md §Determinism boundary — same DB state -> same rows): the tool's Clock is pinned via QuarkusMock.installMockForType(Clock.fixed(worldNow, ZoneOffset.UTC)) where worldNow = worldMaxReadyAt(fingerprint1), installed BEFORE the first dispatch and held through pass 2 (one instant drives BOTH the parse and the tool's ready_at cutoff — SemanticSearchTool.java:170); the manifest gains window_arm=true, window_zone=UTC, world_now; queries.jsonl gains a per-row \"window\" (ISO duration or null). Probe: an owner run's manifest artifact under .bench/retrieval-eval/ resolves every new key; both determinism invocations' per-query uid lists byte-identical (harness-asserted, restated in the record section)."
  - "Every runner fence is behavior-identical (engineering-rules §10; analysis P8): sentinel, stub-exclusion, inter-pass drift, label-fingerprint refusal, double-run determinism, en-zero-translator-calls, fallback abort (RetrievalEvalRunnerIT.java:264-336) — reviewer diff check: no fence method is touched; the manifest/queries key set is additive only; the world seam and POM containment untouched."
  - "FAILURE-MODE (the widening guard, analysis P6 — the M1-946 round-1 rework class closed at build time): RetrievalGoldenSetTest.everyActiveXlingSiblingIdHasAnAuthoredCanonicalPhrasing passes in the DEFAULT suite — it loads the tech golden set through the world seam and derives the active-xling sibling ids via the characterizer's OWN pairsWithSiblings (the exact method whose guard aborted M1-946 r1), then asserts every sibling id resolves in CANONICAL_BY_NEED; RED under the M1-946 r1 mutation shape (remove the top-chips entry -> the leg fails under mvn verify, NOT at the next operator characterization run)."
  - "M1-955 non-interaction (analysis P7): git diff over RetrievalEvalCharacterizationIT.java is VISIBILITY-ONLY (CANONICAL_BY_NEED and pairsWithSiblings private -> package-private static; the M1-937 precedent) — assertEmissionShape's six-field pin and every other assertion byte-identical; probe: git diff over the IT shows no hunks beyond the two modifiers."
  - "OWNER-RUN delta recorded (analysis P9; the M1-938 creditor leg): docs/measurement/retrieval-eval-two-leg.md gains ONE appended dated section (git diff over the record is pure additions) carrying: both legs' window-armed runs (two invocations each, determinism legs restated), per-leg pins byte-equal to the 2026-08-30 readings (fingerprint, golden_set_sha256, world_embedding_coverage, threshold/limit) plus the new window_arm/window_zone/world_now keys, the temporal classes' movement vs the 2026-08-30 unwindowed readings as the paired INSTRUMENT delta (T1 vocabulary per leg: discordant counts, floor 6, never pooled across legs — TL3; absolute counts only — N1), the DISCLOSURE that t24-4/fam-t24-3 parse-miss and ran unwindowed (analysis P4) and that non-en rows are un-armed because en anchoring is identity (P5), and the do-not-settle restatement. Probes: grep -n 'window_arm' over the record returns the section; grep -n 'DESCRIPTIVE\\|descriptive' returns the movement framing; git diff shows added lines only."
  - "mvn verify from the repo root is green with the eval stack ABSENT (the two new default-suite legs run; the runner/characterization ITs stay CI-excluded — the M1-950 verify-log probe)."
test_plan:
  adds:
    - >-
      RetrievalGoldenSetTest — activeTemporalRowsParseToPinnedWindowsAtTheWorldNow
      (the reproduction), everyActiveXlingSiblingIdHasAnAuthoredCanonicalPhrasing
      (the widening guard), and the temporal-rows-are-en leg (P5's residual made
      mechanical: every active temporal row of both sets is en-scoped, else the
      arm's en-only scope is incomplete and the leg fails).
  modifies:
    - >-
      RetrievalEvalCharacterizationIT (AUTHORIZED: visibility-only widen of
      CANONICAL_BY_NEED and pairsWithSiblings to package-private static —
      zero behavior change; the guard leg reuses the exact aborting code path
      so the two surfaces cannot drift).
    - >-
      RetrievalEvalRunnerIT (AUTHORIZED: executeGolden gains the parse-gated
      arm; the pinned Clock; additive manifest/queries keys — every fence and
      existing key behavior-identical).
  preserves:
    - >-
      every runner fence; the golden sets consumed read-only; the world seam;
      the POM tag containment; all tests currently green on main.
spec_refs:
  - docs/spec/security.md §Prompt-injection defenses
  - docs/spec/commands.md §Chat mode
  - docs/spec/commands.md §Content
  - docs/spec/llm.md §Determinism boundary
decision_refs:
  - D19
  - D29
  - D58
  - D59
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
    date: 2026-08-31
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS: PASS; SECURITY: PASS; TEST-ADEQUACY: PASS; MAINTAINABILITY: PASS; SCOPE: PASS — 6 falsification candidates dropped with citations (Clock-mock reachability defeated by @ApplicationScoped proxying + install-before-first-dispatch + the recorded runs' non-empty short windows and 126/126, 92/92 uid identity; QuarkusMock-vs-§8 defeated by §9 prescribing the pattern verbatim; record §13 defeated by the pins being byte-equal restatements of already-committed fixture-world pins; window_zone=Z vs acceptance's UTC defeated by ZoneOffset.UTC.getId() identity + the record's 'Z (UTC)' disclosure; fam-unguarded defeated by CANONICAL_BY_NEED keying exactly the tech set the characterizer consumes; worldNow-helper duplication defeated by the runner method being private + the exact-pin leg red-ing on drift). Verdict: .scratch/tick-review-M1-957-r1.txt"
    diff_stats: "6 files, +351/-26 (RetrievalGoldenSetTest.java +106 three new default-suite legs, RetrievalEvalRunnerIT.java +46/-14 window arm + pins, RetrievalEvalCharacterizationIT.java 2-modifier visibility widen, retrieval-eval-two-leg.md +166 append-only dated section, ticket frontmatter bookkeeping, board regen)"
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  checked: 2026-08-31
  result: >-
    Self-check clean, no blocking question. Lint 0 findings. All file:line
    citations spot-checked true: RetrievalEvalRunnerIT.java:239 exact
    {query} dispatch, :222/:465 worldMaxReadyAt, :264-336 fences;
    TemporalExpressionParser.java:49 pure public parse; SemanticSearchTool
    :93-94 @Inject Clock + :170 cutoff; RetrievalEvalCharacterizationIT
    :57-61/:124/:311-313/:349. Census re-ran clean (CANONICAL_BY_NEED:
    one file, four rows; the runner's :239 the arm's only seam — the
    characterization IT's :253 dispatch is the M1-945 anchor-leg
    instrument, deliberately unwindowed and visibility-only here per
    acceptance item 6). Analysis P1-P9 all landed in the ticket; P17/P18
    restated in out_of_scope. blocked_by empty, replaces empty.
    Parse-map verified against the committed fixtures: 13 hits per leg +
    t24-4/fam-t24-3 misses at the two pinned world nows.
escalation_reason:
---

# M1-957: Arm the eval lane's temporal window + widening guard

## Context

M1-938 landed temporal windowing through the CHAT layer: the dispatch layer
deterministically parses the anchored query (commands.md §Chat mode,
D19) and the window composes AND inside both search arms (security.md
§Prompt-injection defenses, the semanticSearch row's `_window` input). The
eval lane — the instrument the campaign gates every retrieval claim through
— never got the arm: the runner dispatches exactly `{query}`
(RetrievalEvalRunnerIT.java:239), so every temporal row runs unwindowed and
the landed change was invisible (temporal-today/2h 0.100/0.100 byte-identical
across M1-938's landing). M1-938 fenced the eval lane out deliberately (its
P16) and recorded this ticket's obligation in its acceptance item 15. The
second half closes the M1-946 round-1 review observation: a widening that
adds an xling need without a canonical entry ships green and aborts the
characterizer only at the next operator run. Shared analysis:
`analysis_ref:` (Ground truth, Pitfalls P1-P9, options A-E).

## Root cause

Not a code defect — a deliberately deferred instrument extension plus an
un-owned default-suite gap. Verified: the runner's dispatch construction
(RetrievalEvalRunnerIT.java:239) is the only seam the arm needs; the
production parse is pure and public
(`TemporalExpressionParser.parse(String, ZoneId, Instant)`,
TemporalExpressionParser.java:49 — zone and now are parameters); the tool's
cutoff reads the injected Clock (SemanticSearchTool.java:170), so a pinned
`Clock.fixed(worldNow)` reproduces a production turn AT the frozen world's
now (the §9 test convention); every runner fence is a static/shared method
that never sees the dispatch args. For the guard: `CANONICAL_BY_NEED`
(:57-61) and the aborting pairing guard inside `pairsWithSiblings`
(:311-313) are private to the CI-excluded characterizer IT — M1-946 r1
proved the failure mode (its diff shipped green, the next operator run
aborted "no authored canonical phrasing for need top-chips"), and its
review recommended exactly this default-suite leg.

## Pitfalls

Carried from the analysis, numbered identically.

- P1: parse-gated, never class-gated — the dispatch layer keys on the
  PARSE (ChatAgent.java:937-942); a class-gated arm measures a fiction and
  drifts from production the moment a row's phrasing changes.
- P2: determinism — the parse instant AND the tool's cutoff clock must be
  ONE pinned instant (the fingerprint's world now); a wall clock empties
  short windows on a frozen corpus and breaks run-to-run uid identity
  (llm.md §Determinism boundary; §9's one-clock discipline).
- P3: zone — the lane's DM default is UTC; pin explicitly, record in the
  manifest (a hidden zone input is the §9 hazard).
- P4: parse-miss disclosure — t24-4/fam-t24-3 ("from the past day")
  parse-miss by grammar; the arm runs them unwindowed BY DESIGN; pin the
  map, disclose in the record, never widen the grammar here.
- P5: non-en residual — the arm anchors nothing (en is the D58 no-op);
  non-en rows run unwindowed; no non-en temporal row exists — pin it.
- P6: guard placement — default-suite execution reusing the characterizer's
  OWN pairing guard (visibility widen), never a re-implementation that
  drifts.
- P7: M1-955 interaction — the IT was just re-pinned (e596cbf2); this
  diff is visibility-only and every M1-955 pin stays byte-identical.
- P8: no fence weakens — the arm adds dispatch args only; parse-miss rows
  stay byte-identical to the pre-arm runs (§10).
- P9: delta recording — append-only dated section; the movement is a
  paired instrument delta (T1 vocabulary per leg), never cross-leg (TL3),
  absolute counts only (N1), pins restated (D1 + the coverage clause).

## Approach

Derived from `spec_refs:` — security.md §Prompt-injection defenses owns the
semanticSearch row whose `_window` input the arm exercises; commands.md
§Chat mode owns the D19 parse the arm mirrors; commands.md §Content fixes
what the window measures (ready_at); llm.md §Determinism boundary fixes the
pinned-world premise (same DB state -> same rows, clock as legitimate
input).

- **Files to touch** — `files_scope`: the runner (arm + pins), the golden-
  set validator test (three new default-suite legs), the characterizer IT
  (visibility-only), the two-leg record (one appended section).
- **Pre-decided shapes (implementation is execution):**
  1. **The unit legs first (RED per workflow §0):** the parse-map pin over
     both sets at (UTC, each world's pinned now) — the map: tech 13 hits
     (tt-* since-midnight durations at 2026-08-24T16:00:57Z; t2h-* PT2H;
     t24-1/2/3 PT24H) + t24-4 miss; fam 13 hits + fam-t24-3 miss; plus the
     temporal-rows-are-en leg.
  2. **The guard leg:** visibility-widen `CANONICAL_BY_NEED` +
     `pairsWithSiblings` (private -> package-private static), then
     `everyActiveXlingSiblingIdHasAnAuthoredCanonicalPhrasing` calls
     `pairsWithSiblings(load(techBytes), techBytes)` from the default
     suite — the exact aborting guard, now build-time.
  3. **The arm:** in `executeGolden`, for an en row, parse the raw query at
     (ZoneOffset.UTC, worldNow); build args per P1. The Clock pin installs
     once in the test method after `fingerprint1` is read (worldNow =
     `worldMaxReadyAt(fingerprint1)`), before any dispatch, held through
     pass 2. Manifest keys `window_arm`/`window_zone`/`world_now`;
     queries.jsonl per-row `window`.
  4. **The record:** one appended dated section per the acceptance item.
- **Steps in order:** (1) the unit legs RED; (2) the guard leg + the
  visibility widen; (3) the arm + pins; (4) `mvn verify` green; (5) owner
  runs both legs (two invocations each); (6) append the record section.
- **Controls to preserve (§10):** every runner fence behavior-identical;
  the eval-boot key set; the golden sets read-only; the world seam; POM
  containment; the M1-955 emission pin byte-identical; the record's landed
  sections byte-identical (append-only).
- **Pitfall→mitigation:** P1→shape 3; P2/P3→shape 3's single pinned
  instant + manifest pins; P4→shape 1's pinned map + the record
  disclosure; P5→the en-leg; P6→shape 2's reuse; P7→the visibility-only
  fence; P8→the fences' diff check + the byte-identity restatement in the
  record; P9→shape 4's probes.
- **Alternatives considered (rejected; the commit message cites them):**
  class-gated arm (B — measures a fiction, P1); routing the lane through
  ChatAgent (C — breaks M1-929's tool-only charter, massive scope); a new
  absolute-cutoff tool arg (D — production change); a second in-IT guard
  (E — the IT never runs in CI; reachability is the fix).

## Definition of done

The reproduction and the widening-guard leg pass in the default suite; the
parse map and the en-only scope are pinned; the arm is parse-gated with one
pinned world instant driving parse and cutoff alike; the manifest/queries
pins resolve on an owner run; every fence is behavior-identical and the
M1-955 pin untouched; both legs' window-armed runs are recorded in one
appended record section with pins, per-leg T1 vocabulary, the two
disclosures, and the do-not-settle restatement; repo-root `mvn verify` is
green.

## Verification

- P1 → acceptance item 2's probes (the runner calls the PRODUCTION parser;
  grep fence) + the pinned map (a class-gated arm cannot produce the
  misses the map pins).
- P2 → acceptance item 3 (one pinned instant; determinism legs
  harness-asserted; the manifest world_now pin) — a wall-clock cutoff
  collapses temporal-2h returns, which the delta section's movement would
  expose as a no-op delta.
- P3 → acceptance item 3's `window_zone` manifest pin + the explicit
  ZoneOffset.UTC in the arm (grep).
- P4 → acceptance item 1's pinned misses + the record disclosure
  (acceptance item 7).
- P5 → the temporal-rows-are-en leg (test_plan.adds) + the record
  residual.
- P6 → acceptance item 5's leg, RED under the named mutation (remove the
  top-chips canonical entry) IN the default suite.
- P7 → acceptance item 6's visibility-only diff probe.
- P8 → acceptance item 4's reviewer diff check; the record's
  unchanged-class byte-identity restated (acceptance item 7).
- P9 → acceptance item 7's probes (pure additions; TL3/N1 framing; pins
  resolve).
- FAILURE-MODE coverage → acceptance items 1 (the pinned map discriminates
  grammar/fixture drift both directions) and 5 (the M1-946 r1 mutation
  shape, at build time).
- acceptance item 8 → the verify-log probe.

## Out-of-scope

Named in `out_of_scope`: any production change; any parser grammar change
(the two misses are a production observation flagged to the driver); spec/
design edits; golden-set edits; POM/tag mechanics; the width/threshold
knobs (M1-959's, undecided); any world mutation or live-instance target.
The two pre-existing files modified are authorized in `test_plan.modifies`
with the visibility-only and additive shapes stated in plain language
(engineering-rules §8); every other pre-existing test passes unmodified.

## Census

The class the widening guard guards: **committed sites that pair active
xling rows with canonical phrasings.** Re-runnable:
`grep -rn 'CANONICAL_BY_NEED' infochat-provider/src/`. Rows (verified at
draft time):

- RetrievalEvalCharacterizationIT.java:57-61/:124/:311/:349 — the map, its
  dispatch use, the aborting guard, the manifest citation → **FIX** (this
  ticket: the guard made default-suite-reachable via visibility widen; the
  map itself untouched).
- RetrievalGoldenSetTest (the new leg) → **FIX** (the build-time tie).
- No other site exists (grep returns nothing else).

The arm's census: the runner's single dispatch construction
(RetrievalEvalRunnerIT.java:239) is the only seam — no other eval class
constructs semanticSearch args (grep over the eval package).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-957-eval-window-arm-and-widening-guard.md
```

## Review observations

- (r1, RECOMMENDED-NEW-TICKET, TOUCHED-BY-THIS-DIFF: no) The production
  temporal grammar misses "… news from the past day" phrasings: COUNTED
  requires a digit and no calendar token matches, so a user asking
  "security news from the past day" gets an unwindowed chat pre-fetch
  (TemporalExpressionParser.java:26-29). This ticket pins the miss in the
  default suite (t24-4, fam-t24-3) and flags it as a production
  observation; a grammar widening is a production behavior change needing
  its own analysis and decision. Filing is the user's call.
