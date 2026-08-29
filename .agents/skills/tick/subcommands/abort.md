# /tick abort

Cancel an in-progress ticket: `/tick abort <id>`.

- Destructive: requires explicit user confirmation (`yes` typed). Deletes
  the branch, rolls back frontmatter to `pending`, records one entry in
  `aborted_attempts:`, regenerates `STATUS-TICK.md`. Uncommitted work is
  lost — say so before asking.
- When the ticket ran in a `.worktree/<ID>` worktree, run the merge
  subcommand's worktree-cleanup rescue FIRST (copy any primary-absent
  `tick-analysis/` or cited `.scratch/` files to the primary), then
  remove the worktree. Aborted tickets get reopened; their analysis
  surviving the abort is the point.
