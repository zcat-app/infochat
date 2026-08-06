# /tick next

List runnable tick tickets: `status: pending` AND every entry in
`blocked_by` has `status: done` in either ticket directory.

Read the frontmatter of `docs/plan/m1/tick-tickets/M1-*.md` only (the
m1 flow's `tickets/` dir is that flow's business). Print the runnable list
ordered by ID, each with title and complexity. If empty, say so and stop —
a blocked list is only useful when runnable tickets exist.

If the user wants a ticket that is blocked, say what it is blocked on and
stop; `/tick` does not start blocked tickets.
