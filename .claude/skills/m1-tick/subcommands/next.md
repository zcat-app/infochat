# /m1-tick next

List runnable tickets — pending tickets whose `blocked_by` entries are all `done`.

1. Read every file under `docs/plan/m1/tickets/M1-*.md`.
2. Parse YAML frontmatter from each.
3. A ticket is **runnable** iff `status: pending` AND every entry in `blocked_by` resolves to a ticket with `status: done`.
4. Sort runnable tickets by ID ascending.
5. **Validate blocked_by references.** For every `blocked_by` entry that does not resolve to an existing ticket file, surface a warning line in the output: `WARNING: M1-NNN references unknown blocker M1-XXX (no such ticket file)`. Likewise, surface `WARNING: M1-NNN's blocker M1-XXX is deferred (status: deferred); this ticket will stay unrunnable until the blocker is reopened and completed`. Bad references do not halt the listing — they print alongside the runnable list so the user can fix them.
6. Print:

```
Runnable now (N tickets):
  M1-NNN  <title>                     (complexity: <c>, risk: <r>, files_budget: <n>)
  M1-NNN  <title>                     ...

Currently in-progress: <id-or-none>
Currently in-review:   <id-or-none>
Currently escalated:   <id-or-none>

To start: /m1-tick start M1-NNN
```

If something is `in-progress` or `escalated`, recommend resolving it before starting another ticket (unless `--parallel` is intended).
