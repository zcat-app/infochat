---
id: M1-952
title: "Two-leg baseline record + pre-registered gating rules"
status: done
created: 2026-08-28
last_updated: 2026-08-30
flow: tick
reproduction: >-
  Probe (measurement ticket, the M1-930/M1-944 posture — the missing record
  IS the defect): `ls docs/measurement/retrieval-eval-two-leg.md` returns
  "No such file or directory" (verified 2026-08-28; docs/measurement holds
  retrieval-eval-baseline.md, anchor-leg-characterization.md, 14 others) —
  no committed record states the two-leg reading/gating rules, so the
  width-32 lever (the shadow menu's best measured move, TECH-world only)
  and every future product-wide retrieval claim have NO two-leg gate to be
  read against, and the two-leg methodology (a claim gates only when it
  holds on both legs; a corner case found on one leg is extrapolated/
  verified against the other — the user's stated methodology) is recorded
  nowhere durable. Intended entry: the operator invocations documented in
  RetrievalEvalRunnerIT (world=tech against the restored frozen stack;
  world=fam against the isolated replica), landing the record in TWO
  commits (rules, then results — the M1-930 pre-registration discipline).
analysis_ref: docs/plan/m1/tick-analysis/two-world-retrieval-instrument.md
blocked_by: [M1-949, M1-950, M1-951, M1-954]
files_scope:
  - docs/measurement/retrieval-eval-two-leg.md
  - docs/measurement/retrieval-eval-baseline.md
  - docs/measurement/README.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production, spec, design, or test change — doc-only committed diff
    plus operator harness runs; mvn verify N/A per the inert-diff rule (the
    M1-930/M1-944 posture; probe: git diff --name-only names only the three
    record paths plus board/frontmatter).
  - >-
    Amending the pre-registered rules T1/G1/N1/D1 (retrieval-eval-baseline.md
    :64-92) — they are cited BY REFERENCE and never restated-with-knowledge
    or refit; the two-leg rules are ADDITIONS, stated as such (binding brief
    constraint; analysis P12). Any edit to the rules block fails this item.
  - >-
    Deciding the width-32 question — the record explicitly names it NOT
    product-decided: the lever re-reads as owner-run deltas on BOTH legs
    (T1 per leg) before any `infochat.chat.semantic-limit` change, which is
    a SEPARATE decision/ticket after this baseline (binding constraint 4;
    analysis P17).
  - >-
    Editing the two landed Results sections of the old baseline record —
    ONE appended redirect note only (corrections stay visible; the sections
    themselves are history).
  - >-
    Fixing anything the readings show, and any fam-leg labeling correction —
    a NEW label defect goes back through a supersedes correction ticket
    (M1-949's freeze) and the leg re-runs; instrument defects go to M1-950.
  - >-
    PROD containers and LIVE fam — each leg targets its own isolated DB
    (the restored frozen test stack; the M1-948 replica) with postgres +
    embedder + translator only; probe: no prod or fam-live URL in the record
    (the M1-944 content-probe pattern).
acceptance:
  - "PRE-REGISTRATION (the M1-930 two-commit discipline; analysis P12): the record lands in TWO commits on this ticket's branch — commit 1 contains the two-leg rules with NO numbers; commit 2 appends both legs' results — probe: `git log --follow docs/measurement/retrieval-eval-two-leg.md` shows the rules commit preceding every results commit."
  - "The rules commit states the two-leg rules as ADDITIONS citing (never restating or refitting) T1/G1/N1/D1: TL1 (both-legs gate) — a PRODUCT-WIDE retrieval claim gates only when the per-leg T1 sign test (floor 6 one-directional discordant queries, same golden set, matching fingerprint) clears on BOTH legs independently, each at its own pinned fingerprint and golden set; a result on one leg alone is a leg-scoped (instance) statement. TL2 (corner-case extrapolation) — a corner case found on one leg (e.g. the tech world's xl-cyber anchor gap) is a HYPOTHESIS about the other leg; it becomes a product-wide decision only after the other leg measures the same mechanism under these rules; until then it is recorded leg-scoped. TL3 (no pooled cross-leg test) — numbers from different legs/fingerprints never enter one sign test; cross-leg differences are descriptive with both fingerprints named. Plus the coverage-comparability clause: within the fam leg, runs are comparable only fingerprint-to-fingerprint AND coverage-pin-to-coverage-pin (world_embedding_coverage, the coverage-confound rule) — probes: `grep -n 'TL1' docs/measurement/retrieval-eval-two-leg.md` (likewise TL2, TL3) returns each verbatim-named rule in the rules commit's section; `grep -n 'retrieval-eval-baseline.md' docs/measurement/retrieval-eval-two-leg.md` returns the T1/G1/N1/D1 by-reference citation; no rule is expressed as a percentage of a survivor set (N1 discipline — reviewer read of the rules block)."
  - "The results commit carries BOTH legs' first readings with full per-leg pins: repo/harness commit; per-leg DB fingerprint (tech: the restored frozen pin, byte-equal to ready=5214;…06ed…927; fam: the replica pin from M1-948's readout); per-leg golden_set_sha256 + active/retired counts (equal to sha256sum of each committed file); per-leg world_embedding_coverage; endpoints/models (embedder, translator); config (threshold, limit); fallback count 0 and en-scope zero-translator-calls (harness-asserted) per leg; run timestamps; the determinism legs per leg (two invocations each, per-query uid lists byte-identical — the docs/spec/llm.md §Determinism boundary contract each leg's double-run restates at its own pinned world) — probe: every pin key resolves with a value in each leg's section."
  - "Per-leg per-class tables with n, capped AND raw recall, MRR, over-return pair for none_expected rows, lexical-only share, smoke/decision-grade marks per rule G1 AT EACH LEG'S OWN n (marks computed per leg, never across legs — analysis P11); movement vs the 2026-08-27 tech readings (if the tech leg rides M1-946's widened set, note the sha change; else the byte-identical-row determinism proof) is DESCRIPTIVE only, cross-set deltas never gated — probes: the tables exist per leg; `grep -n 'DESCRIPTIVE' docs/measurement/retrieval-eval-two-leg.md` returns the movement paragraph; no line phrases a cross-leg or cross-set delta as gated."
  - "WHAT-THE-NUMBERS-DO-NOT-SETTLE at both legs' n (the M1-930 convention): per-leg CIs, unresolvable sub-floor rankings, the anchor-leg confound on cross-language comparisons (anchored text must be read), temporal labels bound to each leg's fingerprint — AND the explicit width-32 statement: the lever is the tech world's best measured move, is NOT product-decided by this record, and re-reads as owner-run deltas on BOTH legs before any config change (binding constraint 4) — probes: `grep -n 'do not settle' returns the section; `grep -n 'width' returns the not-decided statement."
  - "REDIRECT NOTE appended to docs/measurement/retrieval-eval-baseline.md (one new dated section, pure additions): the campaign gating reference moves to the two-leg record for product-wide claims; the old record remains the TECH-LEG reading history and tech-instance regression reference; corrections stay visible — probes: `grep -n 'two-leg' docs/measurement/retrieval-eval-baseline.md` returns the note; git diff over the old record shows pure additions; the two landed Results sections and the rules block are byte-identical."
  - "docs/measurement/README.md gains index rows for retrieval-eval-two-leg.md (and the baseline record if still unindexed — the M1-930 r1 review observation) — probe: `grep -n 'retrieval-eval-two-leg' docs/measurement/README.md` returns the row."
  - "Diff fence (analysis P9/P16): git diff --name-only names exactly the three files_scope paths plus board/frontmatter regen; no prod or fam-live URL, no user-derived data (only post uids, tag/source counts, fingerprints) in the record — probe: git diff --name-only plus a URL/user-data grep over the new record."
test_plan:
  adds: []
  modifies:
    - >-
      docs/measurement/retrieval-eval-baseline.md (AUTHORIZED: ONE appended
      redirect note; every existing line byte-identical) and
      docs/measurement/README.md (AUTHORIZED: index rows appended).
  preserves:
    - all tests currently green on main (no test diff at all).
spec_refs:
  - docs/spec/llm.md §Determinism boundary
  - docs/spec/security.md §Prompt-injection defenses (LLM call sites)
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
    date: 2026-08-30
    verdict: APPROVE
    checks: "SPEC-TRUTHNESS: PASS; SECURITY: PASS; TEST-ADEQUACY: NOT-APPLICABLE (doc-only, no tests added/modified); MAINTAINABILITY: PASS; SCOPE: PASS"
    diff_stats: "5 files, +354/-12 (two-leg record +317 new in two commits rules->results; baseline +18 append-only redirect; README +1 index row; board+frontmatter bookkeeping)"
    notes: >-
      0 rework items, 0 critical/high. Reviewer independently re-verified:
      rules commit 9bc41854 precedes results 2850e608 with no measured
      values in the rules text; results append is pure additions; baseline
      redirect 18+/0-; both golden-set shas byte-equal the recorded pins
      and all four run manifests; label-set counts/max|E|/none_expected
      claims match the committed files. Three candidates falsified with
      citations (fam replica pin under §13 = fixture-label precedent;
      18080/18081 = committed precedent; fam determinism divergence =
      truthful report, out-of-scope to fix, rules-commit text anticipated
      it). One RECOMMENDED-NEW-TICKET recorded under Review observations.
      Verdict on disk: .scratch/tick-review-M1-952-r1.txt (worktree).
overrides: []
aborted_attempts: []
reopens: []
clarity_check:
  checked: 2026-08-30
  result: >-
    No blocking question. Citations verified (retrieval-eval-baseline.md:64-92
    rules block present as cited; README indexes the baseline row already, so
    only the two-leg row is owed). No test parses docs/measurement/**; the
    runs consume the unchanged RetrievalEvalRunnerIT with the M1-949/950
    world seam. M1-930 precedent confirms the two-commit discipline is
    verified on the ticket branch by the review gate, then squashed.
escalation_reason:
---

# M1-952: Two-leg baseline record + pre-registered gating rules

## Context

M1-948..951 deliver the two legs (isolated fam replica + restored tech
world, fam golden set, world-aware harness); nothing committed states how a
two-leg claim is read. This ticket lands the instrument's charter and first
reading: a NEW record (`retrieval-eval-two-leg.md`) whose rules commit
pre-registers the two-leg gating as an ADDITION to T1/G1/N1/D1 (never a
refit — binding), whose results commit records both legs' first readings
with per-leg pins (fingerprint, golden-set sha, the new coverage pin), and
whose redirect note moves the campaign gating reference for product-wide
claims. The width-32 lever stays explicitly undecided until re-read on both
legs. Shared analysis: `analysis_ref:`. Blocked on all three siblings.

## Root cause

Instrument sequencing, not a code defect (the M1-944 posture one link
later): the baseline record's pre-registered rules are scoped to a single
world's instrument; the two-leg methodology exists only in the user's
ruling and this analysis. Verified: no two-leg record exists
(docs/measurement listing); the old record's rules block (:64-92) and both
Results sections must stay byte-identical (append-only discipline);
T1/G1/N1/D1 are pre-registered and MUST NOT be refit (binding brief
constraint — the pre-registration-free-variable lesson). The harness
asserts per-run identity (fingerprint, sha, coverage via M1-950), so every
pin the record states is mechanically backed by the runs' manifests.

## Pitfalls

Numbered per the analysis document; this ticket carries P6 (rule half),
P11, P12, P9 (record half), P16, P17.

- P11: cross-leg comparison temptation — TL3 forbids pooled tests; marks
  computed per leg; cross-leg differences descriptive with both
  fingerprints named.
- P12: pre-registration — rules commit before any mixed number; the
  additions cite T1/G1/N1/D1, never restate them with new knowledge.
- P6 (rule half): the coverage-comparability clause rides the rules commit
  (fingerprint AND coverage for the fam leg) — the confound the shadow
  record exposed must be pre-registered, not discovered.
- P9 (record half): no user-derived data in the record — post uids, tag and
  source counts, fingerprints only; no prod/fam-live URLs.
- P16/P17: no worktree numbers quoted as results (the shadow record is
  context, not a leg); width-32 explicitly not decided (constraint 4).

## Approach

- **Files to touch** — `files_scope`: the new record, the old record's ONE
  appended note, the README index (plus operator-local run artifacts).
- **Steps in implementation order:**
  1. Write + commit the rules section FIRST (TL1/TL2/TL3, the coverage
     clause, the T1/G1/N1/D1 citations, the record's pins skeleton) — no
     numbers (P12).
  2. Operator runs: tech leg (world=tech, restored frozen stack, two
     invocations) and fam leg (world=fam, replica, two invocations);
     capture manifests/queries/scores per leg (P6/P9 record halves).
  3. Author the results commit: per-leg pins, per-class tables with
     per-leg marks, descriptive movement, do-not-settle at both legs' n,
     the width-32 not-decided statement (P11/P17).
  4. Append the redirect note to the old baseline record; README index
     rows; diff fences.
- **Controls to preserve (§10):** the harness's self-checks gate both runs
  (no number from a refusing run); the old record's append-only shape; the
  golden sets consumed read-only; no spec row cites any of this
  (measurement records are evidence, never spec input).
- **Pitfall→mitigation:** P11→TL3 + per-leg marks + the DESCRIPTIVE probe;
  P12→step 1 ordering + the git-log probe; P6→the rules-commit clause +
  per-leg coverage pins; P9→step 3 content discipline + the grep probe;
  P16/P17→the not-decided statement + no shadow-number quoting.

## Definition of done

The record exists in two commits (rules before results); TL1/TL2/TL3 + the
coverage clause are pre-registered as additions citing T1/G1/N1/D1; both
legs' readings are recorded with every pin resolving and both determinism
legs stated; the tables carry per-leg smoke/decision marks and the movement
is descriptive; the do-not-settle section and the width-32 not-decided
statement are present; the old record's redirect note is appended with pure
additions; the README indexes the record; the diff touches nothing outside
`files_scope` and contains no URLs or user-derived data.

## Verification

- P12 → `git log --follow` on the new record: the rules commit precedes
  every results commit; the rules block contains no measured value.
- P11 → the per-leg tables; the TL3 rule; the DESCRIPTIVE grep; no gated
  cross-leg/cross-set phrasing (reviewer read).
- P6 → the coverage clause in the rules commit; `world_embedding_coverage`
  resolving in the fam leg's pins (and the tech leg's, as measured).
- P9 → the content grep (URLs, user-shaped data) over the new record
  returns nothing; git diff --name-only names exactly the three paths.
- P16/P17 → the width-32 not-decided statement; the shadow record cited as
  context only (no shadow number appears as a leg result — reviewer check).
- The determinism legs → both legs' sections name their two run timestamps
  and state per-query uid byte-identity (restated from the runs' outputs,
  the M1-944 convention).
- acceptance items → the named probes (greps, git-log, git-diff shapes,
  per-key pin resolution).

## Out-of-scope

Named in `out_of_scope`: any production/spec/design/test change; amending
T1/G1/N1/D1; deciding width-32 (separate decision after this baseline);
editing the landed Results sections (one appended redirect note only);
fixing what the readings show (own tickets; label defects via supersedes
+ re-run); prod containers / live fam. The old record and the README ARE
modified — authorized in `test_plan.modifies`: pure additions
(engineering-rules §8).

## Pre-flight self-check (author-side)

```bash
python3 scripts/tick-lint.py docs/plan/m1/tick-tickets/M1-952-mixed-baseline-and-gating.md
```

## Review observations (r1)

- docs/measurement/anchor-leg-characterization.md has no index row in
  docs/measurement/README.md (pre-existing; grep returns 0). This
  ticket's acceptance mandated only the two-leg row plus the baseline
  if unindexed (it is indexed), so it was left untouched. Filing an
  index-row ticket is the user's call.
