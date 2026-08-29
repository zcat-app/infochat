# /tick merge

Squash-merge the per-ticket branch into `main` (one ticket = one commit on
main). Invocation: `/tick merge <id>`. Idempotent.

## Preconditions

- Ticket `status: done` READ FROM THE BRANCH TIP
  (`git show <branch>:docs/plan/m1/tick-tickets/M<N>-NNN-*.md`) — the
  primary's `main` copy still says `pending` at merge time, because the
  done flip rides the ticket branch until the squash lands. Working tree
  clean IN THE TICKET WORKTREE `.worktree/<ID>` (all work committed at
  `/tick commit`); the primary's tree is irrelevant except on the
  primary squash path below. The branch
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

- Staleness check: current `main` must be an ancestor of the branch tip
  (`git merge-base --is-ancestor main <branch>`). If not, main advanced
  after the branch's green verify and that log attests a main that no
  longer exists — cross-ticket semantic collisions survive every
  file-level check and only a full-suite run against CURRENT main catches
  them (tick-workflow §5). Refuse. Recovery: rebase the branch onto fresh
  `main` (the STATUS-board regen is the expected pseudo-conflict), re-run
  the full `scripts/verify-serialized.sh` against the rebased tree, then
  re-run `/tick merge`. A rebase that changed the diff beyond the board
  regen goes through `/tick review` again first.

## Squash-merge path

Run `/tick merge` from the primary repo root — the relative paths below
resolve there. The squash runs where `main` is checked out. From the
primary ONLY when it sits clean on `main`. A primary on another session's
branch (clean or dirty) — or dirty on `main` — is never touched: no
checkout, no stash of its WIP (a stash round-trip races the live session
and `apply --index` does not reliably restore staged-ness). A primary on
a foreign branch: merge from a throwaway worktree instead — `git worktree
add .worktree/tmp-main main` (legal there: `main` is checked out
nowhere) → `git merge --squash <branch>` → `git commit -C <branch-tip>`
(reuses the commit message verbatim, keeping the `Reviewed-by:` /
`Renames:` / `Alternatives considered:` trailers) → `git worktree remove
.worktree/tmp-main` → `git branch -D <branch>` (only after the ticket
worktree is removed — the branch-delete guard is the worktree, not the
primary). A primary DIRTY ON `main`: REFUSE — `git worktree add … main`
is refused by git anyway, and the tree belongs to its live session; the
merge re-runs once that session commits or cleans its WIP.

## Conflicts

- Conflict set is exactly the regenerated `STATUS-TICK.md` → pseudo-conflict
  (deterministic regen): auto-resolve by re-running the regen against the
  post-merge tree, staging it, continuing.
- Anything else → refuse; the user rebases the branch onto fresh `main` and
  re-runs.

## Worktree cleanup (only when `.worktree/<ID>` exists)

The gitignored artifacts of record live in worktrees at death's door:
BEFORE `git worktree remove`, rescue anything the primary lacks —
`docs/plan/m1/tick-analysis/` files first (the canonical store is the
primary; a worktree-only copy is the loss that closed the 2026-08-29 gap),
then any `.scratch/` logs the ticket's frontmatter cites. Copy them to the
primary's same relative paths, THEN `git worktree remove <path> &&
git worktree prune`. Never remove a worktree without diffing its
gitignored dirs against the primary.

Never push. The squash commit on `main` is the merge audit trail.
