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

0. **Ground on the ticket before any other tool call.** This step runs ALONE in its own turn — do NOT batch it with the clarity render, the status edit, code surveys, Writes, or `mvn`. Those all depend on facts this step establishes; issuing them in the same batch means acting on a *guessed* ticket subject before the Read result is in context (the exact failure that motivated this step).
   - Read the ticket file in full (the whole frontmatter AND body — not just the title).
   - Confirm each `files_scope` path's parent module/package resolves on disk (Glob/Bash `test -e`). A path under a module that doesn't exist is a sign the ticket subject was misread or the ticket is stale — stop and surface it.
   - Present ONE grounding line to the user via a **blocking `AskUserQuestion` call** — never as plain printed text, which can be buried among tool output and silently scrolled past (a printed line failed exactly this way once):
     ```
     GROUNDED M1-NNN · "<verbatim title>" · <N> acceptance items · module/scope: <top-level path(s)> · scope resolves on disk: yes|no
     ```
     The question shows the grounding line and asks the user to confirm it matches the ticket they intended. Options: "Confirmed — proceed" and "Wrong ticket — stop". The flow physically stops until the user answers; this is the user's checkpoint to *verify* the right ticket was read, not a courtesy print they have to trust.
   - On "Wrong ticket — stop" (or any non-confirmation answer): stop, surface what was read vs. what was expected, and wait for instructions. Do not proceed to step 1.
   - Only after the user confirms do you proceed to step 1. Discovery-then-act, never discovery-and-act in one batch. This applies for the rest of the flow too: never bundle a mutating or expensive call (Write/Edit/`mvn verify`) into the same batch as the Read/Grep/Glob calls whose results it depends on.

1. **Ticket-clarity pre-flight.** Pre-allocate two paths under `target/` (the directory already exists by Maven convention and is excluded from version control): the verdict file at `target/m1-tick-clarity-{{ID}}.txt` and the substituted-prompt path at `target/m1-tick-prompt-clarity-{{ID}}.txt`. Render the prompt via Bash — do NOT Read `docs/process/clarity-prompt.md` into main-session context; the script extracts the fenced template body and substitutes placeholders, then the subagent Reads the rendered file in its own fresh context:

   ```
   python3 scripts/m1-render-prompt.py \
     docs/process/clarity-prompt.md \
     target/m1-tick-prompt-clarity-{{ID}}.txt \
     TICKET_ID={{ID}} \
     TICKET_FILE_PATH=<repo-relative path to the ticket file> \
     VERDICT_FILE_PATH=target/m1-tick-clarity-{{ID}}.txt
   ```

   The script substitutes three placeholders only: `{{TICKET_ID}}`, `{{TICKET_FILE_PATH}}`, `{{VERDICT_FILE_PATH}}`. No content placeholders are substituted — the clarity subagent loads the ticket and each cited spec file via its own Read tool in fresh context, and runs the spec_refs anchor resolution algorithm itself (the algorithm body is inlined into clarity-prompt.md; the main session never runs it directly). Spawn the subagent with a short stub that points at the rendered file:

   ```
   Agent(
     subagent_type: "clarity-reviewer",
     description: "Clarity pre-flight M1-NNN",
     prompt: "Read target/m1-tick-prompt-clarity-{{ID}}.txt and execute the instructions in that file. Everything you need (ticket path, verdict path, the full procedure) is in that file."
   )
   ```

   Foreground.
2. Parse the four-line short chat reply for the verdict + integer blocker/warning counts. Read `target/m1-tick-clarity-{{ID}}.txt` (the same `{{VERDICT_FILE_PATH}}` substituted above) from disk to extract the BLOCKERS / WARNINGS strings the subagent wrote there. Write to ticket frontmatter (LATEST entry only — git log carries prior rounds):
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
   - **Session already inside a worktree:** if this session's own cwd is a per-ticket worktree rather than the primary checkout (`git rev-parse --show-toplevel` differs from where `main` is checked out — compare against `git worktree list`), the sequential `git checkout -b` creates the branch *in that worktree*, leaving `main` checked out in the primary. This is fine for `start`/`review`/`commit` (none of them touch `main`), but `/m1-tick merge` must then drive the squash against wherever `main` lives — it is worktree-aware for exactly this case (see [`merge.md`](merge.md) step 2).
   - **Absolute paths inside a worktree:** every Write/Edit absolute path must carry the worktree prefix (`<repo-root>/.claude/worktrees/<id>/...`), never the primary checkout's root — a primary-root absolute path silently writes to the main checkout instead of the branch. Verify with `git rev-parse --show-toplevel` before the first Write. (Hit twice on M1-124, 2026-06-02.)
7. If `complexity: high`: pre-allocate the outline sidecar path at `target/m1-tick-outline-{{ID}}.md` and the substituted-prompt path at `target/m1-tick-prompt-plan-{{ID}}.txt`. Render the prompt via Bash (same pattern as step 1 — the main session never Reads `docs/process/plan-prompt.md`):

   ```
   python3 scripts/m1-render-prompt.py \
     docs/process/plan-prompt.md \
     target/m1-tick-prompt-plan-{{ID}}.txt \
     TICKET_ID={{ID}} \
     TICKET_FILE_PATH=<repo-relative path to the ticket file> \
     OUTLINE_FILE_PATH=target/m1-tick-outline-{{ID}}.md
   ```

   The script substitutes three placeholders only: `{{TICKET_ID}}`, `{{TICKET_FILE_PATH}}`, `{{OUTLINE_FILE_PATH}}`. Spawn the subagent with the stub:

   ```
   Agent(
     subagent_type: "plan-writer",
     description: "Implementation outline M1-NNN",
     prompt: "Ultrathink. Read target/m1-tick-prompt-plan-{{ID}}.txt and execute the instructions in that file. Everything you need (ticket path, outline sidecar path, the full procedure) is in that file. This is a complexity:high ticket — spend the thinking budget on cross-cutting consequences, API-surface audits of every class cited in acceptance items, and ground-truth verification of every claim the outline would make about existing code."
   )
   ```

   Foreground. The `plan-writer` agent is defined at `.claude/agents/plan-writer.md` and has `Read, Grep, Glob, Write` capability — Write is required so the agent can author the outline sidecar directly. The built-in `Plan` subagent type is read-only (Claude Code harness enforces no Write/Edit) and would fail at the sidecar-Write step; do NOT substitute `subagent_type: "Plan"` here.
   - If the chat reply begins with `## OUTLINE FAILED`, fire `escalate` with `reason: outline-fail`. The commit message records the escalation; git log is the audit trail. The plan-writer subagent did not Write the sidecar in this branch; do NOT set an `outline_file:` pointer on the ticket. The branch created in step 6 is left in place (deleted by `abort` if the user chooses to abandon, or reused after `refine`).
   - Otherwise the chat reply is the three-line success form (`OUTLINE: PASS` / `Outline file: <path>` / `Risks: <integer>`). Parse it to confirm the success verdict and capture the risk count. Set ticket frontmatter `outline_file: target/m1-tick-outline-M1-NNN.md` as a one-line pointer to the sidecar the subagent Wrote. Do NOT append the outline body to the ticket — the sidecar IS the outline. The developer (the main conversation) reads the sidecar before touching code.
8. Regenerate `STATUS.md` via `scripts/regen-status.py 'docs/plan/m1/tickets/M1-*.md' docs/plan/m1/STATUS.md` (Bash tool). The script writes only the destination path; if it exits non-zero, surface stderr and refuse to proceed.
9. Print:

```
Started M1-NNN on branch m1/M1-NNN-<slug>.
Clarity pre-flight: <PASS | WARN with N warnings>
files_budget: <n>; out_of_scope: <list>; round_cap: <n>.
Implement the ticket per its §Acceptance, then run `mvn verify`,
then `/m1-tick review M1-NNN`.
```

After this point, the main conversation is the developer. Implementation happens in normal Edit/Write/Bash calls. Do NOT spawn a developer-subagent.
