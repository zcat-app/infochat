---
name: git-add-stale-pathspec-silent-nostage
description: git add with one nonexistent pathspec stages NOTHING (all-or-nothing); RM in git status means renamed-in-index + modified-in-worktree
metadata: 
  type: feedback
  modified: 2026-07-18T14:36:22.967Z
---

`git add <newPath> <oldPath>` after a `git mv` stages **nothing at all** — git
rejects the entire command on the missing pathspec rather than adding the
paths that do exist. Pairing it with `2>/dev/null` hides the error, and the
following commit then lands the *index* state (the rename of the ORIGINAL
blob), silently dropping the rewrite.

**Why:** git add is all-or-nothing across its pathspecs. `git mv` already
stages the rename, so re-adding the old path is both redundant and fatal.

**How to apply:** after a `git mv` + content rewrite, `git add` the NEW path
only. Never `2>/dev/null` a staging command. Read the two-letter status code
before committing: `RM` = **R**enamed in index, **M**odified in worktree —
that second letter means the worktree content is NOT staged and will not be
committed. Verify with `git show <ref>:<path>` (not `git log --stat`, whose
rename-similarity percentage compares against the wrong side and reported a
misleading `100%` here).

Hit 2026-07-18 refining M1-652; caught by verifying committed content
afterwards, fixed with a corrective commit rather than rewriting a `main`
other sessions may have picked up. See [[concurrent-session-committed-to-my-branch]].
