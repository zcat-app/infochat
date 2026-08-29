---
name: tick-analysis-canonical-store-primary
description: "tick-analysis/ is gitignored, so its ONLY durable home is the PRIMARY checkout: analysts write there (absolute path via git-common-dir from worktrees), worktree starts copy in for lint, and worktree removal (merge/abort) must rescue primary-absent files before deleting. Eight analyses were lost to worktree-local writes before this rule (2026-08-29)."
metadata:
  type: project
---

`docs/plan/m1/tick-analysis/` is gitignored — git never sees it, so the
PRIMARY checkout's copy is the only durable one. Three coupled rules
(now in the /tick subcommand procedures, landed 2026-08-29):

1. `/tick analyze` writes the analysis into the PRIMARY's
   `tick-analysis/` always; a session parked in a worktree resolves the
   primary root via `git rev-parse --git-common-dir` (its parent) and
   hands the analyst that ABSOLUTE path (analyze.md).
2. `/tick start` in a worktree copies the ticket's `analysis_ref:` file
   from the primary into the worktree before lint — otherwise
   `ANALYSIS-REF-RESOLVABLE` false-blocks the start (start.md; the
   worktree-lint mirror image of the loss).
3. `/tick merge` / `/tick abort` diff the dying worktree's gitignored
   dirs (`tick-analysis/`, cited `.scratch/` logs) against the primary
   and copy primary-absent files OUT before `git worktree remove`.

Why: before the rule, sessions wrote analyses worktree-locally and the
worktree's removal silently destroyed them. Eight documents confirmed
unrecoverable (whole-filesystem find, 2026-08-29), referenced by
~24 tickets M1-784..M1-878 — listed in the primary's
`docs/plan/m1/tick-analysis/LOST-ANALYSES.md`. Supersedes the narrower
[[worktree-missing-gitignored-analysis]] (kept for the lint symptom).
Related: [[ticket-worktrees-live-in-dot-worktree]],
[[gate-scratch-target-wiped-by-concurrent-verify]] (the same
gitignored-artifacts-are-fragile class).
