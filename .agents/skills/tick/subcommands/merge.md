# /tick merge

Squash-merge the per-ticket branch into `main` (one ticket = one commit on
main). Invocation: `/tick merge <id>`. Idempotent.

## Preconditions

- Ticket `status: done`; working tree clean; the branch
  `m<N>/M<N>-NNN-<slug>` resolves per the branch-resolution procedure of
  the m1 flow (same naming conventions) OR the canonical implementation
  commit (subject exactly `M<N>-NNN: <title>`) already exists on `main`
  (idempotent-cleanup arm).
- Idempotency precheck: count commits on `main` whose subject EQUALS
  `M<N>-NNN: <title>` (`git log main --format=%s | grep -cFx "<subject>"`):
  - 0 AND branch resolves → squash-merge.
  - 0 AND branch missing → refuse: ticket says done but no work locatable.
  - 1 → already merged; delete the stale branch if it resolves; done.
  - ≥2 → refuse: duplicate canonical commits (prior partial merge).

## Squash-merge path

`git checkout main` → `git merge --squash <branch>` →
`git commit -C <branch-tip>` (reuses the commit message verbatim, keeping
the `Reviewed-by:` / `Renames:` / `Alternatives considered:` trailers) →
`git branch -D <branch>`.

## Conflicts

- Conflict set is exactly the regenerated `STATUS-TICK.md` → pseudo-conflict
  (deterministic regen): auto-resolve by re-running the regen against the
  post-merge tree, staging it, continuing.
- Anything else → refuse; the user rebases the branch onto fresh `main` and
  re-runs.

Never push. The squash commit on `main` is the merge audit trail.
