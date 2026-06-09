# /m1-tick next

List runnable tickets — pending tickets whose `blocked_by` entries are all `done`.

The runnable computation runs inside `scripts/regen-status.py` (the same script `/m1-tick status` uses), so the main session never reads the ticket corpus — at ~300 tickets (~5 MB) a per-file Read sweep would cost more context than the rest of the session. Read only the bounded outputs below.

1. Run `mkdir -p .scratch && python3 scripts/regen-status.py 'docs/plan/m1/tickets/M1-*.md' .scratch/m1-next-status.md` via the Bash tool. The scratch output path is deliberate: `next` is read-only, and regenerating the real `docs/plan/m1/STATUS.md` would dirty the working tree (the `Last updated:` date line) and block `start`'s clean-tree precondition. stdout is the four-line summary (`STATUS REGENERATED:` / `Counts:` / `Runnable:` with the runnable IDs / `In flight:`); stderr carries `WARNING:` lines for pending tickets whose `blocked_by` references are dangling (no such ticket file) or deferred. If the script exits non-zero, surface stderr and stop.
2. Pull the per-ticket detail (title, complexity, risk) for the runnable IDs from the rendered board, not the ticket files: `sed -n '/^## Runnable now/,/^---/p' .scratch/m1-next-status.md`. For each runnable ID, grep its budget from its own file only: `grep -m1 -H '^files_budget:' docs/plan/m1/tickets/M1-NNN-*.md` (bounded by the runnable count, not the corpus).
3. Identify in-progress / in-review IDs from the stdout `In flight:` line, and escalated IDs from `sed -n '/^## Escalated/,/^---/p' .scratch/m1-next-status.md`.
4. Print:

```
Runnable now (N tickets):
  M1-NNN  <title>                     (complexity: <c>, risk: <r>, files_budget: <n>)
  M1-NNN  <title>                     ...

Currently in-progress: <id-or-none>
Currently in-review:   <id-or-none>
Currently escalated:   <id-or-none>

To start: /m1-tick start M1-NNN
```

5. Relay the script's `WARNING:` lines (if any) verbatim below the list. Bad references do not halt the listing — they print alongside so the user can fix them.

If something is `in-progress` or `escalated`, recommend resolving it before starting another ticket (unless `--parallel` is intended).
