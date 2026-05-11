# /m1-tick review

Spawn the code-reviewer subagent and record its verdict on the current implementation round, with must-shrink enforcement on rework rounds.

Preconditions:

- The ticket exists and `status: in-progress`. (Rework rounds return the ticket to `in-progress` before re-review; the only state from which `review` runs is `in-progress`.)
- The current branch is the per-ticket branch resolved per the **branch resolution procedure** in [`docs/process/workflow.md`](../../../../docs/process/workflow.md) §"Naming conventions (slug, branch, ticket file)" — typically `m1/M1-NNN-<slug>` derived from the current title, with the documented prefix-glob fallback when `refine` has changed the title since `start`.
- `mvn verify` was run after the most recent edit and exited zero. (Re-run it if uncertain — the reviewer requires fresh test output.)

Steps:

1. Determine the current round number `N` from `reviews:` length + 1. The previous round is `N−1` (only meaningful when `N ≥ 2`).
2. Capture inputs:
   - The full diff: at `review` time the implementation lives in the working tree (no commit on the branch yet — `commit` runs *after* `review`, so any commit-range diff against `main` would be empty and starve the reviewer of context). Capture as working-tree-vs-main: run `git add -N <untracked-files-in-the-working-tree>` first so newly created files appear in the diff (intent-to-add; the `-N` entries are absorbed by the explicit `git add` at `commit` time and require no separate cleanup), then `git diff main` to produce the diff. **Write the diff to `target/m1-tick-review-{{ID}}-r{{CURRENT_ROUND}}.diff` BEFORE spawning the subagent** — the reviewer Reads the diff from disk rather than from an inlined prompt placeholder. That path is what gets substituted as `{{DIFF_FILE_PATH}}` in step 3.
   - Diff stats for the current round: files touched, net lines added, net lines removed (`git diff main --shortstat`, mirroring the full-diff command above).
   - Diff stats for the previous round (read from `reviews[N−2].diff_stats` in frontmatter when `N ≥ 2`; on round 1 these are unset).
   - Build the negative-space list: if the ticket has a non-empty `files_scope`, take the union of paths matched by those globs minus paths actually present in the diff. If `files_scope` is empty or absent, the negative-space list is the literal sentinel string `(no path-level scope declared — files_budget is purely numeric, no negative-space evaluation applicable)` and the reviewer reports PASS on `NEGATIVE-SPACE-CHECK` by definition.
   - The path of the most recent `mvn verify` log at `target/m1-tick-test-{{ID}}-r{{CURRENT_ROUND}}.log` (the reviewer Reads this path; the skill no longer captures the tail into the prompt).
   - The ticket file path (the reviewer Reads this path; the skill no longer inlines the ticket body into the prompt).
3. Pre-allocate the verdict file path at `target/m1-tick-review-{{ID}}-r{{CURRENT_ROUND}}.txt`. Read `docs/process/reviewer-prompt.md` and substitute the placeholders. The role-based stats placeholders carry the round-N-vs-round-(N−1) must-shrink machinery:
   - `{{TICKET_ID}}` = the ticket ID
   - `{{TICKET_FILE_PATH}}` = the repo-relative path to the ticket file
   - `{{DIFF_FILE_PATH}}` = the path of the diff written in step 2
   - `{{TEST_LOG_PATH}}` = the test-log path from step 2
   - `{{VERDICT_FILE_PATH}}` = the path pre-allocated at the top of this step
   - `{{BRANCH}}` = the per-ticket branch resolved per the workflow's branch resolution procedure
   - `{{CURRENT_ROUND}}` = `N`; `{{CURRENT_FILES}}` / `{{CURRENT_ADDED}}` / `{{CURRENT_REMOVED}}` = the current diff stats from step 2
   - `{{PREVIOUS_ROUND}}` = `N−1` when `N ≥ 2`, else the literal `(N/A — round 1)`. `{{PREVIOUS_FILES}}` / `{{PREVIOUS_ADDED}}` / `{{PREVIOUS_REMOVED}}` = the prior round's stats from frontmatter when `N ≥ 2`, else the literal `(N/A — round 1, no previous round)`.
   - `{{NEGATIVE_SPACE_LIST}}` = the negative-space list from step 2 (or the no-scope-declared sentinel)
4. Spawn `Agent(subagent_type: "code-reviewer", prompt: <substituted>, description: "Review M1-NNN")`. Foreground (the verdict gates the next step). The `code-reviewer` agent is defined at `.claude/agents/code-reviewer.md` (Read/Grep/Glob/Write tool allowlist, opus model).
5. Parse the three-line short chat reply for the verdict line + integer rework-item count. Read `target/m1-tick-review-{{ID}}-r{{CURRENT_ROUND}}.txt` (the same `{{VERDICT_FILE_PATH}}` substituted above) from disk to extract per-check results (SCOPE-DRIFT-CHECK / TEST-INTEGRITY-CHECK / OUT-OF-SCOPE-CHECK / NEGATIVE-SPACE-CHECK / ACCEPTANCE-CHECK) and the REWORK ITEMS / UNCERTAINTY strings the subagent wrote there.
6. Append to ticket frontmatter under `reviews:`:
   ```yaml
   reviews:
     - round: <1|2|3>
       date: <YYYY-MM-DD>
       verdict: <APPROVE|REWORK|MANUAL>
       checks:
         scope_drift: <PASS|FAIL>
         test_integrity: <PASS|FAIL>
         out_of_scope: <PASS|FAIL>
         negative_space: <PASS|WARN>
         acceptance: <PASS|PARTIAL|FAIL>
       diff_stats:
         files: <n>
         added: <n>
         removed: <n>
   ```
7. Branch on verdict:

   - **APPROVE.** Set `status: in-review`. Update `last_updated`. If `negative_space: WARN`, print the negative-space note to chat as informational. Print:
     ```
     M1-NNN: APPROVE (round <r>).
     Run `/m1-tick commit M1-NNN` to finalize.
     ```

   - **REWORK, round 1.** Set `status: in-progress`. Append the rework items to the ticket body under a new `## Round 1 rework` section. Print the rework items in chat. Remind the developer to address only the named items, then re-run `mvn verify`, then `/m1-tick review M1-NNN`.

   - **REWORK, round N (N≥2).**
     - If the verdict's `SCOPE-DRIFT-CHECK: FAIL` reason includes a must-shrink violation (round-N diff grew along all three dimensions vs round-(N−1) without an authorized refactor citation), set `status: escalated` and fire `escalate` with `reason: round-cap`. Must-shrink failures do NOT consume a round-cap allowance — they exit immediately because the rework is no longer convergent. This applies even when `round_cap: 3`.
     - Otherwise, if `round_cap: 3` AND this is round 2: set `status: in-progress` and append a `## Round 2 rework` section. The next review will be round 3 and will be compared to round 2 for must-shrink.
     - Otherwise (round 2 with default `round_cap: 2`, or round 3 with any `round_cap`): set `status: escalated` and fire `escalate` with `reason: round-cap`.

   - **MANUAL.** Set `status: escalated`. Fire `escalate` with `reason: manual-verdict`.

8. Update `last_updated`. Regenerate `STATUS.md`.
