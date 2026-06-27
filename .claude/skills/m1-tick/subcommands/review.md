# /m1-tick review

Spawn the code-reviewer subagent and record its verdict on the current implementation round, with must-shrink enforcement on rework rounds.

Preconditions:

- The ticket exists and `status: in-progress`. (Rework rounds return the ticket to `in-progress` before re-review; the only state from which `review` runs is `in-progress`.)
- The current branch is the per-ticket branch resolved per the **branch resolution procedure** in [`docs/process/workflow.md`](../../../../docs/process/workflow.md) §"Naming conventions (slug, branch, ticket file)" — typically `m1/M1-NNN-<slug>` derived from the current title, with the documented prefix-glob fallback when `refine` has changed the title since `start`.
- `mvn verify` is green for the current tree — either run after the most recent edit and exited zero, OR a prior green log reused via the inert-diff gate (SKILL.md §M1 workflow rules "`mvn verify` scope — Java/config/DB only"; valid only when no `*.java`/`pom.xml`/`src/**/resources/**` changed since the log and no merge/rebase intervened), OR the diff is **fully inert** (none of `*.java`/`pom.xml`/`src/**/resources/**`), in which case `mvn verify` is N/A — it can cover nothing in the diff — and the round log is the inert-diff note; the reviewer then judges the no-regression item on the M1-272 baseline-unchanged basis, not a fresh run. (When a testable file changed and you are uncertain, run it — the reviewer requires a green log covering the current testable surface.)

Steps:

1. Determine the current round number `N` from `reviews:` length + 1. The previous round is `N−1` (only meaningful when `N ≥ 2`).
2. Capture inputs:
   - The full diff: at `review` time the implementation lives in the working tree (no commit on the branch yet — `commit` runs *after* `review`, so any commit-range diff against `main` would be empty and starve the reviewer of context). Capture as working-tree-vs-fork-point: run `git add -N <untracked-files-in-the-working-tree>` first so newly created files appear in the diff (intent-to-add; the `-N` entries are absorbed by the explicit `git add` at `commit` time and require no separate cleanup), then capture via shell redirection — `git diff $(git merge-base main HEAD) > target/m1-tick-review-{{ID}}-r{{CURRENT_ROUND}}.diff` — **BEFORE spawning the subagent**. (NOT `git diff main`: in a worktree pinned behind a moved `main`, diffing against `main` drags every since-landed ticket into the review as phantom changes; the merge-base is the branch's fork point and is identical to `main` whenever `main` has not moved. Observed: M1-096, 2026-05-30.) The redirect keeps the diff bytes out of the main-session transcript entirely; the reviewer Reads the diff from disk rather than from an inlined prompt placeholder. That path is what gets substituted as `{{DIFF_FILE_PATH}}` in step 3.
   - Diff stats for the current round: files touched, net lines added, net lines removed (`git diff $(git merge-base main HEAD) --shortstat`, mirroring the full-diff command above).
   - Diff stats for the previous round (read from `reviews[N−2].diff_stats` in frontmatter when `N ≥ 2`; on round 1 these are unset).
   - Build the negative-space list: if the ticket has a non-empty `files_scope`, take the union of paths matched by those globs minus paths actually present in the diff. If `files_scope` is empty or absent, the negative-space list is the literal sentinel string `(no path-level scope declared — files_budget is purely numeric, no negative-space evaluation applicable)` and the reviewer reports PASS on `NEGATIVE-SPACE-CHECK` by definition.
   - The path of the most recent `mvn verify` log at `target/m1-tick-test-{{ID}}-r{{CURRENT_ROUND}}.log` (the reviewer Reads this path; the skill no longer captures the tail into the prompt).
   - The ticket file path (the reviewer Reads this path; the skill no longer inlines the ticket body into the prompt).
3. Pre-allocate the verdict file path at `target/m1-tick-review-{{ID}}-r{{CURRENT_ROUND}}.txt` and the substituted-prompt path at `target/m1-tick-prompt-review-{{ID}}-r{{CURRENT_ROUND}}.txt`. If the negative-space list from step 2 is multi-line, write it first to `target/m1-tick-negspace-{{ID}}-r{{CURRENT_ROUND}}.txt` so it can be passed via the `@file` form. Render the prompt via Bash — do NOT Read `docs/process/reviewer-prompt.md` into main-session context; the script extracts the fenced template body and substitutes placeholders. The role-based stats placeholders carry the round-N-vs-round-(N−1) must-shrink machinery:

   ```
   python3 scripts/m1-render-prompt.py \
     docs/process/reviewer-prompt.md \
     target/m1-tick-prompt-review-{{ID}}-r{{CURRENT_ROUND}}.txt \
     TICKET_ID={{ID}} \
     TICKET_FILE_PATH=<repo-relative path to the ticket file> \
     DIFF_FILE_PATH=<diff path from step 2> \
     TEST_LOG_PATH=<test-log path from step 2> \
     VERDICT_FILE_PATH=target/m1-tick-review-{{ID}}-r{{CURRENT_ROUND}}.txt \
     BRANCH=<per-ticket branch> \
     CURRENT_ROUND=<N> \
     CURRENT_FILES=<n> CURRENT_ADDED=<n> CURRENT_REMOVED=<n> \
     PREVIOUS_ROUND=<N−1 or "(N/A — round 1)"> \
     PREVIOUS_FILES=<n or sentinel> PREVIOUS_ADDED=<n or sentinel> PREVIOUS_REMOVED=<n or sentinel> \
     NEGATIVE_SPACE_LIST=@target/m1-tick-negspace-{{ID}}-r{{CURRENT_ROUND}}.txt
   ```

   Notes on the per-placeholder values:
   - `{{PREVIOUS_ROUND}}` = `N−1` when `N ≥ 2`, else the literal `(N/A — round 1)`. `{{PREVIOUS_FILES}}` / `{{PREVIOUS_ADDED}}` / `{{PREVIOUS_REMOVED}}` = the prior round's stats from `reviews[N−2].diff_stats` when `N ≥ 2`, else the literal `(N/A — round 1, no previous round)`.
   - `{{NEGATIVE_SPACE_LIST}}` = the list from step 2. Pass via `@file` form for multi-line; for a single-line sentinel (the no-scope-declared case), pass inline.

   Spawn the subagent with a short stub that points at the rendered file:

   ```
   Agent(
     subagent_type: "code-reviewer",
     description: "Review M1-NNN",
     prompt: "Read target/m1-tick-prompt-review-{{ID}}-r{{CURRENT_ROUND}}.txt and execute the instructions in that file. Everything you need (ticket path, diff path, test-log path, verdict path, stats, negative-space list) is in that file."
   )
   ```

   Foreground (the verdict gates the next step). The `code-reviewer` agent is defined at `.claude/agents/code-reviewer.md` (Read/Grep/Glob/Write tool allowlist; model inherited from the main conversation). The render-script approach replaces the previous "Read template → inline substitute → pass as Agent prompt" pattern; the PROMPT-SIZE-ALARM check is no longer needed (the main session no longer holds the template).
4. Parse the three-line short chat reply for the verdict line + integer rework-item count. Read `target/m1-tick-review-{{ID}}-r{{CURRENT_ROUND}}.txt` (the same `{{VERDICT_FILE_PATH}}` substituted above) from disk to extract per-check results (SCOPE-DRIFT-CHECK / TEST-INTEGRITY-CHECK / OUT-OF-SCOPE-CHECK / NEGATIVE-SPACE-CHECK / ACCEPTANCE-CHECK) and the REWORK ITEMS / UNCERTAINTY strings the subagent wrote there.
5. Append to ticket frontmatter under `reviews:`:
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
6. Branch on verdict:

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

7. Update `last_updated`. Regenerate `STATUS.md`.
