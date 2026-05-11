# /m1-tick merge

Squash-merge the per-ticket branch into `main` and delete the branch. Idempotent: re-running on an already-merged ticket cleans up a stale branch (or no-ops if the branch is already gone).

Preconditions:

- The ticket exists and `status: done`. Refuse with `M1-NNN must be 'done' first; run /m1-tick commit M1-NNN` for any other status.
- The working tree is clean (`git status --porcelain` returns empty). Refuse with `working tree dirty; stash or commit first` — `git checkout main` must not clobber uncommitted edits.
- The per-ticket branch is resolvable per the **branch resolution procedure** in [`docs/process/workflow.md`](../../../../docs/process/workflow.md) §"Naming conventions (slug, branch, ticket file)" OR the canonical ticket commit already exists on `main` (idempotent re-run with the branch already deleted). If neither holds, refuse — see step 1's "neither" arm.

Steps:

1. **Idempotency precheck.** Read the ticket file's `title:` field; if the value is YAML-quoted (surrounded by `"..."` or `'...'`, which YAML requires when the title contains a colon, hash, leading dash, or other reserved character), strip the surrounding quotes so the bare string is used. Construct the canonical implementation-commit subject `M1-NNN: <title>` (e.g. `M1-001: Set up two-module Maven build`; for a ticket with `title: "m1-tick: fix STATUS.md order and review diff capture"` the constructed subject is `M1-002: m1-tick: fix STATUS.md order and review diff capture` — without the surrounding quotes). Count commits on `main` whose subject EQUALS this string: `git log main --format=%s | grep -cFx "M1-NNN: <title>"`. (Fixed-string `-F` + whole-line `-x` match. Preliminary auxiliary commits using the workflow's `M1-NNN: ` prefix — `M1-NNN: draft ticket`, `M1-NNN: refine ticket spec ...`, `M1-NNN: aborted attempt #N`, etc. — have different summaries by convention and so do NOT collide with the canonical subject. A loose `^M1-NNN: ` regex would conflate them.) Three arms:
   - **`0` matches AND branch resolves**: the canonical squash-merge path. Continue at step 2.
   - **`0` matches AND branch does NOT resolve**: refuse with `neither branch m1/M1-NNN-* nor a "M1-NNN: <title>" commit on main found; was /m1-tick commit M1-NNN run? (status is "done" but no committed work is locatable)`. STOP. This indicates ticket-state corruption — escalate to the user, do not silently fix.
   - **`1` match**: the ticket is **already merged on main**. If the per-ticket branch resolves, run `git branch -D <branch>` to delete it; print `M1-NNN already merged on main; deleted stale branch <branch>`. If the branch does NOT resolve, print `M1-NNN already merged on main; no branch to delete`. STOP — success exit. (No new commit, no status change, no `STATUS.md` regen needed.)
   - **`≥2` matches**: refuse with `multiple commits on main match "M1-NNN: <title>" exactly; manual cleanup required (a prior partial merge or hand-amend has produced duplicate canonical ticket commits — the skill will not paper over this)`. STOP. Print the matching SHAs (`git log main --format='%H %s' | grep -F "M1-NNN: <title>"`) so the user can decide. Note: this arm fires only on EXACT duplicate canonical subjects, not on the natural ticket-prefix family (drafts, refines, etc.).

2. **Switch to main:** `git checkout main`. The working-tree-clean precondition guarantees this is non-destructive.

3. **Squash-merge the branch:** `git merge --squash <branch>`. This stages the branch's cumulative tree against `main` without creating a merge commit and without advancing `HEAD`. Refuse on conflict (`git merge --squash` exits non-zero with conflict markers in the working tree) — print the conflicting paths and run `git reset --hard HEAD` to clean up; STOP. (NOT `git merge --abort` — squash mode does not set `MERGE_HEAD`, so `--abort` would emit `fatal: There is no merge to abort`. `git reset --hard HEAD` is safe here because step 2's working-tree-clean precondition guarantees nothing is being discarded.) Conflicts at this point indicate `main` advanced between commit and merge (e.g. another ticket landed); the user resolves by rebasing the per-ticket branch onto fresh `main` and re-running `/m1-tick merge`.

4. **Commit with the branch tip's message verbatim:** `git commit -C $(git rev-parse <branch>)`. The `-C` flag reuses the per-ticket branch tip's full commit metadata — message body (Context paragraph, `Alternatives considered:` block, `Reviewed-by:` trailer), author, and author-date — preserving the audit trail produced by `commit` step 3. The committer line is updated to "now" automatically; this is correct (the merge happens now, the authorship was earlier).

5. **Delete the branch:** `git branch -D <branch>`. `-D` (not `-d`) is required because `git merge --squash` does NOT mark the branch as merged from git's POV — git sees the squash result as an unrelated commit on `main`. The branch's content is fully captured in the squash commit; the deletion is safe.

6. Print:
   ```
   M1-NNN merged into main (commit <new-sha>).
   Branch m1/M1-NNN-<slug> deleted.
   `git revert <new-sha>` cleanly undoes this ticket.
   ```

Do NOT push. Do NOT mutate the ticket's `status` (it stays `done`; the squash commit on `main` IS the merge audit trail — `git log main --grep -F "M1-NNN: <title>"` (with `<title>` filled in from the ticket frontmatter) answers "is this merged?" in one command). Do NOT regenerate `STATUS.md` (counts are unchanged; STATUS.md is in-flight-work-focused, not a merge tracker).
