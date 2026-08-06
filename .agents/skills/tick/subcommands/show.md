# /tick show

Read-only inspection of a ticket: `/tick show <id>`.

Read the ticket file under `docs/plan/m1/tick-tickets/` (or `tickets/` if
it is an m1-flow ticket — say which flow owns it) and print: frontmatter
status/blocked_by/complexity/risk, the acceptance list, the current
`reviews:` verdict, and the `analysis_ref:` pointer. Do not print the full
body unless asked. Nothing is modified.
