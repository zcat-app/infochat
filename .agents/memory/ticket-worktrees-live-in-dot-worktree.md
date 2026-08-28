---
name: ticket-worktrees-live-in-dot-worktree
description: "Per-ticket git worktrees for /tick --parallel live under the repo-root .worktree/ directory (git-unversioned, one per ticket, named for the ticket id) — never the home directory, never /tmp, not .claude/worktrees or .opencode/worktrees."
metadata:
  type: project
---

The `/tick start <id> --parallel` worktree location is fixed by rule
(start.md §Step 2): the repo-root `.worktree/` directory, gitignored,
one worktree per ticket, named for the ticket id (`.worktree/M1-936`).

Before the rule existed a session parked a worktree in the user's home
directory (`/home/<user>/infochat-<id>`) — visible clutter outside the
workspace, removable only with `--force` once the session's own
frontmatter edit sat in it. Earlier conventions scattered worktrees
further (`.claude/worktrees/<id>` per harness, a sibling
`infochat-M1-938` in the home folder); `.worktree/` replaces them all.

Related: [[merge-from-ticket-worktree-main-is-elsewhere]] (merge runs
from the primary; the branch stays held until worktree removal),
[[gate-subagent-audits-wrong-tree-on-relative-paths]] (absolute paths
in every gate stub when the audited tree is a worktree),
[[m1-tick-start-precondition-blind-to-worktrees]] (pair frontmatter
greps with `git worktree list`).
