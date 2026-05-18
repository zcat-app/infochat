# /m1-tick start

Begin work on a ticket: clarity pre-flight, status flip, branch creation, optional Plan outline. Invocation: `start <id> [--parallel]`.

Preconditions (refuse and explain if any fail):

- The ticket exists and `status: pending`.
- All `blocked_by` are `status: done`.
- The working tree is clean (`git status` shows no uncommitted changes), unless `--parallel` is set.
- If not `--parallel`: there is no other ticket with `status: in-progress` or `status: in-review`.
- If `--parallel`:
  - The new ticket and every in-flight ticket BOTH declare a non-empty `files_scope`, AND the new ticket's `files_scope` and `out_of_scope` entries are provably disjoint from every in-flight ticket's. Tickets without `files_scope` (purely numeric `files_budget`) cannot start in parallel — the skill cannot mechanically prove disjointness without a path list.
  - Neither the new ticket nor any in-flight ticket has `migration_touch: true`.

Steps:

1. **Ticket-clarity pre-flight.** Pre-allocate the verdict file path at `target/m1-tick-clarity-{{ID}}.txt` (the directory `target/` already exists by Maven convention and is excluded from version control). Read `docs/process/clarity-prompt.md` to load the template. Substitute three placeholders only: `{{TICKET_ID}}`, `{{TICKET_FILE_PATH}}` (the repo-relative path to the ticket file the skill resolved from the ID), and `{{VERDICT_FILE_PATH}}` (the path pre-allocated above). No content placeholders are substituted — the clarity subagent loads the ticket and each cited spec file via its own Read tool in fresh context, and runs the spec_refs anchor resolution algorithm itself (the algorithm body is inlined into clarity-prompt.md and plan-prompt.md; the main session never runs it directly). **PROMPT-SIZE-ALARM** (clarity-reviewer, threshold 15000 bytes): compute the UTF-8 byte length of the substituted prompt string just built. If that length exceeds 15000, print one chat line: `⚠ PROMPT-SIZE-ALARM clarity-reviewer: substituted prompt is <N> bytes (threshold 15000). This may indicate a regression — a placeholder may have been re-inlined that should reference a file by path. Proceeding anyway.` The alarm is warn-only and does not block the spawn; proceed regardless. Spawn `Agent(subagent_type: "clarity-reviewer", prompt: <substituted>, description: "Clarity pre-flight M1-NNN")`. Foreground.
2. Parse the four-line short chat reply for the verdict + integer blocker/warning counts. Read `target/m1-tick-clarity-{{ID}}.txt` (the same `{{VERDICT_FILE_PATH}}` substituted above) from disk to extract the BLOCKERS / WARNINGS strings the subagent wrote there. Append to ticket frontmatter:
   ```yaml
   clarity_check:
     date: <YYYY-MM-DD>
     verdict: <PASS | WARN | FAIL>
     warnings: [<warning-strings>]
     blockers: [<blocker-strings if FAIL>]
   ```
3. Branch on clarity verdict:
   - `PASS` → continue.
   - `WARN` → print warnings; continue.
   - `FAIL` → print blockers; refuse the start; fire `escalate` with `reason: clarity-fail` (this sets status to `escalated` and prints the five-way menu with `clarity_check.blockers:` as the trigger context). Status does NOT pass through `in-progress`.
4. Set the ticket's frontmatter `status: in-progress`. Update `last_updated` to today's date.
5. Compute slug from the title per the canonical rule in [`docs/process/workflow.md`](../../../../docs/process/workflow.md) §"Naming conventions (slug, branch, ticket file)".
6. Branch:
   - **Sequential:** `git checkout -b m1/M1-NNN-<slug>` from `main`.
   - **Parallel:** create a worktree via `Agent(isolation: "worktree")` and run the rest of the flow inside it.
7. If `complexity: high`: pre-allocate the outline sidecar path at `target/m1-tick-outline-{{ID}}.md`. Read `docs/process/plan-prompt.md` to load the template. Substitute three placeholders only: `{{TICKET_ID}}`, `{{TICKET_FILE_PATH}}` (the repo-relative path to the ticket file the skill resolved from the ID), and `{{OUTLINE_FILE_PATH}}` (the path pre-allocated above). No content placeholders are substituted — the Plan subagent loads the ticket and each cited spec file via its own Read tool in fresh context, and runs the spec_refs anchor resolution algorithm itself (the algorithm body is inlined in plan-prompt.md). **PROMPT-SIZE-ALARM** (Plan, threshold 15000 bytes): compute the UTF-8 byte length of the substituted prompt string just built. If that length exceeds 15000, print one chat line: `⚠ PROMPT-SIZE-ALARM Plan: substituted prompt is <N> bytes (threshold 15000). This may indicate a regression — a placeholder may have been re-inlined that should reference a file by path. Proceeding anyway.` The alarm is warn-only and does not block the spawn; proceed regardless. Spawn `Agent(subagent_type: "Plan", prompt: <substituted>, description: "Implementation outline M1-NNN")`. Foreground.
   - If the chat reply begins with `## OUTLINE FAILED`, append the OUTLINE FAILED block to the ticket's `escalations:` frontmatter entry (existing escalation behavior) and fire `escalate` with `reason: outline-fail`. The Plan subagent did not Write the sidecar in this branch; do NOT set an `outline_file:` pointer on the ticket. The branch created in step 6 is left in place (it'll be deleted by `abort` if the user chooses to abandon, or reused after `refine`).
   - Otherwise the chat reply is the three-line success form (`OUTLINE: PASS` / `Outline file: <path>` / `Risks: <integer>`). Parse it to confirm the success verdict and capture the risk count. Set ticket frontmatter `outline_file: target/m1-tick-outline-M1-NNN.md` as a one-line pointer to the sidecar the subagent Wrote. Do NOT append the outline body to the ticket — the sidecar IS the outline. The developer (the main conversation) reads the sidecar before touching code.
8. Regenerate `STATUS.md` via `scripts/regen-status.py 'docs/plan/m1/tickets/M1-*.md' docs/plan/m1/STATUS.md` (Bash tool). The script writes only the destination path; if it exits non-zero, surface stderr and refuse to proceed.
9. Print:

```
Started M1-NNN on branch m1/M1-NNN-<slug>.
Clarity pre-flight: <PASS | WARN with N warnings>
files_budget: <n>; out_of_scope: <list>; round_cap: <n>.
Implement the ticket per its Definition of Done, then run `mvn verify`,
then `/m1-tick review M1-NNN`.
```

After this point, the main conversation is the developer. Implementation happens in normal Edit/Write/Bash calls. Do NOT spawn a developer-subagent.
