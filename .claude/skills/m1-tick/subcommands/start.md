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

1. **Ticket-clarity pre-flight.** Pre-allocate four paths under `target/` (the directory already exists by Maven convention and is excluded from version control): the verdict file at `target/m1-tick-clarity-{{ID}}.txt`, the substituted-prompt path at `target/m1-tick-prompt-clarity-{{ID}}.txt`, the current-state ticket file at `target/m1-tick-current-{{ID}}.md`, and the history file at `target/m1-tick-history-{{ID}}.md`. First split the ticket via Bash:

   ```
   python3 scripts/m1-split-ticket.py \
     <repo-relative path to the ticket file> \
     target/m1-tick-current-{{ID}}.md \
     target/m1-tick-history-{{ID}}.md
   ```

   The split is mechanical (top-level YAML key parse — `escalations:` and `revisions:` blocks go to history, everything else to current). The history file gets a `# No history` sentinel when neither key is present. Then render the prompt via Bash — do NOT Read `docs/process/clarity-prompt.md` into main-session context; the script extracts the fenced template body and substitutes placeholders, then the subagent Reads the rendered file in its own fresh context:

   ```
   python3 scripts/m1-render-prompt.py \
     docs/process/clarity-prompt.md \
     target/m1-tick-prompt-clarity-{{ID}}.txt \
     TICKET_ID={{ID}} \
     CURRENT_TICKET_PATH=target/m1-tick-current-{{ID}}.md \
     HISTORY_PATH=target/m1-tick-history-{{ID}}.md \
     VERDICT_FILE_PATH=target/m1-tick-clarity-{{ID}}.txt
   ```

   The script substitutes four placeholders only: `{{TICKET_ID}}`, `{{CURRENT_TICKET_PATH}}`, `{{HISTORY_PATH}}`, `{{VERDICT_FILE_PATH}}`. No content placeholders are substituted — the clarity subagent loads the current ticket, the history file, and each cited spec file via its own Read tool in fresh context, and runs the spec_refs anchor resolution algorithm itself (the algorithm body is inlined into clarity-prompt.md and plan-prompt.md; the main session never runs it directly). Spawn the subagent with a short stub that points at the rendered file:

   ```
   Agent(
     subagent_type: "clarity-reviewer",
     description: "Clarity pre-flight M1-NNN",
     prompt: "Read target/m1-tick-prompt-clarity-{{ID}}.txt and execute the instructions in that file. Everything you need (ticket path, verdict path, the full procedure) is in that file."
   )
   ```

   Foreground. The render-script approach replaces the previous "Read template → inline substitute → pass as Agent prompt" pattern; the PROMPT-SIZE-ALARM check is no longer needed (the main session no longer holds the template).
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
7. If `complexity: high`: pre-allocate the outline sidecar path at `target/m1-tick-outline-{{ID}}.md` and the substituted-prompt path at `target/m1-tick-prompt-plan-{{ID}}.txt`. The current-state and history files were already written by the splitter call in step 1 against the post-clarity ticket state; re-run the splitter here (idempotent) only if the ticket file has been modified since (e.g., the clarity step recorded `clarity_check:` into frontmatter — see step 2 below; that frontmatter change means the splitter MUST re-run before Plan so the current-state file reflects it):

   ```
   python3 scripts/m1-split-ticket.py \
     <repo-relative path to the ticket file> \
     target/m1-tick-current-{{ID}}.md \
     target/m1-tick-history-{{ID}}.md
   ```

   Then render the prompt via Bash (same pattern as step 1 — the main session never Reads `docs/process/plan-prompt.md`):

   ```
   python3 scripts/m1-render-prompt.py \
     docs/process/plan-prompt.md \
     target/m1-tick-prompt-plan-{{ID}}.txt \
     TICKET_ID={{ID}} \
     CURRENT_TICKET_PATH=target/m1-tick-current-{{ID}}.md \
     HISTORY_PATH=target/m1-tick-history-{{ID}}.md \
     OUTLINE_FILE_PATH=target/m1-tick-outline-{{ID}}.md
   ```

   The script substitutes four placeholders only: `{{TICKET_ID}}`, `{{CURRENT_TICKET_PATH}}`, `{{HISTORY_PATH}}`, `{{OUTLINE_FILE_PATH}}`. No content placeholders are substituted — the Plan subagent loads the current ticket, the history file, and each cited spec file via its own Read tool in fresh context, and runs the spec_refs anchor resolution algorithm itself (the algorithm body is inlined in plan-prompt.md). Spawn the subagent with the stub:

   ```
   Agent(
     subagent_type: "Plan",
     description: "Implementation outline M1-NNN",
     prompt: "Read target/m1-tick-prompt-plan-{{ID}}.txt and execute the instructions in that file. Everything you need (ticket path, outline sidecar path, the full procedure) is in that file."
   )
   ```

   Foreground.
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
