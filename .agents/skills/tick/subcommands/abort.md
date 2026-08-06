# /tick abort

Cancel an in-progress ticket: `/tick abort <id>`.

- Destructive: requires explicit user confirmation (`yes` typed). Deletes
  the branch, rolls back frontmatter to `pending`, records one entry in
  `aborted_attempts:`, regenerates `STATUS-TICK.md`. Uncommitted work is
  lost — say so before asking.
