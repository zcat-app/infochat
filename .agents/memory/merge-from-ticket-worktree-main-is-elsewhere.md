---
name: merge-from-ticket-worktree-main-is-elsewhere
description: "A /tick merge driven from a ticket worktree hits two git walls: main is checked out in the primary (the worktree cannot checkout main), and the ticket branch cannot be -D'd while the worktree holds it. Run the squash-merge from the primary checkout; the branch stays held until the worktree is removed."
metadata:
  type: project
---

`/tick merge` is normally driven from the primary checkout, but a
session parked in a ticket worktree (the opencode/claude worktree
convention) hits two git guards in the procedure's squash path:

1. `git checkout main` → `fatal: 'main' is already used by worktree at
   '<primary>'` — main is always checked out in the primary worktree,
   so the squash-merge MUST run from the primary (`git -C <primary>
   merge --squash <branch> && git -C <primary> commit -C <branch-tip>`).
   The branch and its tip sha are shared across worktrees, so this is
   safe; verify the primary's tree is clean first.
2. `git branch -D <branch>` → `error: cannot delete branch ... used by
   worktree at '<ticket-worktree>'` — git refuses to delete a branch any
   worktree checks out. This is the accepted end state: merged ticket
   branches stay held by their worktrees until the worktree is removed
   (`git worktree remove` + prune; observed for M1-776/779/795/796/
   804/813/814/836/844/847/850/851). Do not try to force it.

Also on this path (observed M1-842, 2026-08-15): the staleness check
fires when a sibling ticket merged between the branch's verify and the
merge — recovery is `git rebase main`, which rebased cleanly here (the
STATUS-TICK.md regen is deterministic), followed by a FULL
`scripts/verify-serialized.sh` against the rebased tree before re-
running the merge. And the `cp` to `target/` after a verify needs
`mkdir -p target` — `mvn clean` has already deleted it (the
.scratch-redirect pattern is load-bearing).
