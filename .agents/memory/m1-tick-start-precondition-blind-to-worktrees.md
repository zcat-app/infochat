---
name: m1-tick-start-precondition-blind-to-worktrees
description: "/m1-tick start's \"no other ticket in-progress\" precondition greps ticket frontmatter in YOUR working tree, so it cannot see a parallel ticket started in a git worktree — check `git worktree list` too."
metadata: 
  type: feedback
---

`/m1-tick start` refuses when another ticket is `in-progress`/`in-review`. The natural check — `grep -l "^status: in-progress" docs/plan/m1/tickets/*.md` — reports "(none)" even when a concurrent session has a ticket live, because that session's `status:` flip lives in **its own worktree's working tree**, not in yours. Ticket files are versioned, so each worktree has an independent uncommitted copy. Hit on M1-645 (2026-07-18): the grep said no ticket in flight while a concurrent `/m1-tick run M1-650` was 5 minutes into a full verify in `.claude/worktrees/M1-650`.

**Why:** the precondition is really "is anyone else working right now," but its proxy is a file-state read that is worktree-local. The reliable tells are process- and repo-level, not file-level: `git worktree list` (a per-ticket worktree existing at all), `ps -ef | grep maven`, and progressively-spawning `testcontainers-ryuk-*` containers in `docker ps` (the signature of a Dev Services / IT suite mid-run). Complement to [[concurrent-session-committed-to-my-branch]], which is the opposite topology — a SHARED single checkout where `git worktree list` shows only one entry and the tell is the branch tip moving instead.

**How to apply:** at `/m1-tick start`, pair the frontmatter grep with `git worktree list`; if a per-ticket worktree exists, read that worktree's copy of its ticket file (`<worktree>/docs/plan/m1/tickets/<id>.md`) for the true status, and diff the two `files_scope` lists for disjointness before proceeding in parallel. Concurrency itself is safe when scopes are disjoint — `scripts/verify-serialized.sh` flocks on `.git/m1-verify.lock`, so a second full verify simply queues (the log's first line says "another verify holds ... waiting") rather than thrashing the host. Also expect STATUS.md churn: your regen renders the other ticket as still `pending`/runnable, and theirs re-renders it on merge; it is self-correcting, not a conflict.
