---
name: tick-flow-exists
description: The analysis-first /tick flow runs alongside /m1-tick for A/B measurement — tickets in docs/plan/m1/tick-tickets/, mandatory analyst gate at draft time, one merged review gate, hurdle-report discipline, files_budget abolished. Never drive tick tickets with m1-tick or vice versa.
metadata.type: process
---

# The /tick flow (analysis-first) runs alongside /m1-tick

Created 2026-08-06 as a parallel track, not a rewrite, so the two flows can
be measured (`scripts/tick-measure.py`; m1 board = baseline). Spec:
`docs/process/tick-workflow.md`; skill `.agents/skills/tick/` (opencode-native,
NO `.claude/` surface).

Why it exists: brief-driven tickets pushed analysis to implementation time
(deferral chains, M1-694's three redteam rounds for a relocated `sanitize`
call, M1-771/M1-767 at six security re-audit rounds each, and 78 of 133
refines + 34 budget-breach escalations traced to file-count arithmetic).

Deltas vs m1-tick (the parts that make it measurable):

- Every ticket comes out of `/tick analyze` (fresh-context `analyst` gate;
  `analysis_ref:` mandatory) — root cause verified against code, pitfalls
  P1..Pn, spec-grounded approach, verification mapping each pitfall to a
  test that catches it (negative tests mandatory).
- `files_budget` abolished; `files_scope` optional and parallelism-only
  (no review gate). `out_of_scope` stays semantic and load-bearing.
- Implementation = execution; divergence is a `/tick hurdle` stop-and-report
  (root cause + suggested solutions + options), never drift.
- Comment hygiene allowed inside touched classes; renames recorded in the
  commit body `Renames:` trailer.
- ONE merged review gate (`tick-reviewer`): spec-truthness + security +
  test-adequacy + maintainability in one verdict; every finding must
  survive falsification with reachable file:line evidence (FALSIFIED-AND-
  DROPPED entries stay in the record — no hunch-drops); critical/high →
  MANUAL + notify user; medium/low with named fix → REWORK in-band.
- Round-N must-shrink is load-bearing again (growth beyond named items
  FAILs), and per-ticket redteam re-audit loops are gone (the gate IS the
  security review; `/redteam` stays for milestone/release/multi-auditor).

`scripts/tick-lint.py` refuses vague tickets (7 BLOCKER classes) and
`scripts/tick-measure.py` compares flows. Ticket IDs share the M<N>-NNN
sequence across both flows. Engineering-rules §1's "don't improve
comments" is amended for tick-flow tickets only.
