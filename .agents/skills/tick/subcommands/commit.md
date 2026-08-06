# /tick commit

Finalize the per-ticket commit on the branch. Invocation: `/tick commit <id>`.

## Preconditions

- Ticket `status: in-review` with an APPROVE (or OVERRIDE-APPROVE) verdict
  recorded in `reviews:`.
- **Safety re-run.**
  - `complexity: high` OR `risk: high` → re-run the full suite
    (`scripts/verify-serialized.sh` via the `.scratch/` → `target/` capture
    pattern). Proceed only on green.
  - Otherwise → freshness check: the most recent test log
    (`target/tick-test-<ID>-r*.log`) must be newer than every staged file's
    mtime. Older → refuse and require a fresh `mvn verify`.

## The commit

- One commit on the per-ticket branch. Subject `M<N>-NNN: <imperative
  summary>` (≤ 72 chars).
- Body: the Context paragraph from the ticket; `Alternatives considered:`
  trailer if the analyst's rejected options or the implementor's record
  warrant it; `Renames:` trailer with the suggested renames (from the
  implementor's collection + the reviewer's MAINTAINABILITY notes) in the
  form `Old.methodName → newMethodName (file:line)`; `Reviewed-by:` trailer
  with the reviewer verdict line, round, and agent run id (or `NA`).
- Set `status: done`, update `last_updated`, regenerate `STATUS-TICK.md`.
- Print the pointer: `/tick merge <id>` next. Never push.

Never `--amend` a passed commit. Defects found later → new ticket.
