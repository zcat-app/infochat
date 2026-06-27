# /m1-tick commit

Finalize the per-ticket commit on the per-ticket branch after an APPROVE verdict, including a Test-freshness safety check and a `Reviewed-by:` trailer.

Preconditions:

- `status: in-review` and the most recent entry under `reviews:` has `verdict: APPROVE` OR `verdict: OVERRIDE-APPROVE` (the latter is written by the override escalation path).
- The current branch is the per-ticket branch resolved per the **branch resolution procedure** in [`docs/process/workflow.md`](../../../../docs/process/workflow.md) §"Naming conventions (slug, branch, ticket file)".
- Working tree contains the implementation diff to be committed; the ticket file's frontmatter update is the only mutation step 4 below adds.

Steps:

1. **Identify the files about to be committed.** Run `git diff --name-only HEAD` to list modified files in the working tree. Exclude the ticket file itself AND `docs/plan/m1/STATUS.md` (the ticket file's frontmatter mutation in step 4 and the STATUS.md regeneration in step 5 both happen *after* this check; we're checking the freshness of the test result against the source/test code that produced it. STATUS.md is a workflow-artifact regeneration, not a source edit, and so does not invalidate test freshness). Call this set the *commit candidates*.
2. **Test-freshness safety check (user-gated, all tickets).**
   - **Inert-diff short-circuit (no menu).** First classify the *commit candidates* from step 1. If NONE is a testable file (`*.java` / `pom.xml` / `src/**/resources/**`) — a docs / shell-script / `Dockerfile*` / compose-only change — `mvn verify` covers nothing in the diff (SKILL.md §M1 workflow rules "`mvn verify` scope — Java/config/DB only"); do not run it and do not present the menu. Keep the inert-N/A round log as the record and proceed to step 3. The evidence-gathering and menu below apply only when at least one commit candidate is testable.
   - Gather verified evidence — each item checked against disk/git, never assumed:
     1. *Prior log*: does a `target/m1-tick-test-{ID}-r*.log` exist? If yes, record its round token, mtime, and confirm it actually contains `BUILD SUCCESS` (grep the log — a log that exists but is red or truncated counts as "no green log").
     2. *Changes since the log*: the explicit list of *commit candidates* from step 1 (code, test, or configuration files) with mtimes newer than the log. An empty list means skipping the re-run misses nothing; a non-empty list is printed file-by-file in the menu.
     3. *History*: whether the branch was merged or rebased after the log's mtime (`git reflog` entries). A merge/rebase invalidates the log even with no file changes.
   - ALWAYS present a blocking confirmation menu (`AskUserQuestion`) before proceeding — never silently re-run and never silently reuse. The menu question must state explicitly: whether a green log exists (path + round + age), the verified changed-files-since-log list (or "none — skipping misses nothing"), and the merge/rebase result. A green verify stays valid while the tree is unchanged (user rule, M1-272, 2026-06-10). Options:
     - **"Skip re-run — reuse log r<N>"** (recommended when a green log exists, the changed-files list is empty, and no merge/rebase intervened; otherwise its description must spell out exactly what the suite has not seen)
     - **"Re-run full suite now"** (recommended when there is no green log, the list is non-empty, or a merge/rebase intervened)
   - On re-run: use the exact `.scratch/` → `target/` capture command in SKILL.md §M1 workflow rules "Capture `mvn verify` output to a fixed path" (that rule also explains why a direct `target/` redirect loses the log), with round token `rcommit` so the log lands at `target/m1-tick-test-{ID}-rcommit.log`. Refuse to commit if the exit code is non-zero.
   - On approved skip: proceed with the reused log; the user's menu choice is the recorded authorization.
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
