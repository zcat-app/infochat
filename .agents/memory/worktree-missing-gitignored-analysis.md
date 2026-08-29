---
name: worktree-missing-gitignored-analysis
description: "A /tick worktree lacks the gitignored tick-analysis/ files, so tick-lint's ANALYSIS-REF-RESOLVABLE goes BLOCKER on a ticket that lints clean in the primary. Copy the analysis file into the worktree before lint/review."
metadata:
  type: project
---

`docs/plan/m1/tick-analysis/` is gitignored (private planning artifacts),
so `git worktree add` does not materialize it. `scripts/tick-lint.py`
resolves a ticket's `analysis_ref:` against the working tree — in a
per-ticket worktree the ref misses and lint reports
`ANALYSIS-REF-RESOLVABLE: BLOCKER` for a ticket that lints clean in the
primary checkout (observed M1-936, 2026-08-29).

Fix: copy the named analysis file from the primary into the worktree's
`tick-analysis/` before running lint or spawning the reviewer gate
(stays untracked — gitignored in both trees, no diff pollution). The
[[gate-subagent-audits-wrong-tree-on-relative-paths]] rule already
demands absolute paths for gates; this is the same class of
worktree-vs-primary divergence.
