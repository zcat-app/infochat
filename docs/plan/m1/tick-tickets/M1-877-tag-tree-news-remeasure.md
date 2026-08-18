---
id: M1-877
title: "Re-measure the news leg on resolved stored tags"
status: done
created: 2026-08-17
last_updated: 2026-08-18
flow: tick
reproduction: >-
  Probe (evidence gap, verified 2026-08-17): docs/measurement/tag-tree-taxonomy.md
  carries NO resolved-stored-tag measurement of the news-distribution leg —
  its FAIL cell (world node share 1.0, record:318) and finding (record:342-361)
  are scored on raw VALIDATED tuples, and the record's own promise stands
  unfulfilled: "A re-run after M1-865 lands would measure the RESOLVED stored
  tag, not the raw validated tuple" (record:388-392). Observed wrong output:
  grep -n 'resolved stored\|stored tag\|re-run' over the record returns only
  the promise itself — no re-run section, no stored-tag cell, no bars. The
  single-node dump guard the record's finding describes (record:355-359)
  therefore remains unverifiable against shipped behavior.
analysis_ref: docs/plan/m1/tick-analysis/news-world-below-regions.md
blocked_by:
  - M1-876
  - M1-866
files_scope:
  - docs/measurement/tag-tree-taxonomy.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    ANY production code, prompt text, migration, or docs/spec/** edit — this
    ticket produces evidence only (the M1-864 shape); the fix it measures is
    M1-876 (resolver) + M1-866 (seed data), both landed before this ticket
    starts.
  - >-
    COMMITTING .bench/ working data (gitignored by design) — only the
    promoted record section at docs/measurement/tag-tree-taxonomy.md is
    committed.
  - >-
    RE-RUNNING ANY OTHER LEG — the frozen-leg campaign (498 calls) and the
    flat-vocabulary campaign are closed; this ticket re-runs the
    news-expected fixtures only (18 leaf-news + 7 news-geo + xft-cop = 26
    fixtures x 3 resamples = 78 calls, the record's corpus accounting,
    record:342-346) and re-scores ONLY the news-distribution leg on the new
    resolved-stored-tag basis plus the world-co-tag cell.
  - >-
    M1-874's leaf-stability denominator one-liner — that record-side edit is
    M1-874's own ticket (unmerged branch m1/M1-874-leaf-stability-denominator,
    6d5b9ab6); this ticket's record edits are the re-run section and the
    do-NOT-settle re-point, which are disjoint regions. Re-read M1-874's
    actual diff before editing the record file.
  - >-
    RETROACTIVELY EDITING the pre-registered reading — the original FAIL
    cell, finding, and bar text stay verbatim as the historical record (the
    record's never-silent rule); the re-run is a NEW section.
acceptance:
  - "docs/measurement/tag-tree-taxonomy.md gains a re-run section that measures the news-distribution leg on RESOLVED stored tags — the stored leaf of the post-fix deterministic resolver, not the raw validated tuple — reporting the max single-News-leaf share of News-attributed output on distinct stored tuples AND a world-co-tag cell (world present in zero regional stored tuples; world stored only in the fixtures whose entire proposal is world — the 12 world-only tuples of the original leg, record:349) — probe: grep -n 're-run\|stored' docs/measurement/tag-tree-taxonomy.md shows the section and both cells (spec: docs/spec/llm.md §SPI shape as amended by M1-869; analysis P9, P10)."
  - "The re-run's bars are pre-registered BEFORE any arm runs and the order is git-log-provable: (a) news-distribution <= 0.50 max single-News-leaf share on distinct RESOLVED stored tuples (the original bar, now on stored tags — record:69-71, :318); (b) the world-co-tag cell at zero regional stored tuples carrying world (the fallback rule's observable consequence — M1-876 acceptance 1) — probe: git log --follow docs/measurement/tag-tree-taxonomy.md shows the thresholds commit predating the results commit (the record's own pre-registration discipline, record:15-21; analysis P9)."
  - "Harness fidelity: the track-a harness gains a fallback-aware port of the post-fix resolver (M1-876's depth → fallback → emission order; world marked per the V84 seed) and verify-against-java.py runs GREEN over the resolver probes BEFORE the stored-tag scoring is trusted — probe: the record's method section names the verify run and the resolver-port note (the record:206-208 discipline; analysis P10)."
  - "The original FAIL reading is preserved verbatim (record:318, :342-361) and the 'What these numbers do NOT settle' entry (record:388-392) is re-pointed at the new re-run section — no threshold, verdict, or pre-registered text changes retroactively — probe: grep -n 'world node share = 1.0\|must rank world BELOW the region leaves' docs/measurement/tag-tree-taxonomy.md returns the verbatim original reading; grep -n 're-run' docs/measurement/tag-tree-taxonomy.md shows the do-NOT-settle entry naming the new section (analysis P9)."
  - "Pins carry the M1-864 discipline: leaf-list snapshot sha256 (ORDER BY name), repo commit, model identity (gemma-4-26B-A4B-it-UD-Q6_K_XL served from /home/infochat/.local/share/docker/volumes/infochat-llamacpp-models/_data/ — NOT the stale track-a arms.json path), llama.cpp b10221, prompt-file shas, and the fallback designation of world stated in the re-run section — probe: the re-run section's pins table (record:370-384 shape; analysis P9, P10)."
  - "Scored-on-PROPOSED stays binding for every existing leg: the stored-tag cell is ADDITIVE; the injection/AI-policy legs keep their proposed-tags scoring basis untouched — probe: grep -n 'Scored on PROPOSED' docs/measurement/tag-tree-taxonomy.md returns the method sentence unchanged (record:212-214); the re-run section states the stored-tag basis applies to the new news-leg cells only (analysis P10; the record's P3 discipline)."
  - "mvn verify from repo root is green (evidence-only ticket; engineering-rules §5)."
test_plan:
  adds: []
  modifies: []
  preserves:
    - all tests currently green on main
  notes:
    - >-
      Evidence-only: the campaign harness lives under .bench/ (gitignored)
      and the promoted record section is the single committed artifact (the
      M1-864 shape). No JUnit surface to add; mvn verify covers the
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
    date: 2026-08-18
    verdict: OVERRIDE-APPROVE
    checks: "SPEC-TRUTHNESS PASS, SECURITY PASS, TEST-ADEQUACY FAIL, MAINTAINABILITY PASS, SCOPE PASS"
    diff_stats: "3 files changed, 88 insertions(+), 12 deletions(-)"
    findings: "Original MANUAL finding accepted by explicit user override: the fallback-aware resolver probe and scoring artifacts remain ignored local evidence; the committed record is approved as an evidence-only exception."
    verdict_file: .scratch/tick-review-M1-877-r1.txt
overrides:
  - date: 2026-08-18
    arm: override
    justification: "I approve that exception: the committed record may publish results while the ignored .bench harness and scoring data remain local."
    finding: "The clean-checkout reproducibility requirement for the resolver probe and stored-tag scoring artifacts is waived for this evidence-only ticket."
aborted_attempts: []
reopens: []
clarity_check: {}
escalation_reason:
---

# M1-877: re-measure the news leg on resolved stored tags

## Context

The M1-864 record measured the news-distribution FAIL on raw validated
tuples (world share 1.0, record:318) and promised the follow-up this ticket
delivers: "A re-run after M1-865 lands would measure the RESOLVED stored
tag, not the raw validated tuple" (record:388-392). The resolver fix
(M1-876) and the seed data (M1-866, world marked fallback via M1-878) now
exist as the measured subjects; this ticket runs the news-expected corpus
through the harness with a fallback-aware resolver port, re-scores the leg
on RESOLVED STORED tags under pre-registered bars, and lands the re-run
section in the record. It is NOT folded into M1-874 (that ticket is the
leaf-stability denominator one-liner; this one is a new measured leg with a
different blocking structure — see the analysis's Decomposition). Shared
context: `analysis_ref:` (analysis doc, Pitfalls P9–P11).

## Root cause

Not a code defect — an evidence gap the record itself named. The original
leg could not be scored on stored tags because the resolver did not exist in
the harness (the harness ports render/parse/validate only; resolution was
M1-865's Java). Verified: the record's method section names only the three
track-a ports (record:196-207) and the promise sits unfulfilled at
record:388-392. The re-run adds the fourth port (the post-fix resolver,
fallback-aware) and re-measures the FAILING leg on the new basis.

## Pitfalls

- P9: record discipline — bars pre-registered before the arm runs,
  git-log-provable; the original FAIL text never retroactively edited; the
  re-run is a new section; M1-874's edit and this section are disjoint
  regions in the same file (re-read M1-874's diff first — its body is
  unreadable in this checkout, an assumption the analysis flags).
- P10: harness fidelity — verify-against-java green over the resolver probes
  before scoring; the stored-tag leg is ADDITIVE, scored-on-PROPOSED stays
  binding for every existing leg.
- P11: blocking structure — blocked_by M1-876 AND M1-866: a re-run against
  the pre-seed tree measures identity passthrough, i.e. nothing.

## Approach

- **Files to touch:** `docs/measurement/tag-tree-taxonomy.md` (the committed
  artifact); the harness additions live under `.bench/tag-tree-taxonomy/`
  (gitignored).
- **Steps, in order:**
  1. Register the two bars (news-distribution ≤ 0.50 on distinct RESOLVED
     stored tuples; world-co-tag cell at zero regional stored tuples) in
     their own pre-results commit (P9).
  2. Port the post-fix resolver into the track-a harness (depth → fallback →
     emission order; world marked fallback per the V84 seed) and extend
     verify-against-java.py with resolver probes; run it GREEN (P10).
  3. Run the news-expected corpus (26 fixtures × 3 resamples = 78 calls,
     the record's corpus accounting, record:342-346) through the existing
     render/parse/validate chain + the new resolve step, in the run-batch.sh
     session discipline (one setsid'd session, ABORT on UNREACHABLE — the
     record's P4 discipline, record:209-211).
  4. Score the leg on RESOLVED stored tags; write the re-run section (cells,
     method note, pins incl. the world fallback designation).
  5. Re-point the do-NOT-settle entry (record:388-392) at the section;
     leave the original FAIL reading verbatim (P9).
- **Controls to preserve (engineering-rules §10):** none in production code
  (evidence-only); the record's own controls travel — the never-silent
  pre-registration rule, the scored-on-PROPOSED basis for every existing
  leg, the pins discipline, the UNREACHABLE-abort runner.
- **Pitfall→mitigation:** P9→steps 1/4/5; P10→step 2 + acceptance 6; P11→
  `blocked_by` + the analysis's ordering note.

## Definition of done

Every acceptance item holds: the re-run section exists with both cells
(≤ 0.50 max share on distinct stored tuples; world in zero regional stored
tuples); the bars commit predates the results commit; the resolver port is
verify-against-java GREEN; the original FAIL reading is verbatim and the
do-NOT-settle entry re-points at the section; the pins table carries the
M1-864 discipline plus the fallback designation; scored-on-PROPOSED remains
binding for every existing leg; `mvn verify` green.

## Verification

- P9 → acceptance 2 (`git log --follow` order) + acceptance 4 (verbatim
  FAIL text; re-pointed entry) + out_of_scope naming M1-874's disjoint edit.
- P10 → acceptance 3 (verify-against-java green over the resolver probes,
  named in the record's method) + acceptance 6 (scored-on-PROPOSED binding
  for existing legs).
- P11 → `blocked_by: [M1-876, M1-866]` (both exist and must be done before
  start) — a pre-seed run would measure nothing new.
- acceptance 1 → grep 're-run\|stored' on the record shows the section and
  both cells.
- acceptance 5 → the pins table probe (leaf sha, repo commit, model path,
  llama.cpp build, prompt shas, world's fallback designation).
- Failure mode → the world-co-tag cell itself: a resolver port that drops
  the fallback rule reproduces the original world-first co-tag on regional
  fixtures and fails cell (b) — the cell cannot pass on a vacuous port.
- acceptance 7 → `mvn verify` exit 0.

## Out-of-scope

See `out_of_scope:` — no production code/prompt/migration/spec edits, no
committed .bench data, no other leg re-run (the frozen-leg campaign is
closed), M1-874's one-liner stays M1-874's, and no retroactive edit of the
pre-registered reading. The single authorized record edit class is the new
re-run section + the do-NOT-settle re-point.

## Census

Not class-scoped: one record file, one harness port, one measured leg.
