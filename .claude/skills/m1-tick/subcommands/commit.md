# /m1-tick commit

Finalize the per-ticket commit on the per-ticket branch after an APPROVE verdict, including a Test-freshness safety check and a `Reviewed-by:` trailer.

Preconditions:

- `status: in-review` and the most recent entry under `reviews:` has `verdict: APPROVE` OR `verdict: OVERRIDE-APPROVE` (the latter is written by the override escalation path).
- The current branch is the per-ticket branch resolved per the **branch resolution procedure** in [`docs/process/workflow.md`](../../../../docs/process/workflow.md) §"Naming conventions (slug, branch, ticket file)".
- Working tree contains the implementation diff to be committed; the ticket file's frontmatter update is the only mutation step 4 below adds.

Steps:

1. **Identify the files about to be committed.** Run `git diff --name-only HEAD` to list modified files in the working tree. Exclude the ticket file itself AND `docs/plan/m1/STATUS.md` (the ticket file's frontmatter mutation in step 4 and the STATUS.md regeneration in step 5 both happen *after* this check; we're checking the freshness of the test result against the source/test code that produced it. STATUS.md is a workflow-artifact regeneration, not a source edit, and so does not invalidate test freshness). Call this set the *commit candidates*.
2. **Test-freshness safety check.**
   - For tickets with `complexity: high` OR `risk: high`: re-run `mvn verify` from the repo root using the same `.scratch/` → `target/` copy pattern the implementation phase uses (see SKILL.md §M1 workflow rules "Capture `mvn verify` output to a fixed path"). The exact form is `mkdir -p .scratch && mvn -B clean verify > .scratch/m1-tick-test-{ID}-rcommit.log 2>&1 ; ec=$? ; mkdir -p target && cp .scratch/m1-tick-test-{ID}-rcommit.log target/m1-tick-test-{ID}-rcommit.log ; exit $ec` — writing directly to `target/m1-tick-test-{ID}-rcommit.log` loses the build output because the parent-module `mvn-clean-plugin` deletes `<repo-root>/target/` early in the run (the open redirect fd becomes orphaned). Refuse to commit if the exit code is non-zero. Persist the log at `target/m1-tick-test-{ID}-rcommit.log`.
   - For all other tickets: locate the most recent `target/m1-tick-test-{ID}-r*.log`. Read its mtime. Compute the latest mtime among the *commit candidates* from step 1. If the test log is older than any commit candidate, refuse and tell the user to re-run `mvn verify` (the test result is stale relative to the code about to be committed).
3. Build the commit message. Read the ticket file's `title:` field; if the value is YAML-quoted (surrounded by `"..."` or `'...'`, which YAML requires when the title contains a colon, hash, leading dash, or other reserved character), strip the surrounding quotes — the bare string is the imperative summary used in the subject line.
   ```
   M1-NNN: <ticket title (quotes stripped)>

   <Context paragraph from the ticket body, wrapped at 72 chars>

   <If the ticket body has an "Alternatives considered" section with
   non-empty alternatives chosen against, transcribe them here:>
   Alternatives considered:
     - <alt 1>: <reason>
     - <alt 2>: <reason>

   Reviewed-by: code-reviewer (VERDICT: <APPROVE|OVERRIDE-APPROVE>; round <r>; agent run: <id-or-NA>)
   ```
4. Set ticket frontmatter `status: done`. Update `last_updated`. (This mutation produces a working-tree modification on the ticket file in addition to the commit candidates from step 1.)
5. Regenerate `STATUS.md` via `scripts/regen-status.py 'docs/plan/m1/tickets/M1-*.md' docs/plan/m1/STATUS.md` (Bash tool). Run this *before* `git commit` so the regenerated board reflects the new `done` state and lands in the same commit as the implementation; performing it after the commit would leave STATUS.md as an uncommitted modification blocking the next ticket's clean-tree precondition. The script writes only the destination path; if it exits non-zero, surface stderr and refuse to proceed.
6. Stage explicitly: `git add` each commit candidate from step 1, plus the ticket file, plus `docs/plan/m1/STATUS.md`. Never `git add -A`.
7. `git commit -m "<heredoc message>"` — single commit.
8. If the ticket has `security_relevant: true`, remind the user that [`/redteam M1-NNN`](../../redteam/SKILL.md) is recommended before merging.
9. Print:
   ```
   M1-NNN committed on branch m1/M1-NNN-<slug>.
   Next step: `/m1-tick merge M1-NNN` to squash-merge into main.
   (Push remains your call; the skill never pushes. After merge,
   `git revert <sha>` cleanly undoes this ticket on main.)
   ```

Do NOT push. Do NOT amend the commit if a defect is found later — file a new ticket. Merge is a separate explicit step (`/m1-tick merge`); never auto-merge from `commit`.
