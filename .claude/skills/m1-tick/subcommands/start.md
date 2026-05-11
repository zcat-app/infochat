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

1. **Ticket-clarity pre-flight.** Pre-allocate the verdict file path at `target/m1-tick-clarity-{{ID}}.txt` (the directory `target/` already exists by Maven convention and is excluded from version control). Read `docs/process/clarity-prompt.md` to load the template. Substitute three placeholders only: `{{TICKET_ID}}`, `{{TICKET_FILE_PATH}}` (the repo-relative path to the ticket file the skill resolved from the ID), and `{{VERDICT_FILE_PATH}}` (the path pre-allocated above). No content placeholders are substituted — the clarity subagent loads the ticket and each cited spec file via its own Read tool in fresh context, and runs the spec_refs anchor resolution algorithm itself (the algorithm body lives in step 7 below for the Plan-subagent path, which is the only main-session caller now). Spawn `Agent(subagent_type: "clarity-reviewer", prompt: <substituted>, description: "Clarity pre-flight M1-NNN")`. Foreground.
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
7. If `complexity: high`: read `docs/process/plan-prompt.md`. The Plan template still inlines its inputs (the path-based slimming is applied only to the clarity and review prompts; plan-prompt.md remains content-inlined). Substitute `{{TICKET_ID}}`, the ticket body (read from the ticket file) into the template's content placeholder, and a resolution block built by running the **spec_refs anchor resolution algorithm below** over the ticket's `spec_refs` list. Spawn `Agent(subagent_type: "Plan", prompt: <substituted>, description: "Implementation outline M1-NNN")`. Foreground.
   - If the response begins with `## OUTLINE FAILED`, append the OUTLINE FAILED block to the ticket body and fire `escalate` with `reason: outline-fail`. The branch created in step 6 is left in place (it'll be deleted by `abort` if the user chooses to abandon, or reused after `refine`).
   - Otherwise, append the outline to the ticket body under a new `## Implementation outline` section. The developer (the main conversation) reads it before touching code.

   **`spec_refs` anchor resolution algorithm.** For each `spec_refs` entry of the form `<file-path> §<section-title>`:
   1. Read `<file-path>`.
   2. Find every line beginning with `#`-markers (`#`, `##`, `###`, etc.).
   3. Strip the `#`-markers and surrounding whitespace from each candidate heading.
   4. Lowercase both the candidate and the searched section-title; do a substring match (the searched title must appear as a substring of the candidate, or vice-versa for partial titles).
   5. If exactly one heading matches, the resolution is `FOUND (line N: "<heading>")`.
   6. If zero match, the resolution is `ANCHOR-NOT-FOUND`.
   7. If multiple match, prefer the heading whose depth (count of `#` markers) is closest to the most recently resolved anchor's depth; tie-break by line number ascending. If still tied, the resolution is `AMBIGUOUS (lines: N, M, ...)` — the Plan subagent treats AMBIGUOUS as failure.
8. Regenerate `STATUS.md`.
9. Print:

```
Started M1-NNN on branch m1/M1-NNN-<slug>.
Clarity pre-flight: <PASS | WARN with N warnings>
files_budget: <n>; out_of_scope: <list>; round_cap: <n>.
Implement the ticket per its Definition of Done, then run `mvn verify`,
then `/m1-tick review M1-NNN`.
```

After this point, the main conversation is the developer. Implementation happens in normal Edit/Write/Bash calls. Do NOT spawn a developer-subagent.
