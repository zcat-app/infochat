# /m1-tick status

Regenerate `STATUS.md` and print a one-screen summary. The regeneration work runs in a deterministic Python script so the main session never reads N ticket bodies and the cost stays constant as the ticket set grows.

## No-args regenerate path

Run `scripts/regen-status.py 'docs/plan/m1/tickets/M1-*.md' docs/plan/m1/STATUS.md` via the Bash tool. The script reads every ticket's frontmatter, classifies, renders the canonical template, writes `docs/plan/m1/STATUS.md`, and prints a four-line summary on stdout (`STATUS REGENERATED:` / `Counts:` / `Runnable:` / `In flight:`). Print those four lines to the user verbatim, plus any `WARNING:` lines the script emits on stderr (dangling or deferred `blocked_by` references).

If the script exits non-zero, surface the stderr and refuse to proceed; the working tree is left unchanged.

## Optional filter flags

These flags print filtered lists to chat without writing STATUS.md. Neither may Read the full ticket corpus (~300 files) — scope every read to the matching tickets only.

- `/m1-tick status --deferred` — regenerate to a scratch path (`mkdir -p .scratch && python3 scripts/regen-status.py 'docs/plan/m1/tickets/M1-*.md' .scratch/m1-status-filter.md`) and print the board's §Deferred section verbatim (`sed -n '/^## Deferred/,/^---/p' .scratch/m1-status-filter.md`) — it is already grouped by `deferred_reason` with IDs sorted.
- `/m1-tick status --escalated` — locate escalated tickets via `grep -l '^status: escalated' docs/plan/m1/tickets/M1-*.md`, then Read ONLY those files (bounded by the escalated count, typically 0–2) and print each with its most recent reviewer-verdict excerpt from the `reviews:` array.

Neither flag modifies STATUS.md on disk.
