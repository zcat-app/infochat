# /m1-tick status

Regenerate `STATUS.md` and print a one-screen summary. The regeneration work runs in a deterministic Python script so the main session never reads N ticket bodies and the cost stays constant as the ticket set grows.

## No-args regenerate path

Run `scripts/regen-status.py 'docs/plan/m1/tickets/M1-*.md' docs/plan/m1/STATUS.md` via the Bash tool. The script reads every ticket's frontmatter, classifies, renders the canonical template, writes `docs/plan/m1/STATUS.md`, and prints a four-line summary on stdout (`STATUS REGENERATED:` / `Counts:` / `Runnable:` / `In flight:`). Print those four lines to the user verbatim.

If the script exits non-zero, surface the stderr and refuse to proceed; the working tree is left unchanged.

## Optional filter flags

These flags print filtered lists to chat without writing STATUS.md. They keep their main-session implementation because their cost is bounded by N tickets per invocation and they don't write a file.

- `/m1-tick status --deferred` — read each `docs/plan/m1/tickets/M1-*.md`, select those with `status: deferred`, sort by `deferred_reason` then `id`, and print as a list.
- `/m1-tick status --escalated` — read each `docs/plan/m1/tickets/M1-*.md`, select those with `status: escalated`, and print each with its most recent reviewer-verdict excerpt from the `reviews:` array.

Neither flag runs the regen script and neither modifies STATUS.md on disk.
