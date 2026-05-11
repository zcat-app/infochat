# /m1-tick abort

Cancel an in-progress ticket. Roll back the branch and reset the ticket to `pending`.

Preconditions:

- The ticket exists.
- `status: in-progress` OR `status: in-review` (with the most recent review NOT being `APPROVE`). Refuse if the most recent review is `APPROVE` — at that point the ticket is one commit away from done; the right path is `/m1-tick commit` or `/m1-tick escalate ... override`.
- The per-ticket branch is resolvable per the **branch resolution procedure** in [`docs/process/workflow.md`](../../../../docs/process/workflow.md) §"Naming conventions (slug, branch, ticket file)". (The ticket may have been refined since `start`, drifting the slug; the prefix-glob fallback locates it.)
- Refuse if `status: done`. Done tickets are immutable; defects → new ticket.
- Refuse if `status: deferred`. Deferred tickets do not have a branch to abort; use `/m1-tick reopen` if the user wants to resume them.

Steps:

1. Print a confirmation prompt:
   ```
   ABORT M1-NNN: <title>
   Branch m1/M1-NNN-<slug> will be deleted (uncommitted work on it will be lost).
   Ticket frontmatter on `main` will be updated: status reset to pending,
   prior attempt archived under aborted_attempts:. The skill commits the
   archive on main BEFORE deleting the branch.

   Note on refine commits: any `M1-NNN: refine ticket spec ...` commits that
   landed on the branch (from /m1-tick escalate → 1 refine after the branch
   existed) are captured in the archive's `revisions_at_abort:` field for
   audit, but their spec-level edits do NOT propagate to main. If the
   refined acceptance criteria should land, do NOT abort — let the rework
   round complete. Abort is "throw away the attempt"; refine survives only
   inside the per-attempt history.

   Confirm with: yes
   ```
2. Wait for the user's literal `yes`. Any other reply aborts the abort.
3. **Snapshot from the branch** (we are currently on the branch). Read the ticket file's current frontmatter into memory: capture `status`, `reviews:`, `clarity_check:`, `revisions:`, anything else in dynamic fields. The `revisions:` capture preserves any refine snapshots that landed on the branch via `escalate → 1 refine` after `start` had created the branch. This is the data that will become the `aborted_attempts:` archive entry.
4. **Switch to main** with `git checkout main`. This discards any uncommitted working-tree changes on the branch (including the ticket file's in-progress modifications) — which is the intended destructive behavior of abort.
5. **Read the ticket file on main.** This is the older state (typically with `status: pending` from before `start` ran, or `status: in-progress` if a prior abort committed an in-progress reset — either way, the persistent main-branch state).
6. **Build the new frontmatter on main:**
   - Append the snapshot from step 3 to `aborted_attempts:`:
     ```yaml
     aborted_attempts:
       - date: <YYYY-MM-DD>
         prior_status: <captured status from step 3>
         reviews_at_abort: <captured reviews from step 3>
         clarity_check_at_abort: <captured clarity_check from step 3>
         revisions_at_abort: <captured revisions from step 3>
         reason: <user's optional reason from the abort args>
     ```
   - Set `status: pending`.
   - Clear `reviews:`, `clarity_check:` (these belonged to the aborted attempt; they're now archived under `aborted_attempts:`).
   - Update `last_updated` to today.
   - Keep `created`, `blocked_by`, `files_budget`, `files_scope`, `complexity`, `risk`, `round_cap`, `security_relevant`, `migration_touch`, `out_of_scope`, `acceptance`, `test_plan`, `spec_refs`, `decision_refs`, lineage fields untouched.
   - Keep `revisions:` on main exactly as it already is (pre-start refines that landed on main directly are unaffected by this abort). The branch's `revisions:` (which may include during-attempt refines committed on the branch) is captured under `aborted_attempts[].revisions_at_abort:` and does NOT propagate to main — abort is "throw away the attempt", so during-attempt spec edits live only inside the archive.
7. **Commit the archive on main:**
   - Stage only the ticket file: `git add docs/plan/m1/tickets/M1-NNN-<slug>.md`.
   - Commit subject: `M1-NNN: aborted attempt #<N> (reason: <reason-or-no-reason-given>)` where `<N>` is the new length of `aborted_attempts:` after appending. This makes aborts visible in `git log --oneline` and `git bisect`.
8. **Delete the branch:** `git branch -D m1/M1-NNN-<slug>`. (Use `-D` because the branch may have local commits the user is intentionally discarding.)
9. Regenerate `STATUS.md`.
10. Print:
    ```
    M1-NNN aborted. Branch m1/M1-NNN-<slug> deleted.
    Archive committed on main as attempt #<N> under aborted_attempts:.
    Status reset to pending. To resume work, run `/m1-tick start M1-NNN`.
    ```
