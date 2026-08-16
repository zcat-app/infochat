---
id: M1-864
title: "Measure the tag-tree leaf vocabulary on the local model"
status: done
created: 2026-08-16
last_updated: 2026-08-17
flow: tick
reproduction: >-
  Probe (evidence gap, verified 2026-08-16): (1) grep -rl
  'tag-tree\|tree taxonomy\|leaf vocab\|tag vocabulary' over
  docs/measurement/ returns NO file — no committed measurement record
  covers the v2 tree vocabulary; the only tagger numbers there are
  model-screening scores against the CURRENT flat vocabulary
  (docs/measurement/track-a-screening-in-progress.md). (2) The showcase
  that de-risked the four load-bearing assumptions
  (.bench/tag-tree-showcase/, 31 fixtures x 93 calls, GREEN) ran
  deliberately WITHOUT pre-registered bars — its own scorer prints "no
  bars were pre-registered for this showcase (smell-test run — the real
  campaign pre-registers)" (score-showcase.py:127-128). (3)
  docs/measurement/tag-vocabulary.md does not exist on main (the M1-860
  branch carrying it was deleted at abandonment) — the flat-vocabulary
  rejection numbers live only in gitignored .bench artifacts and ticket
  prose. Wrong behavior stated: NOTHING pre-registers or measures,
  against production prompt shape, whether the deployment's local tagger
  model applies the frozen v2 leaf list at the depth-decomposed bars
  (resolved-TOP stability, leaf stability, AI-policy routing, continent
  distribution, injection retention, budget) before the v2 seed lands
  and an operator relies on it.
analysis_ref: docs/plan/m1/tick-analysis/tag-tree-taxonomy-v2.md
blocked_by: []
files_scope:
  - docs/measurement/tag-tree-taxonomy.md
complexity: high
risk: low
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production code, prompt text, migration, or docs/spec/** edit. This
    ticket produces evidence only; the seed that consumes the frozen leaf
    list is M1-866 (blocked_by this ticket among others). No verdict here
    lands a tag row.
  - >-
    COMMITTING .bench/ working data (gitignored by design). Only the
    promoted record at docs/measurement/tag-tree-taxonomy.md is committed.
  - >-
    RE-RUNNING M1-860 PHASES — the flat-vocabulary campaign is complete and
    scored (.bench/tag-vocab/score.json); its arms, fixtures, and verdicts
    are closed. This campaign gets a NEW fixture set for the tree and does
    not re-litigate the flat rejection.
  - >-
    CHANGING the tagger prompts, sampling posture, TagVocabulary, or
    TaggerWorker — the spike MEASURES the production shape (the showcase
    verified the leaf render through the UNEDITED prompts/tagger.md); it
    does not alter it. M1-751's out-of-scope rulings carry over.
  - >-
    Remote/LLM-API model arms — the campaign runs the deployment's local
    tagger-slot weights (gemma-4-26B-A4B-it-UD-Q6_K_XL per the user
    ruling); other weights may appear only as context rows, never as new
    arms.
acceptance:
  - "Pre-registered DEPTH-DECOMPOSED bars land BEFORE any arm runs and the record proves the order: the committed record opens with the bars — resolved-TOP agreement across resamples at/above the re-measured leaf-render noise floor (target band >= 0.92 subject to the floor rule below); leaf-stability-within-winning-branch as a SEPARATE realistic bar explicitly deferred until after the per-leaf competency leg (informed by measured leaf noise 0.52-0.96, M1-860 B2); AI-policy -> Tech/ai >= 0.9 with policy content NOT migrating to News; no single News continent node > 50% of News-attributed output; injection-retention no-regression; prompt-budget ceilings (bytes, p99, schema-violation/fallback rates) — probe: git log --follow docs/measurement/tag-tree-taxonomy.md shows the thresholds commit predating every results commit (analysis P1)."
  - "A same-prompt resample NOISE FLOOR is measured FIRST on the LEAF RENDER (the M1-860 floor 0.9006 was measured on the 23-name flat vocabulary, not a ~46-leaf render) and every headline bar exceeds it; the pre-registered margin rule allows re-registration only UPWARD (floor + 0.01) in its own pre-results commit, never downward, never silent — probe: grep -n 'NOISE-FLOOR' docs/measurement/tag-tree-taxonomy.md shows the floor value and every headline bar printing a strictly positive margin; the record cites the M1-860 floor 0.9006/0.7509 and the M1-751 prior (0.783/5-of-10 identical) as sanity anchors (analysis P1)."
  - "PER-LEAF COMPETENCY GATE: each leaf in the candidate list passes a B1-style application test on its domain content (>= 3 fixtures x 3 resamples per leaf) at the pre-registered bar; a leaf below the bar is REJECTED with its numbers and either fixed (re-worded/re-parented) or dropped — the surviving list is the FROZEN list M1-866 seeds verbatim. The gate covers the sibling pairs the showcase flagged (ai<->cybersecurity) and the personal-vs-athletics definitional ruling, which the record's leaf glossary states BEFORE fixtures are authored — probe: grep -n 'COMPETENCY\\|WINNING' docs/measurement/tag-tree-taxonomy.md shows per-leaf cells and the frozen list (analysis P1, decision 3)."
  - "AI-POLICY ADVERSARIAL LEG (B3 successor): EU-AI-Act / liability / biometric-regulation shapes score ai present >= 0.9 at the leaf (or Tech at top level with the sibling-confusion carve-out recorded), and the News share of that content is asserted NOT to exceed its non-policy baseline — scored on PROPOSED tags where must_not applies, never post-validation survivors — probe: the adversarial cells in the record; the record states the proposed-tags scoring rule (analysis P3; spec: docs/spec/llm.md §Failure handling (recap))."
  - "NEWS-DISTRIBUTION LEG: general geopolitics fixtures distribute across continents with no single continent node > 50% (distinct validated tuples, the showcase's method — world measured 6/12 = 0.50 at, not over, the bar); content that fits a real category routing there, never to News, is recorded as CORRECT (showcase: COP fixtures -> environment) — probe: grep -n 'CONTINENT\\|News-distribution' docs/measurement/tag-tree-taxonomy.md (analysis, Ground truth)."
  - "INJECTION-RETENTION and BUDGET legs carry over unchanged from M1-860's method: the track-a injection-shape fixtures re-run under the leaf vocabulary with per-arm must_not violation counts (a compliance loss is a named rejection, never averaged); rendered prompt bytes, p99 latency, schema-violation/fallback rates tabulated against the M1-860 baseline row (showcase measured +17.8% at 46 leaves, p99 1.22 s — the campaign re-measures at the frozen list's true size) — probes: grep -n 'INJECTION-RETENTION\\|PROMPT-BUDGET' docs/measurement/tag-tree-taxonomy.md (spec: docs/spec/security.md §Prompt-injection defenses (LLM call sites) — the wrapper + treat-as-data promise; analysis P2)."
  - "The harness reuses the M1-860 shape (run.py/score.py/run-batch.sh against llama-server; the app is out) and the record states the render path: real prompts/tagger.md AND tagger-fallback.md, {#tags} expanded one line per name in ORDER BY name order (leaf-only render), <<<UNTRUSTED_CONTENT>>> wrapper with a fresh random delimiter per call, production request shape with no temperature, full fallback chain reproduced per call — probe: grep -n 'harness\\|llama-server' docs/measurement/tag-tree-taxonomy.md; the server + phases run inside ONE setsid'd detached session (run-batch.sh pattern) and the runner ABORTS loudly on UNREACHABLE without writing rows (analysis P2, P4)."
  - "Measured state pinned: the leaf-list snapshot sha256 per arm, the repo commit, the model identity (served from /home/infochat/.local/share/docker/volumes/infochat-llamacpp-models/_data/gemma-4-26B-A4B-it-UD-Q6_K_XL.gguf — NOT the stale track-a arms.json path), llama.cpp version, and prompt-file shas — probe: grep -n 'sha256\\|commit\\|model' docs/measurement/tag-tree-taxonomy.md; every leaf name is English and matches ^[a-z0-9][a-z0-9-]{0,47}$ and the record states the filter (analysis P5, P20; spec: docs/spec/commands.md §Surface conventions)."
  - "verify-against-java.py runs green before any NEW scoring code is trusted over stored calls — probe: the record's method section names the run (analysis P2)."
  - "mvn verify from repo root is green (evidence-only ticket; engineering-rules §5)."
test_plan:
  adds: []
  preserves:
    - all tests currently green on main
  notes:
    - >-
      Evidence-only: the campaign harness lives under .bench/ (gitignored)
      and the promoted record is the single committed artifact (the
      M1-844/M1-860 shape). No JUnit surface to add; mvn verify covers the
      no-regression leg. No test_plan.modifies entries — any pre-existing
      test edit would be an unauthorized engineering-rules §8 change.
spec_refs:
  - docs/spec/llm.md §SPI shape
  - docs/spec/llm.md §Failure handling (recap)
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
  - docs/spec/commands.md §Surface conventions
decision_refs:
  - D5
  - D19
  - D22
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
    date: 2026-08-17
    verdict: REWORK
    checks: {SPEC-TRUTHNESS-CHECK: FAIL, SECURITY-CHECK: PASS, TEST-ADEQUACY-CHECK: PASS, MAINTAINABILITY-CHECK: PASS, SCOPE-CHECK: PASS}
    diff_stats: "3 files changed, 489 insertions(+), 9 deletions(-)"
  - round: 2
    date: 2026-08-17
    verdict: APPROVE-WITH-FIXES
    checks: {SPEC-TRUTHNESS-CHECK: WARN, SECURITY-CHECK: PASS, TEST-ADEQUACY-CHECK: PASS, MAINTAINABILITY-CHECK: PASS, SCOPE-CHECK: PASS}
    diff_stats: "fix diff 3 files, 55 insertions(+), 18 deletions(-) (record +21/-9; remainder round bookkeeping); both round-1 REWORK items dispositioned SATISFIED"
    fix_probes: "grep 'all 28' record → no matches; grep '159 expected-top' record → line 329; ./mvnw -B test-compile exit 0 (3.1 s, incremental); fixed tree f016f1d2 (.scratch/tick-fixes-M1-864.tree)"
    test_log: .scratch/tick-test-M1-864-r2.log (BUILD SUCCESS 2026-08-17 00:15)
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  date: 2026-08-16
  verdict: PASS
  warnings: []
  blockers: []
  notes: >-
    Lint clean after syncing the gitignored analysis doc into the worktree
    (tick-analysis/ is untracked by design, .gitignore:64). Reproduction
    probes re-verified live: no tag-tree measurement file under
    docs/measurement/; tag-vocabulary.md absent; score.json noise_floor
    0.9006/0.7509, b3 politics 0.8333, b5 marginal — all match ticket
    citations; showcase score-showcase.py:127-128 barless note confirmed;
    pinned GGUF path exists; M1-860 harness and
    track-a/verify-against-java.py present. Pitfalls P1-P5+P20 all landed
    in the ticket.
escalation_reason:
---

# M1-864: measure the tag-tree leaf vocabulary on the local model

## Context

The flat Tier-1 vocabulary is measured-dead: M1-860's campaign rejected
every flat candidate (B3 politics-magnet 83.3% on AI-policy content; B2
drift with 23/24 per-tag cells below the 0.92 bar per
`.bench/tag-vocab/score.json`; B5 marginal injection regression), and the
user ruled the v2 tree taxonomy in (disjoint tops, depth-2 leaves,
deterministic Java resolution, News geographic fallback, bounded Others).
The 2026-08-16 showcase (`.bench/tag-tree-showcase/`, 31 fixtures × 93
calls on gemma-4-26B-A4B, GREEN) de-risked the four load-bearing model
assumptions — leaf discrimination 1.0, politics-magnet gone (12/12
top-level Tech on adversarial content), continents distributed (max node
share 0.50), budget +17.8% — but ran deliberately WITHOUT pre-registered
bars. This ticket is the real campaign: pre-registered depth-decomposed
bars, a bigger fixture set, a committed record, and the per-leaf
competency gate that FREEZES the leaf list M1-866 seeds. Shared context:
`analysis_ref:` (analysis doc, Pitfalls P1–P5, P20).

## Root cause

Not a code defect — an evidence gap. No committed measurement record
covers the tree vocabulary (reproduction probe 1); the showcase's own
scorer states it ran barless (probe 2); and the M1-860 record never
landed on main (probe 3). Decision 3 makes every leaf pass a per-leaf
competency gate before the list freezes — that gate does not exist
anywhere. The ticket is still safe to start now: it produces evidence
only, and the showcase already proved the harness and the prompt shape
work (93/93 clean attempt-1 calls through the UNEDITED production
prompts).

## Pitfalls

Numbered per the analysis document; the ones that bite THIS ticket:

- P1: barless measurement — the showcase's smell-test posture does not
  carry over; bars first, floor first, margins provably positive,
  thresholds commit provably first.
- P2: harness fidelity — render the real prompts in ORDER BY name order
  with the wrapper and fresh delimiter; run verify-against-java.py
  before trusting new scoring code (M1-751 was filed on harness drift).
- P3: vacuous scoring — must_not/adversarial predicates score on
  PROPOSED tags (validation drops out-of-vocab proposals before
  storage).
- P4: reaped servers — one setsid'd session per batch; ABORT on
  UNREACHABLE, never write garbage rows.
- P5: stale model path — serve gemma from the docker-volume GGUF path;
  do not trust track-a arms.json.
- P20: pins and hygiene — sha-pin the leaf list, commit, model,
  prompt files; only the record lands.

## Approach

Offline measurement campaign, M1-860 harness reuse (run.py / score.py /
run-batch.sh against llama-server on this box; the app is out).

- **Files to touch:** `docs/measurement/tag-tree-taxonomy.md` (new
  record). Everything else lives under `.bench/` (gitignored).
- **Steps, in order:**
  1. Author the leaf glossary FIRST (including the personal-vs-athletics
     definitional ruling the showcase flagged), the candidate leaf list
     (the showcase's 46-leaf draft as the starting point), and the
     pre-registered depth-decomposed bars; commit the record skeleton —
     order is the whole value of pre-registration (P1).
  2. Build the fixture corpus: per-leaf domain content (≥3 fixtures per
     leaf, Stage-1-shaped bodies: NFKC-clean, no bidi/zero-width,
     `[REDACTED:<id>]` placeholders where flagged content belongs), the
     sibling-pair discriminators (ai↔cybersecurity, football↔esports vs
     gaming), cross-top conflicts (sponsorship, AI-in-sport), the
     AI-policy adversarial set, geopolitics continent set, drift items
     from the campaign snapshot, and the track-a injection fixtures
     verbatim (P3).
  3. Measure the same-prompt resample noise floor ON THE LEAF RENDER;
     apply the margin rule (re-register only upward, pre-results) (P1).
  4. Run the per-leaf competency gate; freeze the winning list; record
     rejections with numbers (P1).
  5. Run the full arms (leaf arm at the frozen list; baseline context
     row from the M1-860 record, not re-run); score the depth-decomposed
     cells: resolved-TOP agreement (apply the deterministic resolution —
     the showcase tree's fixed top-priority order — to each resample's
      validated set and compare TOPS across resamples), leaf stability
     within the winning branch, AI-policy routing, continent
     distribution, injection retention, budget (P2/P3).
  6. Write the frozen list + rejection log + pins; land the record
     (P20).
- **Controls to preserve:** none rerouted (no production code touched);
  the production prompts, `TagVocabulary`, and `TaggerWorker` are
  measured objects, not edit targets (out_of_scope).
- **Pitfall→mitigation:** P1→steps 1/3/4; P2→step 5 harness
  requirements + verify-against-java; P3→scoring rule in acceptance 4;
  P4→run-batch setsid pattern; P5→pinned serve path; P20→step 6.

## Definition of done

Every acceptance item holds with its named probe green: bars
pre-registered and provably first; leaf-render noise floor measured and
exceeded with positive margins; per-leaf competency cells and the FROZEN
list; AI-policy adversarial cells with proposed-tags scoring; continent
distribution cells; injection-retention and budget tables; harness
statement with pins (sha256/commit/model/llama.cpp/prompt shas);
verify-against-java named; `mvn verify` green. The record is the single
committed artifact.

## Verification

- P1 → acceptances 1–3: the record's bar section + `git log --follow`
  ordering probe; floor-margin cells; competency gate output.
- P2 → acceptance 7: render-path statement + prompt-file shas;
  acceptance 9: verify-against-java run named.
- P3 → acceptance 4: the "scored against PROPOSED tags" method statement
  + adversarial cells.
- P4 → acceptance 7: the setsid/run-batch + ABORT-on-UNREACHABLE
  statement in the record's method section.
- P5 → acceptance 8: the pinned GGUF path + model identity.
- P20 → acceptance 8 probes (sha256/commit/model) + the record's
  artifact list naming the single committed file.
- Failure-mode legs (mandatory): acceptance 4 (feeds AI-regulation
  content; asserts policy content does not migrate to News while ai
  coverage holds) and acceptance 6 (feeds self-describing/injection
  bodies; asserts the wrapper discipline holds under the leaf
  vocabulary — a compliance loss is a named rejection).
- acceptance 10 → `mvn verify` exit 0.

## Out-of-scope

See `out_of_scope:` — production/spec surfaces (M1-865..M1-869's job),
.bench commits, re-running M1-860 phases, prompt or sampling changes,
remote model arms. This ticket modifies NO pre-existing test
(`test_plan.modifies` empty); any such edit is an unauthorized
engineering-rules §8 change.

## Census

Not class-scoped: a measurement campaign, not a fix guarding a class of
defect sites. (The no-hardcoded-vocabulary-names property is probed in
M1-866.)

## Round 1 rework

1. Finding 1: correct the record's corpus accounting and floor cell —
   78 news-expected rows / 26 fixtures / 18 leaf-news in the
   News-distribution finding and the Cycle-5 note; Results noise-floor
   table prints the binding final floor 0.9266/0.8092 (0.9259/0.8153
   moves to the earlier-measurements parenthetical); one sentence
   reconciling the per-top sums (159 expected-top triples; the 7
   injection fixtures carry no expected top) — evaluated via `grep -n
   '84 news-expected\|21 leaf-news\|28 news-expected'
   docs/measurement/tag-tree-taxonomy.md` (must return nothing) plus
   `grep -n '0\.9266'` showing the consistent floor.
2. Finding 2: disclose the post-results bar-rule scope edit — a
   Registration-updates bullet naming ac3e6424 (post-results,
   scope-only, no bar number moved, no verdict flips under either
   reading) and the results-commit pin repointed to 7666cc83 —
    evaluated via `grep -n 'ac3e6424\|7666cc83'
    docs/measurement/tag-tree-taxonomy.md`; the acceptance-1
    `git log --follow` probe must stay green.

## Review observations

- Round 2 recommended-new-ticket (recorded, TOUCHED-BY-THIS-DIFF: no,
  no DECIDE-BEFORE — user reads, no decision requested here): the
  leaf-stability row prints "92/95 single-leaf triples" with no sentence
  defining which 95 of the 166 fixtures count as single-leaf triples.
  Commit-body one-liner: "review note (M1-864): leaf-stability
  denominator 95 single-leaf triples undefined in the record — candidate
  one-line record follow-up."
