# /tick show

Read-only inspection of a ticket: `/tick show <id>`.

Read the ticket file under `docs/plan/m1/tick-tickets/` (or `tickets/` if
it is an m1-flow ticket — say which flow owns it) and print: frontmatter
status/blocked_by/complexity/risk, the acceptance list, the current
`reviews:` verdict, and the `analysis_ref:` pointer. Do not print the full
body unless asked. Nothing is modified.

## /tick reopen

Bring a `deferred` ticket back to `pending`: `/tick reopen <id>`.

- Refuse for `abandoned` (terminal — reviving is a fresh deliberate
  decision: `/tick analyze` a new brief) and for any ticket whose
  `deferred_on` is not `done` (say what it still waits on).
- Set `status: pending`, clear `deferred_on`/`deferred_reason`, update
  `last_updated`, regenerate `STATUS-TICK.md`.

## /tick abort

Cancel an in-progress ticket: `/tick abort <id>`.

- Destructive: requires explicit user confirmation (`yes` typed). Deletes
  the branch, rolls back frontmatter to `pending`, records one entry in
  `aborted_attempts:`, regenerates `STATUS-TICK.md`. Uncommitted work is
  lost — say so before asking.
