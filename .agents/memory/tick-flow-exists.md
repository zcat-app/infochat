---
name: tick-flow-exists
description: The analysis-first /tick flow runs alongside /m1-tick for A/B measurement — tickets in docs/plan/m1/tick-tickets/, reproduction gate + mandatory analyst gate at draft time, one merged review gate with bounded re-review, hurdle-report discipline, files_budget abolished. Never drive tick tickets with m1-tick or vice versa.
metadata.type: process
---

# The /tick flow (analysis-first) runs alongside /m1-tick

Created 2026-08-06 as a parallel track, not a rewrite, so the two flows can
be measured (`scripts/tick-measure.py`; m1 board = baseline). Spec:
`docs/process/tick-workflow.md`; skill `.agents/skills/tick/` (opencode-native,
NO `.claude/` surface). Refined 2026-08-06 (c34dadd9, 4e43f835, 4ffd8dea) —
this entry reflects the post-refinement flow.

Why it exists: brief-driven tickets pushed analysis to implementation time
(deferral chains, M1-694's three redteam rounds for a relocated `sanitize`
call, M1-771/M1-767 at six security re-audit rounds each, and 78 of 133
refines + 34 budget-breach escalations traced to file-count arithmetic).

Deltas vs m1-tick (the parts that make it measurable):

- **Two hard gates at the door, before any code.** (1) §0 reproduction: a
  failing test (or probe with observed output) naming the wrong behavior,
  written and run red BEFORE filing; BLOCKER. (2) Mandatory analysis: the
  fresh-context `analyst` gate (analysis_ref mandatory; `none` is illegal —
  restored 2026-08-06 per operator decision) — root cause verified against
  code, pitfalls P1..Pn, spec-grounded approach, verification mapping each
  pitfall to a test that catches it. Single-ticket decompositions embed the
  analysis in the ticket (`analysis_ref: self`); 2+ ticket problems get a
  shared `tick-analysis/` document. The analyst's threat-model read is
  scaled to the surface (full security.md only when the change touches it).
- `files_budget` abolished; `files_scope` optional and never load-bearing
  (supporting evidence only — `--parallel` requires a different Maven
  module from every in-flight ticket, a build-enforced boundary).
  `out_of_scope` stays semantic and load-bearing.
- Implementation = execution of the contract (reproduction + acceptance);
  the Approach is a route, not a gate. `/tick hurdle` fires on exactly four
  triggers: premise wrong, another module or in-flight ticket's file, a
  spec change, a dropped control of the replaced path (§10). Hurdle report
  carries root cause + falsification note per solution + plain-English
  summary.
- Comment hygiene allowed inside touched classes; renames recorded in the
  commit body `Renames:` trailer; new comment rationale capped at 3 lines
  per call site.
- ONE merged review gate (`tick-reviewer`): spec-truthness + security +
  test-adequacy + maintainability in one plain-English verdict; every
  finding must survive falsification with reachable file:line evidence
  (FALSIFIED-AND-DROPPED entries stay in the record — no hunch-drops);
  critical/high → MANUAL + notify user; medium/low with named fix → REWORK
  in-band.
- **Bounded re-review:** rounds ≥ 2 evaluate ONLY the prior round's REWORK
  ITEMS, dispositioned SATISFIED / NOT-ADDRESSED / DECLINED (NOT-ADDRESSED
  = FAIL, DECLINED = MANUAL), with the round-to-round fix hunks as
  evidence; APPROVE is the expected outcome when all items are satisfied.
  Round-N must-shrink is ADVISORY (never a FAIL; the round cap bounds
  non-convergent rework — matches the m1 flow's 2026-07-19 cutover).
  Per-ticket redteam re-audit loops are gone (the gate IS the security
  review; `/redteam` stays for milestone/release/multi-auditor).
- All per-round artifacts live in `.scratch/`, never `target/` — mvn clean
  wipes target/ between REWORK verify runs.

`scripts/tick-lint.py` refuses vague tickets (5 ground-truth BLOCKER
classes: reproduction present, acceptance verifiable, forward/spec/analysis
refs resolve, status valid; prose-shape checks are WARN) and
`scripts/tick-measure.py` compares flows. Ticket IDs share the M<N>-NNN
sequence across both flows. Engineering-rules §1's "don't improve
comments" is amended for tick-flow tickets only.
